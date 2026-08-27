package covia.adapter.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the packaged module with only the venue jar as its parent. */
class DocumentsModuleIT {

	@TempDir Path temp;

	@Test
	void shadedModuleLoadsAndExtractsOutsideTheVenueClasspath() throws Exception {
		Path venueJar = Path.of(System.getProperty("covia.venue.jar"));
		Path moduleJar = Path.of(System.getProperty("covia.module.jar"));
		Assumptions.assumeTrue(Files.isRegularFile(venueJar),
			"venue executable is produced during the install phase");
		Assumptions.assumeTrue(Files.isRegularFile(moduleJar),
			"shaded documents module was not packaged");

		assertFalse(containsPrefix(moduleJar, "convex/core/"));
		assertFalse(containsPrefix(moduleJar, "covia/venue/"));
		assertFalse(containsPrefix(moduleJar, "covia/grid/"));
		assertFalse(containsPrefix(moduleJar, "org/slf4j/"));
		assertTrue(containsPrefix(moduleJar, "covia/adapter/documents/"));
		assertTrue(containsPrefix(moduleJar, "org/apache/pdfbox/"), "PDFBox ships in the module");
		assertTrue(containsPrefix(moduleJar, "org/apache/poi/"), "POI ships in the module");
		assertTrue(contains(moduleJar, "META-INF/services/covia.adapter.AAdapter"));

		// The fixture is built here, where the parsers are on the classpath; the
		// child process has only the venue jar and gets the parsers from the module.
		Path docs = Files.createDirectory(temp.resolve("docs"));
		Files.write(docs.resolve("report.pdf"), Fixtures.pdf("Smoke", "Smoke page one"));

		Path log = temp.resolve("documents-module.log");
		String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
		List<String> command = List.of(
			Path.of(System.getProperty("java.home"), "bin", executable).toString(),
			"-cp", venueJar + java.io.File.pathSeparator + System.getProperty("covia.test.classes"),
			"covia.adapter.documents.DocumentsModuleSmokeMain",
			moduleJar.toString(), docs.toAbsolutePath().toString());
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(process.waitFor(Duration.ofSeconds(120).toMillis(), TimeUnit.MILLISECONDS),
				"module smoke process timed out");
			String output = Files.readString(log);
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains("DOCUMENTS_MODULE_SMOKE_OK"), output);
		} finally {
			if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
		}
	}

	private static boolean containsPrefix(Path jar, String prefix) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.stream().anyMatch(entry -> entry.getName().startsWith(prefix));
		}
	}

	private static boolean contains(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getEntry(name) != null;
		}
	}
}
