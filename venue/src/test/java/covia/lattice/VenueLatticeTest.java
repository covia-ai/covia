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
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import convex.lattice.generic.KeyedLattice;

/**
 * Tests for Covia venue lattice CRDT properties and merge semantics.
 *
 * <p>Uses {@link Covia#VENUE}, a {@link KeyedLattice} that defines per-venue
 * state with keyword-keyed fields (assets, jobs, users, storage, auth, did).
 */
public class VenueLatticeTest {

	private KeyedLattice lattice;

	@BeforeEach
	public void setup() {
		lattice = Covia.VENUE;
	}

	// ========== Basic Lattice Properties ==========

	@Test
	public void testZeroValue() {
		Index<Keyword, ACell> zero = lattice.zero();
		assertNotNull(zero, "Zero value should not be null");
		assertTrue(zero.isEmpty(), "Zero value should be an empty Index");
	}

	@Test
	public void testCheckForeign() {
		assertTrue(lattice.checkForeign(lattice.zero()));
		assertTrue(lattice.checkForeign(Index.none()));
		assertEquals(false, lattice.checkForeign(null));
	}

	// ========== Null Handling ==========

	@Test
	public void testMergeWithNull() {
		Index<Keyword, ACell> value = lattice.zero();

		// merge(value, null) should return value
		assertSame(value, lattice.merge(value, null));

		// merge(null, value) should return value
		assertEquals(value, lattice.merge(null, value));

		// merge(null, null) should return null
		assertNull(lattice.merge(null, null));
	}

	// ========== Idempotency ==========

	@Test
	public void testIdempotency() {
		Index<Keyword, ACell> value = createTestVenueState();

		// merge(a, a) == a
		Index<Keyword, ACell> merged = lattice.merge(value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	@Test
	public void testIdempotencyWithZero() {
		Index<Keyword, ACell> zero = lattice.zero();

		// merge(zero, zero) == zero
		assertSame(zero, lattice.merge(zero, zero));
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		Index<Keyword, ACell> v1 = createVenueStateWithAsset("asset1", "data1");
		Index<Keyword, ACell> v2 = createVenueStateWithAsset("asset2", "data2");

		Index<Keyword, ACell> merge12 = lattice.merge(v1, v2);
		Index<Keyword, ACell> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	@Test
	public void testCommutativityWithJobs() {
		Index<Keyword, ACell> v1 = createVenueStateWithJob("job1", "PENDING", 1000L);
		Index<Keyword, ACell> v2 = createVenueStateWithJob("job2", "COMPLETE", 2000L);

		Index<Keyword, ACell> merge12 = lattice.merge(v1, v2);
		Index<Keyword, ACell> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative for jobs");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		Index<Keyword, ACell> v1 = createVenueStateWithAsset("asset1", "data1");
		Index<Keyword, ACell> v2 = createVenueStateWithAsset("asset2", "data2");
		Index<Keyword, ACell> v3 = createVenueStateWithAsset("asset3", "data3");

		// merge(merge(a,b), c) == merge(a, merge(b,c))
		Index<Keyword, ACell> left = lattice.merge(lattice.merge(v1, v2), v3);
		Index<Keyword, ACell> right = lattice.merge(v1, lattice.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		Index<Keyword, ACell> value = createTestVenueState();
		Index<Keyword, ACell> zero = lattice.zero();

		// merge(value, zero) == value
		assertEquals(value, lattice.merge(value, zero));

		// merge(zero, value) == value
		assertEquals(value, lattice.merge(zero, value));
	}

	// ========== Assets Merge (Union) ==========

	@Test
	public void testAssetsMergeUnion() {
		Index<Keyword, ACell> v1 = createVenueStateWithAsset("0x1111", "asset1");
		Index<Keyword, ACell> v2 = createVenueStateWithAsset("0x2222", "asset2");

		Index<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> assets = (AMap<ACell, ACell>) merged.get(Covia.ASSETS);

		assertEquals(2, assets.count(), "Merged assets should contain both entries");
		assertNotNull(assets.get(Strings.create("0x1111")));
		assertNotNull(assets.get(Strings.create("0x2222")));
	}

	@Test
	public void testAssetsMergeSameKey() {
		// Same key should keep the first value (content-addressed means same ID = same content)
		Index<Keyword, ACell> v1 = createVenueStateWithAsset("0x1111", "data");
		Index<Keyword, ACell> v2 = createVenueStateWithAsset("0x1111", "data");

		Index<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> assets = (AMap<ACell, ACell>) merged.get(Covia.ASSETS);

		assertEquals(1, assets.count(), "Same key should not duplicate");
	}

	// ========== Jobs Merge (Timestamp-based) ==========

	@Test
	public void testJobsMergeNewerWins() {
		Index<Keyword, ACell> v1 = createVenueStateWithJob("job1", "PENDING", 1000L);
		Index<Keyword, ACell> v2 = createVenueStateWithJob("job1", "COMPLETE", 2000L);

		Index<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> jobs = (AMap<ACell, ACell>) merged.get(Covia.JOBS);
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> job = (AMap<ACell, ACell>) jobs.get(Strings.create("job1"));

		assertEquals(Strings.create("COMPLETE"), job.get(Strings.create("status")),
				"Newer timestamp should win");
	}

	@Test
	public void testJobsMergeOlderLoses() {
		// v1 has newer timestamp
		Index<Keyword, ACell> v1 = createVenueStateWithJob("job1", "COMPLETE", 2000L);
		Index<Keyword, ACell> v2 = createVenueStateWithJob("job1", "PENDING", 1000L);

		Index<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> jobs = (AMap<ACell, ACell>) merged.get(Covia.JOBS);
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> job = (AMap<ACell, ACell>) jobs.get(Strings.create("job1"));

		assertEquals(Strings.create("COMPLETE"), job.get(Strings.create("status")),
				"Newer timestamp should win even when merged in reverse order");
	}

	@Test
	public void testJobsMergeDifferentJobs() {
		Index<Keyword, ACell> v1 = createVenueStateWithJob("job1", "PENDING", 1000L);
		Index<Keyword, ACell> v2 = createVenueStateWithJob("job2", "COMPLETE", 2000L);

		Index<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> jobs = (AMap<ACell, ACell>) merged.get(Covia.JOBS);

		assertEquals(2, jobs.count(), "Different jobs should both be present");
	}

	// ========== Path Navigation ==========

	@Test
	public void testPathNavigation() {
		ALattice<ACell> assetsLattice = Covia.VENUE.path(Covia.ASSETS);
		ALattice<ACell> jobsLattice = Covia.VENUE.path(Covia.JOBS);

		assertNotNull(assetsLattice, "Should return assets lattice");
		assertNotNull(jobsLattice, "Should return jobs lattice");

		// Unknown key should return null
		assertNull(Covia.VENUE.path(Keyword.intern("unknown")));
	}

	@Test
	public void testPathSelf() {
		assertSame(lattice, lattice.path(), "Empty path should return self");
	}

	// ========== Merge Stability ==========

	@Test
	public void testMergeStability() {
		Index<Keyword, ACell> v1 = createTestVenueState();
		Index<Keyword, ACell> v2 = createTestVenueState();

		Index<Keyword, ACell> merged = lattice.merge(v1, v2);

		// Merging with the merge result should be stable
		assertEquals(merged, lattice.merge(v1, merged));
		assertEquals(merged, lattice.merge(merged, v1));
		assertEquals(merged, lattice.merge(v2, merged));
		assertEquals(merged, lattice.merge(merged, v2));
	}

	// ========== Helper Methods ==========

	private Index<Keyword, ACell> createTestVenueState() {
		return Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(
				Strings.create("0xabc123"), Maps.of(
					Strings.create("name"), Strings.create("Test Asset")
				)
			))
			.assoc(Covia.JOBS, Index.of(
				Strings.create("job123"), Maps.of(
					Strings.create("status"), Strings.create("COMPLETE"),
					Strings.create("updated"), CVMLong.create(1000L)
				)
			))
			.assoc(Covia.DID, Strings.create("did:key:test"));
	}

	private Index<Keyword, ACell> createVenueStateWithAsset(String assetId, String name) {
		return Covia.VENUE.zero()
			.assoc(Covia.ASSETS, Index.of(
				Strings.create(assetId), Maps.of(
					Strings.create("name"), Strings.create(name)
				)
			));
	}

	private Index<Keyword, ACell> createVenueStateWithJob(String jobId, String status, long timestamp) {
		return Covia.VENUE.zero()
			.assoc(Covia.JOBS, Index.of(
				Strings.create(jobId), Maps.of(
					Strings.create("status"), Strings.create(status),
					Strings.create("updated"), CVMLong.create(timestamp)
				)
			));
	}
}
