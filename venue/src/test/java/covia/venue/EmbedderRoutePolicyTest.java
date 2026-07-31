package covia.venue;

import static covia.venue.server.VenueRouteFeature.ADMITTED_USER;
import static covia.venue.server.VenueRouteFeature.AUTHENTICATED_IDENTITY;
import static covia.venue.server.VenueRouteFeature.RATE_LIMITED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.grid.auth.VenueAuth;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.VenueServer;
import io.javalin.config.RoutesConfig;
import io.javalin.security.RouteRole;

/**
 * Black-box coverage for the public route-policy seam used by embedding venue
 * operators such as GetMine (#309). All tests share one venue fixture.
 */
@TestInstance(Lifecycle.PER_CLASS)
class EmbedderRoutePolicyTest {

	private static final String RAW = "/api/getmine/raw";
	private static final String PRODUCT_ROLE = "/api/getmine/product-role";
	private static final String IDENTITY = "/api/getmine/identity";
	private static final String IDENTITIES = "/api/getmine/identities";
	private static final String USER = "/api/getmine/user";
	private static final String LIMITED = "/api/getmine/limited";
	private static final String CUSTOM_AUTH = "/api/getmine/custom-auth";
	private static final String AUTHENTICATED_IDENTITY_HEADER =
		"X-GetMine-Authenticated-Identity";
	private static final String VENUE_USER_HEADER = "X-GetMine-Venue-User";

	private enum GetMineRole implements RouteRole {
		ADMIN
	}

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private VenueServer strictServer;
	private AKeyPair operatorKey;
	private AString operatorKeyDID;
	private AString operatorDID;

	@BeforeAll
	void setup() {
		operatorKey = AKeyPair.generate();
		operatorKeyDID = UCAN.toDIDKey(operatorKey.getAccountKey());
		strictServer = launch();
		operatorDID = strictServer.getEngine()
			.managedUserDID(Strings.create("operator"));
	}

	@AfterAll
	void close() {
		if (strictServer != null) strictServer.close();
	}

	@Test
	void contributedRoutesAreRawUntilTheyOptIntoVenuePolicy() throws Exception {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		String token = tokenFor(key, strictServer);

		assertResponse(200, "anonymous", get(strictServer, RAW, null));
		assertResponse(200, "anonymous", get(strictServer, RAW, "not-a-jwt"));
		assertResponse(200, "product",
			get(strictServer, PRODUCT_ROLE, "not-a-jwt"));
		assertNull(strictServer.getEngine().getVenueState().users().get(did));

		assertEquals(401, get(strictServer, IDENTITY, null).statusCode());
		assertEquals(401,
			get(strictServer, IDENTITY, "not-a-jwt").statusCode());
		assertEquals(401, get(strictServer, IDENTITY,
			VenueAuth.keyPair(key,
				UCAN.toDIDKey(AKeyPair.generate().getAccountKey()).toString(),
				300).mintToken()).statusCode());

		assertResponse(200, did.toString(), get(strictServer, IDENTITY, token));
		assertNull(strictServer.getEngine().getVenueState().users().get(did),
			"identity verification alone must not provision venue state");

		assertEquals(403, get(strictServer, USER, token).statusCode());
		assertNull(strictServer.getEngine().getVenueState().users().get(did));

		strictServer.getEngine().getVenueState().users().create(did);
		assertResponse(200, did.toString(), get(strictServer, USER, token));
	}

	@Test
	void namedCredentialExposesAuthenticatorAndMappedVenueUser()
			throws Exception {
		String token = VenueAuth.namedKeyPair(
			operatorKey,
			operatorDID.toString(),
			strictServer.getEngine().getDIDString().toString(),
			300).mintToken();

		assertNotEquals(operatorKeyDID, operatorDID);
		assertResponse(200, operatorKeyDID + "|" + operatorDID,
			get(strictServer, IDENTITIES, token));
	}

	@Test
	void extenderCanOwnAuthenticationAndPublishBothIdentities()
			throws Exception {
		AString authenticatedIdentity =
			UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString venueUser =
			UCAN.toDIDKey(AKeyPair.generate().getAccountKey());

		assertResponse(200, authenticatedIdentity + "|" + venueUser,
			getWithIdentityHeaders(strictServer, CUSTOM_AUTH,
				authenticatedIdentity, venueUser));
		assertNull(strictServer.getEngine().getVenueState().users().get(venueUser),
			"publishing extender-owned auth context must not admit a venue user");
		assertEquals(401,
			get(strictServer, CUSTOM_AUTH, null).statusCode());
	}

	@Test
	void nativeSurfacesKeepAdmissionPolicy() throws Exception {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		String token = tokenFor(key, strictServer);

		assertEquals(403,
			get(strictServer, "/api/v1/status", token).statusCode(),
			"native REST must still reject an unknown authenticated DID");
		assertEquals(403,
			post(strictServer, "/mcp", token, "{}").statusCode(),
			"native MCP must still reject an unknown authenticated DID");
		assertNull(strictServer.getEngine().getVenueState().users().get(did));
	}

	@Test
	void rawRoutesCanOptIntoVenueRateLimiting() throws Exception {
		assertEquals(200, get(strictServer, LIMITED, null).statusCode());
		HttpResponse<String> denied = get(strictServer, LIMITED, null);
		assertEquals(429, denied.statusCode());
		assertEquals("1",
			denied.headers().firstValue("Retry-After").orElse(null));
	}

	private VenueServer launch() {
		return VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.HOSTNAME, "embedder.example",
			Config.USERS, Maps.of(
				Config.AUTO_CREATE, false,
				Config.BOOTSTRAP, Maps.of(
					"operator", Maps.of(
						Fields.AUTHENTICATION_KEYS,
						Vectors.of(operatorKeyDID)))),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				"rps", 1L,
				"burst", 1L),
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true),
				Config.AUDIENCE, "require"),
			Fields.MCP, Maps.of(
				Config.AUTH, Maps.of(Config.REQUIRED, true))),
			List.of(this::registerRoutes));
	}

	private void registerRoutes(RoutesConfig routes) {
		routes.get(RAW, ctx -> {
			AString caller = AuthMiddleware.getCallerDID(ctx);
			ctx.result(caller == null ? "anonymous" : caller.toString());
		});
		routes.get(PRODUCT_ROLE, ctx -> ctx.result("product"),
			GetMineRole.ADMIN);
		routes.get(IDENTITY, ctx ->
			ctx.result(AuthMiddleware.getVenueUserDID(ctx).toString()),
			AUTHENTICATED_IDENTITY);
		routes.get(IDENTITIES, ctx -> ctx.result(
			AuthMiddleware.getAuthenticatedIdentity(ctx)
				+ "|" + AuthMiddleware.getVenueUserDID(ctx)),
			AUTHENTICATED_IDENTITY);
		routes.get(USER, ctx ->
			ctx.result(AuthMiddleware.getVenueUserDID(ctx).toString()),
			ADMITTED_USER);
		routes.get(LIMITED, ctx -> ctx.result("ok"), RATE_LIMITED);

		routes.before(CUSTOM_AUTH, ctx -> {
			String authenticated = ctx.header(AUTHENTICATED_IDENTITY_HEADER);
			String venueUser = ctx.header(VENUE_USER_HEADER);
			if (authenticated == null || venueUser == null) {
				ctx.status(401).result("GetMine authentication required");
				ctx.skipRemainingHandlers();
				return;
			}
			AuthMiddleware.setRequestIdentity(ctx,
				Strings.create(authenticated), Strings.create(venueUser));
		});
		routes.get(CUSTOM_AUTH, ctx -> ctx.result(
			AuthMiddleware.getAuthenticatedIdentity(ctx)
				+ "|" + AuthMiddleware.getVenueUserDID(ctx)),
			GetMineRole.ADMIN);
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

	private HttpResponse<String> getWithIdentityHeaders(VenueServer server,
			String path, AString authenticatedIdentity, AString venueUser)
			throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
				URI.create(base(server) + path))
			.timeout(Duration.ofSeconds(10))
			.header(AUTHENTICATED_IDENTITY_HEADER,
				authenticatedIdentity.toString())
			.header(VENUE_USER_HEADER, venueUser.toString())
			.GET()
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
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
