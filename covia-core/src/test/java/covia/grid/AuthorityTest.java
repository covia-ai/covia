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
		assertFalse(a.hasGrants());
		assertTrue(a.getGrants().isEmpty());
	}

	@Test
	public void testAnonymous() {
		assertTrue(Authority.ANONYMOUS.isAnonymous());
		assertNull(Authority.ANONYMOUS.getDID());
		assertFalse(Authority.ANONYMOUS.hasGrants());
		// of(null) collapses to the shared ANONYMOUS instance
		assertSame(Authority.ANONYMOUS, Authority.of(null));
	}

	@Test
	public void testNullProofsNormalised() {
		Authority a = Authority.of(ALICE, null);
		assertTrue(a.getGrants().isEmpty());
		assertFalse(a.hasGrants());
	}

	@Test
	public void testWithGrantsAreAdditiveAndImmutable() {
		Authority base = Authority.of(ALICE);
		Authority one = base.withGrant(GRANT_A);
		Authority two = one.withGrant(GRANT_B);

		// base is unchanged — immutability
		assertFalse(base.hasGrants());
		assertEquals(1, one.getGrants().count());
		assertEquals(2, two.getGrants().count());
		assertTrue(two.hasGrants());
		assertEquals(ALICE, two.getDID());
	}

	@Test
	public void testWithProofsBulk() {
		AVector<ACell> more = Vectors.of(GRANT_A, GRANT_B);
		Authority a = Authority.of(BOB).withGrants(more);
		assertEquals(2, a.getGrants().count());

		// null / empty augmentation is a no-op that returns an equal authority
		assertEquals(a, a.withGrants(null));
		assertEquals(a, a.withGrants(Vectors.empty()));
	}

	@Test
	public void testNullGrantIgnored() {
		Authority a = Authority.of(ALICE);
		assertSame(a, a.withGrant(null));
	}

	@Test
	public void testEquality() {
		Authority a1 = Authority.of(ALICE).withGrant(GRANT_A);
		Authority a2 = Authority.of(ALICE).withGrant(GRANT_A);
		Authority differentGrant = Authority.of(ALICE).withGrant(GRANT_B);
		Authority differentDid = Authority.of(BOB).withGrant(GRANT_A);

		assertEquals(a1, a2);
		assertEquals(a1.hashCode(), a2.hashCode());
		assertNotEquals(a1, differentGrant);
		assertNotEquals(a1, differentDid);
		assertNotEquals(Authority.of(ALICE), Authority.ANONYMOUS);
	}
}
