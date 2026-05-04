package covia.adapter;

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
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.ASet;
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
import convex.lattice.ALattice;
import convex.lattice.cursor.ALatticeCursor;
import covia.api.Fields;
import covia.grid.Asset;
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
 *   user-root
 *   ├── g/          agents     (MapLattice → per-agent records)
 *   ├── s/          secrets    (MapLattice → encrypted values)
 *   ├── j/          jobs       (IndexLattice → job records by Blob ID)
 *   ├── w/          workspace  (MapLattice → user-writable general-purpose data)
 *   └── o/          operations (MapLattice → user-writable operation definitions)
 * </pre>
 *
 * <p>The {@code w/} and {@code o/} namespaces are user-writable via
 * {@code covia:write} and {@code covia:delete}. All other namespaces
 * ({@code g/}, {@code s/}, {@code j/}) are framework-managed and
 * reject direct writes.</p>
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
 */
public class CoviaAdapter extends AAdapter {

	private static final AString K_KEYS    = Strings.intern("keys");
	private static final AString K_COUNT   = Strings.intern("count");
	private static final AString K_TYPE    = Strings.intern("type");
	private static final AString K_VALUE   = Strings.intern("value");
	private static final AString K_VALUES  = Strings.intern("values");
	private static final AString K_EXISTS  = Strings.intern("exists");
	private static final AString K_WRITTEN  = Strings.intern("written");
	private static final AString K_DELETED  = Strings.intern("deleted");
	private static final AString K_APPENDED  = Strings.intern("appended");
	private static final AString K_SIZE      = Strings.intern("size");
	private static final AString K_TRUNCATED = Strings.intern("truncated");
	private static final AString K_MAX_SIZE  = Strings.intern("maxSize");

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
		String BASE = "/adapters/covia/";
		installAsset("covia/read",    BASE + "read.json");
		installAsset("covia/write",   BASE + "write.json");
		installAsset("covia/copy",    BASE + "copy.json");
		installAsset("covia/delete",  BASE + "delete.json");
		installAsset("covia/append",  BASE + "append.json");
		installAsset("covia/slice",   BASE + "slice.json");
		installAsset("covia/list",    BASE + "list.json");
		installAsset("covia/inspect", BASE + "inspect.json");

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
	private ACell handleRead(RequestContext ctx, ACell input) {
		// Check job-scoped virtual namespace (t/) first — these require
		// specialised cursor navigation that Engine.resolvePath doesn't handle.
		ACell pathCell = RT.getIn(input, Fields.PATH);
		if (pathCell != null) {
			ACell[] pathKeys = parsePath(pathCell);
			NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
			if (vns != null && vns.jobId() != null) {
				ACell temp = TempNamespaceResolver.getTemp(vns.cursor(), vns.jobId());
				ACell value = (vns.remainingKeys().length == 0) ? temp
					: deepGet(temp, vns.remainingKeys(), 0);
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
		return sizeGuardedResult(value, input);
	}

	/**
	 * Returns a read result, applying the maxSize guard if necessary.
	 */
	private static ACell sizeGuardedResult(ACell value, ACell input) {
		if (value == null) return result(null);

		long maxSize = DEFAULT_MAX_SIZE;
		ACell maxSizeCell = RT.getIn(input, K_MAX_SIZE);
		if (maxSizeCell instanceof CVMLong l) maxSize = Math.max(0, l.longValue());

		long encodingSize = value.getEncodingLength();
		if (encodingSize > maxSize) {
			return Maps.of(
				K_EXISTS, CVMBool.TRUE,
				K_VALUE, null,
				K_TRUNCATED, CVMBool.TRUE,
				K_SIZE, CVMLong.create(encodingSize));
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
	private ACell handleInspect(RequestContext ctx, ACell input) {
		ACell pathsCell = RT.getIn(input, K_PATHS);

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
		ACell[] pathKeys = parseStringPath(pathStr);

		// Check job-scoped virtual namespace (t/)
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, pathKeys);
		if (vns != null && vns.jobId() != null) {
			ACell temp = TempNamespaceResolver.getTemp(vns.cursor(), vns.jobId());
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
	private ACell handleSlice(RequestContext ctx, ACell input) {
		Object[] target = resolveTargetPath(ctx, input);
		ALatticeCursor<ACell> cursor = (ALatticeCursor<ACell>) target[0];
		ACell[] pathKeys = (ACell[]) target[1];

		if (cursor == null) return Maps.of(K_EXISTS, CVMBool.FALSE);

		ACell value = (pathKeys.length > 0) ? readPath(cursor, pathKeys) : cursor.get();
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
			return Maps.of(K_EXISTS, CVMBool.TRUE, K_TYPE, typeName,
				K_COUNT, CVMLong.create(total), K_VALUES, page,
				Fields.OFFSET, CVMLong.create(offset));

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
			return Maps.of(K_EXISTS, CVMBool.TRUE, K_TYPE, typeName,
				K_COUNT, CVMLong.create(total), K_VALUES, page,
				Fields.OFFSET, CVMLong.create(offset));

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
			return Maps.of(K_EXISTS, CVMBool.TRUE, K_TYPE, typeName,
				K_COUNT, CVMLong.create(total), K_VALUES, page,
				Fields.OFFSET, CVMLong.create(offset));

		} else {
			throw new RuntimeException(
				"covia:slice requires a collection (vector, map, or set), got: " + typeName);
		}
	}

	/** Wraps a value with an exists flag. Null value → exists: false. */
	private static ACell result(ACell value) {
		return Maps.of(
			K_EXISTS, CVMBool.of(value != null),
			K_VALUE, value);
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
	 * @return {@code {written: true}} on success
	 * @throws RuntimeException if the path is invalid or targets a non-writable namespace
	 */
	// ========== covia:copy — server-side path-to-path duplication ==========

	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_TO = Strings.intern("to");
	private static final AString K_COPIED = Strings.intern("copied");

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
	 * @return {@code {copied: true}} on success
	 */
	private ACell handleCopy(RequestContext ctx, ACell input) {
		AString from = RT.ensureString(RT.getIn(input, K_FROM));
		AString to = RT.ensureString(RT.getIn(input, K_TO));
		if (from == null) throw new IllegalArgumentException("'from' is required");
		if (to == null) throw new IllegalArgumentException("'to' is required");

		// Read from source via the canonical universal resolver. Caps
		// enforcement happens inside resolvePath via the cursor it returns.
		ACell value = engine.resolvePath(from, ctx);
		if (value == null) {
			throw new RuntimeException("source not found: " + from);
		}

		// Delegate to handleWrite for the destination, which enforces
		// writability and per-namespace canWrite checks.
		ACell writeInput = Maps.of(Fields.PATH, to, Fields.VALUE, value);
		handleWrite(ctx, writeInput);

		return Maps.of(K_COPIED, CVMBool.TRUE);
	}

	private ACell handleWrite(RequestContext ctx, ACell input) {
		ACell[] jsonKeys = parsePath(RT.getIn(input, Fields.PATH));
		if (!isWritableNamespace(jsonKeys)) validateWritablePath(jsonKeys);
		// Per-call write authorisation for virtual namespaces (e.g. /v/
		// requires the venue identity). Static isWritableNamespace doesn't
		// know about the caller; canWrite(ctx) does.
		if (jsonKeys.length > 0) {
			NamespaceResolver resolver = resolvers.get(jsonKeys[0].toString());
			if (resolver != null && !resolver.canWrite(ctx)) {
				throw new RuntimeException("Write permission denied for namespace: " + jsonKeys[0]);
			}
		}
		ACell value = parseJsonValue(RT.getIn(input, Fields.VALUE));

		// Check job-scoped virtual namespace (t/)
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, jsonKeys);
		if (vns != null && vns.jobId() != null) {
			ACell[] keys = vns.remainingKeys();
			TempNamespaceResolver.updateTemp(vns.cursor(), vns.jobId(), oldTemp -> {
				AMap<AString, ACell> map = (oldTemp instanceof AMap) ? (AMap<AString, ACell>) oldTemp : Maps.empty();
				if (keys.length == 0) return value;
				if (keys.length == 1) return map.assoc(Strings.create(keys[0].toString()), value);
				AString topKey = Strings.create(keys[0].toString());
				return map.assoc(topKey, deepSet(map.get(topKey), keys, 1, value));
			});
			return Maps.of(K_WRITTEN, CVMBool.TRUE);
		}

		// Cursor-based virtual (n/) or physical namespace
		if (vns != null) jsonKeys = vns.remainingKeys();
		ALatticeCursor<ACell> baseCursor = (vns != null) ? vns.cursor() : ensureUserCursor(ctx);
		ALatticeCursor<ACell> entryCursor = resolveEntry(baseCursor, jsonKeys);
		if (jsonKeys.length == 2) {
			entryCursor.set(value);
		} else {
			ACell[] keys = jsonKeys;
			entryCursor.updateAndGet(current -> deepSet(current, keys, 2, value));
		}
		return Maps.of(K_WRITTEN, CVMBool.TRUE);
	}

	/**
	 * Deletes a value at a path in the user's lattice.
	 *
	 * <p>Same namespace restrictions as {@link #handleWrite}. For two-segment
	 * paths, removes the top-level entry. For deeper paths, removes the nested
	 * key via read-modify-write. Idempotent — deleting a non-existent key
	 * succeeds silently.</p>
	 *
	 * @return {@code {deleted: true}} on success
	 */
	private ACell handleDelete(RequestContext ctx, ACell input) {
		ACell[] jsonKeys = parsePath(RT.getIn(input, Fields.PATH));
		if (!isWritableNamespace(jsonKeys)) validateWritablePath(jsonKeys);

		// Check job-scoped virtual namespace (t/)
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, jsonKeys);
		if (vns != null && vns.jobId() != null) {
			ACell[] keys = vns.remainingKeys();
			TempNamespaceResolver.updateTemp(vns.cursor(), vns.jobId(), oldTemp -> {
				AMap<AString, ACell> map = (oldTemp instanceof AMap) ? (AMap<AString, ACell>) oldTemp : Maps.empty();
				if (keys.length == 0) return null;
				AString topKey = Strings.create(keys[0].toString());
				if (keys.length == 1) return map.dissoc(topKey);
				ACell existing = map.get(topKey);
				if (existing == null) return map;
				return map.assoc(topKey, deepDelete(existing, keys, 1));
			});
			return Maps.of(K_DELETED, CVMBool.TRUE);
		}

		// Cursor-based virtual (n/) or physical namespace
		if (vns != null) jsonKeys = vns.remainingKeys();
		ALatticeCursor<ACell> cursor = (vns != null) ? vns.cursor() : getUserCursor(ctx);
		if (cursor == null) return Maps.of(K_DELETED, CVMBool.TRUE);
		ALatticeCursor<ACell> entryCursor = resolveEntryOrNull(cursor, jsonKeys);
		if (entryCursor == null) return Maps.of(K_DELETED, CVMBool.TRUE);
		if (jsonKeys.length == 2) {
			entryCursor.set(null);
		} else {
			ACell[] keys = jsonKeys;
			entryCursor.updateAndGet(current -> current != null ? deepDelete(current, keys, 2) : null);
		}
		return Maps.of(K_DELETED, CVMBool.TRUE);
	}

	/**
	 * Appends an element to a vector at a path in the user's lattice.
	 *
	 * <p>Same namespace restrictions as {@link #handleWrite}. If the target
	 * path is null/missing, a new single-element vector is created. If it is
	 * a vector, the element is appended. Errors if the target is a non-vector
	 * type.</p>
	 *
	 * @return {@code {appended: true}} on success
	 */
	private ACell handleAppend(RequestContext ctx, ACell input) {
		ACell[] jsonKeys = parsePath(RT.getIn(input, Fields.PATH));
		if (!isWritableNamespace(jsonKeys)) validateWritablePath(jsonKeys);

		// Cursor-based virtual (n/) or physical namespace
		NamespaceResolver.ResolvedNamespace vns = resolveVirtual(ctx, jsonKeys);
		if (vns != null) jsonKeys = vns.remainingKeys();
		ALatticeCursor<ACell> baseCursor = (vns != null) ? vns.cursor() : ensureUserCursor(ctx);
		ACell element = parseJsonValue(RT.getIn(input, Fields.VALUE));

		ALatticeCursor<ACell> entryCursor = resolveEntry(baseCursor, jsonKeys);

		if (jsonKeys.length == 2) {
			entryCursor.updateAndGet(current -> appendToVector(current, element));
		} else {
			ACell[] keys = jsonKeys;
			entryCursor.updateAndGet(current -> deepAppend(current, keys, 2, element));
		}
		return Maps.of(K_APPENDED, CVMBool.TRUE);
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
				"Can only write to 'w' (workspace) or 'o' (operations) namespaces, got: " + namespace);
		}
	}

	/**
	 * Resolves the first two path segments (namespace + top-level key)
	 * through the lattice and returns the entry cursor.
	 */
	private static ALatticeCursor<ACell> resolveEntry(ALatticeCursor<ACell> userCursor, ACell[] jsonKeys) {
		ACell[] nsAndKey = new ACell[] { jsonKeys[0], jsonKeys[1] };
		ACell[] resolved = userCursor.getLattice().resolvePath(nsAndKey);
		if (resolved == null) {
			throw new RuntimeException("Cannot resolve path: " + jsonKeys[0] + "/" + jsonKeys[1]);
		}
		return userCursor.path(resolved);
	}

	/** Like {@link #resolveEntry} but returns null instead of throwing. */
	private static ALatticeCursor<ACell> resolveEntryOrNull(ALatticeCursor<ACell> userCursor, ACell[] jsonKeys) {
		ACell[] nsAndKey = new ACell[] { jsonKeys[0], jsonKeys[1] };
		ACell[] resolved = userCursor.getLattice().resolvePath(nsAndKey);
		if (resolved == null) return null;
		return userCursor.path(resolved);
	}

	// ========== Type-aware deep navigation ==========
	//
	// These helpers navigate into opaque CVM values (maps AND vectors/sequences)
	// beyond the lattice boundary. Path keys are interpreted by the value type:
	//   - AMap:     string key lookup
	//   - AVector:  integer index (parsed from string or CVMLong)
	//   - other:    navigation fails (null for reads, error for writes)

	/**
	 * Reads a value at a path, resolving through the lattice then navigating
	 * into the value with type-aware deep navigation. This handles vector
	 * indexing (e.g. {@code "w/events/0"}) that pure lattice resolution cannot.
	 */
	public static ACell readPath(ALatticeCursor<ACell> cursor, ACell[] jsonKeys) {
		// Try full lattice resolution first (works for pure-map paths like
		// g/agent/state/counter where every level is a map).
		ACell[] resolved = cursor.getLattice().resolvePath(jsonKeys);
		if (resolved != null) {
			ACell value = cursor.path(resolved).get();
			if (value != null) return value;
			// Fall through — cursor may have returned null because it cannot
			// navigate into a vector with a string key. Try prefix-based
			// resolution with type-aware deepGet.
		}

		// Resolve progressively shorter prefixes, then navigate the
		// remainder with type-aware deepGet (handles vector indices).
		for (int depth = jsonKeys.length - 1; depth >= 1; depth--) {
			ACell[] prefix = new ACell[depth];
			System.arraycopy(jsonKeys, 0, prefix, 0, depth);
			ACell[] resolvedPrefix = cursor.getLattice().resolvePath(prefix);
			if (resolvedPrefix != null) {
				ACell base = cursor.path(resolvedPrefix).get();
				if (base != null) return deepGet(base, jsonKeys, depth);
				// base is null — cursor may have failed on a vector index.
				// Continue to shorter prefix where deepGet can handle it.
			}
		}
		return null;
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
	 * Navigates one level into a CVM value. For maps, uses string key lookup.
	 * For vectors/sequences, parses the key as an integer index.
	 */
	@SuppressWarnings("unchecked")
	private static ACell navigateInto(ACell value, ACell key) {
		if (value instanceof AVector<?> vec) {
			long idx = parseIndex(key);
			return (idx >= 0 && idx < vec.count()) ? vec.get(idx) : null;
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
				throw new RuntimeException(
					"Vector index out of bounds: " + key + " (size: " + vec.count() + ")");
			}
			return vec.assoc(idx, deepSet(vec.get(idx), keys, fromIndex + 1, value));
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
				throw new RuntimeException(
					"Vector index out of bounds: " + key + " (size: " + vec.count() + ")");
			}
			return vec.assoc(idx, deepAppend(vec.get(idx), keys, fromIndex + 1, element));
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
	private ACell handleList(RequestContext ctx, ACell input) {
		Object[] target = resolveTargetPath(ctx, input);
		ALatticeCursor<ACell> cursor = (ALatticeCursor<ACell>) target[0];
		ACell[] pathKeys = (ACell[]) target[1];

		ACell value = null;
		if (cursor != null) {
			value = (pathKeys.length > 0) ? readPath(cursor, pathKeys) : cursor.get();
		}

		return describeValue(value, input);
	}

	/**
	 * Builds a structured description of a CVM value for the list response.
	 *
	 * <p>Always includes {@code exists} (true if value is non-null) and
	 * {@code type} (standard Convex type name from {@link Types#get}).
	 * Maps additionally include {@code keys} (paginated) and {@code count};
	 * vectors/countables include {@code count}; sets include {@code values}.</p>
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

			// Pagination
			long limit = 1000;
			long offset = 0;
			ACell limitCell = RT.getIn(input, Fields.LIMIT);
			ACell offsetCell = RT.getIn(input, Fields.OFFSET);
			if (limitCell instanceof CVMLong l) limit = Math.max(1, l.longValue());
			if (offsetCell instanceof CVMLong l) offset = Math.max(0, l.longValue());

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

			desc = Maps.of(K_TYPE, typeName, K_COUNT, CVMLong.create(total), K_KEYS, page);
			// Include offset when results are truncated so caller knows where to continue
			if (offset > 0 || page.count() < total) {
				desc = desc.assoc(Fields.OFFSET, CVMLong.create(offset));
			}
		} else if (value instanceof ASet<?> set) {
			// Sets: values are the elements — return them directly
			AVector<ACell> values = Vectors.empty();
			for (ACell elem : set) {
				values = values.conj(ALattice.toJSONKey(elem));
			}
			desc = Maps.of(K_TYPE, typeName, K_COUNT, CVMLong.create(set.count()), K_VALUES, values);
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
		User user = users.get(ctx.getCallerDID());
		if (user == null) return null;
		return user.cursor();
	}

	/**
	 * Gets the lattice cursor for the authenticated user (caller), creating
	 * the user namespace if it does not yet exist. Used by write operations
	 * that need to store data for a first-time user.
	 */
	ALatticeCursor<ACell> ensureUserCursor(RequestContext ctx) {
		Users users = engine.getVenueState().users();
		User user = users.ensure(ctx.getCallerDID());
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
			return resolveDIDURL(ctx, pathStr.toString());
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
	 * Resolves a DID URL path like {@code "did:key:z6MkAlice/w/notes"} into
	 * a target cursor and namespace-relative path keys.
	 */
	private Object[] resolveDIDURL(RequestContext ctx, String rawPath) {
		DIDURL didURL = DIDURL.create(rawPath);
		AString targetDID = Strings.create(didURL.getDID().toString());

		// Parse the DID URL path component into namespace keys
		ACell[] pathKeys = parseStringPath(didURL.getPath());

		if (targetDID.equals(ctx.getCallerDID())) {
			// Own namespace with explicit DID — allowed
			return new Object[] { getUserCursor(ctx), pathKeys };
		}

		// Cross-user access — verify UCAN proofs
		// Build full DID URL for the requested resource
		String requestedResource = targetDID.toString() + buildSubPath(pathKeys);
		if (verifyProofs(ctx, requestedResource, "crud/read")) {
			Users users = engine.getVenueState().users();
			User targetUser = users.get(targetDID);
			if (targetUser != null) {
				return new Object[] { targetUser.cursor(), pathKeys };
			}
		}

		throw new RuntimeException("Access denied");
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

	/**
	 * Verifies that the request context contains a valid UCAN proof
	 * granting the caller the requested capability on the target DID's resource.
	 *
	 * <p>For each proof token in the context:</p>
	 * <ol>
	 *   <li>Parse as UCAN</li>
	 *   <li>Re-check temporal bounds (signature and chain are already verified
	 *       at transport ingress — see {@link UCANValidator#parseTransportUCANs})</li>
	 *   <li>Check audience matches caller</li>
	 *   <li>Check issuer matches the venue (Phase C1: venue is authority for
	 *       all hosted data)</li>
	 *   <li>Check attenuations cover the requested capability via {@link Capability#covers}</li>
	 * </ol>
	 *
	 * <p>Signature verification is <b>not</b> repeated here. The trust
	 * boundary is {@code RequestContext.withProofs(...)}; tokens reach it
	 * only through {@link UCANValidator#parseTransportUCANs} (for transport)
	 * or direct test fixtures. Re-verifying a JWT-origin token via
	 * {@link UCAN#verifySignature()} would fail anyway, because that method
	 * verifies the stored signature against CVM-encoded payload bytes while
	 * JWT signatures cover base64url JWT bytes.</p>
	 *
	 * @param requestedResource Full DID URL (e.g. "did:key:zAlice.../w/notes")
	 * @param requestedAbility Ability string (e.g. "crud/read")
	 */
	@SuppressWarnings("unchecked")
	private boolean verifyProofs(RequestContext ctx,
			String requestedResource, String requestedAbility) {
		AVector<ACell> proofs = ctx.getProofs();
		if (proofs == null || proofs.count() == 0) return false;

		long now = System.currentTimeMillis() / 1000;
		AString venueDID = engine.getDIDString();

		for (long i = 0; i < proofs.count(); i++) {
			if (!(proofs.get(i) instanceof AMap<?,?> tm)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> tokenMap = (AMap<AString, ACell>) tm;

			UCAN token = UCAN.parse(tokenMap);
			if (token == null) continue;

			// Re-check temporal bounds only. Signature and chain were
			// verified at the trust boundary (parseTransportUCANs).
			if (!UCANValidator.checkTemporalBounds(token, now)) continue;

			// Audience must match caller
			AString aud = token.getAudience();
			if (aud == null || !aud.equals(ctx.getCallerDID())) continue;

			// Phase C1: issuer must be the venue (authority for all hosted data).
			AString iss = token.getIssuer();
			if (iss == null || !iss.equals(venueDID)) continue;

			// Check attenuations cover the requested resource (full DID URL)
			AVector<ACell> atts = token.getCapabilities();
			for (long j = 0; j < atts.count(); j++) {
				if (!(atts.get(j) instanceof AMap<?,?> am)) continue;
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> att = (AMap<AString, ACell>) am;
				if (Capability.covers(att, requestedResource, requestedAbility)) {
					return true;
				}
			}
		}
		return false;
	}

}
