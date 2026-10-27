package covia.adapter.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.db.ConvexDB;
import convex.db.jdbc.ConvexDriver;
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
@Execution(ExecutionMode.SAME_THREAD)
public class SQLAdapterTest {

	private static final String EXECUTE = "v/ops/sql/execute";
	private static final String QUERY = "v/ops/sql/query";

	private static Engine engine;
	private static Path sqliteFile;
	private static Path storeDir;
	private AString did;
	private RequestContext ctx;

	@BeforeAll
	static void boot() throws IOException {
		// One operator-registered "external" connection for the shared-db
		// test — itself a convex-db mem database, so the suite stays
		// self-contained. Deliberately a SEPARATE instance from the venue-local
		// one: convex-db 0.8.9 fixed cross-instance DML routing
		// (Convex-Dev/convex#645), and running the shared-db tests against a
		// second instance keeps that fix regression-guarded from our side.
		// The same database is registered again as read-only "sharedro".
		// Another registered connection uses SQLite, a driver other than
		// convex-db whose executeQuery runs a write before reporting that it
		// returned no rows, as PostgreSQL's does.
		sqliteFile = Files.createTempFile("covia-sql-test", ".db");
		storeDir = Files.createTempDirectory("covia-sql-store");
		AString sharedUrl = Strings.create("jdbc:convex:mem:shared_ext;database=shared_ext_test");
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of(
				Strings.create("sql"), Maps.of(
					Strings.create("databases"), Maps.of(
						Strings.create("shareddb"), Maps.of(
							Strings.create("url"), sharedUrl),
						Strings.create("sharedro"), Maps.of(
							Strings.create("url"), sharedUrl,
							Config.READ_ONLY, convex.core.data.prim.CVMBool.TRUE),
						Strings.create("sqlitedb"), Maps.of(
							Strings.create("url"), Strings.create("jdbc:sqlite:" + sqliteFile))))));
		engine = Engine.createTemp(config);
		engine.registerAdapter(new SQLAdapter());
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void shutdown() throws IOException {
		engine.close();
		Files.deleteIfExists(sqliteFile);
		// The driver keeps its stores open beyond the venues that used them
		ConvexDriver.closeAll();
		try (Stream<Path> walk = Files.walk(storeDir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
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

	@Test
	public void testReadOnlyDatabaseRefusesExecute() {
		// Seeded through the writable registration of the same database
		exec(ctx, "shareddb", "CREATE TABLE ro_notes (id INTEGER, txt VARCHAR)");
		exec(ctx, "shareddb", "INSERT INTO ro_notes VALUES (1, 'kept')");
		ACell before = query(ctx, "sharedro", "SELECT id, txt FROM ro_notes ORDER BY id", null, null);
		assertEquals(1, rows(before).count());

		// A null-scope user and the venue itself are refused alike
		for (RequestContext caller : new RequestContext[] {ctx, engine.venueContext()}) {
			for (String write : new String[] {
					"DELETE FROM ro_notes",
					"INSERT INTO ro_notes VALUES (2, 'added')",
					"DROP TABLE ro_notes"}) {
				Job job = engine.jobs().invokeOperation("v/ops/sql/execute",
					Maps.of(Strings.create("db"), Strings.create("sharedro"),
						Strings.create("statement"), Strings.create(write)),
					caller);
				assertThrows(Exception.class, () -> job.awaitResult(15000), write);
				assertEquals(Status.FAILED, job.getStatus(), write);
				assertTrue(job.getErrorMessage().contains(SQLAdapter.READ_ONLY_DATABASE), job.getErrorMessage());
			}
		}
		assertRefused(attemptQuery("sharedro", "DELETE FROM ro_notes", null), "DELETE through sql:query");
		assertEquals(before, query(ctx, "sharedro", "SELECT id, txt FROM ro_notes ORDER BY id", null, null));
	}

	// ========== Venue-local names ==========

	/**
	 * Principals that earlier versions put in one database: DIDs differing
	 * only in punctuation, and DID and database pairs differing only in where
	 * an underscore falls.
	 */
	private static final String[][] LOOKALIKES = {
		{"did:web:venue.example:u:alice.smith", "mine"},
		{"did:web:venue.example:u:alice_smith", "mine"},
		{"did:web:venue.example:u:alice-smith", "mine"},
		{"did:web:bob.example.com", "mine"},
		{"did:web:bob-example.com", "mine"},
		{"did:test:sql:split-", "x"},
		{"did:test:sql:split", "_x"}};

	@Test
	public void testLookalikePrincipalsGetDatabasesOfTheirOwn() {
		assertOwnRowsOnly(engine, LOOKALIKES);
	}

	/**
	 * With {@code adapters.sql.path} every venue-local database lives in one
	 * Etch file, named {@code <userDID>/sql/<db>}: the resource a
	 * {@code sql/<db>} capability names under that user.
	 */
	@Test
	public void testStoredDatabasesAreNamedByOwnerAndName() throws IOException {
		Path file = storeDir.resolve("local.etch");
		Engine fileBacked = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of(Strings.create("sql"), Maps.of(
				Strings.create("path"), Strings.create(file.toString())))));
		try {
			fileBacked.registerAdapter(new SQLAdapter());
			Engine.addDemoAssets(fileBacked);
			assertOwnRowsOnly(fileBacked, LOOKALIKES);
			ConvexDB store = ConvexDriver.instances.get("file:" + file.toFile().getCanonicalPath()).cdb;
			Set<String> names = Set.of(store.getDatabaseNames());
			for (String[] o : LOOKALIKES) {
				assertTrue(names.contains(o[0] + "/sql/" + o[1]), names.toString());
			}
			assertEquals(LOOKALIKES.length, names.size(), names.toString());
		} finally {
			fileBacked.close();
		}
	}

	@Test
	public void testPublicPrincipalHasNoVenueLocalDatabase() {
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		for (RequestContext c : new RequestContext[] {
				RequestContext.of(publicDID),
				RequestContext.ofAgent(publicDID, Strings.create("helper"))}) {
			for (Job job : new Job[] {
					attempt(engine, EXECUTE, c, "mine", "CREATE TABLE marks (owner VARCHAR)"),
					attempt(engine, QUERY, c, "mine", "SELECT owner FROM marks")}) {
				assertEquals(Status.FAILED, job.getStatus());
				assertTrue(job.getErrorMessage().contains(SQLAdapter.PUBLIC_HAS_NO_DATABASES), job.getErrorMessage());
			}
		}
		// Registered databases are the operator's to share, the public principal included
		exec(ctx, "shareddb", "CREATE TABLE notices (txt VARCHAR)");
		exec(ctx, "shareddb", "INSERT INTO notices VALUES ('open to all')");
		assertCompletes(attempt(engine, QUERY, RequestContext.of(publicDID), "shareddb", "SELECT txt FROM notices"));
	}

	/**
	 * Read as a resource, the name of {@code did:test:sql:a/sql/b}'s database
	 * {@code c} would be {@code did:test:sql:a}'s, so a principal named with a
	 * {@code /} owns no database. No DID contains one.
	 */
	@Test
	public void testPrincipalNamedWithSlashOwnsNoDatabase() {
		Job job = attempt(engine, EXECUTE, RequestContext.of(Strings.create("did:test:sql:a/sql/b")),
			"c", "CREATE TABLE marks (owner VARCHAR)");
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(SQLAdapter.UNNAMEABLE_OWNER), job.getErrorMessage());
	}

	/** Databases belong to the user: an agent works in its owner's. */
	@Test
	public void testAgentWorksInItsOwnersDatabase() {
		exec(ctx, "team", "CREATE TABLE notes (txt VARCHAR)");
		exec(ctx, "team", "INSERT INTO notes VALUES ('from the owner')");
		ACell out = query(RequestContext.ofAgent(did, Strings.create("helper")), "team", "SELECT txt FROM notes", null, null);
		assertEquals("from the owner", RT.ensureVector(rows(out).get(0)).get(0).toString());
	}

	/** Each owner creates a table of the same name and puts its DID in it, then
	 *  must read back exactly that one row. */
	private static void assertOwnRowsOnly(Engine e, String[][] owners) {
		for (String[] o : owners) {
			RequestContext c = RequestContext.of(Strings.create(o[0]));
			assertCompletes(attempt(e, EXECUTE, c, o[1], "CREATE TABLE marks (owner VARCHAR)"));
			assertCompletes(attempt(e, EXECUTE, c, o[1], "INSERT INTO marks VALUES (?)", Strings.create(o[0])));
		}
		for (String[] o : owners) {
			Job read = attempt(e, QUERY, RequestContext.of(Strings.create(o[0])), o[1], "SELECT owner FROM marks");
			assertCompletes(read);
			AVector<ACell> rows = rows(read.getOutput());
			assertEquals(1, rows.count(), o[0] + " reads " + rows);
			assertEquals(o[0], RT.ensureVector(rows.get(0)).get(0).toString());
		}
	}

	/** Runs an operation to its end, successful or not, for the caller to inspect. */
	private static Job attempt(Engine e, String op, RequestContext c, String db, String statement, ACell... params) {
		AMap<AString, ACell> input = Maps.of(
			Strings.create("db"), Strings.create(db),
			Strings.create("statement"), Strings.create(statement));
		if (params.length > 0) input = input.assoc(Strings.create("params"), convex.core.data.Vectors.create(params));
		Job job = e.jobs().invokeOperation(op, input, c);
		try {
			job.awaitResult(15000);
		} catch (Exception ex) {
			// the job's status and message say what happened
		}
		assertTrue(job.isFinished(), statement);
		return job;
	}

	private static void assertCompletes(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), String.valueOf(job.getErrorMessage()));
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

	// ========== Query only ==========

	/** Statements that change data or schema, including disguised ones. */
	private static final String[] WRITES = {
		"DELETE FROM guarded WHERE id = 2",
		"UPDATE guarded SET name = 'changed'",
		"INSERT INTO guarded VALUES (3, 'Carol')",
		"DROP TABLE guarded",
		"CREATE TABLE intruder (id INTEGER)",
		"/* x */ DELETE FROM guarded WHERE id = 2",
		"-- x\nDELETE FROM guarded WHERE id = 2",
		"SELECT 1; DELETE FROM guarded WHERE id = 2",
		"WITH doomed AS (SELECT id FROM guarded) DELETE FROM guarded WHERE id = 2"};

	@Test
	public void testQueryRefusesWritesOnVenueLocalDatabase() {
		assertQueryOnly("guard", true);
	}

	@Test
	public void testQueryRefusesWritesOnRegisteredConvexDatabase() {
		assertQueryOnly("shareddb", true);
	}

	/**
	 * Other drivers run sql:query in a read-only transaction that is always
	 * rolled back. SQLite ignores the read-only hint, runs a write passed to
	 * executeQuery and then fails, and runs only the first of several
	 * statements, so here the property is that nothing persists.
	 */
	@Test
	public void testQueryLeavesExternalDatabaseUnchanged() {
		assertQueryOnly("sqlitedb", false);
	}

	@Test
	public void testQueryAcceptsQueryForms() {
		exec(ctx, "forms", "CREATE TABLE t (id INTEGER, name VARCHAR)");
		exec(ctx, "forms", "INSERT INTO t VALUES (1, 'Alice')");
		exec(ctx, "forms", "INSERT INTO t VALUES (2, 'Bob')");
		for (String q : new String[] {
				"select name from t where id = 1 order by name",
				"/* note */ SELECT name FROM t WHERE id = 1",
				"WITH picked AS (SELECT name FROM t WHERE id = 1) SELECT name FROM picked",
				"SELECT name FROM t WHERE id = 1 UNION SELECT name FROM t WHERE id = 1"}) {
			ACell out = query(ctx, "forms", q, null, null);
			assertEquals("Alice", RT.ensureVector(rows(out).get(0)).get(0).toString(), q);
		}
	}

	/**
	 * Sends every write through sql:query and requires that the table is
	 * exactly as it was after each one. For convex-db databases the statement
	 * must also be refused before it reaches the database.
	 */
	private void assertQueryOnly(String db, boolean refusedBeforeExecution) {
		exec(ctx, db, "CREATE TABLE guarded (id INTEGER, name VARCHAR)");
		exec(ctx, db, "INSERT INTO guarded VALUES (1, 'Alice')");
		exec(ctx, db, "INSERT INTO guarded VALUES (2, 'Bob')");
		AVector<ACell> before = contents(db);
		assertEquals(2, before.count());

		for (String write : WRITES) {
			Job job = attemptQuery(db, write, null);
			assertEquals(before, contents(db), "sql:query applied: " + write);
			if (refusedBeforeExecution) assertRefused(job, write);
		}
		Job bound = attemptQuery(db, "DELETE FROM guarded WHERE id = ?",
			convex.core.data.Vectors.of(convex.core.data.prim.CVMLong.create(2)));
		assertEquals(before, contents(db), "sql:query applied a parameterised DELETE");
		if (refusedBeforeExecution) assertRefused(bound, "parameterised DELETE");

		Job intruder = attemptQuery(db, "SELECT COUNT(*) FROM intruder", null);
		assertEquals(Status.FAILED, intruder.getStatus(), "sql:query created a table");
	}

	private AVector<ACell> contents(String db) {
		return rows(query(ctx, db, "SELECT id, name FROM guarded ORDER BY id", null, null));
	}

	/** Runs a query to its end, successful or not, for the caller to inspect. */
	private Job attemptQuery(String db, String statement, ACell params) {
		AMap<AString, ACell> input = Maps.of(
			Strings.create("db"), Strings.create(db),
			Strings.create("statement"), Strings.create(statement));
		if (params != null) input = input.assoc(Strings.create("params"), params);
		Job job = engine.jobs().invokeOperation("v/ops/sql/query", input, ctx);
		try {
			job.awaitResult(15000);
		} catch (Exception e) {
			// the job's status and message say what happened
		}
		assertTrue(job.isFinished(), statement);
		return job;
	}

	private static void assertRefused(Job job, String statement) {
		assertEquals(Status.FAILED, job.getStatus(), statement);
		String message = job.getErrorMessage();
		assertTrue(message.contains(SQLAdapter.NOT_A_QUERY) || message.contains(SQLAdapter.NOT_ONE_QUERY),
			statement + " → " + message);
	}

	// ========== Capabilities ==========

	@Test
	public void testCapabilityDeniedUnderPublicScope() {
		RequestContext capped = ctx.withCaps(CapabilityChecker.readOnlyScope(did));
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
	 * the module jar and installs to {@code v/skills/data/sql} exactly when the
	 * module is loaded. Mirrors the venue's SkillsLibraryTest guards — the
	 * skill must resolve with a real body and every declared tool must
	 * resolve on the venue.
	 */
	@Test
	public void testSqlSkillShipsWithModule() {
		covia.adapter.agent.Skills.ResolvedSkill s =
			covia.adapter.agent.Skills.resolveRef(engine, ctx, Strings.create("v/skills/data/sql"));
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
