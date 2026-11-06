package covia.adapter;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.ACountable;
import convex.core.data.util.CellExplorer;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.ASet;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.lattice.ALattice;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.exception.WrongScopeException;
import covia.lattice.AdapterView;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Entry;
import covia.lattice.Namespace;
import covia.lattice.Position;
import covia.lattice.ValuePath;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;

/**
 * Adapter for native Covia venue operations.
 *
 * <p>Provides generic lattice read/list access to the authenticated user's
 * namespace. Callers supply a JSON-friendly path (e.g. {@code "g/my-agent/state"})
 * and this adapter navigates the lattice to read or list values.</p>
 *
 * <h3>Paths</h3>
 *
 * <p>A path is string segments: {@code "g"}, {@code "my-agent"}, a hex job
 * ID, a list position in digits, made from the caller's input by
 * {@link CoviaPath#of}. The component a segment reaches maps it to a
 * lattice key, and below the last component {@link ValuePath} reads it by the
 * value it meets: a map key, an Index key as the string or as a hex Blob, or a
 * vector index.</p>
 *
 * <p>For the reverse direction (listing keys back to the caller),
 * {@link ALattice#toJSONKey(ACell)} converts CVM keys to JSON-friendly
 * form: Keywords → name strings, Blobs → hex strings, etc.</p>
 *
 * <h3>User namespace structure</h3>
 * <pre>
 *   user-root  (structurally navigable record under the venue :value)
 *   ├── g/          agents     (per-agent records, string-keyed map)
 *   ├── s/          secrets    (encrypted values)
 *   ├── j/          jobs       (job records by Blob ID, sorted Index)
 *   ├── w/          workspace  ({updated,data} view; user-writable)
 *   ├── o/          operations ({updated,data} view; user-writable)
 *   ├── h/          HITL inbox ({updated,data} view; framework-managed)
 *   └── a/          assets     (content-addressed references)
 * </pre>
 *
 * <p>A path is resolved by the component it starts from, which the request
 * chooses ({@link #resolve(RequestContext, ACell)}): the venue for a path that
 * starts with a DID, the request's user for a bare path, and for the
 * context-dependent namespaces the request's agent ({@code n/}), session
 * ({@code c/}), job's scratch ({@code t/}), the venue's user ({@code v/}) or an
 * adapter's view ({@code x/<adapter>/}). Resolution checks nothing; each
 * operation checks the caller's authority and applies its write rules.</p>
 *
 * <p>A change by path is accepted in {@code w/} and {@code o/}, through
 * {@code n/}, {@code c/} and {@code t/} within their agent, session or job, in
 * {@code v/} from the venue itself, and in an {@code x/} view as its adapter
 * allows; {@code covia:delete} also removes a whole secret ({@code s/<name>}).
 * Other stored namespaces ({@code g/}, {@code s/}, {@code j/}) are
 * framework-managed and reject direct writes.</p>
 *
 * <p>All user state lives inside the venue {@code :value}, which merges
 * whole-value by timestamp and re-stamps on every write (see {@link covia.lattice.Covia}).
 * Deletions are therefore durable across the propagator's merge-back — the whole
 * region is replaced by the newer snapshot, not per-key unioned. Writes are a
 * whole-value read-modify-write on the user's value; structural navigation builds
 * intermediates by key shape.</p>
 *
 * <h3>Consistency model</h3>
 *
 * <p>All native covia CRUD sub-operations ({@code read}, {@code write},
 * {@code delete}, {@code append}, {@code list}, {@code slice}, {@code copy},
 * {@code inspect}) are <b>strongly consistent and synchronous</b> on the
 * in-memory lattice cursor. {@link #invokeFuture} dispatches every handler via
 * {@link CompletableFuture#completedFuture(Object)} — the cursor mutation has
 * already completed before the returned future resolves. There is no async
 * commit phase, no lazy path-index projection, and no
 * {@code STARTED → COMPLETE} lifecycle for these calls (that pattern belongs
 * to job-managed operations dispatched through
 * {@link covia.venue.JobManager}, not to native CRUD).</p>
 *
 * <p>Guarantees, for any single caller DID:</p>
 * <ul>
 *   <li>A {@code covia:write} is immediately visible to the next
 *       {@code covia:read}, {@code covia:list}, or {@code covia:slice} on
 *       the same path or any ancestor.</li>
 *   <li>A {@code covia:delete} is immediately reflected in subsequent reads
 *       and lists — no tombstone settling, no eventual visibility.</li>
 *   <li>{@code covia:list} counts are stable across repeated calls when no
 *       writers are active.</li>
 *   <li>Concurrent writes to distinct keys from the same caller all survive
 *       (no lost updates).</li>
 * </ul>
 *
 * <p>Each caller DID has its own {@link covia.venue.User} lattice — cross-DID
 * calls target different namespaces and will not see each other's data. If a
 * caller's effective identity changes between requests (e.g. across a venue
 * restart that rotated the venue's signing key), apparent "wobble" in counts
 * or "disappearing" writes are a consequence of that identity change, not of
 * any consistency gap in this adapter.</p>
 */
public class CoviaAdapter extends AAdapter {

	private static final AString SLASH     = Strings.intern("/");
	private static final AString K_KEYS    = Strings.intern("keys");
	private static final AString K_COUNT = Strings.intern("count");
	private static final AString K_TYPE    = Strings.intern("type");
	private static final AString K_VALUE   = Strings.intern("value");
	private static final AString K_VALUES  = Strings.intern("values");
	private static final AString K_EXISTS  = Strings.intern("exists");
	private static final AString K_VALUE_BYTES  = Strings.intern("valueBytes");
	private static final AString K_TRUNCATED = Strings.intern("truncated");
	private static final AString K_MAX_SIZE  = Strings.intern("maxSize");
	private static final AString K_FIELDS    = Strings.intern("fields");

	/** Cap on projected field count per list call (#191) — exceeding it is an
	 *  explicit error, never a silent truncation. */
	private static final int MAX_PROJECT_FIELDS = 16;

	/** Default max memory size (bytes) for covia:read responses. ~1 MB. */
	private static final long DEFAULT_MAX_SIZE = 1_000_000;

	/**
	 * Namespaces a direct path may change. An agent's {@code n}, a session's
	 * {@code c} and a job's {@code temp} are changed only through {@code n/},
	 * {@code c/} and {@code t/}.
	 */
	private static final Set<String> WRITABLE_NAMESPACES = Set.of("w", "o");

	/** The context-dependent namespaces: the request names the component each starts at. */
	private static final AString N = Strings.intern("n");
	private static final AString C = Strings.intern("c");
	private static final AString T = Strings.intern("t");
	private static final AString V = Strings.intern("v");

	/** The venue user's path that {@code v/} stands for. */
	public static final CoviaPath VENUE_GLOBALS = CoviaPath.parse("w/global");

	public static final String NO_AGENT_SCOPE = "Cannot use 'n/' prefix outside agent scope";
	public static final String NO_SESSION_SCOPE =
		"Cannot use 'c/' prefix outside session scope (requires agentId and sessionId on RequestContext)";
	public static final String NO_JOB_SCOPE =
		"Cannot use 't/' prefix outside job or task scope (requires agentId+taskId or jobId on RequestContext)";
	public static final String TASK_WITHOUT_AGENT = "Cannot use task-scoped 't/' without agentId on RequestContext";
	public static final String NO_USER_SCOPE = "Cannot use a path without an owner DID outside a user's scope (requires userDID on RequestContext)";
	public static final String VENUE_WRITES_ONLY = "Write permission denied for namespace: v";
	public static final String NAMESPACE_AND_KEY = "Path must include namespace and key, e.g. 'w/my-key'";
	public static final String NOT_WRITABLE = "Namespace '%s' is not writable (framework-managed). "
		+ "Writable namespaces: w/ (workspace), o/ (operation pins); "
		+ "n/ and t/ are writable within an agent or job run.";
	public static final String WHOLE_SECRET = "Secret deletion must address a whole record, e.g. 's/MY_KEY'";

	@Override
	public String getName() {
		return "covia";
	}

	@Override
	public String getDescription() {
		return "Provides native access to internal services in this Covia venue, "
			+ "including lattice read/write/list/delete for user data.";
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/workspace lives and dies with this adapter.
		installSkill("data/workspace", "/skills/workspace.json");
		installSkill("root/workspace", "/skills/workspace.json");
		String BASE = "/adapters/covia/";
		installAsset("covia/read",    BASE + "read.json");
		installAsset("covia/write",   BASE + "write.json");
		installAsset("covia/copy",    BASE + "copy.json");
		installAsset("covia/delete",  BASE + "delete.json");
		installAsset("covia/append",  BASE + "append.json");
		installAsset("covia/slice",   BASE + "slice.json");
		installAsset("covia/list",    BASE + "list.json");
		installAsset("covia/inspect", BASE + "inspect.json");
		installAsset("covia/aggregate", BASE + "aggregate.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// Authentication required: every covia:* op runs in a user namespace.
		// Engine-startup code that needs venue-authority writes uses
		// engine.venueContext() which has the venue's own DID as caller.
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new AuthException("Authentication required"));
		}
		try {
			return switch (getSubOperation(meta)) {
				case "read" -> CompletableFuture.completedFuture(handleRead(ctx, input));
				case "write" -> CompletableFuture.completedFuture(handleWrite(ctx, input));
				case "copy" -> CompletableFuture.completedFuture(handleCopy(ctx, input));
				case "delete" -> CompletableFuture.completedFuture(handleDelete(ctx, input));
				case "append" -> CompletableFuture.completedFuture(handleAppend(ctx, input));
				case "slice" -> CompletableFuture.completedFuture(handleSlice(ctx, input));
				case "list" -> CompletableFuture.completedFuture(handleList(ctx, input));
				case "inspect" -> CompletableFuture.completedFuture(handleInspect(ctx, input));
				case "aggregate" -> CompletableFuture.completedFuture(handleAggregate(ctx, input));
				default -> CompletableFuture.failedFuture(
					new NotSupportedException("Unknown covia operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Reads the value at a path in the user's lattice.
	 *
	 * <p>The path's string segments are resolved through the components they
	 * reach, each mapping a segment to its lattice key
	 * ({@link covia.lattice.ACoviaComponent#resolve}), and the value where they
	 * end is read.</p>
	 *
	 * <p>Response always includes {@code exists} (boolean) and {@code value}:</p>
	 * <ul>
	 *   <li>{@code {exists: true, value: <value>}} — path has data</li>
	 *   <li>{@code {exists: false, value: null}} — the path names a place that
	 *       could hold data, but nothing is there, including in a user, agent,
	 *       session or job that does not exist, and a content hash that names
	 *       no asset</li>
	 * </ul>
	 * <p>A path that names no place data could be, such as a name a user's data
	 * does not have, is refused with {@link covia.lattice.ACoviaComponent#NO_SUCH_PATH}.</p>
	 *
	 * <p>An optional {@code maxSize} parameter (default {@value #DEFAULT_MAX_SIZE}
	 * bytes) guards against returning excessively large values. If the value's
	 * memory size exceeds the limit, the response includes
	 * {@code {exists: true, value: null, truncated: true, size: <bytes>}} so the
	 * caller can use {@code covia:list} to inspect the structure or
	 * {@code covia:slice} to read vector elements in pages.</p>
	 */
	public ACell handleRead(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_READ);
		// Try universal resolution via Engine.resolvePath. This covers every
		// input form per OPERATIONS.md §4: bare hex hash, /a/<hash>, /o/<name>,
		// local DID URL, virtual namespaces and workspace paths (w/, g/, o/, j/,
		// etc.). Returns the literal value at the resolved location with no
		// ref-following or asset interpretation.
		AString pathStr = RT.ensureString(RT.getIn(input, Fields.PATH));
		if (pathStr != null) {
			ACell value = engine.resolvePathUnchecked(pathStr, ctx);   // checked by requireCap above
			if (value != null) {
				return sizeGuardedResult(value, input);
			}
			// A content hash names an asset, not a place in the lattice.
			if (Hash.parse(pathStr) != null) return result(null);
		}

		// Then the lattice path itself: a stored null, an absent value, an
		// invalid path, or a vector path form.
		Position target = resolve(ctx, RT.getIn(input, Fields.PATH));
		ACell value = target.read();
		// A stored null reads back as present (exists:true, value:null), distinct
		// from an absent path (exists:false) — decided by key presence in the parent.
		if (value == null) {
			return result(null, target.exists());
		}
		return sizeGuardedResult(value, input);
	}

	/**
	 * Resolves one exact lattice value for framework context assembly.
	 * Unlike the public read operation this returns the value itself (no
	 * {@code {exists,value}} envelope and no response-size guard), while using
	 * the identical namespace semantics: physical user paths plus cursor-based
	 * {@code n/}, job-scoped {@code t/}, and session-scoped {@code c/} views.
	 * The caller must hold {@code crud/read} on the path.
	 */
	public ACell readContextValue(RequestContext ctx, AString path) {
		if (path == null) return null;
		engine.requireResourceAccess(ctx, path, Capability.CRUD_READ);
		if (isContextDependent(CoviaPath.parse(path.toString()))) return resolve(ctx, path).read();

		// Physical namespaces and universal asset/op references.
		return engine.resolvePathUnchecked(path, ctx);
	}

	/**
	 * The {@code maxSize} cap (CAD3 encoding bytes) for the exact-value read ops
	 * ({@code covia:read}, {@code covia:slice}). This is CVM storage size — a hard
	 * cap on the exact return value — and is a distinct concern from
	 * {@code covia:inspect}'s rendering {@code budget}, which bounds a
	 * *summarised* view. Default {@value #DEFAULT_MAX_SIZE} bytes.
	 */
	private static long resolveMaxSize(ACell input) {
		ACell maxSizeCell = RT.getIn(input, K_MAX_SIZE);
		return (maxSizeCell instanceof CVMLong l) ? Math.max(0, l.longValue()) : DEFAULT_MAX_SIZE;
	}

	/**
	 * Builds a {@code covia:slice} page response, enforcing the {@code maxSize}
	 * cap on the exact returned values (#78). Slice returns raw values and never
	 * summarises — so an oversize page {@code fail}s with a clear, actionable
	 * error (reduce {@code limit}, or read the oversize entry directly) rather
	 * than silently returning an unbounded response (the pre-#78 bug) or a
	 * lossy render. {@code inspect} is the op for a summarised view.
	 */
	private static ACell slicePage(AString typeName, long total, AVector<ACell> page,
			long offset, ACell input) {
		long maxSize = resolveMaxSize(input);
		long size = Cells.storageSize(page);
		if (size > maxSize) {
			throw new InvalidInputException(
				"slice response " + size + " bytes exceeds maxSize " + maxSize
				+ " — reduce limit (or, if a single entry is oversize, read it directly "
				+ "or use covia:inspect for a summarised view). slice returns exact values, never summarised.");
		}
		return Maps.of(K_EXISTS, CVMBool.TRUE, K_TYPE, typeName,
			K_COUNT, CVMLong.create(total), K_VALUES, page,
			Fields.OFFSET, CVMLong.create(offset));
	}

	/**
	 * Returns a read result, applying the maxSize guard if necessary.
	 */
	private static ACell sizeGuardedResult(ACell value, ACell input) {
		if (value == null) return result(null);

		long maxSize = resolveMaxSize(input);

		// CVM storage size (recursive) — the true byte footprint of the exact
		// value, not the container's own encoding (which refs large children
		// and undercounts). This is what `maxSize` means for read and slice.
		long storageSize = Cells.storageSize(value);
		if (storageSize > maxSize) {
			return Maps.of(
				K_EXISTS, CVMBool.TRUE,
				K_TYPE, Types.get(value).toAString(),
				K_VALUE, null,
				K_TRUNCATED, CVMBool.TRUE,
				K_VALUE_BYTES, CVMLong.create(storageSize));
		}

		return result(value);
	}

	// ========== covia:inspect — Budget-controlled JSON5 reads ==========

	private static final AString K_PATHS   = Strings.intern("paths");
	private static final AString K_BUDGET  = Strings.intern("budget");
	private static final AString K_COMPACT = Strings.intern("compact");
	private static final AString K_RESULT  = Strings.intern("result");

	/** Default inspect budget in bytes (storage-size units) */
	private static final int DEFAULT_INSPECT_BUDGET = 500;

	/**
	 * Budget-controlled inspection of lattice data via {@link CellExplorer}.
	 *
	 * <p>Accepts a single path or array of paths. Each value is rendered as JSON5
	 * with structural annotations, truncated to the byte budget. For multiple
	 * paths the budget is split equally.</p>
	 *
	 * @return For a single path: {@code {result: "rendered JSON5"}}. For multiple
	 *         paths: {@code {result: {path1: "...", path2: "..."}}}
	 */
	public ACell handleInspect(RequestContext ctx, ACell input) {
		// Keep the provider contract unambiguous: exactly one string `path` or
		// string-vector `paths`. In particular, do not stringify arbitrary cells
		// into paths — a malformed tool call must fail instead of silently reading
		// a surprising location.
		ACell pathsCell = RT.getIn(input, K_PATHS);
		ACell pathCell = RT.getIn(input, Fields.PATH);
		if ((pathCell == null) == (pathsCell == null)) {
			throw new InvalidInputException("Provide exactly one of 'path' or 'paths'");
		}

		// Budget
		int budget = DEFAULT_INSPECT_BUDGET;
		ACell budgetCell = RT.getIn(input, K_BUDGET);
		if (budgetCell instanceof CVMLong l) budget = (int) Math.max(10, l.longValue());

		// Compact (default true)
		boolean compact = true;
		ACell compactCell = RT.getIn(input, K_COMPACT);
		if (CVMBool.FALSE.equals(compactCell)) compact = false;

		// Single path
		if (pathCell != null) {
			AString path = RT.ensureString(pathCell);
			if (path == null) throw new InvalidInputException("'path' must be a string");
			String rendered = explorePath(ctx, path.toString(), budget, compact);
			return Maps.of(K_RESULT, Strings.create(rendered));
		}

		// Multiple paths
		if (!(pathsCell instanceof AVector<?> pathsVec) || pathsVec.isEmpty()) {
			throw new InvalidInputException("'paths' must be a non-empty array of strings");
		}
		int perPath = Math.max(10, budget / (int) pathsVec.count());
		AMap<AString, ACell> results = Maps.empty();
		for (long i = 0; i < pathsVec.count(); i++) {
			AString path = RT.ensureString(pathsVec.get(i));
			if (path == null) throw new InvalidInputException("'paths' must contain only strings");
			String pathStr = path.toString();
			String rendered = explorePath(ctx, pathStr, perPath, compact);
			results = results.assoc(path, Strings.create(rendered));
		}
		return Maps.of(K_RESULT, results);
	}

	/**
	 * Resolves a single path and renders the value via CellExplorer.
	 */
	private String explorePath(RequestContext ctx, String pathStr, int budget, boolean compact) {
		engine.requireResourceAccess(ctx, Strings.create(pathStr), Capability.CRUD_READ);
		ACell value = resolve(ctx, Strings.create(pathStr)).read();
		if (value == null) return "null /* not found: " + pathStr + " */";

		CellExplorer explorer = new CellExplorer(budget, compact);
		return explorer.explore(value).toString();
	}

	/**
	 * Reads a paginated slice of entries from a data structure at a path.
	 *
	 * <p>Supports vectors, maps, and sets. For vectors and sets, returns
	 * elements as {@code values}. For maps, returns entries as
	 * {@code values} (each entry is a {@code {key, value}} map).
	 * Pagination via {@code offset} (default 0) and {@code limit}
	 * (default 100). Works on any path (all namespaces).</p>
	 *
	 * @return {@code {type, values: [...], count: <total>, offset: <offset>}}
	 */
	@SuppressWarnings("unchecked")
	public ACell handleSlice(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_READ);
		ACell value = readInputValue(ctx, input);
		if (value == null) return Maps.of(K_EXISTS, CVMBool.FALSE);

		long limit = 100;
		long offset = 0;
		ACell limitCell = RT.getIn(input, Fields.LIMIT);
		ACell offsetCell = RT.getIn(input, Fields.OFFSET);
		if (limitCell instanceof CVMLong l) limit = Math.max(1, l.longValue());
		if (offsetCell instanceof CVMLong l) offset = Math.max(0, l.longValue());

		AString typeName = Types.get(value).toAString();

		if (value instanceof AVector<?> vec) {
			long total = vec.count();
			AVector<ACell> page = Vectors.empty();
			long end = Math.min(offset + limit, total);
			for (long i = offset; i < end; i++) {
				page = page.conj(vec.get(i));
			}
			return slicePage(typeName, total, page, offset, input);

		} else if (value instanceof AMap<?,?> map) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) map;
			long total = m.count();
			AVector<ACell> page = Vectors.empty();
			long idx = 0;
			long end = offset + limit;
			for (var entry : m.entrySet()) {
				if (idx >= end) break;
				if (idx >= offset) {
					page = page.conj(Maps.of(
						Strings.intern("key"), ALattice.toJSONKey(entry.getKey()),
						K_VALUE, entry.getValue()));
				}
				idx++;
			}
			return slicePage(typeName, total, page, offset, input);

		} else if (value instanceof ASet<?> set) {
			long total = set.count();
			AVector<ACell> page = Vectors.empty();
			long idx = 0;
			long end = offset + limit;
			for (ACell elem : set) {
				if (idx >= end) break;
				if (idx >= offset) {
					page = page.conj(ALattice.toJSONKey(elem));
				}
				idx++;
			}
			return slicePage(typeName, total, page, offset, input);

		} else {
			throw new InvalidInputException(
				"covia:slice requires a collection (vector, map, or set), got: " + typeName);
		}
	}

	/** Wraps a value with an explicit exists flag — lets a stored null (present,
	 *  {@code exists:true}) be distinguished from an absent path. */
	private static ACell result(ACell value, boolean exists) {
		return Maps.of(
			K_EXISTS, CVMBool.of(exists),
			K_VALUE, value,
			K_VALUE_BYTES, CVMLong.create(value == null ? 0 : value.getEncodingLength()));
	}

	/** Wraps a value with an exists flag. Null value → exists: false. */
	private static ACell result(ACell value) {
		return result(value, value != null);
	}

	// ========== covia:copy — server-side path-to-path duplication ==========

	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_TO = Strings.intern("to");

	/**
	 * Server-side value duplication: reads a value from {@code from} and
	 * writes it to {@code to}, all within the venue boundary.
	 *
	 * <p>Per OPERATIONS.md §6, {@code from} accepts any resolvable address
	 * (hex hash, {@code /a/}, {@code /o/}, {@code /v/}, DID URL, workspace
	 * path) and {@code to} must be a writable mutable path. The op is a
	 * thin composition of {@code resolvePath + write}; reads and writes
	 * use the standard caps enforcement of each side.</p>
	 *
	 * <p>Use this to snapshot a venue op into your own {@code /o/} (e.g.
	 * {@code from=v/ops/json/merge to=o/merge}), to cache remote data
	 * locally ({@code from=did:web:...:w/data to=w/cache/...}), or to
	 * branch any workspace value.</p>
	 *
	 * @return the destination write's result: {@code {pathCreated: true}} if a
	 *         missing parent path was built, else an empty map
	 */
	private ACell handleCopy(RequestContext ctx, ACell input) {
		AString from = RT.ensureString(RT.getIn(input, K_FROM));
		AString to = RT.ensureString(RT.getIn(input, K_TO));
		if (from == null) throw new InvalidInputException("'from' is required");
		if (to == null) throw new InvalidInputException("'to' is required");

		// Reads the source (handleWrite enforces crud/write on the destination).
		engine.requireResourceAccess(ctx, from, Capability.CRUD_READ);

		// Read from source via the canonical universal resolver, checked above.
		ACell value = engine.resolvePathUnchecked(from, ctx);
		if (value == null) {
			throw new NotFoundException("source not found: " + from, from.toString());
		}

		// Delegate to handleWrite for the destination, which enforces
		// writability and per-namespace canWrite checks. Its result carries
		// pathCreated (whether the destination needed new hierarchy built).
		ACell writeInput = Maps.of(Fields.PATH, to, Fields.VALUE, value);
		return handleWrite(ctx, writeInput);
	}

	/**
	 * Enforces the capability for a path-targeted lattice mutation at the point
	 * it executes — the adapter pins the exact resource (the path) and ability,
	 * so the enforced cap cannot drift from the implementation. A null grant scope
	 * (internal/authenticated callers) is unrestricted, so this is a no-op for
	 * them; a restricted scope (e.g. the public read-only profile) is gated.
	 */
	private void requireCap(RequestContext ctx, ACell input, AString ability) {
		// One gate for every lattice op — read or mutation. requireLocalAccess is
		// the shared local-access check (a/ governed exactly like w/): the caller's
		// own namespace goes through the scope seam; another user's needs a proof
		// (or public/venue-catalog policy), else a denial.
		engine.requireLocalAccess(ctx, capResource(input), ability);
	}

	/** The resource a path-targeted op names: its path, with any owner DID. */
	private static AString capResource(ACell input) {
		ACell path = RT.getIn(input, Fields.PATH);
		AString resource = RT.ensureString(path);
		if (path instanceof AVector<?>) resource = Strings.create(CoviaPath.of(path).toString());
		if (resource != null && resource.startsWith(SLASH)) resource = resource.slice(1);
		return resource;
	}

	/**
	 * Runs a change at a resolved path in one transaction, first creating the
	 * levels above it that hold nothing. Creating a key changes the map that
	 * holds it, so a change that creates any key of its path, the last included,
	 * also needs write authority at the nearest level that exists.
	 *
	 * @param address The path as the caller named it
	 * @param change The change, made to the entry in the transaction
	 * @return Whether a level above the changed value was created
	 */
	private boolean changeCreatingLevels(RequestContext ctx, AString address, Position target,
			Consumer<Entry> change) {
		boolean[] created = {false};
		target.entry().transact(draft -> {
			int missing = draft.missingLevels();
			if (missing > 0) engine.requireLocalAccess(ctx, ancestor(address, missing), Capability.CRUD_WRITE);
			created[0] = draft.createLevels() > 0;
			change.accept(draft);
		});
		return created[0];
	}

	/**
	 * The address {@code levels} levels above {@code address}, never above its
	 * namespace ({@code w}, or {@code <did>/w} for another user's).
	 */
	static AString ancestor(AString address, int levels) {
		String[] segments = address.toString().split("/", -1);
		int namespace = address.toString().startsWith("did:") ? 1 : 0;
		int keep = Math.max(namespace + 1, segments.length - levels);
		return Strings.create(String.join("/", Arrays.copyOf(segments, keep)));
	}

	private static AMap<AString, ACell> withPathCreated(AMap<AString, ACell> result, boolean created) {
		return created ? result.assoc(Fields.PATH_CREATED, CVMBool.TRUE) : result;
	}

	/**
	 * Writes a value at a path in the user's lattice.
	 *
	 * <p>Writable where {@link #resolveForChange} allows. Deep paths create
	 * intermediate maps as needed, in the same transaction as the write,
	 * authorised at the nearest level that exists.</p>
	 *
	 * @return {@code {existed, pathCreated?}} — {@code existed:false} created a new
	 *         value, {@code true} replaced an existing one; {@code pathCreated:true}
	 *         is added when a missing parent hierarchy had to be built
	 * @throws RuntimeException if the path is invalid or targets a non-writable namespace
	 */
	private ACell handleWrite(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_WRITE);
		// A write must supply a 'value'. An absent key means the caller (often an
		// LLM tool call) forgot the argument — fail loudly so it retries. An
		// explicit null is a valid value: it stores a null at the path (present,
		// distinct from absent — covia:read reports exists:true). Removing an entry
		// is covia:delete, not a null write.
		if (!(input instanceof AMap) || !((AMap<?, ?>) input).containsKey(Fields.VALUE)) {
			throw new InvalidInputException(
				"Write requires a 'value' field (received path only). Provide the value to "
				+ "store (an explicit null stores a null value); to remove an entry use covia:delete.");
		}
		// The value is stored exactly as supplied. A string is a valid value, so
		// a JSON-looking string stays a string: callers wanting structure send
		// structure (the tool descriptions say so), and string-to-object parsing
		// lives only at tool-call boundaries whose schema admits nothing else.
		ACell value = RT.getIn(input, Fields.VALUE);

		Position target = resolveForChange(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_WRITE);
		boolean[] existed = {false};
		boolean created = changeCreatingLevels(ctx, capResource(input), target,
			draft -> existed[0] = draft.write(value));
		return withPathCreated(Maps.of(Fields.EXISTED, CVMBool.of(existed[0])), created);
	}

	/**
	 * Replaces the value at a path with {@code change} applied to it, in one
	 * atomic update authorised as {@code covia:write} is. {@code change}
	 * receives the current value (null when absent) and may run again on a
	 * retry, so it must be pure; an exception from it leaves the path unchanged.
	 * For an adapter that edits part of a stored value, where a read followed by
	 * a write would lose a concurrent edit.
	 */
	void updatePath(RequestContext ctx, AString path, UnaryOperator<ACell> change) {
		ACell input = Maps.of(Fields.PATH, path);
		requireCap(ctx, input, Capability.CRUD_WRITE);
		Position target = resolveForChange(ctx, path, Capability.CRUD_WRITE);
		changeCreatingLevels(ctx, capResource(input), target,
			draft -> draft.update(change));
	}

	/**
	 * Writes through the canonical mutable-path resolver, including federation
	 * for a foreign {@code did:web} destination.
	 *
	 * <p>Bare paths, execution-scoped virtual paths, local users, and local
	 * did:web aliases use {@link #handleWrite}. A genuinely foreign did:web
	 * target is dispatched to that venue's ordinary {@code covia:write}; this
	 * prevents an output handoff from silently materialising a remote owner as a
	 * shadow user in the local lattice.</p>
	 */
	CompletableFuture<ACell> writeResolvedPath(RequestContext ctx, AString path, ACell value) {
		AString remoteVenue = remoteVenueFor(path);
		if (remoteVenue == null || engine.isLocalDIDResource(path)) {
			ACell writeInput = Maps.of(Fields.PATH, path, Fields.VALUE, value);
			// This is framework composition rather than an invoked covia:write
			// Job. Rebind the capability input so a gate can never accidentally
			// authorise against the preceding agent:request input; without a gate
			// evaluator, gated grants fail closed while ordinary grants work.
			return CompletableFuture.completedFuture(
				handleWrite(ctx.withInvocation(writeInput, null), writeInput));
		}
		AAdapter adapter = engine.getAdapter("grid");
		if (!(adapter instanceof GridAdapter grid)) {
			return CompletableFuture.failedFuture(
				new UnavailableException("Remote output path requires the grid adapter"));
		}
		return grid.writeRemotePath(ctx, remoteVenue, path, value);
	}

	/**
	 * Extracts the routable venue DID from a foreign Covia did:web path.
	 * Venue-managed users and agent sub-principals live below {@code :u:};
	 * {@code :public} is likewise a venue child. Other did:web owners are used
	 * as-is. Non-web DIDs have no network location and retain local DID-path
	 * semantics.
	 */
	static AString remoteVenueFor(AString path) {
		if (path == null) return null;
		String value = path.toString();
		if (!value.startsWith("did:web:")) return null;
		int slash = value.indexOf('/');
		if (slash < 0) return null;
		String owner = value.substring(0, slash);
		int user = owner.indexOf(":u:", "did:web:".length());
		if (user > 0) owner = owner.substring(0, user);
		else if (owner.endsWith(":public")) owner = owner.substring(0, owner.length() - ":public".length());
		return Strings.create(owner);
	}

	/**
	 * Deletes a value at a path in the user's lattice.
	 *
	 * <p>Same namespace restrictions as {@link #handleWrite}, plus {@code s/}
	 * (secrets), which is delete-only: records are created exclusively through
	 * {@code secret:set} (which encrypts), but a caller may remove their own
	 * secret — whole records only, {@code s/&lt;name&gt;} (#166). For
	 * two-segment paths, removes the top-level entry. For deeper paths, removes
	 * the nested key via read-modify-write. Idempotent — deleting a
	 * non-existent key succeeds silently.</p>
	 *
	 * <p>Delete removes only the addressed value; it never prunes the parent
	 * hierarchy — an emptied parent container is left in place.</p>
	 *
	 * @return {@code {deleted}} — {@code true} if a value was present and removed,
	 *         {@code false} if the path was already absent (idempotent no-op)
	 */
	private ACell handleDelete(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_DELETE);
		Position target = resolveForChange(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_DELETE);
		CoviaPath path = target.path();
		if (target.component() instanceof SecretStore secrets && path.length() == 1) {
			AString name = path.first();
			boolean deleted = secrets.deleteIfPresent(name);
			if (deleted) {
				// The name only, never the value — the same trail as secret:set.
				engine.audit().event(covia.venue.Audit.SECRET_DELETE, covia.venue.Audit.K_DID, ctx.getCallerDID(),
					covia.venue.Audit.K_NAME, name, covia.venue.Audit.K_TARGET, ctx.getUserDID());
			}
			return Maps.of(Fields.DELETED, CVMBool.of(deleted));
		}
		boolean deleted = target.entry().delete();
		return Maps.of(Fields.DELETED, CVMBool.of(deleted));
	}

	/**
	 * Appends an element to a vector at a path in the user's lattice.
	 *
	 * <p>Same namespace restrictions as {@link #handleWrite}. If the target
	 * path is null/missing, a new single-element vector is created. If it is
	 * a vector, the element is appended. Errors if the target is a non-vector
	 * type.</p>
	 *
	 * @return {@code {existed, index, newSize, pathCreated?}} — {@code existed} =
	 *         the collection was already there; {@code index} = where the element
	 *         landed ({@code newSize-1}); {@code newSize} = element count after the
	 *         append; {@code pathCreated:true} when a missing parent was built
	 */
	private ACell handleAppend(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_WRITE);
		Position target = resolveForChange(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_WRITE);
		ACell element = RT.getIn(input, Fields.VALUE);
		boolean[] existed = {false};
		long[] size = {0};
		boolean created = changeCreatingLevels(ctx, capResource(input), target, draft -> {
			existed[0] = draft.exists();
			size[0] = draft.append(element);
		});
		return withPathCreated(Maps.of(
			Fields.EXISTED, CVMBool.of(existed[0]),
			Fields.INDEX, CVMLong.create(Math.max(0, size[0] - 1)),
			Fields.NEW_SIZE, CVMLong.create(size[0])), created);
	}

	// ========== Write path helpers ==========

	/**
	 * Where a path ends for a change by {@code ability} (write or delete), after
	 * this adapter's write rules ({@link #requireChangeable}). A write creates
	 * the caller's own record if it has none; another user's record must exist.
	 * An {@code x/} path's view applies its adapter's write policy instead.
	 *
	 * @return Where the path ends
	 */
	private Position resolveForChange(RequestContext ctx, ACell path, AString ability) {
		boolean delete = Capability.CRUD_DELETE.equals(ability);
		AString did = didPath(path);
		Engine.UserPathTarget target = (did == null) ? null : engine.resolveUserPath(ctx, did, false);
		CoviaPath segments = (target == null) ? CoviaPath.of(path) : target.path();
		if (isExtensionPath(segments)) return resolve(ctx, path, ability);
		requireChangeable(ctx, segments, delete);
		if (!delete) {
			if (target == null) {
				// The USER's record, not the acting principal's: an agent sub-principal
				// works inside its owner's namespace and has no lattice record of its own.
				engine.getVenueState().users().ensure(ctx.getUserDID());
			} else if (target.user() == null) {
				// Another user's record is an entry of the venue's users, which no grant
				// of theirs covers, so a write reaches only a user the venue has
				if (!target.ownerDID().equals(ctx.getUserDID())) {
					throw new NotFoundException(covia.venue.VenueState.UNKNOWN_USER, target.ownerDID().toString());
				}
				engine.getVenueState().users().ensure(target.ownerDID());
			}
		}
		return resolve(ctx, path, ability);
	}

	/**
	 * This adapter's write rules for a change by path, given the path after any
	 * owner DID. {@code n/}, {@code c/} and {@code t/} take changes within their
	 * scope, and {@code v/} only from the venue itself. A direct path changes only
	 * {@link #WRITABLE_NAMESPACES}, and a delete may also remove a whole secret
	 * ({@code s/<name>}): secrets are created only by {@code secret:set}, which
	 * encrypts them, and part of a secret's record is never meaningful.
	 *
	 * @throws AuthException for a {@code v/} change by anyone but the venue
	 * @throws InvalidInputException for a path without a key, or part of a secret
	 * @throws NotSupportedException for a framework-managed namespace
	 */
	private void requireChangeable(RequestContext ctx, CoviaPath path, boolean delete) {
		AString namespace = path.isEmpty() ? null : path.first();
		if (N.equals(namespace) || C.equals(namespace) || T.equals(namespace)) return;
		if (V.equals(namespace)) {
			AString caller = (ctx == null) ? null : ctx.getCallerDID();
			if (caller == null || !caller.equals(engine.getDIDString())) throw new AuthException(VENUE_WRITES_ONLY);
			return;
		}
		if (delete && Namespace.S.equals(namespace)) {
			if (path.length() != 2) throw new InvalidInputException(WHOLE_SECRET);
			return;
		}
		if (path.length() < 2) throw new InvalidInputException(NAMESPACE_AND_KEY);
		if (!WRITABLE_NAMESPACES.contains(namespace.toString())) {
			throw new NotSupportedException(String.format(NOT_WRITABLE, namespace));
		}
	}

	/**
	 * Reads the literal value targeted by an accessor input.
	 */
	private ACell readInputValue(RequestContext ctx, ACell input) {
		return resolve(ctx, RT.getIn(input, Fields.PATH)).read();
	}

	/**
	 * Describes the structure at a path in the user's lattice.
	 *
	 * <p>Returns a structured description of whatever value lives at the path:</p>
	 * <ul>
	 *   <li><b>Maps:</b> {@code {type: "map", count: 5, keys: ["status", "config", ...]}}
	 *       — keys converted to JSON-friendly form via {@link ALattice#toJSONKey}</li>
	 *   <li><b>Vectors/sequences:</b> {@code {type: "vector", count: 42}} — no keys,
	 *       use integer indices with {@code covia:read} to access elements</li>
	 *   <li><b>Scalars:</b> {@code {type: "string"}} or {@code {type: "long"}} etc.</li>
	 *   <li><b>Null/missing:</b> {@code {type: "null"}}</li>
	 * </ul>
	 *
	 * <p>For maps with many keys, pagination is supported via {@code limit} (default
	 * 1000) and {@code offset}. When keys are truncated, {@code offset} is included
	 * in the response so the caller knows where to continue.</p>
	 */
	public ACell handleList(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_READ);
		ACell value = readInputValue(ctx, input);

		ACell desc = describeValue(value, input);
		ACell fieldsCell = RT.getIn(input, K_FIELDS);
		if (fieldsCell == null || value == null) return desc;
		return projectFields((AMap<AString, ACell>) desc, value, fieldsCell, input);
	}

	/**
	 * Field projection on a list (#191): the standard partial-response pattern.
	 * Adds a {@code values} map to the list response — for each key on the
	 * current page, the read result of each named subpath of that key's child.
	 *
	 * <p>Pure composition of existing reads: each projected field carries
	 * single-read semantics verbatim ({@code {exists, value}}, per-value
	 * {@code maxSize} → {@code truncated}, stored-null is present), resolved
	 * against the one value snapshot the list already materialised. Projection
	 * applies after the {@code limit}/{@code offset} key page. No filtering, no
	 * ordering — output shape only.</p>
	 *
	 * <p>One capability check suffices: {@code crud/read} on the parent was
	 * already required, grants are positive-only, and resource coverage is
	 * prefix-based ({@link convex.auth.ucan.Capability#covers}) — so parent
	 * coverage implies coverage of every child subpath.</p>
	 */
	@SuppressWarnings("unchecked")
	private static ACell projectFields(AMap<AString, ACell> desc, ACell value,
			ACell fieldsCell, ACell input) {
		if (!(value instanceof AMap)) {
			throw new InvalidInputException(
				"fields projection requires a keyed node (map/Index) at the path; found: "
					+ Types.get(value).toAString());
		}
		AString[] fields = parseFieldsParam(fieldsCell);

		AMap<ACell, ACell> m = (AMap<ACell, ACell>) value;
		long offset = pageOffset(input);
		long end = Math.min(offset + pageLimit(input), m.count());

		AMap<AString, ACell> values = Maps.empty();
		long i = 0;
		for (var entry : m.entrySet()) {
			if (i >= end) break;
			if (i++ < offset) continue;
			ACell child = entry.getValue();
			AMap<AString, ACell> proj = Maps.empty();
			for (AString field : fields) {
				proj = proj.assoc(field, projectField(child, field, input));
			}
			values = values.assoc(groupKey(entry.getKey()), proj);
		}
		return desc.assoc(K_VALUES, values);
	}

	/** One projected field = a read at the child's subpath, single-read semantics. */
	private static ACell projectField(ACell child, AString field, ACell input) {
		CoviaPath fieldPath = CoviaPath.parse(field.toString());
		ACell fv = ValuePath.get(child, fieldPath);
		if (fv == null) {
			// A stored null is present; an absent subpath is exists:false —
			// decided by key presence, exactly as covia:read decides it.
			boolean present = fieldPath.isEmpty() || ValuePath.contains(child, fieldPath);
			return result(null, present);
		}
		return sizeGuardedResult(fv, input);
	}

	/**
	 * Parses the {@code fields} parameter: a vector of strings (the op form),
	 * the documented array form serialised as a JSON string (some MCP clients
	 * stringify nested arguments, and this schema declares no {@code type} so
	 * the MCP layer cannot coerce it — #379), or a comma-separated string (the
	 * GET form). Blank names are rejected and the count is capped — loudly,
	 * never silently.
	 */
	private static AString[] parseFieldsParam(ACell fieldsCell) {
		java.util.List<AString> out = new java.util.ArrayList<>();
		if (fieldsCell instanceof AString s) {
			String str = s.toString().trim();
			if (str.startsWith("[")) {
				// A JSON-array string — parse it, rather than comma-splitting the
				// brackets and quotes into mangled field names (#379).
				ACell parsed;
				try {
					parsed = JSON.parse(str);
				} catch (Exception e) {
					throw new InvalidInputException("fields looks like a JSON array but is not valid JSON: " + str);
				}
				if (!(parsed instanceof AVector<?> pv)) {
					throw new InvalidInputException("fields must be an array of strings or a comma-separated string");
				}
				addFieldVector(pv, out);
			} else {
				for (String part : str.split(",")) {
					String trimmed = part.trim();
					if (trimmed.isEmpty()) throw new InvalidInputException("fields contains a blank field name");
					out.add(Strings.create(trimmed));
				}
			}
		} else if (fieldsCell instanceof AVector<?> v) {
			addFieldVector(v, out);
		} else {
			throw new InvalidInputException("fields must be a comma-separated string or an array of strings");
		}
		if (out.isEmpty()) throw new InvalidInputException("fields must name at least one subpath");
		if (out.size() > MAX_PROJECT_FIELDS) {
			throw new InvalidInputException("fields names " + out.size()
				+ " subpaths; the maximum is " + MAX_PROJECT_FIELDS);
		}
		return out.toArray(new AString[0]);
	}

	/** Appends each entry of a field-name vector, rejecting non-string or empty names. */
	private static void addFieldVector(AVector<?> v, java.util.List<AString> out) {
		for (long i = 0; i < v.count(); i++) {
			AString f = RT.ensureString((ACell) v.get(i));
			if (f == null || f.isEmpty()) throw new InvalidInputException("fields entries must be non-empty strings");
			out.add(f);
		}
	}

	// ========== covia:aggregate — count entries at a depth, optionally grouped ==========

	private static final AString K_DEPTH    = Strings.intern("depth");
	private static final AString K_GROUP_BY = Strings.intern("groupBy");
	private static final AString K_GROUPS   = Strings.intern("groups");

	/**
	 * Counts the entries reachable by exactly {@code depth} get-steps below a
	 * path, optionally partitioned by a caller-named field. A "step" is one
	 * {@code get} into a container (map/Index value, vector/set element), uniform
	 * across container types. {@code count} is always returned; {@code groupBy}
	 * adds a {@code groups} breakdown keyed by the field's value, each group a
	 * {@code {count}} object (so reductions can be added additively later).
	 *
	 * <p>{@code exists} means a countable collection is present at the path: an
	 * absent path or a scalar (nothing to descend into) → {@code {exists:false}};
	 * an empty or too-deep/ragged collection → {@code {exists:true, count:0}}. No
	 * shape inference — {@code depth} is the only recursion control, and
	 * {@code groupBy} names the field explicitly.</p>
	 *
	 * @return {@code {exists, count}} or {@code {exists, count, groups:{key:{count}}}}
	 */
	@SuppressWarnings("unchecked")
	public ACell handleAggregate(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_READ);
		ACell value = readInputValue(ctx, input);

		// exists = a countable collection is present (a scalar has nothing to
		// descend into, so it is not "there" to count).
		if (!isAggregable(value)) return Maps.of(K_EXISTS, CVMBool.FALSE);

		long depth = 1;
		ACell depthCell = RT.getIn(input, K_DEPTH);
		if (depthCell instanceof CVMLong l) depth = Math.max(0, l.longValue());

		// Expand children `depth` times. A branch shorter than depth contributes
		// nothing (childrenInto of a scalar is empty), so ragged trees are strict.
		java.util.List<ACell> level = new java.util.ArrayList<>();
		level.add(value);
		for (long d = 0; d < depth; d++) {
			java.util.List<ACell> next = new java.util.ArrayList<>();
			for (ACell node : level) childrenInto(node, next);
			level = next;
		}

		long count = level.size();
		AString groupBy = RT.ensureString(RT.getIn(input, K_GROUP_BY));
		if (groupBy == null) {
			return Maps.of(K_EXISTS, CVMBool.TRUE, K_COUNT, CVMLong.create(count));
		}

		// Partition by the named field (which may be a relative path resolved by
		// get into each entry). Σ(group counts) == count by construction.
		CoviaPath fieldPath = CoviaPath.parse(groupBy.toString());
		AMap<AString, ACell> groups = Maps.empty();
		for (ACell entry : level) {
			ACell fieldVal = ValuePath.get(entry, fieldPath);
			AString key = groupKey(fieldVal);
			ACell existing = groups.get(key);
			long g = (existing == null) ? 0 : ((CVMLong) RT.getIn(existing, K_COUNT)).longValue();
			groups = groups.assoc(key, Maps.of(K_COUNT, CVMLong.create(g + 1)));
		}

		return Maps.of(K_EXISTS, CVMBool.TRUE, K_COUNT, CVMLong.create(count), K_GROUPS, groups);
	}

	/** The key-page size for list responses (default 1000, min 1). */
	private static long pageLimit(ACell input) {
		ACell limitCell = RT.getIn(input, Fields.LIMIT);
		return (limitCell instanceof CVMLong l) ? Math.max(1, l.longValue()) : 1000;
	}

	/** The key-page start offset for list responses (default 0). */
	private static long pageOffset(ACell input) {
		ACell offsetCell = RT.getIn(input, Fields.OFFSET);
		return (offsetCell instanceof CVMLong l) ? Math.max(0, l.longValue()) : 0;
	}

	/** A value we can descend into and count: map/Index, vector, or set. */
	private static boolean isAggregable(ACell v) {
		return (v instanceof AMap) || (v instanceof AVector) || (v instanceof ASet);
	}

	/** Appends the child values/elements of one container node to {@code out}. */
	@SuppressWarnings("unchecked")
	private static void childrenInto(ACell node, java.util.List<ACell> out) {
		if (node instanceof AMap<?,?> m) {
			for (var e : ((AMap<ACell, ACell>) m).entrySet()) out.add(e.getValue());
		} else if (node instanceof AVector<?> vec) {
			for (long i = 0; i < vec.count(); i++) out.add(vec.get(i));
		} else if (node instanceof ASet<?> set) {
			for (ACell e : set) out.add(e);
		}
		// scalar / non-container → no children
	}

	/**
	 * JSON-object-safe group key: strings pass through, other values coerce to a
	 * string key, and a missing field groups under {@code "null"} (object keys
	 * must be strings, so the null bucket is stringified — visible and lossless).
	 */
	private static AString groupKey(ACell fieldVal) {
		if (fieldVal == null) return Strings.intern("null");
		if (fieldVal instanceof AString s) return s;
		ACell jk = ALattice.toJSONKey(fieldVal);
		return (jk instanceof AString s) ? s : Strings.create(String.valueOf(jk));
	}

	/**
	 * Builds a structured description of a CVM value for the list response.
	 *
	 * <p>Always includes {@code exists} (true if value is non-null) and
	 * {@code type} (standard Convex type name from {@link Types#get}).
	 * Keyed collections (maps and Indexes — Index is an {@code AMap} subtype)
	 * additionally include {@code keys} (paginated), {@code count} and
	 * {@code offset}; positional collections (vectors, sets) include {@code count}
	 * only — read their elements via {@code covia:slice}.</p>
	 */
	@SuppressWarnings("unchecked")
	private static ACell describeValue(ACell value, ACell input) {
		// Type name from the Convex type system (e.g. "Map", "Vector", "Nil", "Long")
		AString typeName = Types.get(value).toAString();

		if (value == null) return Maps.of(K_EXISTS, CVMBool.FALSE, K_TYPE, typeName);

		AMap<AString, ACell> desc;

		if (value instanceof AMap<?,?> map) {
			AMap<ACell, ACell> m = (AMap<ACell, ACell>) map;
			long total = m.count();

			// Extract all keys, converting to JSON-friendly form
			AVector<ACell> allKeys = Vectors.empty();
			for (var entry : m.entrySet()) {
				allKeys = allKeys.conj(ALattice.toJSONKey(entry.getKey()));
			}

			// Pagination — same window the fields projection uses (page-then-project).
			long limit = pageLimit(input);
			long offset = pageOffset(input);

			AVector<ACell> page;
			if (offset == 0 && limit >= total) {
				page = allKeys;
			} else {
				page = Vectors.empty();
				long end = Math.min(offset + limit, total);
				for (long i = offset; i < end; i++) {
					page = page.conj(allKeys.get(i));
				}
			}

			// Always echo offset for the paginated map case so the caller has a
			// predictable pagination shape (page is K_KEYS; count is the whole).
			desc = Maps.of(K_TYPE, typeName, K_COUNT, CVMLong.create(total),
				K_KEYS, page, Fields.OFFSET, CVMLong.create(offset));
		} else if (value instanceof ASet<?> set) {
			// Sets are positional — type + count only; read elements via covia:slice.
			desc = Maps.of(K_TYPE, typeName, K_COUNT, CVMLong.create(set.count()));
		} else if (value instanceof ACountable<?> countable) {
			// Vectors, sequences, Index — type and count only.
			// Use covia:slice to read elements from vectors.
			desc = Maps.of(K_TYPE, typeName, K_COUNT, CVMLong.create(countable.count()));
		} else {
			// Scalar — type name only
			desc = Maps.of(K_TYPE, typeName);
		}

		return desc.assoc(K_EXISTS, CVMBool.TRUE);
	}

	// ========== Resolution ==========

	private static final AString DID_PREFIX = Strings.intern("did:");

	/**
	 * Where a path a request names ends. The request chooses the component the
	 * path starts at, and that component resolves it:
	 * <ul>
	 *   <li>a path that starts with a DID: the venue;</li>
	 *   <li>{@code n/}: the request's agent;</li>
	 *   <li>{@code c/}: the request's session;</li>
	 *   <li>{@code t/}: the scratch of the request's task or job, with the rest of the path;</li>
	 *   <li>{@code v/}: the venue's user, at {@link #venueGlobals the path it stands for};</li>
	 *   <li>{@code x/}: the adapter's {@link AdapterView view} for the request's user;</li>
	 *   <li>any other path: the request's user.</li>
	 * </ul>
	 * Resolution checks no authority and changes nothing, except that an
	 * {@code x/} view checks its own. It follows the path's shape, not the
	 * data: a path into a user, agent, session or job that does not exist ends
	 * at a position that reads as absent, and a path that names no place data
	 * could be ends at an invalid one ({@link Position#isValid}).
	 *
	 * @param path The path as the caller gave it: a string, with an optional
	 *        leading slash and owner DID, or a vector of segments
	 * @return Where the path ends, never null
	 * @throws WrongScopeException if {@code n/}, {@code c/} or {@code t/} is used
	 *         outside its scope, or a path without an owner DID in a request with no user
	 */
	public Position resolve(RequestContext ctx, ACell path) {
		return resolve(ctx, path, Capability.CRUD_READ);
	}

	/** As {@link #resolve(RequestContext, ACell)}; {@code ability} is for an {@code x/} view's write policy. */
	private Position resolve(RequestContext ctx, ACell path, AString ability) {
		AString did = didPath(path);
		if (did != null) {
			Engine.UserPathTarget target = engine.resolveUserPath(ctx, did, false);
			if (isExtensionPath(target.path())) {
				return AdapterView.resolve(ctx, this, target.ownerDID(), target.path(), ability);
			}
			return engine.getVenueState().resolve(CoviaPath.segments(target.ownerDID()).append(target.path()));
		}
		CoviaPath segments = CoviaPath.of(path);
		AString namespace = segments.isEmpty() ? null : segments.first();
		if (Namespace.X.equals(namespace)) {
			return AdapterView.resolve(ctx, this, (ctx == null) ? null : ctx.getUserDID(), segments, ability);
		}
		if (V.equals(namespace)) {
			return engine.getVenueState().users().at(engine.getDIDString()).resolve(venueGlobals(segments));
		}
		AString agentId = (ctx == null) ? null : ctx.getAgentId();
		if (N.equals(namespace)) {
			if (agentId == null) throw new WrongScopeException(NO_AGENT_SCOPE);
			return requestUser(ctx).agentAt(agentId).resolve(segments);
		}
		if (C.equals(namespace)) {
			Blob sessionId = (ctx == null) ? null : ctx.getSessionId();
			if (agentId == null || sessionId == null) throw new WrongScopeException(NO_SESSION_SCOPE);
			return requestUser(ctx).agentAt(agentId).session(sessionId).resolve(segments);
		}
		if (T.equals(namespace)) {
			Blob jobId = scopedJob(ctx);
			return requestUser(ctx).jobs().scratch(jobId).resolve(segments.rest());
		}
		return requestUser(ctx).resolve(segments);
	}

	/**
	 * Whether {@code path} names a place in the lattice that could hold data,
	 * as {@link #resolve} reads it, without reading it or checking authority:
	 * an {@code x/} view, a context-dependent path outside its scope, or a path
	 * that resolves to a valid position. A malformed path names none.
	 *
	 * @param path The path as the caller gave it, as for {@link #resolve}
	 */
	public boolean isLatticePath(RequestContext ctx, ACell path) {
		try {
			AString did = didPath(path);
			CoviaPath segments = (did == null) ? CoviaPath.of(path) : engine.resolveUserPath(ctx, did, false).path();
			if (isExtensionPath(segments)) return true;
			return resolve(ctx, path).isValid();
		} catch (WrongScopeException e) {
			return true;
		} catch (InvalidInputException e) {
			return false;
		}
	}

	/**
	 * The request's user, whose namespace a bare path names, whether or not it
	 * has a record. The USER, not the acting principal: an agent sub-principal
	 * works inside its owner's namespace and has no lattice record of its own.
	 * This agrees with how bare paths canonicalise in the capability check
	 * (RequestContext.grantsDenial), so a grant of {@code w/foo} and the read of
	 * {@code w/foo} name the same place.
	 *
	 * @throws WrongScopeException if the request has no user
	 */
	private User requestUser(RequestContext ctx) {
		AString did = (ctx == null) ? null : ctx.getUserDID();
		if (did == null) throw new WrongScopeException(NO_USER_SCOPE);
		return engine.getVenueState().users().at(did);
	}

	/**
	 * The job whose scratch {@code t/} names: the focused task, the caller-facing
	 * Job, in preference to the transition or trigger Job a cycle may also carry.
	 */
	private static Blob scopedJob(RequestContext ctx) {
		Blob taskId = (ctx == null) ? null : ctx.getTaskId();
		if (taskId != null && ctx.getAgentId() == null) throw new IllegalStateException(TASK_WITHOUT_AGENT);
		Blob jobId = (taskId != null) ? taskId : (ctx == null) ? null : ctx.getJobId();
		if (jobId == null) throw new WrongScopeException(NO_JOB_SCOPE);
		return jobId;
	}

	/** The path as a string without its leading slash when it starts with an owner DID, otherwise null. */
	private static AString didPath(ACell path) {
		AString raw = RT.ensureString(path);
		if (raw != null && raw.startsWith(SLASH)) raw = raw.slice(1);
		return (raw != null && raw.startsWith(DID_PREFIX)) ? raw : null;
	}

	/** Whether a path starts in a context-dependent namespace: {@code n/}, {@code c/}, {@code t/}, {@code v/} or {@code x/}. */
	private static boolean isContextDependent(CoviaPath path) {
		if (path.isEmpty()) return false;
		AString namespace = path.first();
		return N.equals(namespace) || C.equals(namespace) || T.equals(namespace)
			|| V.equals(namespace) || Namespace.X.equals(namespace);
	}

	/** The venue user's path a {@code v/} path stands for: {@link #VENUE_GLOBALS} followed by the rest of the path. */
	public static CoviaPath venueGlobals(CoviaPath path) {
		return VENUE_GLOBALS.append(path.rest());
	}

	/** Whether a path, after any owner DID, is an adapter's view ({@code x/}). */
	public static boolean isExtensionPath(CoviaPath path) {
		return !path.isEmpty() && Namespace.X.equals(path.first());
	}

}
