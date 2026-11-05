package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A lattice component that callers can address by a Covia path. Each component
 * defines what the path's segments below it mean, routing them to the component
 * that owns them; a path ends at the last component that claims it.
 *
 * <p>A path is the caller's: string segments, as written. Mapping a segment to
 * a lattice key, such as a job id in hex to its Blob, is the business of the
 * component that meets it, and lattice keys never appear in a path.</p>
 *
 * <p>Resolution is pure: it sees no request, makes no checks and writes
 * nothing. Children are built from this component's own cursor, so a walk from
 * a transaction draft stays in that draft. Any component can be read below its
 * position. Changing data by path is a {@link Workspace}'s operation; elsewhere
 * the component's own operations are the only way to change it.</p>
 *
 * @param <V> Value type of this component's cursor
 */
public abstract class ACoviaComponent<V extends ACell> extends ALatticeComponent<V> {

	/** Refusal for a change by path to data that only its component's operations change. */
	public static final String READ_ONLY = "This data is read-only";

	protected ACoviaComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	/** {@link #resolve(AString[], int, int)} to the end of {@code path}. */
	public final Position resolve(AString[] path, int from) {
		return resolve(path, from, path.length);
	}

	/**
	 * The component that owns the position {@code path[from, to)} names below
	 * this one. A component may take as many segments as its rules cover in one
	 * step. By default a component owns every position below it.
	 *
	 * @param path The caller's path
	 * @param from Index of the first segment below this component
	 * @param to Index after the last segment
	 * @return Where the path ends, never null
	 */
	public Position resolve(AString[] path, int from, int to) {
		return new Position(this, path, from, to);
	}

	/** This component's value as callers see it. */
	public ACell read() {
		return cursor().get();
	}

	/** {@link #read(AString[], int, int)} to the end of {@code path}. */
	public final ACell read(AString[] path, int from) {
		return read(path, from, path.length);
	}

	/** The value at {@code path[from, to)} below this component, as callers see it, or null when absent. */
	public ACell read(AString[] path, int from, int to) {
		return ValuePath.get(read(), path, from, to);
	}

	/** {@link #contains(AString[], int, int)} to the end of {@code path}. */
	public final boolean contains(AString[] path, int from) {
		return contains(path, from, path.length);
	}

	/** Whether a value is at {@code path[from, to)} below this component; a stored null counts. */
	public boolean contains(AString[] path, int from, int to) {
		return ValuePath.contains(read(), path, from, to);
	}
}
