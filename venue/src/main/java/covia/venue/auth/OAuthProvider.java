package covia.venue.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.jwt.JWT;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.Engine;
import covia.venue.server.AuthMiddleware;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/**
 * The venue as an OAuth 2.1 authorization server: a third-party or MCP client
 * obtains a bearer to act as a venue user, through the standard
 * authorization-code + PKCE flow, from operator-registered clients.
 *
 * <p><b>The token is a venue bearer.</b> An issued access token is the same
 * venue-signed EdDSA JWT the login flow mints ({@code sub} = the user's DID,
 * {@code iss}/{@code aud} = the venue), so it is accepted directly by every
 * venue surface — no separate token store, no introspection endpoint. The
 * resource server and the authorization server are the one venue, which is
 * why RFC 9728 protected-resource metadata can now name this server. A
 * granted {@code scope} rides on the token for audit; narrowing a client to
 * attenuated capabilities is the next step and is called out in CONFIG.md.</p>
 *
 * <p><b>Standards.</b> OAuth 2.1 shape: {@code response_type=code} only, PKCE
 * with {@code S256} required for every client, exact {@code redirect_uri}
 * matching (loopback allows any port per RFC 8252), one-time short-lived
 * authorization codes, refresh tokens, RFC 8414 authorization-server metadata,
 * RFC 7009 revocation. The resource owner is authenticated by presenting a
 * venue bearer to {@code /oauth/authorize} (Covia has no cookie session); a
 * browser consent page is deliberately out of this first cut.</p>
 *
 * <p><b>Operator config</b> is {@code oauth.provider} — see CONFIG.md. Absent
 * or {@code enabled:false}: no endpoints are registered and the server is not
 * advertised.</p>
 */
public class OAuthProvider {

	private static final Logger log = LoggerFactory.getLogger(OAuthProvider.class);

	private static final long DEFAULT_ACCESS_TTL_SECS = 3600;
	private static final long CODE_TTL_MILLIS = 60_000;
	private static final int MAX_PENDING_CODES = 10_000;
	private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}");

	// Config keys
	static final AString K_PROVIDER = Strings.intern("provider");
	static final AString K_ENABLED = Strings.intern("enabled");
	static final AString K_ISSUER = Strings.intern("issuer");
	static final AString K_ACCESS_TTL = Strings.intern("accessTokenTtlSecs");
	static final AString K_CLIENTS = Strings.intern("clients");
	static final AString K_REDIRECT_URIS = Strings.intern("redirectUris");
	static final AString K_SECRET = Strings.intern("secret");
	static final AString K_PUBLIC = Strings.intern("public");
	static final AString K_SCOPES = Strings.intern("scopes");
	static final AString K_NAME = Strings.intern("name");

	/** One operator-registered client. */
	record Client(String id, List<String> redirectUris, String secretRef, boolean isPublic,
			List<String> scopes, String name) {}

	/** An authorization code awaiting exchange. */
	private record Code(String clientId, AString userDID, String redirectUri, String challenge,
			List<String> scope, long expiresAt) {}

	/** A refresh grant. */
	private record Refresh(String clientId, AString userDID, List<String> scope) {}

	private final Engine engine;
	private final Map<String, Client> clients;
	private final long accessTtlSecs;
	private final String issuer;

	private final ConcurrentHashMap<String, Code> codes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Refresh> refreshTokens = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();

	private OAuthProvider(Engine engine, Map<String, Client> clients, long accessTtlSecs, String issuer) {
		this.engine = engine;
		this.clients = clients;
		this.accessTtlSecs = accessTtlSecs;
		this.issuer = issuer;
	}

	/**
	 * Builds the provider from {@code auth.oauth.provider}, or null when it is
	 * absent or disabled. Throws {@link IllegalArgumentException} on malformed
	 * configuration so a misconfigured provider fails venue startup rather than
	 * silently not registering.
	 */
	public static OAuthProvider from(Engine engine) {
		AMap<AString, ACell> auth = engine.config().getAuthConfig();
		AMap<AString, ACell> oauth = (auth != null) ? RT.ensureMap(auth.get(Strings.intern("oauth"))) : null;
		AMap<AString, ACell> cfg = (oauth != null) ? RT.ensureMap(oauth.get(K_PROVIDER)) : null;
		if (cfg == null || !Boolean.TRUE.equals(bool(cfg.get(K_ENABLED)))) return null;
		return build(engine, cfg);
	}

	/**
	 * Parses one {@code auth.oauth.provider} block into a provider (assumed
	 * enabled). Package-visible so configuration validation is unit-testable
	 * without launching a venue.
	 */
	static OAuthProvider build(Engine engine, AMap<AString, ACell> cfg) {
		long ttl = DEFAULT_ACCESS_TTL_SECS;
		CVMLong ttlCell = RT.ensureLong(cfg.get(K_ACCESS_TTL));
		if (ttlCell != null) {
			if (ttlCell.longValue() < 60 || ttlCell.longValue() > 86_400) {
				throw new IllegalArgumentException("auth.oauth.provider.accessTokenTtlSecs must be from 60 to 86400");
			}
			ttl = ttlCell.longValue();
		}
		// An explicit issuer (or the venue's configured baseUrl) is the stable
		// identifier; with neither — a venue on an ephemeral port, or behind a
		// proxy that sets Host — it is derived from each request.
		String issuer = str(cfg.get(K_ISSUER));
		if (issuer == null) {
			String base = base(engine);
			if (!base.endsWith(":0") && !base.startsWith("http://localhost:0")) issuer = base;
		}

		Map<String, Client> clients = new LinkedHashMap<>();
		AMap<AString, ACell> clientMap = RT.ensureMap(cfg.get(K_CLIENTS));
		if (clientMap != null) {
			for (var entry : clientMap.entrySet()) {
				String id = (RT.ensureString(entry.getKey()) != null) ? entry.getKey().toString() : null;
				if (id == null || !CLIENT_ID.matcher(id).matches()) {
					throw new IllegalArgumentException("auth.oauth.provider.clients: a client id is invalid: " + entry.getKey());
				}
				clients.put(id, client(id, RT.ensureMap(entry.getValue())));
			}
		}
		if (clients.isEmpty()) {
			throw new IllegalArgumentException("auth.oauth.provider is enabled but registers no clients");
		}
		log.info("OAuth authorization server enabled: issuer {}, {} client(s)", issuer, clients.size());
		return new OAuthProvider(engine, Map.copyOf(clients), ttl, issuer);
	}

	private static Client client(String id, AMap<AString, ACell> settings) {
		if (settings == null) throw new IllegalArgumentException("auth.oauth.provider.clients." + id + " must be a settings object");
		AVector<ACell> uris = RT.ensureVector(settings.get(K_REDIRECT_URIS));
		if (uris == null || uris.isEmpty()) {
			throw new IllegalArgumentException("auth.oauth.provider.clients." + id + ".redirectUris must list at least one URI");
		}
		List<String> redirects = new ArrayList<>();
		for (long i = 0; i < uris.count(); i++) {
			AString u = RT.ensureString(uris.get(i));
			if (u == null || u.toString().isBlank()) throw new IllegalArgumentException("auth.oauth.provider.clients." + id + ".redirectUris entries must be URIs");
			String uri = u.toString().trim();
			URI parsed;
			try {
				parsed = URI.create(uri);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("auth.oauth.provider.clients." + id + ".redirectUris: not a URI: " + uri);
			}
			if (parsed.getScheme() == null || parsed.getFragment() != null) {
				throw new IllegalArgumentException("auth.oauth.provider.clients." + id
					+ ".redirectUris must be absolute URIs without a fragment: " + uri);
			}
			// https everywhere; plain http only for a loopback native redirect; any
			// other scheme (a native app's custom scheme) is taken as registered.
			if ("http".equalsIgnoreCase(parsed.getScheme()) && !isLoopback(parsed)) {
				throw new IllegalArgumentException("auth.oauth.provider.clients." + id
					+ ".redirectUris: plain http is allowed only for loopback: " + uri);
			}
			redirects.add(uri);
		}
		String secretRef = str(settings.get(K_SECRET));
		boolean isPublic = Boolean.TRUE.equals(bool(settings.get(K_PUBLIC)));
		if (secretRef != null && (!secretRef.startsWith("s/") && !secretRef.startsWith("/s/"))) {
			throw new IllegalArgumentException("auth.oauth.provider.clients." + id + ".secret must be an s/NAME reference");
		}
		if (secretRef == null && !isPublic) {
			throw new IllegalArgumentException("auth.oauth.provider.clients." + id
				+ " needs either secret (an s/NAME reference) or public:true (a PKCE-only public client)");
		}
		List<String> scopes = new ArrayList<>();
		AVector<ACell> sc = RT.ensureVector(settings.get(K_SCOPES));
		if (sc != null) {
			for (long i = 0; i < sc.count(); i++) {
				AString s = RT.ensureString(sc.get(i));
				if (s == null || s.toString().isBlank()) throw new IllegalArgumentException("auth.oauth.provider.clients." + id + ".scopes entries must be strings");
				scopes.add(s.toString().trim());
			}
		}
		return new Client(id, List.copyOf(redirects), secretRef, isPublic, List.copyOf(scopes), str(settings.get(K_NAME)));
	}

	// ========== Routes ==========

	public void addRoutes(RoutesConfig routes) {
		routes.get("/.well-known/oauth-authorization-server", this::metadata);
		routes.get("/oauth/authorize", this::authorize, covia.venue.server.VenueRouteFeature.COVIA_API);
		routes.post("/oauth/token", this::token);
		routes.post("/oauth/revoke", this::revoke);
	}

	/** The issuer identifier: the configured one, else derived from this request. */
	public String issuer(Context ctx) {
		if (issuer != null) return issuer;
		String scheme = firstHeader(ctx, "X-Forwarded-Proto");
		if (scheme == null) scheme = ctx.scheme();
		String host = firstHeader(ctx, "X-Forwarded-Host");
		if (host == null) host = ctx.host();
		return scheme + "://" + host;
	}

	private static String firstHeader(Context ctx, String name) {
		String v = ctx.header(name);
		if (v == null || v.isBlank()) return null;
		int comma = v.indexOf(',');
		return (comma >= 0 ? v.substring(0, comma) : v).trim();
	}

	/** RFC 8414 authorization-server metadata. */
	private void metadata(Context ctx) {
		String iss = issuer(ctx);
		AMap<AString, ACell> doc = Maps.of(
			Strings.intern("issuer"), Strings.create(iss),
			Strings.intern("authorization_endpoint"), Strings.create(iss + "/oauth/authorize"),
			Strings.intern("token_endpoint"), Strings.create(iss + "/oauth/token"),
			Strings.intern("revocation_endpoint"), Strings.create(iss + "/oauth/revoke"),
			Strings.intern("response_types_supported"), Vectors.of(Strings.create("code")),
			Strings.intern("grant_types_supported"), Vectors.of(Strings.create("authorization_code"), Strings.create("refresh_token")),
			Strings.intern("code_challenge_methods_supported"), Vectors.of(Strings.create("S256")),
			Strings.intern("token_endpoint_auth_methods_supported"),
				Vectors.of(Strings.create("none"), Strings.create("client_secret_post"), Strings.create("client_secret_basic")),
			Strings.intern("scopes_supported"), scopesSupported());
		ctx.contentType("application/json");
		ctx.result(JSON.print(doc).toString());
	}

	private AVector<ACell> scopesSupported() {
		Set<String> all = new java.util.TreeSet<>();
		for (Client c : clients.values()) all.addAll(c.scopes());
		AVector<ACell> out = Vectors.empty();
		for (String s : all) out = out.conj(Strings.create(s));
		return out;
	}

	/**
	 * Authorization endpoint. The resource owner authenticates by presenting a
	 * venue bearer (the middleware set the caller); we validate the client and
	 * redirect an authorization code back, or — only to a validated redirect
	 * URI — an {@code error}. Anything wrong with the client or redirect URI is
	 * shown here and never redirected, so an attacker cannot bounce an error
	 * (or a code) to a URI the client did not register.
	 */
	private void authorize(Context ctx) {
		String clientId = ctx.queryParam("client_id");
		String redirectUri = ctx.queryParam("redirect_uri");
		Client client = (clientId != null) ? clients.get(clientId) : null;
		if (client == null) {
			errorPage(ctx, 400, "Unknown client_id");
			return;
		}
		String matched = matchRedirect(client, redirectUri);
		if (matched == null) {
			errorPage(ctx, 400, "redirect_uri does not match a registered URI for this client");
			return;
		}
		String state = ctx.queryParam("state");
		String responseType = ctx.queryParam("response_type");
		if (!"code".equals(responseType)) {
			redirectError(ctx, matched, "unsupported_response_type", "only response_type=code is supported", state);
			return;
		}
		String challenge = ctx.queryParam("code_challenge");
		String method = ctx.queryParam("code_challenge_method");
		if (challenge == null || challenge.isBlank() || !"S256".equals(method)) {
			redirectError(ctx, matched, "invalid_request", "PKCE with code_challenge_method=S256 is required", state);
			return;
		}
		List<String> scope = requestedScope(ctx.queryParam("scope"), client);
		if (scope == null) {
			redirectError(ctx, matched, "invalid_scope", "requested scope exceeds what this client may request", state);
			return;
		}
		AString userDID = AuthMiddleware.getCallerDID(ctx);
		if (userDID == null || userDID.toString().endsWith(":public")) {
			redirectError(ctx, matched, "access_denied",
				"the resource owner is not authenticated — present a venue bearer to authorize", state);
			return;
		}

		sweep();
		if (codes.size() >= MAX_PENDING_CODES) {
			redirectError(ctx, matched, "temporarily_unavailable", "too many pending authorizations", state);
			return;
		}
		String code = randomToken(24);
		codes.put(code, new Code(client.id(), userDID, matched, challenge, scope, System.currentTimeMillis() + CODE_TTL_MILLIS));
		String sep = matched.contains("?") ? "&" : "?";
		StringBuilder to = new StringBuilder(matched).append(sep).append("code=").append(enc(code));
		if (state != null) to.append("&state=").append(enc(state));
		ctx.redirect(to.toString());
	}

	/** Token endpoint: {@code authorization_code} and {@code refresh_token} grants. */
	private void token(Context ctx) {
		String grantType = ctx.formParam("grant_type");
		try {
			AMap<AString, ACell> result = switch (grantType == null ? "" : grantType) {
				case "authorization_code" -> exchangeCode(ctx);
				case "refresh_token" -> refresh(ctx);
				default -> throw new OAuthError("unsupported_grant_type", "grant_type must be authorization_code or refresh_token");
			};
			ctx.contentType("application/json");
			ctx.header("Cache-Control", "no-store");
			ctx.result(JSON.print(result).toString());
		} catch (OAuthError e) {
			tokenError(ctx, e);
		}
	}

	private AMap<AString, ACell> exchangeCode(Context ctx) {
		String codeParam = ctx.formParam("code");
		Code code = (codeParam != null) ? codes.remove(codeParam) : null;
		if (code == null || code.expiresAt() < System.currentTimeMillis()) {
			throw new OAuthError("invalid_grant", "authorization code is unknown or expired");
		}
		Client client = authenticateClient(ctx, code.clientId());
		String redirectUri = ctx.formParam("redirect_uri");
		if (!code.redirectUri().equals(redirectUri)) {
			throw new OAuthError("invalid_grant", "redirect_uri does not match the authorization request");
		}
		String verifier = ctx.formParam("code_verifier");
		if (verifier == null || !code.challenge().equals(challenge(verifier))) {
			throw new OAuthError("invalid_grant", "PKCE code_verifier does not match");
		}
		return issue(client, code.userDID(), code.scope(), true);
	}

	private AMap<AString, ACell> refresh(Context ctx) {
		String token = ctx.formParam("refresh_token");
		Refresh grant = (token != null) ? refreshTokens.get(token) : null;
		if (grant == null) {
			throw new OAuthError("invalid_grant", "refresh token is unknown or revoked");
		}
		Client client = authenticateClient(ctx, grant.clientId());
		List<String> scope = grant.scope();
		String requested = ctx.formParam("scope");
		if (requested != null && !requested.isBlank()) {
			List<String> narrower = new ArrayList<>();
			for (String s : requested.trim().split("\\s+")) {
				if (!scope.contains(s)) throw new OAuthError("invalid_scope", "a refresh may only narrow the granted scope");
				narrower.add(s);
			}
			scope = narrower;
		}
		// Refresh-token rotation: the presented token is spent, a new one issued.
		refreshTokens.remove(token);
		return issue(client, grant.userDID(), scope, true);
	}

	/** Mints the venue bearer and (optionally) a rotating refresh token. */
	private AMap<AString, ACell> issue(Client client, AString userDID, List<String> scope, boolean withRefresh) {
		long nowSecs = System.currentTimeMillis() / 1000;
		String scopeString = String.join(" ", scope);
		AMap<AString, ACell> claims = Maps.of(
			Strings.intern("sub"), userDID,
			Strings.intern("iss"), engine.getDIDString(),
			Strings.intern("aud"), engine.getDIDString(),
			Strings.intern("iat"), CVMLong.create(nowSecs),
			Strings.intern("exp"), CVMLong.create(nowSecs + accessTtlSecs),
			Strings.intern("client_id"), Strings.create(client.id()),
			Strings.intern("scope"), Strings.create(scopeString));
		AString jwt = JWT.signPublic(claims, engine.getKeyPair());
		AMap<AString, ACell> result = Maps.of(
			Strings.intern("access_token"), jwt,
			Strings.intern("token_type"), Strings.create("Bearer"),
			Strings.intern("expires_in"), CVMLong.create(accessTtlSecs),
			Strings.intern("scope"), Strings.create(scopeString));
		if (withRefresh) {
			String refresh = randomToken(32);
			refreshTokens.put(refresh, new Refresh(client.id(), userDID, scope));
			result = result.assoc(Strings.intern("refresh_token"), Strings.create(refresh));
		}
		return result;
	}

	/** RFC 7009: revoke a refresh token this venue issued. Always 200. */
	private void revoke(Context ctx) {
		String token = ctx.formParam("token");
		if (token != null) {
			Refresh grant = refreshTokens.get(token);
			if (grant != null) {
				try {
					authenticateClient(ctx, grant.clientId());
					refreshTokens.remove(token);
				} catch (OAuthError e) {
					tokenError(ctx, e);
					return;
				}
			}
		}
		ctx.status(200).result("");
	}

	// ========== Client authentication ==========

	private Client authenticateClient(Context ctx, String boundClientId) {
		String basicId = null;
		String basicSecret = null;
		String authz = ctx.header("Authorization");
		if (authz != null && authz.startsWith("Basic ")) {
			try {
				String decoded = new String(Base64.getDecoder().decode(authz.substring(6).trim()), StandardCharsets.UTF_8);
				int colon = decoded.indexOf(':');
				if (colon >= 0) {
					basicId = URLDecode(decoded.substring(0, colon));
					basicSecret = URLDecode(decoded.substring(colon + 1));
				}
			} catch (IllegalArgumentException ignored) {
				throw new OAuthError("invalid_client", "malformed Basic authorization");
			}
		}
		String clientId = (basicId != null) ? basicId : ctx.formParam("client_id");
		if (clientId == null || !clientId.equals(boundClientId)) {
			throw new OAuthError("invalid_grant", "client_id does not match the grant");
		}
		Client client = clients.get(clientId);
		if (client == null) throw new OAuthError("invalid_client", "unknown client");
		if (client.isPublic()) {
			// A public client authenticates by PKCE alone; a secret must not be presented.
			return client;
		}
		String presented = (basicSecret != null) ? basicSecret : ctx.formParam("client_secret");
		String expected = engine.resolveSecret(client.secretRef(), engine.venueContext());
		if (expected == null) {
			throw new OAuthError("invalid_client", "client secret is not configured on this venue");
		}
		if (presented == null || !constantTimeEquals(presented, expected)) {
			throw new OAuthError("invalid_client", "client authentication failed");
		}
		return client;
	}

	// ========== Helpers ==========

	/** RFC 8252 §7.3: exact match, or any port for a loopback redirect. */
	static String matchRedirect(Client client, String redirectUri) {
		if (redirectUri == null || redirectUri.isBlank()) {
			// A client with exactly one registered URI may omit it (OAuth 2.0 §3.1.2.3).
			return (client.redirectUris().size() == 1) ? client.redirectUris().get(0) : null;
		}
		for (String registered : client.redirectUris()) {
			if (registered.equals(redirectUri)) return redirectUri;
		}
		URI req;
		try {
			req = URI.create(redirectUri);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!isLoopback(req)) return null;
		for (String registered : client.redirectUris()) {
			URI reg = URI.create(registered);
			if (isLoopback(reg)
					&& Objects.equals(lower(reg.getScheme()), lower(req.getScheme()))
					&& Objects.equals(loopbackHost(reg), loopbackHost(req))
					&& Objects.equals(reg.getPath(), req.getPath())) {
				return redirectUri;
			}
		}
		return null;
	}

	private static boolean isLoopback(URI uri) {
		String host = loopbackHost(uri);
		return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
	}

	private static String loopbackHost(URI uri) {
		String host = uri.getHost();
		return (host != null) ? host.toLowerCase(Locale.ROOT) : null;
	}

	private static String lower(String s) {
		return (s != null) ? s.toLowerCase(Locale.ROOT) : null;
	}

	/** Null when a requested scope exceeds the client's allowance; the client's default otherwise. */
	private static List<String> requestedScope(String requested, Client client) {
		if (requested == null || requested.isBlank()) return client.scopes();
		List<String> asked = new ArrayList<>();
		for (String s : requested.trim().split("\\s+")) {
			if (!client.scopes().contains(s)) return null;
			asked.add(s);
		}
		return asked;
	}

	private void sweep() {
		long now = System.currentTimeMillis();
		codes.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
	}

	private void redirectError(Context ctx, String redirectUri, String error, String description, String state) {
		String sep = redirectUri.contains("?") ? "&" : "?";
		StringBuilder to = new StringBuilder(redirectUri).append(sep)
			.append("error=").append(enc(error)).append("&error_description=").append(enc(description));
		if (state != null) to.append("&state=").append(enc(state));
		ctx.redirect(to.toString());
	}

	private void errorPage(Context ctx, int status, String message) {
		String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		ctx.status(status).contentType("text/html").result(
			"<!doctype html><html><head><meta charset=\"utf-8\"><title>Authorization error</title></head>"
			+ "<body style=\"font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem\">"
			+ "<h1>Authorization error</h1><p>" + safe + "</p></body></html>");
	}

	private void tokenError(Context ctx, OAuthError e) {
		int status = "invalid_client".equals(e.error) ? 401 : 400;
		ctx.status(status).contentType("application/json").header("Cache-Control", "no-store").result(
			JSON.print(Maps.of(
				Strings.intern("error"), Strings.create(e.error),
				Strings.intern("error_description"), Strings.create(e.getMessage()))).toString());
	}

	private static final class OAuthError extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final String error;
		OAuthError(String error, String description) { super(description); this.error = error; }
	}

	private String randomToken(int bytes) {
		byte[] buf = new byte[bytes];
		random.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String URLDecode(String s) {
		return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	private static String base(Engine engine) {
		String b = engine.config().getBaseUrl();
		return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
	}

	private static String str(ACell c) {
		AString s = RT.ensureString(c);
		return (s != null && !s.toString().isBlank()) ? s.toString().trim() : null;
	}

	private static Boolean bool(ACell c) {
		return (c instanceof convex.core.data.prim.CVMBool b) ? b.booleanValue() : null;
	}
}
