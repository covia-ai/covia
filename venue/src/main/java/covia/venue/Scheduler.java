package covia.venue;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

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
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Asset;
import covia.venue.ScheduleState.Entry;
import static covia.venue.ScheduleState.*;

/**
 * Per-venue scheduler for deferred grid-operation invocations. Fires any grid
 * operation at a future wall-clock time, once or on a fixed interval; an agent
 * wake is one consumer (a scheduled {@code agent:trigger}). Design in
 * {@code venue/docs/GRID_SCHEDULER.md}.
 *
 * <p>Authoritative state lives on the lattice in the per-venue {@code :schedule}
 * record, whose {@code events} field is an {@link Index} keyed by
 * {@code wakeTime||id}, so its head is always the next event due. The record
 * retains its stable {@code {updated, events, ...}} shape so scheduler metadata
 * can grow without another storage migration. Deletions are durable because
 * the venue value merges whole-value by its own timestamp. This in-memory
 * service is a dumb alarm pointed at the events head, rebuilt from the lattice
 * on boot. See {@code venue/docs/GRID_SCHEDULER.md §8}.</p>
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
 * the timer thread before the schedule is atomically claimed. For recurrence,
 * that one schedule update also records the prepared Job ID; only then does
 * the adapter start, off-thread. Job preparation and schedule claim are two
 * ordered record updates because they have different lattice owners.</p>
 *
 * <p><b>No escalation.</b> An event captures the owner's DID plus the proofs and
 * caps presented at schedule time, and firing replays exactly those via
 * {@link JobManager#invokeInternal} / {@link JobManager#prepareTracked} (§5 of
 * the design), which enforce that scope on dispatch. The scheduler never
 * invents authority.</p>
 */
public class Scheduler {

	private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

	public static final long MIN_REPEAT_MS = ScheduleState.MIN_REPEAT_MS;
	public static final int MAX_DESCRIPTION_CHARS = ScheduleState.MAX_DESCRIPTION_CHARS;
	public static final String DESCRIPTION_NOT_STRING = ScheduleState.DESCRIPTION_NOT_STRING;
	public static final String DESCRIPTION_TOO_LONG = ScheduleState.DESCRIPTION_TOO_LONG;

	private final Engine engine;
	private final LongSupplier clock;
	private final ScheduledThreadPoolExecutor timer;

	/** The single outstanding alarm (fires {@link #drainDue}); null when idle. */
	private ScheduledFuture<?> armed;
	private volatile boolean shutdown = false;
	/** Timer-thread owned: schedule writes during assembly must not arm alarms. */
	private boolean started = false;

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

	/** Commit wake intent and the event together, then arm from committed state. */
	public void setThreadWakeTime(AString owner, AgentState agent,
			AgentState.ThreadKind kind, Blob threadId, long wakeTime) {
		User user = engine.getVenueState().users().get(owner);
		if (user == null) throw new AuthException(Users.ACCOUNT_DELETED);
		ACell generation = RT.getIn(user.get(), Users.GENERATION);
		// Validate the retained agent after reading the generation. A run loop
		// from an erased account must not schedule work for its replacement.
		if (!agent.exists()) return;
		onTimer(() -> {
			engine.getVenueState().setAgentThreadWakeTime(owner, agent.getAgentId(), kind, threadId, wakeTime, generation);
			armNext();
			return null;
		});
	}

	/** Rebuild an agent's persisted wake and arm only after the local commit. */
	public boolean rescheduleAgentWake(AString owner, AString agentId) {
		return onTimer(() -> {
			boolean armed = engine.getVenueState().rescheduleAgentWake(owner, agentId);
			armNext();
			return armed;
		});
	}

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
		return schedule(opRef, input, ctx, wakeTime, repeat, track, null);
	}

	/**
	 * As {@link #schedule(AString, ACell, RequestContext, long, AMap, Boolean)},
	 * with a caller-written {@code description} (validated by
	 * {@link #validateDescription}) saying what the event is for, or null for
	 * none. It is display text for {@link #list}; nothing ever interprets it.
	 */
	public Blob schedule(AString opRef, ACell input, RequestContext ctx, long wakeTime,
			AMap<AString, ACell> repeat, Boolean track, AString description) {
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException("Scheduling requires an authenticated caller");
		AMap<AString, ACell> rep = (repeat == null) ? null : validateRepeat(repeat);
		AString desc = validateDescription(description);
		// Owned by the user, fired as whoever scheduled it — so an agent's queued
		// work stays visible to (and cancellable by) the account it runs in.
		AString actor = ctx.isSubPrincipal() ? ctx.getCallerDID() : null;
		AString by = ctx.getCallerDID();
		AVector<ACell> proofs = ctx.getProofs();
		AVector<ACell> caps = ctx.getCaps();
		return onTimer(() -> doSchedule(opRef, input, owner, actor, by, proofs, caps, wakeTime, rep, track, desc));
	}

	/** Cancel a scheduled event by handle. Owner-only. Returns false if absent. */
	public boolean cancel(Blob handle, RequestContext ctx) {
		return onTimer(() -> doCancel(handle, ctx.getUserDID()));
	}

	/** Remove queued input and recurring work for an erased account. */
	void cancelUser(AString did) {
		onTimer(() -> {
			state().cancelUser(did);
			armNext();
			return null;
		});
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
	 * present, {@code by}, {@code input}, {@code description}, {@code created},
	 * {@code repeat}, {@code lastFired} and {@code lastJob}. {@code track} is
	 * the effective tracking that the next fire will use; {@code input} is
	 * shown as the owner's job history would show it ({@link #listedInput}).
	 * Captured {@code proofs} and {@code caps} are authority, not description,
	 * and are never listed.
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
			ACell input = listedInput(rec);
			if (input != null) item = item.assoc(K_INPUT, input);
			out = out.conj(copyIfPresent(rec, item,
				K_BY, K_DESCRIPTION, K_CREATED, K_REPEAT, K_LAST_FIRED, K_LAST_JOB));
		}
		return out;
	}

	/**
	 * An event's input as a job record for its operation would hold it: the
	 * fields the operation declares in {@code secretFields} are hidden, by the
	 * same code and the same resolution of the recorded {@code op} that job
	 * records use (#228, #499). The stored input is untouched, since firing
	 * replays it. Null when there is no input, or when the operation does not
	 * resolve: its secret fields are then unknown, so nothing is shown rather
	 * than everything.
	 */
	private ACell listedInput(AMap<AString, ACell> rec) {
		ACell input = rec.get(K_INPUT);
		if (input == null) return null;
		Asset op = engine.jobs().resolveRecordedOp(RT.ensureString(rec.get(K_OP)),
			RT.ensureString(rec.get(K_OWNER)));
		return (op == null) ? null : JobManager.redactSecrets(input, op.meta());
	}

	/** Arm the alarm from the (already-loaded) lattice index. Call once on boot. */
	public void start() {
		onTimer(() -> { started = true; armNext(); return null; });
	}

	/** Stop the alarm thread. Fires already dispatched to virtual threads continue. */
	public void shutdown() {
		shutdown = true;
		timer.shutdownNow();
	}

	// ------------------------------------------------------------- recurrence

	public static AMap<AString, ACell> validateRepeat(AMap<AString, ACell> repeat) { return ScheduleState.validateRepeat(repeat); }
	public static AString validateDescription(ACell description) { return ScheduleState.validateDescription(description); }
	public static long everyOf(AMap<AString, ACell> repeat) { return ScheduleState.everyOf(repeat); }
	static long nextTime(AMap<AString, ACell> repeat, long time, long now) { return ScheduleState.nextTime(repeat, time, now); }

	// ------------------------------------------------------- timer-thread bodies

	private Blob doSchedule(AString opRef, ACell input, AString owner, AString actor, AString by,
			AVector<ACell> proofs, AVector<ACell> caps, long wakeTime,
			AMap<AString, ACell> repeat, Boolean track, AString description) {
		engine.getVenueState().users().requireNotDeleted(owner);
		Blob key = state().schedule(opRef, input, owner, actor, by, proofs, caps, wakeTime,
			repeat, track, description, clock.getAsLong());
		armNext();
		return key;
	}

	private boolean doCancel(Blob handle, AString caller) {
		boolean cancelled = state().cancel(handle, caller);
		armNext();
		return cancelled;
	}

	/** Claim an event for an early fire, owner-checked. Returns null if absent. */
	private Claimed doClaim(Blob handle, AString caller) {
		Entry e = state().find(handle);
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
	 * The prepared Job is persisted before this schedule update, so there is no
	 * state in which the event has been consumed but its Job is unknown. A crash
	 * between the two updates can leave a PENDING Job while retaining the event;
	 * recovery fails that unstarted Job, and a later fire prepares a new one.
	 * If the Job cannot be prepared (unresolvable op, capability
	 * denied), the event is still claimed exactly as an untracked failure
	 * would be, and the error travels with the claim for the caller to see.
	 */
	private Claimed claim(Blob key, AMap<AString, ACell> rec, long now, boolean advance) {
		JobManager.Prepared job = null;
		RuntimeException error = null;
		boolean tracked = isTracked(rec);
		try {
			engine.getVenueState().users().requireNotDeleted(RT.ensureString(rec.get(K_OWNER)));
			job = tracked ? engine.jobs().prepareTracked(
				RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec))
				: engine.jobs().prepareUntracked(RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec));
		} catch (RuntimeException e) {
			error = e;
		}
		state().recordFire(new Entry(key, rec), now, advance, tracked && job != null ? job.job().getID() : null);
		armNext();
		return new Claimed(rec, job, error);
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
				state().remove(key);
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

	/** (Re)arm the alarm for the current index head. Runs on the timer thread. */
	private void armNext() {
		if (shutdown || !started) return;
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

	private ScheduleState state() { return engine.getVenueState().schedule(); }
	private Index<Blob, ACell> index() { return state().events(); }

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> asMap(ACell c) {
		return (AMap<AString, ACell>) c;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> asVector(ACell c) {
		return (c instanceof AVector) ? (AVector<ACell>) c : null;
	}

	private static AMap<AString, ACell> copyIfPresent(AMap<AString, ACell> from,
			AMap<AString, ACell> to, AString... keys) {
		for (AString k : keys) {
			ACell v = from.get(k);
			if (v != null) to = to.assoc(k, v);
		}
		return to;
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
