package covia.adapter.whatsapp;

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

class WhatsAppModuleIT {
	@TempDir Path temp;
	@Test void shadedModuleLoadsOutsideVenueClasspath() throws Exception {
		Path venue=Path.of(System.getProperty("covia.venue.jar")),module=Path.of(System.getProperty("covia.module.jar"));
		Assumptions.assumeTrue(Files.isRegularFile(venue));Assumptions.assumeTrue(Files.isRegularFile(module));
		assertFalse(has(module,"convex/core/"));assertFalse(has(module,"covia/venue/"));assertFalse(has(module,"covia/grid/"));assertFalse(has(module,"org/slf4j/"));
		Path log=temp.resolve("whatsapp-module.log");String executable=System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java";
		List<String> command=List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-cp",venue+java.io.File.pathSeparator+System.getProperty("covia.test.classes"),"covia.adapter.whatsapp.WhatsAppModuleSmokeMain",module.toString());
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(process.waitFor(Duration.ofSeconds(120).toMillis(), TimeUnit.MILLISECONDS),
				"module smoke process timed out");
			String output = Files.readString(log);
			assertEquals(0, process.exitValue(), output);
			assertTrue(output.contains("whatsapp_MODULE_SMOKE_OK"), output);
		} finally {
			if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
		}
	}
	private static boolean has(Path jar,String prefix)throws Exception{try(ZipFile z=new ZipFile(jar.toFile())){return z.stream().anyMatch(e->e.getName().startsWith(prefix));}}
}
