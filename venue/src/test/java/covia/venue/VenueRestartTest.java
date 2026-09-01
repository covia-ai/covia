package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.venue.RequestContext;
import covia.test.DurabilityTest;

/**
 * Tests that a Venue can persist its entire lattice state to an Etch store,
 * shut down, restart from the same store, and recover all assets, jobs,
 * and content correctly.
 */
// Durability guarantee: forks real JVMs / relaunches venues to prove crash
// and restart survival. Slow and unavoidably so — excluded from the default
// `mvn test` inner loop, run on every push via CI (-DexcludedGroups=integration).
@DurabilityTest
public class VenueRestartTest {
	private EtchStore store;
	private NodeServer<Index<Keyword, ACell>> activeNode;
	private Engine activeEngine;

	@AfterEach
	void teardown() throws Exception {
		try {
			closeActivePhase();
		} finally {
			if (store != null) store.close();
			store = null;
		}
	}

	private void closeActivePhase() throws Exception {
		try {
			if (activeEngine != null) activeEngine.close();
		} finally {
			activeEngine = null;
			if (activeNode != null) activeNode.close();
			activeNode = null;
		}
	}

	@Test
	public void testRestartFromEtchStore() throws Exception {

		// Use a single Etch store that survives across "restarts"
		store = EtchStore.createTemp();

		// Fixed DID so both engines use the same lattice path
		AKeyPair kp = AKeyPair.generate();
		String did = "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
		AMap<AString, ACell> config = Maps.of(Config.DID, did);

		// Track state across restart
		Hash echoOpId;
		Hash errorOpId;
		Hash neverOpId;
		Hash pauseOpId;
		Hash customAssetId;
		String echoJobId;
		String errorJobId;
		String neverJobId;
		String pauseJobId;
		int assetCount;

		// ========== Stage 1: Create engine via NodeServer, register adapters, run operations ==========

		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			activeNode = ns;
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp).start();
			activeEngine = engine;
			Engine.addDemoAssets(engine);

			// Store a custom asset with content
			AString customMeta = Strings.create("{\"name\":\"restart-test-asset\",\"description\":\"Survives restart\"}");
			Blob customContent = Blob.wrap("restart-test-content".getBytes());
			customAssetId = engine.storeAsset(customMeta, customContent);
			assertNotNull(customAssetId, "Custom asset should be stored");

			// Record operation IDs
			echoOpId = TestOps.ECHO;
			errorOpId = TestOps.ERROR;
			neverOpId = TestOps.NEVER;
			pauseOpId = TestOps.PAUSE;

			// Verify assets exist
			assertNotNull(engine.getAsset(echoOpId), "Echo op should exist");
			assertNotNull(engine.getAsset(errorOpId), "Error op should exist");
			assertNotNull(engine.getAsset(neverOpId), "Never op should exist");
			assertNotNull(engine.getAsset(customAssetId), "Custom asset should exist");

			// Run test:echo → should COMPLETE
			Job echoJob = engine.jobs().invokeOperation(echoOpId.toCVMHexString(),
					Maps.of(Fields.MESSAGE, "hello restart"), engine.venueContext());
			echoJob.future().get(5, TimeUnit.SECONDS);
			assertEquals(Status.COMPLETE, echoJob.getStatus(), "Echo job should complete");
			assertNotNull(echoJob.getOutput(), "Echo job should have output");
			echoJobId = echoJob.getID().toHexString();

			// Run test:error → should FAIL
			Job errorJob = engine.jobs().invokeOperation(errorOpId.toCVMHexString(),
					Maps.of(Fields.MESSAGE, "test error"), engine.venueContext());
			try {
				errorJob.future().get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				// Expected — error jobs complete exceptionally
			}
			assertEquals(Status.FAILED, errorJob.getStatus(), "Error job should fail");
			errorJobId = errorJob.getID().toHexString();

			// Run test:never → should stay STARTED (never completes)
			Job neverJob = engine.jobs().invokeOperation(neverOpId.toCVMHexString(),
					Maps.of(Fields.MESSAGE, "never finishes"), engine.venueContext());
			TestEngine.awaitCondition(() -> Status.STARTED.equals(neverJob.getStatus()), 5000,
				() -> "never job did not start (status=" + neverJob.getStatus() + ")");
			assertEquals(Status.STARTED, neverJob.getStatus(), "Never job should be STARTED");
			neverJobId = neverJob.getID().toHexString();

			// Run test:pause → should auto-pause itself
			Job pauseJob = engine.jobs().invokeOperation(pauseOpId.toCVMHexString(),
					Maps.of(Fields.MESSAGE, "pause me"), engine.venueContext());
			TestEngine.awaitCondition(() -> Status.PAUSED.equals(pauseJob.getStatus()), 5000,
				() -> "pause job did not pause (status=" + pauseJob.getStatus() + ")");
			assertEquals(Status.PAUSED, pauseJob.getStatus(), "Pause job should be PAUSED");
			pauseJobId = pauseJob.getID().toHexString();

			// Record asset count before persist
			assetCount = (int) engine.getAssets().count();
			assertTrue(assetCount >= 4, "Should have at least 4 assets (echo, error, never, custom)");

			// ========== Stage 2: Sync and shut down ==========

			engine.syncState();
			closeActivePhase(); // persists via triggerAndClose
		}

		// ========== Stage 3: Restart with the same store ==========

		{
			NodeServer<Index<Keyword, ACell>> ns2 = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			activeNode = ns2;
			ns2.launch(); // restores from store
			Engine engine2 = new Engine(config, ns2.getCursor(), kp).start();
			activeEngine = engine2;
			Engine.addDemoAssets(engine2);
			engine2.jobs().recoverJobs();

			// ========== Stage 4: Verify state ==========

			// 4a: Assets survived
			int restoredAssetCount = (int) engine2.getAssets().count();
			assertTrue(restoredAssetCount >= assetCount,
					"Restored asset count (" + restoredAssetCount + ") should be >= original (" + assetCount + ")");

			// 4b: Specific assets accessible by original ID
			assertNotNull(engine2.getAsset(echoOpId), "Echo op should survive restart");
			assertNotNull(engine2.getAsset(errorOpId), "Error op should survive restart");
			assertNotNull(engine2.getAsset(neverOpId), "Never op should survive restart");
			assertNotNull(engine2.getAsset(customAssetId), "Custom asset should survive restart");

			// 4c: Custom asset metadata matches
			AString restoredMeta = engine2.getMetadata(customAssetId);
			assertNotNull(restoredMeta, "Custom asset metadata should survive restart");
			assertTrue(restoredMeta.toString().contains("restart-test-asset"),
					"Custom asset metadata should contain original name");

			// 4d: All job IDs present in per-user lattice (venue DID's jobs)
			Index<Blob, ACell> jobIds = engine2.jobs().getJobs(engine2.venueContext());
			assertNotNull(jobIds.get(Blob.parse(echoJobId)),
					"Echo job ID should be in job list after restart");
			assertNotNull(jobIds.get(Blob.parse(errorJobId)),
					"Error job ID should be in job list after restart");
			assertNotNull(jobIds.get(Blob.parse(neverJobId)),
					"Never job ID should be in job list after restart");

			// 4e: COMPLETE job has correct status and output
			AMap<AString, ACell> echoData = engine2.jobs().getJobData(Blob.parse(echoJobId), engine2.venueContext());
			assertNotNull(echoData, "Echo job data should survive restart");
			assertEquals(Status.COMPLETE, RT.ensureString(echoData.get(Fields.STATUS)),
					"Echo job should still be COMPLETE");
			assertNotNull(echoData.get(Fields.OUTPUT), "Echo job output should survive restart");

			// 4f: FAILED job has correct status
			AMap<AString, ACell> errorData = engine2.jobs().getJobData(Blob.parse(errorJobId), engine2.venueContext());
			assertNotNull(errorData, "Error job data should survive restart");
			assertEquals(Status.FAILED, RT.ensureString(errorData.get(Fields.STATUS)),
					"Error job should still be FAILED");

			// 4g: an in-flight job that registered a pause hook is SUSPENDED at
			// graceful shutdown, not left to fail. test:never wires a pause hook
			// (so the lifecycle endpoints work), which under the shutdown
			// contract (4e3b4cf1) is its declaration that the work can be
			// suspended: defaultSuspend pauses it, and boot recovery restores the
			// paused family live. A non-pausable in-flight job is instead
			// cancelled with "Venue shut down", and a true STARTED-at-crash record
			// takes the FAILED default — both covered in JobShutdownTest.
			AMap<AString, ACell> neverData = engine2.jobs().getJobData(Blob.parse(neverJobId), engine2.venueContext());
			assertNotNull(neverData, "Never job record should survive restart");
			assertEquals(Status.PAUSED, RT.ensureString(neverData.get(Fields.STATUS)),
					"Suspendable in-flight job should be PAUSED after a graceful restart");

			// 4h: PAUSED job remains PAUSED after restart (not re-fired)
			AMap<AString, ACell> pauseData = engine2.jobs().getJobData(Blob.parse(pauseJobId), engine2.venueContext());
			assertNotNull(pauseData, "Pause job data should survive restart");
			assertEquals(Status.PAUSED, RT.ensureString(pauseData.get(Fields.STATUS)),
					"Pause job should still be PAUSED after restart");

			// 4i: Unpause the job by delivering a message → should complete
			engine2.jobs().deliverMessage(Blob.parse(pauseJobId), Maps.of("content", "resume"), engine2.getDIDString());
			TestEngine.awaitCondition(() -> {
				AMap<AString, ACell> data = engine2.jobs().getJobData(
					Blob.parse(pauseJobId), engine2.venueContext());
				return data != null && Status.COMPLETE.equals(RT.ensureString(data.get(Fields.STATUS)));
			}, 5000, () -> "resumed pause job did not complete");
			// Job completes and is evicted from activeJobs — use lattice fallback
			AMap<AString, ACell> unpausedData = engine2.jobs().getJobData(Blob.parse(pauseJobId), engine2.venueContext());
			assertNotNull(unpausedData, "Unpaused job data should exist in lattice");
			assertEquals(Status.COMPLETE, RT.ensureString(unpausedData.get(Fields.STATUS)),
					"Unpaused job should be COMPLETE after receiving message");
			assertNotNull(unpausedData.get(Fields.OUTPUT), "Unpaused job should have output");

			closeActivePhase();
		}
	}
}
