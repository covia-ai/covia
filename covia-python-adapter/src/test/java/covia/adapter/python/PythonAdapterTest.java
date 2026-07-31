package covia.adapter.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import covia.exception.AuthException;
import covia.grid.Asset;
import covia.python.PythonRuntime;
import covia.venue.Engine;
import covia.venue.RequestContext;

class PythonAdapterTest {
	@TempDir Path temp;

	@Test
	void unavailableRuntimeDisablesAdapterCleanly() {
		PythonAdapter adapter = new PythonAdapter();
		assertFalse(adapter.configureModule(pythonConfig("missing.py",
			"definitely-not-a-python-library"), false));
	}

	@Test
	void malformedKnownConfigFailsEvenWithoutPython() {
		PythonAdapter adapter = new PythonAdapter();
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("enabled", "yes"), false));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("operations", Maps.of(
				"bad_name", Maps.of("script", "x.py"))), false));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("operations", Maps.of(
				"valid", Maps.of("function", "main"))), false));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("typo", true), true));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("instances", Maps.of(
				"templates", Maps.of("worker", Maps.of("script", "worker.py")))), false));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("instances", Maps.of(
				"maxPerUser", 4L,
				"maxTotal", 2L,
				"templates", Maps.of("worker", Maps.of(
					"script", "worker.py", "functions", Vectors.of("run"))))), false));
	}

	@Test
	void managedInstancesAreOwnedAllowlistedBoundedAndClosable() throws Exception {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		Path script = temp.resolve("worker.py");
		Files.writeString(script, """
			calls = 0
			def add(left, right):
			    global calls
			    calls += 1
			    return {"sum": left + right, "calls": calls}
			def hidden():
			    return "not callable"
			""");

		AStringFixture identities = new AStringFixture();
		RequestContext agent = RequestContext.ofAgent(identities.alice, Strings.create("worker"));
		Engine engine = Engine.createTemp(Maps.empty());
		try {
			PythonAdapter adapter = new PythonAdapter();
			assertTrue(adapter.configureModule(instanceConfig(script.toString()), false));
			engine.registerAdapter(adapter);
			Engine.addDemoAssets(engine);

			Asset create = resolve(engine, "create");
			Asset list = resolve(engine, "list");
			Asset call = resolve(engine, "call");
			Asset close = resolve(engine, "close");
			AMap<?, ?> created = (AMap<?, ?>) invoke(adapter, create, agent,
				Maps.of("template", "worker"));
			String id = created.get(Strings.create("id")).toString();
			assertEquals(agent.getCallerDID(), created.get(Strings.create("createdBy")));
			assertEquals(Vectors.of("add"), created.get(Strings.create("functions")));

			AMap<?, ?> aliceList = (AMap<?, ?>) invoke(adapter, list,
				RequestContext.of(identities.alice), Maps.empty());
			assertEquals(1L,
				((convex.core.data.AVector<?>) aliceList.get(Strings.create("instances"))).count());
			AMap<?, ?> bobList = (AMap<?, ?>) invoke(adapter, list,
				RequestContext.of(identities.bob), Maps.empty());
			assertEquals(0L,
				((convex.core.data.AVector<?>) bobList.get(Strings.create("instances"))).count());

			AMap<?, ?> result = (AMap<?, ?>) invoke(adapter, call,
				RequestContext.of(identities.alice), Maps.of(
					"id", id, "function", "add", "args", Vectors.of(5L, 7L)));
			assertEquals(CVMLong.create(12), result.get(Strings.create("sum")));
			assertEquals(CVMLong.create(1), result.get(Strings.create("calls")));

			assertFutureCause(IllegalArgumentException.class, () -> invoke(adapter, call,
				RequestContext.of(identities.bob), Maps.of(
					"id", id, "function", "add", "args", Vectors.empty())));
			assertFutureCause(IllegalArgumentException.class, () -> invoke(adapter, call,
				RequestContext.of(identities.alice), Maps.of(
					"id", id, "function", "hidden", "args", Vectors.empty())));
			assertFutureCause(IllegalStateException.class, () -> invoke(adapter, create,
				RequestContext.of(identities.alice), Maps.of("template", "worker")));
			assertFutureCause(AuthException.class, () -> invoke(adapter, list,
				RequestContext.ANONYMOUS, Maps.empty()));
			assertFutureCause(AuthException.class, () -> invoke(adapter, list,
				RequestContext.of(Strings.create(engine.getDIDString() + ":public")), Maps.empty()));

			AMap<?, ?> closed = (AMap<?, ?>) invoke(adapter, close,
				RequestContext.ofAgent(identities.alice, Strings.create("closer")),
				Maps.of("id", id));
			assertEquals(CVMBool.TRUE, closed.get(Strings.create("closed")));
			assertFutureCause(IllegalArgumentException.class, () -> invoke(adapter, call,
				RequestContext.of(identities.alice), Maps.of(
					"id", id, "function", "add", "args", Vectors.empty())));
		} finally {
			engine.close();
		}
	}

	private static final class AStringFixture {
		private final convex.core.data.AString alice = Strings.create("did:key:zAlice");
		private final convex.core.data.AString bob = Strings.create("did:key:zBob");
	}

	private static Asset resolve(Engine engine, String operation) {
		Asset asset = engine.resolveAsset(Strings.create("v/ops/python/instances/" + operation),
			engine.venueContext());
		assertNotNull(asset);
		return asset;
	}

	private static ACell invoke(PythonAdapter adapter, Asset asset,
			RequestContext context, ACell input) {
		return adapter.invokeFuture(context, asset.meta(), input).join();
	}

	private static void assertFutureCause(Class<? extends Throwable> type,
			org.junit.jupiter.api.function.Executable invocation) {
		CompletionException error = assertThrows(CompletionException.class, invocation);
		assertTrue(type.isInstance(error.getCause()), () -> "Expected " + type.getName()
			+ " but got " + error.getCause());
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

		Engine engine = Engine.createTemp(Maps.empty());
		try {
			PythonAdapter adapter = new PythonAdapter();
			org.junit.jupiter.api.Assertions.assertTrue(adapter.configureModule(
				pythonConfig(script.toString(), System.getProperty("covia.python.library")), false));
			engine.registerAdapter(adapter);
			Engine.addDemoAssets(engine);
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
		return python;
	}

	private static AMap<convex.core.data.AString, ACell> instanceConfig(String script) {
		AMap<convex.core.data.AString, ACell> python = Maps.of(
			"enabled", true,
			"instances", Maps.of(
				"maxPerUser", 1L,
				"maxTotal", 2L,
				"templates", Maps.of(
					"worker", Maps.of(
						"script", script,
						"functions", Vectors.of("add")))));
		String library = System.getProperty("covia.python.library");
		if (library != null) python = python.assoc(Strings.create("library"),
			Strings.create(library));
		return python;
	}
}
