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
 * {@code null}-caps fast path (own vs cross-user), config-grant coverage, and the
 * additive routing. Proof-backed cross-user access is exercised by
 * AccessControlTest / UCANTest.
 */
public class AuthorityCoversTest {

	private static final Engine ENGINE = TestEngine.ENGINE;

	private static RequestContext withCaps(RequestContext ctx, ACell... grants) {
		return ctx.withCaps(Vectors.of(grants));
	}

	@Test
	public void testNullCapsOwnAllowed() {
		AString alice = TestEngine.uniqueDID("own-alice");
		RequestContext ctx = RequestContext.of(alice);   // null caps → unrestricted own-authority
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("w/x"), Capability.CRUD_WRITE));
		assertTrue(ENGINE.authorityCovers(ctx, Strings.create("o/y"), Capability.CRUD_READ));
	}

	@Test
	public void testNullCapsCrossUserDeniedWithoutProof() {
		AString alice = TestEngine.uniqueDID("xu-alice");
		AString bob = TestEngine.uniqueDID("xu-bob");
		RequestContext ctx = RequestContext.of(alice);   // null caps, no proofs
		AString bobResource = Strings.create(bob + "/w/x");
		// the fast path must NOT reach another user's namespace
		assertFalse(ENGINE.authorityCovers(ctx, bobResource, Capability.CRUD_WRITE));
		assertFalse(ENGINE.authorityCovers(ctx, bobResource, Capability.CRUD_READ));
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
	public void testAnonymousCrossUserDenied() {
		AString bob = TestEngine.uniqueDID("anon-bob");
		assertFalse(ENGINE.authorityCovers(RequestContext.ANONYMOUS,
			Strings.create(bob + "/w/x"), Capability.CRUD_READ));
	}
}
