package covia.lattice;

import java.util.Arrays;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Path;
import convex.lattice.ALatticeComponent;
import convex.lattice.ValueComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.PathCursor;

/**
 * A value location addressed by Covia's path rules: Convex's generic value
 * component, resolving each segment to the key it names in the value it meets
 * ({@link ValuePath#keys}), a list position, a map key or an Index's Blob key
 * written in hex, so a path reaches the same value however deep it is resolved
 * from.
 *
 * <p>A component hands the data below it to one over its cursor, read-only
 * where only the component's own operations change that data: an agent's
 * {@code config/model}, a job's fields, a secret's record.</p>
 *
 * @param <V> Value type at this location
 */
public class CoviaValue<V extends ACell> extends ValueComponent<V> {

	public CoviaValue(AForkableCursor<V> cursor) {
		super(cursor);
	}

	/**
	 * The place segments {@code [start, end)} name in {@code component}'s own
	 * value, read-only, for a component whose data only its own operations
	 * change; the component itself for none.
	 */
	public static ALatticeComponent<?> within(ALatticeComponent<?> component, AString[] segments, int start, int end) {
		Objects.checkFromToIndex(start, end, segments.length);
		if (start == end) return component;
		return new CoviaValue<>(component.cursor().readOnly()).resolve(segments, start, end);
	}

	@Override
	public CoviaValue<?> resolve(AString... segments) {
		return (CoviaValue<?>) super.resolve(segments);
	}

	@Override
	public CoviaValue<?> resolve(Path path) {
		return (CoviaValue<?>) super.resolve(path);
	}

	/**
	 * Takes the keys the segments name in the value here now, so the resolved
	 * location keeps them as later changes reshape the value.
	 */
	@Override
	public CoviaValue<?> resolve(AString[] segments, int start, int end) {
		Objects.checkFromToIndex(start, end, segments.length);
		if (start == end) return this;
		Path rest = Path.of(Arrays.copyOfRange(segments, start, end));
		ACell[] keys = ValuePath.keys(cursor().getOrDefault(null), rest).keys();
		return new CoviaValue<ACell>(new PathCursor<ACell>(cursor(), keys));
	}

	@Override
	public CoviaValue<V> rebind(AForkableCursor<V> cursor) {
		return new CoviaValue<>(cursor);
	}

	@Override
	public CoviaValue<V> fork() {
		return rebind(storage.fork());
	}
}
