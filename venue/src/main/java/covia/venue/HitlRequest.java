package covia.venue;

import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.grid.Attribution;
import covia.grid.hitl.Hitl;
import covia.lattice.CoviaValue;

/**
 * One HITL request in a user's {@link Inbox} (COG-16): what is asked, of whom,
 * and how it was resolved. Its id is the hex id of the job that carries it.
 *
 * <p>A request is open until it is resolved, once: answered, rejected, expired
 * or cancelled ({@link #resolve}). Its record reads as stored, and nothing
 * else changes it.</p>
 */
public final class HitlRequest extends ALatticeComponent<AMap<AString, ACell>>
		implements TransactionalComponent<AMap<AString, ACell>, HitlRequest> {

	/** Refusal of a status that does not resolve a request. */
	public static final String NOT_A_RESOLUTION = "Not a status that resolves a HITL request: ";

	private static final Set<AString> RESOLUTIONS =
		Set.of(Hitl.ANSWERED, Hitl.REJECTED, Hitl.EXPIRED, Hitl.CANCELLED);

	private final AString id;

	HitlRequest(AForkableCursor<AMap<AString, ACell>> cursor, AString id) {
		super(cursor);
		this.id = id;
	}

	@Override
	public HitlRequest rebind(AForkableCursor<AMap<AString, ACell>> cursor) {
		return new HitlRequest(cursor, id);
	}

	@Override
	public HitlRequest withCursor(AForkableCursor<AMap<AString, ACell>> cursor) {
		return rebind(cursor);
	}

	/** The request's id: the hex id of the job that carries it. */
	public AString getID() {
		return id;
	}

	/** The request's record as stored, or null when the inbox holds none under this id. */
	public AMap<AString, ACell> getRecord() {
		return RT.ensureMap(cursor().getOrDefault(null));
	}

	/** The request's status, or null when there is no request. */
	public AString status() {
		AMap<AString, ACell> record = getRecord();
		return (record == null) ? null : RT.ensureString(record.get(Hitl.STATUS));
	}

	/** Whether the request is waiting for an answer. */
	public boolean isOpen() {
		return Hitl.OPEN.equals(status());
	}

	/** Whether the request names an expiry that {@code nowMs} has passed. */
	public boolean isExpired(long nowMs) {
		CVMLong expires = RT.ensureLong(RT.getIn(getRecord(), Hitl.EXPIRES));
		return expires != null && nowMs > expires.longValue();
	}

	/**
	 * Resolves the request, if it is still open.
	 *
	 * @param status {@code answered}, {@code rejected}, {@code expired} or {@code cancelled}
	 * @param response What the responder said, or null (an expiry or a cancellation)
	 * @return whether this resolved it: false when there is no request or it is no longer open
	 * @throws IllegalArgumentException for any other status ({@link #NOT_A_RESOLUTION})
	 */
	public boolean resolve(AString status, AMap<AString, ACell> response) {
		if (!RESOLUTIONS.contains(status)) throw new IllegalArgumentException(NOT_A_RESOLUTION + status);
		return transact(draft -> {
			AMap<AString, ACell> record = draft.getRecord();
			if (record == null || !Hitl.OPEN.equals(record.get(Hitl.STATUS))) return false;
			AMap<AString, ACell> resolved = record.assoc(Hitl.STATUS, status);
			if (response != null) resolved = resolved.assoc(Hitl.RESPONSE, response);
			draft.cursor().set(resolved);
			return true;
		});
	}

	/** The fields below the request read as stored. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		return CoviaValue.within(this, segments, start, end);
	}

	/**
	 * A new open request's record.
	 *
	 * @param id The id of the job that carries it, in hex
	 * @param caller Who asked: the verified acting principal
	 * @param owner Whose request it is, recorded only when not the caller
	 * @param agentId The asking agent, or null
	 * @param sessionId The asking agent's session, or null
	 * @param title What is asked, in a line
	 * @param description More about it, or null
	 * @param asks The validated asks
	 * @param created When it was made, in ms
	 * @param expires When it expires, in ms, or null for never
	 */
	public static AMap<AString, ACell> record(AString id, AString caller, AString owner, AString agentId,
			Blob sessionId, AString title, AString description, AVector<ACell> asks, long created, Long expires) {
		AMap<AString, ACell> record = Attribution.attribute(Maps.of(
			Hitl.ID, id,
			Hitl.TITLE, title,
			Hitl.ASKS, asks,
			Hitl.STATUS, Hitl.OPEN,
			Hitl.CREATED, CVMLong.create(created)), caller, owner);
		if (description != null) record = record.assoc(Hitl.DESCRIPTION, description);
		if (agentId != null) {
			record = record.assoc(Hitl.AGENT, agentId);
			if (sessionId != null) {
				record = record.assoc(Fields.SESSION_ID, Strings.create(sessionId.toHexString()));
			}
		}
		if (expires != null) record = record.assoc(Hitl.EXPIRES, CVMLong.create(expires));
		return record;
	}
}
