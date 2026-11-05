package covia.lattice;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.PathCursor;
import covia.exception.InvalidInputException;

/**
 * A data area that holds any value at any depth, reached by path: a user's
 * {@code w} and {@code o}, an agent's {@code n}, a session's {@code c}, a job's
 * {@code temp}. Its owner binds the cursor at the area's root, with the owner's
 * stamping, and decides whether the root may be created.
 *
 * <p>Operations take the caller's keys and the range of them that is the area's
 * own, {@code keys[from, to)}, read each key by the value it meets ({@link ValuePath}), and change
 * the area in one atomic update. An absent root counts as empty. No operation
 * but {@link #createPath} creates a level: a change below a level that does not
 * exist is refused.</p>
 */
public final class Workspace extends ACoviaComponent<ACell>
		implements TransactionalComponent<ACell, Workspace> {

	/** Refusal for a change to an area whose record does not exist; names the area. */
	public static final String NO_RECORD = "Scoped path is not available: %s has no parent record";

	/**
	 * @param cursor Cursor at the area's root
	 */
	public Workspace(AForkableCursor<ACell> cursor) {
		super(cursor);
	}

	/**
	 * The area at {@code key} in a record. It reads as absent while the record is
	 * missing, and a change to it is refused rather than creating the record.
	 *
	 * @param record Cursor at the record
	 * @param key The area's key in the record
	 * @return The area
	 */
	public static Workspace inRecord(AForkableCursor<ACell> record, AString key) {
		return new Workspace(new RecordArea(record, key));
	}

	@Override
	public Workspace withCursor(AForkableCursor<ACell> draft) {
		return new Workspace(draft);
	}

	/** {@link #write(ACell[], int, int, ACell)} to the end of {@code keys}. */
	public boolean write(ACell[] keys, int from, ACell value) {
		return write(keys, from, keys.length, value);
	}

	/**
	 * Puts {@code value} at {@code keys[from, to)}.
	 *
	 * @return Whether a value was there already
	 */
	public boolean write(ACell[] keys, int from, int to, ACell value) {
		boolean[] existed = {false};
		cursor().updateAndGet(area -> {
			existed[0] = ValuePath.contains(area, keys, from, to);
			return ValuePath.set(base(area, from, to), keys, from, to, value);
		});
		return existed[0];
	}

	/** {@link #append(ACell[], int, int, ACell)} to the end of {@code keys}. */
	public long append(ACell[] keys, int from, ACell element) {
		return append(keys, from, keys.length, element);
	}

	/**
	 * Adds {@code element} to the list at {@code keys[from, to)}, starting one
	 * when nothing is there.
	 *
	 * @return The list's size afterwards
	 */
	public long append(ACell[] keys, int from, int to, ACell element) {
		ACell area = cursor().updateAndGet(current ->
			ValuePath.append(base(current, from, to), keys, from, to, element));
		return ((AVector<?>) ValuePath.get(area, keys, from, to)).count();
	}

	/** {@link #delete(ACell[], int, int)} to the end of {@code keys}. */
	public boolean delete(ACell[] keys, int from) {
		return delete(keys, from, keys.length);
	}

	/**
	 * Removes the value at {@code keys[from, to)}. Nothing is written when it is
	 * absent, and the levels above a removed value stay.
	 *
	 * @return Whether a value was removed
	 */
	public boolean delete(ACell[] keys, int from, int to) {
		boolean[] removed = {false};
		cursor().updateAndGet(area -> {
			removed[0] = ValuePath.contains(area, keys, from, to);
			return removed[0] ? ValuePath.remove(area, keys, from, to) : area;
		});
		return removed[0];
	}

	/** {@link #update(ACell[], int, int, UnaryOperator)} to the end of {@code keys}. */
	public ACell update(ACell[] keys, int from, UnaryOperator<ACell> change) {
		return update(keys, from, keys.length, change);
	}

	/**
	 * Replaces the value at {@code keys[from, to)} with {@code change} applied to
	 * it, null when absent. {@code change} may run again on a retry, so it must
	 * be pure.
	 *
	 * @return The new value
	 */
	public ACell update(ACell[] keys, int from, int to, UnaryOperator<ACell> change) {
		ACell area = cursor().updateAndGet(current ->
			ValuePath.update(base(current, from, to), keys, from, to, change));
		return ValuePath.get(area, keys, from, to);
	}

	/** {@link #createPath(ACell[], int, int)} to the end of {@code keys}. */
	public int createPath(ACell[] keys, int from) {
		return createPath(keys, from, keys.length);
	}

	/**
	 * Creates a map at every level of {@code keys[from, to)} that holds nothing,
	 * so that a change below them can follow. Existing levels are kept.
	 *
	 * @return How many levels were created
	 */
	public int createPath(ACell[] keys, int from, int to) {
		int[] created = {0};
		cursor().updateAndGet(area -> {
			created[0] = 0;
			return ValuePath.createPath(base(area, from, to), keys, from, to, created);
		});
		return created[0];
	}

	/** The area to change below: an absent root counts as an empty map. */
	private static ACell base(ACell area, int from, int to) {
		return (area == null && from < to) ? Maps.empty() : area;
	}

	/** The area at a key in a record; a change needs the record. */
	private static final class RecordArea extends PathCursor<ACell> {
		private final AString key;

		RecordArea(AForkableCursor<ACell> record, AString key) {
			super(record, key);
			this.key = key;
		}

		@Override
		protected ACell write(ACell record, ACell area) {
			if (record == null) throw new InvalidInputException(String.format(NO_RECORD, key));
			return super.write(record, area);
		}
	}
}
