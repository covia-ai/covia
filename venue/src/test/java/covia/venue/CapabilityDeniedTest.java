package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import convex.auth.ucan.Capability;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.exception.CapabilityDeniedException;

/**
 * A capability denial carries, as typed values, the capability the request
 * lacked and the authority that was refused.
 */
class CapabilityDeniedTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString READ = Capability.CRUD_READ;
	private static final AString WRITE = Capability.CRUD_WRITE;

	private static AString path(String path) {
		return Strings.create(path);
	}

	/** {@code did}, able only to read under {@code with}. */
	private static RequestContext reading(AString did, String with) {
		return RequestContext.of(did).withCaps(Vectors.of(Capability.create(path(with), READ)));
	}

	private static void assertDenied(AString resource, AString ability, AString authority, Executable check) {
		CapabilityDeniedException denied = assertThrows(CapabilityDeniedException.class, check);
		assertEquals(resource, denied.getResource());
		assertEquals(ability, denied.getAbility());
		assertEquals(authority, denied.getAuthority());
	}

	@Test void aBarePathIsReportedAsItResolves() {
		AString alice = TestEngine.uniqueDID("DeniedScope");
		RequestContext ctx = reading(alice, "w/shared/");
		assertDenied(path(alice + "/w/private"), WRITE, alice,
			() -> engine.requireAuthority(ctx, path("w/private"), WRITE));
	}

	@Test void anotherUsersResourceIsDeniedAlike() {
		AString alice = TestEngine.uniqueDID("DeniedAlice");
		AString bob = TestEngine.uniqueDID("DeniedBob");
		engine.getVenueState().users().ensure(bob);
		AString bobs = path(bob + "/w/x");
		assertDenied(bobs, READ, alice,
			() -> engine.requireAuthority(RequestContext.of(alice), bobs, READ));
	}

	@Test void aSeparateAuthorityIsTheOneRefused() {
		AString alice = TestEngine.uniqueDID("DeniedOwner");
		AString bob = TestEngine.uniqueDID("DeniedController");
		engine.getVenueState().users().ensure(alice);
		RequestContext ctx = RequestContext.ofAgent(alice, Strings.intern("helper")).withAuthorityOf(RequestContext.of(bob));
		assertDenied(path(alice + "/w/notes"), READ, bob,
			() -> engine.requireAuthority(ctx, path("w/notes"), READ));
	}

	@Test void invokingAnOperationNamesTheOperation() {
		AString alice = TestEngine.uniqueDID("DeniedInvoke");
		RequestContext ctx = reading(alice, "w/shared/");
		assertDenied(path("v/test/ops/echo"), Abilities.INVOKE, alice,
			() -> engine.requireInvoke(ctx, path("v/test/ops/echo")));
	}

	@Test void theContextsOwnChecksCarryItToo() {
		AString alice = TestEngine.uniqueDID("DeniedContext");
		assertDenied(path(alice + "/w/private"), WRITE, alice,
			() -> reading(alice, "w/shared/").requireCapability(path("w/private"), WRITE));
		assertDenied(path(alice + "/w/x"), WRITE, alice,
			() -> RequestContext.of(alice).requireExplicitCapability(path("w/x"), WRITE));
	}

	@Test void aRequestWithNoContextNamesNoAuthority() {
		CapabilityDeniedException denied = assertThrows(CapabilityDeniedException.class,
			() -> engine.requireAuthority(null, path("w/x"), READ));
		assertEquals(path("w/x"), denied.getResource());
		assertNull(denied.getAuthority());
	}
}
