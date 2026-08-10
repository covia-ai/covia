package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.AuthException;
import covia.exception.ResponseException;
import covia.grid.Principals;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

class UserAdmissionTest {

	@Test
	void httpAuthenticationDoesNotProvisionWhenAutoCreateIsOff() {
		VenueServer server = VenueServer.launch(Maps.of(
			Config.PORT, 0));
		try {
			AKeyPair keyPair = AKeyPair.generate();
			AString did = UCAN.toDIDKey(keyPair.getAccountKey());
			VenueHTTP client = VenueHTTP.create(
				URI.create("http://localhost:" + server.port()), VenueAuth.keyPair(keyPair));

			CompletionException failure = assertThrows(CompletionException.class,
				() -> client.startJobAsync(Strings.create("v/test/ops/echo"), Maps.empty()).join());
			assertInstanceOf(ResponseException.class, failure.getCause());
			assertTrue(failure.getCause().getMessage().contains("403"));
			assertTrue(failure.getCause().getMessage().contains("user:create"));
			assertNull(server.getEngine().getVenueState().users().get(did));
		} finally {
			server.close();
		}
	}

	@Test
	void admissionDenialHasStructuredBodyBeforeJobCreation() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(Config.PORT, 0));
		try {
			AKeyPair keyPair = AKeyPair.generate();
			AString did = UCAN.toDIDKey(keyPair.getAccountKey());
			String token = VenueAuth.keyPair(keyPair,
				server.getEngine().getDIDString().toString(), 300).mintToken();
			HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5)).build();
			String requestBody = "{\"operation\":\"v/test/ops/echo\",\"input\":{}}";

			for (String path : new String[] { "/api/v1/invoke", "/api/v1/run" }) {
				HttpRequest request = HttpRequest.newBuilder(URI.create(
						"http://localhost:" + server.port() + path))
					.timeout(Duration.ofSeconds(5))
					.header("Authorization", "Bearer " + token)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(requestBody))
					.build();
				HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString());

				assertEquals(403, response.statusCode(), path + ": " + response.body());
				assertTrue(response.headers().firstValue("Content-Type")
					.orElse("").startsWith("application/json"),
					path + ": " + response.headers());
				assertTrue(response.body().contains("\"error\""), response.body());
				assertTrue(response.body().contains("User is not registered"),
					response.body());
				assertTrue(response.body().contains("user:create"), response.body());
				assertTrue(response.body().contains(did.toString()), response.body());
			}

			assertNull(server.getEngine().getVenueState().users().get(did));
		} finally {
			server.close();
		}
	}

	@Test
	void unknownAuthenticatedDIDIsRejectedWithoutCreatingState() {
		Engine engine = configuredEngine(false);
		try {
			AString did = newDID();
			ExecutionException failure = assertThrows(ExecutionException.class,
				() -> engine.jobs().invokeInternal("v/test/ops/echo", Maps.of(),
					RequestContext.of(did)).get(5, TimeUnit.SECONDS));
			assertInstanceOf(AuthException.class, failure.getCause());
			assertTrue(failure.getCause().getMessage().contains("user:create"));
			assertNull(engine.getVenueState().users().get(did));
		} finally {
			engine.close();
		}
	}

	@Test
	void explicitlyProvisionedDIDCanInvoke() throws Exception {
		Engine engine = configuredEngine(false);
		try {
			AString did = newDID();
			engine.getVenueState().users().create(did);
			ACell input = Maps.of("hello", "world");
			ACell output = engine.jobs().invokeInternal("v/test/ops/echo", input,
				RequestContext.of(did)).get(5, TimeUnit.SECONDS);
			assertEquals(input, output);
		} finally {
			engine.close();
		}
	}

	@Test
	void autoCreateOptInRegistersUnknownDIDOnFirstInvocation() throws Exception {
		Engine engine = configuredEngine(true);
		try {
			AString did = newDID();
			assertNull(engine.getVenueState().users().get(did));
			engine.jobs().invokeInternal("v/test/ops/echo", Maps.of(),
				RequestContext.of(did)).get(5, TimeUnit.SECONDS);
			assertNotNull(engine.getVenueState().users().get(did));
		} finally {
			engine.close();
		}
	}

	@Test
	void agentShapedDIDIsNeverAdmittedAsAUser() {
		// <owner>:g:<id> resolves to the owner's namespace, so letting an external
		// principal authenticate as one would hand its bearer that whole account —
		// the sharp edge of a parseable sub-principal name. admitUser is the
		// authentication boundary (AuthMiddleware.markAuthenticated calls it with
		// the raw presented DID), so the guard belongs there and nowhere else.
		// Rejected even with autoCreate on, the configuration where an unknown DID
		// would otherwise self-register.
		Engine engine = configuredEngine(true);
		try {
			AString owner = newDID();
			engine.getVenueState().users().ensure(owner);
			AString impostor = Principals.agentDID(owner, Strings.create("pwn"));

			assertThrows(AuthException.class, () -> engine.admitUser(impostor));
			assertNull(engine.getVenueState().users().get(impostor),
				"an agent-shaped DID must never gain a user record");

			// Deliberately NOT guarded: a RequestContext built in-process is
			// trusted by construction, and a sub-principal context is exactly what
			// AgentAdapter.wakeAgent must be able to build. Such a context admits
			// through its OWNER, which is the intended behaviour — an agent runs
			// inside an account that already exists.
			assertEquals(owner, RequestContext.of(impostor).getUserDID());
		} finally {
			engine.close();
		}
	}

	@Test
	void frameworkPrincipalsAreBootstrappedWithoutAutoCreate() {
		Engine engine = configuredEngine(false);
		try {
			assertNotNull(engine.getVenueState().users().get(engine.getDIDString()));
			assertNotNull(engine.getVenueState().users().get(
				engine.getDIDString().toString() + ":public"));
		} finally {
			engine.close();
		}
	}

	private static Engine configuredEngine(boolean autoCreate) {
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, autoCreate)));
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static AString newDID() {
		return UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
	}
}
