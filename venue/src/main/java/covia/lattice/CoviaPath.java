package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.lattice.NoSuchPathException;
import covia.exception.InvalidInputException;

/**
 * Covia's syntax for a {@link Path}: string segments, none of which contains
 * {@code /}. Callers write a path as a string such as {@code w/notes/today},
 * or as an array of its segments.
 *
 * <p>A path a caller gives is made only here: {@link #parse} splits a string
 * on {@code /}, {@link #of} takes a caller's string or array, and
 * {@link #segments} builds one from known segments. Each refuses a segment
 * holding {@code /}, so a path and its {@link #address} name the same place,
 * and authority checked on that address covers exactly what the path
 * reaches.</p>
 *
 * <p>A component reads its segments of a path and passes the rest on with
 * {@link Path#slice}, which shares the caller's request. A slice still knows
 * the request it came from, so {@link #address} gives the address the caller
 * wrote up to the slice's end. What a segment means, such as a list position
 * or a job id in hex, is decided by the component it reaches.</p>
 */
public final class CoviaPath {

	/** Refusal for a path array with an element that is not a string; names its position and type. */
	public static final String SEGMENT_NOT_STRING = "Path segment %d must be a string, not %s";

	/** Refusal for a path array with an element containing {@code /}; names its position. */
	public static final String SEGMENT_HAS_SLASH = "Path segment %d must not contain '/'";

	/**
	 * Refusal for a path that names no place data could be; names the path,
	 * then the part of it up to the first segment that names nothing.
	 */
	public static final String NO_SUCH_PATH = "No such path: %s (nothing can be at %s)";

	/** The path with no segments, the root of whatever it is resolved against. */
	public static final Path EMPTY = Path.of();

	private CoviaPath() {}

	// ========== Making paths ==========

	/** Splits a slash-separated path into its segments, keeping each as written; a leading or trailing slash is ignored. */
	public static Path parse(String path) {
		if (path == null) return EMPTY;
		if (path.startsWith("/")) path = path.substring(1);
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (path.isEmpty()) return EMPTY;

		String[] parts = path.split("/");
		AString[] parsed = new AString[parts.length];
		for (int i = 0; i < parts.length; i++) {
			parsed[i] = Strings.create(parts[i]);
		}
		return Path.of(parsed);
	}

	/**
	 * The path a caller gives: a string, split as {@link #parse} does, or an
	 * array of the segments themselves. Anything else, and null, is the empty
	 * path.
	 *
	 * @throws InvalidInputException if an array element is not a string, or contains {@code /}
	 */
	public static Path of(ACell path) {
		if (path instanceof AVector<?> vec) {
			AString[] checked = new AString[Math.toIntExact(vec.count())];
			for (int i = 0; i < checked.length; i++) {
				checked[i] = check(i, vec.get(i));
			}
			return Path.of(checked);
		}
		return (path instanceof AString string) ? parse(string.toString()) : EMPTY;
	}

	/**
	 * The path of the given segments, for code that builds one from known
	 * pieces.
	 *
	 * @throws InvalidInputException if a segment is null or contains {@code /}
	 */
	public static Path segments(AString... segments) {
		for (int i = 0; i < segments.length; i++) {
			check(i, segments[i]);
		}
		return Path.of(segments);
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

	// ========== Places ==========

	/** The path without its last segment: the place above its end. The empty path is its own parent. */
	public static Path parent(Path path) {
		return path.isEmpty() ? path : path.slice(0, path.count() - 1);
	}

	/**
	 * The address of the place a path names: its request up to the path's
	 * end, joined with a leading slash, such as {@code /w/notes}; {@code /}
	 * for none. A slice therefore formats as the address the caller wrote.
	 */
	public static String address(Path path) {
		long end = path.position() + path.count();
		if (end == 0) return "/";
		Path request = path.request();
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < end; i++) {
			sb.append('/').append(request.get(i));
		}
		return sb.toString();
	}

	/** The refusal for a path that names no place, as {@link NoSuchPathException} reports it. */
	public static String noSuchPath(NoSuchPathException e) {
		Path from = e.getPath();
		return String.format(NO_SUCH_PATH, address(from), address(from.slice(0, 1)));
	}
}
