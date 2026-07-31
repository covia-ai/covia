package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.grid.Asset;
import covia.python.PythonRuntime;
import covia.venue.Config;
import covia.venue.Engine;

class PythonAdapterTest {
	@TempDir Path temp;

	@Test
	void unavailableRuntimeDisablesAdapterCleanly() {
		Config config = new Config(pythonConfig("missing.py",
			"definitely-not-a-python-library"));
		assertFalse(PythonAdapter.create(config).isPresent());
	}

	@Test
	void configuredScriptBecomesStatefulVenueOperation() throws Exception {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		Path script = temp.resolve("counter.py");
		Files.writeString(script, """
			calls = 0
			def main(value):
			    global calls
			    calls += 1
			    return {"value": value["value"] * 2, "calls": calls}
			""");

		Engine engine = Engine.createTemp(pythonConfig(script.toString(),
			System.getProperty("covia.python.library")));
		try {
			Engine.addDemoAssets(engine);
			PythonAdapter adapter = (PythonAdapter) engine.getAdapter("python");
			assertNotNull(adapter);
			Asset asset = engine.resolveAsset(Strings.create("v/ops/python/double"),
				engine.venueContext());
			assertNotNull(asset);
			AMap<?, ?> first = (AMap<?, ?>) adapter.invokeFuture(null, asset.meta(),
				Maps.of("value", 6L)).join();
			AMap<?, ?> second = (AMap<?, ?>) adapter.invokeFuture(null, asset.meta(),
				Maps.of("value", 7L)).join();
			assertEquals(CVMLong.create(12), first.get(Strings.create("value")));
			assertEquals(CVMLong.create(1), first.get(Strings.create("calls")));
			assertEquals(CVMLong.create(14), second.get(Strings.create("value")));
			assertEquals(CVMLong.create(2), second.get(Strings.create("calls")));

			List<CompletableFuture<ACell>> concurrent = IntStream.range(0, 8)
				.mapToObj(i -> adapter.invokeFuture(null, asset.meta(),
					Maps.of("value", i)))
				.toList();
			Set<Long> callNumbers = concurrent.stream()
				.map(CompletableFuture::join)
				.map(v -> (AMap<?, ?>) v)
				.map(v -> ((CVMLong) v.get(Strings.create("calls"))).longValue())
				.collect(java.util.stream.Collectors.toSet());
			assertEquals(Set.of(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), callNumbers,
				"the GIL must serialize mutation of shared script globals");
		} finally {
			engine.close();
		}
	}

	private static AMap<convex.core.data.AString, ACell> pythonConfig(
			String script, String library) {
		AMap<convex.core.data.AString, ACell> python = Maps.of(
			"enabled", true,
			"operations", Maps.of(
				"double", Maps.of(
					"script", script,
					"function", "main",
					"name", "Double in Python",
					"description", "Doubles a value with configured CPython")));
		if (library != null) python = python.assoc(Strings.create("library"),
			Strings.create(library));
		return Maps.of(Config.ADAPTERS, Maps.of("python", python));
	}
}
