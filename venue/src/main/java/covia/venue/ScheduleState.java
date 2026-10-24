package covia.venue;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.lattice.Covia;

/** Stored schedule records and recurrence rules. No timers or job execution. */
public final class ScheduleState extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, ScheduleState> {
	private final LatticeContext context;
	private final AForkableCursor<ACell> edits;

	ScheduleState(LatticeContext context, AForkableCursor<ACell> cursor) {
		super(cursor);
		this.context = context;
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampUpdated);
	}

	@Override public AForkableCursor<ACell> cursor() { return edits; }
	@Override public ScheduleState withCursor(AForkableCursor<ACell> draft) {
		return new ScheduleState(context, draft);
	}

	public record Entry(Blob key, AMap<AString, ACell> rec) {}

	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> events() {
		ACell value = cursor().path(K_EVENTS).get();
		return value instanceof Index<?, ?> ? (Index<Blob, ACell>) value : Index.none();
	}

	@SuppressWarnings("unchecked")
	private void updateEvents(UnaryOperator<Index<Blob, ACell>> update) {
		cursor().withDefault(Maps.empty()).path(K_EVENTS).updateAndGet(value ->
			update.apply(value instanceof Index<?, ?> ? (Index<Blob, ACell>) value : Index.none()));
	}

	public Blob schedule(AString op, ACell input, AString owner, AString actor, AString by,
			AVector<ACell> proofs, AVector<ACell> caps, long wakeTime,
			AMap<AString, ACell> repeat, Boolean track, AString description, long created) {
		Blob key = mintKey(wakeTime);
		AMap<AString, ACell> rec = Maps.of(K_OP, op, K_OWNER, owner, K_BY, by,
			K_TIME, CVMLong.create(wakeTime), K_CREATED, CVMLong.create(created));
		if (actor != null) rec = rec.assoc(K_ACTOR, actor);
		if (description != null) rec = rec.assoc(K_DESCRIPTION, validateDescription(description));
		if (input != null) rec = rec.assoc(K_INPUT, input);
		if (proofs != null) rec = rec.assoc(K_PROOFS, proofs);
		if (caps != null) rec = rec.assoc(K_CAPS, caps);
		if (repeat != null) rec = rec.assoc(K_REPEAT, validateRepeat(repeat));
		if (track != null) rec = rec.assoc(K_TRACK, CVMBool.create(track));
		var record = rec;
		updateEvents(events -> events.assoc(key, record));
		return key;
	}

	void remove(Blob key) { updateEvents(events -> events.dissoc(key)); }

	public boolean cancel(Blob handle, AString owner) {
		boolean[] cancelled = {false};
		transact(draft -> {
			Entry entry = draft.find(handle);
			cancelled[0] = entry != null;
			if (entry == null) return;
			requireOwner(entry.rec(), owner);
			draft.remove(entry.key());
		});
		return cancelled[0];
	}

	void cancelUser(AString owner) {
		updateEvents(events -> {
			for (var entry : events.entrySet()) {
				if (owner.equals(RT.getIn(entry.getValue(), K_OWNER))) events = events.dissoc(entry.getKey());
			}
			return events;
		});
	}

	public static final String NOT_OWNER = "Not the owner of this scheduled event";

	static void requireOwner(AMap<AString, ACell> record, AString caller) {
		if (caller == null || !caller.equals(record.get(K_OWNER))) throw new AuthException(NOT_OWNER);
	}

	/** Complete a fire's schedule changes; job preparation remains outside this component. */
	void recordFire(Entry entry, long now, boolean advance, Blob jobId) {
		var rec = entry.rec();
		var repeat = repeatOf(rec);
		if (repeat == null) { updateEvents(events -> events.dissoc(entry.key())); return; }
		var next = rec.assoc(K_LAST_FIRED, CVMLong.create(now));
		if (jobId != null) next = next.assoc(K_LAST_JOB, jobId);
		Blob nextKey = entry.key();
		if (advance) {
			long time = nextTime(repeat, timeOf(rec), now);
			next = next.assoc(K_TIME, CVMLong.create(time));
			nextKey = rekey(nextKey, time);
		}
		var key = nextKey;
		var record = next;
		updateEvents(events -> events.dissoc(entry.key()).assoc(key, record));
	}

	/** Called with the agent on the same venue draft; the venue owns the compound commit. */
	Blob replaceAgentWake(AString owner, AString agentId, Blob oldHandle, long earliest) {
		if (oldHandle != null) {
			var old = find(oldHandle);
			if (old != null && owner.equals(old.rec().get(K_OWNER))
					&& AgentState.TRIGGER_OP.equals(old.rec().get(K_OP))
					&& agentId.equals(RT.getIn(old.rec(), K_INPUT, Fields.AGENT_ID))) {
				updateEvents(events -> events.dissoc(old.key()));
			}
		}
		return earliest <= 0 ? null : schedule(AgentState.TRIGGER_OP,
			Maps.of(Fields.AGENT_ID, agentId, Fields.FORCE, CVMBool.FALSE, Fields.WAIT, CVMBool.FALSE),
			owner, null, owner, null, null, earliest, null, false, null,
			context.currentTimestamp().longValue());
	}

	/** Event record field: the operation reference to invoke (AString). */
	static final AString K_OP = Strings.intern("op");
	/** Event record field: the input cell passed to the operation. */
	static final AString K_INPUT = Strings.intern("input");
	/** Event record field: the owning user DID. Ownership key for
	 *  list/cancel/trigger, and the namespace the fired operation runs in. */
	static final AString K_OWNER = Strings.intern("owner");
	/** Event record field: the agent sub-principal that scheduled this event,
	 *  when not the owner itself. Mirrors {@code Fields.ACTOR} on job records —
	 *  the event fires as this identity, but the OWNER still administers it, so
	 *  a user keeps sight of what their agents have queued. */
	static final AString K_ACTOR = Strings.intern("actor");
	/** Event record field: the DID of the caller that scheduled this event,
	 *  always set — the owner's own DID when the user scheduled it directly.
	 *  Attribution for {@link Scheduler#list} only; it repeats {@link #K_ACTOR} for an
	 *  agent, and firing never reads it. Absent on events stored before it was
	 *  recorded. */
	static final AString K_BY = Strings.intern("by");
	/** Event record field: caller-written display text saying what the event is
	 *  for (AString, at most {@link #MAX_DESCRIPTION_CHARS}). Never interpreted. */
	static final AString K_DESCRIPTION = Fields.DESCRIPTION;
	/** Event record field: wall-clock millis at which the event was scheduled
	 *  (CVMLong). Absent on events stored before it was recorded. */
	static final AString K_CREATED = Fields.CREATED;
	/** Event record field: captured UCAN proofs (AVector), replayed at fire time. */
	static final AString K_PROOFS = Strings.intern("proofs");
	/** Event record field: captured capability attenuations (AVector). */
	static final AString K_CAPS = Strings.intern("caps");
	/** Event record field: absolute wake time in millis (CVMLong). */
	static final AString K_TIME = Strings.intern("time");
	/** Event record field: recurrence spec (AMap, see {@link #validateRepeat});
	 *  absent ⇒ one-shot. */
	static final AString K_REPEAT = Strings.intern("repeat");
	/** Recurrence spec field: fixed interval between fires in millis (CVMLong).
	 *  The only recurrence understood today; calendar forms may join it later. */
	static final AString K_EVERY = Strings.intern("every");
	/** Event record field: the caller's explicit tracking choice (CVMBool);
	 *  absent ⇒ the venue default applies at fire time. */
	static final AString K_TRACK = Strings.intern("track");
	/** Event record field: wall-clock millis of the most recent fire (CVMLong).
	 *  Only a recurring record survives a fire, so only it carries this. */
	static final AString K_LAST_FIRED = Strings.intern("lastFired");
	/** Event record field: ID of the durable Job produced by the most recent
	 *  tracked fire (Blob) — the one link from a schedule to its executions. */
	static final AString K_LAST_JOB = Strings.intern("lastJob");
	/** Scheduler-record field holding the time-ordered events {@link Index}. */
	static final AString K_EVENTS = Strings.intern("events");
	/** List-result field: the event handle (its index key). */
	static final AString K_HANDLE = Strings.intern("handle");

	/** Floor on {@code repeat.every}: the first abuse guard against tight loops. */
	public static final long MIN_REPEAT_MS = 1_000L;

	/** Longest {@code description} an event may carry, in characters. */
	public static final int MAX_DESCRIPTION_CHARS = 500;
	/** Refusal for a {@code description} that is not a string. */
	public static final String DESCRIPTION_NOT_STRING = "description must be a string";
	/** Refusal for a {@code description} longer than {@link #MAX_DESCRIPTION_CHARS}. */
	public static final String DESCRIPTION_TOO_LONG =
		"description must be at most " + MAX_DESCRIPTION_CHARS + " characters";

	/** Index key length: 8-byte big-endian wake time + 8-byte id. */
	private static final int KEY_LENGTH = 16;

	/**
	 * Validate a recurrence spec and return its canonical form. The only form
	 * understood today is {@code {every: <millis>}} with
	 * {@code every >= MIN_REPEAT_MS}; anything else is rejected outright rather
	 * than guessed at, so a calendar form added later cannot be silently
	 * misread by an older venue.
	 */
	public static AMap<AString, ACell> validateRepeat(AMap<AString, ACell> repeat) {
		if (repeat.count() != 1 || !repeat.containsKey(K_EVERY)) {
			throw new IllegalArgumentException("repeat must be {every: <millis>}");
		}
		CVMLong every = RT.ensureLong(repeat.get(K_EVERY));
		if (every == null || every.longValue() < MIN_REPEAT_MS) {
			throw new IllegalArgumentException(
				"repeat.every must be an integer of at least " + MIN_REPEAT_MS + " millis");
		}
		return Maps.of(K_EVERY, every);
	}

	/**
	 * Validate an event description and return it as a string, or null when
	 * absent. It must be a string of at most {@link #MAX_DESCRIPTION_CHARS}
	 * characters; anything else is refused with {@link #DESCRIPTION_NOT_STRING}
	 * or {@link #DESCRIPTION_TOO_LONG}.
	 */
	public static AString validateDescription(ACell description) {
		if (description == null) return null;
		if (!(description instanceof AString s)) {
			throw new IllegalArgumentException(DESCRIPTION_NOT_STRING);
		}
		if (s.toString().length() > MAX_DESCRIPTION_CHARS) {
			throw new IllegalArgumentException(DESCRIPTION_TOO_LONG);
		}
		return s;
	}

	/** The fixed interval of a validated {@code {every}} spec. */
	public static long everyOf(AMap<AString, ACell> repeat) {
		return RT.ensureLong(repeat.get(K_EVERY)).longValue();
	}

	/**
	 * Next due time for a recurring event that was due at {@code time} and is
	 * firing at {@code now}: the first slot on the {@code time + n·every} grid
	 * strictly after {@code now}. Keeping the grid's phase makes "every hour"
	 * stay on the hour it started on; skipping missed slots means a backlog
	 * (venue down for a day) collapses into the single catch-up fire that just
	 * happened — never a burst.
	 */
	static long nextTime(AMap<AString, ACell> repeat, long time, long now) {
		long every = everyOf(repeat);
		long next = time + every;
		if (next <= now) next = time + ((now - time) / every + 1) * every;
		return next;
	}

	/**
	 * Locate an event by handle: the exact key, else — because a recurring
	 * event is re-keyed on every fire while its id suffix stays fixed — the
	 * entry whose id matches. Schedules are small, so the fallback scan is
	 * cheap and only ever runs for a handle that has moved on.
	 */
	public Entry find(Blob handle) {
		Index<Blob, ACell> idx = events();
		ACell rec = idx.get(handle);
		if (rec instanceof AMap) return new Entry(handle, asMap(rec));
		if (handle.count() != KEY_LENGTH) return null;
		byte[] h = handle.getBytes();
		long cnt = idx.count();
		for (long i = 0; i < cnt; i++) {
			var e = idx.entryAt(i);
			Blob k = (Blob) e.getKey();
			if (k.count() != KEY_LENGTH || !(e.getValue() instanceof AMap)) continue;
			if (Arrays.equals(h, 8, KEY_LENGTH, k.getBytes(), 8, KEY_LENGTH)) {
				return new Entry(k, asMap(e.getValue()));
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> asMap(ACell c) {
		return (AMap<AString, ACell>) c;
	}

	static AMap<AString, ACell> repeatOf(AMap<AString, ACell> rec) {
		ACell r = rec.get(K_REPEAT);
		return (r instanceof AMap) ? asMap(r) : null;
	}

	static long timeOf(AMap<AString, ACell> rec) {
		ACell t = rec.get(K_TIME);
		return (t instanceof CVMLong l) ? l.longValue() : 0L;   // malformed → due now
	}

	/** 16-byte key: 8-byte big-endian wakeTime (orders the index) + 8 random bytes (uniqueness). */
	static Blob mintKey(long wakeTime) {
		byte[] bs = new byte[KEY_LENGTH];
		Utils.writeLong(bs, 0, wakeTime);
		Utils.writeLong(bs, 8, ThreadLocalRandom.current().nextLong());
		return Blob.wrap(bs);
	}

	/** The same event id under a new wake time — how a recurring event moves. */
	private static Blob rekey(Blob key, long wakeTime) {
		byte[] bs = key.getBytes();
		Utils.writeLong(bs, 0, wakeTime);
		return Blob.wrap(bs);
	}

}
