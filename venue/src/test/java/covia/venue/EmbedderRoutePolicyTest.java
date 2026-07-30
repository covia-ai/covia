package covia.venue;

import static covia.venue.server.VenueRouteFeature.ADMITTED_USER;
import static covia.venue.server.VenueRouteFeature.AUTHENTICATED_IDENTITY;
import static covia.venue.server.VenueRouteFeature.RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import covia.api.Fields;
import covia.grid.auth.VenueAuth;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.VenueServer;
import io.javalin.security.RouteRole;

/**
 * Black-box coverage for the public route-policy seam used by embedding venue
 * operators such as GetMine (#309).
 */
class EmbedderRoutePolicyTest {

	private static final String RAW = "/api/getmine/raw";
	private static final String PRODUCT_ROLE = "/api/getmine/product-role";
	private static final String IDENTITY = "/api/getmine/identity";
	private static final String USER = "/api/getmine/user";

	private enum GetMineRole implements RouteRole {
		ADMIN
	}

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	@Test
	void contributedRoutesAreRawUntilTheyOptIntoVenuePolicy() throws Exception {
		VenueServer server = launch(false);
		try {
			AKeyPair key = AKeyPair.generate();
			AString did = UCAN.toDIDKey(key.getAccountKey());
			String token = tokenFor(key, server);

			assertResponse(200, "anonymous", get(server, RAW, null));
			assertResponse(200, "anonymous", get(server, RAW, "not-a-jwt"));
			assertResponse(200, "product", get(server, PRODUCT_ROLE, "not-a-jwt"));
			assertNull(server.getEngine().getVenueState().users().get(did));

			assertEquals(401, get(server, IDENTITY, null).statusCode());
			assertEquals(401, get(server, IDENTITY, "not-a-jwt").statusCode());
			assertEquals(401, get(server, IDENTITY,
				VenueAuth.keyPair(key,
					UCAN.toDIDKey(AKeyPair.generate().getAccountKey()).toString(),
					300).mintToken()).statusCode());

			assertResponse(200, did.toString(), get(server, IDENTITY, token));
			assertNull(server.getEngine().getVenueState().users().get(did),
				"identity verification alone must not provision venue state");

			assertEquals(403, get(server, USER, token).statusCode());
			assertNull(server.getEngine().getVenueState().users().get(did));

			server.getEngine().getVenueState().users().create(did);
			assertResponse(200, did.toString(), get(server, USER, token));
		} finally {
			server.close();
		}
	}

	@Test
	void identityOnlyNeverAutoCreatesButAdmissionCan() throws Exception {
		VenueServer server = launch(true);
		try {
			AKeyPair identityKey = AKeyPair.generate();
			AString identityDid = UCAN.toDIDKey(identityKey.getAccountKey());
			assertResponse(200, identityDid.toString(),
				get(server, IDENTITY, tokenFor(identityKey, server)));
			assertNull(server.getEngine().getVenueState().users().get(identityDid),
				"AUTHENTICATED_IDENTITY must remain side-effect free with autoCreate");

			AKeyPair admittedKey = AKeyPair.generate();
			AString admittedDid = UCAN.toDIDKey(admittedKey.getAccountKey());
			assertResponse(200, admittedDid.toString(),
				get(server, USER, tokenFor(admittedKey, server)));
			assertEquals(admittedDid, server.getEngine().getVenueState().users()
				.get(admittedDid).getDID());
		} finally {
			server.close();
		}
	}

	@Test
	void nativeSurfacesKeepAdmissionPolicy() throws Exception {
		VenueServer server = launch(false);
		try {
			AKeyPair key = AKeyPair.generate();
			AString did = UCAN.toDIDKey(key.getAccountKey());
			String token = tokenFor(key, server);

			assertEquals(403, get(server, "/api/v1/status", token).statusCode(),
				"native REST must still reject an unknown authenticated DID");
			assertEquals(403, post(server, "/mcp", token, "{}").statusCode(),
				"native MCP must still reject an unknown authenticated DID");
			assertNull(server.getEngine().getVenueState().users().get(did));
		} finally {
			server.close();
		}
	}

	@Test
	void rawRoutesCanOptIntoVenueRateLimiting() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				"rps", 1L,
				"burst", 1L)),
			List.of(routes -> routes.get("/api/getmine/limited",
				ctx -> ctx.result("ok"), RATE_LIMITED)));
		try {
			assertEquals(200, get(server, "/api/getmine/limited", null).statusCode());
			HttpResponse<String> denied =
				get(server, "/api/getmine/limited", null);
			assertEquals(429, denied.statusCode());
			assertEquals("1",
				denied.headers().firstValue("Retry-After").orElse(null));
		} finally {
			server.close();
		}
	}

	private VenueServer launch(boolean autoCreate) {
		return VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.USERS, Maps.of(Config.AUTO_CREATE, autoCreate),
			Config.RATE_LIMIT, Maps.of(Config.ENABLED, false),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.AUDIENCE, "require"),
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(Config.REQUIRED, true))),
			List.of(routes -> {
				routes.get(RAW, ctx -> {
					AString caller = AuthMiddleware.getCallerDID(ctx);
					ctx.result(caller == null ? "anonymous" : caller.toString());
				});
				routes.get(PRODUCT_ROLE, ctx -> ctx.result("product"),
					GetMineRole.ADMIN);
				routes.get(IDENTITY, ctx ->
					ctx.result(AuthMiddleware.getCallerDID(ctx).toString()),
					AUTHENTICATED_IDENTITY);
				routes.get(USER, ctx ->
					ctx.result(AuthMiddleware.getCallerDID(ctx).toString()),
					ADMITTED_USER);
			}));
	}

	private HttpResponse<String> get(VenueServer server, String path,
			String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(
				URI.create(base(server) + path))
			.timeout(Duration.ofSeconds(10))
			.GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(VenueServer server, String path,
			String token, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(
				URI.create(base(server) + path))
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String tokenFor(AKeyPair key, VenueServer server) {
		return VenueAuth.keyPair(key,
			server.getEngine().getDIDString().toString(), 300).mintToken();
	}

	private static String base(VenueServer server) {
		return "http://127.0.0.1:" + server.port();
	}

	private static void assertResponse(int status, String body,
			HttpResponse<String> response) {
		assertEquals(status, response.statusCode(), response::body);
		assertEquals(body, response.body());
	}
}
