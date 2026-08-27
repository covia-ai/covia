package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.Config;
import covia.venue.SecretStore;
import covia.venue.User;
import covia.venue.server.VenueServer;

/**
 * The venue as an OAuth 2.1 authorization server, tested against itself: the
 * authorization-code + PKCE flow issues a token the same venue then accepts
 * as a bearer on {@code /api/v1}, refresh rotates, revocation sticks, and the
 * metadata and MCP advertisement are standards-shaped. This is the loop the
 * {@code oauth} client adapter would drive against a peer venue.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class OAuthProviderTest {

	private VenueServer server;
	private String base;
	private final HttpClient http = HttpClient.newHttpClient();
	private final SecureRandom random = new SecureRandom();

	// The resource owner: an authenticated venue user with a venue-signed bearer.
	private final AKeyPair userKey = AKeyPair.generate();
	private String userDID;
	private String userBearer;

	@BeforeAll
	public void setup() throws Exception {
		server = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.BIND_ADDRESS, Strings.create("127.0.0.1"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, false),
			covia.api.Fields.MCP, Maps.of(),   // enables the /.well-known/oauth-protected-resource/mcp route
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted")),
				Strings.intern("oauth"), Maps.of("provider", Maps.of(
					"enabled", true,
					"accessTokenTtlSecs", 120,
					"clients", Maps.of(
						"web-app", Maps.of(
							"redirectUris", Vectors.of("https://app.example/callback"),
							"secret", "s/WEBAPP_SECRET",
							"scopes", Vectors.of("read", "write")),
						"cli", Maps.of(
							"redirectUris", Vectors.of("http://127.0.0.1/cb"),
							"public", true,
							"scopes", Vectors.of("read"))))))));
		base = "http://127.0.0.1:" + server.port();

		var engine = server.getEngine();
		userDID = UCAN.toDIDKey(userKey.getAccountKey()).toString();
		engine.getVenueState().users().ensure(Strings.create(userDID));
		long nowSecs = System.currentTimeMillis() / 1000;
		userBearer = convex.auth.jwt.JWT.signPublic(Maps.of(
			"sub", Strings.create(userDID),
			"iss", engine.getDIDString(),
			"aud", engine.getDIDString(),
			"iat", nowSecs,
			"exp", nowSecs + 3600), engine.getKeyPair()).toString();
		// The confidential client's secret lives in the venue identity's store.
		User venueUser = engine.getVenueState().users().ensure(engine.getDIDString());
		venueUser.secrets().store("WEBAPP_SECRET", "web-app-secret", SecretStore.deriveKey(engine.getKeyPair()));
	}

	@AfterAll
	public void teardown() {
		if (server != null) server.close();
	}

	// ========== helpers ==========

	private HttpResponse<String> get(String path, String bearer, boolean follow) throws Exception {
		HttpClient client = follow ? http
			: HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(base + path)).GET();
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> postForm(String path, Map<String, String> form, String basicAuth) throws Exception {
		StringBuilder body = new StringBuilder();
		form.forEach((k, v) -> {
			if (body.length() > 0) body.append('&');
			body.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
				.append(URLEncoder.encode(v, StandardCharsets.UTF_8));
		});
		HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(base + path))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (basicAuth != null) {
			b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8)));
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static Map<String, String> params(String url) {
		Map<String, String> m = new HashMap<>();
		String q = URI.create(url).getQuery();
		if (q != null) for (String pair : q.split("&")) {
			int eq = pair.indexOf('=');
			m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
				URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return m;
	}

	private String verifier() {
		byte[] buf = new byte[32];
		random.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private String authorizeCode(String clientId, String redirectUri, String scope, String verifier, String state)
			throws Exception {
		String url = "/oauth/authorize?response_type=code&client_id=" + clientId
			+ "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
			+ (scope != null ? "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8) : "")
			+ "&code_challenge=" + OAuthProvider.challenge(verifier) + "&code_challenge_method=S256"
			+ (state != null ? "&state=" + state : "");
		HttpResponse<String> r = get(url, userBearer, false);
		assertEquals(302, r.statusCode(), r.body());
		String location = r.headers().firstValue("Location").orElseThrow();
		assertTrue(location.startsWith(redirectUri), location);
		Map<String, String> q = params(location);
		if (state != null) assertEquals(state, q.get("state"));
		assertNotNull(q.get("code"), "an authorization code was returned: " + location);
		return q.get("code");
	}

	/** The caller the venue attributes a bearer to, via v/ops/auth/whoami over /run. */
	private String whoami(String bearer) throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder()
			.uri(URI.create(base + "/api/v1/run"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + bearer)
			.POST(HttpRequest.BodyPublishers.ofString("{\"operation\":\"v/ops/auth/whoami\",\"input\":{}}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, r.statusCode(), r.body());
		ACell out = JSON.parse(r.body());
		ACell caller = RT.getIn(out, "caller");
		return (caller != null) ? caller.toString() : null;
	}

	// ========== the flow ==========

	@Test
	public void testMetadataAndMcpAdvertisement() throws Exception {
		HttpResponse<String> meta = get("/.well-known/oauth-authorization-server", null, true);
		assertEquals(200, meta.statusCode());
		ACell doc = JSON.parse(meta.body());
		assertEquals(base, RT.getIn(doc, "issuer").toString());
		assertEquals(base + "/oauth/authorize", RT.getIn(doc, "authorization_endpoint").toString());
		assertEquals(base + "/oauth/token", RT.getIn(doc, "token_endpoint").toString());
		assertTrue(RT.getIn(doc, "code_challenge_methods_supported").toString().contains("S256"));
		assertEquals("[\"code\"]", JSON.print(RT.getIn(doc, "response_types_supported")).toString());
		assertTrue(RT.getIn(doc, "scopes_supported").toString().contains("write"));

		// The MCP protected-resource metadata now names this authorization server.
		HttpResponse<String> prm = get("/.well-known/oauth-protected-resource/mcp", null, true);
		assertEquals(200, prm.statusCode(), prm.body());
		assertTrue(prm.body().contains("authorization_servers"), prm.body());
		assertTrue(prm.body().contains(base), prm.body());
	}

	@Test
	public void testConfidentialClientCodeFlowThenUseTokenAsBearer() throws Exception {
		String v = verifier();
		String code = authorizeCode("web-app", "https://app.example/callback", "read write", v, "xyz");

		// The token endpoint: authorization_code with the matching PKCE verifier and client secret.
		HttpResponse<String> tok = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "authorization_code",
			"code", code,
			"redirect_uri", "https://app.example/callback",
			"code_verifier", v)), "web-app:web-app-secret");
		assertEquals(200, tok.statusCode(), tok.body());
		ACell t = JSON.parse(tok.body());
		String accessToken = RT.getIn(t, "access_token").toString();
		assertEquals("Bearer", RT.getIn(t, "token_type").toString());
		assertEquals("read write", RT.getIn(t, "scope").toString());
		assertNotNull(RT.getIn(t, "refresh_token"), "a refresh token was issued");
		assertEquals(120L, RT.ensureLong(RT.getIn(t, "expires_in")).longValue());

		// The issued token IS a venue bearer — the same venue accepts it, as the user.
		assertEquals(userDID, whoami(accessToken), "the token acts as the resource owner");

		// A one-time code cannot be replayed.
		HttpResponse<String> replay = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "authorization_code", "code", code,
			"redirect_uri", "https://app.example/callback", "code_verifier", v)), "web-app:web-app-secret");
		assertEquals(400, replay.statusCode());
		assertTrue(replay.body().contains("invalid_grant"), replay.body());

		// Refresh rotates the refresh token and returns a fresh access token.
		String refresh = RT.getIn(t, "refresh_token").toString();
		HttpResponse<String> refreshed = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "refresh_token", "refresh_token", refresh)), "web-app:web-app-secret");
		assertEquals(200, refreshed.statusCode(), refreshed.body());
		ACell t2 = JSON.parse(refreshed.body());
		assertEquals(userDID, whoami(RT.getIn(t2, "access_token").toString()));
		String rotated = RT.getIn(t2, "refresh_token").toString();
		assertNotEquals(refresh, rotated, "the refresh token rotates");
		assertEquals(400, postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "refresh_token", "refresh_token", refresh)), "web-app:web-app-secret").statusCode(),
			"the spent refresh token no longer works");

		// Revoke the rotated token; it stops working.
		assertEquals(200, postForm("/oauth/revoke", new HashMap<>(Map.of("token", rotated)), "web-app:web-app-secret").statusCode());
		assertEquals(400, postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "refresh_token", "refresh_token", rotated)), "web-app:web-app-secret").statusCode());
	}

	@Test
	public void testPublicClientUsesPkceAndLoopbackAnyPort() throws Exception {
		String v = verifier();
		// A native client registered http://127.0.0.1/cb may come back on any port.
		String code = authorizeCode("cli", "http://127.0.0.1:52001/cb", null, v, null);
		HttpResponse<String> tok = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "authorization_code", "code", code,
			"redirect_uri", "http://127.0.0.1:52001/cb", "code_verifier", v,
			"client_id", "cli")), null);   // no secret — public client
		assertEquals(200, tok.statusCode(), tok.body());
		assertEquals("read", RT.getIn(JSON.parse(tok.body()), "scope").toString());

		// A wrong verifier is rejected.
		String v2 = verifier();
		String code2 = authorizeCode("cli", "http://127.0.0.1:9/cb", null, v2, null);
		HttpResponse<String> bad = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "authorization_code", "code", code2,
			"redirect_uri", "http://127.0.0.1:9/cb", "code_verifier", verifier(), "client_id", "cli")), null);
		assertEquals(400, bad.statusCode());
		assertTrue(bad.body().contains("code_verifier"), bad.body());
	}

	// ========== refusals ==========

	@Test
	public void testAuthorizeRefusals() throws Exception {
		String v = verifier();
		String pkce = "&code_challenge=" + OAuthProvider.challenge(v) + "&code_challenge_method=S256";

		// Unknown client and unregistered redirect URI are shown here, never redirected.
		assertEquals(400, get("/oauth/authorize?response_type=code&client_id=ghost&redirect_uri="
			+ URLEncoder.encode("https://app.example/callback", StandardCharsets.UTF_8) + pkce, userBearer, false).statusCode());
		HttpResponse<String> badRedirect = get("/oauth/authorize?response_type=code&client_id=web-app&redirect_uri="
			+ URLEncoder.encode("https://evil.example/steal", StandardCharsets.UTF_8) + pkce, userBearer, false);
		assertEquals(400, badRedirect.statusCode());
		assertTrue(badRedirect.body().contains("redirect_uri"), badRedirect.body());

		String reg = URLEncoder.encode("https://app.example/callback", StandardCharsets.UTF_8);

		// Missing PKCE, a bad scope, and an unauthenticated owner redirect an error to the registered URI.
		HttpResponse<String> noPkce = get("/oauth/authorize?response_type=code&client_id=web-app&redirect_uri=" + reg, userBearer, false);
		assertEquals(302, noPkce.statusCode());
		assertTrue(noPkce.headers().firstValue("Location").orElse("").contains("error=invalid_request"), noPkce.headers().map().toString());

		HttpResponse<String> badScope = get("/oauth/authorize?response_type=code&client_id=web-app&redirect_uri="
			+ reg + pkce + "&scope=admin", userBearer, false);
		assertTrue(badScope.headers().firstValue("Location").orElse("").contains("error=invalid_scope"));

		// No bearer: with public access on the caller is the public principal, not a resource owner.
		HttpResponse<String> anon = get("/oauth/authorize?response_type=code&client_id=web-app&redirect_uri=" + reg + pkce, null, false);
		assertEquals(302, anon.statusCode());
		assertTrue(anon.headers().firstValue("Location").orElse("").contains("error=access_denied"), anon.headers().map().toString());
	}

	@Test
	public void testTokenRefusals() throws Exception {
		// Wrong client secret is 401 invalid_client.
		String v = verifier();
		String code = authorizeCode("web-app", "https://app.example/callback", "read", v, null);
		HttpResponse<String> wrongSecret = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "authorization_code", "code", code,
			"redirect_uri", "https://app.example/callback", "code_verifier", v)), "web-app:nope");
		assertEquals(401, wrongSecret.statusCode());
		assertTrue(wrongSecret.body().contains("invalid_client"), wrongSecret.body());

		// Unsupported grant type.
		HttpResponse<String> grant = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "password", "username", "x")), "web-app:web-app-secret");
		assertEquals(400, grant.statusCode());
		assertTrue(grant.body().contains("unsupported_grant_type"), grant.body());

		// A code issued for web-app cannot be redeemed by cli.
		String v2 = verifier();
		String code2 = authorizeCode("web-app", "https://app.example/callback", "read", v2, null);
		HttpResponse<String> wrongClient = postForm("/oauth/token", new HashMap<>(Map.of(
			"grant_type", "authorization_code", "code", code2,
			"redirect_uri", "https://app.example/callback", "code_verifier", v2, "client_id", "cli")), null);
		assertEquals(400, wrongClient.statusCode());
		assertTrue(wrongClient.body().contains("invalid_grant"), wrongClient.body());
	}

	@Test
	public void testProviderConfigValidation() {
		assertThrows(() -> OAuthProvider.build(server.getEngine(), Maps.of("enabled", true)),
			"enabled provider with no clients");
		assertThrows(() -> OAuthProvider.build(server.getEngine(), Maps.of("enabled", true,
			"clients", Maps.of("c", Maps.of("redirectUris", Vectors.of("https://a/cb"))))),
			"a client needs a secret or public:true");
		assertThrows(() -> OAuthProvider.build(server.getEngine(), Maps.of("enabled", true,
			"clients", Maps.of("c", Maps.of("redirectUris", Vectors.of("http://not-loopback.example/cb"), "public", true)))),
			"plain http only for loopback");
		assertThrows(() -> OAuthProvider.build(server.getEngine(), Maps.of("enabled", true,
			"clients", Maps.of("c", Maps.of("redirectUris", Vectors.of("https://a/cb"), "secret", "literal")))),
			"secret must be an s/NAME reference");
	}

	private static void assertThrows(Runnable r, String because) {
		try {
			r.run();
			org.junit.jupiter.api.Assertions.fail("expected failure: " + because);
		} catch (IllegalArgumentException expected) {
			// good
		}
	}
}
