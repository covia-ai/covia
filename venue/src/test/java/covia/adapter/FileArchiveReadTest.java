package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/** FileAdapter read-through into zip/jar archives via the {@code x.zip!/entry}
 *  convention (jdk.zipfs), including the safety guarantee that a read never
 *  creates an archive. */
public class FileArchiveReadTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final String DID = "did:key:z6Mk-test-FileArchiveReadTest";

	@BeforeAll
	static void setup() throws IOException {
		// A real archive in the root: readme.txt, dir/, dir/inner.txt
		ByteArrayOutputStream archive = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(archive)) {
			zos.putNextEntry(new ZipEntry("readme.txt"));
			zos.write("hello".getBytes());
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("dir/"));
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("dir/inner.txt"));
			zos.write("nested".getBytes());
			zos.closeEntry();
		}
		TestEngine.writeTempFile("FileArchiveReadTest/app.zip", archive.toByteArray());
		TestEngine.writeTempFile("FileArchiveReadTest/notazip.txt", "plain");
		// A .zip-named file that is NOT actually a zip — exercises the mount failure.
		TestEngine.writeTempFile("FileArchiveReadTest/fake.zip", "not really a zip");
	}

	private RequestContext ctx() { return RequestContext.of(Strings.create(DID)); }

	private ACell run(String op, ACell input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx());
		ACell result = job.awaitResult(5000);
		if (job.getStatus() == Status.FAILED) throw new AssertionError("Job failed: " + job.getErrorMessage());
		return result;
	}

	private Job runRaw(String op, ACell input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx());
		try { job.awaitResult(5000); } catch (RuntimeException ignored) { }
		return job;
	}

	// =========================================================

	@Test
	public void testReadEntryFromZip() {
		ACell r = run("v/ops/file/read", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/readme.txt"));
		assertEquals("hello", RT.ensureString(RT.getIn(r, "content")).toString());
	}

	@Test
	public void testReadNestedEntry() {
		ACell r = run("v/ops/file/read", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/dir/inner.txt"));
		assertEquals("nested", RT.ensureString(RT.getIn(r, "content")).toString());
	}

	@Test
	public void testListInsideZip() {
		ACell root = run("v/ops/file/list", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/"));
		AVector<?> top = RT.ensureVector(RT.getIn(root, "entries"));
		boolean hasReadme = false, hasDir = false;
		for (long i = 0; i < top.count(); i++) {
			String name = RT.ensureString(RT.getIn(top.get(i), "name")).toString();
			if (name.equals("readme.txt")) hasReadme = true;
			if (name.equals("dir")) hasDir = true;
		}
		assertTrue(hasReadme && hasDir, "zip root listing must show readme.txt and dir");

		ACell sub = run("v/ops/file/list", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/dir"));
		AVector<?> subEntries = RT.ensureVector(RT.getIn(sub, "entries"));
		assertEquals("inner.txt", RT.ensureString(RT.getIn(subEntries.get(0), "name")).toString());
	}

	@Test
	public void testStatEntry() {
		ACell r = run("v/ops/file/stat", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/readme.txt"));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(r, "exists"));
		assertEquals("file", RT.ensureString(RT.getIn(r, "type")).toString());
		assertEquals(5L, RT.ensureLong(RT.getIn(r, "size")).longValue());
	}

	@Test
	public void testStatMissingEntryInExistingZip() {
		ACell r = run("v/ops/file/stat", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/nope.txt"));
		assertEquals(convex.core.data.prim.CVMBool.FALSE, RT.getIn(r, "exists"));
	}

	@Test
	public void testReadMissingArchiveDoesNotCreateIt() {
		Job job = runRaw("v/ops/file/read", Maps.of("root", "tmp", "path", "FileArchiveReadTest/missing.zip!/x"));
		assertEquals(Status.FAILED, job.getStatus(), "reading into a missing archive must fail");
		ACell stat = run("v/ops/file/stat", Maps.of("root", "tmp", "path", "FileArchiveReadTest/missing.zip"));
		assertEquals(convex.core.data.prim.CVMBool.FALSE, RT.getIn(stat, "exists"),
			"the read path must NEVER create the archive file");
	}

	@Test
	public void testReadNonZipFails() {
		// .zip extension triggers archive mode; the bytes are not a zip → mount fails.
		Job job = runRaw("v/ops/file/read", Maps.of("root", "tmp", "path", "FileArchiveReadTest/fake.zip!/x"));
		assertEquals(Status.FAILED, job.getStatus(), "a non-zip file must not mount as an archive");
		assertFalse(job.getErrorMessage().isBlank());
	}

	@Test
	public void testLiteralBangFilenameIsLiteral() {
		// '!' NOT following a .zip/.jar extension is an ordinary filename character
		// (jar-URL '!' is an archive construct, not a file-path one) — round-trips.
		run("v/ops/file/write", Maps.of("root", "tmp", "path", "FileArchiveReadTest/note!important.txt", "content", "hi"));
		ACell r = run("v/ops/file/read", Maps.of("root", "tmp", "path", "FileArchiveReadTest/note!important.txt"));
		assertEquals("hi", RT.ensureString(RT.getIn(r, "content")).toString());
	}

	@Test
	public void testEntryEscapeRejected() {
		Job job = runRaw("v/ops/file/read", Maps.of("root", "tmp", "path", "FileArchiveReadTest/app.zip!/../escape"));
		assertEquals(Status.FAILED, job.getStatus(), "an entry escaping the archive must be rejected");
	}

	@Test
	public void testWriteIntoArchiveRejected() {
		Job job = runRaw("v/ops/file/write", Maps.of(
			"root", "tmp", "path", "FileArchiveReadTest/app.zip!/injected.txt", "content", "x"));
		assertEquals(Status.FAILED, job.getStatus(), "writing into an archive via file: must be rejected");
		assertFalse(job.getErrorMessage().isBlank());
	}
}
