package covia.venue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.exception.AuthException;

/**
 * Unit coverage for the unified {@code Engine.authorityCovers} seam: the
 * {@code null}-caps fast path (unrestricted scope), config-grant coverage, the
 * additive routing (a config-capped caller needs a proof for a resource its
 * config does not grant), the bound of every scope to the request's own
 * namespaces, and invoking as a choice of operation. Proof-backed cross-user
 * access is exercised end-to-end by AccessControlTest / CoviaAdapterTest /
 * DLFSCrossUserTest.
 */
public class AuthorityCoversTest {

	private static final Engine ENGINE = TestEngine.ENGINE;

	private static RequestContext withCaps(RequestContext ctx, ACell... grants) {
		return ctx.withCaps(Vectors.create(grants));
	}

	@Test
	public void testNullCapsUnrestricted() {
		AString alice = TestEngine.uniqueDID("own-alice");
		RequestContext ctx = RequestContext.of(alice);   // null caps → unrestricted scope
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("w/x"), Capability.CRUD_WRITE));
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("o/y"), Capability.CRUD_READ));
		// content-addressed asset read — unrestricted at the scope (the adapter's
		// resolver, not the scope, is the gate for any cross-user reach)
		assertTrue(ENGINE.authorityCovers(ctx,
			Strings.create(ENGINE.getDIDString() + "/a/00"), Capability.CRUD_READ));
	}

	@Test
	public void testConfigCapsBoundOwnAccess() {
		AString alice = TestEngine.uniqueDID("cap-alice");
		RequestContext ctx = withCaps(RequestContext.of(alice),
			Capability.create(Strings.create("w/reports"), Capability.CRUD_READ));
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("w/reports"), Capability.CRUD_READ));
		// same path, different ability — not granted
		assertFalse(ENGINE.authorityCovers(ctx, Strings.create("w/reports"), Capability.CRUD_WRITE));
		// different path — not granted
		assertFalse(ENGINE.authorityCovers(ctx, Strings.create("w/secrets"), Capability.CRUD_READ));
	}

	@Test
	public void testConfigCapsCrossUserNeedsProof() {
		AString alice = TestEngine.uniqueDID("xu-alice");
		AString bob = TestEngine.uniqueDID("xu-bob");
		RequestContext ctx = withCaps(RequestContext.of(alice),
			Capability.create(Strings.create("w/reports"), Capability.CRUD_READ));
		// config-capped, cross-user resource, no proof → denied (scope denies, no proof)
		assertFalse(ENGINE.authorityCovers(ctx, Strings.create(bob + "/w/x"), Capability.CRUD_READ));
	}

	/** A scope, unrestricted or not, reaches only the request's own namespaces:
	 *  another identity the venue hosts is reached only by what the request holds. */
	@Test
	public void testScopeStopsAtAnotherHostedIdentity() {
		AString alice = TestEngine.uniqueDID("bound-alice");
		AString bob = TestEngine.uniqueDID("bound-bob");
		ENGINE.getVenueState().users().ensure(bob);
		AString bobs = Strings.create(bob + "/w/x");

		RequestContext unrestricted = RequestContext.of(alice);
		assertFalse(ENGINE.authorityCovers(unrestricted, bobs, Capability.CRUD_READ));
		assertThrows(AuthException.class, () -> ENGINE.requireAuthority(unrestricted, bobs, Capability.CRUD_READ));
		assertTrue(ENGINE.authorityCovers(unrestricted, Strings.create(alice + "/w/x"), Capability.CRUD_WRITE));

		// A scope naming bob's resources gives nothing there
		RequestContext naming = withCaps(RequestContext.of(alice),
			Capability.create(Strings.create(bob + "/w/"), Capability.CRUD_READ));
		assertFalse(ENGINE.authorityCovers(naming, bobs, Capability.CRUD_READ));
	}

	/** A resource the venue does not host stays within the request's own scope. */
	@Test
	public void testUnhostedResourceIsWithinScope() {
		RequestContext alice = RequestContext.of(TestEngine.uniqueDID("unhosted-alice"));
		AString remote = Strings.create(TestEngine.uniqueDID("unhosted-remote") + "/w/x");
		assertTrue(ENGINE.authorityCovers(alice, remote, Capability.CRUD_READ));
	}

	/** Choosing an operation is not access to its publisher's namespace: the
	 *  scope, or a presented grant, covers the reference whoever publishes it. */
	@Test
	public void testInvokeIsAChoiceOfOperation() {
		AString alice = TestEngine.uniqueDID("invoke-alice");
		AString bob = TestEngine.uniqueDID("invoke-bob");
		ENGINE.getVenueState().users().ensure(bob);
		AString bobsOp = Strings.create(bob + "/o/op");

		assertDoesNotThrow(() -> ENGINE.requireInvoke(RequestContext.of(alice), bobsOp));
		RequestContext venueOps = withCaps(RequestContext.of(alice),
			Capability.create(Strings.create("v/ops/"), Abilities.INVOKE));
		assertThrows(AuthException.class, () -> ENGINE.requireInvoke(venueOps, bobsOp));
		RequestContext bobsOps = withCaps(RequestContext.of(alice),
			Capability.create(Strings.create(bob + "/o/"), Abilities.INVOKE));
		assertDoesNotThrow(() -> ENGINE.requireInvoke(bobsOps, bobsOp));
	}
}
