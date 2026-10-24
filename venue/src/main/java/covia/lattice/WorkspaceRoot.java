package covia.lattice;

import java.util.Arrays;
import java.util.function.UnaryOperator;
import convex.core.data.ACell;
import convex.core.data.ABlobLike;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeOps;
import convex.lattice.TransactionalComponent;
import convex.lattice.generic.JSONLattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;

/**
 * Path operations and transactions over an already-authorised workspace.
 * The owning component supplies its clock and namespace layout. Callers use the
 * same reads and mutations on live and transaction views; this object preserves
 * record envelopes, stored nulls, and map/vector navigation semantics.
 * External requests must pass authority checks before obtaining this view.
 */
public record WorkspaceRoot(AForkableCursor<ACell> cursor, LatticeContext context,
		boolean userNamespaces) implements TransactionalComponent<ACell, WorkspaceRoot> {

	@Override
	public AForkableCursor<ACell> storage() { return cursor; }

	@Override
	public WorkspaceRoot withCursor(AForkableCursor<ACell> draft) {
		return new WorkspaceRoot(draft, context, userNamespaces);
	}

	public AForkableCursor<ACell> child(ACell key) {
		AForkableCursor<ACell> child = cursor.withDefault(convex.core.data.Maps.empty()).path(key);
		if (!userNamespaces || !(Namespace.W.equals(key) || Namespace.O.equals(key) || Namespace.H.equals(key))) {
			return child;
		}
		return StampedCursor.create(child, context::currentTimestamp, Covia::stampUpdated)
				.withDefault(convex.core.data.Maps.empty()).path(Covia.WORKSPACE_DATA);
	}
	/**
	 * The namespace container a path addresses: a cursor at its caller-visible
	 * value, plus the index of the first path key below it.
	 *
	 * <p>Every resolved path — physical ({@code w/notes}), cross-user, or virtual
	 * ({@code t/x} → the job record's {@code temp}) — has the shape
	 * {@code [container, ...rest]} against its base cursor, so one rule serves all
	 * of them. {@link WorkspaceRoot#child} crosses the {@code {updated, data}} view
	 * boundary of a {@link covia.lattice.Covia#WORKSPACE_RECORD} region, so {@code data}
	 * is never addressed by callers or by this class.</p>
	 */
	private record Target(AForkableCursor<ACell> root, int from) {
		static Target of(WorkspaceRoot cursor, ACell[] keys) {
			return (keys.length == 0)
				? new Target(cursor.cursor(), 0)
				: new Target(cursor.child(keys[0]), 1);
		}
	}

	/**
	 * Atomically changes a path within this already-authorised workspace.
	 * The callback may retry; it receives the namespace value and the first
	 * remaining path index. Scoped scratch must require its parent record.
	 */
	public void update(ACell[] keys,
			boolean requireExistingRecord, PathUpdate update) {
		if (requireExistingRecord) {
			// The precondition and mutation must observe the same base value. A
			// separate get() followed by a descendant update permits a concurrent
			// delete to win between them, after which structural JSON navigation
			// would auto-vivify the missing record again.
			cursor().updateAndGet(base -> {
				if (base == null) {
					String container = (keys.length == 0) ? "root" : keys[0].toString();
					throw new IllegalArgumentException(
						"Scoped path is not available: " + container + " has no parent record");
				}
				if (keys.length == 0) return update.apply(base, 0);

				ACell current = navigateInto(base, keys[0]);
				ACell replacement = update.apply(current, 1);
				if (Utils.equals(current, replacement)) return base;
				return LatticeOps.assocIn(base, replacement, JSONLattice.INSTANCE, keys[0]);
			});
			return;
		}

		Target target = Target.of(this, keys);
		target.root().updateAndGet(current -> update.apply(current, target.from()));
	}


	/** Writes a path atomically, preserving namespace stamps and value shapes. */
	public void write(ACell[] keys, ACell value) {
		if (keys == null || keys.length == 0) {
			throw new IllegalArgumentException("A non-empty cursor-relative path is required");
		}
		update(keys, false,
			(current, from) -> deepSet(current, keys, from, value));
	}

	/** Deletes a path atomically; stored null counts as present. */
	public boolean delete(ACell... keys) {
		if (keys == null || keys.length == 0) {
			throw new IllegalArgumentException("A non-empty cursor-relative path is required");
		}
		if (!contains(keys)) return false;
		final boolean[] deleted = {false};
		update(keys, false, (current, from) -> {
			deleted[0] = leafExisted(current, keys, from);
			return deepDelete(current, keys, from);
		});
		return deleted[0];
	}

	// ========== Type-aware deep navigation ==========
	//
	// These helpers navigate into opaque CVM values (maps AND vectors/sequences)
	// beyond the lattice boundary. Path keys are interpreted by the value type:
	//   - AMap:     string key lookup
	//   - AVector:  integer index (parsed from string or CVMLong)
	//   - other:    navigation fails (null for reads, error for writes)

	/**
	 * Reads a value at a path by navigating to its {@link Target} container and
	 * then into that value with type-aware deep navigation — handles maps, vector
	 * indices (e.g. {@code "w/events/0"}), and Index keys.
	 */
	public ACell read(ACell... jsonKeys) {
		Target target = Target.of(this, jsonKeys);
		return deepGet(target.root().get(), jsonKeys, target.from());
	}

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
	@SuppressWarnings("unchecked")
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
			return vec.assoc(idx, deepUpdate(vec.get(idx), keys, fromIndex + 1, update));
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
	public static ACell deepAppend(ACell root, ACell[] keys, int fromIndex, ACell element) {
		return deepUpdate(root, keys, fromIndex, current -> appendToVector(current, element));
	}
	/**
	 * True if the leaf key of {@code keys} is present in its parent under the
	 * cursor's (namespace-unwrapped) value — so a stored null value (key present,
	 * value nil) is distinguished from an absent path. {@code keys.length == 0}
	 * means the whole namespace, present iff its value is non-null.
	 */
	public boolean contains(ACell... keys) {
		Target t = Target.of(this, keys);
		ACell root = t.root().get();
		if (keys.length <= t.from()) return root != null;
		ACell parent = deepGet(root, Arrays.copyOf(keys, keys.length - 1), t.from());
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/** As {@link #contains} but against a plain value already in hand. */
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

	/**
	 * A positional update applied at a write target: receives the current
	 * value at the navigation root and the index in the path keys where that
	 * root sits, and returns the replacement value.
	 */
	@FunctionalInterface
	public interface PathUpdate {
		ACell apply(ACell current, int fromIndex);
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

	/**
	 * Builds a sub-path string from parsed path keys (e.g. ["w", "notes"] → "/w/notes").
	 */
	public static String buildSubPath(ACell[] pathKeys) {
		if (pathKeys.length == 0) return "/";
		StringBuilder sb = new StringBuilder();
		for (ACell key : pathKeys) {
			sb.append('/').append(key);
		}
		return sb.toString();
	}

}
