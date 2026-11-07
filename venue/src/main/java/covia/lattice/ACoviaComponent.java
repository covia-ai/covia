package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.Path;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A lattice component that callers can address by a Covia path. Each component
 * defines what the path's segments below it mean, routing them to the component
 * that owns them; a path ends at the last component that claims it.
 *
 * <p>A path is the caller's: a {@link Path} of string segments, as written and
 * checked by {@link CoviaPath}. Mapping a segment to
 * a lattice key, such as a job id in hex to its Blob, is the business of the
 * component that meets it, and lattice keys never appear in a path.</p>
 *
 * <p>Resolution is pure: it sees no request, makes no checks and writes
 * nothing. It follows the path's shape, never the data: a child is chosen by
 * its key whether or not it exists, so a position resolved early in a
 * transaction sees the child come and go ({@link Position#exists}). A path
 * that names no place data could be ends at an {@link Position#invalid
 * invalid} position. Children are built from this component's own cursor, so
 * a walk from a transaction draft stays in that draft. Any component can be
 * read below its position. A path into a {@link Workspace} ends at an
 * {@link Entry}, whose operations change it; elsewhere the component's own
 * operations are the only way to change it.</p>
 *
 * <p>A component that owns a kind of data, such as a user, an agent, a session
 * or a job, is an {@link Owner}: it knows whose the data is and has its
 * {@link AccessPolicy}. A request reaches a position only through an
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
	 * The component that owns the position {@code path} names below this one. A
	 * component may take as many segments as its rules cover in one step, and
	 * passes the rest of the path to the child that owns them. A component
	 * with fixed names, or with keys of one form, gives an invalid position for
	 * a segment it cannot hold. By default a component owns every position
	 * below it, as free-form data does.
	 *
	 * @param path The stretch of the caller's path below this component
	 * @return Where the path ends, never null
	 */
	public Position locate(Path path) {
		return new Position(this, path);
	}

	/** Whether this component's data is there now, read from its own cursor, so in a draft as the draft stands. */
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
