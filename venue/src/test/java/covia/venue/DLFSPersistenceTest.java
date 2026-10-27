package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.crypto.AKeyPair;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.adapter.DLFSAdapter;
import covia.lattice.Covia;
import covia.test.DurabilityTest;

/**
 * Tests DLFS data persistence through the full venue stack:
 * DLFSAdapter → cursor chain → NodeServer propagator → Etch store → restore.
 */
// Durability guarantee: forks real JVMs / relaunches venues to prove crash
// and restart survival. Slow and unavoidably so — excluded from the default
// `mvn test` inner loop, run on every push via CI (-DexcludedGroups=integration).
@DurabilityTest
public class DLFSPersistenceTest {

	static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private AStore store;
	private NodeServer<Index<Keyword, ACell>> node;
	private Engine engine;

	@AfterEach
	void teardown() throws Exception {
		closePhase();
	}

	private void closePhase() throws Exception {
		try {
			if (engine != null) engine.close();
		} finally {
			engine = null;
			try {
				if (node != null) node.close();
			} finally {
				node = null;
				if (store != null) store.close();
				store = null;
			}
		}
	}

	@Test
	public void testDLFSDataSurvivesRestart() throws Exception {
		File etchFile = TestTemp.etchFile("dlfs-persist");
		AKeyPair venueKey = AKeyPair.generate();

		// === Phase 1: Write data ===
		{
			store = EtchStore.create(etchFile);
			node =
				new NodeServer<>(CoviaApplication.open(store, venueKey).host(), NodeConfig.port(-1));
			node.launch();

			engine = new Engine(Maps.of(
				Config.USERS, Maps.of(Config.AUTO_CREATE, true)), CoviaApplication.connect(node.getHost()), venueKey).start();
			Engine.addDemoAssets(engine);

			// Write via DLFS adapter
			ACell result = engine.jobs().invokeOperation(
				"v/ops/dlfs/write",
				Maps.of("drive", "test-drive", "path", "test.txt", "content", "persistent!"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			assertNotNull(result, "DLFS write should succeed");
			engine.jobs().invokeOperation("v/ops/dlfs/write",
				Maps.of("drive", "deleted-drive", "path", "gone.txt", "content", "gone"),
				RequestContext.of(ALICE_DID)).awaitResult(5000);
			engine.jobs().invokeOperation("v/ops/dlfs/delete-drive",
				Maps.of("name", "deleted-drive"), RequestContext.of(ALICE_DID)).awaitResult(5000);

			// Verify root cursor has DLFS region with actual content
			ACell rootValue = node.getCursor().get();
			assertNotNull(rootValue, "Root cursor should have value");
			ACell dlfs = RT.getIn(rootValue, Covia.DLFS);
			assertNotNull(dlfs, ":dlfs region should exist after write");

			// Verify we can read the file back immediately (same session)
			ACell readResult = engine.jobs().invokeOperation(
				"v/ops/dlfs/read",
				Maps.of("drive", "test-drive", "path", "test.txt"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			assertEquals("persistent!", RT.ensureString(RT.getIn(readResult, "content")).toString(),
				"File should be readable in same session");

			// Sync the DLFS drive, then checkpoint the host: it retains the
			// current root and completes the store's durability barrier, so
			// the data is on disk before we read it back.
			DLFSAdapter dlfsAdapter1 = (DLFSAdapter) engine.getAdapter("dlfs");
			var drive1 = dlfsAdapter1.getDriveForIdentity(ALICE_DID.toString(), "test-drive");
			drive1.sync();
			node.getHost().checkpoint();

			// Verify store actually received the data
			ACell storeRootAfterSync = store.getRootData();
			assertNotNull(storeRootAfterSync, "Store should have root data after DLFS sync");

			// Verify store has root data
			ACell storeRoot = store.getRootData();
			assertNotNull(storeRoot, "Store should have root data after sync");
			ACell storedDlfs = RT.getIn(storeRoot, Covia.DLFS);
			assertNotNull(storedDlfs, ":dlfs should be in store root data");

			closePhase();
		}

		// === Phase 2: Restart and read ===
		{
			store = EtchStore.create(etchFile);
			node =
				new NodeServer<>(CoviaApplication.open(store, venueKey).host(), NodeConfig.port(-1));
			node.launch();

			// Verify restore — root level
			Index<Keyword, ACell> rootValue = node.getCursor().get();
			assertNotNull(rootValue, "Root cursor should have restored value");
			ACell dlfs = rootValue.get(Covia.DLFS);
			assertNotNull(dlfs, ":dlfs region should survive restart");

			// Check the cursor chain that DLFSAdapter would use
			engine = new Engine(Maps.of(
				Config.USERS, Maps.of(Config.AUTO_CREATE, true)), CoviaApplication.connect(node.getHost()), venueKey).start();
			Engine.addDemoAssets(engine);

			// Navigate the same path DLFSAdapter.getUserDLFSCursor() takes
			var rootCursor = engine.getRootCursor();
			var dlfsCursor = rootCursor.path(Covia.DLFS);
			assertNotNull(dlfsCursor.get(), ":dlfs cursor should have value after restore");

			// Get the DLFSAdapter and check drive access
			DLFSAdapter dlfsAdapter = (DLFSAdapter) engine.getAdapter("dlfs");
			assertEquals(convex.core.data.Vectors.of("test-drive"), RT.getIn(
				dlfsAdapter.listDrives(RequestContext.of(ALICE_DID)), "drives"),
				"deletion and the filtered drive listing survive a store reopen");
			var drive = dlfsAdapter.getDriveForIdentity(ALICE_DID.toString(), "test-drive");
			assertNotNull(drive, "Drive should be accessible after restore");

			// Check root node of the drive
			var driveRoot = drive.getNode(drive.getPath("/"));
			assertNotNull(driveRoot, "Drive root node should exist after restore");

			// Check if test.txt exists
			var fileNode = drive.getNode(drive.getPath("/test.txt"));
			assertNotNull(fileNode, "test.txt node should exist after restore");

			// Read via adapter
			ACell result = engine.jobs().invokeOperation(
				"v/ops/dlfs/read",
				Maps.of("drive", "test-drive", "path", "test.txt"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			String content = RT.ensureString(RT.getIn(result, "content")).toString();
			assertEquals("persistent!", content, "DLFS file content should survive restart");

			closePhase();
		}
	}
}
