package covia.adapter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import convex.auth.did.DIDURL;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.data.ACell;
import convex.core.data.ACountable;
import convex.core.data.util.CellExplorer;
import convex.core.data.ABlobLike;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.cursor.ALatticeCursor;
import covia.api.Fields;
import covia.grid.Asset;
import covia.lattice.AgentNamespaceResolver;
import covia.lattice.CapabilityChecker;
import covia.lattice.NamespaceResolver;
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
 *   user-root  (plain navigable JSON under the venue :state region)
 *   ├── g/          agents     (per-agent records, Blob-keyed Index)
 *   ├── s/          secrets    (encrypted values)
 *   ├── j/          jobs       (job records by Blob ID, sorted Index)
 *   ├── w/          workspace  (user-writable general-purpose data)
 *   └── o/          operations (user-writable operation definitions)
 * </pre>
 *
 * <p>The {@code w/} and {@code o/} namespaces are user-writable via
 * {@code covia:write} and {@code covia:delete}. All other namespaces
 * ({@code g/}, {@code s/}, {@code j/}) are framework-managed and
 * reject direct writes.</p>
 *
 * <p>All user state lives under the venue {@code :state} region, which merges
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

	/**
	 * Namespaces stored as a {@code {updated, data}} container. This is a storage
	 * shape only (NOT a lattice) — deletion durability comes from the venue-value
	 * whole-value LWW, not from this wrapper. Paths key transparently into
	 * {@code data}; {@code updated} is plain per-namespace last-modified metadata
	 * (real wall-clock, never {@code +1}), and other metadata may live alongside
	 * it later. Kept partly for backward compatibility with venues persisted while
	 * these were {@code LWWWrapperLattice}.
	 */
	private static final Set<String> WRAPPED_NAMESPACES = Set.of("w", "o", "h");

	/** Wrapper metadata key: per-namespace last-modified wall-clock time. */
	private static final AString K_UPDATED = Strings.intern("updated");
	/** Wrapper payload key: the user-visible namespace data. */
	private static final AString K_DATA = Strings.intern("data");

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
		// Check job-scoped virtual namespace (t/) first — these require
		// specialised cursor navigation that Engine.resolvePath doesn't handle.
		ACell pathCell = RT.getIn(input, Fields.PATH);
		if (pathCell != null) {
			ACell[] pathKeys = parsePath(pathCell);
			NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
			if (vns != null && (vns.jobId() != null || vns.sessionId() != null)) {
				ACell temp = scopedRoot(vns);
				ACell[] rem = vns.remainingKeys();
				ACell value = (rem.length == 0) ? temp : deepGet(temp, rem, 0);
				// A stored null is a present value, not absent — decide by key presence.
				if (value == null) {
					boolean present = (rem.length == 0) ? (temp != null) : leafPresentIn(temp, rem);
					return result(null, present);
				}
				return sizeGuardedResult(value, input);
			}
		}

		// Try universal resolution via Engine.resolvePath. This covers every
		// input form per OPERATIONS.md §4: bare hex hash, /a/<hash>, /o/<name>,
		// local DID URL, and workspace paths (w/, g/, o/, j/, etc.). Returns
		// the literal value at the resolved location with no ref-following or
		// asset interpretation.
		AString pathStr = RT.ensureString(pathCell);
		if (pathStr != null) {
			ACell value = engine.resolvePath(pathStr, ctx);
			if (value != null) {
				return sizeGuardedResult(value, input);
			}
		}

		// Fall back to local resolution for cases not handled by resolvePath
		// (e.g. n/ virtual namespace requires the agent context).
		Object[] target = resolveTargetPath(ctx, input);
		ALatticeCursor<ACell> cursor = (ALatticeCursor<ACell>) target[0];
		ACell[] pathKeys = (ACell[]) target[1];

		if (cursor == null) return result(null);
		if (pathKeys.length == 0) return sizeGuardedResult(cursor.get(), input);

		ACell value = readPath(cursor, pathKeys);
		// A stored null reads back as present (exists:true, value:null), distinct
		// from an absent path (exists:false) — decided by key presence in the parent.
		if (value == null) {
			return result(null, pathPresent(cursor, pathKeys));
		}
		return sizeGuardedResult(value, input);
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
	@SuppressWarnings("unchecked")
	public ACell handleInspect(RequestContext ctx, ACell input) {
		// Accept the uniform single-path `path` param (values API) as well as the
		// original multi-target `paths`; `paths` wins when both are present.
		ACell pathsCell = RT.getIn(input, K_PATHS);
		if (pathsCell == null) pathsCell = RT.getIn(input, Fields.PATH);

		// Budget
		int budget = DEFAULT_INSPECT_BUDGET;
		ACell budgetCell = RT.getIn(input, K_BUDGET);
		if (budgetCell instanceof CVMLong l) budget = (int) Math.max(10, l.longValue());

		// Compact (default true)
		boolean compact = true;
		ACell compactCell = RT.getIn(input, K_COMPACT);
		if (CVMBool.FALSE.equals(compactCell)) compact = false;

		// Single path (string)
		if (pathsCell instanceof AString) {
			String rendered = explorePath(ctx, pathsCell.toString(), budget, compact);
			return Maps.of(K_RESULT, Strings.create(rendered));
		}

		// Multiple paths (vector)
		if (pathsCell instanceof AVector<?> pathsVec) {
			int perPath = Math.max(10, budget / (int) Math.max(1, pathsVec.count()));
			AMap<AString, ACell> results = Maps.empty();
			for (long i = 0; i < pathsVec.count(); i++) {
				ACell p = pathsVec.get(i);
				String pathStr = (p instanceof AString s) ? s.toString() : p.toString();
				String rendered = explorePath(ctx, pathStr, perPath, compact);
				results = results.assoc(Strings.create(pathStr), Strings.create(rendered));
			}
			return Maps.of(K_RESULT, results);
		}

		throw new RuntimeException("'paths' must be a string or array of strings");
	}

	/**
	 * Resolves a single path and renders the value via CellExplorer.
	 */
	private String explorePath(RequestContext ctx, String pathStr, int budget, boolean compact) {
		engine.requireResourceAccess(ctx, Strings.create(pathStr), Capability.CRUD_READ);
		ACell[] pathKeys = parseStringPath(pathStr);

		// Check atomic embedded virtual namespaces (t/ Job temp, c/ session state).
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
		if (vns != null && (vns.jobId() != null || vns.sessionId() != null)) {
			ACell temp = scopedRoot(vns);
			ACell value = (vns.remainingKeys().length == 0) ? temp
				: deepGet(temp, vns.remainingKeys(), 0);
			if (value == null) return "null /* not found: " + pathStr + " */";
			return new CellExplorer(budget, compact).explore(value).toString();
		}

		// Cursor-based virtual (n/) or physical namespace
		ALatticeCursor<ACell> cursor;
		ACell[] keys;
		if (vns != null) {
			cursor = vns.cursor();
			keys = vns.remainingKeys();
		} else {
			cursor = getUserCursor(ctx);
			keys = pathKeys;
		}
		if (cursor == null) return "null /* no user data */";

		ACell value;
		if (keys.length == 0) {
			value = cursor.get();
		} else {
			value = readPath(cursor, keys);
		}

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

	/**
	 * True if the leaf key of {@code keys} is present in its parent under the
	 * cursor's (namespace-unwrapped) value — so a stored null value (key present,
	 * value nil) is distinguished from an absent path. {@code keys.length == 0}
	 * means the whole namespace, present iff its value is non-null.
	 */
	private static boolean pathPresent(ALatticeCursor<ACell> cursor, ACell[] keys) {
		if (keys.length == 0) return cursor.get() != null;
		ACell parent = readPath(cursor, java.util.Arrays.copyOf(keys, keys.length - 1));
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/** As {@link #pathPresent} but against a plain root value (job-scoped {@code t/}). */
	private static boolean leafPresentIn(ACell root, ACell[] keys) {
		if (keys.length == 0) return root != null;
		ACell parent = deepGet(root, java.util.Arrays.copyOf(keys, keys.length - 1), 0);
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/**
	 * True if {@code parent} contains {@code key} — a map/Index key or a vector
	 * index — independent of whether the value stored there is null. Mirrors the
	 * key resolution of {@link #navigateInto}.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static boolean containsLeaf(ACell parent, ACell key) {
		if (parent instanceof AVector<?> vec) {
			long idx = parseIndex(key);
			return idx >= 0 && idx < vec.count();
		}
		if (parent instanceof Index) {
			Index idx = (Index) parent;
			if (key instanceof ABlobLike && idx.containsKey((ACell) key)) return true;
			if (key instanceof AString s) {
				Blob b = Blob.parse(s.toString());
				return b != null && idx.containsKey(b);
			}
			return false;
		}
		if (parent instanceof AMap<?,?> map) {
			AString sk = RT.ensureString(key);
			return sk != null && ((AMap<ACell, ACell>) map).containsKey(sk);
		}
		return false;
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
		// (or public/venue-catalog policy), else a denial. resolveDIDURL then only
		// *locates* the owner's cursor — it does not re-authorise.
		engine.requireLocalAccess(ctx, RT.ensureString(RT.getIn(input, Fields.PATH)), ability);
	}

	/**
	 * Writes a value at a path in the user's lattice.
	 *
	 * <p>Only the {@code w/} (workspace) and {@code o/} (operations) namespaces
	 * accept writes. The path must have at least two segments: namespace and
	 * top-level key (e.g. {@code "w/my-key"}). Deeper paths are supported
	 * (e.g. {@code "w/data/nested/field"}) — intermediate maps are created
	 * as needed via read-modify-write on the top-level entry.</p>
	 *
	 * @return {@code {existed, pathCreated?}} — {@code existed:false} created a new
	 *         value, {@code true} replaced an existing one; {@code pathCreated:true}
	 *         is added when a missing parent hierarchy had to be built
	 * @throws RuntimeException if the path is invalid or targets a non-writable namespace
	 */
	private ACell handleWrite(RequestContext ctx, ACell input) {
		requireCap(ctx, input, Capability.CRUD_WRITE);
		// A DID-URL path writes to the owner's namespace (authorised in requireCap
		// above); a bare path to the caller's own. When present, xu = [ownerCursor,
		// DID-relative keys] — never a virtual ns.
		Object[] xu = didUrlMutationTarget(ctx, RT.getIn(input, Fields.PATH), true);
		ACell[] jsonKeys = (xu != null) ? (ACell[]) xu[1] : parsePath(RT.getIn(input, Fields.PATH));
		requireWriteAccess(ctx, jsonKeys);
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
		ACell value = parseJsonValue(RT.getIn(input, Fields.VALUE));

		// Job/task-scoped t/: the Job temp value is the navigation root,
		// so keys are already relative to it. Route through the SAME deepSet as
		// the physical path — a scalar-clobbering write now throws the same
		// shape-conflict error instead of silently rebuilding an empty map (#176).
		NamespaceResolver.ResolvedNamespace vns = (xu != null) ? null : resolveVirtual(ctx, jsonKeys);
		if (vns != null && vns.jobId() != null) {
			final ACell[] keys = vns.remainingKeys();
			final boolean[] built = {false};
			final boolean[] existed = {false};
			updateTempPath(vns, (current, from) -> {
				existed[0] = leafExisted(current, keys, from);
				ACell next = deepSet(current, keys, from, value);
				// t/ keys carry no namespace prefix (the resolver consumed it), so
				// "built hierarchy" is one segment shallower than the physical path
				// (whose keys[0] is the namespace): > 1, not > 2.
				built[0] = keys.length > 1 && !parentExisted(current, keys, from);
				return next;
			});
			return writeResult(existed[0], built[0]);
		}
		if (vns != null && vns.sessionId() != null) {
			final ACell[] keys = vns.remainingKeys();
			final boolean[] built = {false};
			final boolean[] existed = {false};
			updateSessionPath(vns, (current, from) -> {
				existed[0] = leafExisted(current, keys, from);
				ACell next = deepSet(current, keys, from, value);
				built[0] = keys.length > 1 && !parentExisted(current, keys, from);
				return next;
			});
			return writeResult(existed[0], built[0]);
		}

		// Cross-user owner cursor, cursor-based virtual (n/), or own physical namespace
		if (vns != null) jsonKeys = vns.remainingKeys();
		@SuppressWarnings("unchecked")
		ALatticeCursor<ACell> baseCursor = (xu != null) ? (ALatticeCursor<ACell>) xu[0]
			: (vns != null) ? vns.cursor() : ensureUserCursor(ctx);
		final ACell[] keys = jsonKeys;
		final boolean[] built = {false};
		final boolean[] existed = {false};
		// deepSet first so a shape conflict throws its descriptive error (#146);
		// pathCreated is read from the pre-state and means "a missing parent
		// container had to be built" — not whether the leaf already had a value
		// (that is `existed`).
		if (!updatePath(baseCursor, keys, (current, from) -> {
				existed[0] = leafExisted(current, keys, from);
				ACell next = deepSet(current, keys, from, value);
				// pathCreated reports building a nested parent below the namespace;
				// creating the namespace + its direct child (keys.length <= 2) is
				// not "hierarchy" — so only paths deeper than that can report it.
				built[0] = keys.length > 2 && !parentExisted(current, keys, from);
				return next;
			})) {
			throw new RuntimeException("Cannot resolve path: " + keys[0] + "/" + keys[1]);
		}
		return writeResult(existed[0], built[0]);
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
		// A DID-URL path deletes from the owner's namespace (authorised in
		// requireCap above). create=false: an absent owner namespace has nothing to
		// remove, and delete stays idempotent without materialising it.
		Object[] xu = didUrlMutationTarget(ctx, RT.getIn(input, Fields.PATH), false);
		ACell[] jsonKeys = (xu != null) ? (ACell[]) xu[1] : parsePath(RT.getIn(input, Fields.PATH));
		requireDeleteAccess(ctx, jsonKeys);

		// Job/task-scoped t/: apply the SAME deepDelete as the
		// physical path against the temp value at the navigation root (#176).
		NamespaceResolver.ResolvedNamespace vns = (xu != null) ? null : resolveVirtual(ctx, jsonKeys);
		if (vns != null && vns.jobId() != null) {
			final ACell[] keys = vns.remainingKeys();
			final boolean[] deleted = {false};
			updateTempPath(vns, (current, from) -> {
				deleted[0] = leafExisted(current, keys, from);
				return deepDelete(current, keys, from);
			});
			return Maps.of(K_DELETED, CVMBool.of(deleted[0]));
		}
		if (vns != null && vns.sessionId() != null) {
			final ACell[] keys = vns.remainingKeys();
			final boolean[] deleted = {false};
			updateSessionPath(vns, (current, from) -> {
				deleted[0] = leafExisted(current, keys, from);
				return deepDelete(current, keys, from);
			});
			return Maps.of(K_DELETED, CVMBool.of(deleted[0]));
		}

		// Cross-user owner cursor, cursor-based virtual (n/), or own physical
		// namespace. An unresolvable path is a silent success (delete is idempotent).
		if (vns != null) jsonKeys = vns.remainingKeys();
		@SuppressWarnings("unchecked")
		ALatticeCursor<ACell> cursor = (xu != null) ? (ALatticeCursor<ACell>) xu[0]
			: (vns != null) ? vns.cursor() : getUserCursor(ctx);
		if (cursor == null) return Maps.of(K_DELETED, CVMBool.FALSE);
		final ACell[] keys = jsonKeys;
		final boolean[] deleted = {false};
		updatePath(cursor, keys, (current, from) -> {
			deleted[0] = leafExisted(current, keys, from);
			return deepDelete(current, keys, from);
		});
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
		// A DID-URL path appends into the owner's namespace (authorised in
		// requireCap above); a bare path into the caller's own.
		Object[] xu = didUrlMutationTarget(ctx, RT.getIn(input, Fields.PATH), true);
		ACell[] jsonKeys = (xu != null) ? (ACell[]) xu[1] : parsePath(RT.getIn(input, Fields.PATH));
		requireWriteAccess(ctx, jsonKeys);

		NamespaceResolver.ResolvedNamespace vns = (xu != null) ? null : resolveVirtual(ctx, jsonKeys);
		ACell element = parseJsonValue(RT.getIn(input, Fields.VALUE));

		// Job/task-scoped t/: append into the Job temp value at the
		// navigation root — the same deepAppend as the physical path. Without
		// this branch a job-scope t/ append would (wrongly) target the jobs
		// Index cursor rather than the job record's temp field (#176).
		if (vns != null && vns.jobId() != null) {
			final ACell[] keys = vns.remainingKeys();
			final boolean[] built = {false};
			final boolean[] existed = {false};
			final long[] newSize = {0};
			updateTempPath(vns, (current, from) -> {
				existed[0] = leafExisted(current, keys, from);
				ACell next = deepAppend(current, keys, from, element);
				built[0] = keys.length > 1 && !parentExisted(current, keys, from);
				ACell leaf = deepGet(next, keys, from);
				newSize[0] = (leaf instanceof AVector) ? ((AVector<?>) leaf).count() : 0;
				return next;
			});
			return appendResult(existed[0], newSize[0], built[0]);
		}
		if (vns != null && vns.sessionId() != null) {
			final ACell[] keys = vns.remainingKeys();
			final boolean[] built = {false};
			final boolean[] existed = {false};
			final long[] newSize = {0};
			updateSessionPath(vns, (current, from) -> {
				existed[0] = leafExisted(current, keys, from);
				ACell next = deepAppend(current, keys, from, element);
				built[0] = keys.length > 1 && !parentExisted(current, keys, from);
				ACell leaf = deepGet(next, keys, from);
				newSize[0] = (leaf instanceof AVector) ? ((AVector<?>) leaf).count() : 0;
				return next;
			});
			return appendResult(existed[0], newSize[0], built[0]);
		}

		// Cross-user owner cursor, cursor-based virtual (n/), or own physical namespace
		if (vns != null) jsonKeys = vns.remainingKeys();
		@SuppressWarnings("unchecked")
		ALatticeCursor<ACell> baseCursor = (xu != null) ? (ALatticeCursor<ACell>) xu[0]
			: (vns != null) ? vns.cursor() : ensureUserCursor(ctx);

		final ACell[] keys = jsonKeys;
		final boolean[] built = {false};
		final boolean[] existed = {false};
		final long[] newSize = {0};
		if (!updatePath(baseCursor, keys, (current, from) -> {
				existed[0] = leafExisted(current, keys, from);
				ACell next = deepAppend(current, keys, from, element);
				built[0] = keys.length > 2 && !parentExisted(current, keys, from);
				ACell leaf = deepGet(next, keys, from);
				newSize[0] = (leaf instanceof AVector) ? ((AVector<?>) leaf).count() : 0;
				return next;
			})) {
			throw new RuntimeException("Cannot resolve path: " + keys[0] + "/" + keys[1]);
		}
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
	 * Coerces a value supplied as a JSON-encoded string into the parsed
	 * structure. LLMs frequently pass nested objects/arrays as a string
	 * literal — without this, an enrichment map would be persisted as a
	 * single string instead of a queryable structure.
	 *
	 * <p>Only strings whose first/last non-whitespace characters look like
	 * JSON object/array delimiters are parsed; everything else is returned
	 * unchanged. Parse failures fall back to the original value.</p>
	 */
	static ACell parseJsonValue(ACell cell) {
		if (!(cell instanceof AString s)) return cell;
		String str = s.toString().trim();
		if (str.isEmpty()) return cell;
		char first = str.charAt(0);
		char last = str.charAt(str.length() - 1);
		if ((first == '{' && last == '}') || (first == '[' && last == ']')) {
			try {
				ACell parsed = JSON.parse(str);
				if (parsed != null) return parsed;
			} catch (Exception e) { /* not valid JSON, return original */ }
		}
		return cell;
	}

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
	 * A positional update applied at a write target: receives the current
	 * value at the navigation root and the index in the path keys where that
	 * root sits, and returns the replacement value.
	 */
	@FunctionalInterface
	private interface PathUpdate {
		ACell apply(ACell current, int fromIndex);
	}

	/**
	 * Applies a {@link PathUpdate} to the Job-backed {@code t/} temp slot. A
	 * focused agent task uses its task id as the Job id; an ordinary operation
	 * uses its current job id. The {@code temp} value inside the Job record is
	 * the navigation root, so the update runs at path index 0 — exactly as
	 * {@link #updatePath} runs it on a cursor value — giving every {@code t/}
	 * write/delete/append the same deep navigation and shape-conflict semantics.
	 *
	 * <p>The data lives once in the Job record's {@code temp} field and is
	 * timestamp-stamped atomically via
	 * {@link TempNamespaceResolver#updateTemp}.</p>
	 */
	private static void updateTempPath(NamespaceResolver.ResolvedNamespace vns, PathUpdate update) {
		TempNamespaceResolver.updateTemp(vns.cursor(), vns.jobId(),
			oldTemp -> update.apply(oldTemp, 0));
	}

	/** Applies a deep mutation to the current session's user-scratch root. */
	private static void updateSessionPath(
			NamespaceResolver.ResolvedNamespace vns, PathUpdate update) {
		SessionNamespaceResolver.updateSessionState(vns.cursor(), vns.sessionId(),
			oldState -> update.apply(oldState, 0));
	}

	/** Materialises the root value of an atomic embedded virtual namespace. */
	private static ACell scopedRoot(NamespaceResolver.ResolvedNamespace vns) {
		if (vns.jobId() != null) {
			return TempNamespaceResolver.getTemp(vns.cursor(), vns.jobId());
		}
		if (vns.sessionId() != null) {
			return SessionNamespaceResolver.getSessionState(vns.cursor(), vns.sessionId());
		}
		return null;
	}

	/**
	 * Reads the literal value targeted by an accessor input, including atomic
	 * embedded virtual namespaces whose parent record cannot be cursor-navigated.
	 */
	private ACell readInputValue(RequestContext ctx, ACell input) {
		ACell pathCell = RT.getIn(input, Fields.PATH);
		AString pathString = RT.ensureString(pathCell);
		if (pathString == null || !pathString.toString().startsWith("did:")) {
			ACell[] pathKeys = parsePath(pathCell);
			NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
			if (vns != null && (vns.jobId() != null || vns.sessionId() != null)) {
				ACell root = scopedRoot(vns);
				return (vns.remainingKeys().length == 0)
					? root : deepGet(root, vns.remainingKeys(), 0);
			}
		}

		Object[] target = resolveTargetPath(ctx, input);
		ALatticeCursor<ACell> cursor = (ALatticeCursor<ACell>) target[0];
		ACell[] pathKeys = (ACell[]) target[1];
		if (cursor == null) return null;
		return (pathKeys.length > 0) ? readPath(cursor, pathKeys) : cursor.get();
	}

	/**
	 * Applies an update to the value addressed by {@code keys} via a read-modify-
	 * write on the navigation root ({@code baseCursor}). The write lands through
	 * the venue {@code :value} stamp-on-write boundary (which re-stamps the whole
	 * value), and whole-value LWW makes the result — including any deletion —
	 * durable across the propagator's merge-back. Structural navigation builds
	 * missing intermediates by key shape.
	 *
	 * <p>For a {@link #WRAPPED_NAMESPACES wrapped namespace} the stored value is a
	 * {@code {updated, data}} container: the update runs against the unwrapped
	 * {@code data} (path index 1) and the namespace is re-wrapped with fresh
	 * {@code updated} metadata — transparent to callers, who address inside
	 * {@code data}.
	 *
	 * @return {@code true} (the navigation root is always addressable; a genuine
	 *         shape conflict throws a descriptive error from {@code deepSet})
	 */
	private static boolean updatePath(ALatticeCursor<ACell> baseCursor, ACell[] keys, PathUpdate update) {
		if (keys.length > 0 && isWrappedNamespace(keys[0])) {
			// Translate w/foo -> w/data/foo: navigate THROUGH the namespace's
			// StampingLattice boundary into `data`, then apply the normal deep op
			// there. Going through the boundary (rather than assoc-ing at the
			// boundary key) is what (1) lets the StampedCursor stamp `updated`
			// automatically and (2) actually propagates the write — a direct assoc
			// at a write-boundary key does not reach storage. Content lives under
			// `data`; callers never address it (path applied from index 1).
			baseCursor.path(new ACell[] { keys[0], K_DATA }).updateAndGet(dataValue ->
				update.apply(dataValue, 1));
			return true;
		}
		baseCursor.updateAndGet(current -> update.apply(current, 0));
		return true;
	}

	/**
	 * Writes directly through an already-authorised lattice cursor using the
	 * same wrapped-namespace and deep-path semantics as {@code covia:write}.
	 *
	 * <p>This is an internal composition primitive for framework-owned state
	 * transactions such as venue bootstrap. It deliberately performs no
	 * capability check and creates no Job: possession of the target cursor is
	 * the authority boundary. External requests must continue to use the
	 * adapter operation.</p>
	 *
	 * @param baseCursor cursor at a user namespace root
	 * @param keys path keys relative to that root
	 * @param value value to store
	 */
	public static void writePathToCursor(
			ALatticeCursor<ACell> baseCursor, ACell[] keys, ACell value) {
		if (baseCursor == null) throw new IllegalArgumentException("baseCursor is required");
		if (keys == null || keys.length == 0) {
			throw new IllegalArgumentException("A non-empty cursor-relative path is required");
		}
		updatePath(baseCursor, keys, (current, from) -> deepSet(current, keys, from, value));
	}

	/** True if {@code key} names a {@code {updated, data}}-wrapped namespace. */
	private static boolean isWrappedNamespace(ACell key) {
		return (key != null) && WRAPPED_NAMESPACES.contains(key.toString());
	}

	/** True if {@code v} has the {@code {updated, data}} storage-wrapper shape. */
	private static boolean isWrapper(ACell v) {
		return (v instanceof AMap<?,?> m) && m.containsKey(K_DATA) && m.containsKey(K_UPDATED);
	}

	/** The user-visible data inside a namespace value (the wrapper is transparent). */
	@SuppressWarnings("unchecked")
	private static ACell unwrap(ACell nsValue) {
		return isWrapper(nsValue) ? ((AMap<AString, ACell>) nsValue).get(K_DATA) : nsValue;
	}

	// ========== Type-aware deep navigation ==========
	//
	// These helpers navigate into opaque CVM values (maps AND vectors/sequences)
	// beyond the lattice boundary. Path keys are interpreted by the value type:
	//   - AMap:     string key lookup
	//   - AVector:  integer index (parsed from string or CVMLong)
	//   - other:    navigation fails (null for reads, error for writes)

	/**
	 * Reads a value at a path by navigating into the navigation root's value with
	 * type-aware deep navigation — handles maps, vector indices (e.g.
	 * {@code "w/events/0"}), and Index keys. For a {@link #WRAPPED_NAMESPACES
	 * wrapped namespace} the {@code {updated, data}} container is unwrapped
	 * transparently, so callers see only the namespace's data.
	 */
	public static ACell readPath(ALatticeCursor<ACell> cursor, ACell[] jsonKeys) {
		ACell root = cursor.get();
		if (jsonKeys.length > 0 && isWrappedNamespace(jsonKeys[0])) {
			ACell data = unwrap(deepGet(root, new ACell[] { jsonKeys[0] }, 0));
			return (jsonKeys.length == 1) ? data : deepGet(data, jsonKeys, 1);
		}
		return deepGet(root, jsonKeys, 0);
	}

	/**
	 * Type-aware deep get: navigates into a CVM value following the path,
	 * interpreting keys as map keys or vector indices depending on the value type.
	 */
	static ACell deepGet(ACell root, ACell[] keys, int fromIndex) {
		ACell current = root;
		for (int i = fromIndex; i < keys.length && current != null; i++) {
			current = navigateInto(current, keys[i]);
		}
		return current;
	}

	/**
	 * True if the parent container of the leaf — the node at
	 * {@code keys[from .. length-2]} — already exists in {@code root}. Used to
	 * derive {@code pathCreated}: a write builds new hierarchy exactly when this
	 * is false (a missing intermediate container had to be auto-created). It is
	 * independent of whether a value already sat at the leaf — overwriting an
	 * existing leaf inside an existing parent is not "path created".
	 *
	 * <p>A path that addresses the root level directly (≤1 segment from
	 * {@code from}) has the namespace container itself as its parent, which
	 * always exists — so it never counts as creating hierarchy.</p>
	 */
	static boolean parentExisted(ACell root, ACell[] keys, int from) {
		if (keys.length - from <= 1) return true;
		ACell node = root;
		for (int i = from; i < keys.length - 1 && node != null; i++) {
			node = navigateInto(node, keys[i]);
		}
		return node != null;
	}

	/**
	 * True if the leaf key ({@code keys[length-1]}) already had an entry in
	 * {@code root} before a mutation — the pre-state that distinguishes a
	 * <em>created</em> write from a <em>replaced</em> one, and a real delete from a
	 * no-op. Independent of whether the value there was null (a stored null still
	 * counts as present). Called with the same {@code (current, keys, from)} the
	 * write/delete lambda receives, so the namespace wrapper is already unwrapped.
	 */
	static boolean leafExisted(ACell root, ACell[] keys, int from) {
		if (keys.length - from <= 0) return root != null;
		ACell parent = root;
		for (int i = from; i < keys.length - 1 && parent != null; i++) {
			parent = navigateInto(parent, keys[i]);
		}
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/**
	 * Navigates one level into a CVM value. For maps, uses string key lookup.
	 * For vectors/sequences, parses the key as an integer index.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static ACell navigateInto(ACell value, ACell key) {
		if (value instanceof AVector<?> vec) {
			long idx = parseIndex(key);
			return (idx >= 0 && idx < vec.count()) ? vec.get(idx) : null;
		}
		// Index keys are blob-like (sessions, jobs, scheduler handles use Blob
		// keys, rendered outward as hex by ALattice.toJSONKey). Check Index
		// before AMap — Index is an AMap subtype. AString is itself ABlobLike, so
		// try the key directly first (a string-keyed index), then fall back to
		// parsing an AString as hex into a Blob (a Blob-keyed index).
		if (value instanceof Index) {
			Index idx = (Index) value;
			if (key instanceof ABlobLike) {
				ACell direct = (ACell) idx.get(key);
				if (direct != null) return direct;
			}
			if (key instanceof AString s) {
				Blob b = Blob.parse(s.toString());
				if (b != null) return (ACell) idx.get(b);
			}
			return null;
		}
		if (value instanceof AMap<?,?> map) {
			AString strKey = RT.ensureString(key);
			return (strKey != null) ? ((AMap<AString, ACell>) map).get(strKey) : null;
		}
		return null;
	}

	/**
	 * Parses a path key as a non-negative integer index.
	 * Accepts CVMLong directly or AString that parses as a non-negative long.
	 * Returns -1 if not a valid index.
	 */
	static long parseIndex(ACell key) {
		if (key instanceof CVMLong l) return l.longValue();
		AString str = RT.ensureString(key);
		if (str == null) return -1;
		try {
			long idx = Long.parseLong(str.toString());
			return (idx >= 0) ? idx : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Deep set: navigates into a CVM value by path and sets the leaf value.
	 * Type-aware — handles both maps (creates intermediate maps as needed)
	 * and vectors (updates element at index; index must be in bounds).
	 */
	@SuppressWarnings("unchecked")
	static ACell deepSet(ACell root, ACell[] keys, int fromIndex, ACell value) {
		if (fromIndex >= keys.length) return value;
		ACell key = keys[fromIndex];

		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(key);
			if (idx < 0 || idx >= vec.count()) {
				String at = buildSubPath(Arrays.copyOfRange(keys, 0, fromIndex));
				if (idx >= 0) {   // numeric, just out of range
					throw new IllegalArgumentException(
						"List index out of bounds at '" + at + "': " + idx + " (size " + vec.count() + ")");
				}
				throw new IllegalArgumentException(   // non-index key on a list = shape conflict
					"Cannot index the list at '" + at + "' (size " + vec.count() + ") with key '" + key
					+ "': lists use non-negative integer positions. To store named keys here, replace "
					+ "the whole node with a map first (write '" + at + "' = {}).");
			}
			return vec.assoc(idx, deepSet(vec.get(idx), keys, fromIndex + 1, value));
		}

		// Navigating into an existing non-map, non-vector value is a shape
		// conflict — the scalar counterpart of the list case above. Only a
		// genuinely absent (null) intermediate is auto-vivified into a map;
		// a real value must never be silently clobbered.
		if (root != null && !(root instanceof AMap)) {
			String at = buildSubPath(Arrays.copyOfRange(keys, 0, fromIndex));
			throw new IllegalArgumentException(
				"Cannot set key '" + key + "' on the scalar value at '" + at
				+ "': this path holds a value, not a map. To store named keys here, replace "
				+ "the whole node with a map first (write '" + at + "' = {}).");
		}
		// Default: map navigation, creating intermediate maps as needed
		AMap<AString, ACell> map = (root instanceof AMap)
			? (AMap<AString, ACell>) root : Maps.empty();
		AString mapKey = RT.ensureString(key);
		return map.assoc(mapKey, deepSet(map.get(mapKey), keys, fromIndex + 1, value));
	}

	/**
	 * Deep delete: navigates into a CVM value by path and removes the leaf.
	 * For maps, dissociates the key. For vectors, sets the element to null
	 * (preserving index stability).
	 */
	@SuppressWarnings("unchecked")
	static ACell deepDelete(ACell root, ACell[] keys, int fromIndex) {
		// Path addresses this node itself — deleting it yields null
		// (the whole-entry delete case for entry-cursor namespaces).
		if (fromIndex >= keys.length) return null;
		if (root == null) return root;
		ACell key = keys[fromIndex];
		boolean isLeaf = (fromIndex == keys.length - 1);

		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(key);
			if (idx < 0 || idx >= vec.count()) return root;
			return isLeaf
				? vec.assoc(idx, null)
				: vec.assoc(idx, deepDelete(vec.get(idx), keys, fromIndex + 1));
		}

		if (!(root instanceof AMap)) return root;
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		AString mapKey = RT.ensureString(key);
		if (isLeaf) return map.dissoc(mapKey);
		ACell child = map.get(mapKey);
		return map.assoc(mapKey, deepDelete(child, keys, fromIndex + 1));
	}

	/**
	 * Appends an element to a vector, creating a new vector if the current
	 * value is null or an empty container (the latter occurs when an
	 * atomic update lambda receives the value lattice's zero in place of
	 * a missing path — see {@link convex.lattice.cursor.PathCursor}).
	 *
	 * @throws RuntimeException if current value is non-empty and non-vector
	 */
	@SuppressWarnings("unchecked")
	private static ACell appendToVector(ACell current, ACell element) {
		if (current == null) return Vectors.of(element);
		if (current instanceof AVector) {
			return ((AVector<ACell>) current).conj(element);
		}
		if (current instanceof AMap && ((AMap<?, ?>) current).isEmpty()) {
			return Vectors.of(element);
		}
		throw new RuntimeException(
			"Cannot append to non-vector value of type: " + current.getType());
	}

	/**
	 * Deep append: navigates into a CVM value by path and appends an element
	 * to the vector at the leaf. Type-aware — handles both map and vector
	 * navigation, creating intermediate maps as needed.
	 */
	@SuppressWarnings("unchecked")
	static ACell deepAppend(ACell root, ACell[] keys, int fromIndex, ACell element) {
		if (fromIndex >= keys.length) return appendToVector(root, element);
		ACell key = keys[fromIndex];

		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(key);
			if (idx < 0 || idx >= vec.count()) {
				String at = buildSubPath(Arrays.copyOfRange(keys, 0, fromIndex));
				if (idx >= 0) {   // numeric, just out of range
					throw new IllegalArgumentException(
						"List index out of bounds at '" + at + "': " + idx + " (size " + vec.count() + ")");
				}
				throw new IllegalArgumentException(   // non-index key on a list = shape conflict
					"Cannot index the list at '" + at + "' (size " + vec.count() + ") with key '" + key
					+ "': lists use non-negative integer positions. To store named keys here, replace "
					+ "the whole node with a map first (write '" + at + "' = {}).");
			}
			return vec.assoc(idx, deepAppend(vec.get(idx), keys, fromIndex + 1, element));
		}

		// Shape conflict: cannot navigate into an existing non-map, non-vector
		// scalar. Auto-vivify only a genuinely absent (null) intermediate.
		if (root != null && !(root instanceof AMap)) {
			String at = buildSubPath(Arrays.copyOfRange(keys, 0, fromIndex));
			throw new IllegalArgumentException(
				"Cannot set key '" + key + "' on the scalar value at '" + at
				+ "': this path holds a value, not a map. To store named keys here, replace "
				+ "the whole node with a map first (write '" + at + "' = {}).");
		}
		AMap<AString, ACell> map = (root instanceof AMap)
			? (AMap<AString, ACell>) root : Maps.empty();
		AString mapKey = RT.ensureString(key);
		return map.assoc(mapKey, deepAppend(map.get(mapKey), keys, fromIndex + 1, element));
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
	 * Parses the {@code fields} parameter: a comma-separated string (the GET
	 * form) or a vector of strings (the op form). Blank names are rejected and
	 * the count is capped — loudly, never silently.
	 */
	private static AString[] parseFieldsParam(ACell fieldsCell) {
		java.util.List<AString> out = new java.util.ArrayList<>();
		if (fieldsCell instanceof AString s) {
			for (String part : s.toString().split(",")) {
				String trimmed = part.trim();
				if (trimmed.isEmpty()) throw new IllegalArgumentException("fields contains a blank field name");
				out.add(Strings.create(trimmed));
			}
		} else if (fieldsCell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				AString f = RT.ensureString((ACell) v.get(i));
				if (f == null || f.isEmpty()) throw new IllegalArgumentException("fields entries must be non-empty strings");
				out.add(f);
			}
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

	/**
	 * Splits a slash-delimited path string into AString segments.
	 *
	 * <p>All segments are kept as AString — no attempt to parse numbers or
	 * guess types. The lattice's {@link ALattice#resolveKey} at each level
	 * is responsible for translating to the correct CVM type.</p>
	 */
	public static ACell[] parseStringPath(String path) {
		if (path == null || path.isEmpty()) return new ACell[0];
		if (path.startsWith("/")) path = path.substring(1);
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (path.isEmpty()) return new ACell[0];

		String[] segments = path.split("/");
		ACell[] keys = new ACell[segments.length];
		for (int i = 0; i < segments.length; i++) {
			keys[i] = Strings.create(segments[i]);
		}
		return keys;
	}

	// ========== Helpers ==========

	/**
	 * Gets the lattice cursor for the authenticated user's namespace root.
	 * Returns null if the user has no lattice state.
	 */
	private ALatticeCursor<ACell> getUserCursor(RequestContext ctx) {
		Users users = engine.getVenueState().users();
		// The USER's record, not the acting principal's: an agent sub-principal
		// works inside its owner's namespace and has no lattice record of its own.
		// This must agree with how bare paths canonicalise in the capability check
		// (RequestContext.grantsDenial), or an agent would be granted w/foo in one
		// namespace and read it from another.
		User user = users.get(ctx.getUserDID());
		if (user == null) return null;
		return user.cursor();
	}

	/**
	 * Gets the lattice cursor for the authenticated user (caller), creating
	 * the user namespace if it does not yet exist. Used by write operations
	 * that need to store data for a first-time user.
	 */
	public ALatticeCursor<ACell> ensureUserCursor(RequestContext ctx) {
		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getUserDID());
		return user.cursor();
	}

	/**
	 * Gets the lattice cursor for a specific user identified by DID, creating
	 * the user namespace if it does not yet exist. Parallel to
	 * {@link #ensureUserCursor(RequestContext)} but targets a named user
	 * rather than the calling user. Used by venue-scoped virtual namespace
	 * resolvers (e.g. {@code VenueGlobalsResolver} for {@code /v/}) that
	 * need to navigate to a fixed user's data regardless of the caller.
	 *
	 * @param did the DID of the user whose cursor to return
	 * @return the lattice cursor positioned at that user's namespace root
	 */
	public ALatticeCursor<ACell> ensureUserCursor(AString did) {
		Users users = engine.getVenueState().users();
		User user = users.ensure(did);
		return user.cursor();
	}

	/**
	 * Resolves a path that may target another user's namespace via DID URL syntax.
	 *
	 * <p>Paths can be either relative (e.g. {@code "w/notes"}) targeting the
	 * caller's own namespace, or DID URL paths (e.g. {@code "did:key:z6Mk.../w/notes"})
	 * targeting another user. DID URL parsing uses {@link DIDURL} from convex-core.</p>
	 *
	 * <p>Cross-user access is denied until capability enforcement is implemented.
	 * The infrastructure resolves the target user and path, then rejects with a
	 * clear error.</p>
	 *
	 * @return A two-element result: [cursor, pathKeys] where pathKeys are the
	 *         namespace-relative keys (DID prefix stripped)
	 */
	private Object[] resolveTargetPath(RequestContext ctx, ACell input) {
		ACell pathCell = RT.getIn(input, Fields.PATH);
		if (pathCell == null) {
			return new Object[] { getUserCursor(ctx), new ACell[0] };
		}

		// Check if the raw path string is a DID URL (starts with "did:")
		AString pathStr = RT.ensureString(pathCell);
		if (pathStr != null && pathStr.toString().startsWith("did:")) {
			return resolveDIDURL(ctx, pathStr.toString(), false);
		}

		ACell[] pathKeys = parsePath(pathCell);

		// Check for virtual namespace (n/ → agent workspace)
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
		if (vns != null && vns.jobId() == null) {
			// Cursor-based virtual namespace (n/): return cursor + remaining keys
			return new Object[] { vns.cursor(), vns.remainingKeys() };
		}
		return new Object[] { getUserCursor(ctx), pathKeys };
	}


	/**
	 * Resolves a parsed path, checking virtual namespace resolvers first,
	 * then falling through to the standard user lattice cursor.
	 *
	 * @return For virtual namespaces: ResolvedNamespace with cursor at namespace root.
	 *         For physical namespaces: null (caller uses standard user cursor flow).
	 */
	public NamespaceResolver.ResolvedNamespace resolveVirtual(RequestContext ctx, ACell[] pathKeys) {
		if (pathKeys.length > 0) {
			String prefix = pathKeys[0].toString();
			NamespaceResolver resolver = resolvers.get(prefix);
			if (resolver != null) {
				return resolver.resolve(ctx, this, pathKeys);
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
	 * to handle virtual namespaces uniformly. The {@code t/} (job-scoped temp)
	 * resolver is excluded — its data structure access pattern (per-job temp
	 * field) requires specialised handling that lives in {@link #handleRead}.</p>
	 */
	public ACell readVirtualNamespace(RequestContext ctx, AString path) {
		if (path == null) return null;
		ACell[] pathKeys = parseStringPath(path.toString());
		if (pathKeys.length == 0) return null;

		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
		if (vns == null) return null;

		// Job-scoped (t/) is not handled here — it requires specialised
		// access via TempNamespaceResolver.getTemp.
		if (vns.jobId() != null) return null;

		// Cursor-based virtual namespace (n/, v/, etc.): navigate the
		// resolved cursor with the remaining keys.
		ACell[] remaining = vns.remainingKeys();
		if (remaining.length == 0) return vns.cursor().get();
		return readPath(vns.cursor(), remaining);
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

	/**
	 * <em>Locates</em> a DID URL path like {@code "did:key:z6MkAlice/w/notes"} —
	 * a target cursor and namespace-relative keys. Authorisation is not repeated
	 * here: {@link #requireCap} is the single gate and always runs first (own
	 * paths through the scope seam, cross-user paths through the proof gate), so
	 * by the time this resolves a cursor the caller is already authorised. Shared
	 * verbatim by reads and mutations.
	 *
	 * @param create materialise the owner's namespace if absent (write/append) —
	 *               a first delegated write may be the owner's first touch here,
	 *               exactly as an own first write creates the caller's namespace —
	 *               versus leave it null (read/delete: an absent namespace reads
	 *               as {@code exists:false} / deletes idempotently)
	 * @return {@code [cursor, pathKeys]}; {@code cursor} is null when
	 *         {@code create} is false and the target has no namespace here
	 */
	private Object[] resolveDIDURL(RequestContext ctx, String rawPath, boolean create) {
		DIDURL didURL = DIDURL.create(rawPath);
		AString targetDID = Strings.create(didURL.getDID().toString());
		ACell[] pathKeys = parseStringPath(didURL.getPath());

		if (targetDID.equals(ctx.getUserDID())) {
			// Own namespace via explicit DID — same place a bare path canonicalises to.
			ALatticeCursor<ACell> own = create ? ensureUserCursor(ctx) : getUserCursor(ctx);
			return new Object[] { own, pathKeys };
		}
		// Cross-user: requireCap already authorised (or threw). Just locate.
		Users users = engine.getVenueState().users();
		User targetUser = create ? users.ensure(targetDID) : users.get(targetDID);
		return new Object[] { (targetUser == null) ? null : targetUser.cursor(), pathKeys };
	}

	/**
	 * The DID-URL mutation target for {@code write}/{@code delete}/{@code append}:
	 * {@code [cursor, pathKeys]} against the owner's namespace when the path is a
	 * DID URL, else {@code null} (the caller uses its own-cursor flow). A DID URL
	 * never names a virtual ({@code n/}, {@code t/}) namespace, so this fully
	 * determines the target. Authorisation is enforced upstream in
	 * {@link #requireCap}; this only locates.
	 */
	private Object[] didUrlMutationTarget(RequestContext ctx, ACell pathCell, boolean create) {
		AString pathStr = RT.ensureString(pathCell);
		if (pathStr == null || !pathStr.toString().startsWith("did:")) return null;
		return resolveDIDURL(ctx, pathStr.toString(), create);
	}

	/**
	 * Builds a sub-path string from parsed path keys (e.g. ["w", "notes"] → "/w/notes").
	 */
	private static String buildSubPath(ACell[] pathKeys) {
		if (pathKeys.length == 0) return "/";
		StringBuilder sb = new StringBuilder();
		for (ACell key : pathKeys) {
			sb.append('/').append(key);
		}
		return sb.toString();
	}

}
