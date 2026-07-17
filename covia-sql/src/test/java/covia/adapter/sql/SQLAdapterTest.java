package covia.adapter.sql;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CapabilityChecker;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Functional tests for the SQL adapter against REAL convex-db in-JVM
 * databases ({@code jdbc:convex:mem:}): real driver, real Calcite planner,
 * real lattice tables — no external processes, no stubs.
 */
public class SQLAdapterTest {

	private static Engine engine;
	private AString did;
	private RequestContext ctx;

	@BeforeAll
	static void boot() {
		// One operator-registered "external" connection for the shared-db
		// test — itself a convex-db mem database, so the suite stays
		// self-contained. Deliberately a SEPARATE instance from the venue-local
		// one: convex-db 0.8.9 fixed cross-instance DML routing
		// (Convex-Dev/convex#645), and running the shared-db tests against a
		// second instance keeps that fix regression-guarded from our side.
		AMap<AString, ACell> config = Maps.of(Config.ADAPTERS, Maps.of(
			Strings.create("sql"), Maps.of(
				Strings.create("databases"), Maps.of(
					Strings.create("shareddb"), Maps.of(
						Strings.create("url"), Strings.create("jdbc:convex:mem:shared_ext;database=shared_ext_test"))))));
		engine = Engine.createTemp(config);
		engine.registerAdapter(new SQLAdapter());
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void shutdown() {
		engine.close();
	}

	@BeforeEach
	void setup(TestInfo info) {
		did = Strings.create("did:test:sql:" + info.getTestMethod().get().getName());
		ctx = RequestContext.of(did);
	}

	private ACell exec(RequestContext c, String db, String statement, ACell... params) {
		AMap<AString, ACell> input = Maps.of(
			Strings.create("db"), Strings.create(db),
			Strings.create("statement"), Strings.create(statement));
		if (params.length > 0) input = input.assoc(Strings.create("params"), convex.core.data.Vectors.create(params));
		Job job = engine.jobs().invokeOperation("v/ops/sql/execute", input, c);
		ACell r = job.awaitResult(15000);
		assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
		return r;
	}

	private ACell query(RequestContext c, String db, String statement, ACell params, Long maxRows) {
		AMap<AString, ACell> input = Maps.of(
			Strings.create("db"), Strings.create(db),
			Strings.create("statement"), Strings.create(statement));
		if (params != null) input = input.assoc(Strings.create("params"), params);
		if (maxRows != null) input = input.assoc(Strings.create("maxRows"), convex.core.data.prim.CVMLong.create(maxRows));
		Job job = engine.jobs().invokeOperation("v/ops/sql/query", input, c);
		ACell r = job.awaitResult(15000);
		assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
		return r;
	}

	private static AVector<ACell> rows(ACell result) {
		return RT.ensureVector(RT.getIn(result, "rows"));
	}

	// ========== Round trip ==========

	@Test
	public void testCreateInsertQuery() {
		exec(ctx, "db1", "CREATE TABLE users (id INTEGER, name VARCHAR)");
		exec(ctx, "db1", "INSERT INTO users VALUES (1, 'Alice')");
		exec(ctx, "db1", "INSERT INTO users VALUES (2, 'Bob')");

		ACell out = query(ctx, "db1", "SELECT id, name FROM users ORDER BY id", null, null);
		assertEquals(2, RT.ensureLong(RT.getIn(out, "count")).longValue());
		assertEquals(2, RT.ensureVector(RT.getIn(out, "columns")).count());
		AVector<ACell> rows = rows(out);
		AVector<ACell> first = RT.ensureVector(rows.get(0));
		assertEquals(1L, RT.ensureLong(first.get(0)).longValue());
		assertEquals("Alice", first.get(1).toString());
		assertNull(RT.getIn(out, "truncated"));
	}

	@Test
	public void testParameterisedStatements() {
		exec(ctx, "db2", "CREATE TABLE t (id INTEGER, val VARCHAR)");
		exec(ctx, "db2", "INSERT INTO t VALUES (?, ?)",
			convex.core.data.prim.CVMLong.create(7), Strings.create("seven"));

		ACell out = query(ctx, "db2", "SELECT val FROM t WHERE id = ?",
			convex.core.data.Vectors.of(convex.core.data.prim.CVMLong.create(7)), null);
		assertEquals(1, RT.ensureLong(RT.getIn(out, "count")).longValue());
		assertEquals("seven", RT.ensureVector(rows(out).get(0)).get(0).toString());
	}

	@Test
	public void testSingleColumnTable() {
		// Regression for Convex-Dev/convex#646 (fixed in convex-db 0.8.9):
		// INSERT into a single-column table used to fail with a
		// ClassCastException in DML execution.
		exec(ctx, "db6", "CREATE TABLE tags (tag VARCHAR)");
		exec(ctx, "db6", "INSERT INTO tags VALUES ('solo')");
		exec(ctx, "db6", "INSERT INTO tags VALUES (?)", Strings.create("bound"));

		ACell out = query(ctx, "db6", "SELECT tag FROM tags ORDER BY tag", null, null);
		assertEquals(2, RT.ensureLong(RT.getIn(out, "count")).longValue());
		assertEquals("bound", RT.ensureVector(rows(out).get(0)).get(0).toString());
		assertEquals("solo", RT.ensureVector(rows(out).get(1)).get(0).toString());
	}

	// ========== Isolation and sharing ==========

	@Test
	public void testPerUserIsolation() {
		RequestContext alice = RequestContext.of(Strings.create("did:test:sql:iso-alice"));
		RequestContext bob = RequestContext.of(Strings.create("did:test:sql:iso-bob"));

		exec(alice, "mine", "CREATE TABLE secrets (id INTEGER, k VARCHAR)");
		exec(alice, "mine", "INSERT INTO secrets VALUES (1, 'alice-only')");

		// Bob's "mine" is a DIFFERENT database — Alice's table is not there
		Job bobQuery = engine.jobs().invokeOperation("v/ops/sql/query",
			Maps.of(Strings.create("db"), Strings.create("mine"),
				Strings.create("statement"), Strings.create("SELECT k FROM secrets")),
			bob);
		assertThrows(Exception.class, () -> bobQuery.awaitResult(15000));
		assertEquals(Status.FAILED, bobQuery.getStatus());
	}

	@Test
	public void testConfiguredSharedDatabase() {
		// Operator-registered connections are venue-shared: what Alice
		// writes, Bob reads.
		RequestContext alice = RequestContext.of(Strings.create("did:test:sql:shared-alice"));
		RequestContext bob = RequestContext.of(Strings.create("did:test:sql:shared-bob"));

		exec(alice, "shareddb", "CREATE TABLE notes (id INTEGER, txt VARCHAR)");
		exec(alice, "shareddb", "INSERT INTO notes VALUES (1, 'hello bob')");

		ACell out = query(bob, "shareddb", "SELECT txt FROM notes WHERE id = 1", null, null);
		assertEquals("hello bob", RT.ensureVector(rows(out).get(0)).get(0).toString());
	}

	// ========== Result bounds ==========

	@Test
	public void testTruncationFlag() {
		exec(ctx, "db3", "CREATE TABLE n (i INTEGER, tag VARCHAR)");
		for (int i = 0; i < 5; i++) {
			exec(ctx, "db3", "INSERT INTO n VALUES (" + i + ", 'row')");
		}
		ACell out = query(ctx, "db3", "SELECT i FROM n", null, 3L);
		assertEquals(3, RT.ensureLong(RT.getIn(out, "count")).longValue());
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(out, "truncated"),
			"more rows were available than maxRows — must be flagged, never silent");
	}

	// ========== Capabilities ==========

	@Test
	public void testCapabilityDeniedUnderPublicCeiling() {
		RequestContext capped = ctx.withCaps(CapabilityChecker.readOnlyCeiling(did));
		Job denied = engine.jobs().invokeOperation("v/ops/sql/query",
			Maps.of(Strings.create("db"), Strings.create("db4"),
				Strings.create("statement"), Strings.create("SELECT 1")),
			capped);
		assertThrows(Exception.class, () -> denied.awaitResult(15000));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("Capability denied"), denied.getErrorMessage());
	}

	// ========== Error comprehensibility ==========

	@Test
	public void testBadDbNameError() {
		Job bad = engine.jobs().invokeOperation("v/ops/sql/query",
			Maps.of(Strings.create("db"), Strings.create("not a db name!"),
				Strings.create("statement"), Strings.create("SELECT 1")),
			ctx);
		assertThrows(Exception.class, () -> bad.awaitResult(15000));
		assertTrue(bad.getErrorMessage().contains("db is required"), bad.getErrorMessage());
	}

	@Test
	public void testSqlErrorNamesDatabase() {
		Job bad = engine.jobs().invokeOperation("v/ops/sql/query",
			Maps.of(Strings.create("db"), Strings.create("db5"),
				Strings.create("statement"), Strings.create("SELECT * FROM no_such_table")),
			ctx);
		assertThrows(Exception.class, () -> bad.awaitResult(15000));
		assertEquals(Status.FAILED, bad.getStatus());
		assertTrue(bad.getErrorMessage().contains("db5"), bad.getErrorMessage());
	}

	// ========== Module-shipped skill ==========

	/**
	 * The module ships its own agent skill (SKILLS.md): the resource lives in
	 * the module jar and installs to {@code v/skills/sql} exactly when the
	 * module is loaded. Mirrors the venue's SkillsLibraryTest guards — the
	 * skill must resolve with a real body and every declared tool must
	 * resolve on the venue.
	 */
	@Test
	public void testSqlSkillShipsWithModule() {
		covia.adapter.agent.Skills.ResolvedSkill s =
			covia.adapter.agent.Skills.resolveRef(engine, ctx, Strings.create("v/skills/sql"));
		assertEquals("sql", s.name());
		assertNotNull(s.body(), "sql skill should carry an inline body");
		AVector<ACell> tools = s.toolOps();
		assertTrue(tools.count() >= 2, "sql skill should declare its ops as tools");
		for (long i = 0; i < tools.count(); i++) {
			AString toolRef = RT.ensureString(tools.get(i));
			assertNotNull(engine.resolveAsset(toolRef, ctx),
				toolRef + " declared by the sql skill must resolve");
		}
	}
}
