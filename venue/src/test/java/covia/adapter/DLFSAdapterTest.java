package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.concurrent.atomic.AtomicInteger;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.lang.RT;
import convex.lattice.cursor.ALatticeCursor;
import covia.lattice.Covia;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

public class DLFSAdapterTest {

	// Class-local engine (not TestEngine.ENGINE) so onSync callbacks added by
	// testDLFSSyncReachesRootOnSyncCallback don't accumulate across all venue
	// tests. Per-method DID still required since methods run in parallel.
	static Engine engine;
	private AString ALICE_DID;

	@BeforeAll
	static void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	@BeforeEach
	public void setupDID(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	private ACell run(String op, ACell input) {
		return engine.jobs().invokeOperation(
			op, input, RequestContext.of(ALICE_DID)
		).awaitResult(5000);
	}

	@Test
	public void testCreateAndListDrives() {
		// No drives initially
		ACell result = run("v/ops/dlfs/list-drives", Maps.empty());
		AVector<?> drives = RT.ensureVector(RT.getIn(result, "drives"));
		assertNotNull(drives);
		long initialCount = drives.count();

		// Create health-vault drive
		result = run("v/ops/dlfs/create-drive", Maps.of("name", "health-vault"));
		assertEquals(true, RT.bool(RT.getIn(result, "created")));

		// List should show it
		result = run("v/ops/dlfs/list-drives", Maps.empty());
		drives = RT.ensureVector(RT.getIn(result, "drives"));
		assertEquals(initialCount + 1, drives.count());
		assertTrue(drives.toString().contains("health-vault"));

		// Creating same drive again is idempotent (lattice-backed)
		result = run("v/ops/dlfs/create-drive", Maps.of("name", "health-vault"));
		assertTrue(RT.bool(RT.getIn(result, "created")));
	}

	@Test
	public void testWriteAndReadFile() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-rw"));

		// Write a file
		ACell result = run("v/ops/dlfs/write", Maps.of(
			"drive", "test-rw",
			"path", "profile.json",
			"content", "{\"name\": \"Sarah Smith\"}"
		));
		assertTrue(RT.bool(RT.getIn(result, "created")));
		long written = RT.ensureLong(RT.getIn(result, "written")).longValue();
		assertTrue(written > 0);

		// Read it back
		result = run("v/ops/dlfs/read", Maps.of("drive", "test-rw", "path", "profile.json"));
		String content = RT.ensureString(RT.getIn(result, "content")).toString();
		assertEquals("{\"name\": \"Sarah Smith\"}", content);
		assertEquals("utf-8", RT.ensureString(RT.getIn(result, "encoding")).toString());
	}

	@Test
	public void testMkdirAndList() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-dir"));

		// Create directory
		ACell result = run("v/ops/dlfs/mkdir", Maps.of("drive", "test-dir", "path", "medications"));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Write file inside
		run("v/ops/dlfs/write", Maps.of(
			"drive", "test-dir",
			"path", "medications/levothyroxine.json",
			"content", "{\"dose\": \"75mcg\"}"
		));

		// List root
		result = run("v/ops/dlfs/list", Maps.of("drive", "test-dir"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertEquals(1, entries.count());
		assertEquals("medications", RT.getIn(entries.get(0), "name").toString());
		assertEquals("directory", RT.getIn(entries.get(0), "type").toString());

		// List medications dir
		result = run("v/ops/dlfs/list", Maps.of("drive", "test-dir", "path", "medications"));
		entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertEquals(1, entries.count());
		assertEquals("levothyroxine.json", RT.getIn(entries.get(0), "name").toString());
		assertEquals("file", RT.getIn(entries.get(0), "type").toString());
	}

	@Test
	public void testDeleteFile() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-del"));
		run("v/ops/dlfs/write", Maps.of("drive", "test-del", "path", "temp.txt", "content", "delete me"));

		// Delete
		ACell result = run("v/ops/dlfs/delete", Maps.of("drive", "test-del", "path", "temp.txt"));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));

		// Read should fail
		assertThrows(Exception.class, () ->
			run("v/ops/dlfs/read", Maps.of("drive", "test-del", "path", "temp.txt"))
		);
	}

	@Test
	public void testDeleteDrive() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-remove"));
		run("v/ops/dlfs/write", Maps.of("drive", "test-remove", "path", "data.txt", "content", "hello"));

		ACell result = run("v/ops/dlfs/delete-drive", Maps.of("name", "test-remove"));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));

		// Drive tombstoned on lattice — re-accessing creates a fresh empty drive
		result = run("v/ops/dlfs/list", Maps.of("drive", "test-remove"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertEquals(0, entries.count(), "Deleted drive should be empty when re-accessed");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testWriteReachesRootCursor() {
		// Write a file via DLFS adapter
		run("v/ops/dlfs/write", Maps.of("drive", "sync-test", "path", "hello.txt", "content", "sync check"));

		// Verify the root cursor has DLFS data
		ALatticeCursor<Index<Keyword, ACell>> root = engine.getRootCursor();
		ACell dlfsRegion = root.get().get(Covia.DLFS);
		assertNotNull(dlfsRegion, "Root cursor should have :dlfs region after DLFS write");
	}

	@Test
	public void testDLFSSyncReachesRootOnSyncCallback() {
		// Hook an onSync callback on the root cursor (simulates NodeServer propagator)
		ALatticeCursor<Index<Keyword, ACell>> root = engine.getRootCursor();
		AtomicInteger syncCount = new AtomicInteger();
		if (root instanceof convex.lattice.cursor.RootLatticeCursor<?> rlc) {
			rlc.onSync(value -> { syncCount.incrementAndGet(); return value; });
		} else {
			fail("Engine root cursor should be a RootLatticeCursor, was: " + root.getClass().getName());
		}

		// Write via adapter
		run("v/ops/dlfs/write", Maps.of("drive", "sync-cb-test", "path", "test.txt", "content", "callback check"));

		// Adapter write alone shouldn't trigger onSync
		int beforeSync = syncCount.get();

		// Now get the drive and sync it (simulates what syncDrive() in WebDAV does)
		DLFSAdapter dlfs = (DLFSAdapter) engine.getAdapter("dlfs");
		var drive = dlfs.getDriveForIdentity(ALICE_DID.toString(), "sync-cb-test");
		drive.sync();

		assertTrue(syncCount.get() > beforeSync,
			"DLFSLocal.sync() should trigger root cursor onSync callback, " +
			"but syncCount went from " + beforeSync + " to " + syncCount.get());
	}

	@Test
	public void testDriveNotFound() {
		assertThrows(Exception.class, () ->
			run("v/ops/dlfs/read", Maps.of("drive", "nonexistent", "path", "foo.txt"))
		);
	}

	@Test
	public void testOverwriteFile() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-overwrite"));
		run("v/ops/dlfs/write", Maps.of("drive", "test-overwrite", "path", "data.txt", "content", "v1"));

		// Overwrite
		ACell result = run("v/ops/dlfs/write", Maps.of(
			"drive", "test-overwrite", "path", "data.txt", "content", "v2"
		));
		assertEquals(false, RT.bool(RT.getIn(result, "created")));

		// Read should return v2
		result = run("v/ops/dlfs/read", Maps.of("drive", "test-overwrite", "path", "data.txt"));
		assertEquals("v2", RT.ensureString(RT.getIn(result, "content")).toString());
	}
}
