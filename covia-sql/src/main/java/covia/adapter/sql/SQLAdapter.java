package covia.adapter.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidatorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.db.calcite.ConvexDdlExecutor;
import convex.db.jdbc.ConvexDriver;
import covia.adapter.AAdapter;
import covia.exception.AuthException;
import covia.exception.ConfigurationException;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.exception.NotSupportedException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * SQL adapter: query and execute statements against named databases.
 *
 * <p>Callers name a {@code db} — never a JDBC URL (raw URLs would reach
 * internal hosts and local files; keeping URLs operator-side removes that
 * attack class entirely). A name resolves to one of:</p>
 *
 * <ul>
 *   <li><b>Venue-local convex-db</b> (default): a lattice-backed database
 *       of the caller's own, {@code <userDID>/sql/<db>}, created on first
 *       use. The venue's public principal has none. Persistent when the
 *       venue config sets {@code adapters.sql.path} (an Etch file);
 *       in-memory otherwise.</li>
 *   <li><b>Operator-registered connection</b>: an entry under venue config
 *       {@code adapters.sql.databases.<name>} with {@code url}, optional
 *       {@code user} and {@code password} (a {@code s/<name>} secret
 *       reference, resolved in the venue's store at connect time). Shared
 *       across callers; any JDBC driver on the classpath. {@code readOnly:
 *       true} refuses {@code sql/execute} on it for every caller, the venue
 *       included.</li>
 * </ul>
 *
 * <p>Capabilities are resource-precise: {@code sql/<db>} with ability
 * {@code sql/query} or {@code sql/execute} — a UCAN grant can delegate one
 * database, and capability gates / argument defaults compose as with any
 * operation.</p>
 *
 * <p>{@code sql/query} never changes data, so the two abilities stay
 * distinct. A statement for convex-db (every venue-local database, and any
 * registered {@code jdbc:convex:} URL) is parsed with convex-db's own grammar
 * and refused before it reaches the database unless it is a single query.
 * Other drivers' dialects need not parse in that grammar, so their queries
 * run in a read-only transaction that is always rolled back. PostgreSQL and
 * MySQL refuse writes in a read-only transaction; elsewhere the rollback
 * undoes transactional writes. Statements that commit on their own (DDL on
 * some databases, non-transactional tables, functions with external effects)
 * are beyond what JDBC guarantees: register such a database with a read-only
 * database user.</p>
 */
public class SQLAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(SQLAdapter.class);

	private static final long DEFAULT_MAX_ROWS = 1000;
	private static final long HARD_MAX_ROWS = 10_000;

	private static final AString K_DB = Strings.intern("db");
	private static final AString K_STATEMENT = Strings.intern("statement");
	private static final AString K_PARAMS = Strings.intern("params");
	private static final AString K_MAX_ROWS = Strings.intern("maxRows");
	private static final AString K_COLUMNS = Strings.intern("columns");
	private static final AString K_ROWS = Strings.intern("rows");
	private static final AString K_COUNT = Strings.intern("count");
	private static final AString K_TRUNCATED = Strings.intern("truncated");
	private static final AString K_UPDATE_COUNT = Strings.intern("updateCount");

	/** Refusal of a sql:query statement that parses as something other than a query. */
	static final String NOT_A_QUERY = "sql:query runs only queries such as SELECT; use sql:execute to change data";

	/** Refusal of a sql:query statement that does not parse as a single query. */
	static final String NOT_ONE_QUERY = "sql:query runs a single query, and this statement does not parse as one";

	/** Refusal of sql:execute on a database registered with {@code readOnly: true}, after its name. */
	static final String READ_ONLY_DATABASE = "is read-only; use sql:query to read it";

	/** Refusal of a db that is missing or not a database name. */
	static final String DB_REQUIRED = "db is required — a database name matching [a-zA-Z0-9_]{1,64}:"
		+ " one of your venue-local databases (created on first use) or a connection registered in venue config";

	// Failures of a database, after its name; ERRORS.md says what each may quote.

	/** A statement Calcite cannot parse or validate, before its message. */
	static final String STATEMENT_INVALID = "cannot run the statement";

	/** A statement convex-db could not evaluate; its message may quote stored values. */
	static final String STATEMENT_FAILED = "could not evaluate the statement";

	/** A statement the database refused, before its SQLState and vendor code. */
	static final String STATEMENT_REFUSED = "refused the statement";

	/** A statement the database does not support. */
	static final String STATEMENT_NOT_SUPPORTED = "does not support the statement";

	/** A statement the database could not complete for now: a deadlock, lack of resources or a shutdown. */
	static final String BUSY = "could not complete the statement now; retrying later may succeed";

	/** A database the venue could not connect to, or lost its connection to. */
	static final String UNREACHABLE = "cannot be reached";

	/** A database that refused the credentials the venue config gives for it. */
	static final String CREDENTIALS_REFUSED = "refused the venue's credentials for it";

	/** A registered url no JDBC driver on the venue accepts. */
	static final String NO_DRIVER = "has no JDBC driver on this venue for its url";

	/** Refusal of a venue-local database to the public principal, which every
	 *  anonymous caller shares and so can own nothing. */
	static final String PUBLIC_HAS_NO_DATABASES =
		"Venue-local SQL databases belong to a signed-in user, and the public principal has none";

	/** Refusal of a venue-local database to a principal whose name contains {@code /},
	 *  after that name. No DID does. */
	static final String UNNAMEABLE_OWNER = "cannot own venue-local SQL databases: its name contains '/'";

	/** The parser convex-db's driver itself uses. */
	private static final SqlParser.Config QUERY_PARSER = SqlParser.config()
		.withParserFactory(ConvexDdlExecutor.PARSER_FACTORY);

	static {
		// The convex-db JDBC driver ships inside this module jar — load it
		// through the module's own classloader so DriverManager registers it.
		try {
			Class.forName("convex.db.jdbc.ConvexDriver", true, SQLAdapter.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			log.warn("convex-db JDBC driver not present — venue-local SQL databases unavailable");
		}
	}

	@Override
	public String getName() {
		return "sql";
	}

	@Override
	public String getDescription() {
		return "SQL databases as grid operations: run queries and statements against venue-local "
			+ "convex-db databases (lattice-backed, per-user, zero setup) or operator-registered "
			+ "JDBC connections. Supports parameterised statements for safe query composition.";
	}

	@Override
	protected void installAssets() {
		installAsset("sql/query",   "/adapters/sql/query.json");
		installAsset("sql/execute", "/adapters/sql/execute.json");
		// The skill travels with the capability: readResource resolves against
		// the module jar's own classloader, so v/skills/data/sql exists exactly when
		// this module is loaded.
		installSkill("data/sql", "/adapters/sql/skill.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			throw new InvalidDefinitionException("Insufficient specification for SQL operation");
		}
		return switch (subOp) {
			case "query" -> CompletableFuture.supplyAsync(() -> handleQuery(ctx, input), VIRTUAL_EXECUTOR);
			case "execute" -> CompletableFuture.supplyAsync(() -> handleExecute(ctx, input), VIRTUAL_EXECUTOR);
			default -> throw new NotSupportedException("Unsupported SQL operation: " + subOp);
		};
	}

	// ========== Operations ==========

	ACell handleQuery(RequestContext ctx, ACell input) {
		String db = requireDb(input);
		engine.requireAuthority(ctx, Strings.create("sql/" + db), Strings.create("sql/query"));
		String statement = requireStatement(input);
		AVector<ACell> params = RT.ensureVector(RT.getIn(input, K_PARAMS));
		long maxRows = maxRows(input);

		Database target = resolveDatabase(ctx, db);
		if (target.isConvexDb()) {
			requireQuery(statement);
			return onConnection(db, target, conn -> runQuery(conn, statement, params, maxRows));
		}
		return onConnection(db, target, conn -> runRolledBack(conn, statement, params, maxRows));
	}

	ACell handleExecute(RequestContext ctx, ACell input) {
		String db = requireDb(input);
		engine.requireAuthority(ctx, Strings.create("sql/" + db), Strings.create("sql/execute"));
		String statement = requireStatement(input);
		AVector<ACell> params = RT.ensureVector(RT.getIn(input, K_PARAMS));
		requireWritable(db);

		return onConnection(db, resolveDatabase(ctx, db), conn -> {
			long updateCount;
			if (params == null || params.count() == 0) {
				try (Statement st = conn.createStatement()) {
					updateCount = st.executeUpdate(statement);
				}
			} else {
				try (PreparedStatement ps = conn.prepareStatement(statement)) {
					bindParams(ps, params);
					updateCount = ps.executeUpdate();
				}
			}
			return Maps.of(K_UPDATE_COUNT, CVMLong.create(updateCount));
		});
	}

	/** Work on an open connection; its {@code SQLException} is the database's answer. */
	private interface Work {
		ACell run(Connection conn) throws SQLException;
	}

	/**
	 * Runs work on a new connection to a database, closing it after, with every
	 * failure of the work or the close reported by {@link #statementFailure}.
	 */
	private static ACell onConnection(String db, Database target, Work work) {
		Connection conn = target.connect(db);
		try (conn) {
			return work.run(conn);
		} catch (SQLException | RuntimeException | ExceptionInInitializerError e) {
			// convex-db can fail a statement with a bare runtime exception, or an
			// error from Calcite's generated code, rather than an SQLException
			throw statementFailure(db, target, e);
		}
	}

	/**
	 * What a caller is told of a failed statement. Calcite's parse and
	 * validation messages come before any row is read and quote only the
	 * caller's statement, so they are passed on. A database's own text may
	 * quote stored values, so it is never passed on: the caller gets the
	 * SQLState and vendor code, and the venue log gets the text.
	 */
	private static RuntimeException statementFailure(String db, Database target, Throwable e) {
		SQLException sql = (e instanceof SQLException s) ? s : null;
		String state = (sql == null) ? null : sql.getSQLState();
		int code = (sql == null) ? 0 : sql.getErrorCode();
		log.debug("SQL database '{}' failed a statement (SQLState {}, error code {}): {}", db, state, code, databaseMessage(e));
		String prefix = "SQL database '" + db + "' ";
		String statementError = statementError(e);
		if (statementError != null) return new InvalidInputException(prefix + STATEMENT_INVALID + ": " + statementError, e);
		String codes = codes(state, code);
		String stateClass = (state != null && state.length() >= 2) ? state.substring(0, 2) : "";
		return switch (stateClass) {
			case "08" -> new UnavailableException(prefix + UNREACHABLE + codes, e);
			case "28" -> new ConfigurationException(prefix + CREDENTIALS_REFUSED + codes, e);
			case "40", "53", "57", "58" -> new UnavailableException(prefix + BUSY + codes, e);
			case "0A" -> new NotSupportedException(prefix + STATEMENT_NOT_SUPPORTED + codes);
			// convex-db sets no SQLState: what it cannot run is the statement's failure
			case "" -> target.isConvexDb() ? new InvalidInputException(prefix + STATEMENT_FAILED, e)
				: new ResponseException(prefix + STATEMENT_REFUSED + codes, e);
			default -> new InvalidInputException(prefix + STATEMENT_REFUSED + codes, e);
		};
	}

	/** Calcite's message for a statement it cannot parse or validate, else null. */
	private static String statementError(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof CalciteContextException || t instanceof SqlParseException || t instanceof SqlValidatorException) {
				// The first line locates the problem; a parse failure's rest lists every token Calcite expected
				return String.valueOf(t.getMessage()).lines().findFirst().orElse("");
			}
			if (t.getCause() == t) break;
		}
		return null;
	}

	/** The SQLState and vendor code a message may quote, as {@code " (SQLState x, error code n)"}. */
	private static String codes(String state, int code) {
		String quoted = identifier(state);
		if (quoted == null && code == 0) return "";
		if (quoted == null) return " (error code " + code + ")";
		return (code == 0) ? " (SQLState " + quoted + ")" : " (SQLState " + quoted + ", error code " + code + ")";
	}

	/**
	 * Refuses sql:execute on a database the operator registered with
	 * {@code readOnly: true}, for every caller including the venue. Venue-local
	 * databases belong to their user and are never read-only.
	 */
	private void requireWritable(String db) {
		ACell registered = RT.getIn(engine.adapterConfig("sql"), "databases", db);
		if (registered != null && RT.bool(RT.getIn(registered, Config.READ_ONLY))) {
			throw new NotSupportedException("SQL database '" + db + "' " + READ_ONLY_DATABASE);
		}
	}

	/**
	 * Refuses a statement unless it parses as a single query in convex-db's
	 * own grammar, so the check classifies exactly what the database would run.
	 */
	private static void requireQuery(String statement) {
		SqlNode node;
		try {
			node = SqlParser.create(statement, QUERY_PARSER).parseStmt();
		} catch (SqlParseException e) {
			// The first line locates the problem; the rest lists every token Calcite expected
			String where = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
			throw new InvalidInputException(NOT_ONE_QUERY + ": " + where);
		}
		if (!node.getKind().belongsTo(SqlKind.QUERY)) {
			throw new InvalidInputException(NOT_A_QUERY + " (statement kind: " + node.getKind() + ")");
		}
	}

	/**
	 * Runs a query on another driver's connection in a read-only transaction
	 * that is always rolled back. JDBC makes read-only a hint: PostgreSQL and
	 * MySQL refuse writes under it, and where a driver ignores it the rollback
	 * still undoes whatever the database can undo.
	 */
	private static ACell runRolledBack(Connection conn, String statement, AVector<ACell> params, long maxRows) throws SQLException {
		try {
			conn.setReadOnly(true);
		} catch (SQLException e) {
			// a hint only: SQLite, for one, refuses it once the connection is open
		}
		conn.setAutoCommit(false);
		ACell result;
		try {
			result = runQuery(conn, statement, params, maxRows);
		} catch (SQLException | RuntimeException e) {
			// The query's own failure is the one to report
			try {
				conn.rollback();
			} catch (SQLException rollback) {
				e.addSuppressed(rollback);
			}
			throw e;
		}
		conn.rollback();
		return result;
	}

	private static ACell runQuery(Connection conn, String statement, AVector<ACell> params, long maxRows) throws SQLException {
		if (params == null || params.count() == 0) {
			try (Statement st = conn.createStatement()) {
				setMaxRowsHint(st, maxRows);
				try (ResultSet rs = st.executeQuery(statement)) {
					return readResults(rs, maxRows);
				}
			}
		}
		try (PreparedStatement ps = conn.prepareStatement(statement)) {
			bindParams(ps, params);
			setMaxRowsHint(ps, maxRows);
			try (ResultSet rs = ps.executeQuery()) {
				return readResults(rs, maxRows);
			}
		}
	}

	// ========== Database resolution ==========

	/** A database name resolved to the JDBC URL and properties to connect with. */
	private record Database(String url, Properties props) {

		/** True when convex-db's driver serves this URL: every venue-local
		 *  database, and any registered {@code jdbc:convex:} connection. */
		boolean isConvexDb() {
			return url.startsWith(ConvexDriver.PREFIX);
		}

		/** A new connection to database {@code db}; a failure is the operator's or the database's, never the caller's. */
		Connection connect(String db) {
			String prefix = "SQL database '" + db + "' ";
			try {
				DriverManager.getDriver(url);
			} catch (SQLException e) {
				log.warn("SQL database '{}': no JDBC driver accepts its url", db);
				throw new ConfigurationException(prefix + NO_DRIVER);
			}
			try {
				return DriverManager.getConnection(url, props);
			} catch (SQLException e) {
				String state = e.getSQLState();
				log.warn("SQL database '{}' refused a connection (SQLState {}, error code {}): {}",
					db, state, e.getErrorCode(), databaseMessage(e));
				String codes = codes(state, e.getErrorCode());
				if (state != null && state.startsWith("28")) {
					throw new ConfigurationException(prefix + CREDENTIALS_REFUSED + codes, e);
				}
				throw new UnavailableException(prefix + UNREACHABLE + codes, e);
			}
		}
	}

	/**
	 * Resolves a database name. Operator-registered entries win; otherwise
	 * the name is the caller's venue-local convex-db database
	 * (created on first use).
	 */
	private Database resolveDatabase(RequestContext ctx, String db) {
		AMap<AString, ACell> cfg = engine.adapterConfig("sql");
		ACell external = RT.getIn(cfg, "databases", db);
		Properties props = new Properties();
		if (external != null) {
			AString url = RT.ensureString(RT.getIn(external, "url"));
			if (url == null) throw new ConfigurationException(
				"Configured SQL database '" + db + "' has no url in venue config");
			AString user = RT.ensureString(RT.getIn(external, "user"));
			if (user != null) props.setProperty("user", user.toString());
			AString password = RT.ensureString(RT.getIn(external, "password"));
			if (password != null) props.setProperty("password", resolvePassword(db, password));
			return new Database(url.toString(), props);
		}

		// Scoped to the USER: an agent shares its owner's venue-local database
		// rather than getting a private one the owner cannot see.
		AString user = ctx.getUserDID();
		if (user == null) throw new AuthException(
			"Venue-local SQL databases require an authenticated caller"
			+ " — or register '" + db + "' under adapters.sql.databases in venue config");
		if (engine.isPublicPrincipal(user)) throw new AuthException(PUBLIC_HAS_NO_DATABASES);
		String name = localName(user, db);
		if (name == null) throw new NotSupportedException(user + " " + UNNAMEABLE_OWNER);
		// ONE convex-db instance for all venue-local databases, with per-user
		// isolation by database name inside it: one instance = one store, so a
		// venue's local SQL state lives in a single Etch file. The name goes as
		// a connection property, so nothing in it is read as a URL parameter.
		props.setProperty("database", name);
		AString path = RT.ensureString(RT.getIn(cfg, "path"));
		String url = (path != null) ? "jdbc:convex:file:" + path : "jdbc:convex:mem:covia_sql";
		return new Database(url, props);
	}

	/**
	 * The name of a user's venue-local database within the venue's convex-db
	 * store: {@code <userDID>/sql/<db>}, the resource a {@code sql/<db>}
	 * capability names under that user. No two users or databases share a
	 * name, as a database name never contains {@code /}. A resource's owner
	 * is what precedes its first {@code /}, so a principal whose name
	 * contains one owns none (null); no DID does.
	 */
	static String localName(AString user, String db) {
		String owner = user.toString();
		if (owner.indexOf('/') >= 0) return null;
		return owner + "/sql/" + db;
	}

	/** Password entries should be secret references ({@code s/<name>}),
	 *  resolved in the VENUE's store at connect time — fail-closed on a
	 *  missing secret. Literals pass through (config is operator-side). */
	private String resolvePassword(String db, AString password) {
		String ref = password.toString();
		if (ref.startsWith("s/") || ref.startsWith("/s/")) {
			String value = engine.resolveSecret(ref, engine.venueContext());
			if (value == null) throw new ConfigurationException(
				"SQL database '" + db + "' password secret not found in the venue store: " + ref
				+ " — store it with the v/ops/secret/set operation");
			return value;
		}
		return ref;
	}

	// ========== Helpers ==========

	private static String requireDb(ACell input) {
		AString db = RT.ensureString(RT.getIn(input, K_DB));
		if (db == null || !db.toString().matches("[a-zA-Z0-9_]{1,64}")) {
			throw new InvalidInputException(DB_REQUIRED);
		}
		return db.toString();
	}

	private static String requireStatement(ACell input) {
		AString s = RT.ensureString(RT.getIn(input, K_STATEMENT));
		if (s == null || s.isEmpty()) {
			throw new InvalidInputException(
				"statement is required — a SQL statement, optionally with ? placeholders bound from params");
		}
		return s.toString();
	}

	private static long maxRows(ACell input) {
		ACell v = RT.getIn(input, K_MAX_ROWS);
		if (v == null) return DEFAULT_MAX_ROWS;
		Long n = RT.ensureLong(v) != null ? RT.ensureLong(v).longValue() : null;
		if (n == null || n < 1) throw new InvalidInputException(
			"maxRows must be a positive integer (default " + DEFAULT_MAX_ROWS + ", max " + HARD_MAX_ROWS + ")");
		return Math.min(n, HARD_MAX_ROWS);
	}

	/** Binds params with TYPED setters, matching JDBC driver conventions
	 *  (convex-db's own tests bind typed; setObject is less travelled). */
	private static void bindParams(PreparedStatement ps, AVector<ACell> params) throws SQLException {
		for (long i = 0; i < params.count(); i++) {
			int idx = (int) i + 1;
			Object o = JSON.json(params.get(i));
			if (o == null) ps.setObject(idx, null);
			else if (o instanceof String s) ps.setString(idx, s);
			else if (o instanceof Long l) ps.setLong(idx, l);
			else if (o instanceof Integer n) ps.setInt(idx, n);
			else if (o instanceof Double d) ps.setDouble(idx, d);
			else if (o instanceof Boolean b) ps.setBoolean(idx, b);
			else if (o instanceof java.math.BigDecimal bd) ps.setBigDecimal(idx, bd);
			else if (o instanceof Number n) ps.setLong(idx, n.longValue());
			else ps.setObject(idx, o);
		}
	}

	/** Best-effort row-cap hint to the driver; the reader enforces the cap
	 *  regardless. Some drivers reject setMaxRows — never fatal. */
	private static void setMaxRowsHint(Statement st, long maxRows) {
		try {
			st.setMaxRows((int) Math.min(maxRows + 1, Integer.MAX_VALUE));
		} catch (Exception e) {
			// hint only
		}
	}

	private static ACell readResults(ResultSet rs, long maxRows) throws SQLException {
		ResultSetMetaData md = rs.getMetaData();
		int n = md.getColumnCount();
		AVector<ACell> columns = Vectors.empty();
		for (int i = 1; i <= n; i++) {
			columns = columns.conj(Strings.create(md.getColumnLabel(i)));
		}
		AVector<ACell> rows = Vectors.empty();
		boolean truncated = false;
		while (rs.next()) {
			if (rows.count() >= maxRows) {
				truncated = true;
				break;
			}
			AVector<ACell> row = Vectors.empty();
			for (int i = 1; i <= n; i++) {
				row = row.conj(toCell(rs.getObject(i)));
			}
			rows = rows.conj(row);
		}
		AMap<AString, ACell> result = Maps.of(
			K_COLUMNS, columns,
			K_ROWS, rows,
			K_COUNT, CVMLong.create(rows.count()));
		if (truncated) result = result.assoc(K_TRUNCATED, CVMBool.TRUE);
		return result;
	}

	/** JDBC value → cell. Common SQL types map naturally; anything exotic
	 *  (dates, intervals) falls back to its string form. */
	private static ACell toCell(Object o) {
		if (o == null) return null;
		if (o instanceof String s) return Strings.create(s);
		if (o instanceof Boolean b) return CVMBool.of(b);
		if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
			return CVMLong.create(((Number) o).longValue());
		}
		if (o instanceof Float || o instanceof Double) {
			return CVMDouble.create(((Number) o).doubleValue());
		}
		if (o instanceof java.math.BigDecimal bd) return CVMDouble.create(bd.doubleValue());
		if (o instanceof byte[] b) return convex.core.data.Blob.wrap(b);
		return Strings.create(String.valueOf(o));
	}

	/** Everything a cause chain says, for the venue log only: Calcite wraps deeply. */
	private static String databaseMessage(Throwable e) {
		StringBuilder sb = new StringBuilder();
		String last = null;
		for (Throwable t = e; t != null; t = t.getCause()) {
			String message = t.getClass().getSimpleName() + ": " + t.getMessage();
			if (!message.equals(last)) sb.append(sb.isEmpty() ? "" : " <- ").append(message);
			last = message;
			if (t.getCause() == t) break;
		}
		return sb.toString();
	}
}
