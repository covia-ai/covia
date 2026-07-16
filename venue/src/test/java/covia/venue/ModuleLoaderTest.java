package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.grid.Job;
import covia.grid.Status;

/**
 * End-to-end tests for venue module loading: a module jar is built AT TEST
 * RUNTIME (adapter class + service declaration + an asset JSON that exists
 * ONLY in the jar), declared in config, and must come up as an ordinary
 * adapter — registered, catalog-materialised, invocable.
 *
 * <p>Uses throwaway engines (bespoke config), not the shared TestEngine —
 * per the TestEngine guidance.</p>
 */
public class ModuleLoaderTest {

	/** Builds a minimal module jar from the test classpath's compiled
	 *  ModuleTestAdapter, plus a service file and a jar-only resource. */
	private static Path buildModuleJar(Path dir) throws Exception {
		Path jar = dir.resolve("modtest-module.jar");
		try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
			String cls = "modtest/ModuleTestAdapter.class";
			jos.putNextEntry(new ZipEntry(cls));
			try (InputStream is = ModuleLoaderTest.class.getResourceAsStream("/" + cls)) {
				jos.write(is.readAllBytes());
			}
			jos.closeEntry();

			jos.putNextEntry(new ZipEntry("META-INF/services/covia.adapter.AAdapter"));
			jos.write("modtest.ModuleTestAdapter\n".getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();

			// The adapter's asset JSON — deliberately NOT on the test
			// classpath, so it can only resolve through the module loader.
			jos.putNextEntry(new ZipEntry("modtest/echo.json"));
			jos.write(("{\n"
				+ "\t\"name\": \"Modtest Echo\",\n"
				+ "\t\"description\": \"Echo operation from a module jar\",\n"
				+ "\t\"operation\": {\n"
				+ "\t\t\"adapter\": \"modtest\",\n"
				+ "\t\t\"input\": {}\n"
				+ "\t}\n"
				+ "}").getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		return jar;
	}

	private static Engine bootWith(ACell modulesEntry) throws Exception {
		AMap<AString, ACell> config = Maps.of(Config.MODULES, Vectors.of(modulesEntry));
		Engine engine = Engine.createTemp(config);
		Engine.addDemoAssets(engine);
		return engine;
	}

	@Test
	public void testModuleLoadsAndServes(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		Engine engine = bootWith(Strings.create(jar.toString()));
		try {
			// Registered, and defined by the module loader (child-first for
			// the non-shared modtest.* package)
			AAdapter adapter = engine.getAdapter("modtest");
			assertNotNull(adapter, "module adapter must register");
			assertTrue(adapter.getClass().getClassLoader() instanceof ModuleClassLoader,
				"module adapter must be defined by the module classloader, was: "
					+ adapter.getClass().getClassLoader());

			// Catalog materialised from a resource that exists ONLY in the jar
			RequestContext ctx = RequestContext.of(Strings.create("did:test:moduleloader"));
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/modtest/echo"), ctx),
				"module op must materialise into the catalog");

			// Invocable end-to-end like any other op
			Job job = engine.jobs().invokeOperation("v/ops/modtest/echo",
				Maps.of(Strings.create("value"), Strings.create("vroom")), ctx);
			ACell result = job.awaitResult(15000);
			assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
			assertEquals(Strings.create("vroom"), RT.getIn(result, "value"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testSha256PinVerification(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		String goodHash = Blob.wrap(MessageDigest.getInstance("SHA-256")
			.digest(Files.readAllBytes(jar))).toHexString();

		// Correct pin loads
		Engine good = bootWith(Maps.of(
			Strings.create("path"), Strings.create(jar.toString()),
			Strings.create("sha256"), Strings.create(goodHash)));
		try {
			assertNotNull(good.getAdapter("modtest"));
		} finally {
			good.close();
		}

		// Wrong pin refuses to load, fail-fast at boot
		AMap<AString, ACell> badConfig = Maps.of(Config.MODULES, Vectors.of(Maps.of(
			Strings.create("path"), Strings.create(jar.toString()),
			Strings.create("sha256"), Strings.create("00".repeat(32)))));
		Engine bad = Engine.createTemp(badConfig);
		try {
			IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> Engine.addDemoAssets(bad));
			assertTrue(e.getMessage().contains("integrity check FAILED"), e.getMessage());
		} finally {
			bad.close();
		}
	}

	@Test
	public void testMissingJarFailsBoot(@TempDir Path dir) {
		AMap<AString, ACell> config = Maps.of(Config.MODULES,
			Vectors.of(Strings.create(dir.resolve("no-such-module.jar").toString())));
		Engine engine = Engine.createTemp(config);
		try {
			IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> Engine.addDemoAssets(engine));
			assertTrue(e.getMessage().contains("Module jar not found"), e.getMessage());
		} finally {
			engine.close();
		}
	}
}
