package covia.adapter.sql;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;
import covia.venue.RequestContext;

/** Child-process entry point for {@link SQLModuleIT}. */
public final class SQLModuleSmokeMain {
	private SQLModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		AMap<AString, ACell> config = Maps.of(
			Config.MODULES, Vectors.of(Maps.of("path", args[0])),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		Engine engine = Engine.createTemp(config);
		try {
			Engine.addDemoAssets(engine);
			AAdapter adapter = engine.getAdapter("sql");
			if (adapter == null) throw new AssertionError("SQL adapter did not load");
			ClassLoader loader = adapter.getClass().getClassLoader();
			if (!(loader instanceof ModuleClassLoader)) {
				throw new AssertionError("Adapter was not loaded as a module: " + loader);
			}
			Class<?> driver = Class.forName("convex.db.jdbc.ConvexDriver", false, loader);
			if (driver.getClassLoader() != loader) {
				throw new AssertionError("convex-db leaked onto the venue classpath");
			}
			if (engine.resolvePath(Strings.create("v/skills/sql"), engine.venueContext()) == null) {
				throw new AssertionError("SQL module skill was not installed");
			}

			RequestContext user = RequestContext.of(Strings.create("did:key:zSqlModuleSmoke"));
			run(engine, user, "v/ops/sql/execute", Maps.of(
				"db", "smoke", "statement", "CREATE TABLE readings (id INTEGER, reading VARCHAR)"));
			run(engine, user, "v/ops/sql/execute", Maps.of(
				"db", "smoke", "statement", "INSERT INTO readings VALUES (?, ?)",
				"params", Vectors.of(7L, "healthy")));
			ACell result = run(engine, user, "v/ops/sql/query", Maps.of(
				"db", "smoke", "statement", "SELECT id, reading FROM readings WHERE id = ?",
				"params", Vectors.of(7L)));
			AVector<ACell> rows = RT.ensureVector(RT.getIn(result, "rows"));
			if (rows == null || rows.count() != 1) {
				throw new AssertionError("Bad SQL row count: " + result);
			}
			AVector<ACell> row = RT.ensureVector(rows.get(0));
			if (row == null || RT.ensureLong(row.get(0)).longValue() != 7L
					|| !"healthy".equals(row.get(1).toString())) {
				throw new AssertionError("Bad SQL result: " + result);
			}
			System.out.println("SQL_MODULE_SMOKE_OK");
		} finally {
			engine.close();
		}
	}

	private static ACell run(Engine engine, RequestContext user, String operation,
			AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(operation, input, user);
		ACell result = job.awaitResult(30_000);
		if (job.getStatus() != Status.COMPLETE) {
			throw new AssertionError(operation + " failed: " + job.getErrorMessage());
		}
		return result;
	}
}
