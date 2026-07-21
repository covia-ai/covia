package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;

public class AuthorityTest {

	private static final AString ALICE = Strings.create("did:key:zAlice");
	private static final AString BOB = Strings.create("did:key:zBob");
	private static final ACell GRANT_A = Strings.create("grant-a");
	private static final ACell GRANT_B = Strings.create("grant-b");

	@Test
	public void testIdentityOnly() {
		Authority a = Authority.of(ALICE);
		assertEquals(ALICE, a.getDID());
		assertFalse(a.isAnonymous());
		assertFalse(a.hasProofs());
		assertTrue(a.getProofs().isEmpty());
	}

	@Test
	public void testAnonymous() {
		assertTrue(Authority.ANONYMOUS.isAnonymous());
		assertNull(Authority.ANONYMOUS.getDID());
		assertFalse(Authority.ANONYMOUS.hasProofs());
		// of(null) collapses to the shared ANONYMOUS instance
		assertSame(Authority.ANONYMOUS, Authority.of(null));
	}

	@Test
	public void testNullProofsNormalised() {
		Authority a = Authority.of(ALICE, null);
		assertTrue(a.getProofs().isEmpty());
		assertFalse(a.hasProofs());
	}

	@Test
	public void testWithGrantsAreAdditiveAndImmutable() {
		Authority base = Authority.of(ALICE);
		Authority one = base.withProof(GRANT_A);
		Authority two = one.withProof(GRANT_B);

		// base is unchanged — immutability
		assertFalse(base.hasProofs());
		assertEquals(1, one.getProofs().count());
		assertEquals(2, two.getProofs().count());
		assertTrue(two.hasProofs());
		assertEquals(ALICE, two.getDID());
	}

	@Test
	public void testWithProofsBulk() {
		AVector<ACell> more = Vectors.of(GRANT_A, GRANT_B);
		Authority a = Authority.of(BOB).withProofs(more);
		assertEquals(2, a.getProofs().count());

		// null / empty augmentation is a no-op that returns an equal authority
		assertEquals(a, a.withProofs(null));
		assertEquals(a, a.withProofs(Vectors.empty()));
	}

	@Test
	public void testNullGrantIgnored() {
		Authority a = Authority.of(ALICE);
		assertSame(a, a.withProof(null));
	}

	@Test
	public void testEquality() {
		Authority a1 = Authority.of(ALICE).withProof(GRANT_A);
		Authority a2 = Authority.of(ALICE).withProof(GRANT_A);
		Authority differentGrant = Authority.of(ALICE).withProof(GRANT_B);
		Authority differentDid = Authority.of(BOB).withProof(GRANT_A);

		assertEquals(a1, a2);
		assertEquals(a1.hashCode(), a2.hashCode());
		assertNotEquals(a1, differentGrant);
		assertNotEquals(a1, differentDid);
		assertNotEquals(Authority.of(ALICE), Authority.ANONYMOUS);
	}
}
