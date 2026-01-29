package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.json.JWT;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.TestServer;

@TestInstance(Lifecycle.PER_CLASS)
public class OAuthTest {

	static final int PORT = TestServer.PORT;
	Engine engine;

	@BeforeAll
	public void setup() {
		assertNotNull(TestServer.SERVER, "Test server should be running");
		engine = TestServer.ENGINE;
	}

	@Test
	void testConfigParsingNoOAuth() {
		// The default test server has no OAuth config, so LoginProviders should be empty
		AMap<AString, ACell> config = Maps.of(
			Strings.create("name"), Strings.create("Test")
		);
		LoginProviders lp = new LoginProviders(engine, config);
		assertFalse(lp.hasProviders());
		assertTrue(lp.getProviders().isEmpty());
	}

	@Test
	void testConfigParsingWithProviders() {
		AMap<AString, ACell> googleConfig = Maps.of(
			Config.CLIENT_ID, Strings.create("test-client-id"),
			Config.CLIENT_SECRET, Strings.create("test-client-secret")
		);
		AMap<AString, ACell> oauthConfig = Maps.of(
			Config.BASE_URL, Strings.create("https://example.com"),
			Strings.create("google"), googleConfig
		);
		AMap<AString, ACell> config = Maps.of(
			Config.OAUTH, oauthConfig
		);
		LoginProviders lp = new LoginProviders(engine, config);
		assertTrue(lp.hasProviders());
		assertEquals(1, lp.getProviders().size());
		assertNotNull(lp.getProviders().get("google"));
	}

	@Test
	void testConfigParsingMultipleProviders() {
		AMap<AString, ACell> creds = Maps.of(
			Config.CLIENT_ID, Strings.create("id"),
			Config.CLIENT_SECRET, Strings.create("secret")
		);
		AMap<AString, ACell> oauthConfig = Maps.of(
			Config.BASE_URL, Strings.create("https://example.com"),
			Strings.create("google"), creds,
			Strings.create("microsoft"), creds,
			Strings.create("github"), creds
		);
		AMap<AString, ACell> config = Maps.of(
			Config.OAUTH, oauthConfig
		);
		LoginProviders lp = new LoginProviders(engine, config);
		assertEquals(3, lp.getProviders().size());
	}

	@Test
	void testOAuthConfigGoogleEndpoints() {
		OAuthConfig google = OAuthConfig.google("cid", "csecret", "https://venue.example.com");
		assertEquals("google", google.name);
		assertEquals("cid", google.clientId);
		assertEquals("csecret", google.clientSecret);
		assertEquals("https://venue.example.com/auth/google/callback", google.redirectUri);
		assertNotNull(google.jwksUri);
		assertEquals("https://accounts.google.com", google.issuer);
		assertTrue(google.scope.contains("openid"));
	}

	@Test
	void testOAuthConfigMicrosoftEndpoints() {
		OAuthConfig ms = OAuthConfig.microsoft("cid", "csecret", "https://venue.example.com");
		assertEquals("microsoft", ms.name);
		assertNull(ms.issuer, "Microsoft issuer should be null (varies by tenant)");
		assertNotNull(ms.jwksUri);
		assertEquals("https://venue.example.com/auth/microsoft/callback", ms.redirectUri);
	}

	@Test
	void testOAuthConfigGitHubEndpoints() {
		OAuthConfig gh = OAuthConfig.github("cid", "csecret", "https://venue.example.com");
		assertEquals("github", gh.name);
		assertNull(gh.jwksUri, "GitHub doesn't issue JWTs");
		assertNull(gh.issuer);
		assertEquals("https://venue.example.com/auth/github/callback", gh.redirectUri);
		assertTrue(gh.scope.contains("user:email"));
	}

	@Test
	void testLoginRedirectUrl() {
		OAuthConfig google = OAuthConfig.google("my-client-id", "secret", "https://venue.example.com");
		String url = google.getAuthUrl();
		assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
		assertTrue(url.contains("client_id=my-client-id"));
		assertTrue(url.contains("redirect_uri="));
		assertTrue(url.contains("scope="));
		assertTrue(url.contains("response_type=code"));
	}

	@Test
	void testUnknownProviderReturnsError() throws Exception {
		HttpClient client = HttpClient.newBuilder().build();
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI("http://localhost:" + PORT + "/auth/unknownprovider"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();

		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		// Should either be 400 (if routes exist) or 404 (if no providers configured)
		assertTrue(resp.statusCode() == 400 || resp.statusCode() == 404,
			"Expected 400 or 404 for unknown provider, got " + resp.statusCode());
	}

	@Test
	void testUserIdentityToUserId() {
		// Test email-based ID derivation
		LoginProviders.UserIdentity id1 = new LoginProviders.UserIdentity("sub123", "alice@gmail.com", "Alice");
		assertEquals("alice_gmail_com", id1.toUserId());

		// Test sub-based fallback when no email
		LoginProviders.UserIdentity id2 = new LoginProviders.UserIdentity("sub456", null, "Bob");
		assertEquals("sub456", id2.toUserId());
	}

	@Test
	void testVenueIssuedJWTVerifies() {
		// Simulate what handleCallback does: issue a venue-signed JWT
		long nowSecs = System.currentTimeMillis() / 1000;
		AString userDID = Strings.create(engine.getDIDString() + ":u:test_user");
		AMap<AString, ACell> claims = Maps.of(
			"sub", userDID,
			"iss", engine.getDIDString(),
			"iat", nowSecs,
			"exp", nowSecs + 86400
		);
		AString jwt = JWT.signPublic(claims, engine.getKeyPair());
		assertNotNull(jwt);

		// Verify it with the venue's public key (same path AuthMiddleware uses)
		AMap<AString, ACell> verified = JWT.verifyPublic(jwt, engine.getAccountKey());
		assertNotNull(verified, "Venue-signed JWT should verify with venue's public key");
		assertEquals(userDID.toString(), verified.get(Strings.create("sub")).toString());
	}

	@Test
	void testRenderLoginPageEmpty() {
		AMap<AString, ACell> config = Maps.of(
			Strings.create("name"), Strings.create("Test")
		);
		LoginProviders lp = new LoginProviders(engine, config);
		String html = lp.renderLoginPage();
		assertTrue(html.contains("No OAuth providers configured"));
	}

	@Test
	void testRenderLoginPageWithProviders() {
		AMap<AString, ACell> creds = Maps.of(
			Config.CLIENT_ID, Strings.create("id"),
			Config.CLIENT_SECRET, Strings.create("secret")
		);
		AMap<AString, ACell> oauthConfig = Maps.of(
			Config.BASE_URL, Strings.create("https://example.com"),
			Strings.create("google"), creds
		);
		AMap<AString, ACell> config = Maps.of(
			Config.OAUTH, oauthConfig
		);
		LoginProviders lp = new LoginProviders(engine, config);
		String html = lp.renderLoginPage();
		assertTrue(html.contains("Login with Google"));
		assertTrue(html.contains("/auth/google"));
	}
}
