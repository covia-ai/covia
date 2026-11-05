package covia.lattice;

import java.util.function.UnaryOperator;

import convex.core.data.ABlob;
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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;

/**
 * Paths into a value, as callers name them. Each key is read by the value it
 * meets: a map key, a vector index (a number, or a string of one), or an Index
 * key (blob-like, or a string of hex). A stored null counts as present.
 *
 * <p>Changes are pure functions of a value, so they can run inside one atomic
 * update. They never create a missing level: {@link #createPath} creates levels,
 * and only when a caller asks. Refusals name the path as the caller wrote it,
 * so callers pass their whole key array and the range of it that names the
 * value, {@code keys[from, to)}; a form without {@code to} runs to the end.</p>
 */
public final class ValuePath {

	private ValuePath() {}

	/** Refusal for a change through a level that does not exist; names that level. */
	public static final String NO_LEVEL = "Nothing exists at '%s' to change below it";

	/** Refusal for a list position past the end; names the list, the index and its size. */
	public static final String INDEX_OUT_OF_RANGE = "List index out of bounds at '%s': %d (size %d)";

	/** Refusal for a list addressed by a key that is not a position; names the list, its size and the key. */
	public static final String NOT_AN_INDEX = "Cannot index the list at '%1$s' (size %2$d) with key '%3$s': "
		+ "lists use non-negative integer positions. To store named keys here, replace the whole node "
		+ "with a map first (write '%1$s' = {}).";

	/** Refusal for a key set on a value that is not a map; names the key and the value. */
	public static final String NOT_A_MAP = "Cannot set key '%1$s' on the scalar value at '%2$s': this path "
		+ "holds a value, not a map. To store named keys here, replace the whole node with a map first "
		+ "(write '%2$s' = {}).";

	/** Refusal for an append to a value that is not a list; names the value's type. */
	public static final String NOT_A_LIST = "Cannot append to non-vector value of type: %s";

	// ========== Reads ==========

	/** The value at {@code keys[from..]} below {@code root}, or null when absent. */
	public static ACell get(ACell root, ACell[] keys, int from) {
		return get(root, keys, from, keys.length);
	}

	/** The value at {@code keys[from, to)} below {@code root}, or null when absent. */
	public static ACell get(ACell root, ACell[] keys, int from, int to) {
		ACell current = root;
		for (int i = from; i < to && current != null; i++) {
			current = child(current, keys[i]);
		}
		return current;
	}

	/** Whether {@code keys[from..]} names a value present in {@code root}; with no keys, whether root exists. */
	public static boolean contains(ACell root, ACell[] keys, int from) {
		return contains(root, keys, from, keys.length);
	}

	/** Whether {@code keys[from, to)} names a value present in {@code root}; with no keys, whether root exists. */
	public static boolean contains(ACell root, ACell[] keys, int from, int to) {
		if (from >= to) return root != null;
		return containsKey(get(root, keys, from, to - 1), keys[to - 1]);
	}

	/** {@link #missingLevels(ACell, ACell[], int, int)} to the end of {@code keys}. */
	public static int missingLevels(ACell root, ACell[] keys, int from) {
		return missingLevels(root, keys, from, keys.length);
	}

	/**
	 * How many levels of {@code keys[from, to)} are absent from {@code root}: none
	 * when the last key has an entry, otherwise the levels from the first missing
	 * key down to the last. A write there, with its levels created, makes exactly these.
	 */
	public static int missingLevels(ACell root, ACell[] keys, int from, int to) {
		ACell node = root;
		for (int i = from; i < to; i++) {
			if (!containsKey(node, keys[i])) return to - i;
			node = child(node, keys[i]);
		}
		return 0;
	}

	/** One level down: a map key, a vector index or an Index key, or null when absent. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static ACell child(ACell value, ACell key) {
		if (value instanceof AVector<?> vec) {
			long idx = parseIndex(key);
			return (idx >= 0 && idx < vec.count()) ? vec.get(idx) : null;
		}
		// Index keys are blob-like. Check Index before AMap, as Index is an AMap.
		// An AString is itself blob-like, so try the key directly (a string-keyed
		// Index), then as hex for a Blob-keyed one.
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
		if (value instanceof AMap<?, ?> map) {
			AString strKey = RT.ensureString(key);
			return (strKey != null) ? ((AMap<AString, ACell>) map).get(strKey) : null;
		}
		return null;
	}

	/** Whether {@code parent} has an entry for {@code key}, even one holding null. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static boolean containsKey(ACell parent, ACell key) {
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
		if (parent instanceof AMap<?, ?> map) {
			AString sk = RT.ensureString(key);
			return sk != null && ((AMap<ACell, ACell>) map).containsKey(sk);
		}
		return false;
	}

	/** The Blob id a key names, such as a job's or a session's, given as a Blob or in hex; null when it names none. */
	public static Blob blobKey(ACell key) {
		if (key instanceof AString s) return Blob.parse(s.toString());
		return (key instanceof ABlob b) ? b.toFlatBlob() : null;
	}

	/** A key as a non-negative list position, from a number or a string of digits; -1 when it is not one. */
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

	// ========== Changes ==========

	/** {@link #set(ACell, ACell[], int, int, ACell)} to the end of {@code keys}. */
	public static ACell set(ACell root, ACell[] keys, int from, ACell value) {
		return set(root, keys, from, keys.length, value);
	}

	/** {@code root} with {@code value} at {@code keys[from, to)}; every level above it must exist. */
	public static ACell set(ACell root, ACell[] keys, int from, int to, ACell value) {
		return update(root, keys, from, to, previous -> value);
	}

	/** {@link #update(ACell, ACell[], int, int, UnaryOperator)} to the end of {@code keys}. */
	public static ACell update(ACell root, ACell[] keys, int from, UnaryOperator<ACell> change) {
		return update(root, keys, from, keys.length, change);
	}

	/**
	 * {@code root} with the value at {@code keys[from, to)} replaced by {@code change}
	 * applied to it (null when absent). Every level above it must exist; a list
	 * position must be in range.
	 */
	@SuppressWarnings("unchecked")
	public static ACell update(ACell root, ACell[] keys, int from, int to, UnaryOperator<ACell> change) {
		if (from >= to) return change.apply(root);
		ACell key = keys[from];
		if (root == null) throw new NotFoundException(String.format(NO_LEVEL, at(keys, from)), at(keys, from));
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = position(vec, keys, from);
			return vec.assoc(idx, update(vec.get(idx), keys, from + 1, to, change));
		}
		if (!(root instanceof AMap)) throw notAMap(keys, from);
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		AString mapKey = RT.ensureString(key);
		return map.assoc(mapKey, update(map.get(mapKey), keys, from + 1, to, change));
	}

	/** {@link #append(ACell, ACell[], int, int, ACell)} to the end of {@code keys}. */
	public static ACell append(ACell root, ACell[] keys, int from, ACell element) {
		return append(root, keys, from, keys.length, element);
	}

	/** {@code root} with {@code element} added to the list at {@code keys[from, to)}, which is created when absent. */
	public static ACell append(ACell root, ACell[] keys, int from, int to, ACell element) {
		return update(root, keys, from, to, current -> appendTo(current, element));
	}

	/** {@link #remove(ACell, ACell[], int, int)} to the end of {@code keys}. */
	public static ACell remove(ACell root, ACell[] keys, int from) {
		return remove(root, keys, from, keys.length);
	}

	/**
	 * {@code root} without the value at {@code keys[from, to)}. An absent value is
	 * left alone; a list element becomes null so later positions keep theirs.
	 */
	@SuppressWarnings("unchecked")
	public static ACell remove(ACell root, ACell[] keys, int from, int to) {
		if (from >= to) return null;
		if (root == null) return null;
		ACell key = keys[from];
		boolean leaf = (from == to - 1);
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(key);
			if (idx < 0 || idx >= vec.count()) return root;
			return leaf ? vec.assoc(idx, null) : vec.assoc(idx, remove(vec.get(idx), keys, from + 1, to));
		}
		if (!(root instanceof AMap)) return root;
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		AString mapKey = RT.ensureString(key);
		if (leaf) return map.dissoc(mapKey);
		if (!map.containsKey(mapKey)) return root;
		ACell child = map.get(mapKey);
		ACell replacement = remove(child, keys, from + 1, to);
		return Utils.equals(child, replacement) ? root : map.assoc(mapKey, replacement);
	}

	/** {@link #createPath(ACell, ACell[], int, int, int[])} to the end of {@code keys}. */
	public static ACell createPath(ACell root, ACell[] keys, int from, int[] created) {
		return createPath(root, keys, from, keys.length, created);
	}

	/**
	 * {@code root} with a map at every level of {@code keys[from, to)} that holds
	 * nothing, or null. Existing maps and lists are kept; a list position must be
	 * in range, and a level holding any other value is refused.
	 *
	 * @param created Receives one count per level created
	 */
	@SuppressWarnings("unchecked")
	public static ACell createPath(ACell root, ACell[] keys, int from, int to, int[] created) {
		if (from >= to) return root;
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = position(vec, keys, from);
			return vec.assoc(idx, createPath(level(vec.get(idx), created), keys, from + 1, to, created));
		}
		if (!(root instanceof AMap)) throw notAMap(keys, from);
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		AString mapKey = RT.ensureString(keys[from]);
		return map.assoc(mapKey, createPath(level(map.get(mapKey), created), keys, from + 1, to, created));
	}

	private static ACell level(ACell existing, int[] created) {
		if (existing != null) return existing;
		created[0]++;
		return Maps.empty();
	}

	/** The list position {@code keys[from]} names in {@code vec}; refuses one out of range or not a position. */
	private static long position(AVector<ACell> vec, ACell[] keys, int from) {
		long idx = parseIndex(keys[from]);
		if (idx >= 0 && idx < vec.count()) return idx;
		String at = at(keys, from);
		if (idx >= 0) throw new InvalidInputException(String.format(INDEX_OUT_OF_RANGE, at, idx, vec.count()));
		throw new InvalidInputException(String.format(NOT_AN_INDEX, at, vec.count(), keys[from]));
	}

	private static InvalidInputException notAMap(ACell[] keys, int from) {
		return new InvalidInputException(String.format(NOT_A_MAP, keys[from], at(keys, from)));
	}

	/**
	 * A list with the element added. Null, or the empty map an atomic update
	 * receives in place of a missing path, starts a new list.
	 */
	@SuppressWarnings("unchecked")
	private static ACell appendTo(ACell current, ACell element) {
		if (current == null) return Vectors.of(element);
		if (current instanceof AVector) return ((AVector<ACell>) current).conj(element);
		if (current instanceof AMap && ((AMap<?, ?>) current).isEmpty()) return Vectors.of(element);
		throw new InvalidInputException(String.format(NOT_A_LIST, current.getType()));
	}

	// ========== Path strings ==========

	/** The path to the level at {@code keys[from]}, as the caller wrote it. */
	private static String at(ACell[] keys, int from) {
		return format(keys, 0, from);
	}

	/** Splits a slash-separated path into string keys, keeping each as written. */
	public static ACell[] parse(String path) {
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

	/** Joins keys into a path with a leading slash, such as {@code /w/notes}; {@code /} for none. */
	public static String format(ACell[] keys) {
		return format(keys, 0, keys.length);
	}

	/** Joins {@code keys[from, to)} into a path with a leading slash; {@code /} for none. */
	public static String format(ACell[] keys, int from, int to) {
		if (from >= to) return "/";
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; i++) {
			sb.append('/').append(keys[i]);
		}
		return sb.toString();
	}
}
