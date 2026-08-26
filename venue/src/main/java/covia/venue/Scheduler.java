package covia.venue;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ALatticeCursor;
import covia.exception.AuthException;

/**
 * Per-venue scheduler for deferred grid-operation invocations. Fires any grid
 * operation at a future wall-clock time, once or on a fixed interval; an agent
 * wake is one consumer (a scheduled {@code agent:trigger}). Design in
 * {@code venue/docs/GRID_SCHEDULER.md}.
 *
 * <p>Authoritative state lives on the lattice in the per-venue {@code :schedule}
 * slot, a whole {@code {updated, events}} value where {@code events} is an
 * {@link Index} keyed by {@code wakeTime||id} so its head is always the next
 * event due. The slot is replaced <i>as a unit</i> on every mutation (the
 * {@code LWW} slot lattice keeps the value with the higher {@code updated}
 * stamp, with no per-entry merge) — so a removal can't be undone by a union
 * when a concurrent persistence sweep forces the fork-merge path. The stamp is
 * strictly increasing, so the latest write always wins. This in-memory service
 * is a dumb alarm pointed at the events head, rebuilt from the lattice on boot.
 * See {@code venue/docs/GRID_SCHEDULER.md §8}.</p>
 *
 * <p><b>Concurrency.</b> A single-thread {@link ScheduledThreadPoolExecutor}
 * owns the alarm <i>and</i> every index mutation (schedule / cancel / trigger /
 * drain). Because removals happen only on that one thread, a claim is simply
 * "read the entry, then remove it" — no cross-thread race, no claim flag. The
 * fired operation itself runs on a fresh virtual thread so its I/O never stalls
 * the alarm.</p>
 *
 * <p><b>Recurrence.</b> An event carrying a {@code repeat} spec is not removed
 * when it fires: it is re-inserted at its next due slot under the same
 * {@code id}, so the handle a caller holds keeps resolving (by id when the
 * exact key has moved on). Missed slots are skipped, never replayed.</p>
 *
 * <p><b>Tracking.</b> The scheduler records schedules; Jobs record executions.
 * A fire is a transient Job by default (§7); a <i>tracked</i> fire is a durable
 * Job in the owner's history, and the only thing the scheduler keeps of it is
 * its ID ({@code lastJob} on a recurring record). No outcome, status or error
 * is ever stored here — that is what the Job is for. The Job is
 * {@link JobManager#prepareTracked prepared} (minted, PENDING, persisted) on
 * the timer thread so that claiming the event and recording its Job ID are
 * one lattice write; only then does the adapter start, off-thread.</p>
 *
 * <p><b>No escalation.</b> An event captures the owner's DID plus the proofs and
 * caps presented at schedule time, and firing replays exactly those via
 * {@link JobManager#invokeInternal} / {@link JobManager#prepareTracked} (§5 of
 * the design), which enforce that scope on dispatch. The scheduler never
 * invents authority.</p>
 */
public class Scheduler {

	private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

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
	/** Slot wrapper field: strictly-increasing stamp; the whole value with the
	 *  higher stamp wins the (rare) merge, so deletions survive (CVMLong). */
	static final AString K_UPDATED = Strings.intern("updated");
	/** Slot wrapper field: the events {@link Index} (key = wakeTime||id). */
	static final AString K_EVENTS = Strings.intern("events");
	/** List-result field: the event handle (its index key). */
	static final AString K_HANDLE = Strings.intern("handle");

	/** Floor on {@code repeat.every}: the first abuse guard against tight loops. */
	public static final long MIN_REPEAT_MS = 1_000L;

	/** Index key length: 8-byte big-endian wake time + 8-byte id. */
	private static final int KEY_LENGTH = 16;

	private final Engine engine;
	private final LongSupplier clock;
	private final ScheduledThreadPoolExecutor timer;

	/** The single outstanding alarm (fires {@link #drainDue}); null when idle. */
	private ScheduledFuture<?> armed;
	private volatile boolean shutdown = false;

	/** Strictly-increasing stamp for the slot wrapper. Touched only on the timer
	 *  thread, so no synchronisation is needed. Seeded from the persisted value
	 *  in {@link #start()} so it keeps increasing across restarts. */
	private long lastStamp = 0L;

	/** An event as it stands in the index. */
	private record Entry(Blob key, AMap<AString, ACell> rec) {}

	/**
	 * A claimed fire: the event record as fired, and either the prepared Job
	 * to start (tracked) or null (transient), or the error that prevented
	 * preparing it. The event's own state has already been committed.
	 */
	private record Claimed(AMap<AString, ACell> rec, JobManager.Prepared job, RuntimeException error) {}

	public Scheduler(Engine engine) {
		this(engine, System::currentTimeMillis);
	}

	public Scheduler(Engine engine, LongSupplier clock) {
		this.engine = engine;
		this.clock = clock;
		ThreadFactory tf = r -> {
			Thread t = Executors.defaultThreadFactory().newThread(r);
			t.setName("venue-scheduler");
			t.setDaemon(true);
			return t;
		};
		this.timer = new ScheduledThreadPoolExecutor(1, tf);
		this.timer.setRemoveOnCancelPolicy(true);
	}

	// ---------------------------------------------------------------- public API

	/**
	 * Schedule a one-shot {@code opRef(input)} to fire at absolute {@code wakeTime}
	 * millis, running as the caller with the proofs/caps carried in {@code ctx}
	 * captured for replay, tracked per the venue default. Returns the event
	 * handle (its index key).
	 */
	public Blob schedule(AString opRef, ACell input, RequestContext ctx, long wakeTime) {
		return schedule(opRef, input, ctx, wakeTime, null, null);
	}

	/**
	 * Schedule {@code opRef(input)} to fire at absolute {@code wakeTime} millis,
	 * running as the caller with the proofs/caps carried in {@code ctx} captured
	 * for replay. Returns the event handle (its index key).
	 *
	 * @param repeat recurrence spec (validated by {@link #validateRepeat}), or
	 *        null for a one-shot event
	 * @param track the caller's explicit tracking choice — true for a durable
	 *        Job per fire, false for transient — or null to take the venue
	 *        default at fire time. An operator-level force overrides both.
	 */
	public Blob schedule(AString opRef, ACell input, RequestContext ctx, long wakeTime,
			AMap<AString, ACell> repeat, Boolean track) {
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException("Scheduling requires an authenticated caller");
		AMap<AString, ACell> rep = (repeat == null) ? null : validateRepeat(repeat);
		// Owned by the user, fired as whoever scheduled it — so an agent's queued
		// work stays visible to (and cancellable by) the account it runs in.
		AString actor = ctx.isSubPrincipal() ? ctx.getCallerDID() : null;
		AVector<ACell> proofs = ctx.getProofs();
		AVector<ACell> caps = ctx.getCaps();
		return onTimer(() -> doSchedule(opRef, input, owner, actor, proofs, caps, wakeTime, rep, track));
	}

	/** Cancel a scheduled event by handle. Owner-only. Returns false if absent. */
	public boolean cancel(Blob handle, RequestContext ctx) {
		return onTimer(() -> doCancel(handle, ctx.getUserDID()));
	}

	/**
	 * Fire a scheduled event now, ahead of its time. Owner-only. A one-shot
	 * event is consumed; a recurring one stays scheduled at its unchanged next
	 * due time. Returns the invocation's result future (run with the event's
	 * stapled authority). The deterministic test hook as well as a real "run it
	 * now".
	 */
	public CompletableFuture<ACell> trigger(Blob handle, RequestContext ctx) {
		Claimed c;
		try {
			c = onTimer(() -> doClaim(handle, ctx.getUserDID()));
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(ex);
		}
		if (c == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("No scheduled event for handle"));
		}
		return fire(c);
	}

	/**
	 * The calling user's pending events, time-ordered — including those queued
	 * by their agents. Each is {@code {handle, op, time, track}} plus, when
	 * present, {@code repeat}, {@code lastFired} and {@code lastJob}.
	 * {@code track} is the effective tracking that the next fire will use.
	 */
	public AVector<ACell> list(RequestContext ctx) {
		AString caller = ctx.getUserDID();
		Index<Blob, ACell> idx = index();
		AVector<ACell> out = Vectors.empty();
		long cnt = idx.count();
		for (long i = 0; i < cnt; i++) {
			var e = idx.entryAt(i);
			if (!(e.getValue() instanceof AMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> rec = (AMap<AString, ACell>) e.getValue();
			if (caller == null || !caller.equals(rec.get(K_OWNER))) continue;
			AMap<AString, ACell> item = Maps.of(
				K_HANDLE, e.getKey(),
				K_OP, rec.get(K_OP),
				K_TIME, rec.get(K_TIME),
				K_TRACK, CVMBool.create(isTracked(rec)));
			out = out.conj(copyIfPresent(rec, item, K_REPEAT, K_LAST_FIRED, K_LAST_JOB));
		}
		return out;
	}

	/** Arm the alarm from the (already-loaded) lattice index. Call once on boot. */
	public void start() {
		onTimer(() -> {
			ACell cur = store().get();
			if (cur instanceof AMap) {
				ACell u = asMap(cur).get(K_UPDATED);
				if (u instanceof CVMLong l) lastStamp = Math.max(lastStamp, l.longValue());
			}
			armNext();
			return null;
		});
	}

	/** Stop the alarm thread. Fires already dispatched to virtual threads continue. */
	public void shutdown() {
		shutdown = true;
		timer.shutdownNow();
	}

	// ------------------------------------------------------------- recurrence

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

	// ------------------------------------------------------- timer-thread bodies

	private Blob doSchedule(AString opRef, ACell input, AString owner, AString actor,
			AVector<ACell> proofs, AVector<ACell> caps, long wakeTime,
			AMap<AString, ACell> repeat, Boolean track) {
		Blob key = mintKey(wakeTime);
		AMap<AString, ACell> rec = Maps.of(
			K_OP, opRef,
			K_OWNER, owner,
			K_TIME, CVMLong.create(wakeTime));
		if (actor != null) rec = rec.assoc(K_ACTOR, actor);
		if (input != null) rec = rec.assoc(K_INPUT, input);
		if (proofs != null) rec = rec.assoc(K_PROOFS, proofs);
		if (caps != null) rec = rec.assoc(K_CAPS, caps);
		if (repeat != null) rec = rec.assoc(K_REPEAT, repeat);
		if (track != null) rec = rec.assoc(K_TRACK, CVMBool.create(track));
		final AMap<AString, ACell> frec = rec;
		putEvents(events -> events.assoc(key, frec));
		return key;
	}

	private boolean doCancel(Blob handle, AString caller) {
		Entry e = find(handle);
		if (e == null) return false;
		requireOwner(e.rec(), caller);
		final Blob key = e.key();
		putEvents(events -> events.dissoc(key));
		return true;
	}

	/** Claim an event for an early fire, owner-checked. Returns null if absent. */
	private Claimed doClaim(Blob handle, AString caller) {
		Entry e = find(handle);
		if (e == null) return null;
		requireOwner(e.rec(), caller);
		return claim(e.key(), e.rec(), clock.getAsLong(), false);
	}

	/**
	 * Claim {@code rec} (at {@code key}) for a fire at {@code now} — one atomic
	 * lattice write covering everything the fire changes about the schedule:
	 * <ul>
	 * <li>a tracked fire's Job is prepared first (minted, PENDING, persisted —
	 *     the adapter is not started), so its ID is known;</li>
	 * <li>a one-shot event is removed;</li>
	 * <li>a recurring event is re-inserted with {@code lastFired = now} and,
	 *     when tracked, {@code lastJob} — re-keyed at its next due slot when
	 *     {@code advance} (the alarm path), or left at its current time when not
	 *     (an early {@code trigger} does not disturb the schedule).</li>
	 * </ul>
	 * There is no state in which the event has been consumed but its Job is
	 * unknown. If the Job cannot be prepared (unresolvable op, capability
	 * denied), the event is still claimed exactly as an untracked failure
	 * would be, and the error travels with the claim for the caller to see.
	 */
	private Claimed claim(Blob key, AMap<AString, ACell> rec, long now, boolean advance) {
		JobManager.Prepared job = null;
		RuntimeException error = null;
		if (isTracked(rec)) {
			try {
				job = engine.jobs().prepareTracked(
					RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec));
			} catch (RuntimeException e) {
				error = e;
			}
		}
		AMap<AString, ACell> repeat = repeatOf(rec);
		if (repeat == null) {
			putEvents(events -> events.dissoc(key));
			return new Claimed(rec, job, error);
		}
		AMap<AString, ACell> next = rec.assoc(K_LAST_FIRED, CVMLong.create(now));
		if (job != null) next = next.assoc(K_LAST_JOB, job.job().getID());
		Blob nextKey = key;
		if (advance) {
			long t = nextTime(repeat, timeOf(rec), now);
			next = next.assoc(K_TIME, CVMLong.create(t));
			nextKey = rekey(key, t);
		}
		final Blob fk = nextKey;
		final AMap<AString, ACell> fn = next;
		putEvents(events -> events.dissoc(key).assoc(fk, fn));
		return new Claimed(rec, job, error);
	}

	/**
	 * Locate an event by handle: the exact key, else — because a recurring
	 * event is re-keyed on every fire while its id suffix stays fixed — the
	 * entry whose id matches. Schedules are small, so the fallback scan is
	 * cheap and only ever runs for a handle that has moved on.
	 */
	private Entry find(Blob handle) {
		Index<Blob, ACell> idx = index();
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

	/** Fire every event due at or before now, then re-arm. Runs on the timer thread. */
	private void drainDue() {
		if (shutdown) return;
		long now = clock.getAsLong();
		while (true) {
			Index<Blob, ACell> idx = index();
			if (idx.isEmpty()) break;
			var e = idx.entryAt(0);
			final Blob key = (Blob) e.getKey();
			ACell recCell = e.getValue();
			if (!(recCell instanceof AMap)) {              // malformed — drop it
				putEvents(events -> events.dissoc(key));
				continue;
			}
			AMap<AString, ACell> rec = asMap(recCell);
			if (timeOf(rec) > now) break;                  // earliest is in the future
			// Claim: a one-shot is removed; a recurring event moves to its next
			// slot (strictly after now, so this loop always advances).
			dispatchFire(claim(key, rec, now, true));
		}
		armNext();
	}

	/**
	 * Replace the whole {@code {updated, events}} slot value as a unit, applying
	 * {@code op} to the current events and stamping a strictly-increasing
	 * {@code updated} so the new value wins the slot's LWW merge — the rare
	 * fork-merge path then keeps this removal/addition rather than re-unioning.
	 * The stamp is computed once outside the update lambda so the lambda stays
	 * pure under CAS retry. Re-arms the alarm. Runs on the timer thread.
	 */
	private void putEvents(UnaryOperator<Index<Blob, ACell>> op) {
		long ts = nextStamp();
		store().updateAndGet(cur -> wrap(ts, op.apply(eventsOf(cur))));
		armNext();
	}

	/** Strictly-increasing stamp (timer thread only). */
	private long nextStamp() {
		long t = clock.getAsLong();
		lastStamp = (t > lastStamp) ? t : lastStamp + 1;
		return lastStamp;
	}

	/** (Re)arm the alarm for the current index head. Runs on the timer thread. */
	private void armNext() {
		if (shutdown) return;
		if (armed != null) { armed.cancel(false); armed = null; }
		Index<Blob, ACell> idx = index();
		if (idx.isEmpty()) return;
		var e = idx.entryAt(0);
		if (!(e.getValue() instanceof AMap)) {             // malformed head — drain to drop it
			armed = timer.schedule(this::drainDue, 0, TimeUnit.MILLISECONDS);
			return;
		}
		long delay = Math.max(0L, timeOf(asMap(e.getValue())) - clock.getAsLong());
		armed = timer.schedule(this::drainDue, delay, TimeUnit.MILLISECONDS);
	}

	// ------------------------------------------------------------------ firing

	/** Fire-and-forget on a virtual thread (drain path); errors are logged. */
	private void dispatchFire(Claimed c) {
		Thread.ofVirtual().name("venue-scheduler-fire").start(() ->
			fire(c).whenComplete((r, err) -> {
				if (err != null) log.warn("scheduled fire failed for op {}", c.rec().get(K_OP), err);
			}));
	}

	/**
	 * Run a claimed fire. Tracked: start the Job prepared at claim time (its
	 * ID is already on the record). Untracked: a transient Job via
	 * {@code invokeInternal} (design §7). Either way the dispatch runs under the
	 * owner's stapled caps, which JobManager enforces — so a scheduled fire
	 * cannot exceed the authority the owner captured at schedule time.
	 */
	private CompletableFuture<ACell> fire(Claimed c) {
		if (c.error() != null) return CompletableFuture.failedFuture(c.error());
		if (c.job() != null) {
			try {
				return c.job().start().future();
			} catch (RuntimeException e) {
				return CompletableFuture.failedFuture(e);
			}
		}
		AMap<AString, ACell> rec = c.rec();
		return engine.jobs().invokeInternal(
			RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec));
	}

	/** The identity and stapled authority a fire runs under (design §5). */
	private static RequestContext fireContext(AMap<AString, ACell> rec) {
		AString owner = RT.ensureString(rec.get(K_OWNER));
		// Fire as the scheduling agent when there was one. Authority.of recovers
		// the owning namespace from the agent DID, so a replayed sub-principal
		// still resolves the owner's secrets and workspace.
		AString actor = RT.ensureString(rec.get(K_ACTOR));
		RequestContext ctx = RequestContext.of((actor != null) ? actor : owner);
		AVector<ACell> proofs = asVector(rec.get(K_PROOFS));
		if (proofs != null) ctx = ctx.withProofs(proofs);
		AVector<ACell> caps = asVector(rec.get(K_CAPS));
		if (caps != null) ctx = ctx.withCaps(caps);
		return ctx;
	}

	/**
	 * Effective tracking for a fire, resolved at fire time so an operator's
	 * policy applies to everything already queued: operator force → the
	 * caller's explicit choice → the venue default.
	 */
	private boolean isTracked(AMap<AString, ACell> rec) {
		Config cfg = engine.config();
		if (cfg.isForceTrackScheduledJobs()) return true;
		ACell t = rec.get(K_TRACK);
		if (t instanceof CVMBool b) return b.booleanValue();
		return cfg.isTrackScheduledJobs();
	}

	// ------------------------------------------------------------------ helpers

	private ALatticeCursor<ACell> store() {
		return engine.getVenueState().scheduleCursor();
	}

	/** Current events index, unwrapped from the {@code {updated, events}} slot value. */
	private Index<Blob, ACell> index() {
		return eventsOf(store().get());
	}

	/** Extract the events {@link Index} from a slot wrapper (empty if absent/zero). */
	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> eventsOf(ACell wrapper) {
		if (wrapper instanceof AMap) {
			ACell ev = asMap(wrapper).get(K_EVENTS);
			if (ev instanceof Index) return (Index<Blob, ACell>) ev;
		}
		return Index.none();
	}

	/** Build a {@code {updated, events}} slot wrapper stamped at {@code ts}. */
	private static AMap<AString, ACell> wrap(long ts, Index<Blob, ACell> events) {
		return Maps.of(K_UPDATED, CVMLong.create(ts), K_EVENTS, events);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> asMap(ACell c) {
		return (AMap<AString, ACell>) c;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> asVector(ACell c) {
		return (c instanceof AVector) ? (AVector<ACell>) c : null;
	}

	private static AMap<AString, ACell> repeatOf(AMap<AString, ACell> rec) {
		ACell r = rec.get(K_REPEAT);
		return (r instanceof AMap) ? asMap(r) : null;
	}

	private static long timeOf(AMap<AString, ACell> rec) {
		ACell t = rec.get(K_TIME);
		return (t instanceof CVMLong l) ? l.longValue() : 0L;   // malformed → due now
	}

	private static AMap<AString, ACell> copyIfPresent(AMap<AString, ACell> from,
			AMap<AString, ACell> to, AString... keys) {
		for (AString k : keys) {
			ACell v = from.get(k);
			if (v != null) to = to.assoc(k, v);
		}
		return to;
	}

	private static void requireOwner(AMap<AString, ACell> rec, AString caller) {
		if (caller == null || !caller.equals(rec.get(K_OWNER))) {
			throw new AuthException("Not the owner of this scheduled event");
		}
	}

	/** 16-byte key: 8-byte big-endian wakeTime (orders the index) + 8 random bytes (uniqueness). */
	private static Blob mintKey(long wakeTime) {
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

	/** Run a body on the single timer thread and await it, unwrapping its exception. */
	private <T> T onTimer(Callable<T> body) {
		if (shutdown) throw new IllegalStateException("Scheduler is shut down");
		try {
			return timer.submit(body).get();
		} catch (ExecutionException e) {
			Throwable c = e.getCause();
			if (c instanceof RuntimeException re) throw re;
			throw new RuntimeException(c);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}
}
