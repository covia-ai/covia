package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.KeyedLattice;

/**
 * Tests for Covia.ROOT lattice CRDT properties and structure.
 */
@SuppressWarnings("unchecked")
public class CoviaTest {

	// Grid lattice for building state
	@SuppressWarnings("rawtypes")
	private static final KeyedLattice gridLattice = (KeyedLattice) (ALattice) Covia.ROOT.path(Covia.GRID);

	// Test keypairs for signing venue data
	private static final AKeyPair kp1 = AKeyPair.generate();
	private static final AKeyPair kp2 = AKeyPair.generate();

	// Test hashes
	private static final Hash testHash1 = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
	private static final Hash testHash2 = Hash.fromHex("2222222222222222222222222222222222222222222222222222222222222222");

	// ========== Basic Properties ==========

	@Test
	public void testRootExists() {
		assertNotNull(Covia.ROOT, "ROOT lattice should exist");
	}

	@Test
	public void testGridKeyword() {
		assertEquals(Keyword.intern("grid"), Covia.GRID);
	}

	@Test
	public void testEmptyState() {
		// Create an initial structured state like Engine.initialiseLattice() does
		Index<Keyword, ACell> empty = createEmptyState();
		assertNotNull(empty);
		assertTrue(empty.containsKey(Covia.GRID));

		// Grid should contain venues and meta
		Index<Keyword, ACell> grid = (Index<Keyword, ACell>) empty.get(Covia.GRID);
		assertTrue(grid.containsKey(Covia.VENUES));
		assertTrue(grid.containsKey(Covia.META));
	}

	@Test
	public void testZeroValue() {
		// KeyedLattice.zero() returns empty Index (the CRDT zero)
		Index<Keyword, ACell> zero = Covia.ROOT.zero();
		assertNotNull(zero);
		assertEquals(0, zero.count());

		// Structured empty state should contain :grid key
		Index<Keyword, ACell> empty = createEmptyState();
		assertTrue(empty.containsKey(Covia.GRID));
	}

	@Test
	public void testCheckForeign() {
		assertTrue(Covia.ROOT.checkForeign(createEmptyState()));
		assertTrue(Covia.ROOT.checkForeign(Index.none()));
		assertEquals(false, Covia.ROOT.checkForeign(null));
	}

	// ========== Null Handling ==========

	@Test
	public void testMergeWithNull() {
		Index<Keyword, ACell> value = createEmptyState();

		assertSame(value, Covia.ROOT.merge(value, null));
		assertEquals(value, Covia.ROOT.merge(null, value));
		assertNull(Covia.ROOT.merge(null, null));
	}

	// ========== Idempotency ==========

	@Test
	public void testIdempotency() {
		Index<Keyword, ACell> value = createTestState(kp1);
		LatticeContext ctx = LatticeContext.create(null, kp1);
		Index<Keyword, ACell> merged = Covia.ROOT.merge(ctx, value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		Index<Keyword, ACell> v1 = createStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		Index<Keyword, ACell> v2 = createStateWithMeta(testHash2, "{\"name\":\"meta2\"}");

		Index<Keyword, ACell> merge12 = Covia.ROOT.merge(v1, v2);
		Index<Keyword, ACell> merge21 = Covia.ROOT.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		Hash hash3 = Hash.fromHex("3333333333333333333333333333333333333333333333333333333333333333");

		Index<Keyword, ACell> v1 = createStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		Index<Keyword, ACell> v2 = createStateWithMeta(testHash2, "{\"name\":\"meta2\"}");
		Index<Keyword, ACell> v3 = createStateWithMeta(hash3, "{\"name\":\"meta3\"}");

		Index<Keyword, ACell> left = Covia.ROOT.merge(Covia.ROOT.merge(v1, v2), v3);
		Index<Keyword, ACell> right = Covia.ROOT.merge(v1, Covia.ROOT.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		Index<Keyword, ACell> value = createTestState(kp1);
		Index<Keyword, ACell> zero = Covia.ROOT.zero();
		LatticeContext ctx = LatticeContext.create(null, kp1);

		assertEquals(value, Covia.ROOT.merge(ctx, value, zero));
		assertEquals(value, Covia.ROOT.merge(ctx, zero, value));
	}

	// ========== Path Navigation ==========

	@Test
	public void testPathToGrid() {
		ALattice<ACell> gridLattice = Covia.ROOT.path(Covia.GRID);
		assertNotNull(gridLattice, "Should return grid KeyedLattice");
	}

	@Test
	public void testPathUnknownKey() {
		assertNull(Covia.ROOT.path(Keyword.intern("unknown")));
	}

	@Test
	public void testPathSelf() {
		assertSame(Covia.ROOT, Covia.ROOT.path(), "Empty path should return self");
	}

	@Test
	public void testDeepPathNavigation() {
		// ROOT -> :grid -> :venues -> <accountKey> -> SignedLattice
		ALattice<ACell> gridLattice = Covia.ROOT.path(Covia.GRID);
		assertNotNull(gridLattice);

		ALattice<ACell> venuesLattice = gridLattice.path(Covia.VENUES);
		assertNotNull(venuesLattice);

		// Any owner key under OwnerLattice should return SignedLattice
		ALattice<ACell> signedLattice = venuesLattice.path(kp1.getAccountKey());
		assertNotNull(signedLattice);
	}

	// ========== Full State Merge ==========

	@Test
	public void testFullStateMerge() {
		// Create two complete states with different venues and meta
		Index<Keyword, ACell> venueState1 = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xasset1"), Maps.empty()))
			.assoc(Covia.JOBS, Index.of(
				Strings.create("job1"), Maps.of(
					Strings.create("status"), Strings.create("COMPLETE"),
					Strings.create("updated"), CVMLong.create(1000L)
				)
			));

		Index<Keyword, ACell> venueState2 = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xasset2"), Maps.empty()))
			.assoc(Covia.JOBS, Index.of(
				Strings.create("job2"), Maps.of(
					Strings.create("status"), Strings.create("PENDING"),
					Strings.create("updated"), CVMLong.create(2000L)
				)
			));

		Index<Keyword, ACell> grid1 = gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(kp1.getAccountKey(), kp1.signData(venueState1)))
			.assoc(Covia.META, Index.of(testHash1, "{\"name\":\"Asset 1\"}"));

		Index<Keyword, ACell> grid2 = gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(kp2.getAccountKey(), kp2.signData(venueState2)))
			.assoc(Covia.META, Index.of(testHash2, "{\"name\":\"Asset 2\"}"));

		Index<Keyword, ACell> state1 = Covia.ROOT.zero().assoc(Covia.GRID, grid1);
		Index<Keyword, ACell> state2 = Covia.ROOT.zero().assoc(Covia.GRID, grid2);

		LatticeContext ctx = LatticeContext.create(null, kp1);
		Index<Keyword, ACell> merged = Covia.ROOT.merge(ctx, state1, state2);

		// Verify merged structure
		Index<Keyword, ACell> mergedGrid = (Index<Keyword, ACell>) merged.get(Covia.GRID);
		AHashMap<ACell, SignedData<?>> venues =
			(AHashMap<ACell, SignedData<?>>) mergedGrid.get(Covia.VENUES);
		Index<ABlob, AString> meta = (Index<ABlob, AString>) mergedGrid.get(Covia.META);

		assertEquals(2, venues.count(), "Should have 2 venues");
		assertEquals(2, meta.count(), "Should have 2 meta entries");
	}

	// ========== Helper Methods ==========

	/**
	 * Creates an empty but structured state, similar to Engine.initialiseLattice()
	 */
	private Index<Keyword, ACell> createEmptyState() {
		return Covia.ROOT.zero()
			.assoc(Covia.GRID, gridLattice.zero()
				.assoc(Covia.VENUES, Maps.empty())
				.assoc(Covia.META, Index.none()));
	}

	private Index<Keyword, ACell> createTestState(AKeyPair kp) {
		Index<Keyword, ACell> venueState = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xtest"), Maps.empty()));

		return Covia.ROOT.zero()
			.assoc(Covia.GRID, gridLattice.zero()
				.assoc(Covia.VENUES, Maps.of(kp.getAccountKey(), kp.signData(venueState)))
				.assoc(Covia.META, Index.of(testHash1, "{\"name\":\"Test\"}")));
	}

	private Index<Keyword, ACell> createStateWithMeta(Hash metaHash, String jsonContent) {
		return Covia.ROOT.zero()
			.assoc(Covia.GRID, gridLattice.zero()
				.assoc(Covia.META, Index.of(metaHash, jsonContent)));
	}
}
