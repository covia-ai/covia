package covia.adapter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.ACountable;
import convex.core.data.util.CellExplorer;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
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
import covia.lattice.WorkspaceRoot;
import static covia.lattice.WorkspaceRoot.deepGet;
import static covia.lattice.WorkspaceRoot.deepSet;
import static covia.lattice.WorkspaceRoot.deepDelete;
import static covia.lattice.WorkspaceRoot.deepAppend;
import static covia.lattice.WorkspaceRoot.parentExisted;
import static covia.lattice.WorkspaceRoot.leafExisted;
import static covia.lattice.WorkspaceRoot.leafPresentIn;
import static covia.lattice.WorkspaceRoot.buildSubPath;
import static covia.lattice.WorkspaceRoot.parseStringPath;

import covia.api.Fields;
import covia.lattice.AgentNamespaceResolver;
import covia.lattice.Covia;
import covia.lattice.NamespaceResolver;
import covia.lattice.ExtensionNamespaceResolver;
import covia.lattice.SessionNamespaceResolver;
import covia.lattice.TempNamespaceResolver;
import covia.lattice.VenueGlobalsResolver;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.Users;

/**
 * Adapter for native Covia venue operations.
 *
 * <p>Provides generic lattice read/list access to the authenticated user's
 * namespace. Callers supply a JSON-friendly path (e.g. {@code "g/my-agent/state"})
 * and this adapter navigates the lattice to read or list values.</p>
 *
 * <h3>Key resolution — why we delegate to the lattice</h3>
 *
 * <p>The user's lattice is a tree of nested lattice types. Each level may
 * use a different internal key type: {@code KeyedLattice} uses Keywords
 * ({@code :g}), {@code StringKeyedLattice} uses AString, {@code IndexLattice}
 * uses Blob keys, etc. A JSON caller doesn't know (or care) about these
 * internal types — they just say {@code "g"} or {@code "my-agent"}.</p>
 *
 * <p>{@link ALattice#resolvePath(ACell...)} walks the lattice hierarchy and
 * calls {@link ALattice#resolveKey(ACell)} at each level to translate the
 * JSON-level key into the canonical CVM key for that lattice type. For
 * example, a {@code KeyedLattice} resolves {@code "g"} → {@code :g} via
 * blob-based comparison against its known child keys.</p>
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
 * <p>The {@code w/} and {@code o/} namespaces are user-writable via
 * {@code covia:write} and {@code covia:delete}. Virtual namespaces have their
 * own policies, including opt-in adapter views at {@code x/<adapter>/}.
 * Other stored namespaces ({@code g/}, {@code s/}, {@code j/}) are framework-managed and
 * reject direct writes.</p>
 *
 * <p>All user state lives inside the venue {@code :value}, which merges
 * whole-value by timestamp and re-stamps on every write (see {@link covia.lattice.Covia}).
 * Deletions are therefore durable across the propagator's merge-back — the whole
 * region is replaced by the newer snapshot, not per-key unioned. Writes are a
 * whole-value read-modify-write on the user's value; structural navigation builds
 * intermediates by key shape.</p>
 *
 * <p>Virtual namespaces ({@code n/} for agent workspace, {@code t/} for
 * goal-scoped temp) are resolved by registered {@link NamespaceResolver}
 * implementations that map to the appropriate lattice location based on
 * the {@link RequestContext}.</p>
 *
 * <p>The {@code n/} prefix is agent-scoped shorthand: when the
 * {@link RequestContext} carries an agentId (set by the harness during agent
 * execution), {@code n/foo} is rewritten to {@code g/{agentId}/n/foo},
 * providing agents with a private read/write workspace within their record.</p>
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

	private static final AString K_KEYS    = Strings.intern("keys");
	private static final AString K_COUNT = Strings.intern("count");
	private static final AString K_TYPE    = Strings.intern("type");
	private static final AString K_VALUE   = Strings.intern("value");
	private static final AString K_VALUES  = Strings.intern("values");
	private static final AString K_EXISTS  = Strings.intern("exists");
	private static final AString K_PATH_CREATED = Strings.intern("pathCreated");
	private static final AString K_NEW_SIZE     = Strings.intern("newSize");
	// Mutation outcome fields (#147): existed = a value was already at the target
	// before the op (created vs replaced); deleted = a value was removed; index =
	// where an appended element landed.
	private static final AString K_EXISTED = Strings.intern("existed");
	private static final AString K_DELETED = Strings.intern("deleted");
	private static final AString K_INDEX   = Strings.intern("index");
	private static final AString K_VALUE_BYTES  = Strings.intern("valueBytes");
	private static final AString K_TRUNCATED = Strings.intern("truncated");
	private static final AString K_MAX_SIZE  = Strings.intern("maxSize");
	private static final AString K_FIELDS    = Strings.intern("fields");

	/** Cap on projected field count per list call (#191) — exceeding it is an
	 *  explicit error, never a silent truncation. */
	private static final int MAX_PROJECT_FIELDS = 16;

	/** Default max memory size (bytes) for covia:read responses. ~1 MB. */
	private static final long DEFAULT_MAX_SIZE = 1_000_000;

	/** Namespaces that accept user writes via covia:write and covia:delete. */
	private static final Set<String> WRITABLE_NAMESPACES = Set.of("w", "o");

	/** Registered virtual namespace resolvers, keyed by prefix. */
	private final Map<String, NamespaceResolver> resolvers = new HashMap<>();

	@Override
	public String getName() {
		return "covia";
	}

	@Override
	public String getDescription() {
		return "Provides native access to internal services in this Covia venue, "
			+ "including lattice read/write/list/delete for user data.";
	}

	/**
	 * Registers a virtual namespace resolver for the given prefix.
	 */
	public void registerResolver(String prefix, NamespaceResolver resolver) {
		resolvers.put(prefix, resolver);
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

		// Register built-in virtual namespace resolvers
		registerResolver("n", new AgentNamespaceResolver());
		registerResolver("c", new SessionNamespaceResolver());
		registerResolver("t", new TempNamespaceResolver());
		registerResolver("v", new VenueGlobalsResolver(this));
		registerResolver("x", new ExtensionNamespaceResolver());
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// Authentication required: every covia:* op runs in a user namespace.
		// Engine-startup code that needs venue-authority writes uses
		// engine.venueContext() which has the venue's own DID as caller.
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new RuntimeException("Authentication required"));
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
					new RuntimeException("Unknown covia operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Reads the value at a path in the user's lattice.
	 *
	 * <p>The path is parsed into JSON-level keys, then resolved to canonical
	 * CVM keys by walking the lattice hierarchy. Each lattice level translates
	 * the key appropriate to its type (e.g. {@code "g"} → {@code :g} in a
	 * KeyedLattice). The resolved keys are then used to navigate the cursor
	 * and read the value.</p>
	 *
	 * <p>Response always includes {@code exists} (boolean) and {@code value}:</p>
	 * <ul>
	 *   <li>{@code {exists: true, value: <value>}} — path has data</li>
	 *   <li>{@code {exists: false, value: null}} — path is valid in the lattice
	 *       schema but has no data, OR the path is structurally invalid (e.g.
	 *       unrecognised key at a KeyedLattice level), OR the user has no state</li>
	 * </ul>
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
			ACell value = engine.resolvePath(pathStr, ctx);
			if (value != null) {
				return sizeGuardedResult(value, input);
			}
		}

		// Fall back to local resolution: a stored null, or a vector path form.
		var target = resolveTargetPath(ctx, input);
		WorkspaceRoot cursor = target.cursor();
		ACell[] pathKeys = target.remainingKeys();

		if (cursor == null) return result(null);

		ACell value = cursor.read(pathKeys);
		// A stored null reads back as present (exists:true, value:null), distinct
		// from an absent path (exists:false) — decided by key presence in the parent.
		if (value == null) {
			return result(null, cursor.contains(pathKeys));
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
		ACell[] pathKeys = parseStringPath(path.toString());
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
		if (vns != null) return vns.cursor().read(vns.remainingKeys());

		// Physical namespaces and universal asset/op references.
		return engine.resolvePath(path, ctx);
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
			throw new IllegalArgumentException(
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
			throw new IllegalArgumentException("Provide exactly one of 'path' or 'paths'");
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
			if (path == null) throw new IllegalArgumentException("'path' must be a string");
			String rendered = explorePath(ctx, path.toString(), budget, compact);
			return Maps.of(K_RESULT, Strings.create(rendered));
		}

		// Multiple paths
		if (!(pathsCell instanceof AVector<?> pathsVec) || pathsVec.isEmpty()) {
			throw new IllegalArgumentException("'paths' must be a non-empty array of strings");
		}
		int perPath = Math.max(10, budget / (int) pathsVec.count());
		AMap<AString, ACell> results = Maps.empty();
		for (long i = 0; i < pathsVec.count(); i++) {
			AString path = RT.ensureString(pathsVec.get(i));
			if (path == null) throw new IllegalArgumentException("'paths' must contain only strings");
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
		var target = resolvePathTarget(ctx, Strings.create(pathStr), Capability.CRUD_READ);
		WorkspaceRoot cursor = target.cursor();
		ACell[] keys = target.remainingKeys();
		if (cursor == null) return "null /* no user data */";

		ACell value = cursor.read(keys);
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
			throw new RuntimeException(
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
		if (from == null) throw new IllegalArgumentException("'from' is required");
		if (to == null) throw new IllegalArgumentException("'to' is required");

		// Reads the source (handleWrite enforces crud/write on the destination).
		engine.requireResourceAccess(ctx, from, Capability.CRUD_READ);

		// Read from source via the canonical universal resolver. Caps
		// enforcement happens inside resolvePath via the cursor it returns.
		ACell value = engine.resolvePath(from, ctx);
		if (value == null) {
			throw new RuntimeException("source not found: " + from);
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
		// (or public/venue-catalog policy), else a denial. Extension cursors also
		// retain this authority check when accessing their adapter-provided view.
		ACell path = RT.getIn(input, Fields.PATH);
		AString resource = RT.ensureString(path);
		if (path instanceof AVector<?>) resource = Strings.create(buildSubPath(parsePath(path)));
		if (resource != null && resource.startsWith(Strings.create("/"))) resource = resource.slice(1);
		engine.requireLocalAccess(ctx, resource, ability);
	}

	/**
	 * Writes a value at a path in the user's lattice.
	 *
	 * <p>Physical writable namespaces are {@code w/} and {@code o/}; virtual
	 * resolvers enforce their own policies, including opt-in {@code x/} views.
	 * Deep paths create intermediate maps as needed within one atomic update.</p>
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
			throw new RuntimeException(
				"Write requires a 'value' field (received path only). Provide the value to "
				+ "store (an explicit null stores a null value); to remove an entry use covia:delete.");
		}
		// The value is stored exactly as supplied. A string is a valid value, so
		// a JSON-looking string stays a string: callers wanting structure send
		// structure (the tool descriptions say so), and string-to-object parsing
		// lives only at tool-call boundaries whose schema admits nothing else.
		ACell value = RT.getIn(input, Fields.VALUE);

		var target = resolvePathTarget(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_WRITE);
		WorkspaceRoot baseCursor = target.cursor();
		final ACell[] keys = target.remainingKeys();
		final boolean[] built = {false};
		final boolean[] existed = {false};
		// deepSet first so a shape conflict throws its descriptive error (#146);
		// pathCreated is read from the pre-state and means "a missing parent
		// container had to be built" — not whether the leaf already had a value
		// (that is `existed`).
		baseCursor.update(keys, target.requireExistingRecord(),
			(current, from) -> {
			existed[0] = leafExisted(current, keys, from);
			ACell next = deepSet(current, keys, from, value);
			// pathCreated reports building a nested parent below the namespace;
			// creating the namespace + its direct child (keys.length <= 2) is
			// not "hierarchy" — so only paths deeper than that can report it.
			built[0] = keys.length > 2 && !parentExisted(current, keys, from);
			return next;
		});
		return writeResult(existed[0], built[0]);
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
				new IllegalStateException("Remote output path requires the grid adapter"));
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
	 * Mutation result shared by {@code covia:write} and {@code covia:copy}:
	 * {@code existed} (a value was already at the target — created vs replaced) is
	 * always present; {@code pathCreated} appears only when a missing parent
	 * hierarchy had to be built.
	 */
	private static ACell writeResult(boolean existed, boolean built) {
		AMap<AString, ACell> out = Maps.of(K_EXISTED, CVMBool.of(existed));
		return built ? out.assoc(K_PATH_CREATED, CVMBool.TRUE) : out;
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
		var target = resolvePathTarget(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_DELETE);
		WorkspaceRoot cursor = target.cursor();
		if (cursor == null) return Maps.of(K_DELETED, CVMBool.FALSE);
		final ACell[] keys = target.remainingKeys();
		boolean requireExistingRecord = target.requireExistingRecord();
		// A missing unscoped target linearises as an immediate no-op. Avoid
		// descending through a PathCursor merely to write null back: that would
		// materialise an absent wrapped namespace even though nothing was deleted.
		if (!requireExistingRecord && !cursor.contains(keys)) {
			return Maps.of(K_DELETED, CVMBool.FALSE);
		}
		final boolean[] deleted = {false};
		cursor.update(keys, requireExistingRecord,
			(current, from) -> {
			deleted[0] = leafExisted(current, keys, from);
			return deepDelete(current, keys, from);
		});
		if (cursor.userNamespaces() && deleted[0] && keys.length == 2 && NS_SECRETS.equals(keys[0].toString())) {
			// The name only, never the value — the same trail as secret:set.
			engine.audit().event(covia.venue.Audit.SECRET_DELETE, covia.venue.Audit.K_DID, ctx.getCallerDID(),
				covia.venue.Audit.K_NAME, keys[1], covia.venue.Audit.K_TARGET, ctx.getUserDID());
		}
		return Maps.of(K_DELETED, CVMBool.of(deleted[0]));
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
		var target = resolvePathTarget(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_WRITE);
		ACell element = RT.getIn(input, Fields.VALUE);
		WorkspaceRoot baseCursor = target.cursor();
		final ACell[] keys = target.remainingKeys();
		final boolean[] built = {false};
		final boolean[] existed = {false};
		final long[] newSize = {0};
		baseCursor.update(keys, target.requireExistingRecord(),
			(current, from) -> {
			existed[0] = leafExisted(current, keys, from);
			ACell next = deepAppend(current, keys, from, element);
			built[0] = keys.length > 2 && !parentExisted(current, keys, from);
			ACell leaf = deepGet(next, keys, from);
			newSize[0] = (leaf instanceof AVector) ? ((AVector<?>) leaf).count() : 0;
			return next;
		});
		return appendResult(existed[0], newSize[0], built[0]);
	}

	/**
	 * Result of {@code covia:append}: {@code existed} (the collection was already
	 * there — extended vs freshly created), {@code index} (the position the element
	 * landed = {@code newSize - 1}), and {@code newSize} (the resulting element
	 * count) are always present; {@code pathCreated} appears only when a missing
	 * parent hierarchy was built.
	 */
	private static ACell appendResult(boolean existed, long newSize, boolean built) {
		AMap<AString, ACell> out = Maps.of(
			K_EXISTED, CVMBool.of(existed),
			K_INDEX, CVMLong.create(Math.max(0, newSize - 1)),
			K_NEW_SIZE, CVMLong.create(newSize));
		return built ? out.assoc(K_PATH_CREATED, CVMBool.TRUE) : out;
	}

	// ========== Write path helpers ==========

	/**
	 * Validates that a parsed path targets a writable namespace.
	 *
	 * @throws RuntimeException if path is too short or targets a non-writable namespace
	 */
	private static void validateWritablePath(ACell[] jsonKeys) {
		if (jsonKeys.length < 2) {
			throw new RuntimeException(
				"Path must include namespace and key, e.g. 'w/my-key'");
		}
		String namespace = jsonKeys[0].toString();
		if (!WRITABLE_NAMESPACES.contains(namespace)) {
			throw new RuntimeException(
				"Namespace '" + namespace + "' is not writable (framework-managed). "
				+ "Writable namespaces: w/ (workspace), o/ (operation pins); "
				+ "n/ and t/ are writable within an agent or job run.");
		}
	}

	/**
	 * Checks that the caller has write access to the namespace addressed by
	 * {@code jsonKeys}. Virtual namespaces with a registered {@link NamespaceResolver}
	 * (e.g. {@code v/}, {@code n/}, {@code t/}) delegate auth to
	 * {@link NamespaceResolver#canWrite(RequestContext)}; plain namespaces fall
	 * back to the {@link #WRITABLE_NAMESPACES} allowlist ({@code w/}, {@code o/}).
	 *
	 * <p>Calling this before {@link #validateWritablePath} ensures that resolver-
	 * managed namespaces (like {@code v/}) aren't blocked by the static allowlist
	 * before their per-caller auth check can run.</p>
	 *
	 * @throws RuntimeException if the caller lacks write access
	 */
	private void requireWriteAccess(RequestContext ctx, ACell[] jsonKeys) {
		if (jsonKeys.length > 0) {
			NamespaceResolver resolver = resolvers.get(jsonKeys[0].toString());
			if (resolver != null) {
				if (!resolver.canWrite(ctx)) {
					throw new RuntimeException("Write permission denied for namespace: " + jsonKeys[0]);
				}
				return;
			}
		}
		if (!isWritableNamespace(jsonKeys)) validateWritablePath(jsonKeys);
	}

	/** The secrets namespace prefix — delete-only via covia:delete (#166). */
	private static final String NS_SECRETS = "s";

	/**
	 * Delete-side counterpart of {@link #requireWriteAccess}. Accepts everything
	 * the write side accepts, plus {@code s/} (secrets), which is delete-only:
	 * secret records are created exclusively through {@code secret:set} (which
	 * encrypts), so {@code covia:write}/{@code covia:append} keep rejecting
	 * {@code s/} — but a caller may remove their own secret. A secret delete
	 * must address a whole record ({@code s/&lt;name&gt;}): partial deletion
	 * inside an {@code {encrypted, updated}} record is never meaningful and
	 * would corrupt its shape. The capability side is pinned by the caller
	 * ({@code crud/delete} on the exact path via {@link #requireCap}).
	 *
	 * @throws RuntimeException if the caller lacks delete access
	 */
	private void requireDeleteAccess(RequestContext ctx, ACell[] jsonKeys) {
		if (jsonKeys.length > 0 && NS_SECRETS.equals(jsonKeys[0].toString())
				&& resolvers.get(NS_SECRETS) == null) {
			if (jsonKeys.length != 2) {
				throw new RuntimeException(
					"Secret deletion must address a whole record, e.g. 's/MY_KEY'");
			}
			return;
		}
		requireWriteAccess(ctx, jsonKeys);
	}

	/**
	 * Reads the literal value targeted by an accessor input.
	 */
	private ACell readInputValue(RequestContext ctx, ACell input) {
		var target = resolveTargetPath(ctx, input);
		WorkspaceRoot cursor = target.cursor();
		if (cursor == null) return null;
		return cursor.read(target.remainingKeys());
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
			throw new IllegalArgumentException(
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
		ACell[] fieldKeys = parseStringPath(field.toString());
		ACell fv = (fieldKeys.length == 0) ? child : deepGet(child, fieldKeys, 0);
		if (fv == null) {
			// A stored null is present; an absent subpath is exists:false —
			// decided by key presence, exactly as covia:read decides it.
			boolean present = (fieldKeys.length == 0) || leafPresentIn(child, fieldKeys);
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
					throw new IllegalArgumentException("fields looks like a JSON array but is not valid JSON: " + str);
				}
				if (!(parsed instanceof AVector<?> pv)) {
					throw new IllegalArgumentException("fields must be an array of strings or a comma-separated string");
				}
				addFieldVector(pv, out);
			} else {
				for (String part : str.split(",")) {
					String trimmed = part.trim();
					if (trimmed.isEmpty()) throw new IllegalArgumentException("fields contains a blank field name");
					out.add(Strings.create(trimmed));
				}
			}
		} else if (fieldsCell instanceof AVector<?> v) {
			addFieldVector(v, out);
		} else {
			throw new IllegalArgumentException("fields must be a comma-separated string or an array of strings");
		}
		if (out.isEmpty()) throw new IllegalArgumentException("fields must name at least one subpath");
		if (out.size() > MAX_PROJECT_FIELDS) {
			throw new IllegalArgumentException("fields names " + out.size()
				+ " subpaths; the maximum is " + MAX_PROJECT_FIELDS);
		}
		return out.toArray(new AString[0]);
	}

	/** Appends each entry of a field-name vector, rejecting non-string or empty names. */
	private static void addFieldVector(AVector<?> v, java.util.List<AString> out) {
		for (long i = 0; i < v.count(); i++) {
			AString f = RT.ensureString((ACell) v.get(i));
			if (f == null || f.isEmpty()) throw new IllegalArgumentException("fields entries must be non-empty strings");
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
		ACell[] fieldKeys = parseStringPath(groupBy.toString());
		AMap<AString, ACell> groups = Maps.empty();
		for (ACell entry : level) {
			ACell fieldVal = (fieldKeys.length == 0) ? entry : deepGet(entry, fieldKeys, 0);
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

	// ========== Path parsing ==========

	/**
	 * Parses a path input into an array of JSON-level keys for lattice resolution.
	 *
	 * <p>Accepts two forms:</p>
	 * <ul>
	 *   <li><b>String:</b> {@code "g/my-agent/timeline/3"} — split on {@code /},
	 *       all segments become AString. The lattice's {@code resolveKey} at each
	 *       level handles type translation (string → keyword, string → blob, etc.)</li>
	 *   <li><b>Vector:</b> {@code ["g", "my-agent", "timeline", 3]} — elements
	 *       used directly, allowing callers to pass pre-typed keys (e.g. integers
	 *       for vector indexing)</li>
	 * </ul>
	 *
	 * <p>Important: these are <em>JSON-level</em> keys, not canonical CVM keys.
	 * They must be passed through {@link ALattice#resolvePath} before being used
	 * for cursor navigation.</p>
	 */
	static ACell[] parsePath(ACell pathCell) {
		if (pathCell == null) return new ACell[0];

		// Vector path: elements used as-is (caller controls types)
		if (pathCell instanceof AVector<?> vec) {
			ACell[] keys = new ACell[Math.toIntExact(vec.count())];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = vec.get(i);
			}
			return keys;
		}

		// String path: split on "/" into AString segments
		AString pathStr = RT.ensureString(pathCell);
		if (pathStr == null) return new ACell[0];
		return parseStringPath(pathStr.toString());
	}

	// ========== Helpers ==========

	/**
	 * Gets the lattice cursor for the authenticated user's namespace root.
	 * Returns null if the user has no lattice state.
	 */
	private WorkspaceRoot getWorkspaceRoot(RequestContext ctx) {
		Users users = engine.getVenueState().users();
		// The USER's record, not the acting principal's: an agent sub-principal
		// works inside its owner's namespace and has no lattice record of its own.
		// This must agree with how bare paths canonicalise in the capability check
		// (RequestContext.grantsDenial), or an agent would be granted w/foo in one
		// namespace and read it from another.
		User user = users.get(ctx.getUserDID());
		if (user == null) return null;
		return user.workspaceRoot();
	}

	/**
	 * Gets the lattice cursor for the authenticated user (caller), creating
	 * the user namespace if it does not yet exist. Used by write operations
	 * that need to store data for a first-time user.
	 */
	public User ensureUser(RequestContext ctx) {
		return engine.getVenueState().users().ensure(ctx.getUserDID());
	}

	public WorkspaceRoot ensureWorkspaceRoot(RequestContext ctx) {
		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getUserDID());
		return user.workspaceRoot();
	}

	/**
	 * Gets the lattice cursor for a specific user identified by DID, creating
	 * the user namespace if it does not yet exist. Parallel to
	 * {@link #ensureWorkspaceRoot(RequestContext)} but targets a named user
	 * rather than the calling user. Used by venue-scoped virtual namespace
	 * resolvers (e.g. {@code VenueGlobalsResolver} for {@code /v/}) that
	 * need to navigate to a fixed user's data regardless of the caller.
	 *
	 * @param did the DID of the user whose cursor to return
	 * @return the lattice cursor positioned at that user's namespace root
	 */
	public WorkspaceRoot ensureWorkspaceRoot(AString did) {
		Users users = engine.getVenueState().users();
		User user = users.ensure(did);
		return user.workspaceRoot();
	}

	/** All standard accessors share this physical/virtual/DID target resolution. */
	private NamespaceResolver.ResolvedNamespace resolveTargetPath(RequestContext ctx, ACell input) {
		return resolvePathTarget(ctx, RT.getIn(input, Fields.PATH), Capability.CRUD_READ);
	}

	private NamespaceResolver.ResolvedNamespace resolvePathTarget(RequestContext ctx, ACell path, AString ability) {
		boolean write = Capability.CRUD_WRITE.equals(ability);
		boolean delete = Capability.CRUD_DELETE.equals(ability);
		AString raw = RT.ensureString(path);
		if (raw != null && raw.startsWith(Strings.create("/"))) raw = raw.slice(1);
		var didTarget = raw != null && raw.toString().startsWith("did:")
			? engine.resolveUserPath(ctx, raw, false) : null;
		ACell[] keys = didTarget == null ? parsePath(path) : didTarget.pathKeys();
		if (didTarget != null && isExtensionPath(keys)) {
			return ExtensionNamespaceResolver.resolveForUser(ctx, this, didTarget.ownerDID(), keys, ability);
		}
		if (write) requireWriteAccess(ctx, keys);
		if (delete) requireDeleteAccess(ctx, keys);
		if (didTarget == null) {
			var virtual = resolveVirtual(ctx, keys, ability);
			if (virtual != null) return virtual;
			return new NamespaceResolver.ResolvedNamespace(write ? ensureWorkspaceRoot(ctx) : getWorkspaceRoot(ctx), keys, false);
		}
		if (write) didTarget = engine.resolveUserPath(ctx, raw, true);
		User user = didTarget.user();
		return new NamespaceResolver.ResolvedNamespace(user == null ? null : user.workspaceRoot(), keys, false);
	}


	/**
	 * Resolves a parsed path, checking virtual namespace resolvers first,
	 * then falling through to the standard user lattice cursor.
	 *
	 * @return For virtual namespaces: ResolvedNamespace with cursor at namespace root.
	 *         For physical namespaces: null (caller uses standard user cursor flow).
	 */
	public NamespaceResolver.ResolvedNamespace resolveVirtual(RequestContext ctx, ACell[] pathKeys) {
		return resolveVirtual(ctx, pathKeys, Capability.CRUD_READ);
	}

	public NamespaceResolver.ResolvedNamespace resolveVirtual(RequestContext ctx, ACell[] pathKeys, AString ability) {
		if (pathKeys.length > 0) {
			String prefix = pathKeys[0].toString();
			NamespaceResolver resolver = resolvers.get(prefix);
			if (resolver != null) {
				return resolver.resolve(ctx, this, pathKeys, ability);
			}
		}
		return null;
	}

	/**
	 * Reads the value at a virtual-namespace path (e.g. {@code n/notes},
	 * {@code v/ops/json/merge}). Returns the literal value at the resolved
	 * cursor, or {@code null} if the path doesn't match a registered
	 * virtual prefix or is unresolvable.
	 *
	 * <p>This is the entry point used by {@link covia.venue.Engine#resolvePath}
	 * to handle virtual namespaces uniformly.</p>
	 */
	public ACell readVirtualNamespace(RequestContext ctx, AString path) {
		if (path == null) return null;
		ACell[] pathKeys = parseStringPath(path.toString());
		if (pathKeys.length == 0) return null;

		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
		if (vns == null) return null;
		return vns.cursor().read(vns.remainingKeys());
	}

	/**
	 * Checks whether a parsed path targets a writable namespace — either a
	 * physical writable namespace or a writable virtual namespace resolver.
	 */
	boolean isWritableNamespace(ACell[] pathKeys) {
		if (pathKeys.length < 2) return false;
		String ns = pathKeys[0].toString();
		if (WRITABLE_NAMESPACES.contains(ns)) return true;
		NamespaceResolver resolver = resolvers.get(ns);
		return resolver != null && resolver.isWritable();
	}

	public static boolean isExtensionPath(ACell[] keys) {
		return keys.length > 0 && Strings.intern("x").equals(keys[0]);
	}

	/** DID-qualified extension reads share the same view and authority checks as bare x paths. */
	public ACell readExtensionNamespace(RequestContext ctx, AString owner, ACell[] keys) {
		var resolved = ExtensionNamespaceResolver.resolveForUser(ctx, this, owner, keys, Capability.CRUD_READ);
		return resolved.cursor().read(resolved.remainingKeys());
	}

}
