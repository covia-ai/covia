package covia.venue.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.auth.jwt.JWT;
import convex.core.crypto.Hashing;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Engine;
import io.javalin.http.Context;

/**
 * OAuth-based login for Covia venues.
 *
 * <p>Supports Google, Microsoft, and GitHub OAuth providers.
 * Configuration is read from the "oauth" section within the venue's
 * "auth" config. After successful OAuth, creates/updates the user in the
 * lattice-backed user database and issues a venue-signed EdDSA JWT.
 *
 * <p>Created and owned by {@link covia.venue.Auth}.
 *
 * @see covia.venue.Auth
 */
public class LoginProviders {

	private static final Logger log = LoggerFactory.getLogger(LoginProviders.class);

	private final Engine engine;
	private final Map<String, OAuthConfig> providers;
	private final Set<String> redirectOrigins;

	private static final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	/**
	 * Create LoginProviders from auth config.
	 * Reads the "oauth" sub-section and registers configured providers.
	 * The base URL for redirect URIs is derived from the venue config
	 * via {@link Config#getBaseUrl()}.
	 *
	 * @param engine The venue engine
	 * @param authConfig The "auth" config section, or null if not configured
	 */
	public LoginProviders(Engine engine, AMap<AString, ACell> authConfig) {
		this.engine = engine;
		this.providers = new HashMap<>();
		this.redirectOrigins = redirectOrigins(engine.config().getBaseUrl(), authConfig);

		if (authConfig == null) return;

		AMap<AString, ACell> oauthConfig = RT.ensureMap(authConfig.get(Config.OAUTH));
		if (oauthConfig == null) return;

		String base = engine.config().getBaseUrl();

		registerProvider("google", oauthConfig, base, OAuthConfig::google);
		registerProvider("microsoft", oauthConfig, base, OAuthConfig::microsoft);
		registerProvider("github", oauthConfig, base, OAuthConfig::github);
	}

	private void registerProvider(String name, AMap<AString, ACell> oauthConfig, String baseUrl,
			ProviderFactory factory) {
		AMap<AString, ACell> providerConfig = RT.ensureMap(oauthConfig.get(Strings.create(name)));
		if (providerConfig == null) return;

		AString clientId = RT.ensureString(providerConfig.get(Config.CLIENT_ID));
		AString clientSecret = RT.ensureString(providerConfig.get(Config.CLIENT_SECRET));
		// A half-filled block never reaches here: Config.validateAuth rejects
		// clientId-without-clientSecret (and vice versa) at construction, so
		// this is the both-absent case — the provider is simply not configured.
		if (clientId == null || clientSecret == null) return;

		OAuthConfig cfg = factory.create(clientId.toString(), clientSecret.toString(), baseUrl);
		providers.put(name, cfg);
		log.info("Registered OAuth provider: {}{}", name,
			isSecretRef(cfg.clientSecret) ? " (clientSecret via " + cfg.clientSecret + ")" : "");
	}

	/** Whether a configured clientSecret is an {@code s/NAME} store reference rather than a literal. */
	private static boolean isSecretRef(String value) {
		return Engine.isSecretRef(value);
	}

	/**
	 * The provider's client secret, resolving an {@code s/NAME} reference against
	 * the venue's own secret store.
	 *
	 * <p>{@code adapters.oauth} requires the reference form and rejects literals
	 * outright; login config predates that and is read literally, so a deployment
	 * had to keep the plaintext secret in its config file. Both forms are accepted
	 * here — the reference is preferred, the literal stays working — and resolution
	 * is deferred to the token exchange because the secret store is not populated
	 * when providers are registered at startup.
	 */
	String resolveClientSecret(OAuthConfig provider) {
		String configured = provider.clientSecret;
		if (!isSecretRef(configured)) return configured;

		String secret = engine.resolveSecret(configured, engine.venueContext());
		if (secret == null) {
			throw new IllegalStateException("auth.oauth." + provider.name + ".clientSecret references "
				+ configured + ", which is not set in the venue's secret store");
		}
		return secret;
	}

	@FunctionalInterface
	interface ProviderFactory {
		OAuthConfig create(String clientId, String clientSecret, String baseUrl);
	}

	/** Whether any providers are configured */
	public boolean hasProviders() {
		return !providers.isEmpty();
	}

	/** Get the configured providers (unmodifiable) */
	public Map<String, OAuthConfig> getProviders() {
		return Collections.unmodifiableMap(providers);
	}

	// ========== Route handlers ==========

	public void handleLogin(Context ctx) {
		String providerName = ctx.pathParam("provider");
		OAuthConfig provider = providers.get(providerName);
		if (provider == null) {
			ctx.status(400).result("Unsupported or unconfigured provider: " + providerName);
			return;
		}

		String redirectUri = ctx.queryParam("redirect_uri");
		if (redirectUri != null) {
			String refused = checkRedirect(redirectUri);
			if (refused != null) {
				ctx.status(400).result(refused);
				return;
			}
			// Encode redirect_uri into OAuth state as base64 JSON
			String stateJson = "{\"redirect_uri\":\"" + redirectUri.replace("\"", "\\\"") + "\"}";
			String state = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(stateJson.getBytes(StandardCharsets.UTF_8));
			ctx.redirect(provider.getAuthUrl(state));
		} else {
			ctx.redirect(provider.getAuthUrl());
		}
	}

	@SuppressWarnings("unchecked")
	public void handleCallback(Context ctx) {
		String providerName = ctx.pathParam("provider");
		OAuthConfig provider = providers.get(providerName);
		if (provider == null) {
			ctx.status(400).result("Unsupported or unconfigured provider: " + providerName);
			return;
		}

		String code = ctx.queryParam("code");
		if (code == null) {
			ctx.status(400).result("Missing authorisation code");
			return;
		}

		// Extract redirect_uri from OAuth state if present
		String frontendRedirectUri = null;
		String stateParam = ctx.queryParam("state");
		if (stateParam != null) {
			try {
				String stateJson = new String(
					Base64.getUrlDecoder().decode(stateParam), StandardCharsets.UTF_8);
				@SuppressWarnings("unchecked")
				Map<String, Object> stateMap = (Map<String, Object>) JSON.jvm(stateJson);
				if (stateMap != null && stateMap.containsKey("redirect_uri")) {
					frontendRedirectUri = stateMap.get("redirect_uri").toString();
				}
			} catch (Exception e) {
				log.debug("Could not decode OAuth state parameter", e);
			}
		}
		if (frontendRedirectUri != null) {
			String refused = checkRedirect(frontendRedirectUri);
			if (refused != null) {
				ctx.status(400).result(refused);
				return;
			}
		}

		try {
			// 1. Exchange code for tokens
			Map<String, String> tokenData = exchangeCode(provider, code);
			if (tokenData == null) {
				ctx.status(500).result("Token exchange failed");
				return;
			}

			String accessToken = tokenData.get("access_token");
			String idToken = tokenData.get("id_token");

			// 2. Extract user identity
			UserIdentity identity = null;

			// Try ID token validation first (Google, Microsoft)
			if (idToken != null && provider.jwksUri != null) {
				identity = validateIdToken(idToken, provider);
			}

			// Fall back to userinfo endpoint
			if (identity == null && accessToken != null && provider.userInfoUrl != null) {
				identity = fetchUserInfo(accessToken, provider);
			}

			if (identity == null) {
				ctx.status(500).result("Could not determine user identity");
				return;
			}

			// 3. Create or update user in lattice
			AString userId = Strings.create(identity.toUserId());
			AMap<AString, ACell> profile = Maps.empty();
			if (identity.email != null) {
				profile = profile.assoc(Fields.EMAIL, Strings.create(identity.email));
			}
			if (identity.name != null) {
				profile = profile.assoc(Fields.NAME, Strings.create(identity.name));
			}
			profile = profile
				.assoc(Fields.PROVIDER, Strings.create(providerName))
				.assoc(Fields.PROVIDER_SUB, Strings.create(identity.sub));

			// OAuth is a trusted venue provisioner. Managed usernames live under
			// the venue's did:web namespace; the runtime account store still accepts
			// arbitrary DIDs for self-sovereign identities provisioned elsewhere.
			// Preserve an existing account DID across upgrades / hostname changes;
			// only new managed users receive the current did:web-derived ID.
			AMap<AString, ACell> userRecord = engine.getAuth().provisionLogin(userId, profile);
			AString userDID = RT.ensureString(userRecord.get(Fields.DID));

			// 4. Issue venue-signed EdDSA JWT
			long nowSecs = System.currentTimeMillis() / 1000;
			AMap<AString, ACell> claims = venueClaims(engine.getDIDString(), userDID,
				identity.email, nowSecs, nowSecs + engine.getAuth().getTokenExpiry());
			AString venueJwt = JWT.signPublic(claims, engine.getKeyPair());
			// The provider and the user's DID — never the email (#448).
			engine.audit().event(covia.venue.Audit.LOGIN, "provider", providerName,
				covia.venue.Audit.K_DID, userDID,
				covia.venue.Audit.K_IP, engine.config().getTrustedProxies().clientIp(ctx.ip(), ctx.header("X-Forwarded-For")));
			engine.audit().event(covia.venue.Audit.TOKEN_ISSUED, covia.venue.Audit.K_TYPE, "session",
				covia.venue.Audit.K_SUBJECT, userDID, covia.venue.Audit.K_EXP, claims.get(Strings.intern("exp")));

			// 5. Return JWT to client
			if (frontendRedirectUri != null) {
				// Redirect back to frontend with token and DID as query params
				String sep = frontendRedirectUri.contains("?") ? "&" : "?";
				String redirectUrl = frontendRedirectUri + sep
					+ "token=" + URLEncoder.encode(venueJwt.toString(), StandardCharsets.UTF_8)
					+ "&did=" + URLEncoder.encode(userDID.toString(), StandardCharsets.UTF_8);
				ctx.redirect(redirectUrl);
			} else {
				// Backward compatible: return JSON for API/CLI clients
				AMap<AString, ACell> response = Maps.of(
					"token", venueJwt,
					"did", userDID
				);
				ctx.header("Content-type", "application/json");
				ctx.result(JSON.toString(response));
			}

		} catch (Exception e) {
			log.error("OAuth callback error for {}", providerName, e);
			ctx.status(500).result("OAuth callback failed: " + e.getMessage());
		}
	}

	/**
	 * Claims for the browser-facing venue credential. Provider profile data stays
	 * in the venue's user record; only its stable pseudonym crosses the callback
	 * URL inside the signed (but unencrypted) JWT.
	 */
	static AMap<AString, ACell> venueClaims(AString venueDID, AString userDID,
			String email, long issuedAt, long expiresAt) {
		AMap<AString, ACell> claims = Maps.of(
			"sub", userDID,
			"iss", venueDID,
			"aud", venueDID,
			"iat", issuedAt,
			"exp", expiresAt);
		if (email != null) claims = claims.assoc(Fields.COVIA_UID, coviaUid(email));
		return claims;
	}

	/** Lowercase first 16 hex characters of SHA-256(normalised email). */
	static AString coviaUid(String email) {
		byte[] normalised = email.trim().toLowerCase(java.util.Locale.ROOT)
			.getBytes(StandardCharsets.UTF_8);
		return Strings.create(Hashing.sha256(normalised).toHexString().substring(0, 16));
	}

	/**
	 * Render a simple login page listing configured providers.
	 */
	public String renderLoginPage() {
		StringBuilder sb = new StringBuilder("<h1>Login</h1>");
		for (String name : providers.keySet()) {
			String label = name.substring(0, 1).toUpperCase() + name.substring(1);
			sb.append("<a href='/auth/").append(name).append("'>Login with ").append(label).append("</a><br>");
		}
		if (providers.isEmpty()) {
			sb.append("<p>No OAuth providers configured.</p>");
		}
		return sb.toString();
	}

	// ========== Private helpers ==========

	/**
	 * Exchange an authorisation code for tokens.
	 * Uses application/x-www-form-urlencoded as required by OAuth spec.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, String> exchangeCode(OAuthConfig provider, String code) {
		try {
			String body = "grant_type=authorization_code"
				+ "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
				+ "&client_id=" + URLEncoder.encode(provider.clientId, StandardCharsets.UTF_8)
				+ "&client_secret=" + URLEncoder.encode(resolveClientSecret(provider), StandardCharsets.UTF_8)
				+ "&redirect_uri=" + URLEncoder.encode(provider.redirectUri, StandardCharsets.UTF_8);

			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
				.uri(URI.create(provider.tokenUrl))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.timeout(Duration.ofSeconds(30));

			// GitHub requires Accept: application/json
			if ("github".equals(provider.name)) {
				reqBuilder.header("Accept", "application/json");
			}

			HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("Token exchange failed: {} returned {}", provider.tokenUrl, resp.statusCode());
				return null;
			}

			return (Map<String, String>) JSON.jvm(resp.body());
		} catch (Exception e) {
			log.error("Token exchange error", e);
			return null;
		}
	}

	/**
	 * Validate an ID token (JWT) from an OAuth provider using their JWKS.
	 */
	private UserIdentity validateIdToken(String idToken, OAuthConfig provider) {
		try {
			AString jwtStr = Strings.create(idToken);
			JWT parsed = JWT.parse(jwtStr);
			if (parsed == null) return null;

			// Look up the signing key from JWKS
			String kid = parsed.getKeyID();
			if (kid == null) return null;

			RSAPublicKey key = JWKSClient.getKey(provider.jwksUri, kid);
			if (key == null) return null;

			// Verify signature
			if (!parsed.verifyRS256(key)) return null;

			// Validate claims (issuer may be null for Microsoft)
			if (!parsed.validateClaims(provider.issuer, provider.clientId)) {
				// Google may use issuer without https prefix
				if (provider.issuer != null && !parsed.validateClaims("accounts.google.com", provider.clientId)) {
					return null;
				}
			}

			// Extract identity from claims
			AMap<AString, ACell> claims = parsed.getClaims();
			return new UserIdentity(
				str(claims, "sub"),
				str(claims, "email"),
				str(claims, "name")
			);
		} catch (Exception e) {
			log.debug("ID token validation failed", e);
			return null;
		}
	}

	/**
	 * Fetch user info from the provider's userinfo endpoint.
	 * Fallback for providers that don't issue ID tokens (e.g. GitHub).
	 */
	@SuppressWarnings("unchecked")
	private UserIdentity fetchUserInfo(String accessToken, OAuthConfig provider) {
		try {
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(provider.userInfoUrl))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.timeout(Duration.ofSeconds(30))
				.build();

			HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("Userinfo fetch failed: {} returned {}", provider.userInfoUrl, resp.statusCode());
				return null;
			}

			Map<String, Object> data = (Map<String, Object>) JSON.jvm(resp.body());
			String sub = data.containsKey("sub") ? data.get("sub").toString() : null;
			if (sub == null && data.containsKey("id")) {
				sub = data.get("id").toString(); // GitHub uses "id" instead of "sub"
			}
			String email = data.containsKey("email") ? (String) data.get("email") : null;
			String name = data.containsKey("name") ? (String) data.get("name") : null;

			if (sub == null) return null;
			return new UserIdentity(sub, email, name);
		} catch (Exception e) {
			log.debug("Userinfo fetch failed", e);
			return null;
		}
	}

	private static String str(AMap<AString, ACell> map, String key) {
		AString v = RT.ensureString(map.get(Strings.create(key)));
		return v != null ? v.toString() : null;
	}

	/**
	 * Represents extracted user identity from OAuth provider.
	 */
	static class UserIdentity {
		final String sub;
		final String email;
		final String name;

		UserIdentity(String sub, String email, String name) {
			this.sub = sub;
			this.email = email;
			this.name = name;
		}

		/**
		 * Derive a user ID suitable for the venue user database.
		 * Uses email if available, otherwise provider sub.
		 */
		String toUserId() {
			if (email != null) {
				// Sanitise email for use as ID: replace @ and . with _
				return email.replace("@", "_").replace(".", "_");
			}
			return sub;
		}
	}

	// ========== Login redirect allow-list ==========

	/** Refusal for a {@code redirect_uri} outside the allowed origins. */
	public static final String REDIRECT_REFUSED =
		"redirect_uri must be a path on this venue or an absolute URL whose origin is the venue's "
		+ "baseUrl or is listed in auth.loginRedirectOrigins";

	/**
	 * The origins a login may return the session token to: the venue's own
	 * {@code baseUrl} origin plus {@code auth.loginRedirectOrigins}.
	 */
	private static Set<String> redirectOrigins(String baseUrl, AMap<AString, ACell> authConfig) {
		Set<String> origins = new HashSet<>();
		String own = originOf(baseUrl);
		if (own != null) origins.add(own);
		AVector<ACell> configured = (authConfig == null) ? null
			: RT.ensureVector(authConfig.get(Config.LOGIN_REDIRECT_ORIGINS));
		if (configured != null) {
			for (long i = 0; i < configured.count(); i++) {
				AString s = RT.ensureString(configured.get(i));
				String origin = (s == null) ? null : originOf(s.toString());
				if (origin == null) {
					log.warn("auth.loginRedirectOrigins entry ignored (not an http(s) origin): {}", s);
				} else {
					origins.add(origin);
				}
			}
		}
		return Collections.unmodifiableSet(origins);
	}

	/** {@code scheme://host[:port]} of an absolute http(s) URL, lower-cased; null otherwise. */
	static String originOf(String url) {
		if (url == null) return null;
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (Exception e) {
			return null;
		}
		String scheme = uri.getScheme(), host = uri.getHost();
		if (scheme == null || host == null) return null;
		scheme = scheme.toLowerCase(java.util.Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) return null;
		String origin = scheme + "://" + host.toLowerCase(java.util.Locale.ROOT);
		int port = uri.getPort();
		boolean standard = (port == -1) || (scheme.equals("http") && port == 80)
			|| (scheme.equals("https") && port == 443);
		return standard ? origin : origin + ":" + port;
	}

	/**
	 * Checks a caller-supplied {@code redirect_uri}: null when it may receive
	 * the login result, otherwise the reason it may not. A relative path on
	 * this venue is always allowed; an absolute URL only when its origin is
	 * the venue's own or a configured one. Checked at login and again at the
	 * callback, because the OAuth {@code state} that carries it is unsigned.
	 */
	public String checkRedirect(String redirectUri) {
		if (redirectUri == null) return null;
		String uri = redirectUri.trim();
		if (uri.startsWith("/")) {
			// A path on this venue — but never a scheme-relative URL.
			if (uri.startsWith("//") || uri.startsWith("/\\")) return REDIRECT_REFUSED + ": " + uri;
			return null;
		}
		String origin = originOf(uri);
		if (origin != null && redirectOrigins.contains(origin)) return null;
		return REDIRECT_REFUSED + ": " + uri;
	}
}
