package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.AAdapter;
import covia.exception.AuthException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.server.VenueServer;

class AuthMethodsTest {
	private static final AString FIRST = Strings.intern("first");
	private static final AString SECOND = Strings.intern("second");
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final String REFUSED = "fixture refused input";
	private static final String WRONG_METHOD = "fixture-wrong-method";

	/** Deliberately not JWT, OAuth, HTTP, a session or a public operation. */
	private static class Fixture extends AAdapter implements AuthenticationMethods {
		private final Map<AString, Verifier> methods;
		private volatile AString label = Strings.create("original");
		private volatile CountDownLatch entered;
		private volatile CountDownLatch release;
		boolean wrongMethod;

		Fixture(boolean multiple) {
			Verifier verifier = (method, input) -> {
				if (!Maps.of("answer", 42).equals(input)) throw new AuthException(REFUSED);
				AString value = label;
				if (entered != null) {
					entered.countDown();
					try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
				}
				return new AuthResult(ALICE, ALICE, null,
					new AuthResult.Authentication(wrongMethod ? Strings.create(WRONG_METHOD) : method,
						null, null, Sets.empty(), Maps.of("label", value)), null, null);
			};
			methods = multiple ? Map.of(FIRST, verifier, SECOND, verifier) : Map.of(FIRST, verifier);
		}
		@Override public String getName() { return "auth-fixture"; }
		@Override public String getDescription() { return "Test authentication extension"; }
		@Override protected void installAssets() {}
		@Override public Map<AString, Verifier> authenticationMethods() { return methods; }
		@Override public boolean configure(AMap<AString, ACell> config, boolean strict) {
			ACell value = config.get(Strings.intern("label"));
			if (value != null && !(value instanceof AString)) return false;
			label = value == null ? Strings.create("original") : (AString) value;
			return true;
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
				AMap<AString, ACell> meta, ACell input) { throw new AssertionError("not a job"); }
	}

	private static AMap<AString, ACell> reference(String adapter, AString implementation) {
		AMap<AString, ACell> ref = Maps.of(AuthMethods.ADAPTER, adapter);
		return implementation == null ? ref : ref.assoc(AuthMethods.IMPLEMENTATION, implementation);
	}
	private static AMap<AString, ACell> config(ACell methods) {
		return Maps.of(Config.AUTH, Maps.of(AuthMethods.METHODS, methods));
	}
	private static AMap<AString, ACell> fixtureBindings() {
		return Maps.of(FIRST, reference("auth-fixture", FIRST), SECOND, reference("auth-fixture", SECOND));
	}
	private static AuthResult call(VenueAuthenticator service, AString method) {
		return service.verify(method, Maps.of("answer", 42));
	}

	@Test void customAdapterContributesTwoExplicitImplementations() {
		Engine engine = Engine.createTemp(config(fixtureBindings()));
		try {
			engine.registerAdapter(new Fixture(true));
			VenueAuthenticator service = new VenueAuthenticator(engine);
			service.validateMethods();
			assertEquals(FIRST, call(service, FIRST).authentication().method());
			assertEquals(SECOND, call(service, SECOND).authentication().method());
			assertEquals(ALICE, call(service, SECOND).userDID());
			assertNull(call(service, FIRST).sessionId());
			assertEquals(REFUSED, assertThrows(AuthException.class,
				() -> service.verify(FIRST, Maps.empty())).getMessage());
			assertEquals(AuthMethods.NOT_SELECTED + "other", assertThrows(AuthException.class,
				() -> call(service, Strings.create("other"))).getMessage());
		} finally { engine.close(); }
	}

	@Test void soleImplementationNeedsNoSelectorAndCannotSpoofMethod() {
		Engine engine = Engine.createTemp(config(Maps.of(FIRST, reference("auth-fixture", null))));
		try {
			Fixture fixture = new Fixture(false);
			engine.registerAdapter(fixture);
			VenueAuthenticator service = new VenueAuthenticator(engine);
			assertEquals(FIRST, call(service, FIRST).authentication().method());
			fixture.wrongMethod = true;
			assertEquals(AuthMethods.INVALID_RESULT, assertThrows(AuthException.class,
				() -> call(service, FIRST)).getMessage());
		} finally { engine.close(); }
	}

	@Test void absentAndEmptySelectionDifferWithoutASecretFallback() {
		AKeyPair key = AKeyPair.generate();
		for (boolean explicit : new boolean[] {false, true}) {
			Engine engine = Engine.createTemp(explicit ? config(Maps.empty()) : Maps.empty());
			try {
				VenueAuthenticator service = new VenueAuthenticator(engine);
				AString did = UCAN.toDIDKey(key.getAccountKey());
				AMap<AString, ACell> claims = Maps.of("sub", did, "exp", System.currentTimeMillis()/1000 + 300);
				AString token = JWT.signPublic(claims, key);
				AString legacy = JWT.signPublic(claims.assoc(UCAN.ISS, engine.getDIDString()), engine.getKeyPair());
				if (explicit) {
					assertEquals(AuthMethods.NOT_SELECTED + VenueAuthenticator.KEY,
						assertThrows(AuthException.class, () -> service.verify(token)).getMessage());
					assertEquals(AuthMethods.LEGACY_RESTRICTED,
						assertThrows(AuthException.class, () -> service.verify(legacy)).getMessage());
				} else {
					assertEquals(did, service.verify(token).userDID());
					assertNull(service.verify(legacy).authentication().method());
				}
				engine.registerAdapter(new Fixture(false));
				assertThrows(AuthException.class, () -> call(service, FIRST), "installation alone selects nothing");
			} finally { engine.close(); }
		}
	}

	@Test void explicitBuiltInSelectionPreservesVerificationAndRejectsOtherClasses() {
		Engine engine = Engine.createTemp(config(Maps.of(VenueAuthenticator.KEY,
			reference("auth", VenueAuthenticator.KEY_JWT))));
		try {
			VenueAuthenticator service = new VenueAuthenticator(engine);
			AKeyPair key = AKeyPair.generate();
			AString did = UCAN.toDIDKey(key.getAccountKey());
			AMap<AString, ACell> claims = Maps.of("sub", did, "iss", did,
				"aud", engine.getDIDString(), "exp", System.currentTimeMillis()/1000 + 300);
			AString token = JWT.signPublic(claims, key);
			assertEquals(did, service.verify(token).userDID());
			assertThrows(AuthException.class, () -> service.verify(JWT.signPublic(
				claims.assoc(UCAN.ATT, Vectors.empty()), key)));
			assertEquals(AuthMethods.INVALID_RESULT, assertThrows(AuthException.class,
				() -> service.verify(VenueAuthenticator.KEY,
					JWT.signPublic(claims.assoc(UCAN.ATT, Vectors.empty()), key))).getMessage());
			engine.disableAdapter("auth");
			assertThrows(AuthException.class, () -> service.verify(token));
			engine.enableAdapter("auth");
			assertEquals(did, service.verify(token).userDID());
		} finally { engine.close(); }
	}

	@Test void ambiguousOrMissingImplementationCannotReplaceWorkingAdapter() {
		Engine engine = Engine.createTemp(config(Maps.of(FIRST, reference("auth-fixture", null))));
		try {
			Fixture working = new Fixture(false);
			engine.registerAdapter(working);
			assertEquals(AuthMethods.AMBIGUOUS + "auth-fixture", assertThrows(IllegalArgumentException.class,
				() -> engine.registerAdapter(new Fixture(true))).getMessage());
			assertSame(working, engine.getAdapter("auth-fixture"));
			assertEquals(ALICE, call(new VenueAuthenticator(engine), FIRST).userDID());
		} finally { engine.close(); }
		Engine missing = Engine.createTemp(config(Maps.of(FIRST, reference("auth-fixture", Strings.create("missing")))));
		try {
			assertEquals(AuthMethods.UNAVAILABLE + "auth-fixture/missing", assertThrows(IllegalArgumentException.class,
				() -> missing.registerAdapter(new Fixture(true))).getMessage());
			assertNull(missing.getAdapter("auth-fixture"));
		} finally { missing.close(); }
	}

	@Test void disabledRemovedAndReconfiguredAdaptersUseTheExistingLifecycle() {
		Engine engine = Engine.createTemp(config(fixtureBindings()));
		try {
			engine.registerAdapter(new Fixture(true));
			VenueAuthenticator service = new VenueAuthenticator(engine);
			long version = engine.adapterRegistryVersion();
			assertThrows(IllegalArgumentException.class,
				() -> engine.configureAdapter("auth-fixture", Maps.of("label", 123)));
			assertEquals(version, engine.adapterRegistryVersion());
			assertEquals(Strings.create("original"), call(service, FIRST).authentication().details().get(Strings.create("label")));
			engine.configureAdapter("auth-fixture", Maps.of("label", "changed"));
			assertEquals(Strings.create("changed"), call(service, FIRST).authentication().details().get(Strings.create("label")));
			engine.disableAdapter("auth-fixture");
			assertThrows(AuthException.class, () -> call(service, FIRST));
			engine.enableAdapter("auth-fixture");
			assertEquals(ALICE, call(service, FIRST).userDID());
			engine.removeAdapter("auth-fixture");
			assertThrows(AuthException.class, () -> call(service, FIRST));
		} finally { engine.close(); }
	}

	@Test void verificationDoesNotHoldLifecycleLockAndRejectsStaleCompletion() throws Exception {
		for (String change : List.of("cycle", "configure", "replace", "remove")) {
			Engine engine = Engine.createTemp(config(fixtureBindings()));
			Fixture fixture = new Fixture(true);
			fixture.entered = new CountDownLatch(1);
			fixture.release = new CountDownLatch(1);
			try {
				engine.registerAdapter(fixture);
				VenueAuthenticator service = new VenueAuthenticator(engine);
				CompletableFuture<AuthResult> pending = CompletableFuture.supplyAsync(() -> call(service, FIRST));
				assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
				CompletableFuture.runAsync(() -> {
					switch (change) {
						case "cycle" -> { engine.disableAdapter("auth-fixture"); engine.enableAdapter("auth-fixture"); }
						case "configure" -> engine.configureAdapter("auth-fixture", Maps.of("label", "changed"));
						case "replace" -> engine.registerAdapter(new Fixture(true));
						case "remove" -> engine.removeAdapter("auth-fixture");
					}
				}).get(5, TimeUnit.SECONDS);
				fixture.release.countDown();
				ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
				assertInstanceOf(AuthException.class, failure.getCause());
				assertEquals(AuthMethods.CHANGED, failure.getCause().getMessage());
			} finally { fixture.release.countDown(); engine.close(); }
		}
	}

	@Test void malformedBindingsFailEvenWithoutStrictConfig() {
		for (ACell methods : new ACell[] {null, Strings.create("wrong"), Maps.of("", Maps.empty()),
			Maps.of(FIRST, Maps.of("adapter", "")), Maps.of(FIRST, Maps.of("adapter", "auth", "enabled", true)),
			Maps.of(FIRST, Maps.of("adapter", "auth", "implementation", null))}) {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new Config(config(methods)));
			assertTrue(failure.getMessage().startsWith(AuthMethods.INVALID_CONFIG));
		}
	}

	@Test void startupResolvesAfterHostHookAndBeforeServing() {
		AMap<AString, ACell> cfg = config(fixtureBindings()).assoc(Config.STORE, Strings.create("temp"))
			.assoc(Strings.intern("port"), convex.core.data.prim.CVMLong.ZERO);
		assertThrows(IllegalArgumentException.class, () -> VenueServer.launch(cfg));
		VenueServer server = VenueServer.launch(cfg, List.of(), engine -> {
			engine.registerAdapter(new Fixture(true));
			return CompletableFuture.completedFuture(null);
		});
		try { assertEquals(ALICE, call(server.authenticator(), SECOND).userDID()); }
		finally { server.close(); }
	}

	@Test void hostCanReplaceDefaultAdapterBeforeReferenceValidation() {
		AMap<AString, ACell> cfg = config(Maps.of(FIRST, reference("auth", FIRST)))
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Strings.intern("port"), convex.core.data.prim.CVMLong.ZERO);
		VenueServer server = VenueServer.launch(cfg, List.of(), engine -> {
			engine.registerAdapter(new Fixture(false) {
				@Override public String getName() { return "auth"; }
			});
			return CompletableFuture.completedFuture(null);
		});
		try { assertEquals(ALICE, call(server.authenticator(), FIRST).userDID()); }
		finally { server.close(); }
	}
}
