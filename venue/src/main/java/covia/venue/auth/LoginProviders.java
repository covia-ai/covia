package covia.venue.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.Nonce;

import convex.auth.jwt.JWT;
import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Auth;
import covia.venue.Config;
import covia.venue.Engine;
import io.javalin.http.Context;

/** External login transport. Provider ceremonies stay here; persistent identities are lattice data.
 * Temporary transactions are process-local, bounded and single-use, not authentication sessions.
 */
public class LoginProviders {
	private static final Logger log = LoggerFactory.getLogger(LoginProviders.class);
	private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	static final long TRANSACTION_TTL = 300_000;
	static final long EXCHANGE_TTL = 60_000;
	private static final int MAX_PENDING = 10_000;
	// Method-owned payload, not protocol fields on the core identity/evidence shape.
	public static final AString EXTERNAL_IDENTITY = Strings.intern("externalIdentity");
	private static final AString ISS = Strings.intern("iss");
	private static final AString SUB = Strings.intern("sub");
	public static final String UNKNOWN_PROVIDER = "Unsupported or unconfigured provider";
	public static final String INVALID_TRANSACTION = "Invalid or expired login transaction";
	public static final String INVALID_EXCHANGE = "Invalid or expired login exchange";
	public static final String INVALID_LOGIN = "Provider login failed";
	public static final String PKCE_REQUIRED = "A redirect login requires S256 code_challenge and client state";
	public static final String REDIRECT_REFUSED = "redirect_uri must exactly match auth.loginRedirectUris";
	public static final String TOO_MANY_PENDING = "Too many pending logins";
	public static final String LEGACY_IDENTITY = "Existing account needs independently authenticated identity-link confirmation";
	public static final String IDENTITY_CONFLICT = "External identity conflicts with an existing account";
	public static final String INVALID_REDIRECT_CONFIG = "auth.loginRedirectUris must contain exact HTTPS URLs (HTTP only on loopback), without query, fragment or user-info";
	public static final String LEGACY_REDIRECT_CONFIG = "Replace auth.loginRedirectOrigins with exact auth.loginRedirectUris";
	public static final String HTTPS_REQUIRED = "External login requires an HTTPS baseUrl (HTTP only on loopback)";

	private final Engine engine;
	private final Map<String, OAuthConfig> providers;
	private final Set<String> redirectUris;
	private final LongSupplier clock;
	private final String cookieName;
	private final boolean secureCookie;
	private final Map<String, Transaction> transactions = new HashMap<>();
	private final Map<String, Exchange> exchanges = new HashMap<>();

	private record Transaction(String provider, String browser, CodeVerifier verifier, Nonce nonce,
			String redirect, String challenge, String clientState, long expiresAt) {}
	private record Exchange(AString userDID, UserIdentity identity, String redirect, String challenge, long expiresAt) {}

	public LoginProviders(Engine engine, AMap<AString, ACell> authConfig) {
		this(engine, authConfig, configuredProviders(engine, authConfig), System::currentTimeMillis);
	}

	// Explicit clock and provider endpoints allow real HTTP contract tests without external accounts.
	LoginProviders(Engine engine, AMap<AString, ACell> authConfig, Map<String, OAuthConfig> providers, LongSupplier clock) {
		this.engine = engine;
		this.providers = Map.copyOf(providers);
		this.clock = clock;
		this.redirectUris = redirectUris(authConfig);
		URI base = URI.create(engine.config().getBaseUrl());
		secureCookie = "https".equals(base.getScheme());
		if (!providers.isEmpty() && !secureCookie && !("http".equals(base.getScheme()) && isLoopback(base.getHost()))) {
			throw new IllegalArgumentException(HTTPS_REQUIRED);
		}
		cookieName = secureCookie ? "__Host-covia-login" : "covia-login";
	}

	private static Map<String, OAuthConfig> configuredProviders(Engine engine, AMap<AString, ACell> authConfig) {
		Map<String, OAuthConfig> result = new HashMap<>();
		AMap<AString, ACell> oauth = authConfig == null ? null : RT.ensureMap(authConfig.get(Config.OAUTH));
		if (oauth == null) return result;
		String base = engine.config().getBaseUrl();
		for (String name : Set.of("google", "microsoft", "github")) {
			AMap<AString, ACell> config = RT.ensureMap(oauth.get(Strings.intern(name)));
			if (config == null) continue;
			String id = str(config, Config.CLIENT_ID), secret = str(config, Config.CLIENT_SECRET);
			if (id == null || secret == null) continue;
			OAuthConfig provider = switch (name) {
				case "google" -> OAuthConfig.google(id, secret, base);
				case "microsoft" -> OAuthConfig.microsoft(id, secret, base, str(config, Strings.intern("tenantId")));
				default -> OAuthConfig.github(id, secret, base);
			};
			result.put(name, provider);
		}
		return result;
	}

	String resolveClientSecret(OAuthConfig provider) {
		if (!Engine.isSecretRef(provider.clientSecret)) return provider.clientSecret;
		String secret = engine.resolveSecret(provider.clientSecret, engine.venueContext());
		if (secret == null) throw new IllegalStateException("auth.oauth." + provider.name
				+ ".clientSecret references " + provider.clientSecret + ", which is not set in the venue's secret store");
		return secret;
	}

	public boolean hasProviders() { return !providers.isEmpty(); }
	public Map<String, OAuthConfig> getProviders() { return providers; }

	public void handleLogin(Context ctx) {
		privateResponse(ctx);
		OAuthConfig provider = providers.get(ctx.pathParam("provider"));
		if (provider == null) { fail(ctx, UNKNOWN_PROVIDER); return; }
		String redirect = ctx.queryParam("redirect_uri");
		String refused = checkRedirect(redirect);
		if (refused != null) { fail(ctx, refused); return; }
		String challenge = ctx.queryParam("code_challenge"), clientState = ctx.queryParam("state");
		if (redirect != null && (!"S256".equals(ctx.queryParam("code_challenge_method"))
				|| challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")
				|| clientState == null || clientState.isBlank() || clientState.length() > 512)) {
			fail(ctx, PKCE_REQUIRED); return;
		}
		String browser = ctx.cookie(cookieName);
		if (browser == null || !browser.matches("[A-Za-z0-9_-]{43}")) browser = new State(32).getValue();
		String state = new State(32).getValue();
		Transaction transaction = new Transaction(provider.name, browser, new CodeVerifier(), new Nonce(32),
				redirect, challenge, clientState, clock.getAsLong() + TRANSACTION_TTL);
		synchronized (this) {
			prune();
			if (transactions.size() >= MAX_PENDING) { ctx.status(503).result(TOO_MANY_PENDING); return; }
			transactions.put(state, transaction);
		}
		ctx.header("Set-Cookie", cookieName + "=" + browser + "; Path=/; Max-Age=300; HttpOnly; SameSite=Lax"
				+ (secureCookie ? "; Secure" : ""));
		ctx.redirect(provider.getAuthUrl(state, transaction.verifier(), transaction.nonce()));
	}

	public void handleCallback(Context ctx) {
		privateResponse(ctx);
		Transaction transaction;
		synchronized (this) {
			prune();
			String state = ctx.queryParam("state");
			transaction = transactions.get(state);
			if (transaction == null || !transaction.provider().equals(ctx.pathParam("provider"))
					|| !transaction.browser().equals(ctx.cookie(cookieName))) {
				fail(ctx, INVALID_TRANSACTION); return;
			}
			transactions.remove(state); // Consume before any network request, including errors.
		}
		OAuthConfig provider = providers.get(transaction.provider());
		String code = ctx.queryParam("code");
		if (provider == null || code == null || code.isBlank() || ctx.queryParam("error") != null) {
			fail(ctx, INVALID_LOGIN); return;
		}
		try {
			Map<String, Object> tokens = exchangeCode(provider, code, transaction.verifier());
			UserIdentity identity;
			if (provider.jwksUri != null) {
				Object raw = tokens.get("id_token");
				if (!(raw instanceof String idToken)) { fail(ctx, INVALID_LOGIN); return; }
				var verified = provider.validateIdToken(idToken, transaction.nonce());
				Map<String, Object> claims = verified.toJSONObject();
				identity = new UserIdentity(provider.issuer, verified.getSubject().getValue(),
						Boolean.TRUE.equals(claims.get("email_verified")) ? string(claims.get("email")) : null,
						string(claims.get("name")));
			} else {
				// OAuth-only providers use their own stable subject API. An invalid OIDC token never falls back here.
				identity = fetchUserInfo(provider, string(tokens.get("access_token")));
			}
			AString did = provisionIdentity(provider.name, identity);
			engine.flush();
			engine.audit().event(covia.venue.Audit.LOGIN, "provider", provider.name,
					covia.venue.Audit.K_DID, did,
					covia.venue.Audit.K_IP, engine.config().getTrustedProxies().clientIp(ctx.ip(), ctx.header("X-Forwarded-For")));
			if (transaction.redirect() == null) {
				issueCredential(ctx, did, identity.email());
			} else {
				String exchangeCode = new State(32).getValue();
				synchronized (this) {
					prune();
					if (exchanges.size() >= MAX_PENDING) { ctx.status(503).result(TOO_MANY_PENDING); return; }
					exchanges.put(exchangeCode, new Exchange(did, identity, transaction.redirect(),
							transaction.challenge(), clock.getAsLong() + EXCHANGE_TTL));
				}
				ctx.redirect(transaction.redirect() + "?code=" + enc(exchangeCode) + "&state=" + enc(transaction.clientState()));
			}
		} catch (Exception e) {
			// Never send provider responses, claims or credentials back as diagnostic text.
			log.debug("External login failed for {} ({})", provider.name, e.getClass().getSimpleName());
			fail(ctx, LEGACY_IDENTITY.equals(e.getMessage()) ? LEGACY_IDENTITY : INVALID_LOGIN);
		}
	}

	/** Frontend handoff only. This is not the venue OAuth token endpoint or a managed session. */
	public void handleExchange(Context ctx) {
		privateResponse(ctx);
		Exchange exchange;
		synchronized (this) {
			prune();
			exchange = exchanges.remove(ctx.formParam("code"));
		}
		try {
			if (exchange == null || ctx.formParam("code_verifier") == null
					|| !exchange.redirect().equals(ctx.formParam("redirect_uri"))
					|| !exchange.challenge().equals(CodeChallenge.compute(CodeChallengeMethod.S256,
						new CodeVerifier(ctx.formParam("code_verifier"))).getValue())) {
				fail(ctx, INVALID_EXCHANGE); return;
			}
			// A binding removed or rebound during the handoff cannot issue a credential.
			if (!exchange.userDID().equals(findIdentity(engine.getAuth(), exchange.identity().issuer(), exchange.identity().sub()))) {
				fail(ctx, INVALID_EXCHANGE); return;
			}
			issueCredential(ctx, exchange.userDID(), exchange.identity().email());
		} catch (IllegalArgumentException | IllegalStateException | AuthException e) { fail(ctx, INVALID_EXCHANGE); }
	}

	/** Current credential delivery seam. Managed-session issuance and assurance remain unimplemented. */
	private void issueCredential(Context ctx, AString did, String email) {
		engine.getVenueState().users().requireNotDeleted(did);
		long now = clock.getAsLong() / 1000;
		AMap<AString, ACell> claims = venueClaims(engine.getDIDString(), did, email, now, now + engine.getAuth().getTokenExpiry());
		AString token = JWT.signPublic(claims, engine.getKeyPair());
		engine.audit().event(covia.venue.Audit.TOKEN_ISSUED, covia.venue.Audit.K_TYPE, "venueJwt",
				covia.venue.Audit.K_SUBJECT, did, covia.venue.Audit.K_EXP, claims.get(Strings.intern("exp")));
		ctx.contentType("application/json").result(JSON.toString(Maps.of("token", token, "did", did)));
	}

	private void prune() {
		long now = clock.getAsLong();
		transactions.values().removeIf(t -> t.expiresAt() <= now);
		exchanges.values().removeIf(e -> e.expiresAt() <= now);
	}

	private static void privateResponse(Context ctx) {
		ctx.header("Cache-Control", "no-store").header("Pragma", "no-cache").header("Referrer-Policy", "no-referrer");
	}
	private static void fail(Context ctx, String reason) { ctx.status(400).result(reason); }
	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
	private static String string(Object value) { return value instanceof String s ? s : null; }
	private static String str(AMap<AString, ACell> map, AString key) {
		return map.get(key) instanceof AString s ? s.toString() : null;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> exchangeCode(OAuthConfig provider, String code, CodeVerifier verifier) throws Exception {
		String body = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(provider.clientId)
				+ "&client_secret=" + enc(resolveClientSecret(provider)) + "&redirect_uri=" + enc(provider.redirectUri)
				+ "&code_verifier=" + enc(verifier.getValue());
		HttpRequest request = HttpRequest.newBuilder(URI.create(provider.tokenUrl))
				.header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(30)).build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IllegalArgumentException(INVALID_LOGIN);
		return (Map<String, Object>) JSON.jvm(response.body());
	}

	@SuppressWarnings("unchecked")
	private UserIdentity fetchUserInfo(OAuthConfig provider, String token) throws Exception {
		if (token == null) throw new IllegalArgumentException(INVALID_LOGIN);
		HttpRequest request = HttpRequest.newBuilder(URI.create(provider.userInfoUrl))
				.header("Authorization", "Bearer " + token).header("Accept", "application/json")
				.GET().timeout(Duration.ofSeconds(30)).build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IllegalArgumentException(INVALID_LOGIN);
		Map<String, Object> data = (Map<String, Object>) JSON.jvm(response.body());
		// GitHub's numeric id is stable. Its /user email has no verified flag, so do not trust it as verified metadata.
		Object id = data.get("id");
		if (!(id instanceof Number) || ((Number) id).longValue() <= 0) throw new IllegalArgumentException(INVALID_LOGIN);
		return new UserIdentity(provider.issuer, id.toString(), null, string(data.get("name")));
	}

	record UserIdentity(String issuer, String sub, String email, String name) {
		UserIdentity {
			if (issuer == null || issuer.isBlank() || sub == null || sub.isBlank()) throw new IllegalArgumentException(INVALID_LOGIN);
		}
		AMap<AString, ACell> binding() { return Maps.of(ISS, issuer, SUB, sub); }
		AString toUserId() { return Strings.create("external-" + Vectors.of(issuer, sub).getHash().toHexString()); }
	}

	/** Trusted provisioning; never selects or links a legacy account using email. */
	AString provisionIdentity(String provider, UserIdentity identity) {
		Auth auth = engine.getAuth();
		synchronized (auth) {
			AString existing = findIdentity(auth, identity.issuer(), identity.sub());
			AString id = identity.toUserId();
			if (existing != null) {
				for (var entry : auth.getUsers().entrySet()) {
					if (matches(entry.getValue(), identity.issuer(), identity.sub())) { id = entry.getKey(); break; }
				}
			} else {
				if (auth.getUser(id) != null) throw new IllegalStateException(IDENTITY_CONFLICT);
				for (var record : auth.getUsers().values()) {
					if (record.get(EXTERNAL_IDENTITY) != null) continue;
					if ((Strings.create(provider).equals(record.get(Fields.PROVIDER))
							&& Strings.create(identity.sub()).equals(record.get(Fields.PROVIDER_SUB)))
							|| (identity.email() != null && Strings.create(identity.email()).equals(record.get(Fields.EMAIL)))) {
						throw new IllegalStateException(LEGACY_IDENTITY);
					}
				}
			}
			AMap<AString, ACell> profile = Maps.of(EXTERNAL_IDENTITY,
					identity.binding().assoc(Fields.PROVIDER, Strings.create(provider)),
					Fields.EMAIL, identity.email(), Fields.NAME, identity.name());
			return RT.ensureString(auth.provisionLogin(id, profile).get(Fields.DID));
		}
	}

	/** Lookup only: direct bearer presentation cannot provision or link an account. */
	public static AString findIdentity(Auth auth, String issuer, String subject) {
		if (issuer == null || subject == null) return null;
		AString found = null;
		for (var record : auth.getUsers().values()) {
			if (!matches(record, issuer, subject)) continue;
			AString did = RT.ensureString(record.get(Fields.DID));
			if (did == null || (found != null && !found.equals(did))) throw new IllegalStateException(IDENTITY_CONFLICT);
			found = did;
		}
		return found;
	}

	private static boolean matches(AMap<AString, ACell> record, String issuer, String subject) {
		AMap<AString, ACell> binding = RT.ensureMap(record.get(EXTERNAL_IDENTITY));
		return binding != null && Strings.create(issuer).equals(binding.get(ISS)) && Strings.create(subject).equals(binding.get(SUB));
	}

	static AMap<AString, ACell> venueClaims(AString venueDID, AString userDID, String email, long issuedAt, long expiresAt) {
		AMap<AString, ACell> claims = Maps.of("sub", userDID, "iss", venueDID, "aud", venueDID, "iat", issuedAt, "exp", expiresAt);
		if (email != null) claims = claims.assoc(Fields.COVIA_UID, coviaUid(email));
		return claims;
	}
	static AString coviaUid(String email) {
		return Strings.create(Hashing.sha256(email.trim().toLowerCase(java.util.Locale.ROOT)
				.getBytes(StandardCharsets.UTF_8)).toHexString().substring(0, 16));
	}

	public String renderLoginPage() {
		StringBuilder sb = new StringBuilder("<h1>Login</h1>");
		for (String name : providers.keySet()) {
			sb.append("<a href='/auth/").append(name).append("'>Login with ")
				.append(name.substring(0, 1).toUpperCase()).append(name.substring(1)).append("</a><br>");
		}
		if (providers.isEmpty()) sb.append("<p>No OAuth providers configured.</p>");
		return sb.toString();
	}

	private static Set<String> redirectUris(AMap<AString, ACell> auth) {
		if (auth == null) return Set.of();
		if (auth.containsKey(Config.LOGIN_REDIRECT_ORIGINS)) throw new IllegalArgumentException(LEGACY_REDIRECT_CONFIG);
		AVector<ACell> configured = RT.ensureVector(auth.get(Config.LOGIN_REDIRECT_URIS));
		Set<String> result = new HashSet<>();
		if (configured != null) for (ACell cell : configured) {
			if (!(cell instanceof AString value)) throw new IllegalArgumentException(INVALID_REDIRECT_CONFIG);
			String text = value.toString();
			URI uri;
			try { uri = URI.create(text); } catch (IllegalArgumentException e) { throw new IllegalArgumentException(INVALID_REDIRECT_CONFIG); }
			if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
					|| !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
					&& isLoopback(uri.getHost())))) {
				throw new IllegalArgumentException(INVALID_REDIRECT_CONFIG);
			}
			result.add(text);
		}
		return Collections.unmodifiableSet(result);
	}
	private static boolean isLoopback(String host) {
		return host != null && Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
	}
	public String checkRedirect(String redirectUri) {
		return redirectUri == null || redirectUris.contains(redirectUri) ? null : REDIRECT_REFUSED;
	}
}
