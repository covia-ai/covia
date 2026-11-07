package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.lattice.ALatticeComponent;
import convex.lattice.MapComponent;
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
public final class Inbox extends MapComponent<AString, AMap<AString, ACell>> {

	Inbox(AForkableCursor<AHashMap<AString, AMap<AString, ACell>>> cursor) {
		super(cursor);
	}

	/** A request id and the fields below it name data in a record, read-only. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		return CoviaValue.within(this, segments, start, end);
	}

	/** Every request record, keyed by request id; empty when there are none. */
	public AMap<AString, AMap<AString, ACell>> getAll() {
		AMap<AString, AMap<AString, ACell>> records = get();
		return (records == null) ? Maps.empty() : records;
	}
}
