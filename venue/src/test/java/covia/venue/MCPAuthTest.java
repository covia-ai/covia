package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.jwt.JWT;
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

	@BeforeAll
	void setup() {
		allowedKey = AKeyPair.generate();
		allowedDid = UCAN.toDIDKey(allowedKey.getAccountKey());
		server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, false),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true)),
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(
					Config.REQUIRED, true,
					Config.ALLOWED_DIDS, Vectors.of(allowedDid)))));
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
	void invalidPolicyShapesFailClosed() {
		Config malformedAuth = new Config(Maps.of(
			Fields.MCP, Maps.of(Config.AUTH, CVMBool.TRUE)));
		boolean authRejected = false;
		try {
			malformedAuth.isMCPAuthRequired();
		} catch (IllegalArgumentException expected) {
			authRejected = true;
		}
		assertTrue(authRejected);

		Config malformedRequired = new Config(Maps.of(
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(Config.REQUIRED, Strings.create("yes")))));
		boolean requiredRejected = false;
		try {
			malformedRequired.isMCPAuthRequired();
		} catch (IllegalArgumentException expected) {
			requiredRejected = true;
		}
		assertTrue(requiredRejected);

		Config malformedAllowlist = new Config(Maps.of(
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(Config.ALLOWED_DIDS, Strings.create("did:key:z")))));
		boolean allowlistRejected = false;
		try {
			malformedAllowlist.getMCPAllowedDids();
		} catch (IllegalArgumentException expected) {
			allowlistRejected = true;
		}
		assertTrue(allowlistRejected);
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
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
			.timeout(Duration.ofSeconds(10));
		if (token != null) builder.header("Authorization", "Bearer " + token);
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
