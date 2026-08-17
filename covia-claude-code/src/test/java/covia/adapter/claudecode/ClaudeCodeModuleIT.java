package covia.adapter.claudecode;

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

/** Exercises the packaged module with only the venue jar as parent classpath. */
class ClaudeCodeModuleIT {
	@TempDir Path temp;

	@Test
	void shadedModuleLoadsAndDrivesTheCliOutsideTheVenueClasspath() throws Exception {
		Path venueJar = Path.of(System.getProperty("covia.venue.jar"));
		Path moduleJar = Path.of(System.getProperty("covia.module.jar"));
		Assumptions.assumeTrue(Files.isRegularFile(venueJar),
			"venue executable is produced during the install phase");
		Assumptions.assumeTrue(Files.isRegularFile(moduleJar),
			"shaded Claude Code module was not packaged");
		assertFalse(containsPrefix(moduleJar, "convex/core/"),
			"venue-provided convex-core must not be bundled in the module");
		assertFalse(containsPrefix(moduleJar, "covia/venue/"),
			"venue implementation must not be bundled in the module");
		assertFalse(containsPrefix(moduleJar, "org/slf4j/"),
			"logging must resolve parent-first from the venue");
		assertTrue(containsPrefix(moduleJar, "covia/adapter/claudecode/"),
			"the adapter classes must be bundled");
		assertTrue(zipHasEntry(moduleJar, "skills/claudecode.json"),
			"the module-shipped agent skill must be bundled");
		Path log = temp.resolve("module-smoke.log");

		String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
		List<String> command = List.of(
			Path.of(System.getProperty("java.home"), "bin", executable).toString(),
			"-cp", venueJar + java.io.File.pathSeparator + System.getProperty("covia.test.classes"),
			"covia.adapter.claudecode.ClaudeCodeModuleSmokeMain", moduleJar.toString());

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		assertTrue(process.waitFor(Duration.ofSeconds(120).toMillis(), TimeUnit.MILLISECONDS),
			"module smoke process timed out");
		String output = Files.readString(log);
		assertEquals(0, process.exitValue(), output);
		assertTrue(output.contains("CLAUDECODE_MODULE_SMOKE_OK"), output);
	}

	private static boolean containsPrefix(Path jar, String prefix) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.stream().anyMatch(entry -> entry.getName().startsWith(prefix));
		}
	}

	private static boolean zipHasEntry(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getEntry(name) != null;
		}
	}
}
