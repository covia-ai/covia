package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.Authority;

/** Covers the Authority view added to RequestContext (getAuthority / of(Authority)). */
public class RequestContextAuthorityTest {

	private static final AString ALICE = Strings.create("did:key:zAlice");
	private static final ACell GRANT = Strings.create("grant");

	@Test
	public void testGetAuthorityView() {
		RequestContext ctx = RequestContext.of(ALICE, Vectors.of(GRANT));
		Authority a = ctx.getAuthority();
		assertEquals(ALICE, a.getDID());
		assertEquals(1, a.getGrants().count());
		// the derived view does not disturb the existing getters
		assertEquals(ALICE, ctx.getCallerDID());
		assertEquals(1, ctx.getProofs().count());
	}

	@Test
	public void testOfAuthorityRoundtrip() {
		Authority a = Authority.of(ALICE, Vectors.of(GRANT));
		RequestContext ctx = RequestContext.ofAuthority(a);
		assertEquals(ALICE, ctx.getCallerDID());
		assertEquals(a, ctx.getAuthority());
	}

	@Test
	public void testGetProofsNullContractPreserved() {
		// of(callerDID) carries no proofs — getProofs() must STILL return null
		// (the contract the 847 call sites depend on)
		RequestContext ctx = RequestContext.of(ALICE);
		assertNull(ctx.getProofs());
		// while the Authority view normalises to an empty, never-null grant set
		assertTrue(ctx.getAuthority().getGrants().isEmpty());
	}

	@Test
	public void testAnonymousAuthority() {
		assertTrue(RequestContext.ANONYMOUS.getAuthority().isAnonymous());
		assertSame(RequestContext.ANONYMOUS, RequestContext.ofAuthority(null));
		assertSame(RequestContext.ANONYMOUS, RequestContext.ofAuthority(Authority.ANONYMOUS));
	}
}
