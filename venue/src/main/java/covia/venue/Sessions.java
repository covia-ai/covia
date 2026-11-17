package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.IndexComponent;
import convex.lattice.NoSuchPathException;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.ValuePath;

/**
 * An agent's sessions ({@code g/<id>/sessions}), keyed by session id.
 * {@link #session} gives one session, which has its own operations
 * ({@link Session}).
 *
 * <p>The list has no policy of its own: the agent's covers it, and each
 * session has its own. The cursor sits inside the agent's stamped record, so
 * every change stamps the agent. Whether an agent may take a new session is
 * the agent's rule ({@link Agent#addTaskInSession}); this collection only
 * keeps the sessions.</p>
 *
 * <p>A removal is durable: the venue merge is whole-value LWW, so the
 * snapshot without the session wins by timestamp and the session is not
 * resurrected on sync.</p>
 */
public final class Sessions extends IndexComponent<Blob, ACell>
		implements TransactionalComponent<Index<Blob, ACell>, Sessions> {

	private final VenueContext context;
	private final AString owner;
	private final AString agentId;

	/**
	 * @param owner DID of the user whose agent holds the sessions, or null outside any user
	 * @param agentId The agent that holds the sessions
	 */
	Sessions(VenueContext context, AForkableCursor<Index<Blob, ACell>> cursor, AString owner, AString agentId) {
		super(cursor);
		this.context = context;
		this.owner = owner;
		this.agentId = agentId;
	}

	@Override
	public Sessions rebind(AForkableCursor<Index<Blob, ACell>> cursor) {
		return new Sessions(context, cursor, owner, agentId);
	}

	/** {@code <hex>} names a session, whether or not it exists; anything else names nothing. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		Blob id = ValuePath.blobKey(segments[start]);
		if (id == null) throw new NoSuchPathException(segments, start, end);
		return session(id).resolve(segments, start + 1, end);
	}

	/** Every session's record, by id; empty when there are none. */
	public Index<Blob, ACell> all() {
		Index<Blob, ACell> sessions = get();
		return (sessions == null) ? Index.none() : sessions;
	}

	/** The session under {@code id}, which need not exist. */
	public Session session(Blob id) {
		return new Session(context, path(id), owner, agentId, id);
	}

	/**
	 * Creates a session: an empty root frame, no pending messages and its
	 * metadata ({@link Session#newSession}).
	 *
	 * @param caller Recorded as the session's first party, or null for none
	 * @param loads The root frame's loads, or null for none
	 * @return whether it was created: false when a session is already under {@code id}
	 */
	public boolean create(Blob id, AString caller, AMap<AString, ACell> loads) {
		AMap<AString, ACell> session = Session.newSession(caller, loads, context.currentTimestamp());
		return transact(draft -> {
			if (draft.all().get(id) != null) return false;
			draft.put(id, session);
			return true;
		});
	}

	/** Whether any session has messages waiting. */
	public boolean hasPending() {
		for (var entry : all().entrySet()) {
			AMap<AString, ACell> session = RT.ensureMap(entry.getValue());
			if (session != null && Session.sessionHasWork(session)) return true;
		}
		return false;
	}

	/**
	 * The session whose oldest waiting message is the oldest of any session's
	 * ({@link Session#queueKey(ACell)}), or null when none has one: the
	 * session that is next in the agent's queue.
	 */
	public Blob oldestWithPending() {
		Blob oldest = null;
		long oldestKey = Long.MAX_VALUE;
		for (var entry : all().entrySet()) {
			AMap<AString, ACell> session = RT.ensureMap(entry.getValue());
			if (session == null || !Session.sessionHasWork(session)) continue;
			long key = Session.queueKey(((AVector<?>) session.get(Session.K_PENDING)).get(0));
			if (oldest == null || key < oldestKey) {
				oldest = entry.getKey();
				oldestKey = key;
			}
		}
		return oldest;
	}
}
