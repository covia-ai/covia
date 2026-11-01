package covia.lattice;

import convex.core.data.ACell;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A lattice component that callers can address by path. Each component defines
 * what the keys below it mean, routing them to the component that owns them;
 * data that no component claims is a {@link DataComponent}.
 *
 * <p>Resolution is pure: it sees no request, makes no checks and writes
 * nothing. Children are built from this component's own cursor, so a walk from
 * a transaction draft stays in that draft. Writability belongs to the
 * operations of the component a walk ends at, which make their own checks.</p>
 *
 * @param <V> Value type of this component's cursor
 */
public abstract class ACoviaComponent<V extends ACell> extends ALatticeComponent<V> {

	protected ACoviaComponent(AForkableCursor<V> cursor) {
		super(cursor);
	}

	/**
	 * The component at the position {@code keys[from..]} names below this one.
	 * A component may take as many keys as its rules cover in one step. By
	 * default the position is this component when no keys remain, and
	 * read-only data below it otherwise.
	 *
	 * @param keys Path keys
	 * @param from Index of the first key below this component
	 * @return The component at that position, never null
	 */
	@SuppressWarnings("unchecked")
	public ACoviaComponent<?> resolve(ACell[] keys, int from) {
		if (from >= keys.length) return this;
		return new DataComponent((AForkableCursor<ACell>) cursor(), keys, from);
	}

	/** This component's value as callers see it. */
	public ACell read() {
		return cursor().get();
	}
}
