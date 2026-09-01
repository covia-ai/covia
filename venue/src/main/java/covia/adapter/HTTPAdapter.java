package covia.adapter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Outbound HTTP for agents and workflows, SSRF-guarded.
 *
 * <p><b>SSRF guard.</b> Every URL — the first request and every redirect hop,
 * and the URLs other outbound adapters submit through {@link #requireSafeUrl}
 * — must be http(s), must resolve, and must not resolve to a loopback,
 * site-local, link-local or wildcard address unless its host is on the allow
 * list. The block list is checked first and always wins. Both lists come from
 * the operator ({@code adapters.http.allowedHosts} / {@code blockedHosts}) or
 * from an embedding host ({@link #addAllowedHost}, {@link #addBlockedHost}).
 * Resolution happens at validation time; the client resolves again when it
 * connects, so a host whose answer changes between the two is not caught.</p>
 *
 * <p><b>User-Agent (#422).</b> A caller who supplies no {@code User-Agent}
 * gets the venue's — {@code Covia/<version> (+https://covia.ai)} unless the
 * operator sets {@code adapters.http.userAgent} — because public APIs refuse
 * anonymous clients. An explicit caller value always wins, whatever its case.</p>
 *
 * <p><b>Redirects (#423).</b> The client never follows redirects itself; the
 * adapter does, so that every hop passes the guard. A chain is bounded by
 * {@code adapters.http.maxRedirects} (default {@value #DEFAULT_MAX_REDIRECTS})
 * and refused on a loop; 303 re-requests with GET, 301/302 do so for POST, and
 * 307/308 keep the method and body. Credentials — {@code Authorization},
 * {@code Cookie}, every {@code secretHeaders} value and the bearer — are
 * dropped on a change of origin (scheme, host or port) and kept within one.
 * The result names the final {@code url} and lists the {@code redirects}
 * taken; {@code followRedirects: false} returns the redirect response itself.</p>
 */
public class HTTPAdapter extends AAdapter {

	public static final Logger log = LoggerFactory.getLogger(HTTPAdapter.class);

	// Operator configuration keys (adapters.http.*)
	static final AString K_USER_AGENT = Strings.intern("userAgent");
	static final AString K_ALLOWED_HOSTS = Strings.intern("allowedHosts");
	static final AString K_BLOCKED_HOSTS = Strings.intern("blockedHosts");
	static final AString K_MAX_REDIRECTS = Strings.intern("maxRedirects");

	/** Redirect hops followed when the operator sets no limit. */
	public static final int DEFAULT_MAX_REDIRECTS = 5;
	/** The most hops an operator may allow. */
	public static final int MAX_REDIRECTS_CEILING = 20;

	private static final String USER_AGENT_HEADER = "User-Agent";
	/** Header names that never cross an origin change, whoever set them. */
	private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "proxy-authorization", "cookie");
	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

	// Instance-level HttpClient for reuse across requests. Redirects are
	// followed by the adapter, never by the client, so every hop is guarded.
	private final HttpClient httpClient;

	/** Hosts an embedding host allowed programmatically (bypass SSRF checks). */
	private final Set<String> allowList = new HashSet<>();

	/** Hosts an embedding host blocked programmatically (checked before allowList). */
	private final Set<String> blockList = new HashSet<>();

	/** Operator lists from {@code adapters.http}, replaced on every {@link #configure}. */
	private volatile Set<String> configuredAllow = Set.of();
	private volatile Set<String> configuredBlock = Set.of();

	private volatile String userAgent = defaultUserAgent();
	private volatile int maxRedirects = DEFAULT_MAX_REDIRECTS;

	/**
	 * Constructor initializes the HttpClient with optimal settings
	 */
	public HTTPAdapter() {
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	/** The User-Agent sent when a caller supplies none: the venue's own. */
	public static String defaultUserAgent() {
		return "Covia/" + Engine.jarVersion() + " (+https://covia.ai)";
	}

	// ========== Configuration ==========

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		String agent = defaultUserAgent();
		Set<String> allow = Set.of();
		Set<String> block = Set.of();
		int hops = DEFAULT_MAX_REDIRECTS;
		if (config != null) {
			ACell ua = config.get(K_USER_AGENT);
			if (ua != null) {
				AString s = RT.ensureString(ua);
				if (s == null || s.toString().isBlank()) {
					throw new IllegalArgumentException("adapters.http.userAgent must be a non-empty string");
				}
				agent = s.toString().trim();
			}
			allow = hosts(config.get(K_ALLOWED_HOSTS), K_ALLOWED_HOSTS);
			block = hosts(config.get(K_BLOCKED_HOSTS), K_BLOCKED_HOSTS);
			ACell max = config.get(K_MAX_REDIRECTS);
			if (max != null) {
				CVMLong n = RT.ensureLong(max);
				if (n == null || n.longValue() < 0 || n.longValue() > MAX_REDIRECTS_CEILING) {
					throw new IllegalArgumentException("adapters.http.maxRedirects must be an integer from 0 to "
						+ MAX_REDIRECTS_CEILING + ", got: " + max);
				}
				hops = (int) n.longValue();
			}
		}
		userAgent = agent;
		configuredAllow = allow;
		configuredBlock = block;
		maxRedirects = hops;
		return true;
	}

	private static Set<String> hosts(ACell raw, AString key) {
		if (raw == null) return Set.of();
		AVector<ACell> values = RT.ensureVector(raw);
		if (values == null) {
			throw new IllegalArgumentException("adapters.http." + key + " must be an array of host names");
		}
		Set<String> out = new HashSet<>();
		for (long i = 0; i < values.count(); i++) {
			AString host = RT.ensureString(values.get(i));
			if (host == null || host.toString().isBlank()) {
				throw new IllegalArgumentException("adapters.http." + key
					+ " entries must be host names, got: " + values.get(i));
			}
			out.add(host.toString().trim().toLowerCase(Locale.ROOT));
		}
		return Set.copyOf(out);
	}

	/** Published at {@code v/info/adapters/http}: the effective settings a caller may rely on. */
	@Override
	public AMap<AString, ACell> info() {
		return Maps.of(
			K_USER_AGENT, Strings.create(userAgent),
			K_MAX_REDIRECTS, CVMLong.create(maxRedirects),
			K_ALLOWED_HOSTS, sorted(configuredAllow),
			K_BLOCKED_HOSTS, sorted(configuredBlock));
	}

	private static AVector<ACell> sorted(Set<String> values) {
		AVector<ACell> out = Vectors.empty();
		for (String s : new TreeSet<>(values)) out = out.conj(Strings.create(s));
		return out;
	}

	/** The User-Agent sent when a caller supplies none. */
	public String getUserAgent() {
		return userAgent;
	}

	/** The longest redirect chain followed; 0 returns redirect responses unfollowed. */
	public int getMaxRedirects() {
		return maxRedirects;
	}

	/**
	 * Add a host pattern to the allow list (bypasses SSRF checks).
	 * @param host Hostname to allow (e.g. "internal-api.company.com")
	 */
	public void addAllowedHost(String host) {
		allowList.add(host.toLowerCase(Locale.ROOT));
	}

	/**
	 * Add a host pattern to the block list (checked before allow list).
	 * @param host Hostname to block
	 */
	public void addBlockedHost(String host) {
		blockList.add(host.toLowerCase(Locale.ROOT));
	}

	// ========== SSRF guard ==========

	/**
	 * Public SSRF guard for other outbound-connecting adapters (MCP server
	 * bridging, #80): the same validation and the same operator allow/block
	 * lists as this adapter's own requests — binding a remote server can
	 * never reach anything a direct HTTP call couldn't.
	 *
	 * @param url URL string to validate
	 * @throws IllegalArgumentException if malformed, blocked or private
	 */
	public void requireSafeUrl(String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (Exception e) {
			throw new IllegalArgumentException("Malformed URL: " + url);
		}
		String scheme = uri.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new IllegalArgumentException("URL must be http(s): " + url);
		}
		validateURL(uri);
	}

	/**
	 * Validates a URL for SSRF safety. Blocks private/internal network addresses
	 * by default. Allow list entries bypass SSRF checks; block list is checked first.
	 *
	 * @param uri URI to validate
	 * @throws IllegalArgumentException if the URL targets a blocked or private address
	 */
	private void validateURL(URI uri) {
		String host = uri.getHost();
		if (host == null) {
			throw new IllegalArgumentException("URL has no host: " + uri);
		}
		String lowerHost = host.toLowerCase(Locale.ROOT);

		// Block list always wins
		if (blockList.contains(lowerHost) || configuredBlock.contains(lowerHost)) {
			throw new IllegalArgumentException("Host is blocked: " + host);
		}

		// Allow list bypasses SSRF checks
		if (allowList.contains(lowerHost) || configuredAllow.contains(lowerHost)) {
			return;
		}

		// Block private/internal addresses
		try {
			InetAddress[] addresses = InetAddress.getAllByName(host);
			for (InetAddress addr : addresses) {
				if (addr.isLoopbackAddress()
						|| addr.isSiteLocalAddress()
						|| addr.isLinkLocalAddress()
						|| addr.isAnyLocalAddress()) {
					throw new IllegalArgumentException(
						"URL targets a private/internal address: " + host
						+ " (resolved to " + addr.getHostAddress() + ")"
						+ ". Add to allowList if this is intentional.");
				}
			}
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Cannot resolve host: " + host);
		}

		// Block non-HTTP(S) schemes
		String scheme = uri.getScheme();
		if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("Only HTTP/HTTPS schemes are allowed, got: " + scheme);
		}
	}

	@Override
	public String getName() {
		return "http";
	}

	@Override
	public String getDescription() {
		return "HTTP client enables seamless web API integration and external service communication. " +
			   "Supports GET, POST, and other HTTP methods with custom headers, query parameters, and request bodies. " +
			   "Perfect for integrating with REST APIs, web services, and external data sources like Google Search and AI model APIs.";
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/http lives and dies with this adapter.
		installSkill("ops-tools/http", "/skills/http.json");
		// Connection skills: third-party services reachable with a user-supplied token
		// via bearerSecret. Pure instruction bundles over the http ops above.
		installSkill("connections/notion", "/skills/notion.json");
		installSkill("connections/hubspot", "/skills/hubspot.json");
		installSkill("connections/slack", "/skills/slack.json");
		String BASE = "/asset-examples/";

		// HTTP primitives — registered in /v/ops/.
		installAsset("http/get",  BASE + "httpget.json");
		installAsset("http/post", BASE + "httppost.json");

		// Demos and orchestrations — stored in CAS only, not in the catalog.
		installExampleAsset(BASE + "http-query-example.json");
		installExampleAsset(BASE + "googlesearch.json");
		installExampleAsset(BASE + "google-search-orch.json");
		installExampleAsset(BASE + "google-search-advanced-orch.json");
		installExampleAsset(BASE + "google-search-practical-orch.json");

		log.info("HTTP adapter assets installed successfully");
	}

	// ========== Requests ==========

	/**
	 * One outbound request as issued — and re-issued across redirects: the
	 * method, the body, the headers in order, and which header names carry
	 * credentials. {@link #follow} derives the next hop's request.
	 */
	private record Outbound(String method, String body, LinkedHashMap<String, String> headers,
			Set<String> credentials) {

		HttpRequest build(URI uri) {
			HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(30));
			switch (method) {
				case "GET" -> b.GET();
				case "DELETE" -> b.DELETE();
				case "POST" -> b.POST(HttpRequest.BodyPublishers.ofString(body));
				case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(body));
				case "PATCH" -> b.method("PATCH", HttpRequest.BodyPublishers.ofString(body));
				default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
			}
			for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
			return b.build();
		}

		/**
		 * The request for the next hop. 303 re-requests with GET and no body;
		 * 301 and 302 do the same for a POST, as browsers do; 307 and 308 keep
		 * the method and body. Credential headers survive only within one
		 * origin.
		 */
		Outbound follow(int status, boolean sameOrigin) {
			String nextMethod = method;
			String nextBody = body;
			if (status == 303 || ((status == 301 || status == 302) && "POST".equals(method))) {
				nextMethod = "GET";
				nextBody = "";
			}
			boolean bodyDropped = !nextMethod.equals(method);
			LinkedHashMap<String, String> next = new LinkedHashMap<>();
			for (Map.Entry<String, String> h : headers.entrySet()) {
				String name = h.getKey().toLowerCase(Locale.ROOT);
				if (!sameOrigin && credentials.contains(name)) continue;
				if (bodyDropped && (name.equals("content-type") || name.equals("content-length"))) continue;
				next.put(h.getKey(), h.getValue());
			}
			return new Outbound(nextMethod, nextBody, next, credentials);
		}
	}

	/** Sets a header, replacing any existing one of the same name in any case. */
	private static void putHeader(LinkedHashMap<String, String> headers, String name, String value) {
		headers.keySet().removeIf(k -> k.equalsIgnoreCase(name));
		headers.put(name, value);
	}

	private static boolean hasHeader(LinkedHashMap<String, String> headers, String name) {
		for (String k : headers.keySet()) if (k.equalsIgnoreCase(name)) return true;
		return false;
	}

	public static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	/** Same scheme, host and effective port. */
	public static boolean sameOrigin(URI a, URI b) {
		return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
			&& a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
			&& port(a) == port(b);
	}

	private static int port(URI uri) {
		if (uri.getPort() != -1) return uri.getPort();
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);

		AString url=RT.ensureString(RT.getIn(input, Fields.URL));
		if (url == null) throw new IllegalArgumentException("url is required");
		AString methodField=RT.ensureString(RT.getIn(input, Fields.METHOD));
		if ((methodField==null)&&(subOp!=null)) {
			methodField=Strings.create(subOp);
		}

		AMap<AString, ACell> headers = optionalMap(input, Fields.HEADERS);
		AMap<AString, ACell> queryParams = optionalMap(input, Fields.QUERY_PARAMS);
		ACell bodyField=RT.getIn(input, Fields.BODY);
		AString bearerSecret=RT.ensureString(RT.getIn(input, Fields.BEARER_SECRET));
		AMap<AString, ACell> secretHeaders = optionalMap(input, Fields.SECRET_HEADERS);
		boolean follow = !CVMBool.FALSE.equals(RT.getIn(input, Fields.FOLLOW_REDIRECTS)) && maxRedirects > 0;

		try {
			String method = "GET"; // default
			if (methodField != null) {
				method = methodField.toString().trim().toUpperCase(Locale.ROOT);
			}
			if (!METHODS.contains(method)) {
				throw new IllegalArgumentException("Unsupported HTTP method: " + method);
			}

			// Build URL with query parameters
			String finalUrl = url.toString();
			if (queryParams != null && !queryParams.isEmpty()) {
				StringBuilder queryString = new StringBuilder();
				boolean first = true;

				for (MapEntry<AString, ACell> me : queryParams.entryVector()) {
					if (!first) {
						queryString.append("&");
					}
					queryString.append(URLEncoder.encode(me.getKey().toString(), StandardCharsets.UTF_8))
							  .append("=")
							  .append(URLEncoder.encode(scalar(me.getValue(), Fields.QUERY_PARAMS, me.getKey()), StandardCharsets.UTF_8));
					first = false;
				}

				if (queryString.length() > 0) {
					finalUrl += (finalUrl.contains("?") ? "&" : "?") + queryString.toString();
				}
			}

			// Validate URL for SSRF safety
			URI targetUri = new URI(finalUrl);
			validateURL(targetUri);

			String bodyText = (bodyField == null) ? "" : JSON.printPretty(bodyField).toString();

			// Headers, in order: the caller's, then resolved secrets over any
			// literal of the same name, then the bearer, then the venue's
			// User-Agent when the caller set none (#422).
			LinkedHashMap<String, String> outHeaders = new LinkedHashMap<>();
			Set<String> credentials = new HashSet<>(CREDENTIAL_HEADERS);
			if (headers != null) {
				for (MapEntry<AString, ACell> me : headers.entryVector()) {
					putHeader(outHeaders, me.getKey().toString(), scalar(me.getValue(), Fields.HEADERS, me.getKey()));
				}
			}

			// Resolved secret headers override matching literal headers. The stored
			// secret is the complete header value (e.g. "Basic ..." or an API key),
			// keeping auth-scheme formatting out of the infrastructure.
			Set<String> resolvedNames = new HashSet<>();
			if (secretHeaders != null) {
				for (MapEntry<AString, ACell> entry : secretHeaders.entryVector()) {
					AString header = RT.ensureString(entry.getKey());
					AString secret = RT.ensureString(entry.getValue());
					if (header == null || header.isEmpty() || secret == null || secret.isEmpty()) {
						throw new IllegalArgumentException(
							"secretHeaders must map non-empty header names to secret references");
					}
					String canonical = header.toString().toLowerCase(Locale.ROOT);
					if (!resolvedNames.add(canonical)) {
						throw new IllegalArgumentException(
							"secretHeaders contains the same header more than once: " + header);
					}
					putHeader(outHeaders, header.toString(), resolveSecret(secret, ctx, "secretHeaders"));
					credentials.add(canonical);
				}
			}

			// Backward-compatible bearer shorthand. Refuse two secret sources for
			// Authorization rather than silently choosing one.
			if (bearerSecret != null) {
				if (resolvedNames.contains("authorization")) {
					throw new IllegalArgumentException(
						"Specify Authorization in either secretHeaders or bearerSecret, not both");
				}
				String ref = bearerSecret.toString();
				String bearer = ref.startsWith(TokenSource.PREFIX)
					? connectionToken(ctx, ref.substring(TokenSource.PREFIX.length()))
					: resolveSecret(bearerSecret, ctx, "bearerSecret");
				putHeader(outHeaders, "Authorization", "Bearer " + bearer);
			}

			if (!hasHeader(outHeaders, USER_AGENT_HEADER)) {
				outHeaders.put(USER_AGENT_HEADER, userAgent);
			}

			Outbound request = new Outbound(method, bodyText, outHeaders, Set.copyOf(credentials));
			Set<URI> visited = new HashSet<>();
			visited.add(targetUri);
			return send(targetUri, request, follow, maxRedirects, Vectors.empty(), visited);

		} catch (URISyntaxException e) {
			throw new RuntimeException("Bad URI syntax: "+url,e);
		}
	}

	/**
	 * Sends one hop and, on a redirect the caller asked to follow, the next —
	 * each target validated exactly as the first was (#423). Loops, chains
	 * longer than the limit and refused targets fail the job naming the chain.
	 */
	private CompletableFuture<ACell> send(URI uri, Outbound request, boolean follow, int hopsLeft,
			AVector<ACell> trail, Set<URI> visited) {
		return httpClient.sendAsync(request.build(uri), HttpResponse.BodyHandlers.ofString())
			.thenCompose(response -> {
				int code = response.statusCode();
				String location = response.headers().firstValue("Location").orElse(null);
				if (!follow || !isRedirect(code) || location == null) {
					return CompletableFuture.completedFuture(output(response, uri, trail));
				}
				URI next;
				try {
					next = uri.resolve(location.trim());
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("Redirect from " + uri + " has a malformed Location: " + location);
				}
				AMap<AString, ACell> hop = Maps.of(
					Fields.STATUS, CVMLong.create(code),
					Fields.FROM, Strings.create(uri.toString()),
					Fields.TO, Strings.create(next.toString()));
				AVector<ACell> hops = trail.conj(hop);
				if (hopsLeft <= 0) {
					throw new IllegalArgumentException("Too many redirects (limit " + maxRedirects + "): " + chain(hops));
				}
				if (!visited.add(next)) {
					throw new IllegalArgumentException("Redirect loop: " + chain(hops));
				}
				try {
					validateURL(next);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("Redirect refused: " + e.getMessage() + " — " + chain(hops));
				}
				return send(next, request.follow(code, sameOrigin(uri, next)), true, hopsLeft - 1, hops, visited);
			});
	}

	/** The chain so far, for a failure message: {@code a -> b -> c}. */
	private static String chain(AVector<ACell> hops) {
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < hops.count(); i++) {
			// Bind to ACell first: a generic getIn in argument position would pick
			// append(CharSequence) and fail its cast at runtime.
			ACell from = RT.getIn(hops.get(i), Fields.FROM);
			ACell to = RT.getIn(hops.get(i), Fields.TO);
			if (i == 0) sb.append(from);
			sb.append(" -> ").append(to);
		}
		return sb.toString();
	}

	/** The result: status, body and headers of the final response, the final
	 *  {@code url}, and the {@code redirects} taken when there were any. */
	private static AMap<AString, ACell> output(HttpResponse<String> response, URI finalUri, AVector<ACell> trail) {
		AMap<AString, ACell> output = Maps.of(
			Fields.STATUS, CVMLong.create(response.statusCode()),
			Fields.BODY, Strings.create(response.body()));

		// Convert response headers
		Map<String, List<String>> responseHeaders = response.headers().map();
		AMap<AString, AString> rheaders = Maps.empty();
		for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
			String key = entry.getKey();
			String value = String.join(", ", entry.getValue());
			rheaders = rheaders.assoc(Strings.create(key), Strings.create(value));
		}
		output = output.assoc(Fields.HEADERS, RT.cvm(rheaders));
		output = output.assoc(Fields.URL, Strings.create(finalUri.toString()));
		if (!trail.isEmpty()) output = output.assoc(Fields.REDIRECTS, trail);
		return output;
	}

	/**
	 * A header or query value as text: a string as-is, a number or boolean
	 * printed — so {@code count: 10} works as a caller would expect — and
	 * anything structured refused with a message naming the field.
	 */
	private static String scalar(ACell value, AString field, ACell key) {
		if (value instanceof AString s) return s.toString();
		if (value instanceof CVMLong || value instanceof CVMDouble || value instanceof CVMBool) {
			return value.toString();
		}
		throw new IllegalArgumentException(field + "." + key
			+ " must be a string, number or boolean, got: " + value);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> optionalMap(ACell input, AString field) {
		ACell value = RT.getIn(input, field);
		if (value == null) return null;
		if (!(value instanceof AMap<?, ?> map)) {
			throw new IllegalArgumentException(field + " must be an object");
		}
		return (AMap<AString, ACell>) map;
	}

	/** {@code bearerSecret: "oauth/<provider>"} — a fresh access token for the caller's connected account. */
	private String connectionToken(RequestContext ctx, String provider) {
		TokenSource source = (engine != null) ? engine.findAdapter(TokenSource.class) : null;
		if (source == null) {
			throw new IllegalStateException("bearerSecret \"oauth/" + provider
				+ "\" needs the oauth adapter, which is not registered on this venue");
		}
		try {
			return source.accessToken(ctx, provider);
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not obtain an access token for " + provider + ": " + e.getMessage());
		}
	}

	private String resolveSecret(AString reference, RequestContext ctx, String field) {
		if (engine == null || ctx == null) {
			throw new IllegalStateException(field + " requires engine and request context");
		}
		String resolved = engine.resolveSecret(reference.toString(), ctx);
		if (resolved == null) {
			throw new IllegalArgumentException("Cannot resolve " + field + " reference '"
				+ reference + "'; store it with secret:set or pass an existing s/<name> reference");
		}
		return resolved;
	}

}
