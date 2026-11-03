package covia.lattice;

import convex.core.data.ACell;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A lattice component that callers can address by path. Each component defines
 * what the keys below it mean, routing them to the component that owns them;
 * a path ends at the last component that claims it.
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

	/**
	 * The component that owns the position {@code keys[from..]} names below this
	 * one. A component may take as many keys as its rules cover in one step. By
	 * default a component owns every position below it.
	 *
	 * @param keys Path keys
	 * @param from Index of the first key below this component
	 * @return Where the path ends, never null
	 */
	public Position resolve(ACell[] keys, int from) {
		return new Position(this, keys, from);
	}

	/** This component's value as callers see it. */
	public ACell read() {
		return cursor().get();
	}

	/** The value at {@code keys[from..]} below this component, as callers see it, or null when absent. */
	public ACell read(ACell[] keys, int from) {
		return ValuePath.get(read(), keys, from);
	}

	/** Whether a value is at {@code keys[from..]} below this component; a stored null counts. */
	public boolean contains(ACell[] keys, int from) {
		return ValuePath.contains(read(), keys, from);
	}
}
