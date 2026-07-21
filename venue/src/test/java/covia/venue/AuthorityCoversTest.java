package covia.venue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/**
 * Unit coverage for the unified {@code Engine.authorityCovers} seam: the
 * {@code null}-caps fast path (unrestricted ceiling), config-grant coverage, and
 * the additive routing (a config-capped caller needs a proof for a resource its
 * config does not grant). Proof-backed cross-user access is exercised end-to-end
 * by AccessControlTest / CoviaAdapterTest / DLFSCrossUserTest.
 */
public class AuthorityCoversTest {

	private static final Engine ENGINE = TestEngine.ENGINE;

	private static RequestContext withCaps(RequestContext ctx, ACell... grants) {
		return ctx.withCaps(Vectors.of(grants));
	}

	@Test
	public void testNullCapsUnrestricted() {
		AString alice = TestEngine.uniqueDID("own-alice");
		RequestContext ctx = RequestContext.of(alice);   // null caps → unrestricted ceiling
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("w/x"), Capability.CRUD_WRITE));
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("o/y"), Capability.CRUD_READ));
		// content-addressed asset read — unrestricted at the ceiling (the adapter's
		// resolver, not the ceiling, is the gate for any cross-user reach)
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
		// config-capped, cross-user resource, no proof → denied (ceiling denies, no proof)
		assertFalse(ENGINE.authorityCovers(ctx, Strings.create(bob + "/w/x"), Capability.CRUD_READ));
	}
}
