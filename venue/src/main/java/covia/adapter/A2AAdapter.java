package covia.adapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;
import covia.venue.api.A2ACodec;

/**
 * Outbound A2A adapter — invoke remote A2A agents as Covia grid operations.
 *
 * <p>Sub-operations:</p>
 * <ul>
 *   <li>{@code a2a:importAgent} — import a standard endpoint or Covia agent as an Asset.</li>
 *   <li>{@code a2a:getAgentCard} — read the imported Agent Card snapshot.</li>
 *   <li>{@code a2a:send} — send a message; one Covia Job mirrors one remote A2A Task.</li>
 *   <li>{@code a2a:getTask} / {@code a2a:cancel} — one-shot RPCs against a remote Task.</li>
 *   <li>{@code a2a:raw*} — diagnostic URL/credential-bearing escape hatches.</li>
 * </ul>
 *
 * <p>Uses the SDK's {@link JsonUtil#OBJECT_MAPPER} so polymorphic types
 * (Part, SecurityScheme, etc.) parse correctly. Goes directly via
 * {@link HttpClient} instead of the SDK's {@code Client} — we don't need
 * the card-driven transport configuration here, and going direct keeps
 * error handling explicit.</p>
 */
public class A2AAdapter extends AAdapter {

	public static final Logger log = LoggerFactory.getLogger(A2AAdapter.class);

	private static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";
	private static final String A2A_RPC_PATH = "/a2a";
	private static final AString K_AGENT = Strings.intern("agent");
	private static final AString K_AUTH = Strings.intern("auth");
	private static final AString K_CARD = Strings.intern("card");
	private static final AString K_CARD_URL = Strings.intern("cardUrl");
	private static final AString K_ENDPOINT = Strings.intern("endpoint");
	private static final AString K_SECRET = Strings.intern("secret");
	private static final AString K_SCHEME = Strings.intern("scheme");
	private static final AString K_KIND = Strings.intern("kind");
	private static final AString K_TARGET = Strings.intern("target");
	private static final AString K_COVIA_AGENT = Strings.intern("coviaAgent");
	private static final AString K_VENUE = Strings.intern("venue");
	private static final AString K_SUPPORTED_INTERFACES = Strings.intern("supportedInterfaces");
	private static final AString K_SECURITY_SCHEMES = Strings.intern("securitySchemes");
	private static final AString K_API_KEY_SCHEME = Strings.intern("apiKeySecurityScheme");
	private static final AString K_HTTP_AUTH_SCHEME = Strings.intern("httpAuthSecurityScheme");
	private static final AString K_LOCATION = Strings.intern("location");
	private static final AString K_AGENT_ASSET = Strings.intern("a2aAgentAsset");
	private static final AString TYPE_A2A_AGENT = Strings.intern("a2a-agent");

	public static Hash IMPORT_AGENT_OPERATION;
	public static Hash GET_AGENT_CARD_OPERATION;
	public static Hash GET_TASK_OPERATION;
	public static Hash CANCEL_OPERATION;
	public static Hash SEND_OPERATION;

	/** Poll interval for mirroring remote Task state into the local Job. */
	static final Duration POLL_INTERVAL = Duration.ofMillis(500);

	/** Upper bound on total mirror lifetime — defends against a runaway remote.
	 *  Covia jobs can be long-lived but a misbehaving remote peer that never
	 *  terminates shouldn't hold a poller thread forever. */
	static final Duration POLL_MAX_LIFETIME = Duration.ofMinutes(30);

	private final HttpClient httpClient;

	public A2AAdapter() {
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	@Override
	public String getName() {
		return "a2a";
	}

	@Override
	public String getDescription() {
		return "Outbound Agent-to-Agent client. Invoke remote A2A agents as grid operations: "
				+ "fetch agent cards, send messages, get/cancel remote tasks.";
	}

	@Override
	protected void installAssets() {
		IMPORT_AGENT_OPERATION   = installAsset("a2a/import-agent", "/adapters/a2a/importAgent.json");
		GET_AGENT_CARD_OPERATION = installAsset("a2a/agent-card", "/adapters/a2a/agentCard.json");
		GET_TASK_OPERATION       = installAsset("a2a/get-task",   "/adapters/a2a/getTask.json");
		CANCEL_OPERATION         = installAsset("a2a/cancel",     "/adapters/a2a/cancel.json");
		SEND_OPERATION           = installAsset("a2a/send",       "/adapters/a2a/send.json");
		// URL/credential-bearing escape hatches for diagnostics and migration.
		// They are deliberately absent from the A2A skill; normal callers import
		// an agent Asset once and use the operations above with {agent: ...}.
		installAsset("a2a/raw/agent-card", "/adapters/a2a/rawAgentCard.json");
		installAsset("a2a/raw/get-task",   "/adapters/a2a/rawGetTask.json");
		installAsset("a2a/raw/cancel",     "/adapters/a2a/rawCancel.json");
		installAsset("a2a/raw/send",       "/adapters/a2a/rawSend.json");
	}

	// ==================== Job-aware dispatch ====================

	/**
	 * Override the job-aware path so {@code a2a:send} can mirror a remote
	 * Task's lifecycle into the local Job. Other sub-ops go through the
	 * default future-based dispatch.
	 */
	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// The send branch bypasses AAdapter's future-based default, so pin the
		// operation capability before it performs any outbound work.
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if ("send".equals(subOp) || "rawSend".equals(subOp)) {
			doSendMirrored(job, ctx, input, "rawSend".equals(subOp));
			return;
		}
		super.invoke(job, ctx, meta, input);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException("No sub-operation in a2a adapter metadata"));
		}
		return switch (subOp) {
			case "importAgent"  -> importAgent(input, ctx);
			case "getAgentCard" -> fetchAgentCard(input, ctx, false);
			case "getTask"      -> rpcCallWithId(input, ctx, A2AMethods.GET_TASK_METHOD, false);
			case "cancel"       -> rpcCallWithId(input, ctx, A2AMethods.CANCEL_TASK_METHOD, false);
			case "rawAgentCard" -> fetchAgentCard(input, ctx, true);
			case "rawGetTask"   -> rpcCallWithId(input, ctx, A2AMethods.GET_TASK_METHOD, true);
			case "rawCancel"    -> rpcCallWithId(input, ctx, A2AMethods.CANCEL_TASK_METHOD, true);
			case "send", "rawSend" -> {
				// Job-worthy (the #85 delegation pattern, caught live by an agent
				// calling a2a_send as a tool): send exists only in the Job-aware
				// dispatch — the mirror needs a real Job for status propagation,
				// the cancel hook, and the remote task id. Delegate the transient-Job
				// internal path to an owner-attributed Job rather than rejecting.
				Job job = engine.jobs().invokeOperation(meta, input, ctx);
				yield job.future().thenApply(x -> x);
			}
			default -> CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown a2a sub-operation: " + subOp));
		};
	}

	// ==================== Imported A2A agent assets ====================

	/** Resolved transport authentication. The secret value exists only in
	 * process memory; imported assets persist the SecretStore reference. */
	private record RequestAuth(String location, String name, String value) {
		static RequestAuth bearer(String value) {
			return value == null ? null : new RequestAuth("header", "Authorization", "Bearer " + value);
		}

		URI applyTo(URI uri) {
			if (!"query".equals(location)) return uri;
			String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
			String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8);
			String raw = uri.toString();
			return URI.create(raw + (uri.getRawQuery() == null ? "?" : "&")
				+ encodedName + "=" + encodedValue);
		}

		void applyHeaders(HttpRequest.Builder builder) {
			if ("header".equals(location)) {
				builder.setHeader(name, value);
			} else if ("cookie".equals(location)) {
				builder.setHeader("Cookie", name + "=" + value);
			}
		}
	}

	/** One resolved immutable agent profile for the lifetime of an invocation. */
	private record AgentTarget(String rpcUrl, AMap<AString, ACell> card,
			RequestAuth auth, Hash assetId) {}

	/**
	 * Import a standard A2A endpoint or a Covia agent endpoint as an immutable
	 * {@code type:a2a-agent} Asset and publish the same snapshot at the caller's
	 * mutable {@code w/a2a/agents/<name>} binding.
	 */
	@SuppressWarnings("unchecked")
	private CompletableFuture<ACell> importAgent(ACell input, RequestContext ctx) {
		AString nameCell = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (nameCell == null || !nameCell.toString().matches("[a-z0-9-]{1,64}")) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"'name' is required and must match [a-z0-9-]{1,64}"));
		}
		String alias = nameCell.toString();
		String bindingPath = "w/a2a/agents/" + alias;

		AString urlCell = RT.ensureString(RT.getIn(input, Fields.URL));
		AString coviaAgentCell = RT.ensureString(RT.getIn(input, K_COVIA_AGENT));
		if ((urlCell == null) == (coviaAgentCell == null)) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"Specify exactly one of 'url' or 'coviaAgent'"));
		}

		AMap<AString, ACell> storedAuth;
		try {
			engine.requireAuthority(ctx, Strings.create("a/"), Abilities.ASSET_STORE);
			engine.requireResourceAccess(ctx, Strings.create(bindingPath),
				convex.auth.ucan.Capability.CRUD_WRITE);
			storedAuth = parseStoredAuth(input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}

		String cardBase;
		AMap<AString, ACell> targetMeta;
		try {
			if (urlCell != null) {
				cardBase = stripTrailingSlash(urlCell.toString());
				targetMeta = Maps.of(K_KIND, Strings.intern("a2a"), Fields.URL, Strings.create(cardBase));
			} else {
				boolean localReference = coviaAgentCell.toString().startsWith("g/");
				AString venueCell = RT.ensureString(RT.getIn(input, K_VENUE));
				if (venueCell == null && !localReference) throw new IllegalArgumentException(
					"'venue' is required with a remote coviaAgent address");
				String address = normaliseCoviaAgentAddress(coviaAgentCell.toString(), ctx);
				String venue = stripTrailingSlash(venueCell != null
					? venueCell.toString() : engine.config().getBaseUrl());
				cardBase = venue + "/a2a/" + address;
				targetMeta = Maps.of(
					K_KIND, Strings.intern("covia"),
					K_VENUE, Strings.create(venue),
					K_AGENT, Strings.create(address));
			}
			requireSafeUrl(normaliseAgentCardUrl(cardBase));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}

		// A direct bearer binding can authenticate discovery of a private card.
		// Card-named schemes are resolved only after the public card is known.
		RequestAuth discoveryAuth;
		try {
			discoveryAuth = directBearerAuth(storedAuth, ctx);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}

		return fetchAgentCardUrl(cardBase, discoveryAuth).thenApply(cardCell -> {
			if (!(cardCell instanceof AMap<?, ?>)) {
				throw new IllegalArgumentException("Remote Agent Card is not a JSON object");
			}
			AMap<AString, ACell> card = (AMap<AString, ACell>) cardCell;
			String endpoint = endpointFromCard(card);
			requireSafeUrl(endpoint);
			// Validate the stored scheme and secret now, while importing, so a bad
			// binding cannot become a dormant asset that fails only during a task.
			resolveAssetAuth(storedAuth, card, ctx);

			AMap<AString, ACell> a2a = Maps.of(
				K_CARD, card,
				K_CARD_URL, Strings.create(normaliseAgentCardUrl(cardBase)),
				K_ENDPOINT, Strings.create(endpoint),
				K_TARGET, targetMeta);
			if (storedAuth != null) a2a = a2a.assoc(K_AUTH, storedAuth);

			AString displayName = RT.ensureString(card.get(Fields.NAME));
			AString description = RT.ensureString(card.get(Fields.DESCRIPTION));
			AMap<AString, ACell> metadata = Maps.of(
				Fields.NAME, displayName != null ? displayName : nameCell,
				Fields.TYPE, TYPE_A2A_AGENT,
				Fields.A2A, a2a);
			if (description != null) metadata = metadata.assoc(Fields.DESCRIPTION, description);

			Hash id = engine.storeUserAsset(JSON.printPretty(metadata), null, ctx);
			engine.jobs().invokeInternal(Strings.create("v/ops/covia/write"), Maps.of(
				Fields.PATH, Strings.create(bindingPath),
				Fields.VALUE, metadata), ctx).join();

			return Maps.of(
				Fields.ID, ctx.getUserDID().append("/a/" + id.toHexString()),
				Fields.PATH, Strings.create(bindingPath),
				K_AGENT_ASSET, Strings.create(id.toHexString()),
				Fields.STORED, convex.core.data.prim.CVMBool.TRUE);
		});
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> parseStoredAuth(ACell input) {
		ACell raw = RT.getIn(input, K_AUTH);
		if (raw == null) return null;
		if (!(raw instanceof AMap<?, ?>)) throw new IllegalArgumentException("'auth' must be an object");
		AMap<AString, ACell> auth = (AMap<AString, ACell>) raw;
		AString secret = RT.ensureString(auth.get(K_SECRET));
		if (secret == null || !(secret.toString().startsWith("s/") || secret.toString().startsWith("/s/"))) {
			throw new IllegalArgumentException("auth.secret must be a SecretStore reference such as s/PARTNER_KEY");
		}
		AString kind = RT.ensureString(auth.get(K_KIND));
		AString scheme = RT.ensureString(auth.get(K_SCHEME));
		if ((kind == null) == (scheme == null)) {
			throw new IllegalArgumentException("auth must specify exactly one of 'kind' or 'scheme'");
		}
		if (kind != null && !"bearer".equalsIgnoreCase(kind.toString())) {
			throw new IllegalArgumentException("Unsupported direct auth kind '" + kind + "'; supported: bearer");
		}
		return auth;
	}

	private static String normaliseCoviaAgentAddress(String value, RequestContext ctx) {
		String address = value;
		if (address.startsWith("g/")) {
			if (ctx.getUserDID() == null) throw new IllegalArgumentException(
				"A local g/<agentId> reference requires an authenticated user");
			address = ctx.getUserDID() + "/" + address;
		}
		A2ACodec.AgentRef ref = A2ACodec.parseAgentEndpoint("/a2a/" + address);
		if (ref == null) throw new IllegalArgumentException(
			"coviaAgent must be g/<agentId> or <ownerDID>/g/<agentId>");
		return ref.gridAddress();
	}

	private static String stripTrailingSlash(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("URL must not be empty");
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	@SuppressWarnings("unchecked")
	private AgentTarget resolveAgentTarget(ACell input, RequestContext ctx, boolean allowRaw) {
		ACell ref = RT.getIn(input, K_AGENT);
		if (ref == null) {
			if (allowRaw) return resolveRawTarget(input, ctx);
			throw new IllegalArgumentException("'agent' is required; import one with v/ops/a2a/import-agent");
		}

		ACell value;
		if (ref instanceof AMap<?, ?>) {
			value = ref;
		} else {
			AString path = RT.ensureString(ref);
			if (path == null) throw new IllegalArgumentException("'agent' must be an asset reference");
			value = engine.resolvePath(path, ctx);
		}
		if (!(value instanceof AMap<?, ?>)) {
			throw new IllegalArgumentException("Cannot resolve A2A agent asset: " + ref);
		}
		AMap<AString, ACell> metadata = (AMap<AString, ACell>) value;
		if (!TYPE_A2A_AGENT.equals(RT.ensureString(metadata.get(Fields.TYPE)))) {
			throw new IllegalArgumentException("Asset is not type 'a2a-agent': " + ref);
		}
		AMap<AString, ACell> a2a = RT.ensureMap(metadata.get(Fields.A2A));
		if (a2a == null) throw new IllegalArgumentException("A2A agent asset has no 'a2a' profile");
		AString endpoint = RT.ensureString(a2a.get(K_ENDPOINT));
		AMap<AString, ACell> card = RT.ensureMap(a2a.get(K_CARD));
		if (endpoint == null || card == null) {
			throw new IllegalArgumentException("A2A agent asset requires a card and endpoint");
		}
		requireSafeUrl(endpoint.toString());
		AMap<AString, ACell> authBinding = RT.ensureMap(a2a.get(K_AUTH));
		RequestAuth auth = resolveAssetAuth(authBinding, card, ctx);
		return new AgentTarget(endpoint.toString(), card, auth, metadata.getHash());
	}

	private AgentTarget resolveRawTarget(ACell input, RequestContext ctx) {
		AString url = RT.ensureString(RT.getIn(input, Fields.URL));
		if (url == null) throw new IllegalArgumentException("'agent' is required (or 'url' on a raw A2A operation)");
		String rpcUrl = normaliseRpcUrl(url.toString());
		requireSafeUrl(rpcUrl);
		return new AgentTarget(rpcUrl, null, RequestAuth.bearer(resolveBearer(input, ctx)), null);
	}

	private RequestAuth directBearerAuth(AMap<AString, ACell> auth, RequestContext ctx) {
		if (auth == null) return null;
		AString kind = RT.ensureString(auth.get(K_KIND));
		if (kind == null || !"bearer".equalsIgnoreCase(kind.toString())) return null;
		return RequestAuth.bearer(resolveSecretRef(auth, ctx));
	}

	@SuppressWarnings("unchecked")
	private RequestAuth resolveAssetAuth(AMap<AString, ACell> auth,
			AMap<AString, ACell> card, RequestContext ctx) {
		if (auth == null) return null;
		RequestAuth direct = directBearerAuth(auth, ctx);
		if (direct != null) return direct;

		AString schemeName = RT.ensureString(auth.get(K_SCHEME));
		AMap<AString, ACell> schemes = RT.ensureMap(card.get(K_SECURITY_SCHEMES));
		ACell schemeCell = schemes != null ? schemes.get(schemeName) : null;
		if (!(schemeCell instanceof AMap<?, ?>)) {
			throw new IllegalArgumentException("Agent Card does not declare security scheme '" + schemeName + "'");
		}
		AMap<AString, ACell> scheme = (AMap<AString, ACell>) schemeCell;
		String secret = resolveSecretRef(auth, ctx);

		AMap<AString, ACell> apiKey = RT.ensureMap(scheme.get(K_API_KEY_SCHEME));
		if (apiKey != null) {
			AString location = RT.ensureString(apiKey.get(K_LOCATION));
			AString name = RT.ensureString(apiKey.get(Fields.NAME));
			if (location == null || name == null
					|| !("header".equals(location.toString()) || "query".equals(location.toString())
						|| "cookie".equals(location.toString()))) {
				throw new IllegalArgumentException("API-key scheme '" + schemeName
					+ "' must declare location header, query, or cookie and a name");
			}
			return new RequestAuth(location.toString(), name.toString(), secret);
		}

		AMap<AString, ACell> http = RT.ensureMap(scheme.get(K_HTTP_AUTH_SCHEME));
		AString httpScheme = http != null ? RT.ensureString(http.get(K_SCHEME)) : null;
		if (httpScheme != null && "bearer".equalsIgnoreCase(httpScheme.toString())) {
			return RequestAuth.bearer(secret);
		}
		throw new IllegalArgumentException("Unsupported Agent Card security scheme '" + schemeName
			+ "'; supported: API key and HTTP Bearer");
	}

	private String resolveSecretRef(AMap<AString, ACell> auth, RequestContext ctx) {
		AString ref = RT.ensureString(auth.get(K_SECRET));
		String value = ref != null ? engine.resolveSecret(ref.toString(), ctx) : null;
		if (value == null) throw new IllegalArgumentException(
			"Cannot resolve A2A auth secret '" + ref + "' from the caller's SecretStore");
		return value;
	}

	@SuppressWarnings("unchecked")
	private static String endpointFromCard(AMap<AString, ACell> card) {
		ACell interfacesCell = card.get(K_SUPPORTED_INTERFACES);
		if (interfacesCell instanceof AVector<?> interfaces && interfaces.count() > 0) {
			ACell first = interfaces.get(0);
			if (first instanceof AMap<?, ?> map) {
				AString url = RT.ensureString(((AMap<AString, ACell>) map).get(Fields.URL));
				if (url != null) return url.toString();
			}
		}
		// Compatibility with pre-1.0 cards that advertised one top-level URL.
		AString legacy = RT.ensureString(card.get(Fields.URL));
		if (legacy != null) return legacy.toString();
		throw new IllegalArgumentException("Agent Card has no supported interface URL");
	}

	// ==================== getAgentCard ====================

	private CompletableFuture<ACell> fetchAgentCard(ACell input, RequestContext ctx, boolean allowRaw) {
		if (RT.getIn(input, K_AGENT) != null) {
			try {
				return CompletableFuture.completedFuture(resolveAgentTarget(input, ctx, false).card());
			} catch (RuntimeException e) {
				return CompletableFuture.failedFuture(e);
			}
		}
		if (!allowRaw) return CompletableFuture.failedFuture(new IllegalArgumentException(
			"'agent' is required; import one with v/ops/a2a/import-agent"));
		AString urlCell = RT.ensureString(RT.getIn(input, Fields.URL));
		if (urlCell == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"'url' is required (remote A2A base or RPC URL)"));
		}
		try {
			return fetchAgentCardUrl(urlCell.toString(), RequestAuth.bearer(resolveBearer(input, ctx)));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private CompletableFuture<ACell> fetchAgentCardUrl(String baseUrl, RequestAuth auth) {
		String url = normaliseAgentCardUrl(baseUrl);
		requireSafeUrl(url);
		HttpRequest.Builder builder = HttpRequest.newBuilder(authUri(url, auth))
				.GET()
				.timeout(Duration.ofSeconds(30));
		applyAuth(builder, auth);
		HttpRequest req = builder.build();
		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					int sc = resp.statusCode();
					if (sc < 200 || sc >= 300) {
						throw new RuntimeException("Agent card fetch failed: HTTP " + sc + ": "
							+ conciseDetail(resp.body(), 512));
					}
					// Round-trip through the SDK mapper so the response is parsed
					// (and any drift in upstream card format is flagged loudly).
					// The returned value is a plain JSON map on the Covia side.
					String body = resp.body();
					return JSON.parse(body);
				});
	}

	/** SSRF guard shared with the http adapter, including its operator
	 *  allow/block lists (#234): an A2A target can never reach anything a
	 *  direct HTTP call couldn't. Fails closed if the http adapter is absent. */
	private void requireSafeUrl(String url) {
		AAdapter http = engine.getAdapter("http");
		if (!(http instanceof HTTPAdapter h)) {
			throw new IllegalStateException("SSRF validation unavailable: http adapter not registered");
		}
		h.requireSafeUrl(url);
	}

	private static String normaliseAgentCardUrl(String url) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url must not be empty");
		}
		if (url.endsWith(AGENT_CARD_PATH)) return url;
		// Strip a single trailing slash so we don't double it.
		if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
		return url + AGENT_CARD_PATH;
	}

	// ==================== Generic JSON-RPC call ====================

	/**
	 * Build a params map with an {@code id} field extracted from input. Shared
	 * by {@code getTask} and {@code cancel}, which both take only a task id.
	 */
	private static Map<String, Object> idParams(ACell input) {
		AString id = RT.ensureString(RT.getIn(input, Fields.ID));
		if (id == null) {
			throw new IllegalArgumentException("'id' is required (remote A2A task ID)");
		}
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("id", id.toString());
		return p;
	}

	/** Keep missing/invalid RPC input on the normal failed-Job path. */
	private CompletableFuture<ACell> rpcCallWithId(ACell input, RequestContext ctx, String method,
			boolean allowRaw) {
		try {
			return rpcCall(input, ctx, method, idParams(input), allowRaw);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * POST a JSON-RPC 2.0 request to the {@code /a2a} endpoint on the remote
	 * agent and return the {@code result} as a Covia ACell. Throws if the
	 * remote returns an {@code error} — callers get a failed future.
	 */
	private CompletableFuture<ACell> rpcCall(ACell input, RequestContext ctx, String method,
			Map<String, Object> params, boolean allowRaw) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", UUID.randomUUID().toString());
		envelope.put("method", method);
		envelope.put("params", params);

		AgentTarget target;
		try {
			target = resolveAgentTarget(input, ctx, allowRaw);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		HttpRequest req = postEnvelope(target.rpcUrl(), envelope, target.auth());

		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					int sc = resp.statusCode();
					// Per spec §9, JSON-RPC errors still arrive with HTTP 200;
					// we only reject on non-2xx transport-level failures.
					if (sc < 200 || sc >= 300) {
						throw new RuntimeException("A2A RPC failed: HTTP " + sc + ": "
							+ conciseDetail(resp.body(), 512));
					}
					ACell parsed = JSON.parse(resp.body());
					if (!(parsed instanceof AMap)) {
						throw new RuntimeException("A2A response is not a JSON object");
					}
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> parsedMap = (AMap<AString, ACell>) parsed;
					ACell err = parsedMap.get(Fields.ERROR);
					if (err != null) {
						throw new RuntimeException("A2A error: " + conciseDetail(err, 512));
					}
					ACell result = parsedMap.get(Fields.RESULT);
					if (result == null) {
						throw new RuntimeException("A2A response has neither result nor error");
					}
					// getTask / cancel both return a Task; unwrap if wrapped.
					return unwrapKind(result, "task");
				});
	}

	static String normaliseRpcUrl(String url) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url must not be empty");
		}
		if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
		// Already an A2A endpoint — the venue front door (…/a2a) or a
		// per-agent endpoint below it (…/a2a/<ownerDID>/g/<agentId>): use it
		// verbatim. Appending /a2a to a per-agent endpoint would corrupt the
		// agent address and 404 at the remote.
		String path;
		try {
			path = URI.create(url).getPath();
		} catch (IllegalArgumentException e) {
			path = null; // not a parseable URI — fall through; the request build fails with the real error
		}
		if (path != null && (path.endsWith(A2A_RPC_PATH) || path.contains(A2A_RPC_PATH + "/"))) return url;
		return url + A2A_RPC_PATH;
	}

	// ==================== a2a:send with mirroring ====================

	@SuppressWarnings("unchecked")
	private void doSendMirrored(Job job, RequestContext ctx, ACell input, boolean allowRaw) {
		AgentTarget target;
		try {
			target = resolveAgentTarget(input, ctx, allowRaw);
		} catch (RuntimeException e) {
			job.fail(describeFailure(e));
			return;
		}
		String rpcUrl = target.rpcUrl();
		RequestAuth auth = target.auth();
		if (target.assetId() != null) {
			job.updateData(job.getData().assoc(K_AGENT_ASSET,
				Strings.create(target.assetId().toHexString())));
		}

		ACell messageRaw = RT.getIn(input, Fields.MESSAGE);
		if (!(messageRaw instanceof AMap)) {
			job.fail("'message' is required and must be an A2A message object"); return;
		}

		AString continuationTaskIdCell = RT.ensureString(RT.getIn(input, Fields.TASK_ID));
		String continuationTaskId = continuationTaskIdCell != null ? continuationTaskIdCell.toString() : null;

		// Build the outbound Message. Roles from local records are not trusted
		// for outbound — we're acting as the user from the remote agent's POV.
		Message parsed = A2ACodec.fromMessageRecord((AMap<AString, ACell>) messageRaw, null, continuationTaskId);
		if (parsed == null) {
			job.fail("Invalid 'message': include at least one supported A2A message part"); return;
		}
		Message outbound = Message.builder()
				.role(Message.Role.ROLE_USER)
				.parts(parsed.parts())
				.messageId(parsed.messageId() != null ? parsed.messageId() : UUID.randomUUID().toString())
				.contextId(parsed.contextId())
				.taskId(continuationTaskId)
				.build();

		MessageSendParams params = new MessageSendParams(outbound, null, null);
		Map<String, Object> envelope = rpcEnvelope(A2AMethods.SEND_MESSAGE_METHOD, params);

		job.setStatus(Status.STARTED);

		HttpRequest req = postEnvelope(rpcUrl, envelope, auth);
		httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.whenComplete((resp, err) -> {
					if (err != null) {
						Throwable cause = (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
								? err.getCause() : err;
						job.fail("SendMessage transport failed: " + describeFailure(cause)
							+ "; verify 'url' and retry");
						return;
					}
					handleSendResponse(job, rpcUrl, auth, resp);
				});
	}

	private void handleSendResponse(Job job, String rpcUrl, RequestAuth auth, HttpResponse<String> resp) {
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			job.fail("Remote SendMessage failed: HTTP " + resp.statusCode() + ": "
				+ conciseDetail(resp.body(), 512));
			return;
		}
		Map<?, ?> envReply;
		try {
			envReply = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
		} catch (Exception e) {
			job.fail("Remote SendMessage returned invalid JSON: " + describeFailure(e));
			return;
		}
		Object error = envReply.get("error");
		if (error != null) {
			job.fail("Remote SendMessage error: " + conciseDetail(error, 512));
			return;
		}
		Object result = envReply.get("result");
		if (result == null) {
			job.fail("Remote SendMessage response has neither result nor error");
			return;
		}

		// SendMessageResponse is a union {task: Task} | {message: Message}
		// (spec §3.2.3). gson's StreamingEventKindTypeAdapter wraps it with
		// the discriminator key on the wire. We unwrap to get a flat Task map
		// for the Covia Job output.
		Task remoteTask;
		ACell taskCell;
		try {
			String resultJson = JsonUtil.OBJECT_MAPPER.toJson(result);
			remoteTask = JsonUtil.OBJECT_MAPPER.fromJson(resultJson, Task.class);
			taskCell = unwrapKind(JSON.parse(resultJson), "task");
		} catch (Exception e) {
			job.fail("Remote SendMessage result is not a valid Task: " + describeFailure(e));
			return;
		}
		ACell rawResultCell = taskCell; // renamed, same semantics below

		String remoteTaskId = remoteTask.id();
		AMap<AString, ACell> current = job.getData();
		job.updateData(current.assoc(Fields.REMOTE_TASK_ID, Strings.create(remoteTaskId)));
		job.setCancelHook(() -> fireAndForgetCancel(rpcUrl, remoteTaskId, auth));

		if (remoteTask.status().state().isFinal()) {
			finishJobWithRemoteTask(job, remoteTask, rawResultCell);
			return;
		}
		if (remoteTask.status().state().isInterrupted()) {
			applyInterruptedState(job, remoteTask, rawResultCell);
			return;
		}
		startPoller(job, rpcUrl, remoteTaskId, auth);
	}

	/**
	 * Virtual-thread poller that mirrors the remote Task's state onto the
	 * local Job. Exits when the Job is finished locally (e.g. cancelled) or
	 * the remote reaches a terminal/interrupted state.
	 */
	private void startPoller(Job job, String rpcUrl, String remoteTaskId, RequestAuth auth) {
		Thread.ofVirtual().name("a2a-mirror-" + remoteTaskId).start(() -> {
			long deadline = System.currentTimeMillis() + POLL_MAX_LIFETIME.toMillis();
			while (!job.isFinished() && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(POLL_INTERVAL.toMillis());
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				if (job.isFinished()) return;

				PollResult p;
				try {
					p = pollRemote(rpcUrl, remoteTaskId, auth);
				} catch (Exception e) {
					log.warn("Mirror poll failed for remote task {}: {}", remoteTaskId, e.getMessage());
					continue; // transient errors don't fail the Job
				}

				if (p.task().status().state().isFinal()) {
					finishJobWithRemoteTask(job, p.task(), p.rawResultCell());
					return;
				}
				if (p.task().status().state().isInterrupted()) {
					applyInterruptedState(job, p.task(), p.rawResultCell());
					return;
				}
				// Non-terminal, non-interrupted: reflect STARTED if not already.
				if (!Status.STARTED.equals(job.getStatus())) {
					job.setStatus(Status.STARTED);
				}
			}
			if (!job.isFinished()) {
				job.fail("A2A mirror timed out after " + POLL_MAX_LIFETIME);
			}
		});
	}

	/**
	 * Unwrap a StreamingEventKind discriminator ({@code {"task": {...}}} or
	 * {@code {"message": {...}}}) to the inner map. Returns the cell unchanged
	 * if it's not wrapped. Needed because gson's type-hierarchy adapter always
	 * wraps when serialising any StreamingEventKind — including non-streaming
	 * responses like SendMessage's single result.
	 */
	@SuppressWarnings("unchecked")
	private static ACell unwrapKind(ACell cell, String kind) {
		if (cell instanceof AMap) {
			AMap<AString, ACell> map = (AMap<AString, ACell>) cell;
			ACell inner = map.get(Strings.create(kind));
			if (inner != null) return inner;
		}
		return cell;
	}

	/** Remote state + the raw result cell preserving the remote's exact JSON. */
	private record PollResult(Task task, ACell rawResultCell) {}

	private PollResult pollRemote(String rpcUrl, String remoteTaskId, RequestAuth auth) throws Exception {
		Map<String, Object> params = Map.of("id", remoteTaskId);
		Map<String, Object> envelope = rpcEnvelope(A2AMethods.GET_TASK_METHOD, params);
		HttpRequest req = postEnvelope(rpcUrl, envelope, auth);
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			throw new RuntimeException("Remote getTask failed: HTTP " + resp.statusCode()
				+ ": " + conciseDetail(resp.body(), 512));
		}
		@SuppressWarnings("rawtypes")
		Map envReply = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
		Object error = envReply.get("error");
		if (error != null) throw new RuntimeException("Remote getTask error: " + conciseDetail(error, 512));
		Object result = envReply.get("result");
		if (result == null) throw new RuntimeException("Remote getTask response has neither result nor error");
		String resultJson = JsonUtil.OBJECT_MAPPER.toJson(result);
		Task task = JsonUtil.OBJECT_MAPPER.fromJson(resultJson, Task.class);
		ACell raw = unwrapKind(JSON.parse(resultJson), "task");
		return new PollResult(task, raw);
	}

	private void finishJobWithRemoteTask(Job job, Task remote, ACell rawTaskCell) {
		if (remote.status().state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED) {
			job.completeWith(rawTaskCell);
		} else {
			// Failed / cancelled / rejected — terminal non-success. Surface the
			// remote state on the local Job with the task as output.
			AMap<AString, ACell> current = job.getData();
			AString coviaStatus = A2ACodec.fromTaskState(remote.status().state());
			job.updateData(current
					.assoc(Fields.STATUS, coviaStatus)
					.assoc(Fields.OUTPUT, rawTaskCell));
		}
	}

	private void applyInterruptedState(Job job, Task remote, ACell rawTaskCell) {
		AString coviaStatus = A2ACodec.fromTaskState(remote.status().state());
		AMap<AString, ACell> current = job.getData();
		job.updateData(current
				.assoc(Fields.STATUS, coviaStatus)
				.assoc(Fields.OUTPUT, rawTaskCell));
	}

	private void fireAndForgetCancel(String rpcUrl, String remoteTaskId, RequestAuth auth) {
		try {
			Map<String, Object> params = Map.of("id", remoteTaskId);
			Map<String, Object> envelope = rpcEnvelope(A2AMethods.CANCEL_TASK_METHOD, params);
			httpClient.sendAsync(postEnvelope(rpcUrl, envelope, auth), HttpResponse.BodyHandlers.discarding());
		} catch (Exception e) {
			log.warn("Best-effort remote cancel failed for {}: {}", remoteTaskId, e.getMessage());
		}
	}

	// ==================== envelope + HTTP helpers ====================

	private static Map<String, Object> rpcEnvelope(String method, Object params) {
		Map<String, Object> env = new LinkedHashMap<>();
		env.put("jsonrpc", "2.0");
		env.put("id", UUID.randomUUID().toString());
		env.put("method", method);
		env.put("params", params);
		return env;
	}

	static HttpRequest postEnvelope(String rpcUrl, Map<String, Object> envelope, String bearer) {
		return postEnvelope(rpcUrl, envelope, RequestAuth.bearer(bearer));
	}

	private static HttpRequest postEnvelope(String rpcUrl, Map<String, Object> envelope, RequestAuth auth) {
		String body = JsonUtil.OBJECT_MAPPER.toJson(envelope);
		HttpRequest.Builder builder = HttpRequest.newBuilder(authUri(rpcUrl, auth))
				.header("Content-Type", "application/json")
				.header("Accept", "application/a2a+json, application/json")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		applyAuth(builder, auth);
		return builder.build();
	}

	private static URI authUri(String url, RequestAuth auth) {
		URI uri = URI.create(url);
		return auth == null ? uri : auth.applyTo(uri);
	}

	private static void applyAuth(HttpRequest.Builder builder, RequestAuth auth) {
		if (auth != null) auth.applyHeaders(builder);
	}

	/**
	 * Resolve standard HTTP Bearer authentication for one outbound A2A
	 * invocation. A secret reference is resolved in the caller's SecretStore;
	 * a literal token is accepted for one-off interoperability. These inputs are
	 * transport credentials only — they are never added to the A2A payload.
	 */
	String resolveBearer(ACell input, RequestContext ctx) {
		AString secret = RT.ensureString(RT.getIn(input, Fields.BEARER_SECRET));
		AString literal = RT.ensureString(RT.getIn(input, Fields.BEARER_TOKEN));
		if (secret != null && literal != null) {
			throw new IllegalArgumentException("Specify only one of 'bearerSecret' or 'bearerToken'");
		}
		if (literal != null) {
			String token = literal.toString();
			if (token.isBlank()) throw new IllegalArgumentException("'bearerToken' must not be empty");
			return token;
		}
		if (secret == null) return null;
		String ref = secret.toString();
		if (ref.isBlank()) throw new IllegalArgumentException("'bearerSecret' must not be empty");
		String token = engine.resolveSecret(ref, ctx);
		if (token == null) {
			throw new IllegalArgumentException("Cannot resolve bearerSecret '" + ref
				+ "'; store it with secret:set or pass an existing s/<name> reference");
		}
		return token;
	}

	@SuppressWarnings("unused")
	private static final Class<?>[] _keepTypes = {JsonUtil.class};
}
