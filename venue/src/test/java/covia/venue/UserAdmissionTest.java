package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import covia.venue.api.ApiErrors;
import covia.venue.server.VenueServer;

class UserAdmissionTest {

	/** A venue with {@code users.autoCreate} off, which the shared one has on. */
	private static Engine engine;

	@BeforeAll
	static void startEngine() {
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, false)));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void closeEngine() {
		engine.close();
	}

	@Test
	void httpAuthenticationDoesNotProvisionWhenAutoCreateIsOff() {
		VenueServer server = VenueServer.launch(Maps.of(
			Config.PORT, 0, Config.STORE, "memory"));
		try {
			AKeyPair keyPair = AKeyPair.generate();
			AString did = UCAN.toDIDKey(keyPair.getAccountKey());
			VenueHTTP client = VenueHTTP.create(
				URI.create("http://localhost:" + server.port()), VenueAuth.keyPair(keyPair));

			CompletionException failure = assertThrows(CompletionException.class,
				() -> client.startJobAsync(Strings.create("v/test/ops/echo"), Maps.empty()).join());
			ResponseException refused = assertInstanceOf(ResponseException.class, failure.getCause());
			assertTrue(refused.getMessage().contains("403"));
			// The venue's reason stays in the response, never in the client's message
			HttpResponse<?> response = assertInstanceOf(HttpResponse.class, refused.response);
			assertTrue(String.valueOf(response.body()).contains("user:create"));
			assertFalse(refused.getMessage().contains("user:create"));
			assertNull(server.getEngine().getVenueState().users().user(did));
		} finally {
			server.close();
		}
	}

	@Test
	void admissionDenialHasStructuredBodyBeforeJobCreation() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(Config.PORT, 0, Config.STORE, "memory"));
		try {
			AKeyPair keyPair = AKeyPair.generate();
			AString did = UCAN.toDIDKey(keyPair.getAccountKey());
			String token = VenueAuth.keyPair(keyPair, 300).mintToken(server.getEngine().getDIDString().toString());
			HttpClient http = TestHTTP.CLIENT;
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
					.orElse("").startsWith(ApiErrors.PROBLEM_JSON),
					path + ": " + response.headers());
				assertTrue(response.body().contains("\"detail\""), response.body());
				assertTrue(response.body().contains("User is not registered"),
					response.body());
				assertTrue(response.body().contains("user:create"), response.body());
				assertTrue(response.body().contains(did.toString()), response.body());
			}

			assertNull(server.getEngine().getVenueState().users().user(did));
		} finally {
			server.close();
		}
	}

	@Test
	void unknownAuthenticatedDIDIsRejectedWithoutCreatingState() {
		AString did = newDID();
		ExecutionException failure = assertThrows(ExecutionException.class,
			() -> engine.jobs().invokeInternal("v/test/ops/echo", Maps.of(),
				RequestContext.of(did)).get(5, TimeUnit.SECONDS));
		assertInstanceOf(AuthException.class, failure.getCause());
		assertTrue(failure.getCause().getMessage().contains("user:create"));
		assertNull(engine.getVenueState().users().user(did));
	}

	@Test
	void explicitlyProvisionedDIDCanInvoke() throws Exception {
		AString did = newDID();
		engine.getVenueState().users().create(did);
		ACell input = Maps.of("hello", "world");
		ACell output = engine.jobs().invokeInternal("v/test/ops/echo", input,
			RequestContext.of(did)).get(5, TimeUnit.SECONDS);
		assertEquals(input, output);
	}

	@Test
	void autoCreateOptInRegistersUnknownDIDOnFirstInvocation() throws Exception {
		Engine open = TestEngine.ENGINE;
		AString did = newDID();
		assertNull(open.getVenueState().users().user(did));
		open.jobs().invokeInternal("v/test/ops/echo", Maps.of(),
			RequestContext.of(did)).get(5, TimeUnit.SECONDS);
		assertNotNull(open.getVenueState().users().user(did));
	}

	@Test
	void agentShapedDIDIsNeverAdmittedAsAUser() {
		// <owner>:g:<id> also addresses agent <id> of <owner>, so admitting one as a
		// user would make one name mean two principals. admitUser is the admission
		// boundary, so the guard belongs there and nowhere else. Rejected even with
		// autoCreate on, the configuration where an unknown DID would otherwise
		// self-register.
		Engine open = TestEngine.ENGINE;
		AString owner = newDID();
		open.getVenueState().users().ensure(owner);
		AString impostor = Principals.agentDID(owner, Strings.create("pwn"));

		assertThrows(AuthException.class, () -> open.admitUser(impostor));
		assertNull(open.getVenueState().users().user(impostor),
			"an agent-shaped DID must never gain a user record");

		// The name alone is its own namespace. The venue builds an agent's
		// context in its owner's namespace (as AgentAdapter.wakeAgent does), so
		// that context admits through its OWNER: an agent runs inside an
		// account that already exists.
		assertEquals(impostor, RequestContext.of(impostor).getUserDID());
		assertEquals(owner, RequestContext.ofAgent(owner, Strings.create("pwn")).getUserDID());
	}

	@Test
	void frameworkPrincipalsAreBootstrappedWithoutAutoCreate() {
		assertNotNull(engine.getVenueState().users().user(engine.getDIDString()));
		assertNotNull(engine.getVenueState().users().user(
			engine.getDIDString().toString() + ":public"));
	}

	private static AString newDID() {
		return UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
	}
}
