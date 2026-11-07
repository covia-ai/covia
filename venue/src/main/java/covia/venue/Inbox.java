package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.CoviaValue;

/**
 * A user's HITL inbox ({@code h/}, COG-16): request records keyed by request
 * id, the hex id of the request's job.
 *
 * <p>Only the venue creates and resolves records; callers read them, and
 * {@code covia:write} cannot change them. The cursor is the {@code h} region's
 * data inside its stamped envelope, so every change stamps the region.</p>
 */
public final class Inbox extends ALatticeComponent<ACell> {

	Inbox(AForkableCursor<ACell> cursor) {
		super(cursor);
	}

	/** A request id and the fields below it name data in a record, read-only. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		return CoviaValue.within(this, segments, start, end);
	}

	/** Every request record, keyed by request id; empty when there are none. */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getAll() {
		ACell v = cursor().getOrDefault(null);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : Maps.empty();
	}

	/**
	 * Gets one request record.
	 *
	 * @param id Request id
	 * @return The record, or null if absent
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> get(AString id) {
		ACell v = getAll().get(id);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/**
	 * Writes a request record, replacing any with the same id.
	 *
	 * @param id Request id
	 * @param record Request record
	 */
	@SuppressWarnings("unchecked")
	public void put(AString id, AMap<AString, ACell> record) {
		cursor().updateAndGet(data -> {
			AMap<AString, ACell> m = (data instanceof AMap) ? (AMap<AString, ACell>) data : Maps.empty();
			return m.assoc(id, record);
		});
	}
}
