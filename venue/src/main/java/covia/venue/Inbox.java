package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.lattice.ALatticeComponent;
import convex.lattice.IndexComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.grid.hitl.Hitl;
import covia.lattice.CoviaValue;

/**
 * A user's HITL inbox ({@code h/}, COG-16): the requests addressed to them,
 * keyed by request id, the id of the request's job. A path names a request by
 * its id in hex. {@link #open} adds a request and {@link #request} gives one,
 * which resolves itself ({@link HitlRequest}).
 *
 * <p>Only the venue opens and resolves requests; callers read them, and
 * {@code covia:write} cannot change them. The cursor is the {@code h} region's
 * data inside its stamped envelope, so every change stamps the region.</p>
 */
public final class Inbox extends IndexComponent<Blob, AMap<AString, ACell>>
		implements TransactionalComponent<Index<Blob, AMap<AString, ACell>>, Inbox> {

	/** Refusal of a record that is not a new open request with an id. */
	public static final String NOT_A_NEW_REQUEST = "Not a new open HITL request: ";

	Inbox(AForkableCursor<Index<Blob, AMap<AString, ACell>>> cursor) {
		super(cursor);
	}

	@Override
	public Inbox rebind(AForkableCursor<Index<Blob, AMap<AString, ACell>>> cursor) {
		return new Inbox(cursor);
	}

	/** A request id and the fields below it name data in a record, read-only. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		return CoviaValue.within(this, segments, start, end);
	}

	/** Every request record, keyed by request id; empty when there are none. */
	public Index<Blob, AMap<AString, ACell>> getAll() {
		Index<Blob, AMap<AString, ACell>> records = get();
		return (records == null) ? Index.none() : records;
	}

	/**
	 * Adds a new open request ({@link HitlRequest#record}).
	 *
	 * @return whether it was added: false when the inbox already holds a request under its id
	 * @throws IllegalArgumentException when the record has no id or is not open
	 */
	public boolean open(AMap<AString, ACell> record) {
		Blob id = Ids.stored(record.get(Hitl.ID));
		if (id == null || !Hitl.OPEN.equals(record.get(Hitl.STATUS))) {
			throw new IllegalArgumentException(NOT_A_NEW_REQUEST + record);
		}
		return transact(draft -> {
			if (draft.containsKey(id)) return false;
			draft.put(id, record);
			return true;
		});
	}

	/** The request under {@code id}, which need not exist. */
	public HitlRequest request(Blob id) {
		return new HitlRequest(path(id), id);
	}
}
