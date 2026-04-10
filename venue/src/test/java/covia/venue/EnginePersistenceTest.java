package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.store.AStore;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.venue.server.VenueServer;

/**
 * Tests for Engine persistence semantics — sweep, flush, close ordering.
 *
 * <p>Each test constructs a venue with a temp Etch store, exercises the
 * Engine.flush() / Engine.close() / persistence-sweep contract, and (where
 * relevant) restarts a fresh venue against the same store to verify that
 * writes physically survived to disk.</p>
 *
 * <p>See {@code venue/docs/PERSISTENCE.md} for the design these tests
 * verify. The contract being tested:</p>
 *
 * <ul>
 *   <li>{@link Engine#flush()} is synchronous: after it returns, the current
 *       venueState write set is on disk.</li>
 *   <li>{@link Engine#close()} runs a final flush before nodeServer.close()
 *       so the venueState fork's writes are merged into the root before
 *       the existing graceful drain reads from it.</li>
 *   <li>The persistence sweep daemon thread fires periodically while the
 *       engine is running, automatically syncing venueState into the root.</li>
 * </ul>
 *
 * <p><b>Determinism.</b> These tests use {@link CountDownLatch} and direct
 * post-condition checks rather than {@code Thread.sleep}. The only timing
 * dependency is {@code latch.await(timeout)} which is bounded and
 * deterministic given a working implementation.</p>
 */
public class EnginePersistenceTest {

	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlicePersistTest");
	private static final AString BOB_DID   = Strings.create("did:key:z6MkBobPersistTest");

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> testConfig(String storePath, String seedHex) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Engine Persistence Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(seedHex),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	private static File newTempEtch() throws Exception {
		File f = File.createTempFile("engine-persist-", ".etch");
		f.delete();
		f.deleteOnExit();
		return f;
	}

	/**
	 * Reads a user record from the persisted store. Navigates the venue path:
	 * {@code root → :grid → :venues → <accountKey> → :value → :user-data → <DID>}.
	 *
	 * @return the user record, or null if not present
	 */
	private static ACell userRecordInStore(AStore store, AccountKey accountKey, AString did) {
		ACell rootData;
		try {
			rootData = store.getRootData();
		} catch (java.io.IOException e) {
			throw new RuntimeException("Failed to read store root data", e);
		}
		if (rootData == null) return null;
		return RT.getIn(rootData,
			Covia.GRID,
			Covia.VENUES,
			accountKey,
			Keywords.VALUE,
			Covia.USER_DATA,
			did);
	}

	// ========================================================================
	// Test 1 — Engine.flush() is synchronous: after it returns, the venueState
	// write set is on disk in the etch store.
	// ========================================================================
	@Test
	public void testFlushIsSynchronous() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(1001);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		VenueServer server = VenueServer.launch(config);
		try {
			Engine engine = server.getEngine();
			AccountKey ak = engine.getAccountKey();

			// Write through venueState — creates a user record in the fork
			engine.getVenueState().users().ensure(ALICE_DID);

			// Synchronous barrier — must return only after the write is on disk
			engine.flush();

			// After flush: the etch root data MUST contain Alice's user record
			ACell aliceRecord = userRecordInStore(server.getStore(), ak, ALICE_DID);
			assertNotNull(aliceRecord,
				"Alice's user record must be in the etch root data after flush — flush is supposed to be synchronous");
		} finally {
			server.close();
		}
	}

	// ========================================================================
	// Test 2 — Engine.close() runs a final flush BEFORE nodeServer.close(),
	// so writes survive even if the caller never calls flush() explicitly.
	// This is the bug we discovered in the AP demo session.
	// ========================================================================
	@Test
	public void testCloseDoesFinalFlush() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(1002);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		AccountKey expectedKey = venueKey.getAccountKey();

		// Phase 1: launch venue, write through venueState, close (no manual flush)
		{
			VenueServer server = VenueServer.launch(config);
			Engine engine = server.getEngine();

			engine.getVenueState().users().ensure(BOB_DID);

			// Close WITHOUT calling flush() — the close should do its own
			// final flush so the write survives anyway.
			server.close();
		}

		// Phase 2: launch a fresh venue against the same store
		{
			VenueServer server = VenueServer.launch(config);
			try {
				// Bob's user record must be present after restart
				ACell bobRecord = userRecordInStore(server.getStore(), expectedKey, BOB_DID);
				assertNotNull(bobRecord,
					"Bob's user record must survive close+restart — Engine.close() should do a final flush");
			} finally {
				server.close();
			}
		}
	}

	// ========================================================================
	// Test 3 — Background sweep daemon eventually fires venueState.sync()
	// + lattice.sync() automatically, without any explicit flush() call.
	// ========================================================================
	@Test
	public void testSweepFiresPeriodically() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(1003);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		VenueServer server = VenueServer.launch(config);
		try {
			Engine engine = server.getEngine();
			AccountKey ak = engine.getAccountKey();

			// Sweep observability: write through venueState without calling flush().
			// If the sweep daemon is running, the value will eventually appear in
			// the etch store via the sweep's venueState.sync() + lattice.sync()
			// → propagator path.
			engine.getVenueState().users().ensure(ALICE_DID);

			// Poll the store with a bounded timeout. With the default 100ms sweep
			// interval, the value should appear within a few hundred ms. We
			// poll with Thread.yield() rather than sleep — bounded by the
			// 5-second deadline.
			long deadline = System.currentTimeMillis() + 5_000;
			ACell aliceRecord = null;
			while (System.currentTimeMillis() < deadline) {
				aliceRecord = userRecordInStore(server.getStore(), ak, ALICE_DID);
				if (aliceRecord != null) break;
				Thread.yield();
			}

			assertNotNull(aliceRecord,
				"Sweep daemon must propagate venueState writes to the etch store within 5s — "
				+ "either the daemon isn't running or the sweep doesn't include lattice.sync()");
		} finally {
			server.close();
		}
	}

	// ========================================================================
	// Test 4 — Engine.close() stops the sweep daemon cleanly within a bounded
	// time. We verify by measuring close() latency rather than counting
	// threads (which is flaky in a multi-test JVM with leftover daemons).
	// ========================================================================
	@Test
	public void testCloseStopsSweepDaemon() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(1004);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		VenueServer server = VenueServer.launch(config);

		// Verify that AT LEAST one persistence sweep daemon is alive while
		// the engine is running. We can't pin a specific thread to this engine
		// without test hooks, so we just check the named prefix exists.
		assertTrue(countThreadsByNamePrefix("covia-persistence-sweep") >= 1,
			"Sweep daemon thread should be running while engine is alive");

		// Time the close call. If shutdownNow + awaitTermination is working,
		// close should return well under the 2s awaitTermination ceiling.
		long t0 = System.nanoTime();
		server.close();
		long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

		assertTrue(elapsedMs < 3_000,
			"Engine.close() should return within 3s — actual: " + elapsedMs + "ms");
	}

	// ========================================================================
	// Test 5 — Multiple writes within a single sweep window are batched into
	// one persist (the LatticePropagator's LatestUpdateQueue coalesces).
	// We can't directly observe the queue, but we can verify that all writes
	// in a burst end up on disk after a single flush.
	// ========================================================================
	@Test
	public void testFlushPersistsBurstOfWrites() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(1005);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		VenueServer server = VenueServer.launch(config);
		try {
			Engine engine = server.getEngine();
			AccountKey ak = engine.getAccountKey();

			// Write 10 different users in rapid succession
			for (int i = 0; i < 10; i++) {
				engine.getVenueState().users().ensure(
					Strings.create("did:key:z6Mkburst" + i));
			}

			engine.flush();

			for (int i = 0; i < 10; i++) {
				AString did = Strings.create("did:key:z6Mkburst" + i);
				ACell userRec = userRecordInStore(server.getStore(), ak, did);
				assertNotNull(userRec, "User " + i + " must survive a single flush");
			}
		} finally {
			server.close();
		}
	}

	// ========================================================================
	// Test 6 — Calling flush() on an idle venue (no pending writes) is a
	// safe no-op. Should not throw, should not corrupt state.
	// ========================================================================
	@Test
	public void testFlushOnIdleVenueIsNoop() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(1006);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		VenueServer server = VenueServer.launch(config);
		try {
			Engine engine = server.getEngine();
			AccountKey ak = engine.getAccountKey();

			// Three flushes in a row on an idle venue should all succeed
			engine.flush();
			engine.flush();
			engine.flush();

			// Engine should still be functional after the no-op flushes
			engine.getVenueState().users().ensure(ALICE_DID);
			engine.flush();

			assertNotNull(userRecordInStore(server.getStore(), ak, ALICE_DID));
		} finally {
			server.close();
		}
	}

	// ========================================================================
	// Helpers
	// ========================================================================

	private static int countThreadsByNamePrefix(String prefix) {
		Thread[] threads = new Thread[Thread.activeCount() * 2];
		int n = Thread.enumerate(threads);
		int count = 0;
		for (int i = 0; i < n; i++) {
			Thread t = threads[i];
			if (t != null && t.getName() != null && t.getName().startsWith(prefix)) {
				count++;
			}
		}
		return count;
	}
}
