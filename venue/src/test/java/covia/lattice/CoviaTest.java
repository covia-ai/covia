package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Tests for Covia.ROOT lattice CRDT properties and structure.
 */
@SuppressWarnings("unchecked")
public class CoviaTest {

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
		AMap<Keyword, ACell> empty = (AMap<Keyword, ACell>) Covia.empty();
		assertNotNull(empty);
		assertTrue(empty.containsKey(Covia.GRID));

		// Grid should contain venues and meta
		@SuppressWarnings("unchecked")
		AMap<Keyword, ACell> grid = (AMap<Keyword, ACell>) empty.get(Covia.GRID);
		assertTrue(grid.containsKey(GridLattice.VENUES));
		assertTrue(grid.containsKey(GridLattice.META));
	}

	@Test
	public void testZeroValue() {
		// KeyedLattice.zero() returns empty map (the CRDT zero)
		AMap<Keyword, ACell> zero = (AMap<Keyword, ACell>) Covia.ROOT.zero();
		assertNotNull(zero);
		assertEquals(0, zero.count());

		// Covia.empty() returns structured empty state for convenience
		AMap<Keyword, ACell> empty = Covia.empty();
		assertTrue(empty.containsKey(Covia.GRID));
	}

	@Test
	public void testCheckForeign() {
		assertTrue(Covia.ROOT.checkForeign(Covia.empty()));
		assertTrue(Covia.ROOT.checkForeign(Maps.empty()));
		assertEquals(false, Covia.ROOT.checkForeign(null));
	}

	// ========== Null Handling ==========

	@Test
	public void testMergeWithNull() {
		AMap<Keyword, ACell> value = (AMap<Keyword, ACell>) Covia.empty();

		assertSame(value, Covia.ROOT.merge(value, null));
		assertEquals(value, Covia.ROOT.merge(null, value));
		assertNull(Covia.ROOT.merge(null, null));
	}

	// ========== Idempotency ==========

	@Test
	public void testIdempotency() {
		AMap<Keyword, ACell> value = createTestState();
		AMap<Keyword, ACell> merged = (AMap<Keyword, ACell>) Covia.ROOT.merge(value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		AMap<Keyword, ACell> v1 = createStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		AMap<Keyword, ACell> v2 = createStateWithMeta(testHash2, "{\"name\":\"meta2\"}");

		AMap<Keyword, ACell> merge12 = (AMap<Keyword, ACell>) Covia.ROOT.merge(v1, v2);
		AMap<Keyword, ACell> merge21 = (AMap<Keyword, ACell>) Covia.ROOT.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		Hash hash3 = Hash.fromHex("3333333333333333333333333333333333333333333333333333333333333333");

		AMap<Keyword, ACell> v1 = createStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		AMap<Keyword, ACell> v2 = createStateWithMeta(testHash2, "{\"name\":\"meta2\"}");
		AMap<Keyword, ACell> v3 = createStateWithMeta(hash3, "{\"name\":\"meta3\"}");

		AMap<Keyword, ACell> left = (AMap<Keyword, ACell>) Covia.ROOT.merge(Covia.ROOT.merge(v1, v2), v3);
		AMap<Keyword, ACell> right = (AMap<Keyword, ACell>) Covia.ROOT.merge(v1, Covia.ROOT.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		AMap<Keyword, ACell> value = createTestState();
		AMap<Keyword, ACell> zero = (AMap<Keyword, ACell>) Covia.ROOT.zero();

		assertEquals(value, Covia.ROOT.merge(value, zero));
		assertEquals(value, Covia.ROOT.merge(zero, value));
	}

	// ========== Path Navigation ==========

	@Test
	public void testPathToGrid() {
		ALattice<ACell> gridLattice = Covia.ROOT.path(Covia.GRID);
		assertNotNull(gridLattice, "Should return GridLattice");
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
		// ROOT -> :grid -> :venues -> <did> -> VenueLattice
		ALattice<ACell> gridLattice = Covia.ROOT.path(Covia.GRID);
		assertNotNull(gridLattice);

		ALattice<ACell> venuesLattice = gridLattice.path(GridLattice.VENUES);
		assertNotNull(venuesLattice);

		// Any venue DID should return VenueLattice
		ALattice<ACell> venueLattice = venuesLattice.path(Strings.create("did:web:example.com"));
		assertNotNull(venueLattice);
	}

	// ========== Full State Merge ==========

	@Test
	public void testFullStateMerge() {
		// Create two complete states with different venues and meta
		AMap<Keyword, ACell> venueState1 = Maps.of(
			VenueLattice.ASSETS, Maps.of(Strings.create("0xasset1"), Maps.of()),
			VenueLattice.JOBS, Maps.of(
				Strings.create("job1"), Maps.of(
					Strings.create("status"), Strings.create("COMPLETE"),
					VenueLattice.UPDATED, CVMLong.create(1000L)
				)
			)
		);

		AMap<Keyword, ACell> venueState2 = Maps.of(
			VenueLattice.ASSETS, Maps.of(Strings.create("0xasset2"), Maps.of()),
			VenueLattice.JOBS, Maps.of(
				Strings.create("job2"), Maps.of(
					Strings.create("status"), Strings.create("PENDING"),
					VenueLattice.UPDATED, CVMLong.create(2000L)
				)
			)
		);

		AMap<Keyword, ACell> grid1 = Maps.of(
			GridLattice.VENUES, Maps.of(Strings.create("did:web:venue1.com"), venueState1),
			GridLattice.META, Index.create(testHash1, Strings.create("{\"name\":\"Asset 1\"}"))
		);

		AMap<Keyword, ACell> grid2 = Maps.of(
			GridLattice.VENUES, Maps.of(Strings.create("did:web:venue2.com"), venueState2),
			GridLattice.META, Index.create(testHash2, Strings.create("{\"name\":\"Asset 2\"}"))
		);

		AMap<Keyword, ACell> state1 = Maps.of(Covia.GRID, grid1);
		AMap<Keyword, ACell> state2 = Maps.of(Covia.GRID, grid2);

		AMap<Keyword, ACell> merged = (AMap<Keyword, ACell>) Covia.ROOT.merge(state1, state2);

		// Verify merged structure
		@SuppressWarnings("unchecked")
		AMap<Keyword, ACell> mergedGrid = (AMap<Keyword, ACell>) merged.get(Covia.GRID);
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> venues = (AMap<ACell, ACell>) mergedGrid.get(GridLattice.VENUES);
		@SuppressWarnings("unchecked")
		Index<Hash, AString> meta = (Index<Hash, AString>) mergedGrid.get(GridLattice.META);

		assertEquals(2, venues.count(), "Should have 2 venues");
		assertEquals(2, meta.count(), "Should have 2 meta entries");
	}

	// ========== Helper Methods ==========

	private AMap<Keyword, ACell> createTestState() {
		AMap<Keyword, ACell> venueState = Maps.of(
			VenueLattice.ASSETS, Maps.of(Strings.create("0xtest"), Maps.of()),
			VenueLattice.JOBS, Maps.empty()
		);

		AMap<Keyword, ACell> grid = Maps.of(
			GridLattice.VENUES, Maps.of(Strings.create("did:web:test.example.com"), venueState),
			GridLattice.META, Index.create(testHash1, Strings.create("{\"name\":\"Test\"}"))
		);

		return Maps.of(Covia.GRID, grid);
	}

	private AMap<Keyword, ACell> createStateWithMeta(Hash metaHash, String jsonContent) {
		AMap<Keyword, ACell> grid = Maps.of(
			GridLattice.VENUES, Maps.empty(),
			GridLattice.META, Index.create(metaHash, Strings.create(jsonContent))
		);

		return Maps.of(Covia.GRID, grid);
	}
}
