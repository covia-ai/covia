package covia.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import covia.exception.InvalidInputException;

/**
 * A Covia path, or a stretch of one: a sequence of string segments, none of
 * which contains {@code /}. Callers write a path as a string such as
 * {@code w/notes/today}, or as an array of its segments.
 *
 * <p>A path is made only here: {@link #parse} splits a string on {@code /},
 * {@link #of} takes a caller's string or array, and {@link #segments} builds
 * one from known segments. Each refuses a segment holding {@code /}, so a path
 * and its {@link #toString} name the same place, and authority checked on
 * that string covers exactly what the path reaches.</p>
 *
 * <p>A stretch is the segments {@code [start, end)} of a whole path, sharing
 * its segments without copying them: {@link #rest} passes the segments below a
 * component on to its child, and {@link #prefix} and {@link #parent} name a
 * place above the end. A stretch still knows the whole path it came from, so
 * it formats as the address the caller wrote. What a segment means, such as a
 * list position or a job id in hex, is decided by the component it reaches.</p>
 */
public final class CoviaPath {

	/** Refusal for a path array with an element that is not a string; names its position and type. */
	public static final String SEGMENT_NOT_STRING = "Path segment %d must be a string, not %s";

	/** Refusal for a path array with an element containing {@code /}; names its position. */
	public static final String SEGMENT_HAS_SLASH = "Path segment %d must not contain '/'";

	/** The path with no segments, the root of whatever it is resolved against. */
	public static final CoviaPath EMPTY = new CoviaPath(new AString[0], 0, 0);

	private final AString[] segments;
	private final int start;
	private final int end;

	private CoviaPath(AString[] segments, int start, int end) {
		this.segments = segments;
		this.start = start;
		this.end = end;
	}

	// ========== Making paths ==========

	/** Splits a slash-separated path into its segments, keeping each as written; a leading or trailing slash is ignored. */
	public static CoviaPath parse(String path) {
		if (path == null) return EMPTY;
		if (path.startsWith("/")) path = path.substring(1);
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (path.isEmpty()) return EMPTY;

		String[] parts = path.split("/");
		AString[] parsed = new AString[parts.length];
		for (int i = 0; i < parts.length; i++) {
			parsed[i] = Strings.create(parts[i]);
		}
		return whole(parsed);
	}

	/**
	 * The path a caller gives: a string, split as {@link #parse} does, or an
	 * array of the segments themselves. Anything else, and null, is the empty
	 * path.
	 *
	 * @throws InvalidInputException if an array element is not a string, or contains {@code /}
	 */
	public static CoviaPath of(ACell path) {
		if (path instanceof AVector<?> vec) {
			AString[] checked = new AString[Math.toIntExact(vec.count())];
			for (int i = 0; i < checked.length; i++) {
				checked[i] = check(i, vec.get(i));
			}
			return whole(checked);
		}
		return (path instanceof AString string) ? parse(string.toString()) : EMPTY;
	}

	/**
	 * The path of the given segments, for code that builds one from known
	 * pieces.
	 *
	 * @throws InvalidInputException if a segment is null or contains {@code /}
	 */
	public static CoviaPath segments(AString... segments) {
		AString[] checked = new AString[segments.length];
		for (int i = 0; i < checked.length; i++) {
			checked[i] = check(i, segments[i]);
		}
		return whole(checked);
	}

	private static AString check(int i, ACell element) {
		if (!(element instanceof AString segment)) {
			throw new InvalidInputException(String.format(SEGMENT_NOT_STRING, i,
				(element == null) ? "null" : element.getType()));
		}
		if (segment.toString().indexOf('/') >= 0) {
			throw new InvalidInputException(String.format(SEGMENT_HAS_SLASH, i));
		}
		return segment;
	}

	private static CoviaPath whole(AString[] segments) {
		return (segments.length == 0) ? EMPTY : new CoviaPath(segments, 0, segments.length);
	}

	// ========== Segments ==========

	/** Whether this stretch has no segments. */
	public boolean isEmpty() {
		return start == end;
	}

	/** The number of segments in this stretch. */
	public int length() {
		return end - start;
	}

	/** The segment at {@code index} in this stretch. */
	public AString get(int index) {
		Objects.checkIndex(index, length());
		return segments[start + index];
	}

	/** The first segment of this stretch. */
	public AString first() {
		return get(0);
	}

	// ========== Stretches ==========

	/** This stretch without its first segment. */
	public CoviaPath rest() {
		return rest(1);
	}

	/** This stretch without its first {@code count} segments. */
	public CoviaPath rest(int count) {
		Objects.checkFromToIndex(0, count, length());
		return new CoviaPath(segments, start + count, end);
	}

	/** The first {@code count} segments of this stretch. */
	public CoviaPath prefix(int count) {
		Objects.checkFromToIndex(0, count, length());
		return new CoviaPath(segments, start, start + count);
	}

	/** This stretch without its last segment: the place above its end. An empty stretch is its own parent. */
	public CoviaPath parent() {
		return isEmpty() ? this : prefix(length() - 1);
	}

	/**
	 * A whole path of this stretch's segments followed by {@code other}'s, such
	 * as the path an alias stands for. Both were checked when they were made.
	 */
	public CoviaPath append(CoviaPath other) {
		AString[] joined = new AString[length() + other.length()];
		System.arraycopy(segments, start, joined, 0, length());
		System.arraycopy(other.segments, other.start, joined, length(), other.length());
		return whole(joined);
	}

	// ========== Values ==========

	/**
	 * The address of the place this stretch names: the whole path up to its
	 * end, joined with a leading slash, such as {@code /w/notes}; {@code /} for
	 * none.
	 */
	@Override
	public String toString() {
		if (end == 0) return "/";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < end; i++) {
			sb.append('/').append(segments[i]);
		}
		return sb.toString();
	}

	/** Equal when both name the same place by the same segments and start at the same one. */
	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof CoviaPath path) || path.start != start || path.end != end) return false;
		for (int i = 0; i < end; i++) {
			if (!segments[i].equals(path.segments[i])) return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 31 * start + end;
		for (int i = 0; i < end; i++) {
			hash = 31 * hash + segments[i].hashCode();
		}
		return hash;
	}
}
