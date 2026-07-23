package covia.adapter.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

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
import covia.adapter.AAdapter;
import covia.exception.JobFailedException;
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
 *       namespaced by caller DID, created on first use. Persistent when the
 *       venue config sets {@code adapters.sql.path} (an Etch file);
 *       in-memory otherwise.</li>
 *   <li><b>Operator-registered connection</b>: an entry under venue config
 *       {@code adapters.sql.databases.<name>} with {@code url}, optional
 *       {@code user} and {@code password} (a {@code s/<name>} secret
 *       reference, resolved in the venue's store at connect time). Shared
 *       across callers; any JDBC driver on the classpath.</li>
 * </ul>
 *
 * <p>Capabilities are resource-precise: {@code sql/<db>} with ability
 * {@code sql/query} or {@code sql/execute} — a UCAN grant can delegate one
 * database, and capability gates / argument defaults compose as with any
 * operation.</p>
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
		// the module jar's own classloader, so v/skills/sql exists exactly when
		// this module is loaded.
		installSkill("sql", "/skills/sql.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			throw new IllegalArgumentException("Insufficient specification for SQL operation");
		}
		return switch (subOp) {
			case "query" -> CompletableFuture.supplyAsync(() -> handleQuery(ctx, input), VIRTUAL_EXECUTOR);
			case "execute" -> CompletableFuture.supplyAsync(() -> handleExecute(ctx, input), VIRTUAL_EXECUTOR);
			default -> throw new UnsupportedOperationException("Unsupported SQL operation: " + subOp);
		};
	}

	// ========== Operations ==========

	ACell handleQuery(RequestContext ctx, ACell input) {
		String db = requireDb(input);
		ctx.requireCapability("sql/" + db, "sql/query");
		String statement = requireStatement(input);
		AVector<ACell> params = RT.ensureVector(RT.getIn(input, K_PARAMS));
		long maxRows = maxRows(input);

		try (Connection conn = openConnection(ctx, db)) {
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
		} catch (JobFailedException e) {
			throw e;
		} catch (Exception e) {
			throw new JobFailedException("SQL query failed on database '" + db + "': " + rootMessage(e));
		}
	}

	ACell handleExecute(RequestContext ctx, ACell input) {
		String db = requireDb(input);
		ctx.requireCapability("sql/" + db, "sql/execute");
		String statement = requireStatement(input);
		AVector<ACell> params = RT.ensureVector(RT.getIn(input, K_PARAMS));

		try (Connection conn = openConnection(ctx, db)) {
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
		} catch (JobFailedException e) {
			throw e;
		} catch (Exception e) {
			throw new JobFailedException("SQL statement failed on database '" + db + "': " + rootMessage(e));
		}
	}

	// ========== Database resolution ==========

	/**
	 * Resolves a database name to a live connection. Operator-registered
	 * entries win; otherwise the name is a venue-local convex-db database
	 * namespaced by caller DID (created on first use).
	 */
	private Connection openConnection(RequestContext ctx, String db) throws Exception {
		AMap<AString, ACell> cfg = engine.config().getAdapterConfig("sql");
		ACell external = RT.getIn(cfg, "databases", db);
		if (external != null) {
			AString url = RT.ensureString(RT.getIn(external, "url"));
			if (url == null) throw new JobFailedException(
				"Configured SQL database '" + db + "' has no url in venue config");
			Properties props = new Properties();
			AString user = RT.ensureString(RT.getIn(external, "user"));
			if (user != null) props.setProperty("user", user.toString());
			AString password = RT.ensureString(RT.getIn(external, "password"));
			if (password != null) props.setProperty("password", resolvePassword(db, password));
			return DriverManager.getConnection(url.toString(), props);
		}

		// Scoped to the USER: an agent shares its owner's venue-local database
		// rather than getting a private one the owner cannot see.
		AString caller = ctx.getUserDID();
		if (caller == null) throw new JobFailedException(
			"Venue-local SQL databases require an authenticated caller"
			+ " — or register '" + db + "' under adapters.sql.databases in venue config");
		String scoped = sanitise(caller.toString()) + "__" + db;
		// ONE convex-db instance for all venue-local databases, with per-user
		// isolation via the database parameter inside it: one instance = one
		// store, so a venue's local SQL state lives in a single Etch file.
		AString path = RT.ensureString(RT.getIn(cfg, "path"));
		String url = (path != null)
			? "jdbc:convex:file:" + path + ";database=" + scoped
			: "jdbc:convex:mem:covia_sql;database=" + scoped;
		return DriverManager.getConnection(url);
	}

	/** Password entries should be secret references ({@code s/<name>}),
	 *  resolved in the VENUE's store at connect time — fail-closed on a
	 *  missing secret. Literals pass through (config is operator-side). */
	private String resolvePassword(String db, AString password) {
		String ref = password.toString();
		if (ref.startsWith("s/") || ref.startsWith("/s/")) {
			String value = engine.resolveSecret(ref, engine.venueContext());
			if (value == null) throw new JobFailedException(
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
			throw new IllegalArgumentException(
				"db is required — a database name matching [a-zA-Z0-9_]{1,64}:"
				+ " one of your venue-local databases (created on first use)"
				+ " or a connection registered in venue config");
		}
		return db.toString();
	}

	private static String requireStatement(ACell input) {
		AString s = RT.ensureString(RT.getIn(input, K_STATEMENT));
		if (s == null || s.isEmpty()) {
			throw new IllegalArgumentException(
				"statement is required — a SQL statement, optionally with ? placeholders bound from params");
		}
		return s.toString();
	}

	private static long maxRows(ACell input) {
		ACell v = RT.getIn(input, K_MAX_ROWS);
		if (v == null) return DEFAULT_MAX_ROWS;
		Long n = RT.ensureLong(v) != null ? RT.ensureLong(v).longValue() : null;
		if (n == null || n < 1) throw new IllegalArgumentException(
			"maxRows must be a positive integer (default " + DEFAULT_MAX_ROWS + ", max " + HARD_MAX_ROWS + ")");
		return Math.min(n, HARD_MAX_ROWS);
	}

	/** Binds params with TYPED setters, matching JDBC driver conventions
	 *  (convex-db's own tests bind typed; setObject is less travelled). */
	private static void bindParams(PreparedStatement ps, AVector<ACell> params) throws Exception {
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

	private static ACell readResults(ResultSet rs, long maxRows) throws Exception {
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

	private static String sanitise(String did) {
		return did.replaceAll("[^A-Za-z0-9]", "_");
	}

	/** The most diagnosable message in a cause chain — Calcite wraps deeply. */
	private static String rootMessage(Throwable e) {
		Throwable best = null;
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t.getMessage() != null) best = t;
			if (t.getCause() == t) break;
		}
		return (best != null) ? best.getMessage() : e.getClass().getSimpleName();
	}
}
