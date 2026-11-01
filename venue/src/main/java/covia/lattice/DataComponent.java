package covia.lattice;

import java.util.Arrays;
import java.util.function.UnaryOperator;

import convex.auth.ucan.Capability;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.venue.RequestContext;
import covia.venue.VenueContext;

/**
 * Data at a position no other component claims. Maps, vectors and Indexes are
 * navigated by value type rather than by cursor, so the position is a cursor
 * at the nearest container plus the keys below it.
 *
 * <p>The component above decides whether its data can be edited, and how: it
 * supplies {@link Edits}, which apply one change to the container atomically.
 * Data without edits is read-only. Each operation makes its checks inside that
 * one atomic update, so they always match the value it changes.</p>
 */
public final class DataComponent extends ACoviaComponent<ACell> {

	/** Refusal for an edit to data its component does not let callers edit. */
	public static final String READ_ONLY = "This data is read-only";

	/**
	 * A change at an edit's starting value: receives that value and the index
	 * of the first path key below it, and returns the replacement. It may run
	 * again on a retry, so it must be pure.
	 */
	@FunctionalInterface
	public interface PathUpdate {
		ACell apply(ACell current, int fromIndex);
	}

	/** How the component above lets callers edit its data. */
	@FunctionalInterface
	public interface Edits {
		/** Applies the change in one atomic update. */
		void apply(PathUpdate change);

		/** Refuses when nothing here can be edited now, such as scoped data whose record is missing. */
		default void requireAvailable() {}
	}

	private final VenueContext context;
	private final AString address;
	private final ACell[] keys;
	private final int from;
	private final Edits edits;

	/** Read-only data at {@code keys[from..]} below the container. */
	DataComponent(AForkableCursor<ACell> container, ACell[] keys, int from) {
		this(null, null, container, keys, from, null);
	}

	/**
	 * Data at {@code keys[from..]} below the container.
	 *
	 * @param context Policy for the access checks the operations make
	 * @param address The address callers name this data by, from which access
	 *        to create its missing levels is checked
	 * @param container Cursor at the container's value
	 * @param keys Path keys; the container sits at {@code from}
	 * @param from Index of the first key below the container
	 * @param edits How this data is edited, or null when it is read-only
	 */
	public DataComponent(VenueContext context, AString address, AForkableCursor<ACell> container,
			ACell[] keys, int from, Edits edits) {
		super(container);
		this.context = context;
		this.address = address;
		this.keys = keys;
		this.from = from;
		this.edits = edits;
	}

	@Override
	public ACoviaComponent<?> resolve(ACell[] more, int at) {
		if (at >= more.length) return this;
		ACell[] rest = Arrays.copyOfRange(more, at, more.length);
		ACell[] joined = Arrays.copyOf(keys, keys.length + rest.length);
		System.arraycopy(rest, 0, joined, keys.length, rest.length);
		AString below = (address == null) ? null
			: Strings.create(address + WorkspaceRoot.buildSubPath(rest));
		return new DataComponent(context, below, cursor(), joined, from, edits);
	}

	@Override
	public ACell read() {
		return deepGet(cursor().get(), keys, from);
	}

	/** Whether a value is here; a stored null counts as present. */
	public boolean contains() {
		ACell root = cursor().get();
		if (keys.length <= from) return root != null;
		ACell parent = deepGet(root, Arrays.copyOf(keys, keys.length - 1), from);
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/**
	 * Writes the value here, building missing levels as maps.
	 *
	 * @return {@code {existed}}, with {@code pathCreated: true} when a missing
	 *         parent below the namespace was built
	 */
	public AMap<AString, ACell> write(RequestContext ctx, ACell value) {
		boolean[] existed = {false};
		boolean[] built = {false};
		edit((current, at) -> {
			requireCreateAccess(ctx, current, at);
			existed[0] = leafExisted(current, keys, at);
			ACell next = deepSet(current, keys, at, value);
			built[0] = builtParent(current, at);
			return next;
		});
		return withPathCreated(Maps.of(Fields.EXISTED, CVMBool.of(existed[0])), built[0]);
	}

	/**
	 * Appends the element to the vector here, creating the vector when absent.
	 *
	 * @return {@code {existed, index, newSize}}, with {@code pathCreated: true}
	 *         when a missing parent below the namespace was built
	 */
	public AMap<AString, ACell> append(RequestContext ctx, ACell element) {
		boolean[] existed = {false};
		boolean[] built = {false};
		long[] size = {0};
		edit((current, at) -> {
			requireCreateAccess(ctx, current, at);
			existed[0] = leafExisted(current, keys, at);
			ACell next = deepAppend(current, keys, at, element);
			built[0] = builtParent(current, at);
			ACell leaf = deepGet(next, keys, at);
			size[0] = (leaf instanceof AVector<?> vector) ? vector.count() : 0;
			return next;
		});
		return withPathCreated(Maps.of(
			Fields.EXISTED, CVMBool.of(existed[0]),
			Fields.INDEX, CVMLong.create(Math.max(0, size[0] - 1)),
			Fields.NEW_SIZE, CVMLong.create(size[0])), built[0]);
	}

	/**
	 * Removes the value here. An absent value is left alone, and the parents of
	 * a removed one stay even when emptied.
	 *
	 * @return {@code {deleted}}: whether a value was removed
	 */
	public AMap<AString, ACell> delete() {
		Edits edits = requireEdits();
		boolean[] deleted = {false};
		if (contains()) {
			edit((current, at) -> {
				deleted[0] = leafExisted(current, keys, at);
				return deepDelete(current, keys, at);
			});
		} else {
			// Nothing to remove: linearise as a no-op rather than writing an
			// unchanged value back through cursors that would create containers.
			wrapInput(edits::requireAvailable);
		}
		return Maps.of(Fields.DELETED, CVMBool.of(deleted[0]));
	}

	/**
	 * Replaces the value here with {@code change} applied to it, in one atomic
	 * update. {@code change} receives the current value (null when absent) and
	 * may run again on a retry, so it must be pure.
	 */
	public void update(RequestContext ctx, UnaryOperator<ACell> change) {
		edit((current, at) -> {
			requireCreateAccess(ctx, current, at);
			return deepSet(current, keys, at, change.apply(deepGet(current, keys, at)));
		});
	}

	private Edits requireEdits() {
		if (edits == null) throw new AuthException(READ_ONLY);
		return edits;
	}

	/** Applies a change through the edits; a path that does not fit the data is the caller's input. */
	private void edit(PathUpdate change) {
		Edits edits = requireEdits();
		wrapInput(() -> edits.apply(change));
	}

	private static void wrapInput(Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(e.getMessage(), e);
		}
	}

	/**
	 * Creating a key changes the map that holds it, so a change that creates
	 * levels of its path is also authorised at the nearest level that exists.
	 */
	private void requireCreateAccess(RequestContext ctx, ACell current, int at) {
		int missing = missingLevels(current, keys, at);
		if (missing == 0) return;
		context.requireAccess(ctx, ancestor(address, missing), Capability.CRUD_WRITE);
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

	/**
	 * Whether a change built a missing parent below the namespace. Creating the
	 * namespace and its direct child is not hierarchy, so only paths deeper than
	 * that can report it.
	 */
	private boolean builtParent(ACell current, int at) {
		return keys.length > 2 && !parentExisted(current, keys, at);
	}

	private static AMap<AString, ACell> withPathCreated(AMap<AString, ACell> result, boolean built) {
		return built ? result.assoc(Fields.PATH_CREATED, CVMBool.TRUE) : result;
	}

	// ========== Type-aware navigation ==========
	//
	// These helpers navigate into opaque CVM values beyond the lattice boundary.
	// Path keys are interpreted by the value type:
	//   - AMap:     string key lookup
	//   - Index:    blob-like key, or a string parsed as hex
	//   - AVector:  integer index (parsed from string or CVMLong)
	//   - other:    navigation fails (null for reads, error for writes)

	/**
	 * Type-aware deep get: navigates into a CVM value following the path,
	 * interpreting keys as map keys or vector indices depending on the value type.
	 */
	public static ACell deepGet(ACell root, ACell[] keys, int fromIndex) {
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
	public static boolean parentExisted(ACell root, ACell[] keys, int from) {
		if (keys.length - from <= 1) return true;
		ACell node = root;
		for (int i = from; i < keys.length - 1 && node != null; i++) {
			node = navigateInto(node, keys[i]);
		}
		return node != null;
	}

	/**
	 * How many levels of the path {@code keys[from..]} are absent from
	 * {@code root}: none when the leaf has an entry, otherwise the levels from
	 * the first missing key down to the leaf. A write at the path creates
	 * exactly these.
	 */
	public static int missingLevels(ACell root, ACell[] keys, int from) {
		ACell node = root;
		for (int i = from; i < keys.length; i++) {
			if (!containsLeaf(node, keys[i])) return keys.length - i;
			node = navigateInto(node, keys[i]);
		}
		return 0;
	}

	/**
	 * True if the leaf key ({@code keys[length-1]}) already had an entry in
	 * {@code root} before a mutation — the pre-state that distinguishes a
	 * <em>created</em> write from a <em>replaced</em> one, and a real delete from a
	 * no-op. Independent of whether the value there was null (a stored null still
	 * counts as present). Called with the same {@code (current, keys, from)} the
	 * write/delete lambda receives, so the namespace wrapper is already unwrapped.
	 */
	public static boolean leafExisted(ACell root, ACell[] keys, int from) {
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
	static ACell navigateInto(ACell value, ACell key) {
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
	public static long parseIndex(ACell key) {
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
	public static ACell deepSet(ACell root, ACell[] keys, int fromIndex, ACell value) {
		return deepUpdate(root, keys, fromIndex, previous -> value);
	}

	/** Shared map/vector traversal with the workspace's explicit construction policy. */
	@SuppressWarnings("unchecked")
	private static ACell deepUpdate(ACell root, ACell[] keys, int fromIndex,
			UnaryOperator<ACell> update) {
		if (fromIndex >= keys.length) return update.apply(root);
		ACell key = keys[fromIndex];

		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(key);
			if (idx < 0 || idx >= vec.count()) {
				String at = WorkspaceRoot.buildSubPath(Arrays.copyOfRange(keys, 0, fromIndex));
				if (idx >= 0) {   // numeric, just out of range
					throw new IllegalArgumentException(
						"List index out of bounds at '" + at + "': " + idx + " (size " + vec.count() + ")");
				}
				throw new IllegalArgumentException(   // non-index key on a list = shape conflict
					"Cannot index the list at '" + at + "' (size " + vec.count() + ") with key '" + key
					+ "': lists use non-negative integer positions. To store named keys here, replace "
					+ "the whole node with a map first (write '" + at + "' = {}).");
			}
			return vec.assoc(idx, deepUpdate(vec.get(idx), keys, fromIndex + 1, update));
		}

		// Navigating into an existing non-map, non-vector value is a shape
		// conflict — the scalar counterpart of the list case above. Only a
		// genuinely absent (null) intermediate is auto-vivified into a map;
		// a real value must never be silently clobbered.
		if (root != null && !(root instanceof AMap)) {
			String at = WorkspaceRoot.buildSubPath(Arrays.copyOfRange(keys, 0, fromIndex));
			throw new IllegalArgumentException(
				"Cannot set key '" + key + "' on the scalar value at '" + at
				+ "': this path holds a value, not a map. To store named keys here, replace "
				+ "the whole node with a map first (write '" + at + "' = {}).");
		}
		// Default: map navigation, creating intermediate maps as needed
		AMap<AString, ACell> map = (root instanceof AMap)
			? (AMap<AString, ACell>) root : Maps.empty();
		AString mapKey = RT.ensureString(key);
		return map.assoc(mapKey, deepUpdate(map.get(mapKey), keys, fromIndex + 1, update));
	}

	/**
	 * Deep delete: navigates into a CVM value by path and removes the leaf.
	 * For maps, dissociates the key. For vectors, sets the element to null
	 * (preserving index stability).
	 */
	@SuppressWarnings("unchecked")
	public static ACell deepDelete(ACell root, ACell[] keys, int fromIndex) {
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
		if (!map.containsKey(mapKey)) return root;
		ACell child = map.get(mapKey);
		ACell replacement = deepDelete(child, keys, fromIndex + 1);
		return Utils.equals(child, replacement) ? root : map.assoc(mapKey, replacement);
	}

	/**
	 * Appends an element to a vector, creating a new vector if the current
	 * value is null or an empty container (the latter occurs when an
	 * atomic update lambda receives the value lattice's zero in place of
	 * a missing path — see {@link convex.lattice.cursor.PathCursor}).
	 *
	 * @throws InvalidInputException if current value is non-empty and non-vector
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
		throw new InvalidInputException(
			"Cannot append to non-vector value of type: " + current.getType());
	}

	/**
	 * Deep append: navigates into a CVM value by path and appends an element
	 * to the vector at the leaf. Type-aware — handles both map and vector
	 * navigation, creating intermediate maps as needed.
	 */
	public static ACell deepAppend(ACell root, ACell[] keys, int fromIndex, ACell element) {
		return deepUpdate(root, keys, fromIndex, current -> appendToVector(current, element));
	}

	/**
	 * True if the leaf key of {@code keys} is present in its parent within
	 * {@code root}, so a stored null value (key present, value nil) is
	 * distinguished from an absent path.
	 */
	public static boolean leafPresentIn(ACell root, ACell[] keys) {
		if (keys.length == 0) return root != null;
		ACell parent = deepGet(root, Arrays.copyOf(keys, keys.length - 1), 0);
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/**
	 * True if {@code parent} contains {@code key} — a map/Index key or a vector
	 * index — independent of whether the value stored there is null. Mirrors the
	 * key resolution of {@link #navigateInto}.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static boolean containsLeaf(ACell parent, ACell key) {
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
}
