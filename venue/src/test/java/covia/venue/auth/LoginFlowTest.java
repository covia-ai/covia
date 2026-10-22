package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.sun.net.httpserver.HttpServer;

import convex.auth.jwt.JWT;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Engine;
import io.javalin.Javalin;

/** Actual HTTP browser/provider exchanges, without live accounts or sleeps. */
class LoginFlowTest {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final String REDIRECT = "https://app.example/callback";
	private record Started(Map<String, String> query, String cookie, String code) {}

	private static final class Fixture implements AutoCloseable {
		final Engine engine = Engine.createTemp(Maps.of(Config.HOSTNAME, "login.example", Config.BASE_URL, "https://login.example"));
		final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
		final AtomicInteger tokenCalls = new AtomicInteger(), userInfoCalls = new AtomicInteger();
		final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
		final HttpServer provider;
		final RSAKey key;
		final OAuthConfig config;
		final LoginProviders login;
		final Javalin app;
		Consumer<Map<String, Object>> alterClaims = claims -> {};
		boolean omitIdToken;

		Fixture() throws Exception { this(true); }
		Fixture(boolean oidc) throws Exception {
			key = new RSAKeyGenerator(2048).keyID("test").generate();
			provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			String base = "http://127.0.0.1:" + provider.getAddress().getPort();
			config = new OAuthConfig("test", "client", "secret", base + "/authorize", base + "/token",
					base + "/userinfo", oidc ? base + "/jwks" : null, base, oidc ? "openid email profile" : "read:user", "http://localhost/callback");
			provider.createContext("/jwks", x -> {
				byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
				x.sendResponseHeaders(200, body.length);
				try (var out = x.getResponseBody()) { out.write(body); }
			});
			provider.createContext("/userinfo", x -> {
				userInfoCalls.incrementAndGet();
				byte[] body = "{\"id\":12345,\"sub\":\"subject\",\"email\":\"alice@example.com\"}".getBytes(StandardCharsets.UTF_8);
				x.sendResponseHeaders(200, body.length);
				try (var out = x.getResponseBody()) { out.write(body); }
			});
			provider.createContext("/token", x -> {
				tokenCalls.incrementAndGet();
				Map<String, String> form = params(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				Map<String, String> request = codes.remove(form.get("code"));
				String body;
				int status = 200;
				try {
					if (request == null || !request.get("code_challenge").equals(challenge(new CodeVerifier(form.get("code_verifier"))))
							|| !"client".equals(form.get("client_id")) || !"secret".equals(form.get("client_secret"))
							|| !config.redirectUri.equals(form.get("redirect_uri"))) throw new IllegalArgumentException();
					Map<String, String> response = new HashMap<>(Map.of("access_token", "provider-secret"));
					if (oidc && !omitIdToken) {
						Map<String, Object> claims = claims(request.get("nonce"));
						alterClaims.accept(claims);
						response.put("id_token", sign(claims));
					}
					body = com.nimbusds.jose.util.JSONObjectUtils.toJSONString(response);
				} catch (Exception e) { status = 400; body = "{\"error\":\"invalid_grant\"}"; }
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				x.getResponseHeaders().add("Content-Type", "application/json");
				x.sendResponseHeaders(status, bytes.length);
				try (var out = x.getResponseBody()) { out.write(bytes); }
			});
			provider.start();
			login = new LoginProviders(engine, Maps.of(Config.LOGIN_REDIRECT_URIS, Vectors.of(REDIRECT)), Map.of("test", config), clock::get);
			app = Javalin.create(c -> {
				c.routes.get("/auth/{provider}", login::handleLogin);
				c.routes.get("/auth/{provider}/callback", login::handleCallback);
				c.routes.post("/auth/exchange", login::handleExchange);
			}).start("127.0.0.1", 0);
		}
		Map<String, Object> claims(String nonce) {
			long now = System.currentTimeMillis() / 1000;
			return new HashMap<>(Map.of("iss", config.issuer, "sub", "subject", "aud", "client", "iat", now,
					"exp", now + 300, "nonce", nonce, "email", "alice@example.com", "email_verified", true));
		}
		String sign(Map<String, Object> claims) throws Exception {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), JWTClaimsSet.parse(claims));
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		String base() { return "http://127.0.0.1:" + app.port(); }
		HttpResponse<String> get(String path, String cookie) throws Exception {
			var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10));
			if (cookie != null) builder.header("Cookie", cookie);
			return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
		}
		Started start(String query) throws Exception {
			var response = get("/auth/test" + query, null);
			assertEquals(302, response.statusCode(), response.body());
			assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
			String header = response.headers().firstValue("Set-Cookie").orElseThrow();
			assertTrue(header.contains("HttpOnly"));
			assertTrue(header.contains("SameSite=Lax"));
			assertTrue(header.startsWith("__Host-covia-login="));
			assertTrue(header.contains("; Secure"));
			Map<String, String> request = params(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
			assertEquals("S256", request.get("code_challenge_method"));
			if (config.jwksUri != null) assertNotNull(request.get("nonce"));
			String code = "code-" + java.util.UUID.randomUUID();
			codes.put(code, request);
			return new Started(request, header.split(";", 2)[0], code);
		}
		HttpResponse<String> callback(Started started) throws Exception {
			return get("/auth/test/callback?state=" + enc(started.query().get("state")) + "&code=" + enc(started.code()), started.cookie());
		}
		HttpResponse<String> exchange(String code, String verifier, String redirect) throws Exception {
			String body = "code=" + enc(code) + "&redirect_uri=" + enc(redirect);
			if (verifier != null) body += "&code_verifier=" + enc(verifier);
			return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/auth/exchange"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		}
		@Override public void close() { app.stop(); provider.stop(0); engine.close(); }
	}

	@Test void browserLoginUsesPkceNonceAndNeverPutsBearerInLocation() throws Exception {
		try (Fixture f = new Fixture()) {
			CodeVerifier frontend = new CodeVerifier();
			Started start = f.start("?redirect_uri=" + enc(REDIRECT) + "&state=frontend-state&code_challenge_method=S256&code_challenge=" + challenge(frontend));
			assertNotEquals("frontend-state", start.query().get("state"));
			var callback = f.callback(start);
			assertEquals(302, callback.statusCode(), callback.body());
			String location = callback.headers().firstValue("Location").orElseThrow();
			assertFalse(location.contains("token="));
			Map<String, String> handoff = params(URI.create(location).getRawQuery());
			assertEquals("frontend-state", handoff.get("state"));
			var response = f.exchange(handoff.get("code"), frontend.getValue(), REDIRECT);
			assertEquals(200, response.statusCode(), response.body());
			assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
			AMap<AString, ACell> body = RT.ensureMap(JSON.parse(response.body()));
			AMap<AString, ACell> claims = JWT.verifyPublic(RT.ensureString(body.get(Fields.TOKEN)), f.engine.getAccountKey());
			assertEquals(body.get(Fields.DID), claims.get(Fields.SUB));
			assertEquals(400, f.exchange(handoff.get("code"), frontend.getValue(), REDIRECT).statusCode());
			assertEquals(0, f.userInfoCalls.get());
		}
	}

	@Test void stateIsRequiredBrowserBoundProviderBoundExpiringAndSingleUse() throws Exception {
		try (Fixture f = new Fixture()) {
			assertEquals(LoginProviders.INVALID_TRANSACTION, f.get("/auth/test/callback?code=x", null).body());
			Started start = f.start("");
			String path = "/auth/test/callback?state=" + start.query().get("state") + "&code=" + start.code();
			assertEquals(LoginProviders.INVALID_TRANSACTION, f.get(path, null).body());
			assertEquals(LoginProviders.INVALID_TRANSACTION, f.get(path.replace("/test/", "/other/"), start.cookie()).body());
			assertEquals(0, f.tokenCalls.get());
			var first = f.callback(start);
			assertEquals(200, first.statusCode(), first.body());
			assertEquals(LoginProviders.INVALID_TRANSACTION, f.callback(start).body());
			assertEquals(1, f.tokenCalls.get());
			Started expired = f.start("");
			f.clock.addAndGet(LoginProviders.TRANSACTION_TTL);
			assertEquals(LoginProviders.INVALID_TRANSACTION, f.callback(expired).body());
			assertEquals(1, f.tokenCalls.get());
		}
	}

	@Test void invalidOrAbsentOidcTokenNeverFallsBackToUserInfo() throws Exception {
		try (Fixture f = new Fixture()) {
			f.alterClaims = claims -> claims.put("nonce", "wrong");
			assertEquals(LoginProviders.INVALID_LOGIN, f.callback(f.start("")).body());
			f.alterClaims = claims -> claims.remove("nonce");
			assertEquals(LoginProviders.INVALID_LOGIN, f.callback(f.start("")).body());
			f.omitIdToken = true;
			assertEquals(LoginProviders.INVALID_LOGIN, f.callback(f.start("")).body());
			assertEquals(0, f.userInfoCalls.get());
			assertTrue(f.engine.getAuth().getUsers().isEmpty());
		}
	}

	@Test void concurrentCallbacksHaveExactlyOneWinner() throws Exception {
		try (Fixture f = new Fixture(); var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			Started start = f.start("");
			var results = workers.invokeAll(java.util.List.<java.util.concurrent.Callable<Integer>>of(
				() -> f.callback(start).statusCode(), () -> f.callback(start).statusCode()));
			assertEquals(java.util.Set.of(200, 400), java.util.Set.of(results.get(0).get(), results.get(1).get()));
			assertEquals(1, f.tokenCalls.get());
		}
	}

	@Test void googleIssuerAliasDoesNotRelaxOtherProviders() throws Exception {
		try (Fixture f = new Fixture()) {
			OAuthConfig google = new OAuthConfig("google", "client", "secret", null, null, null,
				f.config.jwksUri, "https://accounts.google.com", null, null);
			Map<String, Object> claims = f.claims("nonce");
			claims.put("iss", "accounts.google.com");
			assertNotNull(google.validateIdToken(f.sign(claims), new Nonce("nonce")));
			assertThrows(Exception.class, () -> f.config.validateIdToken(f.sign(claims), new Nonce("nonce")));
		}
	}

	@Test void upstreamVerifierRejectsInvalidClaimsAndSignature() throws Exception {
		try (Fixture f = new Fixture()) {
			Map<String, Object> good = f.claims("nonce");
			assertEquals("subject", f.config.validateIdToken(f.sign(good), new Nonce("nonce")).getSubject().getValue());
			for (String required : new String[] {"iss", "sub", "aud", "iat", "exp"}) {
				Map<String, Object> claims = new HashMap<>(good); claims.remove(required);
				assertThrows(Exception.class, () -> f.config.validateIdToken(f.sign(claims), new Nonce("nonce")), required);
			}
			Map<String, Object> invalid = Map.of("iss", "https://wrong.example", "sub", "", "aud", "other-client",
					"exp", 1L, "iat", System.currentTimeMillis()/1000 + 3600,
					"nbf", System.currentTimeMillis()/1000 + 3600, "azp", "other-client", "nonce", "other-nonce");
			for (var field : invalid.entrySet()) {
				Map<String, Object> claims = new HashMap<>(good); claims.put(field.getKey(), field.getValue());
				assertThrows(Exception.class, () -> f.config.validateIdToken(f.sign(claims), new Nonce("nonce")), field.getKey());
			}
			Map<String, Object> multi = new HashMap<>(good); multi.put("aud", java.util.List.of("client", "another"));
			assertThrows(Exception.class, () -> f.config.validateIdToken(f.sign(multi), new Nonce("nonce")));
			multi.put("azp", "client");
			assertNotNull(f.config.validateIdToken(f.sign(multi), new Nonce("nonce")));
			String signed = f.sign(good);
			int dot = signed.lastIndexOf('.') + 1;
			String tampered = signed.substring(0, dot) + (signed.charAt(dot) == 'A' ? "B" : "A") + signed.substring(dot + 1);
			assertThrows(Exception.class, () -> f.config.validateIdToken(tampered, new Nonce("nonce")));
			OAuthConfig unchecked = new OAuthConfig("test", "client", "secret", null, null, null, f.config.jwksUri, null, null, null);
			assertThrows(Exception.class, () -> unchecked.validateIdToken(signed, null));
		}
	}

	@Test void frontendExchangeRejectsWrongProofDestinationExpiryAndReplay() throws Exception {
		try (Fixture f = new Fixture()) {
			for (String failure : new String[] {"proof", "destination", "expired", "missing"}) {
				CodeVerifier verifier = new CodeVerifier();
				var start = f.start("?redirect_uri=" + enc(REDIRECT) + "&state=state&code_challenge_method=S256&code_challenge=" + challenge(verifier));
				var callback = f.callback(start);
				String code = params(URI.create(callback.headers().firstValue("Location").orElseThrow()).getRawQuery()).get("code");
				if (failure.equals("expired")) f.clock.addAndGet(LoginProviders.EXCHANGE_TTL);
				String proof = failure.equals("proof") ? new CodeVerifier().getValue() : failure.equals("missing") ? null : verifier.getValue();
				var response = f.exchange(code, proof, failure.equals("destination") ? "https://other.example/cb" : REDIRECT);
				assertEquals(400, response.statusCode(), failure + ": " + response.body());
				assertEquals(LoginProviders.INVALID_EXCHANGE, response.body());
				assertEquals(400, f.exchange(code, verifier.getValue(), REDIRECT).statusCode());
			}
		}
	}

	@Test void redirectLoginRequiresExplicitDestinationAndFrontendPkce() throws Exception {
		try (Fixture f = new Fixture()) {
			assertEquals(LoginProviders.REDIRECT_REFUSED, f.get("/auth/test?redirect_uri=" + enc("https://app.example/other"), null).body());
			assertEquals(LoginProviders.PKCE_REQUIRED, f.get("/auth/test?redirect_uri=" + enc(REDIRECT), null).body());
			assertEquals(0, f.tokenCalls.get());
		}
	}

	@Test void identitiesSurviveEmailChangesAndDoNotMergeMatchingEmails() throws Exception {
		try (Fixture f = new Fixture()) {
			var first = new LoginProviders.UserIdentity("https://issuer", "subject", "alice@example", "Alice");
			AString did = f.login.provisionIdentity("test", first);
			assertEquals(did, f.login.provisionIdentity("test", new LoginProviders.UserIdentity("https://issuer", "subject", "new@example", "New")));
			assertNotEquals(did, f.login.provisionIdentity("other", new LoginProviders.UserIdentity("https://other", "subject", "new@example", "New")));
			assertNotEquals(did, f.login.provisionIdentity("test", new LoginProviders.UserIdentity("https://issuer", "different", "new@example", "New")));
			assertEquals(did, LoginProviders.findIdentity(f.engine.getAuth(), "https://issuer", "subject"));
			assertEquals(Strings.create("new@example"), f.engine.getAuth().getUser(first.toUserId()).get(Fields.EMAIL));
		}
	}

	@Test void legacyEmailAndProviderSubjectNeedExplicitMigration() throws Exception {
		try (Fixture f = new Fixture()) {
			AString legacyId = Strings.create("alice_example");
			AString legacyDID = f.engine.managedUserDID(legacyId);
			f.engine.getAuth().putUser(legacyId, Maps.of(Fields.DID, legacyDID, Fields.EMAIL, "alice@example",
					Fields.PROVIDER, "test", Fields.PROVIDER_SUB, "subject"));
			assertNull(LoginProviders.findIdentity(f.engine.getAuth(), "https://issuer", "subject"));
			var identity = new LoginProviders.UserIdentity("https://issuer", "subject", "alice@example", "Alice");
			assertEquals(LoginProviders.LEGACY_IDENTITY, assertThrows(IllegalStateException.class,
					() -> f.login.provisionIdentity("test", identity)).getMessage());
			assertNull(f.engine.getAuth().getUser(identity.toUserId()));
		}
	}

	@Test void conflictingBindingsFailClosed() throws Exception {
		try (Fixture f = new Fixture()) {
			var identity = new LoginProviders.UserIdentity("https://issuer", "subject", null, null);
			f.login.provisionIdentity("test", identity);
			f.engine.getAuth().putUser(Strings.create("conflict"), Maps.of(Fields.DID, "did:web:other.example:u:other",
				LoginProviders.EXTERNAL_IDENTITY, identity.binding()));
			assertEquals(LoginProviders.IDENTITY_CONFLICT, assertThrows(IllegalStateException.class,
				() -> f.login.provisionIdentity("test", identity)).getMessage());
		}
	}

	@Test void unverifiedEmailIsNotStoredAsTrustedProfileMetadata() throws Exception {
		try (Fixture f = new Fixture()) {
			f.alterClaims = claims -> claims.put("email_verified", false);
			assertEquals(200, f.callback(f.start("")).statusCode());
			AMap<AString, ACell> record = f.engine.getAuth().getUsers().values().iterator().next();
			assertNull(record.get(Fields.EMAIL));
			assertNotNull(record.get(LoginProviders.EXTERNAL_IDENTITY));
		}
	}

	@Test void oauthOnlyProviderUsesStableNumericIdWithoutInferringVerifiedEmail() throws Exception {
		try (Fixture f = new Fixture(false)) {
			Started start = f.start("");
			assertNull(start.query().get("nonce"));
			var response = f.callback(start);
			assertEquals(200, response.statusCode(), response.body());
			assertEquals(1, f.userInfoCalls.get());
			AMap<AString, ACell> record = f.engine.getAuth().getUsers().values().iterator().next();
			assertNull(record.get(Fields.EMAIL));
			AMap<AString, ACell> identity = RT.ensureMap(record.get(LoginProviders.EXTERNAL_IDENTITY));
			assertEquals(Strings.create("12345"), identity.get(Fields.SUB));
		}
	}

	private static String challenge(CodeVerifier verifier) { return CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue(); }
	private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
	private static Map<String, String> params(String query) {
		Map<String, String> result = new HashMap<>();
		for (String item : query.split("&")) {
			String[] pair = item.split("=", 2);
			result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
		}
		return result;
	}
}

