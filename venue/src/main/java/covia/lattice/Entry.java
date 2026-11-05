package covia.lattice;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;

/**
 * A value inside a {@link Workspace}, at a path whose lattice keys were fixed
 * when it was resolved ({@link ValuePath#keys}). The entry is a path cursor at
 * those keys under the workspace's cursor, so its changes land in the
 * workspace's data with its owner's stamping.
 *
 * <p>A change is made at the entry's key in the level above it, in one atomic
 * update, so a stored null counts as present and a deletion removes the key.
 * It needs every level above the entry to exist, each still the kind of value
 * its key was resolved against; otherwise it is refused and nothing is written.
 * Only {@link #createLevels} creates missing levels, as maps, and an absent
 * workspace root counts as an empty map. An entry makes no authority checks:
 * they belong to the code asking for the change.</p>
 */
public final class Entry extends ACoviaComponent<ACell> {

	/** Refusal for a change at a level that changed kind while the path was in use; names that level. */
	public static final String CHANGED = "The value at '%s' changed while this path was in use";

	private final Workspace area;
	private final CoviaPath path;
	private final ValuePath.Keys resolved;
	private final ACell[] keys;
	/** The level above the entry, or null for the workspace's root. */
	private final AForkableCursor<ACell> above;

	Entry(Workspace area, CoviaPath path, ValuePath.Keys resolved) {
		super(area.cursor().path(resolved.keys()));
		this.area = area;
		this.path = path;
		this.resolved = resolved;
		this.keys = resolved.keys();
		this.above = keys.length == 0 ? null : area.cursor().path(Arrays.copyOf(keys, keys.length - 1));
	}

	/** The stretch of the caller's path the workspace read; it formats as the whole address. */
	public CoviaPath path() {
		return path;
	}

	/** Whether a value is here; a stored null counts. */
	public boolean contains() {
		if (keys.length == 0) return read() != null;
		return resolved.refusal() == null && holds(above.get(), last());
	}

	@Override
	public boolean contains(CoviaPath below) {
		return below.isEmpty() ? contains() : super.contains(below);
	}

	/**
	 * Puts {@code value} here.
	 *
	 * @return Whether a value was here already
	 */
	public boolean write(ACell value) {
		boolean[] existed = {false};
		if (keys.length == 0) {
			cursor().getAndUpdate(current -> {
				existed[0] = current != null;
				return value;
			});
		} else {
			changeAbove(container -> {
				existed[0] = holds(container, last());
				return put(container, last(), value);
			});
		}
		return existed[0];
	}

	/**
	 * Replaces the value here with {@code change} applied to it, null when
	 * absent. {@code change} may run again on a retry, so it must be pure.
	 *
	 * @return The new value
	 */
	public ACell update(UnaryOperator<ACell> change) {
		if (keys.length == 0) return cursor().updateAndGet(change);
		ACell[] result = {null};
		changeAbove(container -> {
			result[0] = change.apply(valueIn(container, last()));
			return put(container, last(), result[0]);
		});
		return result[0];
	}

	/**
	 * Adds {@code element} to the list here, starting one when nothing is here.
	 *
	 * @return The list's size afterwards
	 */
	public long append(ACell element) {
		return ((AVector<?>) update(current -> ValuePath.appendTo(current, element))).count();
	}

	/**
	 * Removes the value here. Nothing is written when it is absent; a list
	 * element becomes null so later positions keep theirs.
	 *
	 * @return Whether a value was removed
	 */
	@SuppressWarnings("unchecked")
	public boolean delete() {
		boolean[] removed = {false};
		if (keys.length == 0) {
			cursor().getAndUpdate(current -> {
				removed[0] = current != null;
				return null;
			});
			return removed[0];
		}
		if (resolved.refusal() != null) return false;
		above.updateAndGet(container -> {
			removed[0] = holds(container, last());
			if (!removed[0]) return container;
			if (container instanceof AVector<?> list) {
				return ((AVector<ACell>) list).assoc(((CVMLong) keys[last()]).longValue(), null);
			}
			return ((AMap<ACell, ACell>) container).dissoc(keys[last()]);
		});
		return removed[0];
	}

	/**
	 * How many levels of the path are absent: none when a value is here,
	 * otherwise those from the first missing one down to this one. A change
	 * after {@link #createLevels} makes exactly these.
	 */
	public int missingLevels() {
		ACell node = area.read();
		for (int i = 0; i < keys.length; i++) {
			if (!holds(node, i)) return keys.length - i;
			node = valueIn(node, i);
		}
		return 0;
	}

	/**
	 * Creates a map at every level above this entry that holds nothing, so
	 * that a change here can follow. Existing levels are kept.
	 *
	 * @return How many levels were created
	 */
	public int createLevels() {
		refuseUnreachable();
		if (keys.length < 2) return 0;
		int[] created = {0};
		area.cursor().updateAndGet(root -> {
			created[0] = 0;
			return createBelow(root == null ? Maps.empty() : root, 0, created);
		});
		return created[0];
	}

	/**
	 * Runs {@code action} in one transaction over the workspace, on an entry
	 * with this one's keys, so that levels it creates commit with its change.
	 */
	public void transact(Consumer<? super Entry> action) {
		area.transact(draft -> action.accept(new Entry(draft, path, resolved)));
	}

	// ========== Levels ==========

	private int last() {
		return keys.length - 1;
	}

	/**
	 * Changes the level above this entry in one atomic update. An absent
	 * workspace root counts as an empty map; any other missing level is refused.
	 * {@code edit} returns the level it is given when nothing changes.
	 */
	private void changeAbove(UnaryOperator<ACell> edit) {
		refuseUnreachable();
		above.updateAndGet(value -> {
			ACell container = (value == null && keys.length == 1) ? Maps.empty() : value;
			if (container == null) throw noLevel();
			ACell changed = edit.apply(container);
			return changed == container ? value : changed;
		});
	}

	/** {@code node}, the value at level {@code i}, with the levels below it down to the one above this entry. */
	private ACell createBelow(ACell node, int i, int[] created) {
		if (i >= last()) return node;
		ACell child = valueIn(node, i);
		if (child == null) {
			child = Maps.empty();
			created[0]++;
		}
		return put(node, i, createBelow(child, i + 1, created));
	}

	/** Whether {@code container} holds key {@code i} as the kind of value it was resolved against. */
	private boolean fits(ACell container, int i) {
		if (keys[i] instanceof CVMLong position) {
			return container instanceof AVector<?> list && position.longValue() < list.count();
		}
		return container instanceof AMap;
	}

	/** Whether {@code container} has an entry for key {@code i}, even one holding null. */
	@SuppressWarnings("unchecked")
	private boolean holds(ACell container, int i) {
		if (!fits(container, i)) return false;
		return container instanceof AVector || ((AMap<ACell, ACell>) container).containsKey(keys[i]);
	}

	/** The value at key {@code i} in {@code container}, or null. */
	@SuppressWarnings("unchecked")
	private ACell valueIn(ACell container, int i) {
		return fits(container, i) ? ((ADataStructure<ACell>) container).get(keys[i]) : null;
	}

	/** {@code container} with {@code value} at key {@code i}; refused when it no longer holds that key. */
	@SuppressWarnings("unchecked")
	private ACell put(ACell container, int i, ACell value) {
		if (!fits(container, i)) {
			String refusal = ValuePath.keyRefusal(container, path, i);
			throw new InvalidInputException(refusal != null ? refusal : String.format(CHANGED, path.prefix(i)));
		}
		if (container instanceof AVector<?> list) {
			return ((AVector<ACell>) list).assoc(((CVMLong) keys[i]).longValue(), value);
		}
		return ((AMap<ACell, ACell>) container).assoc(keys[i], value);
	}

	private void refuseUnreachable() {
		if (resolved.refusal() != null) throw new InvalidInputException(resolved.refusal());
	}

	/** The refusal for a change below a missing level, naming the first one missing. */
	private NotFoundException noLevel() {
		int first = Math.min(keys.length - missingLevels(), last() - 1);
		String at = path.prefix(first + 1).toString();
		return new NotFoundException(String.format(ValuePath.NO_LEVEL, at), at);
	}
}
