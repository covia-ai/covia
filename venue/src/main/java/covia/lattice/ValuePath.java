package covia.lattice;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.Utils;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;

/**
 * Covia paths into a value. A path is a sequence of string segments, as callers
 * write it; each segment is read by the value it meets: a map key, a list
 * position (a string of digits), or an Index key (the string itself, or a Blob
 * written in hex). A stored null counts as present.
 *
 * <p>A path is made here and nowhere else: {@link #parse} splits a string on
 * {@code /}, and {@link #of} takes a caller's string or array. No segment
 * contains {@code /}, so a path and the string {@link #format} gives for it
 * name the same place, and authority checked on that string covers exactly
 * what the path reaches.</p>
 *
 * <p>Changes are pure functions of a value, so they can run inside one atomic
 * update. They never create a missing level: {@link #createPath} creates levels,
 * and only when a caller asks. Refusals name the path as the caller wrote it,
 * so callers pass their whole path and the range of it that names the value,
 * {@code path[from, to)}; a form without {@code to} runs to the end.</p>
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

	/** {@link #get(ACell, AString[], int, int)} to the end of {@code path}. */
	public static ACell get(ACell root, AString[] path, int from) {
		return get(root, path, from, path.length);
	}

	/** The value at {@code path[from, to)} below {@code root}, or null when absent. */
	public static ACell get(ACell root, AString[] path, int from, int to) {
		ACell current = root;
		for (int i = from; i < to && current != null; i++) {
			current = child(current, path[i]);
		}
		return current;
	}

	/** {@link #contains(ACell, AString[], int, int)} to the end of {@code path}. */
	public static boolean contains(ACell root, AString[] path, int from) {
		return contains(root, path, from, path.length);
	}

	/** Whether {@code path[from, to)} names a value present in {@code root}; with no segments, whether root exists. */
	public static boolean contains(ACell root, AString[] path, int from, int to) {
		if (from >= to) return root != null;
		return containsKey(get(root, path, from, to - 1), path[to - 1]);
	}

	/**
	 * How many levels of {@code path[from, to)} are absent from {@code root}: none
	 * when the last segment has an entry, otherwise the levels from the first
	 * missing segment down to the last. A write there, with its levels created,
	 * makes exactly these.
	 */
	public static int missingLevels(ACell root, AString[] path, int from, int to) {
		ACell node = root;
		for (int i = from; i < to; i++) {
			if (!containsKey(node, path[i])) return to - i;
			node = child(node, path[i]);
		}
		return 0;
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
		return Blob.parse(segment.toString());
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

	// ========== Changes ==========

	/** {@link #set(ACell, AString[], int, int, ACell)} to the end of {@code path}. */
	public static ACell set(ACell root, AString[] path, int from, ACell value) {
		return set(root, path, from, path.length, value);
	}

	/** {@code root} with {@code value} at {@code path[from, to)}; every level above it must exist. */
	public static ACell set(ACell root, AString[] path, int from, int to, ACell value) {
		return update(root, path, from, to, previous -> value);
	}

	/**
	 * {@code root} with the value at {@code path[from, to)} replaced by {@code change}
	 * applied to it (null when absent). Every level above it must exist; a list
	 * position must be in range.
	 */
	@SuppressWarnings("unchecked")
	public static ACell update(ACell root, AString[] path, int from, int to, UnaryOperator<ACell> change) {
		if (from >= to) return change.apply(root);
		if (root == null) throw new NotFoundException(String.format(NO_LEVEL, at(path, from)), at(path, from));
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = position(vec, path, from);
			return vec.assoc(idx, update(vec.get(idx), path, from + 1, to, change));
		}
		if (!(root instanceof AMap)) throw notAMap(path, from);
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		return map.assoc(path[from], update(map.get(path[from]), path, from + 1, to, change));
	}

	/** {@code root} with {@code element} added to the list at {@code path[from, to)}, which is created when absent. */
	public static ACell append(ACell root, AString[] path, int from, int to, ACell element) {
		return update(root, path, from, to, current -> appendTo(current, element));
	}

	/** {@link #remove(ACell, AString[], int, int)} to the end of {@code path}. */
	public static ACell remove(ACell root, AString[] path, int from) {
		return remove(root, path, from, path.length);
	}

	/**
	 * {@code root} without the value at {@code path[from, to)}. An absent value is
	 * left alone; a list element becomes null so later positions keep theirs.
	 */
	@SuppressWarnings("unchecked")
	public static ACell remove(ACell root, AString[] path, int from, int to) {
		if (from >= to) return null;
		if (root == null) return null;
		AString segment = path[from];
		boolean leaf = (from == to - 1);
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = parseIndex(segment);
			if (idx < 0 || idx >= vec.count()) return root;
			return leaf ? vec.assoc(idx, null) : vec.assoc(idx, remove(vec.get(idx), path, from + 1, to));
		}
		if (!(root instanceof AMap)) return root;
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		if (leaf) return map.dissoc(segment);
		if (!map.containsKey(segment)) return root;
		ACell child = map.get(segment);
		ACell replacement = remove(child, path, from + 1, to);
		return Utils.equals(child, replacement) ? root : map.assoc(segment, replacement);
	}

	/**
	 * {@code root} with a map at every level of {@code path[from, to)} that holds
	 * nothing, or null. Existing maps and lists are kept; a list position must be
	 * in range, and a level holding any other value is refused.
	 *
	 * @param created Receives one count per level created
	 */
	@SuppressWarnings("unchecked")
	public static ACell createPath(ACell root, AString[] path, int from, int to, int[] created) {
		if (from >= to) return root;
		if (root instanceof AVector<?>) {
			AVector<ACell> vec = (AVector<ACell>) root;
			long idx = position(vec, path, from);
			return vec.assoc(idx, createPath(level(vec.get(idx), created), path, from + 1, to, created));
		}
		if (!(root instanceof AMap)) throw notAMap(path, from);
		AMap<AString, ACell> map = (AMap<AString, ACell>) root;
		return map.assoc(path[from], createPath(level(map.get(path[from]), created), path, from + 1, to, created));
	}

	private static ACell level(ACell existing, int[] created) {
		if (existing != null) return existing;
		created[0]++;
		return Maps.empty();
	}

	/** The list position {@code path[from]} names in {@code vec}; refuses one out of range or not a position. */
	private static long position(AVector<ACell> vec, AString[] path, int from) {
		long idx = parseIndex(path[from]);
		if (idx >= 0 && idx < vec.count()) return idx;
		String at = at(path, from);
		if (idx >= 0) throw new InvalidInputException(String.format(INDEX_OUT_OF_RANGE, at, idx, vec.count()));
		throw new InvalidInputException(String.format(NOT_AN_INDEX, at, vec.count(), path[from]));
	}

	private static InvalidInputException notAMap(AString[] path, int from) {
		return new InvalidInputException(String.format(NOT_A_MAP, path[from], at(path, from)));
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

	// ========== Making paths ==========

	/** Refusal for a path array with an element that is not a string; names its position and type. */
	public static final String SEGMENT_NOT_STRING = "Path segment %d must be a string, not %s";

	/** Refusal for a path array with an element containing {@code /}; names its position. */
	public static final String SEGMENT_HAS_SLASH = "Path segment %d must not contain '/'";

	/**
	 * The path a caller gives: a string, split as {@link #parse} does, or an
	 * array of the segments themselves. Anything else, and null, is the empty
	 * path.
	 *
	 * @throws InvalidInputException if an array element is not a string, or contains {@code /}
	 */
	public static AString[] of(ACell path) {
		if (path instanceof AVector<?> vec) {
			AString[] segments = new AString[Math.toIntExact(vec.count())];
			for (int i = 0; i < segments.length; i++) {
				ACell element = vec.get(i);
				if (!(element instanceof AString segment)) {
					throw new InvalidInputException(String.format(SEGMENT_NOT_STRING, i,
						(element == null) ? "null" : element.getType()));
				}
				if (segment.toString().indexOf('/') >= 0) {
					throw new InvalidInputException(String.format(SEGMENT_HAS_SLASH, i));
				}
				segments[i] = segment;
			}
			return segments;
		}
		return (path instanceof AString string) ? parse(string.toString()) : new AString[0];
	}

	/** The path to the level at {@code path[from]}, as the caller wrote it. */
	private static String at(AString[] path, int from) {
		return format(path, 0, from);
	}

	/** Splits a slash-separated path into its segments, keeping each as written. */
	public static AString[] parse(String path) {
		if (path == null || path.isEmpty()) return new AString[0];
		if (path.startsWith("/")) path = path.substring(1);
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (path.isEmpty()) return new AString[0];

		String[] segments = path.split("/");
		AString[] parsed = new AString[segments.length];
		for (int i = 0; i < segments.length; i++) {
			parsed[i] = Strings.create(segments[i]);
		}
		return parsed;
	}

	/** Joins a path's segments with a leading slash, such as {@code /w/notes}; {@code /} for none. */
	public static String format(AString[] path) {
		return format(path, 0, path.length);
	}

	/** Joins {@code path[from, to)} with a leading slash; {@code /} for none. */
	public static String format(AString[] path, int from, int to) {
		if (from >= to) return "/";
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; i++) {
			sb.append('/').append(path[i]);
		}
		return sb.toString();
	}
}
