package covia.adapter.discord;

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

class DiscordModuleIT {
	@TempDir Path temp;
	@Test void shadedModuleLoadsOutsideVenueClasspath() throws Exception {
		Path venue=Path.of(System.getProperty("covia.venue.jar")),module=Path.of(System.getProperty("covia.module.jar"));
		Assumptions.assumeTrue(Files.isRegularFile(venue));Assumptions.assumeTrue(Files.isRegularFile(module));
		assertFalse(has(module,"convex/core/"));assertFalse(has(module,"covia/venue/"));assertFalse(has(module,"covia/grid/"));assertFalse(has(module,"org/slf4j/"));
		assertTrue(has(module,"net/dv8tion/jda/"),"JDA must be bundled");assertTrue(has(module,"okhttp3/"),"JDA HTTP stack must be bundled");
		Path log=temp.resolve("discord-module.log");String executable=System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java";
		List<String> command=List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-cp",venue+java.io.File.pathSeparator+System.getProperty("covia.test.classes"),"covia.adapter.discord.DiscordModuleSmokeMain",module.toString());
		Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();assertTrue(p.waitFor(Duration.ofSeconds(120).toMillis(),TimeUnit.MILLISECONDS));String out=Files.readString(log);assertEquals(0,p.exitValue(),out);assertTrue(out.contains("DISCORD_MODULE_SMOKE_OK"),out);
	}
	private static boolean has(Path jar,String prefix)throws Exception{try(ZipFile z=new ZipFile(jar.toFile())){return z.stream().anyMatch(e->e.getName().startsWith(prefix));}}
}
