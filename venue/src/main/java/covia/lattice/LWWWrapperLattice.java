package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Whole-value LWW lattice over a {@code {updated, data}} wrapper, used for
 * the user-writable namespaces ({@code w/}, {@code o/}, {@code h/}) that
 * must support durable deletion.
 *
 * <p>Per-entry merge lattices (MapLattice / JSONValueLattice) are additive:
 * a merge unions keys, so a deleted key is re-introduced whenever the live
 * cursor is merged with any pre-delete snapshot — which the NodeServer
 * propagator does on every announce round-trip. A join semilattice cannot
 * express removal per-entry; it can only express it by replacing a whole
 * value under a total order. This lattice provides that: the namespace
 * value is {@code {updated: <millis>, data: <map>}}, merged as a single
 * unit — the higher {@code updated} stamp wins wholesale, ties prefer the
 * own (local) value. The {@code :schedule} slot made the same trade for
 * the same reason (see {@code GRID_SCHEDULER.md} §8).</p>

 * <p>Every write through {@code covia:write} / {@code covia:delete} /
 * {@code covia:append} replaces the wrapper with a freshly stamped one,
 * and the stamp is <b>strictly increasing</b> ({@code max(now, current+1)},
 * the same rule as {@code Scheduler.nextStamp}): fast sequential writes
 * within one millisecond each dominate the value they replace in either
 * merge argument order, so the last write applied always wins. Equal-stamp
 * ties can therefore only arise between genuinely divergent replicas, where
 * preferring own is the documented policy. The accepted
 * trade-off: if a user's namespace were ever edited concurrently on two
 * venues and merged, the older side's edits lose wholesale. Today a user's
 * workspace has a single authoritative venue, so the only real merges are
 * self-merges with earlier snapshots.</p>
 *
 * <h2>Path opacity</h2>
 * <p>{@link #resolveKey} returns null: the wrapper has no externally
 * addressable children, so generic lattice path resolution stops at the
 * namespace root. Navigation inside the namespace is handled by
 * {@code CoviaAdapter}, which unwraps via {@link #unwrap} and navigates
 * the data value directly. This also means the wrapper internals
 * ({@code updated}, {@code data}) are not addressable as path segments —
 * a user key named {@code "data"} lives inside the data map and never
 * collides with the wrapper's own field.</p>
 *
 * <h2>Legacy values</h2>
 * <p>Values persisted before this lattice existed are raw maps without the
 * wrapper. {@link #unwrap} passes them through unchanged (read-compatible)
 * and {@link #timestamp} treats them as stamp 0, so the first stamped write
 * dominates and migrates the namespace in place.</p>
 */
public final class LWWWrapperLattice extends ALattice<ACell> {

	/** Wrapper timestamp field (epoch millis, set on every write). */
	public static final AString KEY_UPDATED = Strings.intern("updated");

	/** Wrapper payload field — the namespace's actual key→value map. */
	public static final AString KEY_DATA = Strings.intern("data");

	public static final LWWWrapperLattice INSTANCE = new LWWWrapperLattice();

	private LWWWrapperLattice() {}

	@Override
	public ACell merge(ACell own, ACell other) {
		if (own == null) return other;
		if (other == null) return own;
		// Higher stamp wins wholesale; ties prefer own (reduces churn and
		// risk from spurious incoming values — same policy as LWWLattice).
		return (timestamp(other) > timestamp(own)) ? other : own;
	}

	@Override
	public ACell zero() {
		return null;
	}

	@Override
	public boolean checkForeign(ACell value) {
		return true;
	}

	/** No externally addressable children — see class javadoc. */
	@Override
	public ACell resolveKey(ACell key) {
		return null;
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}

	/**
	 * Extracts the LWW stamp from a stored value. Non-wrapper values
	 * (legacy raw maps, null) stamp as 0 so any stamped write dominates.
	 *
	 * @param value Stored namespace value
	 * @return Stamp in epoch millis, or 0 for non-wrapper values
	 */
	@SuppressWarnings("unchecked")
	public static long timestamp(ACell value) {
		if (isWrapper(value)) {
			return ((CVMLong) ((AMap<ACell, ACell>) value).get(KEY_UPDATED)).longValue();
		}
		return 0;
	}

	/**
	 * True iff the value has the exact wrapper shape: a two-entry map of
	 * {@code updated} (integer) and {@code data}. The check is strict so
	 * legacy user data is never misread as a wrapper.
	 *
	 * @param value Value to test
	 * @return true for the canonical wrapper shape
	 */
	@SuppressWarnings("unchecked")
	public static boolean isWrapper(ACell value) {
		if (!(value instanceof AMap<?, ?> m)) return false;
		if (m.count() != 2) return false;
		AMap<ACell, ACell> map = (AMap<ACell, ACell>) m;
		return (map.get(KEY_UPDATED) instanceof CVMLong) && map.containsKey(KEY_DATA);
	}

	/**
	 * Returns the namespace data for a stored value: the wrapper's
	 * {@code data} field, or the value itself for legacy unwrapped maps.
	 *
	 * @param value Stored namespace value (wrapper, legacy raw map, or null)
	 * @return Namespace data, or null
	 */
	@SuppressWarnings("unchecked")
	public static ACell unwrap(ACell value) {
		if (isWrapper(value)) return ((AMap<ACell, ACell>) value).get(KEY_DATA);
		return value;
	}

	/**
	 * Builds a stamped wrapper around namespace data. Null data is
	 * normalised to an empty map so a delete of the last key still produces
	 * a stamped wrapper that dominates earlier snapshots (a null namespace
	 * value would lose the merge and resurrect them).
	 *
	 * @param timestamp Stamp in epoch millis
	 * @param data Namespace data map (null treated as empty)
	 * @return Wrapper map {@code {updated, data}}
	 */
	public static AMap<AString, ACell> wrap(long timestamp, ACell data) {
		return Maps.of(KEY_UPDATED, CVMLong.create(timestamp),
			KEY_DATA, (data == null) ? Maps.empty() : data);
	}
}
