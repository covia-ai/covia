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
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Tests for VenueLattice CRDT properties and merge semantics.
 */
public class VenueLatticeTest {

	private VenueLattice lattice;

	@BeforeEach
	public void setup() {
		lattice = VenueLattice.create();
	}

	// ========== Basic Lattice Properties ==========

	@Test
	public void testSingletonInstance() {
		VenueLattice l1 = VenueLattice.create();
		VenueLattice l2 = VenueLattice.create();
		assertSame(l1, l2, "VenueLattice should be a singleton");
		assertSame(VenueLattice.INSTANCE, l1);
	}

	@Test
	public void testZeroValue() {
		AMap<Keyword, ACell> zero = lattice.zero();
		assertNotNull(zero, "Zero value should not be null");
		assertTrue(zero.containsKey(VenueLattice.ASSETS), "Zero should contain :assets");
		assertTrue(zero.containsKey(VenueLattice.JOBS), "Zero should contain :jobs");

		// All fields should be empty maps
		assertEquals(Maps.empty(), zero.get(VenueLattice.ASSETS));
		assertEquals(Maps.empty(), zero.get(VenueLattice.JOBS));
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
		AMap<Keyword, ACell> value = createTestVenueState();

		// merge(a, a) == a
		AMap<Keyword, ACell> merged = lattice.merge(value, value);
		assertSame(value, merged, "Merge with self should return same instance");
	}

	@Test
	public void testIdempotencyWithZero() {
		AMap<Keyword, ACell> zero = lattice.zero();

		// merge(zero, zero) == zero
		assertSame(zero, lattice.merge(zero, zero));
	}

	// ========== Commutativity ==========

	@Test
	public void testCommutativity() {
		AMap<Keyword, ACell> v1 = createVenueStateWithAsset("asset1", "data1");
		AMap<Keyword, ACell> v2 = createVenueStateWithAsset("asset2", "data2");

		AMap<Keyword, ACell> merge12 = lattice.merge(v1, v2);
		AMap<Keyword, ACell> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative");
	}

	@Test
	public void testCommutativityWithJobs() {
		AMap<Keyword, ACell> v1 = createVenueStateWithJob("job1", "PENDING", 1000L);
		AMap<Keyword, ACell> v2 = createVenueStateWithJob("job2", "COMPLETE", 2000L);

		AMap<Keyword, ACell> merge12 = lattice.merge(v1, v2);
		AMap<Keyword, ACell> merge21 = lattice.merge(v2, v1);

		assertEquals(merge12, merge21, "Merge should be commutative for jobs");
	}

	// ========== Associativity ==========

	@Test
	public void testAssociativity() {
		AMap<Keyword, ACell> v1 = createVenueStateWithAsset("asset1", "data1");
		AMap<Keyword, ACell> v2 = createVenueStateWithAsset("asset2", "data2");
		AMap<Keyword, ACell> v3 = createVenueStateWithAsset("asset3", "data3");

		// merge(merge(a,b), c) == merge(a, merge(b,c))
		AMap<Keyword, ACell> left = lattice.merge(lattice.merge(v1, v2), v3);
		AMap<Keyword, ACell> right = lattice.merge(v1, lattice.merge(v2, v3));

		assertEquals(left, right, "Merge should be associative");
	}

	// ========== Zero Identity ==========

	@Test
	public void testZeroIdentity() {
		AMap<Keyword, ACell> value = createTestVenueState();
		AMap<Keyword, ACell> zero = lattice.zero();

		// merge(value, zero) == value
		assertEquals(value, lattice.merge(value, zero));

		// merge(zero, value) == value
		assertEquals(value, lattice.merge(zero, value));
	}

	// ========== Assets Merge (Union) ==========

	@Test
	public void testAssetsMergeUnion() {
		AMap<Keyword, ACell> v1 = createVenueStateWithAsset("0x1111", "asset1");
		AMap<Keyword, ACell> v2 = createVenueStateWithAsset("0x2222", "asset2");

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> assets = (AMap<ACell, ACell>) merged.get(VenueLattice.ASSETS);

		assertEquals(2, assets.count(), "Merged assets should contain both entries");
		assertNotNull(assets.get(Strings.create("0x1111")));
		assertNotNull(assets.get(Strings.create("0x2222")));
	}

	@Test
	public void testAssetsMergeSameKey() {
		// Same key should keep the first value (content-addressed means same ID = same content)
		AMap<Keyword, ACell> v1 = createVenueStateWithAsset("0x1111", "data");
		AMap<Keyword, ACell> v2 = createVenueStateWithAsset("0x1111", "data");

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> assets = (AMap<ACell, ACell>) merged.get(VenueLattice.ASSETS);

		assertEquals(1, assets.count(), "Same key should not duplicate");
	}

	// ========== Jobs Merge (Timestamp-based) ==========

	@Test
	public void testJobsMergeNewerWins() {
		AMap<Keyword, ACell> v1 = createVenueStateWithJob("job1", "PENDING", 1000L);
		AMap<Keyword, ACell> v2 = createVenueStateWithJob("job1", "COMPLETE", 2000L);

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> jobs = (AMap<ACell, ACell>) merged.get(VenueLattice.JOBS);
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> job = (AMap<ACell, ACell>) jobs.get(Strings.create("job1"));

		assertEquals(Strings.create("COMPLETE"), job.get(Strings.create("status")),
				"Newer timestamp should win");
	}

	@Test
	public void testJobsMergeOlderLoses() {
		// v1 has newer timestamp
		AMap<Keyword, ACell> v1 = createVenueStateWithJob("job1", "COMPLETE", 2000L);
		AMap<Keyword, ACell> v2 = createVenueStateWithJob("job1", "PENDING", 1000L);

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> jobs = (AMap<ACell, ACell>) merged.get(VenueLattice.JOBS);
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> job = (AMap<ACell, ACell>) jobs.get(Strings.create("job1"));

		assertEquals(Strings.create("COMPLETE"), job.get(Strings.create("status")),
				"Newer timestamp should win even when merged in reverse order");
	}

	@Test
	public void testJobsMergeDifferentJobs() {
		AMap<Keyword, ACell> v1 = createVenueStateWithJob("job1", "PENDING", 1000L);
		AMap<Keyword, ACell> v2 = createVenueStateWithJob("job2", "COMPLETE", 2000L);

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> jobs = (AMap<ACell, ACell>) merged.get(VenueLattice.JOBS);

		assertEquals(2, jobs.count(), "Different jobs should both be present");
	}

	// ========== Path Navigation ==========

	@Test
	public void testPathNavigation() {
		ALattice<ACell> assetsLattice = lattice.path(VenueLattice.ASSETS);
		ALattice<ACell> jobsLattice = lattice.path(VenueLattice.JOBS);

		assertNotNull(assetsLattice, "Should return assets lattice");
		assertNotNull(jobsLattice, "Should return jobs lattice");

		// Unknown key should return null
		assertNull(lattice.path(Keyword.intern("unknown")));
	}

	@Test
	public void testPathSelf() {
		assertSame(lattice, lattice.path(), "Empty path should return self");
	}

	// ========== Merge Stability ==========

	@Test
	public void testMergeStability() {
		AMap<Keyword, ACell> v1 = createTestVenueState();
		AMap<Keyword, ACell> v2 = createTestVenueState();

		AMap<Keyword, ACell> merged = lattice.merge(v1, v2);

		// Merging with the merge result should be stable
		assertEquals(merged, lattice.merge(v1, merged));
		assertEquals(merged, lattice.merge(merged, v1));
		assertEquals(merged, lattice.merge(v2, merged));
		assertEquals(merged, lattice.merge(merged, v2));
	}

	// ========== Helper Methods ==========

	private AMap<Keyword, ACell> createTestVenueState() {
		return Maps.of(
			VenueLattice.ASSETS, Maps.of(
				Strings.create("0xabc123"), Maps.of(
					Strings.create("name"), Strings.create("Test Asset")
				)
			),
			VenueLattice.JOBS, Maps.of(
				Strings.create("job123"), Maps.of(
					Strings.create("status"), Strings.create("COMPLETE"),
					VenueLattice.UPDATED, CVMLong.create(1000L)
				)
			)
		);
	}

	private AMap<Keyword, ACell> createVenueStateWithAsset(String assetId, String name) {
		return Maps.of(
			VenueLattice.ASSETS, Maps.of(
				Strings.create(assetId), Maps.of(
					Strings.create("name"), Strings.create(name)
				)
			),
			VenueLattice.JOBS, Maps.empty()
		);
	}

	private AMap<Keyword, ACell> createVenueStateWithJob(String jobId, String status, long timestamp) {
		return Maps.of(
			VenueLattice.ASSETS, Maps.empty(),
			VenueLattice.JOBS, Maps.of(
				Strings.create(jobId), Maps.of(
					Strings.create("status"), Strings.create(status),
					VenueLattice.UPDATED, CVMLong.create(timestamp)
				)
			)
		);
	}
}
