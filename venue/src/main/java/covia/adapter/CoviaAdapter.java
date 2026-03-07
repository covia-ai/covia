package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.ACountable;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALattice;
import convex.lattice.cursor.ALatticeCursor;
import covia.api.Fields;
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

	private static final AString K_KEYS  = Strings.intern("keys");
	private static final AString K_COUNT = Strings.intern("count");

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
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new RuntimeException("Authentication required"));
		}
		return switch (getSubOperation(meta)) {
			case "read" -> CompletableFuture.completedFuture(handleRead(ctx, input));
			case "list" -> CompletableFuture.completedFuture(handleList(ctx, input));
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
	 */
	private ACell handleRead(RequestContext ctx, ACell input) {
		ACell pathCell = RT.getIn(input, Fields.PATH);
		ACell[] jsonKeys = parsePath(pathCell);

		ALatticeCursor<ACell> cursor = getUserCursor(ctx);
		if (cursor == null) return null;
		if (jsonKeys.length == 0) return cursor.get();

		// Ask the lattice to translate JSON keys → CVM keys level by level.
		// Returns null if any key can't be resolved at its lattice level.
		ACell[] resolved = cursor.getLattice().resolvePath(jsonKeys);
		if (resolved == null) return null;

		return cursor.path(resolved).get();
	}

	/**
	 * Lists the keys at a path in the user's lattice, with optional pagination.
	 *
	 * <p>Navigates to the target value (same resolution as read), then extracts
	 * its keys. Map keys are converted back to JSON-friendly form via
	 * {@link ALattice#toJSONKey} (Keywords → strings, Blobs → hex strings).
	 * Countable values (vectors, sequences) return integer indices.</p>
	 *
	 * @return {@code {keys: [...], count: N}} where count is the total before pagination
	 */
	private ACell handleList(RequestContext ctx, ACell input) {
		ACell pathCell = RT.getIn(input, Fields.PATH);
		ACell[] jsonKeys = parsePath(pathCell);

		ALatticeCursor<ACell> cursor = getUserCursor(ctx);
		if (cursor == null) return emptyList();

		ACell value;
		if (jsonKeys.length > 0) {
			ACell[] resolved = cursor.getLattice().resolvePath(jsonKeys);
			if (resolved == null) return emptyList();
			value = cursor.path(resolved).get();
		} else {
			value = cursor.get();
		}
		if (value == null) return emptyList();

		AVector<ACell> allKeys = extractKeys(value);
		long total = allKeys.count();

		// Pagination defaults: offset 0, limit 1000
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

		return Maps.of(K_KEYS, page, K_COUNT, CVMLong.create(total));
	}

	private static ACell emptyList() {
		return Maps.of(K_KEYS, Vectors.empty(), K_COUNT, CVMLong.ZERO);
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

	/**
	 * Extracts keys from a CVM data structure, converting to JSON-friendly form.
	 *
	 * <p>For maps (AMap, Index): returns the map keys, each converted via
	 * {@link ALattice#toJSONKey} — Keywords become name strings, Blobs become
	 * hex strings, AStrings and integers pass through unchanged.</p>
	 *
	 * <p>For countable values (vectors, sequences): returns integer indices
	 * {@code [0, 1, 2, ...]}.</p>
	 */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> extractKeys(ACell value) {
		if (value instanceof AMap<?,?> map) {
			AVector<ACell> keys = Vectors.empty();
			for (var entry : ((AMap<ACell, ACell>) map).entrySet()) {
				keys = keys.conj(ALattice.toJSONKey(entry.getKey()));
			}
			return keys;
		}
		if (value instanceof ACountable<?> countable) {
			long n = countable.count();
			AVector<ACell> keys = Vectors.empty();
			for (long i = 0; i < n; i++) {
				keys = keys.conj(CVMLong.create(i));
			}
			return keys;
		}
		return Vectors.empty();
	}
}
