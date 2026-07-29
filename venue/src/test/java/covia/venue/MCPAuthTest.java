package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.auth.VenueAuth;
import covia.venue.server.VenueServer;

/**
 * #308 — MCP transport authentication with public discovery.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class MCPAuthTest {

	private static final String INITIALIZE = """
		{
		  "jsonrpc": "2.0",
		  "id": 1,
		  "method": "initialize",
		  "params": {
		    "protocolVersion": "2025-11-25",
		    "capabilities": {},
		    "clientInfo": {"name": "covia-test", "version": "1"}
		  }
		}
		""";

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private VenueServer server;
	private String baseUrl;
	private AKeyPair allowedKey;
	private AString allowedDid;
	private AKeyPair namedKey;
	private AKeyPair peerKey;
	private AKeyPair outsiderKey;
	private AString namedDid;
	private AString peerDid;
	private AString outsiderDid;

	@TempDir
	Path tempDir;

	@BeforeAll
	void setup() {
		allowedKey = AKeyPair.generate();
		allowedDid = UCAN.toDIDKey(allowedKey.getAccountKey());
		namedKey = AKeyPair.generate();
		peerKey = AKeyPair.generate();
		outsiderKey = AKeyPair.generate();
		namedDid = Strings.create("did:web:named-mcp.example:u:admin");
		peerDid = Strings.create("did:web:named-mcp.example:u:peer");
		outsiderDid = Strings.create("did:web:named-mcp.example:u:outsider");
		server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.HOSTNAME, "named-mcp.example",
			Config.USERS, Maps.of(
				Config.AUTO_CREATE, true,
				Config.BOOTSTRAP, Maps.of(
					"admin", Maps.of(Fields.AUTHENTICATION_KEYS,
						Vectors.of(UCAN.toDIDKey(namedKey.getAccountKey()))),
					"peer", Maps.of(Fields.AUTHENTICATION_KEYS,
						Vectors.of(UCAN.toDIDKey(peerKey.getAccountKey()))),
					"outsider", Maps.of(Fields.AUTHENTICATION_KEYS,
						Vectors.of(UCAN.toDIDKey(outsiderKey.getAccountKey()))))),
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, false),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.AUDIENCE, "require"),
			Fields.MCP, Maps.of(
				"includeAdapters", Vectors.of("covia", "secret", "user"),
				Config.AUTH, Maps.of(
					Config.REQUIRED, true,
					Config.ALLOWED_DIDS, Vectors.of(
						allowedDid, namedDid, peerDid)))));
		baseUrl = "http://localhost:" + server.port();
	}

	@AfterAll
	void teardown() {
		if (server != null) {
			try {
				server.close();
			} catch (Exception ignored) {
				// Best effort in test teardown.
			}
		}
	}

	@Test
	void discoveryRemainsPublicAndAdvertisesEffectiveAuthPolicy() throws Exception {
		HttpResponse<String> response = get("/.well-known/mcp");
		assertEquals(200, response.statusCode());

		ACell body = JSON.parse(response.body());
		assertEquals(Strings.create(baseUrl + "/mcp"), RT.getIn(body, "server_url"));
		assertEquals(CVMBool.TRUE,
			RT.getIn(body, "_meta", "ai.covia/authentication", "required"));
		assertEquals(Strings.create(baseUrl + "/.well-known/oauth-protected-resource/mcp"),
			RT.getIn(body, "_meta", "ai.covia/authentication",
				"protected_resource_metadata"));
		assertEquals(CVMBool.TRUE,
			RT.getIn(body, "_meta", "ai.covia/authentication", "did_allowlist"));
		assertFalse(response.body().contains(allowedDid.toString()),
			"public discovery must not disclose the DID allowlist");
	}

	@Test
	void protectedResourceMetadataIsPublicAndRfc9728Shaped() throws Exception {
		HttpResponse<String> response =
			get("/.well-known/oauth-protected-resource/mcp");
		assertEquals(200, response.statusCode());

		ACell body = JSON.parse(response.body());
		assertEquals(Strings.create(baseUrl + "/mcp"), RT.getIn(body, "resource"));
		assertEquals(Strings.create("header"),
			RT.getIn(body, "bearer_methods_supported", 0L));
		assertEquals(CVMBool.TRUE,
			RT.getIn(body, "_meta", "ai.covia/authentication", "required"));
		assertNull(RT.getIn(body, "authorization_servers"),
			"Covia must not claim an OAuth authorization server it does not provide");
	}

	@Test
	void anonymousTransportGetsStandardsShapedChallenge() throws Exception {
		HttpResponse<String> response = post(null);
		assertEquals(401, response.statusCode());
		assertEquals(
			"Bearer resource_metadata=\"" + baseUrl
				+ "/.well-known/oauth-protected-resource/mcp\"",
			response.headers().firstValue("WWW-Authenticate").orElse(null));
	}

	@Test
	void futureMcpSubRoutesAreFailClosed() throws Exception {
		HttpResponse<String> response = get("/mcp/future");
		assertEquals(401, response.statusCode(),
			"an unimplemented future MCP route must cross auth before returning 404");
	}

	@Test
	void allowedAuthenticatedDidCanUseMcp() throws Exception {
		HttpResponse<String> response = post(selfIssued(allowedKey));
		assertEquals(200, response.statusCode(), response::body);
		assertTrue(response.body().contains("\"result\""));
	}

	@Test
	void authenticatedDidOutsideAllowlistGetsForbidden() throws Exception {
		HttpResponse<String> response = post(selfIssued(AKeyPair.generate()));
		assertEquals(403, response.statusCode());
		assertTrue(response.body().contains("not allowed"));
	}

	@Test
	void namedCredentialAuthenticatesAsStableAllowlistedSubject() throws Exception {
		String token = namedToken(namedKey, namedDid, server);
		HttpResponse<String> initialized = post(token);
		assertEquals(200, initialized.statusCode(), initialized::body);

		AMap<AString, ACell> write = toolCall(baseUrl, token, "covia_write",
			Maps.of(Fields.PATH, "w/mcp-credential", Fields.VALUE, "owned"));
		assertToolSuccess(write);

		AMap<AString, ACell> read = toolCall(baseUrl, token, "covia_read",
			Maps.of(Fields.PATH, "w/mcp-credential"));
		assertToolSuccess(read);
		assertTrue(read.toString().contains("owned"), read::toString);

		String outsider = namedToken(outsiderKey, outsiderDid, server);
		HttpResponse<String> forbidden = post(outsider);
		assertEquals(403, forbidden.statusCode());
		assertTrue(forbidden.body().contains("not allowed"));
	}

	@Test
	void namedCredentialRejectsForgeryReplayAndInvalidTemporalClaims() throws Exception {
		AString venueDid = server.getEngine().getDIDString();
		long now = System.currentTimeMillis() / 1000;

		assertEquals(401, post(namedClaimsToken(
			AKeyPair.generate(), namedDid, venueDid, now - 1, now + 300, null))
			.statusCode(), "an unregistered signing key cannot impersonate the named subject");
		assertEquals(401, post(namedClaimsToken(
			namedKey, namedDid, UCAN.toDIDKey(AKeyPair.generate().getAccountKey()),
			now - 300, now + 300, null)).statusCode(),
			"an audience-bound credential must not replay at another venue");
		assertEquals(401, post(namedClaimsToken(
			namedKey, namedDid, null, now - 300, now + 300, null)).statusCode(),
			"auth.audience=require must reject a missing audience");
		assertEquals(401, post(namedClaimsToken(
			namedKey, namedDid, venueDid, now - 300, now - 120, null)).statusCode(),
			"an expired credential must fail beyond clock-skew leeway");
		assertEquals(401, post(namedClaimsToken(
			namedKey, namedDid, venueDid, now, now + 600, now + 180)).statusCode(),
			"a not-yet-valid credential must fail beyond clock-skew leeway");
		assertEquals(401, post("not-a-jwt").statusCode(),
			"malformed credentials must fail rather than downgrade to public access");

		String signingKeyAsSubject = VenueAuth.keyPair(
			namedKey, venueDid.toString(), 300).mintToken();
		assertEquals(403, post(signingKeyAsSubject).statusCode(),
			"registering a key for a named user must not allow its did:key "
				+ "principal through the named-subject MCP allowlist");
	}

	@Test
	void mcpAllowlistDoesNotGrantCrossUserAuthority() throws Exception {
		String adminToken = namedToken(namedKey, namedDid, server);
		String peerToken = namedToken(peerKey, peerDid, server);
		String target = peerDid + "/w/delegated";

		AMap<AString, ACell> ungranted = toolCall(baseUrl, adminToken, "covia_write",
			Maps.of(Fields.PATH, target, Fields.VALUE, "must-not-land"));
		assertToolError(ungranted);

		String readOnly = delegation(server, namedDid, target,
			Capability.CRUD_READ);
		AMap<AString, ACell> wrongAbility = toolCall(baseUrl, adminToken,
			"covia_write", Maps.of(
				Fields.PATH, target,
				Fields.VALUE, "must-not-land",
				Fields.UCANS, Vectors.of(readOnly)));
		assertToolError(wrongAbility);

		String wrongAudience = delegation(server, outsiderDid, target,
			Capability.CRUD_WRITE);
		AMap<AString, ACell> notForCaller = toolCall(baseUrl, adminToken,
			"covia_write", Maps.of(
				Fields.PATH, target,
				Fields.VALUE, "must-not-land",
				Fields.UCANS, Vectors.of(wrongAudience)));
		assertToolError(notForCaller);

		String writeGrant = delegation(server, namedDid, target,
			Capability.CRUD_WRITE);
		AMap<AString, ACell> granted = toolCall(baseUrl, adminToken, "covia_write",
			Maps.of(
				Fields.PATH, target,
				Fields.VALUE, "delegated-value",
				Fields.UCANS, Vectors.of(writeGrant)));
		assertToolSuccess(granted);
		String adminJobs = server.getEngine().jobs()
			.getJobs(RequestContext.of(namedDid)).toString();
		assertFalse(adminJobs.contains(readOnly));
		assertFalse(adminJobs.contains(wrongAudience));
		assertFalse(adminJobs.contains(writeGrant),
			"raw MCP UCAN proofs must not be persisted as operation input");

		AMap<AString, ACell> peerRead = toolCall(baseUrl, peerToken, "covia_read",
			Maps.of(Fields.PATH, "w/delegated"));
		assertToolSuccess(peerRead);
		assertTrue(peerRead.toString().contains("delegated-value"),
			peerRead::toString);
	}

	@Test
	void mcpSessionsAreBoundToTheAuthenticatedSubject() throws Exception {
		String adminToken = namedToken(namedKey, namedDid, server);
		String peerToken = namedToken(peerKey, peerDid, server);
		HttpResponse<String> initialized = post(adminToken);
		assertEquals(200, initialized.statusCode());
		String sessionId = initialized.headers()
			.firstValue("Mcp-Session-Id").orElse(null);
		assertNotNull(sessionId);

		HttpResponse<String> stolenDelete =
			deleteMcp(baseUrl, peerToken, sessionId);
		assertEquals(404, stolenDelete.statusCode(),
			"another allowed principal must not terminate a stolen MCP session");

		HttpResponse<String> ownerDelete =
			deleteMcp(baseUrl, adminToken, sessionId);
		assertEquals(200, ownerDelete.statusCode(),
			"the creating principal must retain control of its session");
	}

	@Test
	void mcpSecretCallsDoNotPersistBearerOrPlaintext() throws Exception {
		String token = namedToken(namedKey, namedDid, server);
		String plaintext = "mcp-secret-" + System.nanoTime();
		long before = server.getEngine().jobs()
			.getJobs(RequestContext.of(namedDid)).count();

		HttpResponse<String> response = postJson(baseUrl, token,
			rpc("tools/call", Maps.of(
				Fields.NAME, "secret_set",
				Fields.ARGUMENTS, Maps.of(
					Fields.NAME, "MCP_TEST_SECRET",
					Fields.VALUE, plaintext))));
		assertEquals(200, response.statusCode());
		assertToolSuccess(RT.ensureMap(JSON.parse(response.body())));
		assertFalse(response.body().contains(plaintext),
			"secret:set must not echo plaintext through MCP");
		assertFalse(response.body().contains(token),
			"the Authorization bearer must never enter the tool result");

		var jobs = server.getEngine().jobs().getJobs(RequestContext.of(namedDid));
		assertTrue(jobs.count() > before);
		String persisted = jobs.toString();
		assertFalse(persisted.contains(plaintext),
			"secret plaintext must be redacted from durable job input");
		assertFalse(persisted.contains(token),
			"transport credentials must never be persisted with jobs");
	}

	@Test
	void rotationRevocationAndAuthenticatorHistorySurviveRestart() throws Exception {
		Path store = tempDir.resolve("credential-lifecycle.etch");
		AKeyPair oldKey = AKeyPair.generate();
		AKeyPair replacement = AKeyPair.generate();
		AString oldKeyDid = UCAN.toDIDKey(oldKey.getAccountKey());
		AString replacementDid = UCAN.toDIDKey(replacement.getAccountKey());
		AString subject = Strings.create("did:web:persistent-mcp.example:u:operator");
		String seed = AKeyPair.createSeeded(7303).getSeed().toHexString();
		AMap<AString, ACell> config = persistentCredentialConfig(
			store, seed, oldKeyDid, subject);

		VenueServer first = VenueServer.launch(config);
		String oldToken;
		String replacementToken;
		try {
			oldToken = namedToken(oldKey, subject, first);
			replacementToken = namedToken(replacement, subject, first);
			HttpResponse<String> initialized =
				postJson(firstBase(first), oldToken, INITIALIZE);
			assertEquals(200, initialized.statusCode());
			String sessionId = initialized.headers()
				.firstValue("Mcp-Session-Id").orElseThrow();

			assertToolSuccess(toolCall(firstBase(first), oldToken,
				"user_authentication-add", Maps.of(
					Fields.KEY, replacementDid,
					Fields.LABEL, "replacement")));
			assertToolSuccess(toolCall(firstBase(first), oldToken,
				"user_authentication-revoke", Maps.of(Fields.KEY, oldKeyDid)));

			assertEquals(401, postJson(firstBase(first), oldToken, INITIALIZE)
				.statusCode(), "revocation must invalidate an already-minted bearer");
			assertEquals(200, postJson(firstBase(first), replacementToken, INITIALIZE)
				.statusCode());
			assertEquals(401, deleteMcp(firstBase(first), oldToken, sessionId)
				.statusCode(), "a revoked credential cannot reuse its existing session");
			assertEquals(200, deleteMcp(firstBase(first), replacementToken, sessionId)
				.statusCode(), "a replacement key for the same stable subject "
					+ "retains control of that subject's session");

			AMap<AString, ACell> lastKey = toolCall(firstBase(first),
				replacementToken, "user_authentication-revoke",
				Maps.of(Fields.KEY, replacementDid));
			assertToolError(lastKey);
			assertEquals(200, postJson(firstBase(first), replacementToken, INITIALIZE)
				.statusCode(), "failed last-key revocation must leave the key active");
		} finally {
			first.close();
		}

		VenueServer restarted = VenueServer.launch(config);
		try {
			assertEquals(401, postJson(firstBase(restarted), oldToken, INITIALIZE)
				.statusCode(), "revoked-key tombstone must survive restart");
			assertEquals(200, postJson(firstBase(restarted), replacementToken, INITIALIZE)
				.statusCode(), "replacement authenticator must survive restart");
			assertFalse(restarted.getEngine().getAuth().isAuthenticationKeyActive(
				Strings.create("operator"), oldKeyDid));
			assertTrue(restarted.getEngine().getAuth().isAuthenticationKeyActive(
				Strings.create("operator"), replacementDid));
		} finally {
			restarted.close();
		}
	}

	@Test
	void invalidPolicyShapesFailClosed() {
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Fields.MCP, Maps.of(Config.AUTH, CVMBool.TRUE))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(Config.REQUIRED, Strings.create("yes"))))));
		assertThrows(IllegalArgumentException.class, () -> new Config(Maps.of(
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(
					Config.ALLOWED_DIDS, Strings.create("did:key:z"))))));
	}

	@Test
	void effectivePolicyPreservesVenueWidePrivacyAndAllowlistIntent() {
		Config openVenue = new Config(Maps.of(
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true)),
			Fields.MCP, Maps.empty()));
		assertFalse(openVenue.isMCPAuthRequired());

		Config privateVenueExplicitlyFalse = new Config(Maps.of(
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false)),
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(Config.REQUIRED, false))));
		assertTrue(privateVenueExplicitlyFalse.isMCPAuthRequired(),
			"MCP must not override disabled venue-wide public access");

		Config allowlistedVenue = new Config(Maps.of(
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true)),
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(
					Config.REQUIRED, false,
					Config.ALLOWED_DIDS, Vectors.of(allowedDid)))));
		assertTrue(allowlistedVenue.isMCPAuthRequired(),
			"a DID allowlist is meaningless without authentication");
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String token) throws Exception {
		return postJson(baseUrl, token, INITIALIZE);
	}

	private HttpResponse<String> postJson(String targetBase, String token,
			String body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create(targetBase + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.timeout(Duration.ofSeconds(10));
		if (token != null) builder.header("Authorization", "Bearer " + token);
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> deleteMcp(String targetBase, String token,
			String sessionId) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create(targetBase + "/mcp"))
			.header("Mcp-Session-Id", sessionId)
			.DELETE()
			.timeout(Duration.ofSeconds(10));
		if (token != null) builder.header("Authorization", "Bearer " + token);
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private AMap<AString, ACell> toolCall(String targetBase, String token,
			String toolName, ACell arguments) throws Exception {
		HttpResponse<String> response = postJson(targetBase, token,
			rpc("tools/call", Maps.of(
				Fields.NAME, toolName,
				Fields.ARGUMENTS, arguments)));
		assertEquals(200, response.statusCode(), response::body);
		AMap<AString, ACell> parsed = RT.ensureMap(JSON.parse(response.body()));
		assertNotNull(parsed, response::body);
		return parsed;
	}

	private static String rpc(String method, ACell params) {
		return JSON.print(Maps.of(
			"jsonrpc", "2.0",
			"id", "security-test",
			"method", method,
			"params", params)).toString();
	}

	private static void assertToolSuccess(AMap<AString, ACell> response) {
		assertNull(response.get(Strings.create("error")), response::toString);
		assertEquals(CVMBool.FALSE,
			RT.getIn(response, Fields.RESULT, "isError"), response::toString);
	}

	private static void assertToolError(AMap<AString, ACell> response) {
		assertNull(response.get(Strings.create("error")),
			"operation failures must be MCP tool results: " + response);
		assertEquals(CVMBool.TRUE,
			RT.getIn(response, Fields.RESULT, "isError"), response::toString);
	}

	private String namedToken(AKeyPair key, AString subject,
			VenueServer target) {
		return VenueAuth.namedKeyPair(
			key,
			subject.toString(),
			target.getEngine().getDIDString().toString(),
			3600).mintToken();
	}

	private static String namedClaimsToken(AKeyPair key, AString subject,
			AString audience, long issuedAt, long expiresAt, Long notBefore) {
		AMap<AString, ACell> claims = Maps.of(
			JWT.SUB, subject,
			JWT.ISS, subject,
			JWT.IAT, CVMLong.create(issuedAt),
			JWT.EXP, CVMLong.create(expiresAt));
		if (audience != null) claims = claims.assoc(JWT.AUD, audience);
		if (notBefore != null) {
			claims = claims.assoc(
				Strings.create("nbf"), CVMLong.create(notBefore));
		}
		return JWT.signPublic(claims, key).toString();
	}

	/**
	 * Venue-rooted UCAN whose audience is a stable named DID rather than the
	 * authentication key's did:key. Build from the standard UCAN claim shape,
	 * replace only aud, then re-sign the complete claims with the venue key.
	 */
	private String delegation(VenueServer target, AString audience,
			String resource, AString ability) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN template = UCAN.create(
			target.getEngine().getKeyPair(),
			namedKey.getAccountKey(),
			exp,
			Vectors.of(Capability.create(Strings.create(resource), ability)),
			Vectors.empty());
		AString templateJwt = template.toJWT(
			target.getEngine().getKeyPair());
		AMap<AString, ACell> claims = JWT.parse(templateJwt).getClaims()
			.assoc(UCAN.AUD, audience);
		return JWT.signPublic(claims, target.getEngine().getKeyPair()).toString();
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> persistentCredentialConfig(Path store,
			String seed, AString initialKey, AString subject) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Config.PORT, 0,
			Config.HOSTNAME, "persistent-mcp.example",
			Config.STORE, store.toString().replace('\\', '/'),
			Config.SEED, seed,
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, false),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, false),
				Config.AUDIENCE, "require"),
			Config.USERS, Maps.of(
				Config.AUTO_CREATE, false,
				Config.BOOTSTRAP, Maps.of(
					"operator", Maps.of(
						Fields.AUTHENTICATION_KEYS, Vectors.of(initialKey)))),
			Fields.MCP, Maps.of(
				"includeAdapters", Vectors.of("covia", "secret", "user"),
				Config.AUTH, Maps.of(
					Config.REQUIRED, true,
					Config.ALLOWED_DIDS, Vectors.of(subject))));
	}

	private static String firstBase(VenueServer target) {
		return "http://localhost:" + target.port();
	}

	private String selfIssued(AKeyPair keyPair) {
		AString did = UCAN.toDIDKey(keyPair.getAccountKey());
		AMap<AString, ACell> claims = Maps.of(
			Strings.create("sub"), did,
			Strings.create("aud"), server.getEngine().getDIDString(),
			Strings.create("exp"),
				CVMLong.create((System.currentTimeMillis() / 1000) + 3600));
		return JWT.signPublic(claims, keyPair).toString();
	}
}
