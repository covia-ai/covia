package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Strings;

/**
 * Tests for CASLattice (Content-Addressed Storage Lattice) CRDT properties.
 */
public class CASLatticeTest {

	private CASLattice<Hash, AString> lattice;

	// Test hashes
	private static final Hash hash1 = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
	private static final Hash hash2 = Hash.fromHex("2222222222222222222222222222222222222222222222222222222222222222");
	private static final Hash hash3 = Hash.fromHex("3333333333333333333333333333333333333333333333333333333333333333");

	@BeforeEach
	public void setup() {
		lattice = CASLattice.create();
	}

	// ========== Basic Lattice Properties ==========

	@Test
	public void testSingletonInstance() {
		CASLattice<Hash, AString> l1 = CASLattice.create();
		CASLattice<Hash, AString> l2 = CASLattice.create();
		assertSame(l1, l2, "CASLattice should be a singleton");
	}

	@Test
	public void testZeroValue() {
		Index<Hash, AString> zero = lattice.zero();
		assertNotNull(zero, "Zero value should not be null");
		assertEquals(0, zero.count(), "Zero should be empty");
		assertEquals(Index.none(), zero);
	}

	@Test
	public void testCheckForeign() {
		assertTrue(lattice.checkForeign(lattice.zero()));
		assertTrue(lattice.checkForeign(Index.create(hash1, Strings.create("test"))));
		assertEquals(false, lattice.checkForeign(null));
	}

	@Test
	public void testPathReturnsNull() {
		// CASLattice is a leaf lattice - no child navigation
		assertNull(lattice.path(hash1));
		assertNull(lattice.path(Strings.create("key")));
	}

	// ========== Null Handling ==========

	@Test
	public void testMergeWithNull() {
		Index<Hash, AString> value = Index.create(hash1, Strings.create("data"));

		assertSame(value, lattice.merge(value, null));
		assertEquals(value, lattice.merge(null, value));
		assertNull(lattice.merge(null, null));
	}

	// ========== Idempotency ==========

	@Test
	public void testIdempotency() {
		Index<Hash, AString> value = Index.create(hash1, Strings.create("data"));
		Index<Hash, AString> merged = lattice.merge(value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	@Test
	public void testIdempotencyWithZero() {
		Index<Hash, AString> zero = lattice.zero();
		assertSame(zero, lattice.merge(zero, zero));
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		Index<Hash, AString> v1 = Index.create(hash1, Strings.create("data1"));
		Index<Hash, AString> v2 = Index.create(hash2, Strings.create("data2"));

		Index<Hash, AString> merge12 = lattice.merge(v1, v2);
		Index<Hash, AString> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		Index<Hash, AString> v1 = Index.create(hash1, Strings.create("data1"));
		Index<Hash, AString> v2 = Index.create(hash2, Strings.create("data2"));
		Index<Hash, AString> v3 = Index.create(hash3, Strings.create("data3"));

		Index<Hash, AString> left = lattice.merge(lattice.merge(v1, v2), v3);
		Index<Hash, AString> right = lattice.merge(v1, lattice.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		Index<Hash, AString> value = Index.create(hash1, Strings.create("data"));
		Index<Hash, AString> zero = lattice.zero();

		assertEquals(value, lattice.merge(value, zero));
		assertEquals(value, lattice.merge(zero, value));
	}

	// ========== Union Merge Semantics ==========

	@Test
	public void testUnionMerge() {
		Index<Hash, AString> v1 = Index.create(hash1, Strings.create("data1"));
		Index<Hash, AString> v2 = Index.create(hash2, Strings.create("data2"));

		Index<Hash, AString> merged = lattice.merge(v1, v2);

		assertEquals(2, merged.count(), "Merged index should contain both entries");
		assertNotNull(merged.get(hash1));
		assertNotNull(merged.get(hash2));
		assertEquals(Strings.create("data1"), merged.get(hash1));
		assertEquals(Strings.create("data2"), merged.get(hash2));
	}

	@Test
	public void testSameKeyNoConflict() {
		// Same content hash means same content - no conflict possible
		Index<Hash, AString> v1 = Index.create(hash1, Strings.create("data"));
		Index<Hash, AString> v2 = Index.create(hash1, Strings.create("data"));

		Index<Hash, AString> merged = lattice.merge(v1, v2);

		assertEquals(1, merged.count(), "Same key should not duplicate");
		assertEquals(Strings.create("data"), merged.get(hash1));
	}

	// ========== Different Key Types ==========

	@Test
	public void testWithBlobKeys() {
		CASLattice<Blob, AString> blobLattice = CASLattice.create();

		Blob key1 = Blob.fromHex("aabbccdd");
		Blob key2 = Blob.fromHex("11223344");

		Index<Blob, AString> v1 = Index.create(key1, Strings.create("value1"));
		Index<Blob, AString> v2 = Index.create(key2, Strings.create("value2"));

		Index<Blob, AString> merged = blobLattice.merge(v1, v2);

		assertEquals(2, merged.count());
		assertEquals(Strings.create("value1"), merged.get(key1));
		assertEquals(Strings.create("value2"), merged.get(key2));
	}

	// ========== Merge Stability ==========

	@Test
	public void testMergeStability() {
		Index<Hash, AString> v1 = Index.create(hash1, Strings.create("data1"));
		Index<Hash, AString> v2 = Index.create(hash2, Strings.create("data2"));

		Index<Hash, AString> merged = lattice.merge(v1, v2);

		// Merging with the merge result should be stable
		assertEquals(merged, lattice.merge(v1, merged));
		assertEquals(merged, lattice.merge(merged, v1));
		assertEquals(merged, lattice.merge(v2, merged));
		assertEquals(merged, lattice.merge(merged, v2));
	}
}
