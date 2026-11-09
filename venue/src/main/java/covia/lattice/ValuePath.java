package covia.lattice;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.grid.HexId;

/**
 * Walks a value along a {@link Path}. Each segment is read by the value it
 * meets: a map key, a list position (a string of digits), or an Index key (the
 * string itself, or a Blob written in hex). A stored null counts as present.
 *
 * <p>Changes are pure functions of a value, so they can run inside one atomic
 * update. They never create a missing level: {@link #createPath} creates levels,
 * and only when a caller asks. Callers pass the stretch of their path that
 * names the value, and refusals name the path as the caller wrote it
 * ({@link CoviaPath#address}), since a slice keeps the request it came from.</p>
 */
public final class ValuePath {

	private ValuePath() {}

	/** Refusal for a change through a level that does not exist; names that level. */
	public static final String NO_LEVEL = "Nothing exists at '%s' to change below it";

	/** Refusal for a list position past the end; names the list, the index and its size. */
	public static final String INDEX_OUT_OF_RANGE = "List index out of bounds at '%s': %d (size %d)";

	/** Refusal for a list addressed by a segment that is not a position; names the list, its size and the segment. */
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

	/** The value at {@code path} below {@code root}, or null when absent. */
	public static ACell get(ACell root, Path path) {
		ACell current = root;
		for (int i = 0; i < path.count() && current != null; i++) {
			current = child(current, path.get(i));
		}
		return current;
	}

	/** Whether {@code path} names a value present in {@code root}; with no segments, whether root exists. */
	public static boolean contains(ACell root, Path path) {
		if (path.isEmpty()) return root != null;
		return containsKey(get(root, CoviaPath.parent(path)), path.get(path.count() - 1));
	}

	/** One level down: a map key, a list position or an Index key, or null when absent. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static ACell child(ACell value, AString segment) {
		if (value instanceof AVector<?> vec) {
			long idx = parseIndex(segment);
			return (idx >= 0 && idx < vec.count()) ? vec.get(idx) : null;
		}
		// Check Index before AMap, as Index is an AMap. A string is itself
		// blob-like, so try it directly (a string-keyed Index), then as hex for a
		// Blob-keyed one.
		if (value instanceof Index) {
			Index idx = (Index) value;
			ACell direct = (ACell) idx.get(segment);
			if (direct != null) return direct;
			Blob b = blobKey(segment);
			return (b != null) ? (ACell) idx.get(b) : null;
		}
		if (value instanceof AMap<?, ?> map) {
			return ((AMap<AString, ACell>) map).get(segment);
		}
		return null;
	}

	/** Whether {@code parent} has an entry for {@code segment}, even one holding null. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static boolean containsKey(ACell parent, AString segment) {
		if (parent instanceof AVector<?> vec) {
			long idx = parseIndex(segment);
			return idx >= 0 && idx < vec.count();
		}
		if (parent instanceof Index) {
			Index idx = (Index) parent;
			if (idx.containsKey(segment)) return true;
			Blob b = blobKey(segment);
			return b != null && idx.containsKey(b);
		}
		if (parent instanceof AMap<?, ?> map) {
			return ((AMap<ACell, ACell>) map).containsKey(segment);
		}
		return false;
	}

	/** The Blob id a segment writes in hex, such as a job's or a session's; null when it is not hex. */
	public static Blob blobKey(AString segment) {
		return HexId.parse(segment);
	}

	/** A segment as a non-negative list position, from a string of digits; -1 when it is not one. */
	public static long parseIndex(AString segment) {
		try {
			long idx = Long.parseLong(segment.toString());
			return (idx >= 0) ? idx : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// ========== Keys ==========

	/**
	 * The lattice keys a path goes through in a value.
	 *
	 * @param keys One key per segment of the path
	 * @param refusal Why a change cannot reach the end of the path, or null when it can
	 */
	public record Keys(ACell[] keys, String refusal) {}

	/**
	 * The lattice key each segment of {@code path} names in {@code root}, read by
	 * the value it meets as {@link #child} reads it: a list position, a map key, or
	 * an Index key, which is the Blob the segment writes in hex where only that is
	 * stored. A level that does not exist yet takes the segment itself, the key of
	 * the map {@link #createPath} makes there. A segment the value it meets cannot
	 * hold ({@link #keyRefusal}) ends the walk: it and the segments below it keep
	 * their strings, and the refusal says why.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Keys keys(ACell root, Path path) {
		ACell[] keys = new ACell[Math.toIntExact(path.count())];
		String refusal = null;
		ACell node = root;
		for (int i = 0; i < path.count(); i++) {
			AString segment = path.get(i);
			ACell key = segment;
			if (node != null) {
				refusal = keyRefusal(node, path, i);
				if (refusal == null) {
					if (node instanceof AVector<?>) {
						key = CVMLong.create(parseIndex(segment));
					} else if (node instanceof Index index && !index.containsKey(segment)) {
						Blob blob = blobKey(segment);
						if (blob != null && index.containsKey(blob)) key = blob;
					}
					node = ((ADataStructure<ACell>) node).get(key);
				} else {
					node = null;
				}
			}
			keys[i] = key;
		}
		return new Keys(keys, refusal);
	}

	/**
	 * Why {@code container}, the value segment {@code i} of {@code path} is read
	 * from, cannot hold that segment, or null when it can: a list holds only a
	 * position within it, and a value that is neither a list nor a map holds no keys.
	 *
	 * @param container A value, not null
	 */
	public static String keyRefusal(ACell container, Path path, int i) {
		if (container instanceof AVector<?> vec) {
			long idx = parseIndex(path.get(i));
			if (idx >= 0 && idx < vec.count()) return null;
			String at = at(path, i);
			if (idx >= 0) return String.format(INDEX_OUT_OF_RANGE, at, idx, vec.count());
			return String.format(NOT_AN_INDEX, at, vec.count(), path.get(i));
		}
		if (container instanceof AMap) return null;
		return notAMapReason(path, i);
	}

	// ========== Changes ==========

	/** {@code root} with {@code value} at {@code path}; every level above it must exist, and a list position must be in range. */
	public static ACell set(ACell root, Path path, ACell value) {
		return update(root, path, 0, previous -> value);
	}

	@SuppressWarnings("unchecked")
	private static ACell update(ACell root, Path path, int i, UnaryOperator<ACell> change) {
		if (i >= path.count()) return change.apply(root);
		if (root == null) {
			String at = at(path, i);
			throw new NotFoundException(String.format(NO_LEVEL, at), at);
		}
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = position(vec, path, i);
			return vec.assoc(idx, update(vec.get(idx), path, i + 1, change));
		}
		if (!(root instanceof AMap)) throw notAMap(path, i);
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		AString key = path.get(i);
		return map.assoc(key, update(map.get(key), path, i + 1, change));
	}

	/**
	 * {@code root} without the value at {@code path}. An absent value is left
	 * alone; a list element becomes null so later positions keep theirs.
	 */
	public static ACell remove(ACell root, Path path) {
		return path.isEmpty() ? null : remove(root, path, 0);
	}

	@SuppressWarnings("unchecked")
	private static ACell remove(ACell root, Path path, int i) {
		if (root == null) return null;
		AString segment = path.get(i);
		boolean leaf = (i == path.count() - 1);
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(segment);
			if (idx < 0 || idx >= vec.count()) return root;
			return leaf ? vec.assoc(idx, null) : vec.assoc(idx, remove(vec.get(idx), path, i + 1));
		}
		if (!(root instanceof AMap)) return root;
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		if (leaf) return map.dissoc(segment);
		if (!map.containsKey(segment)) return root;
		ACell child = map.get(segment);
		ACell replacement = remove(child, path, i + 1);
		return Utils.equals(child, replacement) ? root : map.assoc(segment, replacement);
	}

	/**
	 * {@code root} with a map at every level of {@code path} that holds nothing,
	 * or null. Existing maps and lists are kept; a list position must be in
	 * range, and a level holding any other value is refused.
	 *
	 * @param created Receives one count per level created
	 */
	public static ACell createPath(ACell root, Path path, int[] created) {
		return createPath(root, path, 0, created);
	}

	@SuppressWarnings("unchecked")
	private static ACell createPath(ACell root, Path path, int i, int[] created) {
		if (i >= path.count()) return root;
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = position(vec, path, i);
			return vec.assoc(idx, createPath(level(vec.get(idx), created), path, i + 1, created));
		}
		if (!(root instanceof AMap)) throw notAMap(path, i);
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		AString key = path.get(i);
		return map.assoc(key, createPath(level(map.get(key), created), path, i + 1, created));
	}

	private static ACell level(ACell existing, int[] created) {
		if (existing != null) return existing;
		created[0]++;
		return Maps.empty();
	}

	/** The list position segment {@code i} of {@code path} names in {@code vec}; refuses one out of range or not a position. */
	private static long position(AVector<ACell> vec, Path path, int i) {
		String refusal = keyRefusal(vec, path, i);
		if (refusal != null) throw new InvalidInputException(refusal);
		return parseIndex(path.get(i));
	}

	private static InvalidInputException notAMap(Path path, int i) {
		return new InvalidInputException(notAMapReason(path, i));
	}

	private static String notAMapReason(Path path, int i) {
		return String.format(NOT_A_MAP, path.get(i), at(path, i));
	}

	/** The address of the level segment {@code i} of {@code path} is read from, as the caller wrote it. */
	private static String at(Path path, int i) {
		return CoviaPath.address(path.slice(0, i));
	}

	/**
	 * A list with the element added. Null, or the empty map an atomic update
	 * receives in place of a missing path, starts a new list.
	 */
	@SuppressWarnings("unchecked")
	static ACell appendTo(ACell current, ACell element) {
		if (current == null) return Vectors.of(element);
		if (current instanceof AVector) return ((AVector<ACell>) current).conj(element);
		if (current instanceof AMap && ((AMap<?, ?>) current).isEmpty()) return Vectors.of(element);
		throw new InvalidInputException(String.format(NOT_A_LIST, current.getType()));
	}
}
