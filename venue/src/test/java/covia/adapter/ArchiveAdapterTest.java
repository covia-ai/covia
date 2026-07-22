package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.Config;
import covia.venue.RequestContext;

public class ArchiveAdapterTest {

	@TempDir static Path workspace;
	@TempDir static Path readonly;

	private static Engine engine;
	private static final String DID = "did:key:z6Mk-test-ArchiveAdapterTest";

	@BeforeAll
	static void setup() throws IOException {
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			"file", Maps.of("roots", Maps.of(
				"work", workspace.toAbsolutePath().toString(),
				"ro", Maps.of(
					"path", readonly.toAbsolutePath().toString(),
					"readOnly", true)
			))
		));
		Engine.addDemoAssets(engine);

		// Seed a small tree under the 'work' root: data/a.txt, data/sub/b.txt
		Files.createDirectories(workspace.resolve("data/sub"));
		Files.writeString(workspace.resolve("data/a.txt"), "alpha");
		Files.writeString(workspace.resolve("data/sub/b.txt"), "bravo");
	}

	private RequestContext ctx() {
		return RequestContext.of(Strings.create(DID));
	}

	private ACell run(String op, ACell input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx());
		ACell result = job.awaitResult(5000);
		if (job.getStatus() == Status.FAILED) {
			throw new AssertionError("Job failed: " + job.getErrorMessage());
		}
		return result;
	}

	private Job runRaw(String op, ACell input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx());
		try { job.awaitResult(5000); } catch (RuntimeException ignored) { }
		return job;
	}

	/** Build a zip in memory from name -> bytes (a '/'-terminated name is a dir). */
	private static byte[] makeZip(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(bos)) {
			for (var e : entries.entrySet()) {
				zos.putNextEntry(new ZipEntry(e.getKey()));
				if (e.getValue() != null) zos.write(e.getValue());
				zos.closeEntry();
			}
		}
		return bos.toByteArray();
	}

	// =========================================================

	@Test
	public void testZipToFileThenList() {
		ACell zipped = run("v/ops/archive/zip", Maps.of(
			"root", "work", "path", "data",
			"destRoot", "work", "destPath", "out.zip"));
		assertTrue(RT.getIn(zipped, "entries") != null);
		assertTrue(Files.exists(workspace.resolve("out.zip")), "zip file must be created");

		ACell listed = run("v/ops/archive/list", Maps.of("root", "work", "path", "out.zip"));
		AVector<?> entries = RT.ensureVector(RT.getIn(listed, "entries"));
		boolean hasA = false, hasB = false;
		for (long i = 0; i < entries.count(); i++) {
			String name = RT.ensureString(RT.getIn(entries.get(i), "name")).toString();
			if (name.equals("data/a.txt")) hasA = true;
			if (name.equals("data/sub/b.txt")) hasB = true;
		}
		assertTrue(hasA, "listing must contain data/a.txt");
		assertTrue(hasB, "listing must contain data/sub/b.txt (recursive)");
	}

	@Test
	public void testZipToAssetThenExtractRoundTrips() throws IOException {
		ACell zipped = run("v/ops/archive/zip", Maps.of(
			"root", "work", "paths", Vectors.of(Strings.create("data"))));
		String assetRef = RT.ensureString(RT.getIn(zipped, "asset")).toString();
		assertNotNull(assetRef);
		assertTrue(assetRef.contains("/a/"), "zip must return a CAS asset ref: " + assetRef);

		run("v/ops/archive/extract", Maps.of(
			"asset", assetRef, "destRoot", "work", "destPath", "restored"));
		assertEquals("alpha", Files.readString(workspace.resolve("restored/data/a.txt")));
		assertEquals("bravo", Files.readString(workspace.resolve("restored/data/sub/b.txt")));
	}

	@Test
	public void testExtractFromInlineBytes() throws IOException {
		Map<String, byte[]> m = new LinkedHashMap<>();
		m.put("hello.txt", "world".getBytes(StandardCharsets.UTF_8));
		String b64 = Base64.getEncoder().encodeToString(makeZip(m));

		run("v/ops/archive/extract", Maps.of(
			"bytes", b64, "destRoot", "work", "destPath", "frombytes"));
		assertEquals("world", Files.readString(workspace.resolve("frombytes/hello.txt")));
	}

	@Test
	public void testZipSlipRejectedAndNoFileEscapes() throws IOException {
		// A malicious archive whose entry escapes the destination via '..'.
		Map<String, byte[]> m = new LinkedHashMap<>();
		m.put("../escaped.txt", "pwned".getBytes(StandardCharsets.UTF_8));
		Files.write(workspace.resolve("evil.zip"), makeZip(m));

		Job job = runRaw("v/ops/archive/extract", Maps.of(
			"root", "work", "path", "evil.zip",
			"destRoot", "work", "destPath", "sandbox"));
		assertEquals(Status.FAILED, job.getStatus(), "zip-slip must be rejected");
		assertTrue(job.getErrorMessage().toLowerCase().contains("escape"),
			"error should mention escape: " + job.getErrorMessage());
		// The escaping file must NOT have been written outside the sandbox.
		assertFalse(Files.exists(workspace.resolve("escaped.txt")),
			"zip-slip entry must never land outside the destination");
	}

	@Test
	public void testMissingSourceFailsAndCreatesNothing() {
		Job job = runRaw("v/ops/archive/list", Maps.of("root", "work", "path", "does-not-exist.zip"));
		assertEquals(Status.FAILED, job.getStatus(), "missing archive must fail");
		assertFalse(Files.exists(workspace.resolve("does-not-exist.zip")),
			"a read-path op must never create the archive file");
	}

	@Test
	public void testExtractToReadOnlyRootRejected() {
		Job job = runRaw("v/ops/archive/extract", Maps.of(
			"bytes", "UEsDBAoAAAAAAA==",   // any value; rejected before parsing
			"destRoot", "ro", "destPath", "x"));
		assertEquals(Status.FAILED, job.getStatus(), "read-only destination must be rejected");
		assertTrue(job.getErrorMessage().toLowerCase().contains("read-only"),
			"error should mention read-only: " + job.getErrorMessage());
	}

	@Test
	public void testListReportsMetadata() {
		run("v/ops/archive/zip", Maps.of(
			"root", "work", "path", "data",
			"destRoot", "work", "destPath", "meta.zip"));
		ACell listed = run("v/ops/archive/list", Maps.of("root", "work", "path", "meta.zip"));
		assertTrue(RT.getIn(listed, "count") != null, "list must report a count");
		AVector<?> entries = RT.ensureVector(RT.getIn(listed, "entries"));
		assertTrue(entries.count() > 0);
		// every entry carries name + directory flag
		for (long i = 0; i < entries.count(); i++) {
			assertNotNull(RT.getIn(entries.get(i), "name"));
			assertNotNull(RT.getIn(entries.get(i), "directory"));
		}
	}
}
