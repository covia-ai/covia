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
import covia.venue.Config;
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
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
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

		// Create test-drive drive
		result = run("v/ops/dlfs/create-drive", Maps.of("name", "test-drive"));
		assertEquals(true, RT.bool(RT.getIn(result, "created")));

		// List should show it
		result = run("v/ops/dlfs/list-drives", Maps.empty());
		drives = RT.ensureVector(RT.getIn(result, "drives"));
		assertEquals(initialCount + 1, drives.count());
		assertTrue(drives.toString().contains("test-drive"));

		// Creating same drive again is idempotent (lattice-backed)
		result = run("v/ops/dlfs/create-drive", Maps.of("name", "test-drive"));
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
		ACell result = run("v/ops/dlfs/mkdir", Maps.of("drive", "test-dir", "path", "documents"));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Write file inside
		run("v/ops/dlfs/write", Maps.of(
			"drive", "test-dir",
			"path", "documents/report.json",
			"content", "{\"status\": \"complete\"}"
		));

		// List root
		result = run("v/ops/dlfs/list", Maps.of("drive", "test-dir"));
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertEquals(1, entries.count());
		assertEquals("documents", RT.getIn(entries.get(0), "name").toString());
		assertEquals("directory", RT.getIn(entries.get(0), "type").toString());

		// List documents dir
		result = run("v/ops/dlfs/list", Maps.of("drive", "test-dir", "path", "documents"));
		entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertEquals(1, entries.count());
		assertEquals("report.json", RT.getIn(entries.get(0), "name").toString());
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

	// ===== Operations harmonised with FileAdapter =====

	@Test
	public void testAppend() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-append"));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-append", "path", "log.txt", "content", "line1\n"));

		ACell result = run("v/ops/dlfs/append",
			Maps.of("drive", "test-append", "path", "log.txt", "content", "line2\n"));
		assertEquals(6L, RT.ensureLong(RT.getIn(result, "appended")).longValue());
		assertFalse(RT.bool(RT.getIn(result, "created")));

		ACell read = run("v/ops/dlfs/read",
			Maps.of("drive", "test-append", "path", "log.txt"));
		assertEquals("line1\nline2\n", RT.ensureString(RT.getIn(read, "content")).toString());
	}

	@Test
	public void testAppendCreatesIfMissing() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-append-new"));
		ACell result = run("v/ops/dlfs/append",
			Maps.of("drive", "test-append-new", "path", "fresh.txt", "content", "first"));
		assertTrue(RT.bool(RT.getIn(result, "created")));
	}

	@Test
	public void testWriteBytes() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-bytes"));
		String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{0x00, 0x01, 0x02});
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-bytes", "path", "bin.dat", "bytes", b64));
		ACell read = run("v/ops/dlfs/read",
			Maps.of("drive", "test-bytes", "path", "bin.dat", "mode", "bytes"));
		assertEquals(b64, RT.ensureString(RT.getIn(read, "content")).toString());
	}

	@Test
	public void testStat() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-stat"));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-stat", "path", "thing.txt", "content", "hi"));

		ACell stat = run("v/ops/dlfs/stat",
			Maps.of("drive", "test-stat", "path", "thing.txt"));
		assertTrue(RT.bool(RT.getIn(stat, "exists")));
		assertEquals("file", RT.ensureString(RT.getIn(stat, "type")).toString());
		assertEquals(2L, RT.ensureLong(RT.getIn(stat, "size")).longValue());
		assertNotNull(RT.getIn(stat, "modified"));

		ACell missing = run("v/ops/dlfs/stat",
			Maps.of("drive", "test-stat", "path", "nope.txt"));
		assertFalse(RT.bool(RT.getIn(missing, "exists")));
	}

	@Test
	public void testMkdirParents() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-mkdir-p"));

		// Without parents, intermediate must exist — direct creation fails.
		assertThrows(Exception.class, () -> run("v/ops/dlfs/mkdir",
			Maps.of("drive", "test-mkdir-p", "path", "a/b/c")));

		// With parents=true, builds the chain.
		ACell result = run("v/ops/dlfs/mkdir",
			Maps.of("drive", "test-mkdir-p", "path", "a/b/c", "parents", true));
		assertTrue(RT.bool(RT.getIn(result, "created")));

		// Idempotent: calling again on existing directory is harmless.
		ACell again = run("v/ops/dlfs/mkdir",
			Maps.of("drive", "test-mkdir-p", "path", "a/b/c", "parents", true));
		assertFalse(RT.bool(RT.getIn(again, "created")));
	}

	@Test
	public void testDeleteRecursive() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-rmrf"));
		run("v/ops/dlfs/mkdir",
			Maps.of("drive", "test-rmrf", "path", "tree", "parents", true));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-rmrf", "path", "tree/a.txt", "content", "A"));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-rmrf", "path", "tree/b.txt", "content", "B"));

		// Recursive delete removes the populated directory.
		ACell result = run("v/ops/dlfs/delete",
			Maps.of("drive", "test-rmrf", "path", "tree", "recursive", true));
		assertTrue(RT.bool(RT.getIn(result, "deleted")));
		assertTrue(RT.bool(RT.getIn(result, "existed")));

		// And the tree is gone.
		ACell stat = run("v/ops/dlfs/stat",
			Maps.of("drive", "test-rmrf", "path", "tree"));
		assertFalse(RT.bool(RT.getIn(stat, "exists")));
	}

	@Test
	public void testDeleteMissingIsIdempotent() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-del-missing"));
		ACell result = run("v/ops/dlfs/delete",
			Maps.of("drive", "test-del-missing", "path", "nope.txt"));
		assertFalse(RT.bool(RT.getIn(result, "deleted")));
		assertFalse(RT.bool(RT.getIn(result, "existed")));
	}

	@Test
	public void testTreeBasic() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-tree"));
		run("v/ops/dlfs/mkdir",
			Maps.of("drive", "test-tree", "path", "/area/sub", "parents", true));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-tree", "path", "/area/a.txt", "content", "A"));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-tree", "path", "/area/sub/inner.txt", "content", "BB"));

		ACell out = run("v/ops/dlfs/tree",
			Maps.of("drive", "test-tree", "path", "/area"));
		String tree = RT.ensureString(RT.getIn(out, "tree")).toString();
		// Names only, sorted, dirs end with /
		assertEquals("a.txt\nsub/\n\tinner.txt\n", tree);
		assertFalse(RT.bool(RT.getIn(out, "truncated")));
	}

	@Test
	public void testTreeWithSizeInfo() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-tree-info"));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-tree-info", "path", "/short.txt", "content", "hi"));

		ACell out = run("v/ops/dlfs/tree",
			Maps.of("drive", "test-tree-info", "info", "size"));
		String tree = RT.ensureString(RT.getIn(out, "tree")).toString();
		assertTrue(tree.contains("short.txt (2 B)"), "expected size: " + tree);
	}

	@Test
	public void testListIncludesModified() {
		run("v/ops/dlfs/create-drive", Maps.of("name", "test-mtime"));
		run("v/ops/dlfs/write",
			Maps.of("drive", "test-mtime", "path", "a.txt", "content", "x"));
		ACell list = run("v/ops/dlfs/list", Maps.of("drive", "test-mtime"));
		AVector<?> entries = RT.ensureVector(RT.getIn(list, "entries"));
		assertEquals(1, entries.count());
		assertNotNull(RT.getIn(entries.get(0), "modified"));
	}

	// ========== DLFS as reference-addressed content storage (ContentProvider) ==========

	@Test
	public void testAssetContentServesDlfsPath() {
		// asset:content resolves a DID-scoped DLFS path — DLFS is an alternative
		// content storage mechanism behind the same op, not a special case.
		byte[] bytes = new byte[] {(byte)0x89, 0x50, 0x4E, 0x47, 42};
		run("v/ops/dlfs/create-drive", Maps.of("name", "cp"));
		run("v/ops/dlfs/write", Maps.of("drive", "cp", "path", "pic.png",
			"bytes", java.util.Base64.getEncoder().encodeToString(bytes)));

		ACell r = run("v/ops/asset/content", Maps.of("id", "dlfs/cp/pic.png"));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(r, "exists"));
		var value = RT.getIn(r, "value");
		assertTrue(value instanceof convex.core.data.ABlob, "content should be a Blob");
		assertArrayEquals(bytes, ((convex.core.data.ABlob) value).getBytes());
	}

	@Test
	public void testDlfsWriteFromPlainContentAsset() {
		// Regression: dlfs:write asset: refs previously resolved via resolveAsset,
		// which only recognises operation-shaped assets — a PLAIN content asset
		// failed "Asset not found". The unified Engine.resolveContent fixes it.
		byte[] bytes = "plain content asset".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		ACell stored = run("v/ops/asset/store", Maps.of(
			"metadata", Maps.of("name", "doc", "contentType", "text/plain"),
			"content", convex.core.data.Strings.create(
				"0x" + convex.core.data.Blob.wrap(bytes).toHexString())));
		String id = RT.ensureString(RT.getIn(stored, "id")).toString();

		run("v/ops/dlfs/create-drive", Maps.of("name", "docs2"));
		run("v/ops/dlfs/write", Maps.of("drive", "docs2", "path", "copy.txt", "asset", id));

		ACell read = run("v/ops/dlfs/read", Maps.of("drive", "docs2", "path", "copy.txt"));
		assertEquals("plain content asset", RT.getIn(read, "content").toString());
	}

	@Test
	public void testDlfsWriteFromAnotherDlfsPath() {
		// dlfs-to-dlfs copy: the asset: ref accepts a DLFS path too.
		run("v/ops/dlfs/create-drive", Maps.of("name", "src"));
		run("v/ops/dlfs/create-drive", Maps.of("name", "dst"));
		run("v/ops/dlfs/write", Maps.of("drive", "src", "path", "a.txt", "content", "across drives"));
		run("v/ops/dlfs/write", Maps.of("drive", "dst", "path", "b.txt", "asset", "dlfs/src/a.txt"));
		ACell read = run("v/ops/dlfs/read", Maps.of("drive", "dst", "path", "b.txt"));
		assertEquals("across drives", RT.getIn(read, "content").toString());
	}

	@Test
	public void testEnginePutContentToDlfsPath() throws Exception {
		// The write half of reference-addressed content: Engine.putContent routes
		// to the DLFS provider for drive paths.
		run("v/ops/dlfs/create-drive", Maps.of("name", "put"));
		byte[] bytes = "put via engine".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		boolean handled = engine.putContent(
			convex.core.data.Strings.create("dlfs/put/x.txt"),
			new java.io.ByteArrayInputStream(bytes), "text/plain",
			covia.venue.RequestContext.of(ALICE_DID));
		assertTrue(handled, "DLFS provider should handle a drive path");
		ACell read = run("v/ops/dlfs/read", Maps.of("drive", "put", "path", "x.txt"));
		assertEquals("put via engine", RT.getIn(read, "content").toString());

		// Non-provider refs are not handled (CAS put stays hash-keyed).
		assertFalse(engine.putContent(convex.core.data.Strings.create("w/not/dlfs"),
			new java.io.ByteArrayInputStream(bytes), null,
			covia.venue.RequestContext.of(ALICE_DID)));
	}

	@Test
	public void testAssetWithLiveDlfsContent() {
		// An asset whose metadata declares content: {dlfs: <path>} WITHOUT a
		// sha256 is a LIVE binding — content is whatever the file currently
		// holds. The asset identity covers the pointer, not the bytes.
		run("v/ops/dlfs/create-drive", Maps.of("name", "live"));
		run("v/ops/dlfs/write", Maps.of("drive", "live", "path", "doc.txt", "content", "version one"));
		ACell stored = run("v/ops/asset/store", Maps.of("metadata", Maps.of(
			"name", "current doc", "contentType", "text/plain",
			"content", Maps.of("dlfs", "dlfs/live/doc.txt"))));
		String id = RT.ensureString(RT.getIn(stored, "id")).toString();

		ACell r1 = run("v/ops/asset/content", Maps.of("id", id));
		assertEquals("version one", new String(
			((convex.core.data.ABlob) RT.getIn(r1, "value")).getBytes(),
			java.nio.charset.StandardCharsets.UTF_8));

		// The file changes — the SAME asset serves the new content.
		run("v/ops/dlfs/write", Maps.of("drive", "live", "path", "doc.txt", "content", "version two"));
		ACell r2 = run("v/ops/asset/content", Maps.of("id", id));
		assertEquals("version two", new String(
			((convex.core.data.ABlob) RT.getIn(r2, "value")).getBytes(),
			java.nio.charset.StandardCharsets.UTF_8));
	}

	@Test
	public void testAssetWithPinnedDlfsContentDetectsDrift() {
		// With content: {dlfs, sha256} the asset is PINNED: bytes are verified on
		// fetch, and drift after minting fails loudly — never silently-different
		// content.
		byte[] original = "pinned bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String sha = convex.core.crypto.Hashing.sha256(original).toHexString();
		run("v/ops/dlfs/create-drive", Maps.of("name", "pin2"));
		run("v/ops/dlfs/write", Maps.of("drive", "pin2", "path", "doc.txt", "content", "pinned bytes"));
		ACell stored = run("v/ops/asset/store", Maps.of("metadata", Maps.of(
			"name", "pinned doc", "contentType", "text/plain",
			"content", Maps.of("dlfs", "dlfs/pin2/doc.txt", "sha256", sha))));
		String id = RT.ensureString(RT.getIn(stored, "id")).toString();

		// Matching content serves fine.
		ACell ok = run("v/ops/asset/content", Maps.of("id", id));
		assertEquals("pinned bytes", new String(
			((convex.core.data.ABlob) RT.getIn(ok, "value")).getBytes(),
			java.nio.charset.StandardCharsets.UTF_8));

		// The file drifts — fetching through the asset now fails loudly.
		run("v/ops/dlfs/write", Maps.of("drive", "pin2", "path", "doc.txt", "content", "tampered"));
		Exception e = assertThrows(Exception.class,
			() -> run("v/ops/asset/content", Maps.of("id", id)));
		StringBuilder chain = new StringBuilder();
		for (Throwable c = e; c != null; c = c.getCause()) chain.append(c.getMessage()).append(" | ");
		assertTrue(chain.toString().contains("hash mismatch"),
			"drift must be a loud hash-mismatch error, got: " + chain);
	}
}
