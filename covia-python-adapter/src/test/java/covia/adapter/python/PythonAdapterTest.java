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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Abilities;
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
	}

	/** The instances the module once offered are gone, and a config naming them is told what replaces them. */
	@Test
	void instancesAreRefusedWithWhatReplacesThem() {
		PythonAdapter adapter = new PythonAdapter();
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> adapter.configureModule(Maps.of("instances", Maps.of(
				"templates", Maps.of("worker", Maps.of("script", "worker.py", "functions", Vectors.of("run"))))), true));
		assertEquals(PythonAdapter.INSTANCES_REMOVED, refused.getMessage());
	}

	/** {@code user} presenting the venue's grant of {@code ability} on its resource {@code resource}. */
	private static RequestContext granted(Engine engine, AString user, String resource, AString ability) {
		long expiry = engine.currentTimestamp().longValue() / 1000 + 3600;
		UCAN grant = UCAN.create(engine.getKeyPair(), UCAN.fromDIDKey(user), expiry,
			Vectors.of(Capability.create(Strings.create(engine.getDIDString() + "/" + resource), ability)),
			Vectors.empty());
		return RequestContext.of(user).withProofs(Vectors.of(grant.toMap()));
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
	@Tag("integration")
	@EnabledIfSystemProperty(named = "covia.tests.integration", matches = "true",
		disabledReason = "embeds native CPython; enable with -Dcovia.tests.integration=true")
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
			RequestContext venue = engine.venueContext();
			AMap<?, ?> first = (AMap<?, ?>) adapter.invokeFuture(venue, asset.meta(),
				Maps.of("value", 6L)).join();
			AMap<?, ?> second = (AMap<?, ?>) adapter.invokeFuture(venue, asset.meta(),
				Maps.of("value", 7L)).join();
			assertEquals(CVMLong.create(12), first.get(Strings.create("value")));
			assertEquals(CVMLong.create(1), first.get(Strings.create("calls")));
			assertEquals(CVMLong.create(14), second.get(Strings.create("value")));
			assertEquals(CVMLong.create(2), second.get(Strings.create("calls")));

			List<CompletableFuture<ACell>> concurrent = IntStream.range(0, 8)
				.mapToObj(i -> adapter.invokeFuture(venue, asset.meta(),
					Maps.of("value", i)))
				.toList();
			Set<Long> callNumbers = concurrent.stream()
				.map(CompletableFuture::join)
				.map(v -> (AMap<?, ?>) v)
				.map(v -> ((CVMLong) v.get(Strings.create("calls"))).longValue())
				.collect(java.util.stream.Collectors.toSet());
			assertEquals(Set.of(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), callNumbers,
				"the GIL must serialize mutation of shared script globals");

			// Running it is the operator's: a user needs the venue's grant on this function
			AString alice = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
			String double_ = PythonAdapter.OPERATION_RESOURCE + "double";
			assertFutureCause(AuthException.class, () -> invoke(adapter, asset,
				RequestContext.of(alice), Maps.of("value", 1L)));
			assertFutureCause(AuthException.class, () -> invoke(adapter, asset,
				granted(engine, alice, PythonAdapter.OPERATION_RESOURCE + "other", PythonAdapter.ADMIN_EXECUTE_PYTHON),
				Maps.of("value", 1L)));
			assertEquals(CVMLong.create(2), ((AMap<?, ?>) invoke(adapter, asset,
				granted(engine, alice, double_, PythonAdapter.ADMIN_EXECUTE_PYTHON), Maps.of("value", 1L)))
				.get(Strings.create("value")));
			assertEquals(CVMLong.create(4), ((AMap<?, ?>) invoke(adapter, asset,
				granted(engine, alice, "python", Abilities.ADMIN_EXECUTE), Maps.of("value", 2L)))
				.get(Strings.create("value")), "the parent ability on all of the venue's Python");
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
}
