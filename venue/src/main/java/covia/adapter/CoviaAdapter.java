package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.ACountable;
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
 *   ├── g/          agents    (StringKeyedLattice → per-agent records)
 *   ├── s/          secrets   (StringKeyedLattice → encrypted values)
 *   └── j/          jobs      (IndexLattice → job records by Blob ID)
 * </pre>
 */
public class CoviaAdapter extends AAdapter {

	private static final AString K_KEYS   = Strings.intern("keys");
	private static final AString K_COUNT  = Strings.intern("count");
	private static final AString K_TYPE   = Strings.intern("type");
	private static final AString K_VALUE  = Strings.intern("value");
	private static final AString K_VALUES = Strings.intern("values");
	private static final AString K_EXISTS = Strings.intern("exists");

	@Override
	public String getName() {
		return "covia";
	}

	@Override
	public String getDescription() {
		return "Provides native access to internal services in this Covia venue, "
			+ "including lattice read/list for user data (agents, secrets, jobs).";
	}

	@Override
	protected void installAssets() {
		String BASE = "/adapters/covia/";
		installAsset(BASE + "read.json");
		installAsset(BASE + "list.json");
		installAsset(BASE + "functions.json");
		installAsset(BASE + "describe.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new RuntimeException("Authentication required"));
		}
		return switch (getSubOperation(meta)) {
			case "read" -> CompletableFuture.completedFuture(handleRead(ctx, input));
			case "list" -> CompletableFuture.completedFuture(handleList(ctx, input));
			case "functions" -> CompletableFuture.completedFuture(handleFunctions());
			case "describe" -> CompletableFuture.completedFuture(handleDescribe(input));
			default -> CompletableFuture.failedFuture(
				new RuntimeException("Unknown covia operation: " + getSubOperation(meta)));
		};
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
	 */
	private ACell handleRead(RequestContext ctx, ACell input) {
		ACell pathCell = RT.getIn(input, Fields.PATH);
		ACell[] jsonKeys = parsePath(pathCell);

		ALatticeCursor<ACell> cursor = getUserCursor(ctx);
		if (cursor == null) return result(null);
		if (jsonKeys.length == 0) return result(cursor.get());

		// Ask the lattice to translate JSON keys → CVM keys level by level.
		// Returns null if any key can't be resolved at its lattice level
		// (e.g. a KeyedLattice with fixed children rejects unknown keys).
		// A StringKeyedLattice accepts any string key, so resolvePath succeeds
		// even for non-existent data — the null check on the value handles that.
		ACell[] resolved = cursor.getLattice().resolvePath(jsonKeys);
		if (resolved == null) return result(null);

		return result(cursor.path(resolved).get());
	}

	/** Wraps a value with an exists flag. Null value → exists: false. */
	private static ACell result(ACell value) {
		return Maps.of(
			K_EXISTS, CVMBool.of(value != null),
			K_VALUE, value);
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
		ACell pathCell = RT.getIn(input, Fields.PATH);
		ACell[] jsonKeys = parsePath(pathCell);

		ALatticeCursor<ACell> cursor = getUserCursor(ctx);
		ACell value = null;

		if (cursor != null) {
			if (jsonKeys.length > 0) {
				ACell[] resolved = cursor.getLattice().resolvePath(jsonKeys);
				if (resolved != null) value = cursor.path(resolved).get();
			} else {
				value = cursor.get();
			}
		}

		return describeValue(value, input);
	}

	/**
	 * Builds a structured description of a CVM value for the list response.
	 */
	/**
	 * Builds a structured description of a CVM value for the list response.
	 *
	 * <p>Always includes {@code exists} (true if value is non-null) and
	 * {@code type}. Maps additionally include {@code keys} and {@code count};
	 * vectors/countables include {@code count}; sets include {@code values}.</p>
	 */
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
			// Vectors, sequences, Index — just type and count
			desc = Maps.of(K_TYPE, typeName, K_COUNT, CVMLong.create(countable.count()));
		} else {
			// Scalar — type name only
			desc = Maps.of(K_TYPE, typeName);
		}

		return desc.assoc(K_EXISTS, CVMBool.TRUE);
	}

	// ========== Function introspection ==========

	/**
	 * Lists all registered adapter functions with names, IDs, and descriptions.
	 */
	@SuppressWarnings("unchecked")
	private ACell handleFunctions() {
		Index<AString, Hash> ops = engine.getOperationRegistry();
		AVector<ACell> functions = Vectors.empty();
		for (long i = 0; i < ops.count(); i++) {
			var entry = ops.entryAt(i);
			AString name = entry.getKey();
			Hash hash = entry.getValue();
			Asset asset = engine.getAsset(hash);

			AMap<AString, ACell> func = Maps.of(
				Fields.NAME, name,
				Fields.ID, Strings.create(hash.toHexString()));
			if (asset != null) {
				ACell desc = asset.meta().get(Fields.DESCRIPTION);
				if (desc != null) func = func.assoc(Fields.DESCRIPTION, desc);
			}
			functions = (AVector<ACell>) functions.conj(func);
		}
		return Maps.of(Strings.intern("functions"), functions);
	}

	/**
	 * Gets full metadata for a named adapter function, including input/output schemas.
	 */
	private ACell handleDescribe(ACell input) {
		AString funcName = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (funcName == null) return Maps.of(Fields.ERROR, Strings.create("name is required"));

		Hash hash = engine.resolveOperation(funcName);
		if (hash == null) return Maps.of(Fields.ERROR, Strings.create("function not found: " + funcName));

		Asset asset = engine.getAsset(hash);
		if (asset == null) return Maps.of(Fields.ERROR, Strings.create("asset not found for: " + funcName));

		return asset.meta();
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
	static ACell[] parseStringPath(String path) {
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

}
