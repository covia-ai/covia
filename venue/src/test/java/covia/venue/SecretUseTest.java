package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.HTTPAdapter;
import covia.api.Abilities;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.lattice.CapabilityChecker;
import covia.lattice.CapabilityGate;

/**
 * Using a secret is an action on {@code <owner>/s/NAME} requiring
 * {@code secret/use}: the owner's by the implicit grant, anyone else's under a
 * grant, which may be bound to a destination url ({@code nb.url}). A secret
 * goes only where its owner pointed it.
 */
public class SecretUseTest {
	private static Engine engine;
	private static byte[] encKey;

	@BeforeAll
	static void start() {
		// A hostname lets the venue host managed (did:web) users, for whom it signs grants.
		engine = Engine.createTemp(Maps.of(Config.HOSTNAME, Strings.create("secrets.test.covia.example")));
		Engine.addDemoAssets(engine);
		((HTTPAdapter) engine.getAdapter("http")).addAllowedHost("localhost");
		encKey = SecretStore.deriveKey(engine.getKeyPair());
	}

	@AfterAll
	static void stop() {
		engine.close();
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}

	private static AVector<ACell> useOf(AString resource, String url) {
		return Vectors.of(Capability.create(resource, Abilities.SECRET_USE,
			Maps.of(Strings.create("url"), Strings.create(url))));
	}

	@Test
	public void testUrlCaveatCoverage() {
		assertTrue(CapabilityChecker.urlCovers("https://safe.com/api", "https://safe.com/api"));
		assertTrue(CapabilityChecker.urlCovers("https://safe.com/api", "https://safe.com/api/v1/chat?x=1"));
		assertTrue(CapabilityChecker.urlCovers("https://safe.com/api", "https://SAFE.com:443/api"));
		assertTrue(CapabilityChecker.urlCovers("https://safe.com/", "https://safe.com/anything/at/all"));
		assertTrue(CapabilityChecker.urlCovers("https://safe.com", "https://safe.com/anything"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "https://safe.com/apix"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "http://safe.com/api"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "https://safe.com:8443/api"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "https://evil.com/api"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "https://safe.com.evil.com/api"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "https://user@safe.com@evil.com/api"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api", "not a url"));
		assertFalse(CapabilityChecker.urlCovers("not a url", "https://safe.com/api"));
		// A trailing slash means "everything under", the bare path included.
		assertTrue(CapabilityChecker.urlCovers("https://safe.com/api/", "https://safe.com/api"));
		assertTrue(CapabilityChecker.urlCovers("https://safe.com/api/", "https://safe.com/api/x"));
		assertFalse(CapabilityChecker.urlCovers("https://safe.com/api/", "https://safe.com/apix"));
	}

	@Test
	public void testUrlCaveatInAGrantScope() {
		AString alice = TestEngine.uniqueDID("url-caveat");
		AString resource = Strings.create("s/FOO");
		AString use = Abilities.SECRET_USE;
		AVector<ACell> caps = Vectors.of(Maps.of(
			"with", Strings.create("s/FOO"), "can", use,
			"nb", Maps.of("url", Strings.create("https://safe.com/api"))));

		assertNull(CapabilityChecker.allows(caps, resource, use, alice, null, null, null,
			Strings.create("https://safe.com/api/v1")));
		String denied = CapabilityChecker.allows(caps, resource, use, alice, null, null, null,
			Strings.create("https://evil.com/api"));
		assertNotNull(denied);
		assertTrue(denied.contains("url caveat"), denied);
		assertNotNull(CapabilityChecker.allows(caps, resource, use, alice, null, null, null, null),
			"a use with no destination never satisfies a url caveat");

		// An unknown caveat fails closed.
		AVector<ACell> unknown = Vectors.of(Maps.of(
			"with", Strings.create("s/FOO"), "can", use,
			"nb", Maps.of("host", Strings.create("safe.com"))));
		assertNotNull(CapabilityChecker.allows(unknown, resource, use, alice, null, null, null,
			Strings.create("https://safe.com/api")));

		// A gate and a url together must both hold.
		AVector<ACell> both = Vectors.of(Maps.of(
			"with", Strings.create("s/FOO"), "can", use,
			"nb", Maps.of("url", Strings.create("https://safe.com/api"),
				"gate", Strings.create("v/test/ops/gate"))));
		CapabilityGate deny = (g, op, in, c) -> "no";
		CapabilityGate allow = (g, op, in, c) -> null;
		AString covered = Strings.create("https://safe.com/api");
		assertNotNull(CapabilityChecker.allows(both, resource, use, alice, null, null, deny, covered));
		assertNull(CapabilityChecker.allows(both, resource, use, alice, null, null, allow, covered));
		assertNotNull(CapabilityChecker.allows(both, resource, use, alice, null, null, allow,
			Strings.create("https://evil.com/api")));
	}

	/** "I grant s/FOO to Bob for 60 minutes for API calls to https://safe.com/api." */
	@Test
	public void testGrantedSecretGoesOnlyWhereItsOwnerPointedIt() {
		AKeyPair aliceKP = AKeyPair.generate();
		AKeyPair bobKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());
		engine.getVenueState().users().ensure(aliceDID).secrets().store("FOO", "alice-foo", encKey);
		AString resource = Strings.create(aliceDID + "/s/FOO");
		String ref = resource.toString();
		String api = "https://safe.com/api/v1/chat";

		// Alice uses her own secret anywhere; Bob has nothing without a grant.
		assertEquals("alice-foo", engine.resolveSecret("s/FOO", RequestContext.of(aliceDID), "https://anywhere.example/"));
		assertEquals("alice-foo", engine.resolveSecret(ref, RequestContext.of(aliceDID)));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, RequestContext.of(bobDID), api));

		long exp = now() + 3600;
		UCAN grant = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			useOf(resource, "https://safe.com/api"), Vectors.empty());
		RequestContext bob = RequestContext.of(bobDID).withProofs(Vectors.of(grant.toMap()));
		assertEquals("alice-foo", engine.resolveSecret(ref, bob, api));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, bob, "https://evil.example/api"));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, bob, "https://safe.com/apix"));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, bob),
			"a use with no destination never satisfies the url caveat");

		// Expired: refused.
		UCAN expired = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), now() - 1,
			useOf(resource, "https://safe.com/api"), Vectors.empty());
		RequestContext late = RequestContext.of(bobDID).withProofs(Vectors.of(expired.toMap()));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, late, api));

		// Re-delegation cannot shed the caveat: every link of Carol's chain must cover the use.
		AKeyPair carolKP = AKeyPair.generate();
		AString carolDID = UCAN.toDIDKey(carolKP.getAccountKey());
		UCAN child = UCAN.create(bobKP, UCAN.fromDIDKey(carolDID), exp,
			Vectors.of(Capability.create(resource, Abilities.SECRET_USE)), Vectors.of(grant.toMap()));
		RequestContext carol = RequestContext.of(carolDID).withProofs(Vectors.of(child.toMap()));
		assertEquals("alice-foo", engine.resolveSecret(ref, carol, api));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, carol, "https://evil.example/api"));
	}

	@Test
	public void testPublicStoreNeedsAnExplicitGrant() {
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		engine.getVenueState().users().ensure(publicDID).secrets().store("OPERATOR_KEY", "operator-key", encKey);
		AString aliceDID = TestEngine.uniqueDID("public-secret");
		String provider = "https://api.anthropic.com/v1/messages";

		// Under the default read-only public scope not even the anonymous caller
		// may use the operator's key, and an authenticated caller never reaches
		// the public store without a grant.
		RequestContext anonymous = RequestContext.of(publicDID)
			.withCaps(CapabilityChecker.readOnlyScope(publicDID));
		assertThrows(AuthException.class, () -> engine.resolveSecret("s/OPERATOR_KEY", anonymous, provider));
		assertThrows(AuthException.class,
			() -> engine.resolveSecret(publicDID + "/s/OPERATOR_KEY", RequestContext.of(aliceDID), provider));

		// auth.public.caps grants its use, bound to the provider: everyone may
		// use it there and nowhere else.
		Engine shared = Engine.createTemp(Maps.of(Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.CAPS,
			Vectors.of(Maps.of("with", Strings.create("s/OPERATOR_KEY"), "can", Abilities.SECRET_USE,
				"nb", Maps.of("url", Strings.create("https://api.anthropic.com/"))))))));
		try {
			AString sharedPublic = Strings.create(shared.getDIDString() + ":public");
			shared.getVenueState().users().ensure(sharedPublic).secrets()
				.store("OPERATOR_KEY", "operator-key", SecretStore.deriveKey(shared.getKeyPair()));
			String ref = sharedPublic + "/s/OPERATOR_KEY";
			assertEquals("operator-key", shared.resolveSecret(ref, RequestContext.of(aliceDID), provider));
			assertThrows(AuthException.class,
				() -> shared.resolveSecret(ref, RequestContext.of(aliceDID), "https://evil.example/v1/"));
			assertThrows(AuthException.class, () -> shared.resolveSecret(ref, RequestContext.of(aliceDID)));
		} finally {
			shared.close();
		}
	}

	@Test
	public void testVenueSecretsIncludeItsEnvironment() {
		// The venue principal's secrets are its configured store and its process
		// environment; PATH exists in every test environment.
		String path = System.getenv("PATH");
		assertNotNull(path);
		assertEquals(path, engine.resolveSecret("s/PATH", engine.venueContext()));
		assertThrows(AuthException.class,
			() -> engine.resolveSecret(engine.getDIDString() + "/s/PATH", RequestContext.of(TestEngine.uniqueDID("env"))));
	}

	@Test
	public void testHttpBearerUsesAGrantedSecretOnlyAtItsUrl() throws Exception {
		AKeyPair aliceKP = AKeyPair.generate();
		AKeyPair bobKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());
		engine.getVenueState().users().ensure(aliceDID).secrets().store("TOKEN", "alice-token", encKey);
		engine.getVenueState().users().ensure(bobDID);   // a registered caller, no auto-create on this venue

		AtomicReference<String> seen = new AtomicReference<>();
		HttpServer echo = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		echo.createContext("/", exchange -> {
			String bearer = exchange.getRequestHeaders().getFirst("Authorization");
			seen.set(bearer != null ? bearer : exchange.getRequestHeaders().getFirst("X-Api-Key"));
			byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		echo.start();
		try {
			String base = "http://localhost:" + echo.getAddress().getPort();
			String ref = aliceDID + "/s/TOKEN";
			UCAN grant = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), now() + 3600,
				useOf(Strings.create(ref), base + "/api"), Vectors.empty());
			RequestContext bob = RequestContext.of(bobDID).withProofs(Vectors.of(grant.toMap()));

			Job sent = engine.jobs().invokeOperation("v/ops/http/get",
				Maps.of("url", base + "/api/items", "bearerSecret", ref), bob);
			sent.awaitResult(10_000);
			assertEquals("Bearer alice-token", seen.get());

			// The same grant serves a secret header; the same bound applies.
			seen.set(null);
			engine.jobs().invokeOperation("v/ops/http/get",
				Maps.of("url", base + "/api/more", "secretHeaders", Maps.of("X-Api-Key", ref)), bob)
				.awaitResult(10_000);
			assertEquals("alice-token", seen.get());

			seen.set(null);
			Job refused = engine.jobs().invokeOperation("v/ops/http/get",
				Maps.of("url", base + "/other", "bearerSecret", ref), bob);
			try {
				refused.awaitResult(10_000);
			} catch (RuntimeException expected) {
				// the job failed, which is the point
			}
			assertFalse(refused.isComplete(), "the token must not go where the grant does not point");
			assertTrue(refused.getErrorMessage().contains("secret/use"), refused.getErrorMessage());
			assertNull(seen.get());
		} finally {
			echo.stop(0);
		}
	}

	/** The documented granting path: {@code ucan:issue} carries {@code nb.url} through. */
	@Test
	public void testIssuedGrantCarriesItsUrl() {
		// The venue signs for its managed (did:web) users.
		AString lenderDID = engine.managedUserDID(Strings.create("secret-lender"));
		engine.getVenueState().users().ensure(lenderDID).secrets().store("FOO", "lender-foo", encKey);
		AString bobDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());

		Job issued = engine.jobs().invokeOperation("v/ops/ucan/issue", Maps.of(
			UCAN.AUD, bobDID,
			UCAN.ATT, Vectors.of(Capability.create(Strings.create("s/FOO"), Abilities.SECRET_USE,
				Maps.of(Strings.create("url"), Strings.create("https://safe.com/api")))),
			UCAN.EXP, convex.core.data.prim.CVMLong.create(now() + 3600)),
			RequestContext.of(lenderDID));
		AString jwt = convex.core.lang.RT.ensureString(convex.core.lang.RT.getIn(issued.awaitResult(5000), "token"));
		UCAN token = UCAN.fromJWT(jwt);
		RequestContext bob = RequestContext.of(bobDID).withProofs(Vectors.of(token.toMap()));

		String ref = lenderDID + "/s/FOO";
		assertEquals("lender-foo", engine.resolveSecret(ref, bob, "https://safe.com/api/v1"));
		assertThrows(AuthException.class, () -> engine.resolveSecret(ref, bob, "https://evil.example/api"));
	}

	@Test
	public void testGrantMustMatchAudienceAbilityAndResource() {
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AString bobDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString carolDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		User alice = engine.getVenueState().users().ensure(aliceDID);
		alice.secrets().store("FOO", "alice-foo", encKey);
		alice.secrets().store("BAR", "alice-bar", encKey);
		AString foo = Strings.create(aliceDID + "/s/FOO");
		String api = "https://safe.com/api";
		long exp = now() + 3600;

		// Carol's grant presented by Bob: refused.
		UCAN forCarol = UCAN.create(aliceKP, UCAN.fromDIDKey(carolDID), exp, useOf(foo, api), Vectors.empty());
		assertThrows(AuthException.class, () -> engine.resolveSecret(foo.toString(),
			RequestContext.of(bobDID).withProofs(Vectors.of(forCarol.toMap())), api));

		// A read grant on the secret is not use, and a use grant on BAR is not FOO.
		UCAN read = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(foo, Capability.CRUD_READ)), Vectors.empty());
		assertThrows(AuthException.class, () -> engine.resolveSecret(foo.toString(),
			RequestContext.of(bobDID).withProofs(Vectors.of(read.toMap())), api));
		UCAN bar = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			useOf(Strings.create(aliceDID + "/s/BAR"), api), Vectors.empty());
		RequestContext bobBar = RequestContext.of(bobDID).withProofs(Vectors.of(bar.toMap()));
		assertThrows(AuthException.class, () -> engine.resolveSecret(foo.toString(), bobBar, api));
		assertEquals("alice-bar", engine.resolveSecret(aliceDID + "/s/BAR", bobBar, api));

		// An unconditional use grant goes anywhere, a use with no destination included.
		UCAN any = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(foo, Abilities.SECRET_USE)), Vectors.empty());
		RequestContext bobAny = RequestContext.of(bobDID).withProofs(Vectors.of(any.toMap()));
		assertEquals("alice-foo", engine.resolveSecret(foo.toString(), bobAny, "https://anywhere.example/"));
		assertEquals("alice-foo", engine.resolveSecret(foo.toString(), bobAny));

		// A grant over the whole store covers the secret, under its url.
		UCAN all = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			useOf(Strings.create(aliceDID + "/s/"), api), Vectors.empty());
		RequestContext bobAll = RequestContext.of(bobDID).withProofs(Vectors.of(all.toMap()));
		assertEquals("alice-foo", engine.resolveSecret(foo.toString(), bobAll, api + "/x"));
		assertThrows(AuthException.class, () -> engine.resolveSecret(foo.toString(), bobAll, "https://evil.example/"));
	}

	@Test
	public void testReferenceShapesAreStrict() {
		AString aliceDID = TestEngine.uniqueDID("shapes");
		engine.getVenueState().users().ensure(aliceDID).secrets().store("FOO", "alice-foo", encKey);
		RequestContext alice = RequestContext.of(aliceDID);
		assertEquals("alice-foo", engine.resolveSecret("s/FOO", alice));
		assertEquals("alice-foo", engine.resolveSecret("/s/FOO", alice));
		assertEquals("alice-foo", engine.resolveSecret("FOO", alice));
		assertEquals("alice-foo", engine.resolveSecret(aliceDID + "/s/FOO", alice));

		// A DID-qualified reference names its owner and one secret, nothing else.
		assertNull(engine.resolveSecret(aliceDID + "/w/x/s/FOO", alice));
		assertNull(engine.resolveSecret("did:key:zNobody", alice));
		assertNull(engine.resolveSecret("s/", alice));
		assertNull(engine.resolveSecret("s/FOO/BAR", alice));
		assertNull(engine.resolveSecret("s/FOO", RequestContext.ANONYMOUS));

		assertTrue(Engine.isSecretRef("s/FOO"));
		assertTrue(Engine.isSecretRef("/s/FOO"));
		assertTrue(Engine.isSecretRef("did:key:zA/s/FOO"));
		assertFalse(Engine.isSecretRef("sk-literal"));
		assertFalse(Engine.isSecretRef("did:key:zA"));
		assertFalse(Engine.isSecretRef(null));
	}
}
