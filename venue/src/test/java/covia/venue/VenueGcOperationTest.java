package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import convex.etch.Etch;
import covia.adapter.VenueAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.venue.server.VenueServer;

/**
 * Online garbage collection of a running venue's Etch store through
 * {@code venue:gc} (covia#452): venue-operator-only, the venue keeps serving
 * across the cutover, one collection per process, and a plain relaunch adopts
 * the collected file with everything intact.
 *
 * <p>Venue launches are the expensive part: the whole live story runs as one
 * scenario on one store (plus a relaunch), the authority matrix runs on the
 * shared {@link TestEngine} with no venue at all, and the temp-store refusal
 * uses the cheapest venue there is.</p>
 */
public class VenueGcOperationTest {

	private static final String SEED_HEX =
		"7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b7b";
	private static final AString USER = Strings.create("did:key:z6Mk-test-venue-gc");
	private static final String OP = "v/ops/venue/gc";
	private static final AMap<AString, ACell> STATUS = Maps.of("status", CVMBool.TRUE);
	private static final String KEY_HEX = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

	/** Pause the actual Convex cycle after writes redirect, without timing races
	 * or changes to the production host. All venue calls use the real adapter. */
	private static final class PausedSweepStore extends EtchStore {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);

		PausedSweepStore(File file, AMap<AString, ACell> config) throws IOException {
			super(config.get(Config.ETCH) == null ? Etch.create(file)
				: Etch.create(file, new Config(config).getEtchConfig()));
		}

		@Override
		public void transferGC() throws IOException {
			entered.countDown();
			try {
				if (!resume.await(15, TimeUnit.SECONDS)) throw new IOException("Test sweep was not released");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
			super.transferGC();
		}
	}

	private static void writeFile(VenueServer venue, String text) {
		venue.getEngine().jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", "checkpoint", "path", "proof.txt", "content", text),
			RequestContext.of(USER)).awaitResult(5000);
	}

	private static void assertFile(VenueServer venue, String expected) {
		ACell result = venue.getEngine().jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", "checkpoint", "path", "proof.txt"),
			RequestContext.of(USER)).awaitResult(5000);
		assertEquals(Strings.create(expected), RT.getIn(result, "content"));
	}

	@Test
	void onlineCheckpointRestoresPreCycleStateWhileLiveWritesSurvive() throws Exception {
		checkpointRoundTrip(false);
	}

	@Test
	void encryptedOnlineCheckpointRestoresWithOriginalKey() throws Exception {
		checkpointRoundTrip(true);
	}

	private void checkpointRoundTrip(boolean encrypted) throws Exception {
		File dir = TestTemp.dir("venue-gc-checkpoint").toFile();
		File live = new File(dir, "live.etch");
		File backup = new File(dir, "checkpoint.etch");
		AMap<AString, ACell> cfg = config(live.getAbsolutePath());
		if (encrypted) cfg = cfg.assoc(Config.ETCH, Maps.of("version", 3,
			"cipher", "aes-256-ctr", "encryptIndex", true, "key", KEY_HEX));
		PausedSweepStore store = new PausedSweepStore(live, cfg);
		VenueServer venue = VenueServer.launch(cfg, store);
		try {
			write(venue, "w/proof", Strings.create("before"));
			writeFile(venue, "before");
			Engine engine = venue.getEngine();
			// No test-side flush: the maintenance boundary must publish the root.
			Job cycle = engine.jobs().invokeOperation(OP, Maps.of("backupFile", backup.getAbsolutePath()), engine.venueContext());
			assertTrue(store.entered.await(5, TimeUnit.SECONDS));
			assertEquals(CVMBool.TRUE, RT.getIn(gc(engine, engine.venueContext(), STATUS), "inProgress"));
			assertEquals(Strings.create("before"), read(venue, "w/proof"));
			write(venue, "w/proof", Strings.create("during"));
			writeFile(venue, "during");
			engine.flush();
			assertEquals(Strings.create("during"), read(venue, "w/proof"));
			assertFile(venue, "during");
			store.resume.countDown();
			ACell result = cycle.awaitResult(60_000);
			assertEquals(Strings.create(backup.getCanonicalPath()), RT.getIn(result, "backupFile"));
			assertEquals(Strings.create("shutdown"), RT.getIn(result, "backupReadyAt"));
			assertEquals(Strings.create("backupRemoval"), RT.getIn(result, "reclaimedAt"));
			assertTrue(backup.isFile());
			write(venue, "w/after", Strings.create("after"));
			engine.flush();
		} finally {
			store.resume.countDown();
			venue.close();
		}
		// Clean shutdown seals the checkpoint. Restore from an independent copy,
		// never open the retained hard link as a writable live store.
		File restoredFile = new File(dir, "restored.etch");
		Files.copy(backup.toPath(), restoredFile.toPath());
		byte[] checkpointBytes = Files.readAllBytes(backup.toPath());
		VenueServer restored = VenueServer.launch(cfg.assoc(Config.STORE, Strings.create(restoredFile.getAbsolutePath())));
		try {
			assertEquals(Strings.create("before"), read(restored, "w/proof"));
			assertFile(restored, "before");
			assertNull(read(restored, "w/after"));
		} finally {
			restored.close();
		}
		VenueServer reopened = VenueServer.launch(cfg);
		try {
			assertEquals(Strings.create("during"), read(reopened, "w/proof"));
			assertEquals(Strings.create("after"), read(reopened, "w/after"));
			assertFile(reopened, "during");
		} finally {
			reopened.close();
		}
		assertArrayEquals(checkpointBytes, Files.readAllBytes(backup.toPath()),
			"restoring a copy and reopening the live store must not mutate the checkpoint");
	}

	@Test
	void failedBackupCreationRollsBackConcurrentWrites() throws Exception {
		abortedCheckpointRoundTrip(false);
	}

	@Test
	void cancelledCheckpointRollsBackConcurrentWrites() throws Exception {
		abortedCheckpointRoundTrip(true);
	}

	private void abortedCheckpointRoundTrip(boolean cancel) throws Exception {
		File dir = TestTemp.dir("venue-gc-failed-backup").toFile();
		File live = new File(dir, "live.etch");
		File backup = new File(dir, "checkpoint.etch");
		AMap<AString, ACell> cfg = config(live.getAbsolutePath());
		PausedSweepStore store = new PausedSweepStore(live, cfg);
		VenueServer venue = VenueServer.launch(cfg, store);
		try {
			write(venue, "w/proof", Strings.create("before"));
			Engine engine = venue.getEngine();
			Job cycle = engine.jobs().invokeOperation(OP, Maps.of("backupFile", backup.getAbsolutePath()), engine.venueContext());
			assertTrue(store.entered.await(5, TimeUnit.SECONDS));
			write(venue, "w/proof", Strings.create("during"));
			engine.flush();
			// A competing creator wins after preflight. Convex must never replace
			// this file; the failed checkpoint must roll the live writes back safely.
			if (cancel) gc(engine, engine.venueContext(), Maps.of("cancel", true));
			else Files.writeString(backup.toPath(), "do not replace");
			store.resume.countDown();
			assertThrows(JobFailedException.class, () -> cycle.awaitResult(60_000));
			if (cancel) assertFalse(backup.exists(), "an aborted cycle must not publish a checkpoint");
			else assertEquals("do not replace", Files.readString(backup.toPath()));
			assertFalse(store.isGCInProgress());
			assertEquals(CVMBool.FALSE, RT.getIn(gc(engine, engine.venueContext(), STATUS), "completed"));
			assertEquals(Strings.create("during"), read(venue, "w/proof"));
		} finally {
			store.resume.countDown();
			venue.close();
		}
		VenueServer reopened = VenueServer.launch(cfg);
		try {
			assertEquals(Strings.create("during"), read(reopened, "w/proof"));
		} finally {
			reopened.close();
		}
	}

	@Test
	void checkpointInputCannotSilentlyBecomeAStatusRequest() {
		Engine engine = TestEngine.ENGINE;
		for (String action : new String[] {"status", "cancel"}) {
			assertEquals(VenueAdapter.BACKUP_WITH_STATUS, gcFails(engine, engine.venueContext(),
				Maps.of("backupFile", "checkpoint.etch", action, true)));
		}
		assertEquals(VenueAdapter.INVALID_BACKUP_FILE, gcFails(engine, engine.venueContext(),
			Maps.of("backupFile", " ")));
		assertEquals(VenueAdapter.INVALID_RETENTION, gcFails(engine, engine.venueContext(),
			Maps.of("retainSuperseded", "true")));
		assertEquals(VenueAdapter.BACKUP_RETENTION_DISABLED, gcFails(engine, engine.venueContext(),
			Maps.of("backupFile", "checkpoint.etch", "retainSuperseded", false)));
	}

	@Test
	void onlineRetentionInheritsConfigAndCanBeOverridden() throws Exception {
		for (Boolean override : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE}) {
			File file = new File(TestTemp.dir("gc-retention-policy").toFile(), "venue.etch");
			AMap<AString, ACell> cfg = config(file.getAbsolutePath()).assoc(Config.ETCH,
				Maps.of("gc", Maps.of("retainSuperseded", override != Boolean.TRUE)));
			VenueServer venue = VenueServer.launch(cfg);
			try {
				write(venue, "w/proof", Strings.create("retained"));
				ACell result = gc(venue.getEngine(), venue.getEngine().venueContext(),
					override == null ? Maps.empty() : Maps.of("retainSuperseded", override));
				ACell backup = RT.getIn(result, "backupFile");
				if (Boolean.FALSE.equals(override)) assertNull(backup);
				else { assertNotNull(backup); assertTrue(new File(backup.toString()).isFile()); }
			} finally { venue.close(); }
		}
	}

	private static AMap<AString, ACell> config(String store) {
		return Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create(store),
			Config.SEED, Strings.create(SEED_HEX),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
	}

	private static void write(VenueServer v, String path, ACell value) throws Exception {
		v.getEngine().jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), RequestContext.of(USER))
			.get(5, TimeUnit.SECONDS);
	}

	private static ACell read(VenueServer v, String path) throws Exception {
		ACell read = v.getEngine().jobs().invokeInternal("v/ops/covia/read",
			Maps.of(Fields.PATH, path), RequestContext.of(USER)).get(5, TimeUnit.SECONDS);
		return RT.getIn(read, Fields.VALUE);
	}

	private static ACell gc(Engine engine, RequestContext ctx, AMap<AString, ACell> input) {
		return engine.jobs().invokeOperation(OP, input, ctx).awaitResult(60_000);
	}

	private static String gcFails(Engine engine, RequestContext ctx, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(OP, input, ctx);
		assertThrows(JobFailedException.class, () -> job.awaitResult(60_000));
		return job.getErrorMessage();
	}

	/** Every file beside the store with its size — what recovery had to work with. */
	private static String describe(File dir) {
		StringBuilder sb = new StringBuilder();
		File[] files = dir.listFiles();
		if (files != null) {
			java.util.Arrays.sort(files);
			for (File f : files) sb.append(f.getName()).append('=').append(f.length()).append(' ');
		}
		return sb.toString().trim();
	}

	private static long asLong(ACell map, String key) {
		CVMLong v = RT.ensureLong(RT.getIn(map, key));
		assertNotNull(v, key);
		return v.longValue();
	}

	@Test
	public void collectsWhileServingThenAdoptsOnRelaunch() throws Exception {
		File file = new File(TestTemp.dir("venue-gc-online").toFile(), "venue.etch");
		String storePath = file.getAbsolutePath().replace('\\', '/');
		long garbage = EtchGcOnStartTest.addGarbage(file);

		long before = 0;
		VenueServer v = VenueServer.launch(config(storePath));
		try {
			Engine engine = v.getEngine();
			RequestContext operator = engine.venueContext();
			write(v, "w/before-gc", Strings.create("before"));

			// Before any cycle: status reports the uncollected file; cancel is a
			// no-op; restart:true is refused up front (no MainVenue process
			// control), so no collection ran for a restart that could not follow.
			ACell status = gc(engine, operator, STATUS);
			assertEquals(CVMBool.FALSE, RT.getIn(status, "inProgress"));
			assertEquals(CVMBool.FALSE, RT.getIn(status, "completed"));
			assertTrue(asLong(status, "bytes") >= garbage);
			assertEquals(CVMBool.FALSE, RT.getIn(gc(engine, operator, Maps.of("cancel", CVMBool.TRUE)), "inProgress"));
			String noRestart = gcFails(engine, operator, Maps.of("restart", CVMBool.TRUE));
			assertTrue(noRestart.contains("restart"), noRestart);
			assertEquals(CVMBool.FALSE, RT.getIn(gc(engine, operator, STATUS), "completed"));

			// The collection itself. Persist the live root first: the venue writes
			// lazily on its 100 ms sweep, so without this the sizes below would
			// compare a file that does not yet hold the ~1.7 MB operation catalog
			// against one that does (a fast sequential run collected before the
			// first sweep and the relaunched store came out bigger, not smaller).
			engine.flush();
			ACell result = gc(engine, operator, Maps.empty());
			before = asLong(result, "bytesBefore");
			long after = asLong(result, "bytesAfter");
			assertTrue(after < before, "collected file must be smaller: " + after + " vs " + before);
			assertEquals(before - after, asLong(result, "reclaimed"));
			assertTrue(before - after > garbage / 2,
				"the garbage must be what went: reclaimed " + (before - after) + " of " + garbage);
			assertEquals(Strings.create("shutdown"), RT.getIn(result, "reclaimedAt"));
			assertNotNull(RT.getIn(result, "collectedFile"));

			// The venue keeps serving on the old handle: cycle-era data and new
			// writes resolve, and durability barriers still work.
			assertEquals(Strings.create("before"), read(v, "w/before-gc"));
			write(v, "w/after-gc", Strings.create("after"));
			engine.flush();
			assertEquals(Strings.create("after"), read(v, "w/after-gc"));

			ACell done = gc(engine, operator, STATUS);
			assertEquals(CVMBool.TRUE, RT.getIn(done, "completed"));
			assertEquals(CVMBool.FALSE, RT.getIn(done, "inProgress"));
			assertTrue(asLong(done, "collectedBytes") >= after);

			// One collection per process: the successor cannot be threaded into
			// the running node, so a second cycle needs a restart first.
			String again = gcFails(engine, operator, Maps.empty());
			assertTrue(again.contains("restart"), again);
		} finally {
			v.close();
		}

		// Both handles closed cleanly; a plain relaunch adopts the collected
		// file (or opens it directly while the old one is pinned) with
		// everything written before and after the cycle intact.
		VenueServer relaunched = VenueServer.launch(config(storePath));
		try {
			assertEquals(Strings.create("before"), read(relaunched, "w/before-gc"));
			assertEquals(Strings.create("after"), read(relaunched, "w/after-gc"));
			EtchStore store = (EtchStore) relaunched.getStore();
			long relaunchedBytes = store.getEtch().getDataLength();
			long uncollected = before;
			assertTrue(relaunchedBytes < uncollected, () -> "the relaunched venue must run on the collected data:"
				+ " opened " + store.getFile() + " holding " + relaunchedBytes + " bytes, the uncollected store held "
				+ uncollected + "; store directory: " + describe(file.getParentFile()));
			assertFalse(store.isGCInProgress());
		} finally {
			relaunched.close();
		}
	}

	@Test
	public void venueOperatorOnly() {
		// No venue needed: authority is decided before the store seam is touched.
		Engine engine = TestEngine.ENGINE;
		AString venue = engine.getDIDString();
		// An ordinary user, a user's agent, the venue's own agent and the public
		// principal are all refused: only the venue principal itself (or a
		// venue-issued venue/gc delegation) may collect.
		for (RequestContext caller : new RequestContext[] {
				RequestContext.of(USER),
				RequestContext.ofAgent(USER, Strings.create("helper")),
				RequestContext.ofAgent(venue, Strings.create("odin")),
				RequestContext.of(Strings.create(venue + ":public"))}) {
			String denied = gcFails(engine, caller, STATUS);
			assertTrue(denied.contains("venue/gc") || denied.contains("denied"), denied);
			String backupDenied = gcFails(engine, caller, Maps.of("backupFile", "unauthorised.etch"));
			assertTrue(backupDenied.contains("venue/gc") || backupDenied.contains("denied"), backupDenied);
		}
		// The venue itself passes authority; this engine's host installed no
		// store seam, which is the next thing the operation reports.
		String noSeam = gcFails(engine, engine.venueContext(), STATUS);
		assertTrue(noSeam.contains("unavailable"), noSeam);
	}

	@Test
	public void refusedOnATemporaryStore() throws Exception {
		VenueServer v = VenueServer.launch(Maps.of(
			Config.PORT, 0, Config.STORE, Strings.create("temp"), Config.SEED, Strings.create(SEED_HEX)));
		try {
			String refused = gcFails(v.getEngine(), v.getEngine().venueContext(), Maps.empty());
			assertTrue(refused.contains("persistent file"), refused);
		} finally {
			v.close();
		}
	}
}
