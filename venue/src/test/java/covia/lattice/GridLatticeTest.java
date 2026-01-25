package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
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
 * Tests for GridLattice CRDT properties and merge semantics.
 */
public class GridLatticeTest {

	private GridLattice lattice;

	// Test hashes for content-addressed metadata
	private static final Hash testHash1 = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
	private static final Hash testHash2 = Hash.fromHex("2222222222222222222222222222222222222222222222222222222222222222");
	private static final Hash testHash3 = Hash.fromHex("3333333333333333333333333333333333333333333333333333333333333333");
	private static final Hash metaHash1 = Hash.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	private static final Hash metaHash2 = Hash.fromHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

	@BeforeEach
	public void setup() {
		lattice = GridLattice.create();
	}

	// ========== Basic Lattice Properties ==========

	@Test
	public void testSingletonInstance() {
		GridLattice l1 = GridLattice.create();
		GridLattice l2 = GridLattice.create();
		assertSame(l1, l2, "GridLattice should be a singleton");
		assertSame(GridLattice.INSTANCE, l1);
	}

	@Test
	public void testZeroValue() {
		AMap<Keyword, ACell> zero = lattice.zero();
		assertNotNull(zero, "Zero value should not be null");
		assertTrue(zero.containsKey(GridLattice.VENUES), "Zero should contain :venues");
		assertTrue(zero.containsKey(GridLattice.META), "Zero should contain :meta");

		assertEquals(Index.none(), zero.get(GridLattice.VENUES));
		assertEquals(Index.none(), zero.get(GridLattice.META));
	}

	@Test
	public void testCheckForeign() {
		assertTrue(lattice.checkForeign(lattice.zero()));
		assertTrue(lattice.checkForeign(Maps.empty()));
		assertEquals(false, lattice.checkForeign(null));
	}

	// ========== Null Handling ==========

	@Test
	public void testMergeWithNull() {
		AMap<Keyword, ACell> value = lattice.zero();

		assertSame(value, lattice.merge(value, null));
		assertEquals(value, lattice.merge(null, value));
		assertNull(lattice.merge(null, null));
	}

	// ========== Idempotency ==========

	@Test
	public void testIdempotency() {
		AMap<Keyword, ACell> value = createTestGridState();
		AMap<Keyword, ACell> merged = lattice.merge(value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		AMap<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		AMap<Keyword, ACell> v2 = createGridStateWithMeta(testHash2, "{\"name\":\"meta2\"}");

		AMap<Keyword, ACell> merge12 = lattice.merge(v1, v2);
		AMap<Keyword, ACell> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	@Test
	public void testCommutativityWithVenues() {
		AMap<Keyword, ACell> v1 = createGridStateWithVenue("did:web:venue1.example.com");
		AMap<Keyword, ACell> v2 = createGridStateWithVenue("did:web:venue2.example.com");

		AMap<Keyword, ACell> merge12 = lattice.merge(v1, v2);
		AMap<Keyword, ACell> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative for venues");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		AMap<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		AMap<Keyword, ACell> v2 = createGridStateWithMeta(testHash2, "{\"name\":\"meta2\"}");
		AMap<Keyword, ACell> v3 = createGridStateWithMeta(testHash3, "{\"name\":\"meta3\"}");

		AMap<Keyword, ACell> left = lattice.merge(lattice.merge(v1, v2), v3);
		AMap<Keyword, ACell> right = lattice.merge(v1, lattice.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		AMap<Keyword, ACell> value = createTestGridState();
		AMap<Keyword, ACell> zero = lattice.zero();

		assertEquals(value, lattice.merge(value, zero));
		assertEquals(value, lattice.merge(zero, value));
	}

	// ========== Meta Merge (Union - Content Addressed Index<Hash, AString>) ==========

	@Test
	public void testMetaMergeUnion() {
		AMap<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"asset1\"}");
		AMap<Keyword, ACell> v2 = createGridStateWithMeta(testHash2, "{\"name\":\"asset2\"}");

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		Index<Hash, AString> meta = (Index<Hash, AString>) merged.get(GridLattice.META);

		assertEquals(2, meta.count(), "Merged meta should contain both entries");
		assertNotNull(meta.get(testHash1));
		assertNotNull(meta.get(testHash2));
	}

	@Test
	public void testMetaMergeSameKey() {
		// Same content hash means same content - no conflict
		AMap<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"data\"}");
		AMap<Keyword, ACell> v2 = createGridStateWithMeta(testHash1, "{\"name\":\"data\"}");

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		Index<Hash, AString> meta = (Index<Hash, AString>) merged.get(GridLattice.META);

		assertEquals(1, meta.count(), "Same key should not duplicate");
	}

	// ========== Venues Merge (Per-Venue with VenueLattice) ==========

	@Test
	public void testVenuesMergeUnion() {
		AMap<Keyword, ACell> v1 = createGridStateWithVenue("did:web:venue1.example.com");
		AMap<Keyword, ACell> v2 = createGridStateWithVenue("did:web:venue2.example.com");

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		Index<AString, ACell> venues = (Index<AString, ACell>) merged.get(GridLattice.VENUES);

		assertEquals(2, venues.count(), "Merged venues should contain both entries");
	}

	@Test
	public void testVenuesMergeSameVenueDifferentAssets() {
		// Two updates to the same venue should merge venue state
		String venueDID = "did:web:venue1.example.com";

		AMap<Keyword, ACell> venueState1 = Maps.of(
			VenueLattice.ASSETS, Maps.of("0xasset1", Maps.of("name", "Asset1")),
			VenueLattice.JOBS, Maps.empty()
		);
		AMap<Keyword, ACell> venueState2 = Maps.of(
			VenueLattice.ASSETS, Maps.of("0xasset2", Maps.of("name", "Asset2")),
			VenueLattice.JOBS, Maps.empty()
		);

		AMap<Keyword, ACell> v1 = Maps.of(
			GridLattice.VENUES, Index.of(venueDID, venueState1),
			GridLattice.META, Index.none()
		);
		AMap<Keyword, ACell> v2 = Maps.of(
			GridLattice.VENUES, Index.of(venueDID, venueState2),
			GridLattice.META, Index.none()
		);

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		Index<AString, ACell> venues = (Index<AString, ACell>) merged.get(GridLattice.VENUES);
		@SuppressWarnings("unchecked")
		AMap<Keyword, ACell> mergedVenue = (AMap<Keyword, ACell>) venues.get(Strings.create(venueDID));
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> assets = (AMap<ACell, ACell>) mergedVenue.get(VenueLattice.ASSETS);

		assertEquals(1, venues.count(), "Should have one venue");
		assertEquals(2, assets.count(), "Venue should have both assets merged");
	}

	// ========== Path Navigation ==========

	@Test
	public void testPathNavigation() {
		ALattice<ACell> venuesLattice = lattice.path(GridLattice.VENUES);
		ALattice<ACell> metaLattice = lattice.path(GridLattice.META);

		assertNotNull(venuesLattice, "Should return venues lattice");
		assertNotNull(metaLattice, "Should return meta lattice");

		assertNull(lattice.path(Keyword.intern("unknown")));
	}

	@Test
	public void testPathSelf() {
		assertSame(lattice, lattice.path(), "Empty path should return self");
	}

	@Test
	public void testPathToVenueLattice() {
		// Path through :venues to a specific venue should get VenueLattice
		ALattice<ACell> venuesLattice = lattice.path(GridLattice.VENUES);
		assertNotNull(venuesLattice);

		// Any key under venues should return VenueLattice
		ALattice<ACell> venueLattice = venuesLattice.path(Strings.create("did:web:example.com"));
		assertNotNull(venueLattice, "Should get VenueLattice for any venue DID");
	}

	// ========== Full Grid Structure Test ==========

	@Test
	public void testFullGridMerge() {
		// Create two grid states with different venues and shared meta
		AMap<Keyword, ACell> venueState1 = Maps.of(
			VenueLattice.ASSETS, Maps.of("0xasset1", Maps.empty()),
			VenueLattice.JOBS, Maps.of(
				"job1", Maps.of(
					"status", "COMPLETE",
					VenueLattice.UPDATED, CVMLong.create(1000L)
				)
			)
		);

		AMap<Keyword, ACell> venueState2 = Maps.of(
			VenueLattice.ASSETS, Maps.of("0xasset2", Maps.empty()),
			VenueLattice.JOBS, Maps.of(
				"job2", Maps.of(
					"status", "PENDING",
					VenueLattice.UPDATED, CVMLong.create(2000L)
				)
			)
		);

		AMap<Keyword, ACell> grid1 = Maps.of(
			GridLattice.VENUES, Index.of("did:web:venue1.com", venueState1),
			GridLattice.META, Index.of(metaHash1, "{\"name\":\"Asset Definition 1\"}")
		);

		AMap<Keyword, ACell> grid2 = Maps.of(
			GridLattice.VENUES, Index.of("did:web:venue2.com", venueState2),
			GridLattice.META, Index.of(metaHash2, "{\"name\":\"Asset Definition 2\"}")
		);

		AMap<Keyword, ACell> merged = lattice.merge(grid1, grid2);

		@SuppressWarnings("unchecked")
		Index<AString, ACell> venues = (Index<AString, ACell>) merged.get(GridLattice.VENUES);
		@SuppressWarnings("unchecked")
		Index<Hash, AString> meta = (Index<Hash, AString>) merged.get(GridLattice.META);

		assertEquals(2, venues.count(), "Should have 2 venues");
		assertEquals(2, meta.count(), "Should have 2 meta entries");
	}

	// ========== Helper Methods ==========

	private AMap<Keyword, ACell> createTestGridState() {
		AMap<Keyword, ACell> venueState = Maps.of(
			VenueLattice.ASSETS, Maps.of("0xtest", Maps.empty()),
			VenueLattice.JOBS, Maps.empty()
		);

		return Maps.of(
			GridLattice.VENUES, Index.of("did:web:test.example.com", venueState),
			GridLattice.META, Index.of(testHash1, "{\"name\":\"Test\"}")
		);
	}

	private AMap<Keyword, ACell> createGridStateWithMeta(Hash metaHash, String jsonContent) {
		return Maps.of(
			GridLattice.VENUES, Index.none(),
			GridLattice.META, Index.of(metaHash, jsonContent)
		);
	}

	private AMap<Keyword, ACell> createGridStateWithVenue(String venueDID) {
		AMap<Keyword, ACell> venueState = Maps.of(
			VenueLattice.ASSETS, Maps.empty(),
			VenueLattice.JOBS, Maps.empty()
		);

		return Maps.of(
			GridLattice.VENUES, Index.of(venueDID, venueState),
			GridLattice.META, Index.none()
		);
	}
}
