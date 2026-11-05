package covia.lattice;

import convex.core.data.ACell;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A lattice component that callers can address by a Covia path. Each component
 * defines what the path's segments below it mean, routing them to the component
 * that owns them; a path ends at the last component that claims it.
 *
 * <p>A path is the caller's: a {@link CoviaPath} of string segments, as written. Mapping a segment to
 * a lattice key, such as a job id in hex to its Blob, is the business of the
 * component that meets it, and lattice keys never appear in a path.</p>
 *
 * <p>Resolution is pure: it sees no request, makes no checks and writes
 * nothing. Children are built from this component's own cursor, so a walk from
 * a transaction draft stays in that draft. Any component can be read below its
 * position. A path into a {@link Workspace} ends at an {@link Entry}, whose
 * operations change it; elsewhere the component's own operations are the only
 * way to change it.</p>
 *
 * @param <V> Value type of this component's cursor
 */
public abstract class ACoviaComponent<V extends ACell> extends ALatticeComponent<V> {

	/** Refusal for a change by path to data that only its component's operations change. */
	public static final String READ_ONLY = "This data is read-only";

	protected ACoviaComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	/**
	 * The component that owns the position {@code path} names below this one. A
	 * component may take as many segments as its rules cover in one step, and
	 * passes the rest of the path to the child that owns them. By default a
	 * component owns every position below it.
	 *
	 * @param path The stretch of the caller's path below this component
	 * @return Where the path ends, never null
	 */
	public Position resolve(CoviaPath path) {
		return new Position(this, path);
	}

	/** This component's value as callers see it. */
	public ACell read() {
		return cursor().get();
	}

	/** The value at {@code path} below this component, as callers see it, or null when absent. */
	public ACell read(CoviaPath path) {
		return ValuePath.get(read(), path);
	}

	/** Whether a value is at {@code path} below this component; a stored null counts. */
	public boolean contains(CoviaPath path) {
		return ValuePath.contains(read(), path);
	}
}
