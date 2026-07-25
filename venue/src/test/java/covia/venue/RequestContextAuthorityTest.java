package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.Authority;
import covia.lattice.CapabilityChecker;

/** Covers RequestContext wrapping an Authority (getAuthority / ofAuthority) and
 *  the credential getters reading through to it. */
public class RequestContextAuthorityTest {

	private static final AString ALICE = Strings.create("did:key:zAlice");
	private static final ACell PROOF = Strings.create("proof");
	private static final ACell CAP = Strings.create("cap");

	@Test
	public void testWrapsPresentedProofs() {
		// of(did, proofs) attaches PRESENTED proofs; the caller stays unrestricted.
		RequestContext ctx = RequestContext.of(ALICE, Vectors.of(PROOF));
		Authority a = ctx.getAuthority();
		assertEquals(ALICE, a.getDID());
		assertEquals(1, a.getProofs().count());
		assertNull(a.getGrants());   // no explicit scope = unrestricted
		// the credential getters read through to the wrapped Authority
		assertEquals(ALICE, ctx.getCallerDID());
		assertEquals(1, ctx.getProofs().count());
		assertNull(ctx.getCaps());
	}

	@Test
	public void testOfAuthorityRoundtrip() {
		// A scoped Authority (CAP in its grant scope) survives the wrap intact.
		Authority a = Authority.of(ALICE, Vectors.of(CAP));
		RequestContext ctx = RequestContext.ofAuthority(a);
		assertEquals(ALICE, ctx.getCallerDID());
		assertSame(a, ctx.getAuthority());
		assertEquals(1, ctx.getCaps().count());   // getCaps reads the scope
	}

	@Test
	public void testWithCapsSetsScopeOnAuthority() {
		// withCaps replaces the wrapped Authority's grant scope.
		RequestContext ctx = RequestContext.of(ALICE).withCaps(Vectors.of(CAP));
		assertEquals(1, ctx.getCaps().count());
		assertEquals(1, ctx.getAuthority().getGrants().count());
		// null restores unrestricted
		assertNull(ctx.withCaps(null).getCaps());
	}

	@Test
	public void testGetProofsNullContractPreserved() {
		// of(callerDID) carries no proofs — getProofs() must STILL return null
		// (the contract the call sites depend on), and the scope is unrestricted.
		RequestContext ctx = RequestContext.of(ALICE);
		assertNull(ctx.getProofs());
		assertNull(ctx.getCaps());
		assertNull(ctx.getAuthority().getGrants());
	}

	@Test
	public void testAnonymousAuthority() {
		assertTrue(RequestContext.ANONYMOUS.getAuthority().isAnonymous());
		assertSame(RequestContext.ANONYMOUS, RequestContext.ofAuthority(null));
		assertSame(RequestContext.ANONYMOUS, RequestContext.ofAuthority(Authority.ANONYMOUS));
	}

	@Test
	public void testGateContextIsConstrainedAndNonRecursive() {
		AString gateOp = Strings.create("v/test/ops/allowgate");
		ACell gatedWrite = Maps.of(
			"with", Strings.create("w/private"),
			"can", Strings.create("crud/write"),
			"nb", Maps.of("gate", gateOp));
		RequestContext original = RequestContext.of(ALICE)
			.withCaps(Vectors.of(gatedWrite))
			.withProofs(Vectors.of(PROOF));

		RequestContext gateCtx = original.forGateEvaluation(gateOp);

		assertTrue(gateCtx.isGateEvaluation());
		assertNull(gateCtx.getProofs(),
			"gate policy code must not inherit additive cross-user delegations");
		assertNull(CapabilityChecker.allows(gateCtx.getCaps(), gateOp,
			Strings.create("invoke"), ALICE, gateOp, null, null),
			"the gate receives only the synthetic right to invoke itself");
		assertNotNull(CapabilityChecker.allows(gateCtx.getCaps(),
			Strings.create("w/private"), Strings.create("crud/write"),
			ALICE, gateOp, null, null),
			"the original gated grant stays fail-closed during gate execution");
	}
}
