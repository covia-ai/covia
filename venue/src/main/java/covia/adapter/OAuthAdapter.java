package covia.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.auth.ucan.Capability;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.lattice.Namespace;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;

/**
 * Connected accounts: OAuth 2.0 authorization-code grants held on behalf of
 * venue users, so an agent can call a provider's API as the user without a
 * token ever reaching the model.
 *
 * <p>This is not login. Login OAuth ({@code /auth/{provider}}) proves who a
 * caller is and mints a venue bearer; a <em>connection</em> is a grant to act
 * on a user's data at a provider — Gmail, Microsoft Graph, GitHub, anything
 * that speaks OAuth 2.0 — obtained under the caller's venue identity.</p>
 *
 * <p><b>Storage.</b> A connection is this adapter's own state, at
 * {@code users/<did>/<provider>}: its provider, scope, expiry, when it was
 * obtained and whether it refreshes in the clear, and its tokens sealed under
 * the venue's secret store keys. It is not in the user's secret store: no
 * secret operation reads, replaces or deletes it, and its tokens leave only
 * through {@link #accessToken}, into a request. Grants an earlier venue kept in
 * the secret store as {@code oauth/<provider>} move here on first access.
 * Deleting an account revokes its grants at their providers.</p>
 *
 * <p><b>Authority.</b> A connection is the resource {@code <did>/oauth/<provider>}:
 * {@code crud/read} to see it in {@code oauth:status}, {@code oauth/connect},
 * {@code oauth/disconnect}, and {@code oauth/use} to send its token, which an
 * {@code nb.url} caveat binds to a destination. The owner holds them all.</p>
 *
 * <p><b>Flow.</b> {@code oauth:connect} mints a one-time {@code state} and a
 * PKCE verifier bound to the caller and returns the provider's authorisation
 * URL. The user approves in a browser; the provider redirects to
 * {@code /auth/connect/{provider}/callback}, which {@link #complete} serves:
 * the code is exchanged for tokens and the grant stored. Thereafter
 * {@code http:*} with {@code bearerSecret: "oauth/<provider>"} resolves a
 * fresh access token through {@link TokenSource}, refreshing when expired;
 * {@code oauth:status} reports connections without tokens; {@code
 * oauth:disconnect} forgets the grant and revokes it at the provider.</p>
 *
 * <p><b>Providers</b> are operator configuration ({@code adapters.oauth.providers}):
 * a client id, an {@code s/NAME} reference to the client secret (omit for a
 * public PKCE client), endpoints — filled in for the {@code google},
 * {@code microsoft} and {@code github} presets — default scopes and extra
 * authorisation parameters. Provider endpoints are trusted configuration, so
 * they are not subject to the HTTP adapter's SSRF guard.</p>
 */
public class OAuthAdapter extends AAdapter implements TokenSource {

	public static final Logger log = LoggerFactory.getLogger(OAuthAdapter.class);

	public static final String NAME = "oauth";

	/** The resource segment of a connection: {@code <did>/oauth/<provider>}. */
	public static final String RESOURCE_PREFIX = "oauth/";

	/** All of a user's connections, as a resource relative to the user. */
	static final AString ALL_CONNECTIONS = Strings.intern(RESOURCE_PREFIX);

	/** Secret-store name prefix of a grant kept by an earlier venue, moved on first access. */
	static final String LEGACY_GRANT_PREFIX = "oauth/";

	/** Refusal for the public principal, which signed-out callers share. */
	public static final String PUBLIC_HAS_NO_CONNECTIONS =
		"Connected accounts belong to a signed-in user, and the public principal has none";

	/** A callback for an account deleted since its connect began. */
	public static final String ACCOUNT_GONE =
		"The account that started this connection no longer exists; nothing was connected";

	/** Opening of the refusal for a provider the user has no connection to. */
	public static final String NOT_CONNECTED = "Not connected to ";

	static final long DEFAULT_PENDING_TTL_MILLIS = 10 * 60_000;
	static final int MAX_PENDING = 10_000;
	/** Refresh this long before the reported expiry, so a token is never handed out about to die. */
	static final long REFRESH_MARGIN_MILLIS = 60_000;

	private static final Pattern PROVIDER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

	// Configuration keys
	static final AString K_PROVIDERS = Strings.intern("providers");
	static final AString K_CLIENT_ID = Strings.intern("clientId");
	static final AString K_CLIENT_SECRET = Strings.intern("clientSecret");
	static final AString K_AUTHORIZATION_ENDPOINT = Strings.intern("authorizationEndpoint");
	static final AString K_TOKEN_ENDPOINT = Strings.intern("tokenEndpoint");
	static final AString K_REVOCATION_ENDPOINT = Strings.intern("revocationEndpoint");
	static final AString K_SCOPES = Strings.intern("scopes");
	static final AString K_PKCE = Strings.intern("pkce");
	static final AString K_PARAMS = Strings.intern("params");
	static final AString K_REDIRECT_URI = Strings.intern("redirectUri");
	static final AString K_RETURN_TO = Strings.intern("returnTo");
	static final AString K_PENDING_TTL_SECS = Strings.intern("pendingTtlSecs");

	// Input / output keys
	static final AString K_PROVIDER = Strings.intern("provider");
	static final AString K_STATE = Strings.intern("state");
	static final AString K_EXPIRES_AT = Strings.intern("expiresAt");
	static final AString K_CONNECTIONS = Strings.intern("connections");
	static final AString K_OBTAINED = Strings.intern("obtained");
	static final AString K_REFRESHABLE = Strings.intern("refreshable");
	static final AString K_DISCONNECTED = Strings.intern("disconnected");
	static final AString K_REVOKED = Strings.intern("revoked");
	static final AString K_SCOPE = Strings.intern("scope");

	// Stored grant keys
	static final AString G_PROVIDER = K_PROVIDER;
	static final AString G_ACCESS_TOKEN = Strings.intern("accessToken");
	static final AString G_REFRESH_TOKEN = Strings.intern("refreshToken");
	static final AString G_EXPIRES_AT = K_EXPIRES_AT;
	static final AString G_SCOPE = K_SCOPE;
	static final AString G_TOKEN_TYPE = Strings.intern("tokenType");
	static final AString G_OBTAINED = K_OBTAINED;
	/** A stored connection's tokens, sealed: {@code accessToken} and any {@code refreshToken}. */
	static final AString C_TOKENS = Strings.intern("tokens");
	/** Deleted users' sealed tokens, by DID then provider, until {@link #onUserDeleted} revokes them. */
	static final String REVOCATIONS = "revocations";

	/** One configured provider. */
	record Provider(String name, String clientId, String clientSecretRef, String authorizationEndpoint,
			String tokenEndpoint, String revocationEndpoint, List<String> scopes, boolean pkce,
			Map<String, String> params, String redirectUri) {}

	/** A connect awaiting the provider's callback, holding the connections of the
	 *  account that started it: a callback after that account is deleted stores nothing. */
	private record Pending(String provider, AString userDID, AdapterState connections, String verifier,
			List<String> scopes, String returnTo, long expiresAt) {}

	/** The outcome of a callback, for the route that renders it. */
	public record Completion(boolean ok, String provider, String message, String returnTo) {}

	private volatile Map<String, Provider> providers = Map.of();
	private volatile List<String> returnToAllow = List.of();
	private volatile long pendingTtlMillis = DEFAULT_PENDING_TTL_MILLIS;

	private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	/** Shared by every venue in the process: each client holds a selector thread for its lifetime. */
	private static final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.executor(VIRTUAL_EXECUTOR)
		.build();

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Connected accounts: OAuth 2.0 grants held for venue users so agents can call a provider's API "
			+ "as the user — connect, check status, disconnect — with access tokens reaching only the HTTP "
			+ "adapter (bearerSecret \"oauth/<provider>\"), never a model.";
	}

	@Override
	protected void installAssets() {
		installSkill("auth/oauth", "/skills/oauth.json");
		installAsset("oauth/connect", "/adapters/oauth/connect.json");
		installAsset("oauth/status", "/adapters/oauth/status.json");
		installAsset("oauth/disconnect", "/adapters/oauth/disconnect.json");
	}

	// ========== Configuration ==========

	/** Endpoints and conventions for the providers everyone connects to. */
	static Provider preset(String name) {
		return switch (name) {
			case "google" -> new Provider(name, null, null,
				"https://accounts.google.com/o/oauth2/v2/auth",
				"https://oauth2.googleapis.com/token",
				"https://oauth2.googleapis.com/revoke",
				List.of(), true, Map.of("access_type", "offline", "prompt", "consent"), null);
			case "microsoft" -> new Provider(name, null, null,
				"https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
				"https://login.microsoftonline.com/common/oauth2/v2.0/token",
				null, List.of(), true, Map.of(), null);
			case "github" -> new Provider(name, null, null,
				"https://github.com/login/oauth/authorize",
				"https://github.com/login/oauth/access_token",
				null, List.of(), false, Map.of(), null);
			default -> null;
		};
	}

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		Map<String, Provider> parsed = new LinkedHashMap<>();
		List<String> allow = List.of();
		long ttl = DEFAULT_PENDING_TTL_MILLIS;
		if (config != null) {
			ACell raw = config.get(K_PROVIDERS);
			if (raw != null) {
				AMap<AString, ACell> map = RT.ensureMap(raw);
				if (map == null) throw new IllegalArgumentException("adapters.oauth.providers must be a map of provider name to settings");
				for (var entry : map.entrySet()) {
					String name = RT.ensureString(entry.getKey()) != null ? entry.getKey().toString() : null;
					if (name == null || !PROVIDER_NAME.matcher(name).matches()) {
						throw new IllegalArgumentException("adapters.oauth.providers: provider names are lowercase letters, digits and dashes, got: " + entry.getKey());
					}
					parsed.put(name, provider(name, RT.ensureMap(entry.getValue())));
				}
			}
			ACell rt = config.get(K_RETURN_TO);
			if (rt != null) {
				AVector<ACell> v = RT.ensureVector(rt);
				if (v == null) throw new IllegalArgumentException("adapters.oauth.returnTo must be an array of URL prefixes");
				List<String> prefixes = new ArrayList<>();
				for (long i = 0; i < v.count(); i++) {
					AString s = RT.ensureString(v.get(i));
					if (s == null || s.toString().isBlank()) throw new IllegalArgumentException("adapters.oauth.returnTo entries must be URL prefixes");
					prefixes.add(s.toString().trim());
				}
				allow = List.copyOf(prefixes);
			}
			ACell ttlCell = config.get(K_PENDING_TTL_SECS);
			if (ttlCell != null) {
				CVMLong n = RT.ensureLong(ttlCell);
				if (n == null || n.longValue() < 30 || n.longValue() > 3600) {
					throw new IllegalArgumentException("adapters.oauth.pendingTtlSecs must be from 30 to 3600");
				}
				ttl = n.longValue() * 1000;
			}
		}
		providers = Map.copyOf(parsed);
		returnToAllow = allow;
		pendingTtlMillis = ttl;
		return true;
	}

	private static Provider provider(String name, AMap<AString, ACell> settings) {
		if (settings == null) throw new IllegalArgumentException("adapters.oauth.providers." + name + " must be a settings object");
		Provider base = preset(name);
		String clientId = text(settings, K_CLIENT_ID);
		if (clientId == null) throw new IllegalArgumentException("adapters.oauth.providers." + name + ".clientId is required");
		String secretRef = text(settings, K_CLIENT_SECRET);
		if (secretRef != null && !Engine.isSecretRef(secretRef)) {
			throw new IllegalArgumentException("adapters.oauth.providers." + name
				+ ".clientSecret must be an s/NAME reference to a venue secret, never the literal secret");
		}
		String authz = or(text(settings, K_AUTHORIZATION_ENDPOINT), base != null ? base.authorizationEndpoint() : null);
		String token = or(text(settings, K_TOKEN_ENDPOINT), base != null ? base.tokenEndpoint() : null);
		String revoke = or(text(settings, K_REVOCATION_ENDPOINT), base != null ? base.revocationEndpoint() : null);
		if (authz == null || token == null) {
			throw new IllegalArgumentException("adapters.oauth.providers." + name
				+ " needs authorizationEndpoint and tokenEndpoint (only google, microsoft and github are presets)");
		}
		requireHttps(name, K_AUTHORIZATION_ENDPOINT, authz);
		requireHttps(name, K_TOKEN_ENDPOINT, token);
		List<String> scopes = base != null ? base.scopes() : List.of();
		ACell scopesCell = settings.get(K_SCOPES);
		if (scopesCell != null) {
			AVector<ACell> v = RT.ensureVector(scopesCell);
			if (v == null) throw new IllegalArgumentException("adapters.oauth.providers." + name + ".scopes must be an array of strings");
			List<String> list = new ArrayList<>();
			for (long i = 0; i < v.count(); i++) {
				AString s = RT.ensureString(v.get(i));
				if (s == null || s.toString().isBlank()) throw new IllegalArgumentException("adapters.oauth.providers." + name + ".scopes entries must be strings");
				list.add(s.toString().trim());
			}
			scopes = List.copyOf(list);
		}
		boolean pkce = (base != null) ? base.pkce() : true;
		ACell pkceCell = settings.get(K_PKCE);
		if (pkceCell != null) {
			if (!(pkceCell instanceof CVMBool b)) throw new IllegalArgumentException("adapters.oauth.providers." + name + ".pkce must be a boolean");
			pkce = b.booleanValue();
		}
		Map<String, String> params = new LinkedHashMap<>(base != null ? base.params() : Map.of());
		ACell paramsCell = settings.get(K_PARAMS);
		if (paramsCell != null) {
			AMap<AString, ACell> pm = RT.ensureMap(paramsCell);
			if (pm == null) throw new IllegalArgumentException("adapters.oauth.providers." + name + ".params must be a map of strings");
			for (var e : pm.entrySet()) {
				AString v = RT.ensureString(e.getValue());
				if (v == null) throw new IllegalArgumentException("adapters.oauth.providers." + name + ".params values must be strings");
				params.put(e.getKey().toString(), v.toString());
			}
		}
		return new Provider(name, clientId, secretRef, authz, token, revoke, scopes, pkce, Map.copyOf(params),
			text(settings, K_REDIRECT_URI));
	}

	private static void requireHttps(String name, AString key, String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (Exception e) {
			throw new IllegalArgumentException("adapters.oauth.providers." + name + "." + key + " is not a URL: " + url);
		}
		String scheme = uri.getScheme();
		boolean loopback = uri.getHost() != null && (uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1"));
		if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && loopback)) {
			throw new IllegalArgumentException("adapters.oauth.providers." + name + "." + key + " must be https (http only for loopback): " + url);
		}
	}

	private static String text(AMap<AString, ACell> m, AString key) {
		AString s = RT.ensureString(m.get(key));
		return (s != null && !s.toString().isBlank()) ? s.toString().trim() : null;
	}

	private static String or(String a, String b) {
		return (a != null) ? a : b;
	}

	@Override
	public AMap<AString, ACell> info() {
		AVector<ACell> out = Vectors.empty();
		for (Provider p : providers.values()) {
			out = out.conj(Maps.of(
				Fields.NAME, Strings.create(p.name()),
				K_SCOPES, strings(p.scopes()),
				K_PKCE, CVMBool.create(p.pkce())));
		}
		return Maps.of(K_PROVIDERS, out);
	}

	/** The configured providers, by name. */
	public Map<String, Provider> providers() {
		return providers;
	}

	// ========== Operations ==========

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		try {
			ACell result = switch (subOp == null ? "" : subOp) {
				case "connect" -> handleConnect(ctx, input);
				case "status" -> handleStatus(ctx, input);
				case "disconnect" -> handleDisconnect(ctx, input);
				default -> throw new IllegalArgumentException("Unknown oauth operation: " + subOp);
			};
			return CompletableFuture.completedFuture(result);
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private Provider providerFor(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, K_PROVIDER));
		if (name == null || name.isEmpty()) throw new IllegalArgumentException("provider is required");
		Provider p = providers.get(name.toString());
		if (p == null) {
			throw new IllegalArgumentException("No OAuth provider '" + name + "' is configured on this venue"
				+ (providers.isEmpty() ? " (adapters.oauth.providers is empty)" : "; configured: " + String.join(", ", providers.keySet())));
		}
		return p;
	}

	/** A provider name an operation names, whether or not it is configured. */
	private static String providerName(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, K_PROVIDER));
		if (name == null || name.isEmpty()) throw new IllegalArgumentException("provider is required");
		if (!PROVIDER_NAME.matcher(name.toString()).matches()) {
			throw new IllegalArgumentException("provider names are lowercase letters, digits and dashes, got: " + name);
		}
		return name.toString();
	}

	/** The resource a connection to {@code provider} is, relative to its user. */
	static AString resource(String provider) {
		return Strings.create(RESOURCE_PREFIX + provider);
	}

	private AString userOf(RequestContext ctx, String op) {
		if (ctx == null || ctx.isAnonymous() || ctx.getUserDID() == null) {
			throw new IllegalArgumentException(op + " needs an authenticated caller — a connection belongs to a venue user");
		}
		if (engine.isPublicPrincipal(ctx.getUserDID())) throw new IllegalArgumentException(PUBLIC_HAS_NO_CONNECTIONS);
		return ctx.getUserDID();
	}

	/** {@code oauth:connect}: the URL the user opens to approve, bound to a one-time state. */
	private ACell handleConnect(RequestContext ctx, ACell input) {
		AString userDID = userOf(ctx, "oauth:connect");
		Provider p = providerFor(input);
		engine.requireAuthority(ctx, resource(p.name()), Abilities.OAUTH_CONNECT);
		List<String> scopes = p.scopes();
		ACell scopesCell = RT.getIn(input, K_SCOPES);
		if (scopesCell != null) {
			AVector<ACell> v = RT.ensureVector(scopesCell);
			if (v == null) throw new IllegalArgumentException("scopes must be an array of strings");
			List<String> list = new ArrayList<>();
			for (long i = 0; i < v.count(); i++) {
				AString s = RT.ensureString(v.get(i));
				if (s == null || s.toString().isBlank()) throw new IllegalArgumentException("scopes entries must be strings");
				list.add(s.toString().trim());
			}
			scopes = List.copyOf(list);
		}
		String returnTo = returnTo(RT.ensureString(RT.getIn(input, K_RETURN_TO)));

		sweepPending();
		if (pending.size() >= MAX_PENDING) {
			throw new IllegalStateException("Too many connections awaiting approval; try again later");
		}
		engine.getVenueState().users().ensure(userDID);
		AdapterState connections = adapterState(userDID);
		String state = randomToken(24);
		String verifier = p.pkce() ? randomToken(32) : null;
		long expiresAt = System.currentTimeMillis() + pendingTtlMillis;
		pending.put(state, new Pending(p.name(), userDID, connections, verifier, scopes, returnTo, expiresAt));

		return Maps.of(
			K_PROVIDER, Strings.create(p.name()),
			Fields.URL, Strings.create(authorizationUrl(p, state, verifier, scopes)),
			K_STATE, Strings.create(state),
			K_EXPIRES_AT, CVMLong.create(expiresAt),
			K_SCOPES, strings(scopes));
	}

	/** Where the browser goes after the callback: a venue-relative path, or an operator-allowed prefix. */
	private String returnTo(AString raw) {
		if (raw == null || raw.isEmpty()) return null;
		String value = raw.toString().trim();
		if (value.startsWith("/") && !value.startsWith("//")) return value;
		for (String prefix : returnToAllow) if (value.startsWith(prefix)) return value;
		throw new IllegalArgumentException("returnTo must be a venue-relative path or start with one of adapters.oauth.returnTo");
	}

	String authorizationUrl(Provider p, String state, String verifier, List<String> scopes) {
		StringBuilder url = new StringBuilder(p.authorizationEndpoint());
		url.append(p.authorizationEndpoint().contains("?") ? '&' : '?');
		url.append("response_type=code");
		url.append("&client_id=").append(enc(p.clientId()));
		url.append("&redirect_uri=").append(enc(redirectUri(p)));
		if (!scopes.isEmpty()) url.append("&scope=").append(enc(String.join(" ", scopes)));
		url.append("&state=").append(enc(state));
		if (verifier != null) {
			url.append("&code_challenge=").append(enc(challenge(verifier)));
			url.append("&code_challenge_method=S256");
		}
		for (var e : p.params().entrySet()) {
			url.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
		}
		return url.toString();
	}

	/** The venue's callback for a provider — the URL registered with the provider's client. */
	public String redirectUri(Provider p) {
		if (p.redirectUri() != null) return p.redirectUri();
		String base = (engine != null) ? engine.config().getBaseUrl() : "http://localhost:8080";
		if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		return base + "/auth/connect/" + p.name() + "/callback";
	}

	/**
	 * The provider's callback: consumes the one-time state, exchanges the code
	 * and stores the grant for the user who started the connect. Every
	 * failure is a {@link Completion} the route renders — never an exception
	 * in the user's browser.
	 */
	public Completion complete(String providerName, String state, String code, String error, String errorDescription) {
		Pending pend = (state != null && !state.isBlank()) ? pending.remove(state) : null;
		if (pend == null || pend.expiresAt() < System.currentTimeMillis()) {
			return new Completion(false, providerName,
				"This connection request is unknown or has expired; start again with oauth:connect", null);
		}
		if (!pend.provider().equals(providerName)) {
			return new Completion(false, providerName, "This connection request was for another provider", null);
		}
		if (error != null && !error.isBlank()) {
			return new Completion(false, providerName, "The provider refused the connection: " + error
				+ ((errorDescription != null && !errorDescription.isBlank()) ? " — " + errorDescription : ""),
				pend.returnTo());
		}
		if (code == null || code.isBlank()) {
			return new Completion(false, providerName, "The provider sent no authorization code", pend.returnTo());
		}
		Provider p = providers.get(providerName);
		if (p == null) {
			return new Completion(false, providerName, "Provider '" + providerName + "' is no longer configured", pend.returnTo());
		}
		AMap<AString, ACell> tokens;
		try {
			tokens = exchange(p, code, pend.verifier());
		} catch (Exception e) {
			log.warn("OAuth token exchange with {} failed: {}", providerName, describeFailure(e));
			return new Completion(false, providerName,
				"Token exchange with " + providerName + " failed: " + describeFailure(e), pend.returnTo());
		}
		try {
			storeGrant(pend, p, tokens);
		} catch (AuthException gone) {
			// Issued for an account deleted since the connect began: kept nowhere, so left live nowhere
			revokeGrant(p, str(tokens, Strings.intern("refresh_token")), str(tokens, Strings.intern("access_token")));
			return new Completion(false, providerName, ACCOUNT_GONE, pend.returnTo());
		} catch (RuntimeException e) {
			log.warn("Storing the {} connection failed: {}", providerName, describeFailure(e));
			return new Completion(false, providerName,
				"The connection to " + providerName + " could not be stored: " + describeFailure(e), pend.returnTo());
		}
		return new Completion(true, providerName, "Connected to " + providerName, pend.returnTo());
	}

	/** {@code oauth:status}: the caller's connections, without their tokens. */
	private ACell handleStatus(RequestContext ctx, ACell input) {
		AString userDID = userOf(ctx, "oauth:status");
		String only = (RT.getIn(input, K_PROVIDER) != null) ? providerName(input) : null;
		engine.requireAuthority(ctx, (only != null) ? resource(only) : ALL_CONNECTIONS, Capability.CRUD_READ);
		AdapterState state = connections(userDID);
		AMap<AString, ACell> mine = RT.ensureMap(state.read(state.userPath(userDID, "")));
		AVector<ACell> out = Vectors.empty();
		if (mine != null) for (var entry : mine.entrySet()) {
			if (only != null && !only.equals(entry.getKey().toString())) continue;
			AMap<AString, ACell> connection = RT.ensureMap(entry.getValue());
			if (connection != null) out = out.conj(summary(connection));
		}
		return Maps.of(K_CONNECTIONS, out);
	}

	/** {@code oauth:disconnect}: forget the grant, and revoke it at the provider when it can. */
	private ACell handleDisconnect(RequestContext ctx, ACell input) throws IOException {
		AString userDID = userOf(ctx, "oauth:disconnect");
		String provider = providerName(input);
		engine.requireAuthority(ctx, resource(provider), Abilities.OAUTH_DISCONNECT);
		AdapterState state = connections(userDID);
		String path = state.userPath(userDID, provider);
		// Taken in one step, so a refresh finishing meanwhile cannot write the grant back
		AtomicReference<AMap<AString, ACell>> taken = new AtomicReference<>();
		state.transact(draft -> {
			taken.set(RT.ensureMap(draft.read(path)));
			if (taken.get() != null) draft.delete(path);
		});
		if (taken.get() == null) throw new IllegalArgumentException(NOT_CONNECTED + provider);
		boolean revoked = revokeSealed(providers.get(provider), taken.get().get(C_TOKENS));
		return Maps.of(
			K_PROVIDER, Strings.create(provider),
			K_DISCONNECTED, CVMBool.TRUE,
			K_REVOKED, CVMBool.create(revoked));
	}

	private static IllegalArgumentException notConnected(String provider) {
		return new IllegalArgumentException(NOT_CONNECTED + provider + " — run oauth:connect and approve in the browser");
	}

	// ========== TokenSource ==========

	@Override
	public String accessToken(RequestContext ctx, String provider, String destination) throws IOException {
		AString userDID = userOf(ctx, "oauth/" + provider);
		if (!PROVIDER_NAME.matcher(provider).matches()) throw notConnected(provider);
		engine.requireAuthority(ctx, resource(provider), Abilities.OAUTH_USE,
			(destination != null) ? Strings.create(destination) : null);
		AdapterState state = connections(userDID);
		String path = state.userPath(userDID, provider);
		AMap<AString, ACell> connection = RT.ensureMap(state.read(path));
		if (connection == null) throw notConnected(provider);
		if (!expiring(connection)) return str(tokens(connection), G_ACCESS_TOKEN);

		// One refresh per (user, provider) at a time; a second caller waits and reads the result.
		Object lock = refreshLocks.computeIfAbsent(userDID + "|" + provider, k -> new Object());
		synchronized (lock) {
			AMap<AString, ACell> current = RT.ensureMap(state.read(path));
			if (current == null) throw notConnected(provider);
			AMap<AString, ACell> tokens = tokens(current);
			if (!expiring(current)) return str(tokens, G_ACCESS_TOKEN);
			String refreshToken = str(tokens, G_REFRESH_TOKEN);
			Provider p = providers.get(provider);
			if (refreshToken == null || p == null) {
				throw new IllegalArgumentException("The connection to " + provider
					+ " has expired and cannot be refreshed — reconnect with oauth:connect");
			}
			AMap<AString, ACell> refreshed;
			try {
				refreshed = refresh(p, refreshToken);
			} catch (Exception e) {
				throw new IllegalArgumentException("The connection to " + provider
					+ " could not be refreshed (" + describeFailure(e) + ") — reconnect with oauth:connect");
			}
			AMap<AString, ACell> fresh = tokens
				.assoc(G_ACCESS_TOKEN, Strings.create(str(refreshed, Strings.intern("access_token"))));
			String newRefresh = str(refreshed, Strings.intern("refresh_token"));
			if (newRefresh != null) fresh = fresh.assoc(G_REFRESH_TOKEN, Strings.create(newRefresh));
			AMap<AString, ACell> updated = withTokens(current, fresh)
				.assoc(G_EXPIRES_AT, CVMLong.create(expiresAt(refreshed)));
			// Written back only over the connection it refreshed: a disconnect or a new connect meanwhile wins
			AtomicReference<ACell> seen = new AtomicReference<>();
			state.transact(draft -> {
				seen.set(draft.read(path));
				if (current.equals(seen.get())) draft.write(path, updated);
			});
			if (current.equals(seen.get())) return str(fresh, G_ACCESS_TOKEN);
			AMap<AString, ACell> replaced = RT.ensureMap(seen.get());
			if (replaced == null) throw notConnected(provider);
			return str(tokens(replaced), G_ACCESS_TOKEN);
		}
	}

	@Override
	public boolean connected(AString userDID, String provider) {
		if (userDID == null || provider == null || !PROVIDER_NAME.matcher(provider).matches()) return false;
		AdapterState state = connections(userDID);
		return state.read(state.userPath(userDID, provider)) != null;
	}

	private static boolean expiring(AMap<AString, ACell> grant) {
		CVMLong at = RT.ensureLong(grant.get(G_EXPIRES_AT));
		if (at == null || at.longValue() <= 0) return false;   // no expiry reported: treat as long-lived
		return at.longValue() - REFRESH_MARGIN_MILLIS <= System.currentTimeMillis();
	}

	// ========== Provider calls ==========

	private AMap<AString, ACell> exchange(Provider p, String code, String verifier) throws IOException {
		StringBuilder form = new StringBuilder("grant_type=authorization_code")
			.append("&code=").append(enc(code))
			.append("&redirect_uri=").append(enc(redirectUri(p)))
			.append("&client_id=").append(enc(p.clientId()));
		if (verifier != null) form.append("&code_verifier=").append(enc(verifier));
		appendClientSecret(form, p);
		return tokenCall(p, form.toString());
	}

	private AMap<AString, ACell> refresh(Provider p, String refreshToken) throws IOException {
		StringBuilder form = new StringBuilder("grant_type=refresh_token")
			.append("&refresh_token=").append(enc(refreshToken))
			.append("&client_id=").append(enc(p.clientId()));
		appendClientSecret(form, p);
		return tokenCall(p, form.toString());
	}

	private void appendClientSecret(StringBuilder form, Provider p) {
		if (p.clientSecretRef() == null) return;
		String secret = engine.resolveSecret(p.clientSecretRef(), engine.venueContext());
		if (secret == null) {
			throw new IllegalStateException("adapters.oauth.providers." + p.name() + ".clientSecret references "
				+ p.clientSecretRef() + ", which is not set in the venue's secret store");
		}
		form.append("&client_secret=").append(enc(secret));
	}

	private AMap<AString, ACell> tokenCall(Provider p, String form) throws IOException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(p.tokenEndpoint()))
			.timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while calling " + p.tokenEndpoint());
		}
		ACell body;
		try {
			body = JSON.parse(response.body());
		} catch (Exception e) {
			throw new IOException(p.name() + " token endpoint returned " + response.statusCode() + " with a non-JSON body");
		}
		AMap<AString, ACell> tokens = RT.ensureMap(body);
		String error = (tokens != null) ? str(tokens, Strings.intern("error")) : null;
		if (response.statusCode() != 200 || tokens == null || error != null) {
			String description = (tokens != null) ? str(tokens, Strings.intern("error_description")) : null;
			throw new IOException(p.name() + " token endpoint returned " + response.statusCode()
				+ ((error != null) ? ": " + error : "") + ((description != null) ? " — " + description : ""));
		}
		if (str(tokens, Strings.intern("access_token")) == null) {
			throw new IOException(p.name() + " token endpoint returned no access_token");
		}
		return tokens;
	}

	/** RFC 7009 revocation; false when the provider refuses or has no endpoint. */
	private boolean revoke(Provider p, String token) {
		if (token == null) return false;
		StringBuilder form = new StringBuilder("token=").append(enc(token))
			.append("&client_id=").append(enc(p.clientId()));
		try {
			appendClientSecret(form, p);
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(p.revocationEndpoint()))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form.toString()))
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() >= 200 && response.statusCode() < 300;
		} catch (Exception e) {
			log.warn("Token revocation at {} failed: {}", p.revocationEndpoint(), describeFailure(e));
			return false;
		}
	}

	/** Revokes a grant by its refresh token, which takes its access tokens with it, else by its access token;
	 *  false when its provider is no longer configured or has no revocation endpoint. */
	private boolean revokeGrant(Provider p, String refreshToken, String accessToken) {
		if (p == null || p.revocationEndpoint() == null) return false;
		return revoke(p, (refreshToken != null) ? refreshToken : accessToken);
	}

	/** Revokes the grant whose tokens a stored connection holds sealed. */
	private boolean revokeSealed(Provider p, ACell sealed) {
		if (p == null || p.revocationEndpoint() == null) return false;
		AMap<AString, ACell> tokens = opened(sealed);
		return revokeGrant(p, str(tokens, G_REFRESH_TOKEN), str(tokens, G_ACCESS_TOKEN));
	}

	// ========== Connections ==========

	/** Stores the grant a callback obtained, for the account that started the connect. */
	private void storeGrant(Pending pend, Provider p, AMap<AString, ACell> tokens) {
		AMap<AString, ACell> grant = Maps.of(
			G_PROVIDER, Strings.create(p.name()),
			G_ACCESS_TOKEN, Strings.create(str(tokens, Strings.intern("access_token"))),
			G_EXPIRES_AT, CVMLong.create(expiresAt(tokens)),
			G_OBTAINED, CVMLong.create(System.currentTimeMillis()));
		String refresh = str(tokens, Strings.intern("refresh_token"));
		if (refresh != null) grant = grant.assoc(G_REFRESH_TOKEN, Strings.create(refresh));
		String scope = str(tokens, Strings.intern("scope"));
		grant = grant.assoc(G_SCOPE, Strings.create((scope != null) ? scope : String.join(" ", pend.scopes())));
		String type = str(tokens, Strings.intern("token_type"));
		if (type != null) grant = grant.assoc(G_TOKEN_TYPE, Strings.create(type));
		AdapterState state = pend.connections();
		state.write(state.userPath(pend.userDID(), p.name()), connection(grant));
	}

	private static long expiresAt(AMap<AString, ACell> tokens) {
		CVMLong in = RT.ensureLong(tokens.get(Strings.intern("expires_in")));
		return (in != null) ? System.currentTimeMillis() + in.longValue() * 1000 : 0;
	}

	/** A grant as stored: its facts in the clear, its tokens sealed. */
	private AMap<AString, ACell> connection(AMap<AString, ACell> grant) {
		return connection(grant, keys());
	}

	private static AMap<AString, ACell> connection(AMap<AString, ACell> grant, SecretStore.Keys keys) {
		AMap<AString, ACell> tokens = Maps.of(G_ACCESS_TOKEN, grant.get(G_ACCESS_TOKEN));
		if (grant.get(G_REFRESH_TOKEN) != null) tokens = tokens.assoc(G_REFRESH_TOKEN, grant.get(G_REFRESH_TOKEN));
		return withTokens(grant.dissoc(G_ACCESS_TOKEN).dissoc(G_REFRESH_TOKEN), tokens, keys);
	}

	/** A stored connection holding {@code tokens}, sealed. */
	private AMap<AString, ACell> withTokens(AMap<AString, ACell> connection, AMap<AString, ACell> tokens) {
		return withTokens(connection, tokens, keys());
	}

	private static AMap<AString, ACell> withTokens(AMap<AString, ACell> connection, AMap<AString, ACell> tokens,
			SecretStore.Keys keys) {
		return connection
			.assoc(K_REFRESHABLE, CVMBool.create(tokens.get(G_REFRESH_TOKEN) != null))
			.assoc(C_TOKENS, SecretStore.seal(Strings.create(JSON.toString(tokens)), keys));
	}

	/** A stored connection's tokens, opened. */
	private AMap<AString, ACell> tokens(AMap<AString, ACell> connection) {
		return opened(connection.get(C_TOKENS));
	}

	/** Tokens {@link #withTokens} sealed, opened. */
	private AMap<AString, ACell> opened(ACell sealed) {
		AString raw = SecretStore.unseal(sealed, keys());
		AMap<AString, ACell> tokens = (raw != null) ? RT.ensureMap(JSON.parse(raw.toString())) : null;
		if (tokens == null || str(tokens, G_ACCESS_TOKEN) == null) {
			throw new IllegalStateException(SecretStore.INVALID_RECORD);
		}
		return tokens;
	}

	private SecretStore.Keys keys() {
		return SecretStore.keys(engine.getKeyPair());
	}

	private static AMap<AString, ACell> summary(AMap<AString, ACell> connection) {
		AMap<AString, ACell> out = Maps.of(
			K_PROVIDER, connection.get(G_PROVIDER),
			K_REFRESHABLE, CVMBool.create(CVMBool.TRUE.equals(connection.get(K_REFRESHABLE))));
		if (connection.get(G_SCOPE) != null) out = out.assoc(K_SCOPE, connection.get(G_SCOPE));
		if (connection.get(G_EXPIRES_AT) != null) out = out.assoc(K_EXPIRES_AT, connection.get(G_EXPIRES_AT));
		if (connection.get(G_OBTAINED) != null) out = out.assoc(K_OBTAINED, connection.get(G_OBTAINED));
		return out;
	}

	/**
	 * The connections of one user, fenced to their account, after moving any
	 * grants an earlier venue kept in their secret store.
	 */
	private AdapterState connections(AString userDID) {
		AdapterState state = adapterState(userDID);
		moveLegacyGrants(userDID, state);
		return state;
	}

	/**
	 * Converts on use the grants an earlier venue kept among a user's secrets
	 * ({@link #moveLegacyGrants(AMap, AMap, SecretStore.Keys)}), in one venue
	 * transaction with their removal from the secret store. Nothing else
	 * addresses those names: secret names hold no {@code /}.
	 */
	private void moveLegacyGrants(AString userDID, AdapterState state) {
		User user = engine.getVenueState().users().get(userDID);
		if (user == null || !hasLegacyGrant(RT.ensureMap(user.secrets().cursor().get()))) return;
		SecretStore.Keys keys = keys();
		String path = state.userPath(userDID, "");
		engine.getVenueState().transact(draft -> {
			User owner = draft.users().get(userDID);
			if (owner == null) return;
			AdapterState mine = state.inVenue(draft);
			AMap<AString, ACell> secrets = RT.ensureMap(owner.secrets().cursor().get());
			AMap<AString, ACell> connections = RT.ensureMap(mine.read(path));
			LegacyMove move = moveLegacyGrants(secrets, connections, keys);
			if (move.secrets() == secrets) return;
			owner.secrets().cursor().set(move.secrets());
			mine.write(path, move.connections());
		});
	}

	/** A user's secret records and connections after {@link #moveLegacyGrants(AMap, AMap, SecretStore.Keys)}. */
	record LegacyMove(AMap<AString, ACell> secrets, AMap<AString, ACell> connections) {}

	/** Refusal for a grant an earlier venue kept that cannot be read as one. */
	public static final String UNREADABLE_LEGACY_GRANT =
		"A connected account kept by an earlier venue is not a readable grant: ";

	/**
	 * Moves the grants an earlier venue kept among a user's secret records, as
	 * {@code oauth/<provider>}, into their connections: a connection made since
	 * is kept over the older grant. Pure: the venue's keys are passed in, and
	 * with no such grant both maps come back as the same cells.
	 *
	 * @throws IllegalStateException with {@link #UNREADABLE_LEGACY_GRANT} for a
	 * record that is not a readable grant, leaving every record as it was
	 */
	static LegacyMove moveLegacyGrants(AMap<AString, ACell> secrets, AMap<AString, ACell> connections,
			SecretStore.Keys keys) {
		if (!hasLegacyGrant(secrets)) return new LegacyMove(secrets, connections);
		AMap<AString, ACell> remaining = secrets;
		AMap<AString, ACell> moved = (connections != null) ? connections : Maps.empty();
		for (var entry : secrets.entrySet()) {
			String name = entry.getKey().toString();
			if (!name.startsWith(LEGACY_GRANT_PREFIX)) continue;
			String provider = name.substring(LEGACY_GRANT_PREFIX.length());
			AMap<AString, ACell> grant = legacyGrant(entry.getValue(), keys);
			if (grant == null || !PROVIDER_NAME.matcher(provider).matches()) {
				throw new IllegalStateException(UNREADABLE_LEGACY_GRANT + name);
			}
			AString key = Strings.create(provider);
			if (moved.get(key) == null) moved = moved.assoc(key, connection(grant, keys));
			remaining = remaining.dissoc(entry.getKey());
		}
		return new LegacyMove(remaining, moved);
	}

	private static boolean hasLegacyGrant(AMap<AString, ACell> secrets) {
		if (secrets == null) return false;
		for (AString name : secrets.keySet()) {
			if (name.toString().startsWith(LEGACY_GRANT_PREFIX)) return true;
		}
		return false;
	}

	/** A grant as an earlier venue stored it (JSON of the grant fields), or null when it is unreadable or holds no token. */
	private static AMap<AString, ACell> legacyGrant(ACell record, SecretStore.Keys keys) {
		try {
			AString raw = SecretStore.unseal(record, keys);
			AMap<AString, ACell> grant = (raw != null) ? RT.ensureMap(JSON.parse(raw.toString())) : null;
			return (grant != null && str(grant, G_ACCESS_TOKEN) != null) ? grant : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Erases a deleted user's connections, keeping only their sealed tokens,
	 * outside them, for {@link #onUserDeleted} to revoke. An instance never
	 * installed has no keys to open them with, so keeps nothing.
	 */
	@Override
	public void deleteUserState(AString did, AdapterState state) {
		String path = state.userPath(did, "");
		AMap<AString, ACell> mine = RT.ensureMap(state.read(path));
		if (mine != null && engine != null) {
			AMap<AString, ACell> all = RT.ensureMap(state.read(REVOCATIONS));
			if (all == null) all = Maps.empty();
			AMap<AString, ACell> theirs = RT.ensureMap(all.get(did));
			if (theirs == null) theirs = Maps.empty();
			for (var entry : mine.entrySet()) {
				ACell sealed = RT.getIn(entry.getValue(), C_TOKENS);
				if (sealed != null) theirs = theirs.assoc(entry.getKey(), sealed);
			}
			if (!theirs.isEmpty()) state.write(REVOCATIONS, all.assoc(did, theirs));
		}
		state.delete(path);
	}

	/**
	 * Revokes a deleted user's grants at their providers, once and as
	 * {@code oauth:disconnect} does: a provider that refuses or cannot be
	 * reached keeps a grant the venue no longer holds. Grants an earlier venue
	 * kept among their secrets, and never moved, are revoked too.
	 */
	@Override
	public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
		if (engine == null) return;
		AtomicReference<AMap<AString, ACell>> taken = new AtomicReference<>();
		adapterState().transact(draft -> {
			AMap<AString, ACell> all = RT.ensureMap(draft.read(REVOCATIONS));
			taken.set((all != null) ? RT.ensureMap(all.get(did)) : null);
			if (taken.get() == null) return;
			AMap<AString, ACell> rest = all.dissoc(did);
			if (rest.isEmpty()) draft.delete(REVOCATIONS); else draft.write(REVOCATIONS, rest);
		});
		if (taken.get() != null) for (var entry : taken.get().entrySet()) {
			try {
				revokeSealed(providers.get(entry.getKey().toString()), entry.getValue());
			} catch (RuntimeException e) {
				log.warn("Could not revoke the deleted user {}'s {} connection: {}", did, entry.getKey(), describeFailure(e));
			}
		}
		AMap<AString, ACell> secrets = (previousUser != null) ? RT.ensureMap(previousUser.get(Namespace.S)) : null;
		if (!hasLegacyGrant(secrets)) return;
		SecretStore.Keys keys = keys();
		for (var entry : secrets.entrySet()) {
			String name = entry.getKey().toString();
			if (!name.startsWith(LEGACY_GRANT_PREFIX)) continue;
			AMap<AString, ACell> grant = legacyGrant(entry.getValue(), keys);
			if (grant != null) revokeGrant(providers.get(name.substring(LEGACY_GRANT_PREFIX.length())),
				str(grant, G_REFRESH_TOKEN), str(grant, G_ACCESS_TOKEN));
		}
	}

	// ========== Helpers ==========

	private void sweepPending() {
		long now = System.currentTimeMillis();
		pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
	}

	private String randomToken(int bytes) {
		byte[] buf = new byte[bytes];
		random.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	/** PKCE S256: base64url(SHA-256(verifier)). */
	static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String str(AMap<AString, ACell> m, AString key) {
		AString s = (m != null) ? RT.ensureString(m.get(key)) : null;
		return (s != null && !s.isEmpty()) ? s.toString() : null;
	}

	private static AVector<ACell> strings(List<String> values) {
		AVector<ACell> out = Vectors.empty();
		for (String s : values) out = out.conj(Strings.create(s));
		return out;
	}

	/** Present for symmetry with the other case-insensitive lookups; provider names are lowercase by validation. */
	static String canonical(String provider) {
		return provider.toLowerCase(Locale.ROOT);
	}
}
