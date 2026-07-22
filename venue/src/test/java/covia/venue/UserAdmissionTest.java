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

import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.AuthException;
import covia.exception.ResponseException;
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
