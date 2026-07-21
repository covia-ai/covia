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
	private static final ACell PROOF = Strings.create("proof-1");

	@Test
	public void testIdentityOnlyIsUnrestricted() {
		// Identity with no explicit scope = unrestricted (null grants), no proofs.
		Authority a = Authority.of(ALICE);
		assertEquals(ALICE, a.getDID());
		assertFalse(a.isAnonymous());
		assertFalse(a.hasGrants());
		assertNull(a.getGrants());   // null scope = unrestricted, the fast path
		assertNull(a.getProofs());
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
	public void testScopedConstruction() {
		Authority a = Authority.of(ALICE, Vectors.of(GRANT_A));
		assertEquals(1, a.getGrants().count());
		assertTrue(a.hasGrants());
		// a null scope is unrestricted, not a bound
		assertNull(Authority.of(ALICE, null).getGrants());
	}

	@Test
	public void testWithGrantIsAdditiveOnAScopedAuthority() {
		Authority base = Authority.of(ALICE, Vectors.of(GRANT_A));  // scoped
		Authority two = base.withGrant(GRANT_B);

		// base is unchanged — immutability
		assertEquals(1, base.getGrants().count());
		assertEquals(2, two.getGrants().count());
		assertTrue(two.hasGrants());
		assertEquals(ALICE, two.getDID());
	}

	@Test
	public void testWithGrantsBulk() {
		Authority a = Authority.of(BOB, Vectors.of(GRANT_A)).withGrants(Vectors.of(GRANT_B));
		assertEquals(2, a.getGrants().count());

		// null / empty augmentation is a no-op
		assertSame(a, a.withGrants(null));
		assertSame(a, a.withGrants(Vectors.empty()));
	}

	@Test
	public void testAugmentingUnrestrictedIsANoOp() {
		// A null scope already covers everything — adding a grant cannot widen it,
		// and must never accidentally narrow an unrestricted principal to a scope.
		Authority unrestricted = Authority.of(ALICE);
		assertSame(unrestricted, unrestricted.withGrant(GRANT_A));
		assertSame(unrestricted, unrestricted.withGrants(Vectors.of(GRANT_A)));
		assertNull(unrestricted.withGrant(GRANT_A).getGrants());
	}

	@Test
	public void testNullGrantIgnored() {
		Authority a = Authority.of(ALICE, Vectors.of(GRANT_A));
		assertSame(a, a.withGrant(null));
	}

	@Test
	public void testWithGrantScopeReplaces() {
		Authority unrestricted = Authority.of(ALICE);
		Authority scoped = unrestricted.withGrantScope(Vectors.of(GRANT_A));
		assertEquals(1, scoped.getGrants().count());
		// replacing back to null restores unrestricted
		assertNull(scoped.withGrantScope(null).getGrants());
		// identity preserved
		assertEquals(ALICE, scoped.getDID());
	}

	@Test
	public void testWithProofsCarriesAndPreservesScope() {
		Authority scoped = Authority.of(ALICE, Vectors.of(GRANT_A));
		Authority withProof = scoped.withProofs(Vectors.of(PROOF));
		assertEquals(1, withProof.getProofs().count());
		assertTrue(withProof.hasProofs());
		// scope and identity survive attaching proofs
		assertEquals(1, withProof.getGrants().count());
		assertEquals(ALICE, withProof.getDID());
		// no proofs by default
		assertFalse(scoped.hasProofs());
	}

	@Test
	public void testEquality() {
		Authority a1 = Authority.of(ALICE, Vectors.of(GRANT_A));
		Authority a2 = Authority.of(ALICE, Vectors.of(GRANT_A));
		Authority differentGrant = Authority.of(ALICE, Vectors.of(GRANT_B));
		Authority differentDid = Authority.of(BOB, Vectors.of(GRANT_A));
		Authority withProof = a1.withProofs(Vectors.of(PROOF));

		assertEquals(a1, a2);
		assertEquals(a1.hashCode(), a2.hashCode());
		assertNotEquals(a1, differentGrant);
		assertNotEquals(a1, differentDid);
		assertNotEquals(a1, withProof);                       // proofs count in equality
		assertNotEquals(Authority.of(ALICE), Authority.ANONYMOUS);
		// unrestricted (null scope) != empty scope
		assertNotEquals(Authority.of(ALICE), Authority.of(ALICE, Vectors.empty()));
	}
}
