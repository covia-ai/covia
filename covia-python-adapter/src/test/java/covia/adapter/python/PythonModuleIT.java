package covia.adapter.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import covia.python.PythonRuntime;

/** Exercises the packaged module with only the Python-free venue jar as parent. */
class PythonModuleIT {
	@TempDir Path temp;

	@Test
	void shadedModuleLoadsAndInvokesOutsideTheVenueClasspath() throws Exception {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		Path venueJar = Path.of(System.getProperty("covia.venue.jar"));
		Path moduleJar = Path.of(System.getProperty("covia.module.jar"));
		Assumptions.assumeTrue(Files.isRegularFile(venueJar),
			"venue executable is produced during the install phase");
		Assumptions.assumeTrue(Files.isRegularFile(moduleJar),
			"shaded Python module was not packaged");
		Path script = temp.resolve("smoke.py");
		Files.writeString(script, "def main(value):\n    return value['x'] + 1\n");
		Path log = temp.resolve("module-smoke.log");

		String executable = System.getProperty("os.name", "").startsWith("Windows")
			? "java.exe" : "java";
		List<String> command = new ArrayList<>(List.of(
			Path.of(System.getProperty("java.home"), "bin", executable).toString(),
			"--enable-native-access=ALL-UNNAMED"));
		String configuredLibrary = System.getProperty("covia.python.library");
		if (configuredLibrary != null && !configuredLibrary.isBlank()) {
			command.add("-Dcovia.python.library=" + configuredLibrary);
		}
		command.addAll(List.of(
			"-cp", venueJar
				+ java.io.File.pathSeparator + System.getProperty("covia.test.classes"),
			"covia.adapter.python.PythonModuleSmokeMain",
			moduleJar.toString(), script.toString()));

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS),
				"module smoke process timed out");
			String output = Files.readString(log);
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains("PYTHON_MODULE_SMOKE_OK"), output);
		} finally {
			if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
		}
	}
}
