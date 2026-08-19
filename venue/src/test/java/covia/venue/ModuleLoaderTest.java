package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
import convex.core.data.prim.CVMBool;
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
		AMap<AString, ACell> config = Maps.of(
			Config.MODULES, Vectors.of(modulesEntry),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		Engine engine = Engine.createTemp(config);
		Engine.addDemoAssets(engine);
		return engine;
	}

	@Test
	public void testModuleLoadsAndServes(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		Engine engine = bootWith(Maps.of(
			"path", jar.toString(),
			"config", Maps.of("label", "configured")));
		try {
			// Registered, and defined by the module loader (child-first for
			// the non-shared modtest.* package)
			AAdapter adapter = engine.getAdapter("modtest");
			assertNotNull(adapter, "module adapter must register");
			assertTrue(adapter.getClass().getClassLoader() instanceof ModuleClassLoader,
				"module adapter must be defined by the module classloader, was: "
					+ adapter.getClass().getClassLoader());
			assertTrue(adapter.getDescription().contains("configured"),
				"module-local config must be supplied before registration");

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
	public void testModuleAdapterMayRemainInactive(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		Engine engine = bootWith(Maps.of(
			"path", jar.toString(),
			"config", Maps.of("enabled", false)));
		try {
			assertNull(engine.getAdapter("modtest"));
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

	// ========== Runtime module lifecycle (v/ops/venue/module/*) ==========

	private static Engine bootDynamic(Path dir, boolean anyPath) {
		AMap<AString, ACell> dyn = Maps.of(
			Config.ENABLED, true,
			Strings.create("dir"), Strings.create(dir.toString()));
		if (anyPath) dyn = dyn.assoc(Strings.create("anyPath"), CVMBool.TRUE);
		Engine engine = Engine.createTemp(Maps.of(Config.DYNAMIC_MODULES, dyn,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static ACell asVenue(Engine engine, String op, AMap<AString, ACell> input) throws Exception {
		return engine.jobs().invokeInternal(op, input, engine.venueContext())
			.get(15, TimeUnit.SECONDS);
	}

	private static Throwable rootCause(ExecutionException e) {
		Throwable t = e.getCause();
		while (t instanceof CompletionException && t.getCause() != null && t.getCause() != t) t = t.getCause();
		return t;
	}

	@Test
	public void testRuntimeLoadAndUnloadViaOps(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		Path overrideJar = dir.resolve("override-module.jar");
		Files.copy(jar, overrideJar);
		Engine engine = bootDynamic(dir, false);
		try {
			RequestContext ctx = RequestContext.of(Strings.create("did:test:runtimeload"));
			assertNull(engine.getAdapter("modtest"));
			assertNull(engine.resolveAsset(Strings.create("v/ops/modtest/echo"), ctx));

			// Load by jar NAME inside the staging directory
			ACell loaded = asVenue(engine, "v/ops/venue/module/load", Maps.of(
				Strings.create("module"), Strings.create(jar.getFileName().toString()),
				Strings.create("config"), Maps.of("label", "runtime")));
			assertEquals(Strings.create("modtest-module"), RT.getIn(loaded, "name"));
			AAdapter adapter = engine.getAdapter("modtest");
			assertNotNull(adapter, "module adapter must register at runtime");
			assertTrue(adapter.getDescription().contains("runtime"), "module config reaches the adapter");
			assertNotNull(engine.getModule("modtest-module"));
			assertEquals("modtest-module", engine.moduleOf("modtest").name());

			// Catalog + introspection published incrementally into the LIVE venue
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/modtest/echo"), ctx));
			ACell info = engine.resolvePath(Strings.create("v/info/adapters/modtest"), engine.venueContext());
			assertNotNull(info);
			assertEquals(Strings.create("modtest-module"), RT.getIn(info, "module"));
			ACell modInfo = engine.resolvePath(Strings.create("v/info/modules/modtest-module"), engine.venueContext());
			assertNotNull(modInfo);
			assertEquals(Strings.create("modtest"), RT.getIn(modInfo, "adapters", 0));

			// Invocable end-to-end
			Job job = engine.jobs().invokeOperation("v/ops/modtest/echo",
				Maps.of(Strings.create("value"), Strings.create("hot")), ctx);
			assertEquals(Strings.create("hot"), RT.getIn(job.awaitResult(15000), "value"));

			// Loading the same module again is an operator-directed replacement.
			asVenue(engine, "v/ops/venue/module/load", Maps.of(
				Strings.create("module"), Strings.create(jar.getFileName().toString()),
				Strings.create("config"), Maps.of("label", "replacement")));
			AAdapter replacement = engine.getAdapter("modtest");
			assertNotSame(adapter, replacement);
			assertTrue(replacement.getDescription().contains("replacement"));

			// A differently named module may overwrite the same adapter name too.
			asVenue(engine, "v/ops/venue/module/load", Maps.of(
				Strings.create("module"), Strings.create(overrideJar.getFileName().toString()),
				Strings.create("config"), Maps.of("label", "override")));
			AAdapter override = engine.getAdapter("modtest");
			assertNotSame(replacement, override);
			assertTrue(override.getDescription().contains("override"));

			// Unloading the shadowed module does not remove the newer live adapter.
			ACell unloaded = asVenue(engine, "v/ops/venue/module/unload",
				Maps.of(Strings.create("name"), Strings.create("modtest-module")));
			assertEquals(CVMBool.TRUE, RT.getIn(unloaded, "unloaded"));
			assertSame(override, engine.getAdapter("modtest"));
			assertNull(engine.getModule("modtest-module"));

			// Unloading the current provider removes dispatch but retains metadata.
			asVenue(engine, "v/ops/venue/module/unload",
				Maps.of(Strings.create("name"), Strings.create("override-module")));
			assertNull(engine.getAdapter("modtest"));
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/modtest/echo"), ctx),
				"catalog metadata remains after the adapter is unloaded");
			assertNull(engine.resolvePath(Strings.create("v/info/adapters/modtest"), engine.venueContext()));
			assertNull(engine.resolvePath(Strings.create("v/info/modules/modtest-module"), engine.venueContext()));

			// And it can come back (fresh loader, fresh instance)
			asVenue(engine, "v/ops/venue/module/load",
				Maps.of(Strings.create("module"), Strings.create(jar.getFileName().toString())));
			assertNotNull(engine.getAdapter("modtest"));
			assertNotSame(override, engine.getAdapter("modtest"));
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/modtest/echo"), ctx));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRuntimeLoadDisabledByDefault(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		Engine engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
		try {
			ExecutionException e = assertThrows(ExecutionException.class,
				() -> asVenue(engine, "v/ops/venue/module/load",
					Maps.of(Strings.create("module"), Strings.create(jar.toString()))));
			assertTrue(rootCause(e).getMessage().contains("dynamicModules.enabled"), rootCause(e).getMessage());
			assertNull(engine.getAdapter("modtest"));
			// Unload is gated by the same switch
			e = assertThrows(ExecutionException.class,
				() -> asVenue(engine, "v/ops/venue/module/unload",
					Maps.of(Strings.create("name"), Strings.create("whatever"))));
			assertTrue(rootCause(e).getMessage().contains("dynamicModules.enabled"), rootCause(e).getMessage());
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRuntimeLoadPathPolicy(@TempDir Path staging, @TempDir Path elsewhere) throws Exception {
		Path outsideJar = buildModuleJar(elsewhere);

		// Default policy: staging directory only
		Engine strict = bootDynamic(staging, false);
		try {
			for (String bad : new String[] {
					outsideJar.toString(),                                            // absolute path
					"../" + elsewhere.getFileName() + "/" + outsideJar.getFileName(), // traversal
					"no-such.jar" }) {                                                // missing
				ExecutionException e = assertThrows(ExecutionException.class,
					() -> asVenue(strict, "v/ops/venue/module/load",
						Maps.of(Strings.create("module"), Strings.create(bad))), bad);
				assertInstanceOf(IllegalArgumentException.class, rootCause(e), bad + ": " + rootCause(e));
				assertNull(strict.getAdapter("modtest"), bad);
			}
			// A relative sub-path inside the staging dir is fine
			Files.createDirectories(staging.resolve("sub"));
			Path subJar = buildModuleJar(staging.resolve("sub"));
			asVenue(strict, "v/ops/venue/module/load",
				Maps.of(Strings.create("module"), Strings.create("sub/" + subJar.getFileName())));
			assertNotNull(strict.getAdapter("modtest"));
		} finally {
			strict.close();
		}

		// anyPath: absolute paths anywhere are accepted
		Engine open = bootDynamic(staging, true);
		try {
			ACell loaded = asVenue(open, "v/ops/venue/module/load",
				Maps.of(Strings.create("module"), Strings.create(outsideJar.toString())));
			assertEquals(Strings.create(outsideJar.toString()), RT.getIn(loaded, "path"));
			assertNotNull(open.getAdapter("modtest"));
		} finally {
			open.close();
		}
	}

	@Test
	public void testRuntimeSha256PinAndRollback(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		Engine engine = bootDynamic(dir, false);
		try {
			ExecutionException e = assertThrows(ExecutionException.class,
				() -> asVenue(engine, "v/ops/venue/module/load", Maps.of(
					Strings.create("module"), Strings.create(jar.getFileName().toString()),
					Strings.create("sha256"), Strings.create("00".repeat(32)))));
			assertTrue(rootCause(e).getMessage().contains("integrity check FAILED"), rootCause(e).getMessage());
			assertNull(engine.getAdapter("modtest"));
			assertNull(engine.getModule("modtest-module"));

			String goodHash = Blob.wrap(MessageDigest.getInstance("SHA-256")
				.digest(Files.readAllBytes(jar))).toHexString();
			ACell loaded = asVenue(engine, "v/ops/venue/module/load", Maps.of(
				Strings.create("module"), Strings.create(jar.getFileName().toString()),
				Strings.create("sha256"), Strings.create(goodHash)));
			assertEquals(Strings.create(goodHash), RT.getIn(loaded, "sha256"));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testBootModuleCanBeUnloadedAtRuntime(@TempDir Path dir) throws Exception {
		Path jar = buildModuleJar(dir);
		AMap<AString, ACell> config = Maps.of(
			Config.MODULES, Vectors.of(Strings.create(jar.toString())),
			Config.DYNAMIC_MODULES, Maps.of(Config.ENABLED, true,
				Strings.create("dir"), Strings.create(dir.toString())),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		Engine engine = Engine.createTemp(config);
		Engine.addDemoAssets(engine);
		try {
			RequestContext ctx = RequestContext.of(Strings.create("did:test:bootunload"));
			assertNotNull(engine.getAdapter("modtest"));
			assertNotNull(engine.resolvePath(Strings.create("v/info/modules/modtest-module"), engine.venueContext()),
				"boot snapshot lists loaded modules");
			// The admin listing shows the module and its adapter's owner
			ACell listing = asVenue(engine, "v/ops/venue/adapters", Maps.empty());
			assertEquals(Strings.create("modtest-module"), RT.getIn(listing, "modules", 0, "name"));

			asVenue(engine, "v/ops/venue/module/unload",
				Maps.of(Strings.create("name"), Strings.create("modtest-module")));
			assertNull(engine.getAdapter("modtest"));
			assertNotNull(engine.resolveAsset(Strings.create("v/ops/modtest/echo"), ctx),
				"unloading leaves catalog metadata for possible later replacement");
			assertTrue(engine.getModules().isEmpty());
		} finally {
			engine.close();
		}
	}
}
