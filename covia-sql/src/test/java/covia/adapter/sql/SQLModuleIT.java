package covia.adapter.sql;

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

/** Exercises the packaged module with only the SQL-free venue jar as parent. */
class SQLModuleIT {
	@TempDir Path temp;

	@Test
	void shadedModuleLoadsAndInvokesOutsideTheVenueClasspath() throws Exception {
		Path venueJar = Path.of(System.getProperty("covia.venue.jar"));
		Path moduleJar = Path.of(System.getProperty("covia.module.jar"));
		Assumptions.assumeTrue(Files.isRegularFile(venueJar),
			"venue executable is produced during the install phase");
		Assumptions.assumeTrue(Files.isRegularFile(moduleJar),
			"shaded SQL module was not packaged");
		assertFalse(containsPrefix(moduleJar, "convex/core/"),
			"venue-provided convex-core must not be bundled in the SQL module");
		assertFalse(containsPrefix(moduleJar, "convex/peer/"),
			"venue-provided convex-peer must not be bundled in the SQL module");
		assertFalse(containsPrefix(moduleJar, "covia/venue/"),
			"venue implementation must not be bundled in the SQL module");
		assertFalse(containsPrefix(moduleJar, "covia/grid/"),
			"venue SPI must not be bundled in the SQL module");
		Path log = temp.resolve("module-smoke.log");

		String executable = System.getProperty("os.name", "").startsWith("Windows")
			? "java.exe" : "java";
		List<String> command = List.of(
			Path.of(System.getProperty("java.home"), "bin", executable).toString(),
			"-cp", venueJar + java.io.File.pathSeparator
				+ System.getProperty("covia.test.classes"),
			"covia.adapter.sql.SQLModuleSmokeMain", moduleJar.toString());

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS),
				"module smoke process timed out");
			String output = Files.readString(log);
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains("SQL_MODULE_SMOKE_OK"), output);
		} finally {
			if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
		}
	}

	private static boolean containsPrefix(Path jar, String prefix) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.stream().anyMatch(entry -> entry.getName().startsWith(prefix));
		}
	}
}
