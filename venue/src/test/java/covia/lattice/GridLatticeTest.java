package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
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
 * Tests for Grid-level lattice CRDT properties and merge semantics.
 *
 * <p>The grid lattice is obtained via {@code Covia.ROOT.path(Covia.GRID)} and is
 * a {@link KeyedLattice} with {@code :venues} and {@code :meta} fields.
 */
public class GridLatticeTest {

	private KeyedLattice gridLattice;

	// Test keypairs for signing venue data
	private static final AKeyPair kp1 = AKeyPair.generate();
	private static final AKeyPair kp2 = AKeyPair.generate();
	private static final AKeyPair kp3 = AKeyPair.generate();

	// Test hashes for content-addressed metadata
	private static final Hash testHash1 = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
	private static final Hash testHash2 = Hash.fromHex("2222222222222222222222222222222222222222222222222222222222222222");
	private static final Hash testHash3 = Hash.fromHex("3333333333333333333333333333333333333333333333333333333333333333");
	private static final Hash metaHash1 = Hash.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	private static final Hash metaHash2 = Hash.fromHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

	@BeforeEach
	public void setup() {
		ALattice<?> lattice = Covia.ROOT.path(Covia.GRID);
		gridLattice = (KeyedLattice) lattice;
	}

	// ========== Basic Lattice Properties ==========

	@Test
	public void testGridLatticeAvailable() {
		ALattice<?> lattice = Covia.ROOT.path(Covia.GRID);
		assertNotNull(lattice, "Grid lattice should be available via Covia.ROOT.path(Covia.GRID)");
		assertTrue(lattice instanceof KeyedLattice, "Grid lattice should be a KeyedLattice");
	}

	@Test
	public void testZeroValue() {
		Index<Keyword, ACell> zero = gridLattice.zero();
		assertNotNull(zero, "Zero value should not be null");
		// zero() returns an empty Index (no pre-populated keys)
		assertEquals(0, zero.count(), "Zero should be empty Index");
	}

	@Test
	public void testCheckForeign() {
		assertTrue(gridLattice.checkForeign(gridLattice.zero()));
		assertTrue(gridLattice.checkForeign(Index.of()));
		assertEquals(false, gridLattice.checkForeign(null));
	}

	// ========== Null Handling ==========

	@Test
	public void testMergeWithNull() {
		Index<Keyword, ACell> value = gridLattice.zero();

		assertSame(value, gridLattice.merge(value, null));
		assertEquals(value, gridLattice.merge(null, value));
		assertNull(gridLattice.merge(null, null));
	}

	// ========== Idempotency ==========

	@Test
	public void testIdempotency() {
		Index<Keyword, ACell> value = createTestGridState(kp1);
		LatticeContext ctx = LatticeContext.create(null, kp1);
		Index<Keyword, ACell> merged = gridLattice.merge(ctx, value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		Index<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		Index<Keyword, ACell> v2 = createGridStateWithMeta(testHash2, "{\"name\":\"meta2\"}");

		Index<Keyword, ACell> merge12 = gridLattice.merge(v1, v2);
		Index<Keyword, ACell> merge21 = gridLattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	@Test
	public void testCommutativityWithVenues() {
		LatticeContext ctx1 = LatticeContext.create(null, kp1);
		LatticeContext ctx2 = LatticeContext.create(null, kp2);
		Index<Keyword, ACell> v1 = createGridStateWithVenue(kp1);
		Index<Keyword, ACell> v2 = createGridStateWithVenue(kp2);

		// OwnerLattice merge needs context for signing
		Index<Keyword, ACell> merge12 = gridLattice.merge(ctx1, v1, v2);
		Index<Keyword, ACell> merge21 = gridLattice.merge(ctx2, v2, v1);

		// Both merges should contain both venues (same owners, same data)
		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<?>> venues12 = (AHashMap<ACell, SignedData<?>>) merge12.get(Covia.VENUES);
		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<?>> venues21 = (AHashMap<ACell, SignedData<?>>) merge21.get(Covia.VENUES);
		assertEquals(2, venues12.count(), "Merge should contain both venues");
		assertEquals(2, venues21.count(), "Merge should contain both venues");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		Index<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"meta1\"}");
		Index<Keyword, ACell> v2 = createGridStateWithMeta(testHash2, "{\"name\":\"meta2\"}");
		Index<Keyword, ACell> v3 = createGridStateWithMeta(testHash3, "{\"name\":\"meta3\"}");

		Index<Keyword, ACell> left = gridLattice.merge(gridLattice.merge(v1, v2), v3);
		Index<Keyword, ACell> right = gridLattice.merge(v1, gridLattice.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		Index<Keyword, ACell> value = createTestGridState(kp1);
		Index<Keyword, ACell> zero = gridLattice.zero();
		LatticeContext ctx = LatticeContext.create(null, kp1);

		assertEquals(value, gridLattice.merge(ctx, value, zero));
		assertEquals(value, gridLattice.merge(ctx, zero, value));
	}

	// ========== Meta Merge (Union - Content Addressed Index<ABlob, AString>) ==========

	@Test
	public void testMetaMergeUnion() {
		Index<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"asset1\"}");
		Index<Keyword, ACell> v2 = createGridStateWithMeta(testHash2, "{\"name\":\"asset2\"}");

		Index<Keyword, ACell> merged = gridLattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		Index<ABlob, AString> meta = (Index<ABlob, AString>) merged.get(Covia.META);

		assertEquals(2, meta.count(), "Merged meta should contain both entries");
		assertNotNull(meta.get(testHash1));
		assertNotNull(meta.get(testHash2));
	}

	@Test
	public void testMetaMergeSameKey() {
		// Same content hash means same content - no conflict
		Index<Keyword, ACell> v1 = createGridStateWithMeta(testHash1, "{\"name\":\"data\"}");
		Index<Keyword, ACell> v2 = createGridStateWithMeta(testHash1, "{\"name\":\"data\"}");

		Index<Keyword, ACell> merged = gridLattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		Index<ABlob, AString> meta = (Index<ABlob, AString>) merged.get(Covia.META);

		assertEquals(1, meta.count(), "Same key should not duplicate");
	}

	// ========== Venues Merge (Per-Owner with OwnerLattice) ==========

	@Test
	public void testVenuesMergeUnion() {
		Index<Keyword, ACell> v1 = createGridStateWithVenue(kp1);
		Index<Keyword, ACell> v2 = createGridStateWithVenue(kp2);

		LatticeContext ctx = LatticeContext.create(null, kp1);
		Index<Keyword, ACell> merged = gridLattice.merge(ctx, v1, v2);

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<?>> venues = (AHashMap<ACell, SignedData<?>>) merged.get(Covia.VENUES);

		assertEquals(2, venues.count(), "Merged venues should contain both entries");
	}

	@Test
	public void testVenuesMergeSameOwnerDifferentAssets() {
		// Two updates to the same venue (same owner) should merge venue state
		Index<Keyword, ACell> venueState1 = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xasset1"), Maps.of(Strings.create("name"), Strings.create("Asset1"))));
		Index<Keyword, ACell> venueState2 = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xasset2"), Maps.of(Strings.create("name"), Strings.create("Asset2"))));

		AccountKey owner = kp1.getAccountKey();
		Index<Keyword, ACell> v1 = gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(owner, kp1.signData(venueState1)));
		Index<Keyword, ACell> v2 = gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(owner, kp1.signData(venueState2)));

		LatticeContext ctx = LatticeContext.create(null, kp1);
		Index<Keyword, ACell> merged = gridLattice.merge(ctx, v1, v2);

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<Index<Keyword, ACell>>> venues =
			(AHashMap<ACell, SignedData<Index<Keyword, ACell>>>) merged.get(Covia.VENUES);
		assertEquals(1, venues.count(), "Should have one venue");

		SignedData<Index<Keyword, ACell>> signedVenue = venues.get(owner);
		assertNotNull(signedVenue, "Owner's venue should exist");

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> assets = (AMap<ACell, ACell>) signedVenue.getValue().get(Covia.ASSETS);
		assertEquals(2, assets.count(), "Venue should have both assets merged");
	}

	// ========== Path Navigation ==========

	@Test
	public void testPathNavigation() {
		ALattice<ACell> venuesLattice = gridLattice.path(Covia.VENUES);
		ALattice<ACell> metaLattice = gridLattice.path(Covia.META);

		assertNotNull(venuesLattice, "Should return venues lattice");
		assertNotNull(metaLattice, "Should return meta lattice");

		assertNull(gridLattice.path(Keyword.intern("unknown")));
	}

	@Test
	public void testPathSelf() {
		assertSame(gridLattice, gridLattice.path(), "Empty path should return self");
	}

	@Test
	public void testPathToVenueLattice() {
		// Path through :venues returns OwnerLattice
		ALattice<ACell> venuesLattice = gridLattice.path(Covia.VENUES);
		assertNotNull(venuesLattice);

		// Any key under OwnerLattice returns SignedLattice
		ALattice<ACell> signedLattice = venuesLattice.path(kp1.getAccountKey());
		assertNotNull(signedLattice, "Should get SignedLattice for any owner key");
	}

	// ========== Full Grid Structure Test ==========

	@Test
	public void testFullGridMerge() {
		// Create two grid states with different venues and shared meta
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
			.assoc(Covia.META, Index.of(metaHash1, "{\"name\":\"Asset Definition 1\"}"));

		Index<Keyword, ACell> grid2 = gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(kp2.getAccountKey(), kp2.signData(venueState2)))
			.assoc(Covia.META, Index.of(metaHash2, "{\"name\":\"Asset Definition 2\"}"));

		LatticeContext ctx = LatticeContext.create(null, kp1);
		Index<Keyword, ACell> merged = gridLattice.merge(ctx, grid1, grid2);

		@SuppressWarnings("unchecked")
		AHashMap<ACell, SignedData<?>> venues = (AHashMap<ACell, SignedData<?>>) merged.get(Covia.VENUES);
		@SuppressWarnings("unchecked")
		Index<ABlob, AString> meta = (Index<ABlob, AString>) merged.get(Covia.META);

		assertEquals(2, venues.count(), "Should have 2 venues");
		assertEquals(2, meta.count(), "Should have 2 meta entries");
	}

	// ========== Helper Methods ==========

	private Index<Keyword, ACell> createTestGridState(AKeyPair kp) {
		Index<Keyword, ACell> venueState = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xtest"), Maps.empty()));

		return gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(kp.getAccountKey(), kp.signData(venueState)))
			.assoc(Covia.META, Index.of(testHash1, "{\"name\":\"Test\"}"));
	}

	private Index<Keyword, ACell> createGridStateWithMeta(Hash metaHash, String jsonContent) {
		return gridLattice.zero()
			.assoc(Covia.META, Index.of(metaHash, jsonContent));
	}

	private Index<Keyword, ACell> createGridStateWithVenue(AKeyPair kp) {
		Index<Keyword, ACell> venueState = Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(Strings.create("0xtest"), Maps.empty()));

		return gridLattice.zero()
			.assoc(Covia.VENUES, Maps.of(kp.getAccountKey(), kp.signData(venueState)));
	}
}
