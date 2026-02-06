package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Tests that a Venue can persist its entire lattice state to an Etch store,
 * shut down, restart from the same store, and recover all assets, jobs,
 * and content correctly.
 */
public class VenueRestartTest {

	@Test
	public void testRestartFromEtchStore() throws Exception {

		// Use a single Etch store that survives across "restarts"
		EtchStore store = EtchStore.createTemp();

		// Fixed DID so both engines use the same lattice path
		AKeyPair kp = AKeyPair.generate();
		AString did = Strings.create("did:key:" + Multikey.encodePublicKey(kp.getAccountKey()));
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

		// ========== Stage 1: Create engine, register adapters, run operations ==========

		{
			Engine engine = new Engine(config, store);
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
			Job echoJob = engine.invokeOperation(echoOpId.toHexString(),
					Maps.of(Fields.MESSAGE, Strings.create("hello restart")));
			echoJob.future().get(5, TimeUnit.SECONDS);
			assertEquals(Status.COMPLETE, echoJob.getStatus(), "Echo job should complete");
			assertNotNull(echoJob.getOutput(), "Echo job should have output");
			echoJobId = echoJob.getID().toString();

			// Run test:error → should FAIL
			Job errorJob = engine.invokeOperation(errorOpId.toHexString(),
					Maps.of(Fields.MESSAGE, Strings.create("test error")));
			try {
				errorJob.future().get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				// Expected — error jobs complete exceptionally
			}
			assertEquals(Status.FAILED, errorJob.getStatus(), "Error job should fail");
			errorJobId = errorJob.getID().toString();

			// Run test:never → should stay STARTED (never completes)
			Job neverJob = engine.invokeOperation(neverOpId.toHexString(),
					Maps.of(Fields.MESSAGE, Strings.create("never finishes")));
			Thread.sleep(50); // Give it a moment to start
			assertEquals(Status.STARTED, neverJob.getStatus(), "Never job should be STARTED");
			neverJobId = neverJob.getID().toString();

			// Run test:pause → should auto-pause itself
			Job pauseJob = engine.invokeOperation(pauseOpId.toHexString(),
					Maps.of(Fields.MESSAGE, Strings.create("pause me")));
			Thread.sleep(50); // Give it a moment to pause
			assertEquals(Status.PAUSED, pauseJob.getStatus(), "Pause job should be PAUSED");
			pauseJobId = pauseJob.getID().toString();

			// Record asset count before persist
			assetCount = (int) engine.getAssets().count();
			assertTrue(assetCount >= 4, "Should have at least 4 assets (echo, error, never, custom)");

			// ========== Stage 2: Persist and shut down ==========

			engine.persistState();
			// engine goes out of scope — simulates shutdown
		}

		// ========== Stage 3: Restart with the same store ==========

		{
			Engine engine2 = new Engine(config, store);
			Engine.addDemoAssets(engine2);
			engine2.recoverJobs();

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

			// 4d: All job IDs present in lattice
			List<AString> jobIds = engine2.getJobs();
			assertTrue(jobIds.contains(Strings.create(echoJobId)),
					"Echo job ID should be in job list after restart");
			assertTrue(jobIds.contains(Strings.create(errorJobId)),
					"Error job ID should be in job list after restart");
			assertTrue(jobIds.contains(Strings.create(neverJobId)),
					"Never job ID should be in job list after restart");

			// 4e: COMPLETE job has correct status and output
			AMap<AString, ACell> echoData = engine2.getJobData(Strings.create(echoJobId));
			assertNotNull(echoData, "Echo job data should survive restart");
			assertEquals(Status.COMPLETE, RT.ensureString(echoData.get(Fields.STATUS)),
					"Echo job should still be COMPLETE");
			assertNotNull(echoData.get(Fields.OUTPUT), "Echo job output should survive restart");

			// 4f: FAILED job has correct status
			AMap<AString, ACell> errorData = engine2.getJobData(Strings.create(errorJobId));
			assertNotNull(errorData, "Error job data should survive restart");
			assertEquals(Status.FAILED, RT.ensureString(errorData.get(Fields.STATUS)),
					"Error job should still be FAILED");

			// 4g: STARTED job was re-fired by recovery (should be in-memory jobs now)
			Job recoveredNeverJob = engine2.getJob(neverJobId);
			assertNotNull(recoveredNeverJob, "Never job should be recovered after restart");
			AString neverStatus = recoveredNeverJob.getStatus();
			assertTrue(Status.STARTED.equals(neverStatus) || Status.PENDING.equals(neverStatus),
					"Recovered never job should be STARTED or PENDING, got: " + neverStatus);

			// 4h: PAUSED job remains PAUSED after restart (not re-fired)
			AMap<AString, ACell> pauseData = engine2.getJobData(Strings.create(pauseJobId));
			assertNotNull(pauseData, "Pause job data should survive restart");
			assertEquals(Status.PAUSED, RT.ensureString(pauseData.get(Fields.STATUS)),
					"Pause job should still be PAUSED after restart");

			// 4i: Unpause the job by delivering a message → should complete
			engine2.deliverMessage(pauseJobId, Maps.of("content", Strings.create("resume")), (String) null);
			Thread.sleep(50); // Give it a moment to process
			Job unpausedJob = engine2.getJob(pauseJobId);
			assertNotNull(unpausedJob, "Unpaused job should exist");
			assertEquals(Status.COMPLETE, unpausedJob.getStatus(),
					"Unpaused job should be COMPLETE after receiving message");
			assertNotNull(unpausedJob.getOutput(), "Unpaused job should have output");
		}
	}
}
