package covia.venue.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.json.JWT;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.Config;
import covia.venue.Engine;
import io.javalin.http.Context;

/**
 * OAuth-based login for Covia venues.
 *
 * <p>Supports Google, Microsoft, and GitHub OAuth providers.
 * Configuration is read from the venue config under the "oauth" key.
 * After successful OAuth, creates/updates the user in the lattice-backed
 * user database and issues a venue-signed EdDSA JWT.
 *
 * <h2>Config format</h2>
 * <pre>
 * {
 *   "oauth": {
 *     "baseUrl": "https://venue.example.com",
 *     "google": { "clientId": "...", "clientSecret": "..." },
 *     "microsoft": { "clientId": "...", "clientSecret": "..." },
 *     "github": { "clientId": "...", "clientSecret": "..." }
 *   }
 * }
 * </pre>
 */
public class LoginProviders {

	private static final Logger log = LoggerFactory.getLogger(LoginProviders.class);

	private final Engine engine;
	private final Map<String, OAuthConfig> providers;

	private static final HttpClient client = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	/**
	 * Create LoginProviders from venue config.
	 * Reads oauth section and registers configured providers.
	 */
	public LoginProviders(Engine engine, AMap<AString, ACell> config) {
		this.engine = engine;
		this.providers = new HashMap<>();

		AMap<AString, ACell> oauthConfig = RT.ensureMap(config.get(Config.OAUTH));
		if (oauthConfig == null) return;

		AString baseUrl = RT.ensureString(oauthConfig.get(Config.BASE_URL));
		String base = baseUrl != null ? baseUrl.toString() : "http://localhost:8080";

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
		if (clientId == null || clientSecret == null) return;

		OAuthConfig cfg = factory.create(clientId.toString(), clientSecret.toString(), baseUrl);
		providers.put(name, cfg);
		log.info("Registered OAuth provider: {}", name);
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
		ctx.redirect(provider.getAuthUrl());
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
			String userId = identity.toUserId();
			AMap<AString, ACell> userRecord = engine.getUser(userId);
			if (userRecord == null) {
				userRecord = Maps.empty();
			}
			if (identity.email != null) {
				userRecord = userRecord.assoc(Strings.create("email"), Strings.create(identity.email));
			}
			if (identity.name != null) {
				userRecord = userRecord.assoc(Strings.create("name"), Strings.create(identity.name));
			}
			userRecord = userRecord
				.assoc(Strings.create("provider"), Strings.create(providerName))
				.assoc(Strings.create("providerSub"), Strings.create(identity.sub));

			// Set DID for user (appended to venue DID)
			AString userDID = Strings.create(engine.getDIDString() + ":u:" + userId);
			userRecord = userRecord.assoc(Strings.create("did"), userDID);
			engine.putUser(userId, userRecord);

			// 4. Issue venue-signed EdDSA JWT
			long nowSecs = System.currentTimeMillis() / 1000;
			AMap<AString, ACell> claims = Maps.of(
				"sub", userDID,
				"iss", engine.getDIDString(),
				"iat", nowSecs,
				"exp", nowSecs + 86400 // 24 hour expiry
			);
			if (identity.email != null) {
				claims = claims.assoc(Strings.create("email"), Strings.create(identity.email));
			}
			if (identity.name != null) {
				claims = claims.assoc(Strings.create("name"), Strings.create(identity.name));
			}
			AString venueJwt = JWT.signPublic(claims, engine.getKeyPair());

			// 5. Return JWT to client
			AMap<AString, ACell> response = Maps.of(
				"token", venueJwt,
				"did", userDID
			);
			ctx.header("Content-type", "application/json");
			ctx.result(JSON.toString(response));

		} catch (Exception e) {
			log.error("OAuth callback error for {}", providerName, e);
			ctx.status(500).result("OAuth callback failed: " + e.getMessage());
		}
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
				+ "&client_secret=" + URLEncoder.encode(provider.clientSecret, StandardCharsets.UTF_8)
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
}
