package covia.lattice;

import java.util.Arrays;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Path;
import convex.lattice.ALatticeComponent;
import convex.lattice.NoSuchPathException;
import convex.lattice.cursor.AForkableCursor;

/**
 * A lattice component that callers can address by a Covia path. Each component
 * defines what the path's segments below it mean, routing them to the component
 * that owns them through Convex's resolution hook,
 * {@link #resolve(AString[], int, int)}; a path ends at the last component
 * that claims it.
 *
 * <p>A path is the caller's: a {@link Path} of string segments, as written and
 * checked by {@link CoviaPath}. Mapping a segment to
 * a lattice key, such as a job id in hex to its Blob, is the business of the
 * component that meets it, and lattice keys never appear in a path.</p>
 *
 * <p>Resolution is pure: it sees no request, makes no checks and writes
 * nothing. It follows the path's shape, never the data: a child is chosen by
 * its key whether or not it exists, so a component resolved early in a
 * transaction sees the child come and go ({@link #exists}). A segment that
 * names no place data could be throws {@link NoSuchPathException}, from which
 * {@link #noSuchPath} gives the refusal. Children are built from this
 * component's own cursor, so a walk from a transaction draft stays in that
 * draft. A path into a {@link Workspace} ends at an {@link Entry}, whose
 * operations change it; below any other component it ends at its
 * {@link RecordData}, read-only, since only the component's own operations
 * change it.</p>
 *
 * <p>A component that owns a kind of data, such as a user, an agent, a session
 * or a job, is an {@link Owner}: it knows whose the data is and has its
 * {@link AccessPolicy}. A request reaches the data only through an
 * {@link Access}, which asks the innermost owner's policy; the component
 * itself checks nothing.</p>
 *
 * @param <V> Value type of this component's cursor
 */
public abstract class ACoviaComponent<V extends ACell> extends ALatticeComponent<V> {

	/** Refusal for a change by path to data that only its component's operations change. */
	public static final String READ_ONLY = "This data is read-only";

	/**
	 * Refusal for a path that names no place data could be; names the path,
	 * then the part of it up to the first segment that names nothing.
	 */
	public static final String NO_SUCH_PATH = "No such path: %s (nothing can be at %s)";

	protected ACoviaComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	/**
	 * The component at the place segments {@code [start, end)} name below this
	 * one. A component may take as many segments as its rules cover in one
	 * step, and passes the rest to the child that owns them as
	 * {@code child.resolve(segments, start + n, end)}. A component with fixed
	 * names, or with keys of one form, throws {@link NoSuchPathException} at a
	 * segment it cannot hold. By default the segments name data in this
	 * component's value, as its {@link RecordData}.
	 *
	 * @return The component there, never null; this one for an empty range
	 * @throws NoSuchPathException If a segment names no place
	 */
	@Override
	public ACoviaComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		return new RecordData(this, Path.of(Arrays.copyOfRange(segments, start, end)));
	}

	@Override
	public ACoviaComponent<?> resolve(Path path) {
		return (ACoviaComponent<?>) super.resolve(path);
	}

	@Override
	public ACoviaComponent<?> resolve(AString... segments) {
		return (ACoviaComponent<?>) super.resolve(segments);
	}

	/** The refusal for a path that names no place, as {@link NoSuchPathException} reports it. */
	public static String noSuchPath(NoSuchPathException e) {
		Path from = e.getPath();
		return String.format(NO_SUCH_PATH, CoviaPath.address(from), CoviaPath.address(from.slice(0, 1)));
	}

	/** Whether this component's data is there now, read from its own cursor, so in a draft as the draft stands. */
	@Override
	public boolean exists() {
		return cursor().getOrDefault(null) != null;
	}

	/** This component's value as callers see it. */
	public ACell read() {
		return cursor().getOrDefault(null);
	}

	/** The value at {@code path} below this component, as callers see it, or null when absent. */
	public ACell read(Path path) {
		return ValuePath.get(read(), path);
	}

	/** Whether a value is at {@code path} below this component; a stored null counts. */
	public boolean contains(Path path) {
		return ValuePath.contains(read(), path);
	}
}
