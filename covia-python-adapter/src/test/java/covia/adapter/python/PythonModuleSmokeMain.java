package covia.adapter.python;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.adapter.AAdapter;
import covia.grid.Asset;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;
import covia.venue.RequestContext;

/** Child-process entry point for {@link PythonModuleIT}. */
public final class PythonModuleSmokeMain {
	private PythonModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		AMap<AString, ACell> moduleConfig = Maps.of(
			"operations", Maps.of("smoke", Maps.of("script", args[1])),
			"instances", Maps.of("templates", Maps.of("smoke", Maps.of(
				"script", args[1], "functions", Vectors.of("main")))));
		AMap<AString, ACell> config = Maps.of(
			Config.MODULES, Vectors.of(Maps.of(
				"path", args[0], "config", moduleConfig)));
		Engine engine = Engine.createTemp(config);
		try {
			Engine.addDemoAssets(engine);
			AAdapter adapter = engine.getAdapter("python");
			if (adapter == null) throw new AssertionError("Python adapter did not load");
			ClassLoader loader = adapter.getClass().getClassLoader();
			if (!(loader instanceof ModuleClassLoader)) {
				throw new AssertionError("Adapter was not loaded as a module: " + loader);
			}
			Class<?> runtime = Class.forName("covia.python.PythonRuntime", false, loader);
			if (runtime.getClassLoader() != loader) {
				throw new AssertionError("Python runtime leaked onto the venue classpath");
			}
			Asset asset = engine.resolveAsset(Strings.create("v/ops/python/smoke"),
				engine.venueContext());
			ACell result = adapter.invokeFuture(null, asset.meta(), Maps.of("x", 41L)).join();
			if (!CVMLong.create(42).equals(result)) throw new AssertionError("Bad result: " + result);

			RequestContext user = RequestContext.of(Strings.create("did:key:zSmoke"));
			Asset create = engine.resolveAsset(
				Strings.create("v/ops/python/instances/create"), engine.venueContext());
			AMap<?, ?> instance = (AMap<?, ?>) adapter.invokeFuture(user, create.meta(),
				Maps.of("template", "smoke")).join();
			AString id = (AString) instance.get(Strings.create("id"));
			Asset call = engine.resolveAsset(
				Strings.create("v/ops/python/instances/call"), engine.venueContext());
			ACell called = adapter.invokeFuture(user, call.meta(), Maps.of(
				"id", id, "function", "main", "args", Vectors.of(Maps.of("x", 41L)))).join();
			if (!CVMLong.create(42).equals(called)) {
				throw new AssertionError("Bad instance result: " + called);
			}
			Asset close = engine.resolveAsset(
				Strings.create("v/ops/python/instances/close"), engine.venueContext());
			adapter.invokeFuture(user, close.meta(), Maps.of("id", id)).join();
			System.out.println("PYTHON_MODULE_SMOKE_OK");
		} finally {
			engine.close();
		}
	}
}
