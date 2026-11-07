package covia.lattice;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.exception.AuthException;
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
 * a request reaches it through an {@link Access}, which asks the owner's
 * policy first.</p>
 */
public final class Entry extends ACoviaComponent<ACell> {

	/** Refusal for a change at a level that changed kind while the path was in use; names that level. */
	public static final String CHANGED = "The value at '%s' changed while this path was in use";

	private final Workspace area;
	private final Path path;
	private final ValuePath.Keys resolved;
	private final ACell[] keys;
	/** The level above the entry, or null for the workspace's root. */
	private final AForkableCursor<ACell> above;

	Entry(Workspace area, Path path, ValuePath.Keys resolved) {
		super(area.cursor().path(resolved.keys()));
		this.area = area;
		this.path = path;
		this.resolved = resolved;
		this.keys = resolved.keys();
		this.above = keys.length == 0 ? null : area.cursor().path(Arrays.copyOf(keys, keys.length - 1));
	}

	/**
	 * The entry further down the same workspace, its keys fixed now as the
	 * workspace fixes them: segments below an entry continue its path.
	 */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		return area.resolve(segments, path.isEmpty() ? start : path.position(), end);
	}

	/**
	 * The entry to change where a path ends: the entry itself, or a workspace's
	 * root for the workspace. Anything else only its component's operations
	 * change.
	 *
	 * @throws AuthException with {@link ACoviaComponent#READ_ONLY} for anything but a workspace or its entry
	 */
	public static Entry of(ALatticeComponent<?> destination) {
		if (destination instanceof Entry entry) return entry;
		if (destination instanceof Workspace area) return area.entry(CoviaPath.EMPTY);
		throw new AuthException(READ_ONLY);
	}

	/** The stretch of the caller's path the workspace read; it formats as the whole address. */
	public Path path() {
		return path;
	}

	/** Whether a value is here; a stored null counts. */
	@Override
	public boolean exists() {
		if (keys.length == 0) return cursor().getOrDefault(null) != null;
		return resolved.refusal() == null && holds(above.getOrDefault(null), last());
	}

	/**
	 * Puts {@code value} here.
	 *
	 * @return Whether a value was here already
	 */
	public boolean write(ACell value) {
		if (keys.length == 0) return cursor().getAndUpdate(current -> value) != null;
		ACell before = changeAbove(container -> put(container, last(), value), false);
		return holds(before, last());
	}

	/**
	 * Replaces the value here with {@code change} applied to it, null when
	 * absent. {@code change} may run again on a retry, so it must be pure.
	 *
	 * @return The new value
	 */
	public ACell update(UnaryOperator<ACell> change) {
		if (keys.length == 0) return cursor().updateAndGet(change);
		ACell after = changeAbove(container -> put(container, last(), change.apply(valueIn(container, last()))), true);
		return valueIn(after, last());
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
		if (keys.length == 0) return cursor().getAndUpdate(current -> null) != null;
		if (resolved.refusal() != null) return false;
		ACell before = above.getAndUpdate(container -> {
			if (!holds(container, last())) return container;
			if (container instanceof AVector<?> list) {
				return ((AVector<ACell>) list).assoc(((CVMLong) keys[last()]).longValue(), null);
			}
			return ((AMap<ACell, ACell>) container).dissoc(keys[last()]);
		});
		return holds(before, last());
	}

	/**
	 * How many levels of the path are absent: none when a value is here,
	 * otherwise those from the first missing one down to this one. A change
	 * after {@link #createLevels} makes exactly these.
	 */
	public int missingLevels() {
		ACell node = area.cursor().getOrDefault(null);
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
		return createLevels(keys.length - 1);
	}

	/**
	 * Creates a map at each of the first {@code count} levels of the path that
	 * holds nothing, in one atomic update of the workspace.
	 *
	 * @return How many levels were created
	 */
	int createLevels(int count) {
		refuseUnreachable();
		if (count < 1) return 0;
		ACell before = area.cursor().getAndUpdate(root -> createBelow(root == null ? Maps.empty() : root, 0, count));
		return absentLevels(before, count);
	}

	/**
	 * Runs {@code action} in one transaction over the workspace, on an entry
	 * with this one's keys, so that levels it creates commit with its change.
	 */
	public void execute(Consumer<? super Entry> action) {
		area.execute(draft -> action.accept(new Entry(draft, path, resolved)));
	}

	/**
	 * As {@link #execute}, returning what {@code action} returned in the run
	 * that committed.
	 */
	public <R> R transact(Function<? super Entry, ? extends R> action) {
		return area.transact(draft -> action.apply(new Entry(draft, path, resolved)));
	}

	// ========== Levels ==========

	private int last() {
		return keys.length - 1;
	}

	/**
	 * Changes the level above this entry in one atomic update. An absent
	 * workspace root counts as an empty map; any other missing level is refused.
	 * {@code edit} returns the level it is given when nothing changes.
	 *
	 * @return The level after the change when {@code after}, otherwise before it
	 */
	private ACell changeAbove(UnaryOperator<ACell> edit, boolean after) {
		refuseUnreachable();
		UnaryOperator<ACell> step = value -> {
			ACell container = (value == null && keys.length == 1) ? Maps.empty() : value;
			if (container == null) throw noLevel();
			ACell changed = edit.apply(container);
			return changed == container ? value : changed;
		};
		return after ? above.updateAndGet(step) : above.getAndUpdate(step);
	}

	/** {@code node}, the value at level {@code i}, with the levels below it created down to level {@code count}. */
	private ACell createBelow(ACell node, int i, int count) {
		if (i >= count) return node;
		ACell child = valueIn(node, i);
		return put(node, i, createBelow((child == null) ? Maps.empty() : child, i + 1, count));
	}

	/**
	 * How many of the first {@code count} levels under {@code root} hold
	 * nothing: those {@link #createBelow} creates. Below the first such level
	 * every level is absent too.
	 */
	private int absentLevels(ACell root, int count) {
		ACell node = root;
		for (int i = 0; i < count; i++) {
			node = valueIn(node, i);
			if (node == null) return count - i;
		}
		return 0;
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
			throw new InvalidInputException(refusal != null ? refusal : String.format(CHANGED, CoviaPath.address(path.slice(0, i))));
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
		String at = CoviaPath.address(path.slice(0, first + 1));
		return new NotFoundException(String.format(ValuePath.NO_LEVEL, at), at);
	}
}
