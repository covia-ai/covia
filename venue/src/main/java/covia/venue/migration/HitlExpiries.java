package covia.venue.migration;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.adapter.HITLAdapter;
import covia.api.Fields;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * Part of venue version 1 to 2, applied after {@link CallerAndOwner}: an open
 * HITL request with a timeout expires through the venue's schedule. A venue
 * before this kept its expiry in memory and re-armed it at each start, so each
 * open request with {@code expires} gets the one-shot event a request asked
 * now schedules: {@link HITLAdapter#EXPIRE_OP} at its {@code expires} time,
 * made and fired by the venue, untracked. A request already overdue gets one
 * too, which the venue's first update fires.
 *
 * <p>The event's key is its time followed by the last eight bytes of the
 * request's ID, so converting again changes nothing. A request whose ID is
 * not a job ID gets none: the expiry operation could not find its Job, and
 * answering it after its time still finds it expired.</p>
 */
public final class HitlExpiries implements MigrationStep {

	static final AString EVENTS = Strings.intern("events");
	static final AString OP = Strings.intern("op");
	static final AString TIME = Strings.intern("time");
	static final AString INPUT = Strings.intern("input");
	static final AString TRACK = Strings.intern("track");

	/** Refusal of a venue holding a timed request but no DID to fire its expiry as. */
	public static final String NO_VENUE_DID = "Venue holds HITL requests with timeouts but no DID to expire them as";

	@Override
	@SuppressWarnings("unchecked")
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> value)) return venue;
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) value;
		if (!(record.get(Covia.USER_DATA) instanceof AMap<?, ?> users)) return venue;
		ACell schedule = record.get(Covia.SCHEDULE);
		AMap<AString, ACell> scheduleRecord = (schedule instanceof AMap<?, ?> s) ? (AMap<AString, ACell>) s : Maps.empty();
		ACell held = scheduleRecord.get(EVENTS);
		Index<Blob, ACell> events = (held instanceof Index<?, ?> index) ? (Index<Blob, ACell>) index : Index.none();
		Index<Blob, ACell> converted = events;
		AString venueDID = RT.ensureString(record.get(Covia.DID));
		for (var user : users.entrySet()) {
			if (!(user.getKey() instanceof AString target)) continue;
			ACell items = RT.getIn(user.getValue(), Namespace.H, Covia.WORKSPACE_DATA);
			if (!(items instanceof AMap<?, ?> byId)) continue;
			for (var item : byId.entrySet()) {
				if (!(item.getValue() instanceof AMap<?, ?> request)) continue;
				if (!Hitl.OPEN.equals(request.get(Hitl.STATUS))) continue;
				if (!(request.get(Hitl.EXPIRES) instanceof CVMLong expires)) continue;
				if (!(item.getKey() instanceof AString id)) continue;
				Blob jobId = Blob.parse(id.toString());
				if (jobId == null || jobId.count() < 8) continue;
				if (venueDID == null) throw new IllegalStateException(NO_VENUE_DID);
				Blob key = key(expires.longValue(), jobId);
				if (converted.containsKey(key)) continue;
				converted = converted.assoc(key, event(venueDID, target, id, expires, request.get(Hitl.CREATED)));
			}
		}
		if (converted == events) return venue;
		return record.assoc(Covia.SCHEDULE, scheduleRecord.assoc(EVENTS, converted));
	}

	/** The expiry event of request {@code id} in {@code target}'s inbox, as the venue schedules it. */
	static AMap<AString, ACell> event(AString venueDID, AString target, AString id, CVMLong expires, ACell created) {
		AMap<AString, ACell> event = Maps.of(
			OP, HITLAdapter.EXPIRE_OP,
			TIME, expires,
			Fields.CALLER, venueDID,
			INPUT, HITLAdapter.expiryInput(target, id),
			TRACK, CVMBool.FALSE,
			Fields.DESCRIPTION, Strings.create("Expires HITL request " + id));
		return (created instanceof CVMLong) ? event.assoc(Fields.CREATED, created) : event;
	}

	/** The event's key: its time, then the last eight bytes of the request's job ID. */
	static Blob key(long time, Blob jobId) {
		byte[] bs = new byte[16];
		Utils.writeLong(bs, 0, time);
		byte[] id = jobId.getBytes();
		System.arraycopy(id, id.length - 8, bs, 8, 8);
		return Blob.wrap(bs);
	}
}
