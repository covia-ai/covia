package covia.lattice;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AString;
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
 * <p>A path resolves to the {@link Entry} it names, whose keys are fixed then,
 * and every change is an entry's. The area's own operations, for its owner's
 * code, take the stretch of a path that is the area's own and act through the
 * entry it resolves to. An absent root counts as empty. No operation but
 * {@link #createPath} creates a level: a change below a level that does not
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

	/** Where {@code path} ends in this area: the {@link Entry} there. */
	@Override
	public Position resolve(CoviaPath path) {
		return new Position(entry(path), path.rest(path.length()));
	}

	/** The entry at {@code path} in this area, its keys fixed now by the values the path meets. */
	public Entry entry(CoviaPath path) {
		return new Entry(this, path, ValuePath.keys(read(), path));
	}

	/**
	 * Puts {@code value} at {@code path}, as {@link Entry#write}.
	 *
	 * @return Whether a value was there already
	 */
	public boolean write(CoviaPath path, ACell value) {
		return entry(path).write(value);
	}

	/**
	 * Adds {@code element} to the list at {@code path}, starting one when
	 * nothing is there, as {@link Entry#append}.
	 *
	 * @return The list's size afterwards
	 */
	public long append(CoviaPath path, ACell element) {
		return entry(path).append(element);
	}

	/**
	 * Removes the value at {@code path}, as {@link Entry#delete}. Nothing is
	 * written when it is absent, and the levels above a removed value stay.
	 *
	 * @return Whether a value was removed
	 */
	public boolean delete(CoviaPath path) {
		return entry(path).delete();
	}

	/**
	 * Replaces the value at {@code path} with {@code change} applied to it, null
	 * when absent, as {@link Entry#update}. {@code change} may run again on a
	 * retry, so it must be pure.
	 *
	 * @return The new value
	 */
	public ACell update(CoviaPath path, UnaryOperator<ACell> change) {
		return entry(path).update(change);
	}

	/**
	 * Creates a map at every level of {@code path} that holds nothing, so that a
	 * change below them can follow. Existing levels are kept.
	 *
	 * @return How many levels were created
	 */
	public int createPath(CoviaPath path) {
		return entry(path).createLevels(path.length());
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
