package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Covia;

/**
 * Tests for the cursor-based VenueState application API.
 * Follows the convex-social SocialAppTest pattern: standalone wrappers,
 * no raw lattice data construction.
 */
public class VenueStateTest {

	// ========== Factory Methods ==========

	@Test
	public void testCreate() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		assertNotNull(vs);
		assertEquals(kp.getAccountKey(), vs.getOwnerKey());
		assertNotNull(vs.cursor());
	}

	@Test
	public void testFromRoot() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.createLattice(Covia.ROOT);
		root.withContext(convex.lattice.LatticeContext.create(null, kp));

		VenueState vs = VenueState.fromRoot(root, kp.getAccountKey());

		assertNotNull(vs);
		assertEquals(kp.getAccountKey(), vs.getOwnerKey());
	}

	// ========== Asset Store ==========

	@Test
	public void testAssetStoreRoundTrip() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AssetStore assets = vs.assets();

		assertEquals(0, assets.count());

		AString meta = Strings.create("{\"name\":\"Test Asset\"}");
		Hash id = assets.store(meta, null);

		assertNotNull(id);
		assertEquals(1, assets.count());

		AVector<?> record = assets.getRecord(id);
		assertNotNull(record);
		assertEquals(meta, record.get(AssetStore.POS_JSON));
		assertNull(record.get(AssetStore.POS_CONTENT));
	}

	@Test
	public void testAssetStoreWithContent() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AssetStore assets = vs.assets();

		AString meta = Strings.create("{\"name\":\"With Content\"}");
		ACell content = Strings.create("binary-data-here");
		Hash id = assets.store(meta, content);

		AVector<?> record = assets.getRecord(id);
		assertEquals(content, record.get(AssetStore.POS_CONTENT));
	}

	@Test
	public void testAssetStoreMultiple() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AssetStore assets = vs.assets();

		assets.store(Strings.create("{\"name\":\"Asset 1\"}"), null);
		assets.store(Strings.create("{\"name\":\"Asset 2\"}"), null);
		assets.store(Strings.create("{\"name\":\"Asset 3\"}"), null);

		assertEquals(3, assets.count());
	}

	@Test
	public void testAssetStoreIdempotent() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AssetStore assets = vs.assets();

		AString meta = Strings.create("{\"name\":\"Same Asset\"}");
		Hash id1 = assets.store(meta, null);
		Hash id2 = assets.store(meta, null);

		assertEquals(id1, id2, "Same metadata should produce same ID");
		assertEquals(1, assets.count(), "Duplicate store should not create new entry");
	}

	@Test
	public void testAssetStoreInvalidJson() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		// Invalid JSON throws ParseException; valid JSON that isn't an object throws IAE
		assertThrows(Exception.class,
			() -> vs.assets().store(Strings.create("not json"), null));
		assertThrows(IllegalArgumentException.class,
			() -> vs.assets().store(Strings.create("[1,2,3]"), null));
	}

	@Test
	public void testAssetStoreGetAll() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AssetStore assets = vs.assets();

		Hash id1 = assets.store(Strings.create("{\"name\":\"A\"}"), null);
		Hash id2 = assets.store(Strings.create("{\"name\":\"B\"}"), null);

		AMap<ABlob, AVector<?>> all = assets.getAll();
		assertNotNull(all);
		assertEquals(2, all.count());
		assertNotNull(all.get(id1));
		assertNotNull(all.get(id2));
	}

	// ========== Job Store ==========

	@Test
	public void testJobStoreRoundTrip() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		JobStore jobs = vs.jobs();

		assertEquals(0, jobs.count());

		AString jobID = Strings.create("job001");
		AMap<AString, ACell> record = Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L));
		jobs.persist(jobID, record);

		assertEquals(1, jobs.count());

		AMap<AString, ACell> retrieved = jobs.get(jobID);
		assertNotNull(retrieved);
		assertEquals(Status.PENDING, retrieved.get(Fields.STATUS));
	}

	@Test
	public void testJobStoreUpdate() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		JobStore jobs = vs.jobs();

		AString jobID = Strings.create("job001");
		jobs.persist(jobID, Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L)));

		// Update same job
		jobs.persist(jobID, Maps.of(
			Fields.STATUS, Status.COMPLETE,
			Fields.UPDATED, CVMLong.create(2000L)));

		assertEquals(1, jobs.count(), "Should still be one job");

		AMap<AString, ACell> retrieved = jobs.get(jobID);
		assertEquals(Status.COMPLETE, retrieved.get(Fields.STATUS));
	}

	@Test
	public void testJobStoreMultiple() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		JobStore jobs = vs.jobs();

		jobs.persist(Strings.create("job001"), Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L)));
		jobs.persist(Strings.create("job002"), Maps.of(
			Fields.STATUS, Status.COMPLETE,
			Fields.UPDATED, CVMLong.create(2000L)));

		assertEquals(2, jobs.count());
	}

	@Test
	public void testJobStoreGetAll() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		JobStore jobs = vs.jobs();

		jobs.persist(Strings.create("job001"), Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L)));

		Index<AString, ACell> all = jobs.getAll();
		assertNotNull(all);
		assertEquals(1, all.count());
	}

	@Test
	public void testJobStoreGetNonExistent() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		assertNull(vs.jobs().get(Strings.create("nonexistent")));
	}

	// ========== Child Cursor Accessors ==========

	@Test
	public void testChildCursorAccessors() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		assertNotNull(vs.assets().cursor(), "Assets cursor should be available");
		assertNotNull(vs.jobs().cursor(), "Jobs cursor should be available");
		assertNotNull(vs.usersCursor(), "Users cursor should be available");
		assertNotNull(vs.authCursor(), "Auth cursor should be available");
		assertNotNull(vs.storageCursor(), "Storage cursor should be available");
	}

	// ========== State Bootstrapping ==========

	@Test
	public void testBootstrapping() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		// Initially null (no venue state set yet)
		assertNull(vs.get());

		// Bootstrap venue state
		ACell zero = Covia.VENUE.zero().assoc(Covia.DID, Strings.create("did:key:test"));
		vs.set(zero);

		assertNotNull(vs.get());
	}

	// ========== Write Propagation ==========

	@Test
	public void testWritesPropagateToRoot() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.createLattice(Covia.ROOT);
		root.withContext(convex.lattice.LatticeContext.create(null, kp));

		VenueState vs = VenueState.fromRoot(root, kp.getAccountKey());
		vs.assets().store(Strings.create("{\"name\":\"Propagated\"}"), null);

		// Root cursor should have data
		ACell rootValue = root.get();
		assertNotNull(rootValue, "Root cursor should contain data after store");
	}

	// ========== Fork / Sync ==========

	@Test
	public void testForkWritesNotVisibleBeforeSync() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.createLattice(Covia.ROOT);
		root.withContext(convex.lattice.LatticeContext.create(null, kp));

		VenueState connected = VenueState.fromRoot(root, kp.getAccountKey());
		ACell rootBefore = root.get();

		// Fork and write to the fork
		VenueState forked = connected.fork();
		forked.assets().store(Strings.create("{\"name\":\"Invisible\"}"), null);

		// Forked writes should be visible through the fork
		assertEquals(1, forked.assets().count());

		// Parent root cursor should be unchanged (no leakage before sync)
		ACell rootAfter = root.get();
		assertEquals(rootBefore, rootAfter,
			"Parent cursor must not change before sync");
	}

	@Test
	public void testForkSyncPropagates() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.createLattice(Covia.ROOT);
		root.withContext(convex.lattice.LatticeContext.create(null, kp));

		VenueState connected = VenueState.fromRoot(root, kp.getAccountKey());
		VenueState forked = connected.fork();

		// Write asset and job through the fork
		Hash assetId = forked.assets().store(
			Strings.create("{\"name\":\"Synced Asset\"}"), null);
		forked.jobs().persist(Strings.create("job-sync-01"), Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L)));

		// Sync merges local changes into the parent (single sign)
		forked.sync();

		// Verify changes visible through the connected VenueState
		assertNotNull(connected.assets().getRecord(assetId),
			"Asset should be visible through connected cursor after sync");
		assertNotNull(connected.jobs().get(Strings.create("job-sync-01")),
			"Job should be visible through connected cursor after sync");
	}

	@Test
	public void testForkMultipleWritesSingleSync() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.createLattice(Covia.ROOT);
		root.withContext(convex.lattice.LatticeContext.create(null, kp));

		VenueState connected = VenueState.fromRoot(root, kp.getAccountKey());
		VenueState forked = connected.fork();

		// Multiple asset writes
		forked.assets().store(Strings.create("{\"name\":\"Batch A\"}"), null);
		forked.assets().store(Strings.create("{\"name\":\"Batch B\"}"), null);
		forked.assets().store(Strings.create("{\"name\":\"Batch C\"}"), null);

		// Multiple job writes
		forked.jobs().persist(Strings.create("batch-job-01"), Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L)));
		forked.jobs().persist(Strings.create("batch-job-02"), Maps.of(
			Fields.STATUS, Status.COMPLETE,
			Fields.UPDATED, CVMLong.create(2000L)));

		// Single sync propagates all 5 writes
		forked.sync();

		assertEquals(3, connected.assets().count(),
			"All 3 assets should be visible after single sync");
		assertEquals(2, connected.jobs().count(),
			"All 2 jobs should be visible after single sync");
	}

	// ========== User State ==========

	@Test
	public void testUserReturnsNullForNonExistent() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		assertNull(vs.user(Strings.create("did:key:zUnknown")),
			"user() should return null when no data exists for that DID");
	}

	@Test
	public void testEnsureUserCreatesState() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AString did = Strings.create("did:key:zAlice");

		// ensureUser creates the user state
		UserState alice = vs.ensureUser(did);
		assertNotNull(alice);
		assertEquals(did, alice.getDID());
		assertNotNull(alice.get(), "User state should be initialised");

		// Subsequent user() call should also return non-null
		UserState aliceAgain = vs.user(did);
		assertNotNull(aliceAgain, "user() should return non-null after ensureUser");
	}

	@Test
	public void testUserDataIsolation() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		AString aliceDID = Strings.create("did:key:zAlice");
		AString bobDID = Strings.create("did:key:zBob");

		UserState alice = vs.ensureUser(aliceDID);
		UserState bob = vs.ensureUser(bobDID);

		// Alice writes a job reference
		alice.jobs().persist(Strings.create("alice-job-01"), Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L)));

		// Bob writes a job reference
		bob.jobs().persist(Strings.create("bob-job-01"), Maps.of(
			Fields.STATUS, Status.COMPLETE,
			Fields.UPDATED, CVMLong.create(2000L)));

		// Each sees only their own data
		assertEquals(1, alice.jobs().count(), "Alice should see only her job");
		assertEquals(1, bob.jobs().count(), "Bob should see only his job");
		assertNotNull(alice.jobs().get(Strings.create("alice-job-01")));
		assertNull(alice.jobs().get(Strings.create("bob-job-01")));
	}

	@Test
	public void testCapsCursorAccessor() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);

		assertNotNull(vs.capsCursor(), "Caps cursor should be available");
	}

	// ========== Fork / Sync ==========

	@Test
	public void testForkSyncIdempotent() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.createLattice(Covia.ROOT);
		root.withContext(convex.lattice.LatticeContext.create(null, kp));

		VenueState connected = VenueState.fromRoot(root, kp.getAccountKey());
		VenueState forked = connected.fork();

		forked.assets().store(Strings.create("{\"name\":\"Once\"}"), null);
		forked.sync();

		ACell stateAfterFirstSync = root.get();

		// Second sync with no new writes should be a no-op
		forked.sync();

		ACell stateAfterSecondSync = root.get();
		assertEquals(stateAfterFirstSync, stateAfterSecondSync,
			"Second sync without new writes should not change state");
	}
}
