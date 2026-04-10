package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;

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
import convex.lattice.LatticeContext;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.adapter.DLFSAdapter;
import covia.lattice.Covia;

/**
 * Tests DLFS data persistence through the full venue stack:
 * DLFSAdapter → cursor chain → NodeServer propagator → Etch store → restore.
 */
public class DLFSPersistenceTest {

	static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	@Test
	public void testDLFSDataSurvivesRestart() throws Exception {
		File etchFile = File.createTempFile("venue-test-", ".etch");
		etchFile.delete(); // EtchStore.create needs non-existent file
		etchFile.deleteOnExit();
		AKeyPair venueKey = AKeyPair.generate();

		// === Phase 1: Write data ===
		{
			AStore store = EtchStore.create(etchFile);
			NodeServer<Index<Keyword, ACell>> node =
				new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			node.setMergeContext(LatticeContext.create(null, venueKey));
			node.launch();

			Engine engine = new Engine(Maps.empty(), node.getCursor(), venueKey);
			Engine.addDemoAssets(engine);

			// Write via DLFS adapter
			ACell result = engine.jobs().invokeOperation(
				"dlfs:write",
				Maps.of("drive", "health-vault", "path", "test.txt", "content", "persistent!"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			assertNotNull(result, "DLFS write should succeed");

			// Verify root cursor has DLFS region with actual content
			ACell rootValue = node.getCursor().get();
			assertNotNull(rootValue, "Root cursor should have value");
			ACell dlfs = RT.getIn(rootValue, Covia.DLFS);
			assertNotNull(dlfs, ":dlfs region should exist after write");

			// Verify we can read the file back immediately (same session)
			ACell readResult = engine.jobs().invokeOperation(
				"dlfs:read",
				Maps.of("drive", "health-vault", "path", "test.txt"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			assertEquals("persistent!", RT.ensureString(RT.getIn(readResult, "content")).toString(),
				"File should be readable in same session");

			// Sync DLFS drive, then synchronously persist the root via the
			// node's persistSnapshot — guarantees data is on disk before we
			// read it. Tests construct Engine with the 3-arg constructor
			// (NOOP persist handler) so engine.flush() is a no-op here; we
			// drive persistence directly through the local node.
			DLFSAdapter dlfsAdapter1 = (DLFSAdapter) engine.getAdapter("dlfs");
			var drive1 = dlfsAdapter1.getDriveForIdentity(ALICE_DID.toString(), "health-vault");
			drive1.sync();
			node.persistSnapshot(node.getCursor().get());

			// Verify store actually received the data
			ACell storeRootAfterSync = store.getRootData();
			assertNotNull(storeRootAfterSync, "Store should have root data after DLFS sync");

			// Verify store has root data
			ACell storeRoot = store.getRootData();
			assertNotNull(storeRoot, "Store should have root data after sync");
			ACell storedDlfs = RT.getIn(storeRoot, Covia.DLFS);
			assertNotNull(storedDlfs, ":dlfs should be in store root data");

			node.close();
			store.close();
		}

		// === Phase 2: Restart and read ===
		{
			AStore store = EtchStore.create(etchFile);
			NodeServer<Index<Keyword, ACell>> node =
				new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			node.setMergeContext(LatticeContext.create(null, venueKey));
			node.launch();

			// Verify restore — root level
			Index<Keyword, ACell> rootValue = node.getCursor().get();
			assertNotNull(rootValue, "Root cursor should have restored value");
			ACell dlfs = rootValue.get(Covia.DLFS);
			assertNotNull(dlfs, ":dlfs region should survive restart");

			// Check the cursor chain that DLFSAdapter would use
			Engine engine = new Engine(Maps.empty(), node.getCursor(), venueKey);
			Engine.addDemoAssets(engine);

			// Navigate the same path DLFSAdapter.getUserDLFSCursor() takes
			var rootCursor = engine.getRootCursor();
			var dlfsCursor = rootCursor.path(Covia.DLFS);
			assertNotNull(dlfsCursor.get(), ":dlfs cursor should have value after restore");

			// Get the DLFSAdapter and check drive access
			DLFSAdapter dlfsAdapter = (DLFSAdapter) engine.getAdapter("dlfs");
			var drive = dlfsAdapter.getDriveForIdentity(ALICE_DID.toString(), "health-vault");
			assertNotNull(drive, "Drive should be accessible after restore");

			// Check root node of the drive
			var driveRoot = drive.getNode(drive.getPath("/"));
			assertNotNull(driveRoot, "Drive root node should exist after restore");

			// Check if test.txt exists
			var fileNode = drive.getNode(drive.getPath("/test.txt"));
			assertNotNull(fileNode, "test.txt node should exist after restore");

			// Read via adapter
			ACell result = engine.jobs().invokeOperation(
				"dlfs:read",
				Maps.of("drive", "health-vault", "path", "test.txt"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			String content = RT.ensureString(RT.getIn(result, "content")).toString();
			assertEquals("persistent!", content, "DLFS file content should survive restart");

			node.close();
			store.close();
		}
	}
}
