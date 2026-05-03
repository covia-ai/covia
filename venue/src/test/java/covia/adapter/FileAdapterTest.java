package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

public class FileAdapterTest {

	@TempDir static Path workspace;
	@TempDir static Path readonly;

	private static Engine engine;
	private static final String DID = "did:key:z6Mk-test-FileAdapterTest";

	@BeforeAll
	static void setup() {
		engine = Engine.createTemp(Maps.of(
			"file", Maps.of(
				"roots", Maps.of(
					"work", workspace.toAbsolutePath().toString(),
					"ro", Maps.of(
						"path", readonly.toAbsolutePath().toString(),
						"readOnly", true
					)
				)
			)
		));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void teardown() throws IOException {
		// TempDir cleans the directories themselves, but files inside the
		// readonly root may have been left behind (it's only logically RO).
		try (Stream<Path> walk = Files.walk(workspace)) {
			walk.sorted(Comparator.reverseOrder())
				.filter(p -> !p.equals(workspace))
				.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
		}
	}

	private ACell run(String op, ACell input) {
		Job job = engine.jobs().invokeOperation(op, input, RequestContext.of(convex.core.data.Strings.create(DID)));
		ACell result = job.awaitResult(5000);
		if (job.getStatus() == Status.FAILED) {
			throw new AssertionError("Job failed: " + job.getErrorMessage());
		}
		return result;
	}

	private Job runRaw(String op, ACell input) {
		Job job = engine.jobs().invokeOperation(op, input, RequestContext.of(convex.core.data.Strings.create(DID)));
		try {
			job.awaitResult(5000);
		} catch (RuntimeException ignored) {
			// Negative tests inspect job.getStatus()/getErrorMessage() directly.
		}
		return job;
	}

	// =========================================================

	@Test
	public void testRoots() {
		ACell result = run("v/ops/file/roots", Maps.empty());
		AVector<?> roots = RT.ensureVector(RT.getIn(result, "roots"));
		assertNotNull(roots);
		assertEquals(2, roots.count());
		String dump = roots.toString();
		assertTrue(dump.contains("work"));
		assertTrue(dump.contains("ro"));
	}

	@Test
	public void testWriteAndRead() throws IOException {
		ACell write = run("v/ops/file/write", Maps.of(
			"root", "work",
			"path", "hello.txt",
			"content", "hello world"
		));
		assertEquals(11L, RT.ensureLong(RT.getIn(write, "written")).longValue());
		assertTrue(RT.bool(RT.getIn(write, "created")));

		// File is on disk
		Path file = workspace.resolve("hello.txt");
		assertTrue(Files.exists(file));
		assertEquals("hello world", Files.readString(file));

		// Read it back through the adapter
		ACell read = run("v/ops/file/read", Maps.of(
			"root", "work",
			"path", "hello.txt"
		));
		assertEquals("hello world", RT.ensureString(RT.getIn(read, "content")).toString());
		assertEquals("utf-8", RT.ensureString(RT.getIn(read, "encoding")).toString());
		assertEquals(11L, RT.ensureLong(RT.getIn(read, "size")).longValue());
	}

	@Test
	public void testAppend() throws IOException {
		run("v/ops/file/write", Maps.of("root", "work", "path", "log.txt", "content", "line1\n"));
		ACell appended = run("v/ops/file/append", Maps.of(
			"root", "work", "path", "log.txt", "content", "line2\n"
		));
		assertEquals(6L, RT.ensureLong(RT.getIn(appended, "appended")).longValue());
		assertFalse(RT.bool(RT.getIn(appended, "created")));
		assertEquals("line1\nline2\n", Files.readString(workspace.resolve("log.txt")));
	}

	@Test
	public void testBytesRoundTrip() {
		// Bytes that contain a NUL — auto mode should pick base64
		String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{0x00, 0x01, 0x02, 0x03});
		run("v/ops/file/write", Maps.of(
			"root", "work", "path", "bin.dat", "bytes", b64
		));
		ACell read = run("v/ops/file/read", Maps.of(
			"root", "work", "path", "bin.dat"
		));
		assertEquals("base64", RT.ensureString(RT.getIn(read, "encoding")).toString());
		assertEquals(b64, RT.ensureString(RT.getIn(read, "content")).toString());
	}

	@Test
	public void testMkdirAndList() throws IOException {
		run("v/ops/file/mkdir", Maps.of("root", "work", "path", "sub/nested", "parents", true));
		assertTrue(Files.isDirectory(workspace.resolve("sub/nested")));

		run("v/ops/file/write", Maps.of("root", "work", "path", "sub/a.txt", "content", "A"));
		run("v/ops/file/write", Maps.of("root", "work", "path", "sub/b.txt", "content", "BB"));

		ACell list = run("v/ops/file/list", Maps.of("root", "work", "path", "sub"));
		AVector<?> entries = RT.ensureVector(RT.getIn(list, "entries"));
		assertNotNull(entries);
		// 3 entries: a.txt, b.txt, nested/
		assertEquals(3, entries.count());
	}

	@Test
	public void testStatAndDelete() throws IOException {
		run("v/ops/file/write", Maps.of("root", "work", "path", "doomed.txt", "content", "x"));

		ACell stat = run("v/ops/file/stat", Maps.of("root", "work", "path", "doomed.txt"));
		assertTrue(RT.bool(RT.getIn(stat, "exists")));
		assertEquals("file", RT.ensureString(RT.getIn(stat, "type")).toString());

		ACell del = run("v/ops/file/delete", Maps.of("root", "work", "path", "doomed.txt"));
		assertTrue(RT.bool(RT.getIn(del, "deleted")));
		assertTrue(RT.bool(RT.getIn(del, "existed")));

		ACell stat2 = run("v/ops/file/stat", Maps.of("root", "work", "path", "doomed.txt"));
		assertFalse(RT.bool(RT.getIn(stat2, "exists")));
	}

	@Test
	public void testReadIncludesMime() throws IOException {
		run("v/ops/file/write", Maps.of(
			"root", "work", "path", "page.html", "content", "<h1>hi</h1>"));
		ACell read = run("v/ops/file/read",
			Maps.of("root", "work", "path", "page.html"));
		assertEquals("text/html", RT.ensureString(RT.getIn(read, "mime")).toString());
	}

	@Test
	public void testJsonMode() {
		run("v/ops/file/write", Maps.of(
			"root", "work", "path", "obj.json",
			"value", Maps.of("a", 1L, "b", "two")
		));
		ACell read = run("v/ops/file/read", Maps.of(
			"root", "work", "path", "obj.json", "mode", "json"
		));
		ACell value = RT.getIn(read, "value");
		assertNotNull(value);
		assertEquals(1L, RT.ensureLong(RT.getIn(value, "a")).longValue());
		assertEquals("two", RT.ensureString(RT.getIn(value, "b")).toString());
	}

	// =========================================================
	// Security

	@Test
	public void testRejectsUnknownRoot() {
		Job job = runRaw("v/ops/file/read", Maps.of("root", "nope", "path", "x"));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("Unknown root"));
	}

	@Test
	public void testRejectsTraversal() {
		Job job = runRaw("v/ops/file/read", Maps.of("root", "work", "path", "../etc/passwd"));
		assertEquals(Status.FAILED, job.getStatus());
		String msg = job.getErrorMessage();
		assertTrue(msg.contains("escapes") || msg.contains("No accessible ancestor"),
			"unexpected error: " + msg);
	}

	@Test
	public void testRejectsAbsolutePath() {
		// On Windows this is C:/something; on POSIX it's /something. Both are
		// invalid as relative paths.
		String absolute = Path.of("/", "etc", "passwd").toAbsolutePath().toString();
		Job job = runRaw("v/ops/file/read", Maps.of("root", "work", "path", absolute));
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	public void testReadOnlyRoot() {
		Job job = runRaw("v/ops/file/write", Maps.of(
			"root", "ro", "path", "x.txt", "content", "nope"
		));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("read-only"));
	}

	@Test
	public void testRejectsDeepTraversal() {
		Job job = runRaw("v/ops/file/read",
			Maps.of("root", "work", "path", "a/b/../../../etc/passwd"));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().toLowerCase().contains("escape"),
			"unexpected error: " + job.getErrorMessage());
	}

	@Test
	public void testRejectsBareParent() {
		Job job = runRaw("v/ops/file/list", Maps.of("root", "work", "path", ".."));
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	public void testAcceptsInternalTraversal() throws IOException {
		// "sub/../inside.txt" normalises to "inside.txt" inside the root —
		// should succeed, not be misclassified as escape.
		run("v/ops/file/mkdir", Maps.of("root", "work", "path", "subdir"));
		run("v/ops/file/write", Maps.of(
			"root", "work", "path", "subdir/../inside.txt", "content", "ok"));
		assertEquals("ok", Files.readString(workspace.resolve("inside.txt")));
	}

	@Test
	public void testRejectsNullByte() {
		Job job = runRaw("v/ops/file/read",
			Maps.of("root", "work", "path", "legit.txt ../etc/passwd"));
		assertEquals(Status.FAILED, job.getStatus());
		String msg = job.getErrorMessage().toLowerCase();
		assertTrue(msg.contains("invalid") || msg.contains("nul"),
			"unexpected error: " + job.getErrorMessage());
	}

	@Test
	public void testAcceptsLeadingSlash() throws IOException {
		// DLFS-compatible: "/foo.txt" is equivalent to "foo.txt" — leading
		// slashes are stripped and the path is treated as relative-to-root.
		run("v/ops/file/write", Maps.of(
			"root", "work", "path", "/leading.txt", "content", "ok"));
		assertEquals("ok", Files.readString(workspace.resolve("leading.txt")));

		ACell read = run("v/ops/file/read",
			Maps.of("root", "work", "path", "/leading.txt"));
		assertEquals("ok", RT.ensureString(RT.getIn(read, "content")).toString());
	}

	@Test
	public void testLeadingSlashStillRejectsTraversal() {
		// "/" prefix doesn't grant escape — "/../etc/passwd" still escapes after
		// the leading slash is stripped.
		Job job = runRaw("v/ops/file/read",
			Maps.of("root", "work", "path", "/../etc/passwd"));
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	public void testRejectsSymlinkEscape() throws IOException {
		// Plant a symlink inside the root pointing outside, then try to read it.
		// Skip on platforms where unprivileged symlink creation isn't allowed.
		Path outside = Files.createTempFile("file-adapter-outside-", ".txt");
		Files.writeString(outside, "secrets");
		Path link = workspace.resolve("escape-link");
		try {
			Files.createSymbolicLink(link, outside);
		} catch (Exception e) {
			// e.g. Windows without dev mode / admin
			Files.deleteIfExists(outside);
			return;
		}
		try {
			Job job = runRaw("v/ops/file/read", Maps.of("root", "work", "path", "escape-link"));
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().toLowerCase().contains("symlink"),
				"unexpected error: " + job.getErrorMessage());
		} finally {
			Files.deleteIfExists(link);
			Files.deleteIfExists(outside);
		}
	}

	@Test
	public void testReadOnlyAllowsReads() throws IOException {
		// Drop a file directly into the read-only root, then verify we can read it.
		Files.writeString(readonly.resolve("ok.txt"), "hi");
		ACell read = run("v/ops/file/read", Maps.of("root", "ro", "path", "ok.txt"));
		assertEquals("hi", RT.ensureString(RT.getIn(read, "content")).toString());
	}

	// =========================================================
	// Capability gating

	private static String capDenialOf(Runnable r) {
		try {
			r.run();
			return null;
		} catch (RuntimeException e) {
			return e.getMessage();
		}
	}

	@Test
	public void testCapsGateFileWrites() {
		// Caller has a UCAN-style cap restricting writes to file/work/cap-write/.
		// Writing inside that subtree succeeds; outside fails before the
		// adapter is even dispatched. JobManager throws synchronously on
		// cap denial (no Job is created).
		convex.core.data.AVector<convex.core.data.ACell> caps = convex.core.data.Vectors.of(
			convex.auth.ucan.Capability.create(
				convex.core.data.Strings.create("file/work/cap-write/"),
				convex.auth.ucan.Capability.CRUD_WRITE),
			convex.auth.ucan.Capability.create(
				convex.core.data.Strings.create("file/work/"),
				convex.auth.ucan.Capability.CRUD_READ)
		);
		RequestContext gated = RequestContext.of(
			convex.core.data.Strings.create(DID)).withCaps(caps);

		// mkdir within scope — allowed
		Job mkdirJob = engine.jobs().invokeOperation("v/ops/file/mkdir",
			Maps.of("root", "work", "path", "cap-write", "parents", true), gated);
		mkdirJob.awaitResult(5000);
		assertEquals(Status.COMPLETE, mkdirJob.getStatus());

		// Write within scope — allowed
		Job okWrite = engine.jobs().invokeOperation("v/ops/file/write",
			Maps.of("root", "work", "path", "cap-write/ok.txt", "content", "ok"),
			gated);
		okWrite.awaitResult(5000);
		assertEquals(Status.COMPLETE, okWrite.getStatus());

		// Read anywhere in work — allowed (read cap covers all of file/work/)
		Job okRead = engine.jobs().invokeOperation("v/ops/file/read",
			Maps.of("root", "work", "path", "cap-write/ok.txt"),
			gated);
		okRead.awaitResult(5000);
		assertEquals(Status.COMPLETE, okRead.getStatus());

		// Write outside scope — denied (only have read on broader file/work/)
		String denied = capDenialOf(() -> engine.jobs().invokeOperation("v/ops/file/write",
			Maps.of("root", "work", "path", "cap-outside.txt", "content", "nope"),
			gated));
		assertNotNull(denied, "should have been denied");
		assertTrue(denied.contains("Capability denied"),
			"expected capability denial, got: " + denied);

		// Different root entirely — denied
		String otherDenied = capDenialOf(() -> engine.jobs().invokeOperation("v/ops/file/read",
			Maps.of("root", "ro", "path", "ok.txt"), gated));
		assertNotNull(otherDenied, "cross-root should have been denied");
		assertTrue(otherDenied.contains("Capability denied"),
			"expected capability denial for cross-root, got: " + otherDenied);
	}

	@Test
	public void testCapsGateFileDelete() {
		// crud (no /verb) covers read+write+delete. Verify delete works
		// inside scope and is denied outside.
		convex.core.data.AVector<convex.core.data.ACell> caps = convex.core.data.Vectors.of(
			convex.auth.ucan.Capability.create(
				convex.core.data.Strings.create("file/work/cap-del/"),
				convex.core.data.Strings.create("crud"))
		);
		RequestContext gated = RequestContext.of(
			convex.core.data.Strings.create(DID)).withCaps(caps);
		RequestContext free = RequestContext.of(convex.core.data.Strings.create(DID));

		// Set up files using an unconstrained ctx.
		engine.jobs().invokeOperation("v/ops/file/mkdir",
			Maps.of("root", "work", "path", "cap-del", "parents", true), free).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/file/write",
			Maps.of("root", "work", "path", "cap-del/doomed.txt", "content", "x"),
			free).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/file/write",
			Maps.of("root", "work", "path", "cap-del-outside.txt", "content", "x"),
			free).awaitResult(5000);

		// Delete inside scope — allowed via crud
		Job okDelete = engine.jobs().invokeOperation("v/ops/file/delete",
			Maps.of("root", "work", "path", "cap-del/doomed.txt"), gated);
		okDelete.awaitResult(5000);
		assertEquals(Status.COMPLETE, okDelete.getStatus());

		// Delete outside scope — denied
		String denied = capDenialOf(() -> engine.jobs().invokeOperation("v/ops/file/delete",
			Maps.of("root", "work", "path", "cap-del-outside.txt"), gated));
		assertNotNull(denied, "delete outside scope should be denied");
		assertTrue(denied.contains("Capability denied"),
			"expected capability denial, got: " + denied);
	}

	// =========================================================
	// Default (no config) inertness

	@Test
	public void testDLFSBackedRoot() {
		// Configure a file: root that points at a DLFS drive. Operations should
		// route through DLFS but use the same file: surface as host roots.
		Engine eng = Engine.createTemp(Maps.of(
			"file", Maps.of("roots", Maps.of(
				"shared", Maps.of("dlfs", "shared-drive")
			))
		));
		Engine.addDemoAssets(eng);

		convex.core.data.AString did = convex.core.data.Strings.create(
			"did:key:z6Mk-test-FileAdapterTest-dlfs");

		// roots reports the dlfs-backed root with kind=dlfs.
		Job rootsJob = eng.jobs().invokeOperation(
			"v/ops/file/roots", Maps.empty(), RequestContext.of(did));
		rootsJob.awaitResult(2000);
		AVector<?> rootsList = RT.ensureVector(RT.getIn(rootsJob.getOutput(), "roots"));
		assertEquals(1, rootsList.count());
		assertEquals("dlfs", RT.ensureString(RT.getIn(rootsList.get(0), "kind")).toString());
		assertEquals("shared", RT.ensureString(RT.getIn(rootsList.get(0), "name")).toString());

		// Write through file: surface
		Job writeJob = eng.jobs().invokeOperation(
			"v/ops/file/write",
			Maps.of("root", "shared", "path", "hello.txt", "content", "via file"),
			RequestContext.of(did));
		writeJob.awaitResult(2000);
		assertEquals(Status.COMPLETE, writeJob.getStatus());
		assertTrue(RT.bool(RT.getIn(writeJob.getOutput(), "created")));

		// Read it back through file:
		Job readJob = eng.jobs().invokeOperation(
			"v/ops/file/read",
			Maps.of("root", "shared", "path", "hello.txt"),
			RequestContext.of(did));
		readJob.awaitResult(2000);
		assertEquals("via file", RT.ensureString(RT.getIn(readJob.getOutput(), "content")).toString());

		// And confirm the same data is visible via dlfs: surface
		Job dlfsRead = eng.jobs().invokeOperation(
			"v/ops/dlfs/read",
			Maps.of("drive", "shared-drive", "path", "/hello.txt"),
			RequestContext.of(did));
		dlfsRead.awaitResult(2000);
		assertEquals("via file", RT.ensureString(RT.getIn(dlfsRead.getOutput(), "content")).toString());
	}

	@Test
	public void testDLFSRootIsolatesPerCaller() {
		// Each caller's drive view is signed with their own key — writes by
		// one DID must not be visible to another.
		Engine eng = Engine.createTemp(Maps.of(
			"file", Maps.of("roots", Maps.of(
				"private", Maps.of("dlfs", "private-drive")
			))
		));
		Engine.addDemoAssets(eng);

		convex.core.data.AString alice = convex.core.data.Strings.create(
			"did:key:z6Mk-test-FileAdapterTest-alice");
		convex.core.data.AString bob = convex.core.data.Strings.create(
			"did:key:z6Mk-test-FileAdapterTest-bob");

		eng.jobs().invokeOperation("v/ops/file/write",
			Maps.of("root", "private", "path", "secret.txt", "content", "alice's note"),
			RequestContext.of(alice)).awaitResult(2000);

		// Bob's view of the same drive name must be empty.
		Job bobList = eng.jobs().invokeOperation(
			"v/ops/file/list", Maps.of("root", "private"),
			RequestContext.of(bob));
		bobList.awaitResult(2000);
		AVector<?> entries = RT.ensureVector(RT.getIn(bobList.getOutput(), "entries"));
		assertEquals(0, entries.count(), "Bob should not see Alice's drive contents");
	}

	@Test
	public void testDefaultEngineHasTempRoot() {
		// With no file.roots configured, the adapter defaults to creating an
		// ephemeral 'tmp' root. Agents get a usable scratch space immediately.
		Engine bare = TestEngine.ENGINE;
		convex.core.data.AString did = convex.core.data.Strings.create(DID);

		Job rootsJob = bare.jobs().invokeOperation(
			"v/ops/file/roots", Maps.empty(), RequestContext.of(did));
		rootsJob.awaitResult(2000);
		assertEquals(Status.COMPLETE, rootsJob.getStatus());
		AVector<?> roots = RT.ensureVector(RT.getIn(rootsJob.getOutput(), "roots"));
		assertEquals(1, roots.count());
		assertTrue(roots.toString().contains("tmp"));

		// Default temp root is writable.
		Job writeJob = bare.jobs().invokeOperation(
			"v/ops/file/write",
			Maps.of("root", "tmp", "path", "scratch.txt", "content", "ok"),
			RequestContext.of(did));
		writeJob.awaitResult(2000);
		assertEquals(Status.COMPLETE, writeJob.getStatus());
	}

	@Test
	public void testExplicitTempRoot() throws IOException {
		// Configure an engine with an explicit temp root and verify it lands
		// in the system tmp dir, is writable, and is cleaned up on demand.
		Engine tempEng = Engine.createTemp(Maps.of(
			"file", Maps.of("roots", Maps.of(
				"scratch", Maps.of("temp", true, "prefix", "fa-test-")
			))
		));
		Engine.addDemoAssets(tempEng);

		Job rootsJob = tempEng.jobs().invokeOperation(
			"v/ops/file/roots", Maps.empty(),
			RequestContext.of(convex.core.data.Strings.create(DID)));
		rootsJob.awaitResult(2000);
		assertEquals(Status.COMPLETE, rootsJob.getStatus());

		AVector<?> rootsList = RT.ensureVector(RT.getIn(rootsJob.getOutput(), "roots"));
		assertEquals(1, rootsList.count());
		String tempRootPath = RT.ensureString(RT.getIn(rootsList.get(0), "path")).toString();

		// Should be a real directory under the system tmp.
		Path tempPath = Path.of(tempRootPath);
		assertTrue(Files.isDirectory(tempPath));
		assertTrue(tempPath.getFileName().toString().startsWith("fa-test-"),
			"unexpected temp dir name: " + tempPath.getFileName());

		// And it's writable.
		Job writeJob = tempEng.jobs().invokeOperation(
			"v/ops/file/write",
			Maps.of("root", "scratch", "path", "hi.txt", "content", "hello"),
			RequestContext.of(convex.core.data.Strings.create(DID)));
		writeJob.awaitResult(2000);
		assertEquals(Status.COMPLETE, writeJob.getStatus());
		assertEquals("hello", Files.readString(tempPath.resolve("hi.txt")));
	}
}
