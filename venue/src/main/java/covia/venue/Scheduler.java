package covia.venue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.NotFoundException;
import covia.exception.ShuttingDownException;
import covia.grid.Asset;
import covia.grid.Attribution;
import covia.venue.ScheduleState.Entry;
import static covia.venue.ScheduleState.*;

/**
 * Per-venue scheduler for deferred grid-operation invocations. Fires any grid
 * operation at a time on the venue's clock, once or on a fixed interval; an
 * agent wake is one consumer (a scheduled {@code agent:trigger}). Design in
 * {@code venue/docs/GRID_SCHEDULER.md}.
 *
 * <p>Authoritative state lives on the lattice in the per-venue {@code :schedule}
 * record, whose {@code events} field is an {@link Index} keyed by
 * {@code wakeTime||id}, so its head is always the next event due. The record
 * retains its stable {@code {updated, events, ...}} shape so scheduler metadata
 * can grow without another storage migration. Deletions are durable because
 * the venue value merges whole-value by its own timestamp. Nothing here keeps
 * time: the application's update fires what is due ({@link #update}), and the
 * scheduler tells the application when its next event is due
 * ({@link #nextDue}). See {@code venue/docs/GRID_SCHEDULER.md §8}.</p>
 *
 * <p><b>Concurrency.</b> One lock covers every index mutation (schedule,
 * cancel, trigger, claims by an update, agent wakes, user and caller
 * cancellation). Because removals happen only under it, a claim is simply
 * "read the entry, then remove it" — no claim flag. Once a change commits, the
 * application hears when the venue's next event is due. A claimed event's
 * operation is invoked on a fresh virtual thread, off the lock, so its I/O
 * never stalls the schedule.</p>
 *
 * <p><b>Updates.</b> An update at time {@code t} starts every event due by
 * {@code t} in index order (time, then ID), at most
 * {@code scheduler.maxConcurrency} at once. The rest wait in the schedule,
 * cancellable, until a place frees, which happens when the operation's
 * invocation returns, not when its Job finishes. Overlapping updates make one
 * drain whose time is the latest asked for; claims use that time, never the
 * clock's current value, so a stepped run repeats. Only a started scheduler
 * takes part.</p>
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
 * {@link JobManager#prepareTracked prepared} (minted, PENDING, persisted) under
 * the lock before the schedule is atomically claimed. For recurrence, that one
 * schedule update also records the prepared Job ID; only then does the
 * adapter start, off the lock. Job preparation and schedule claim are two
 * ordered record updates because they have different lattice owners.</p>
 *
 * <p><b>No escalation.</b> An event records its caller, its owner when that
 * differs, and the proofs presented at schedule time. A fire runs as that
 * caller would invoke the operation now ({@link Engine#context(AString, AString, AVector)}), presenting
 * those proofs again, via {@link JobManager#invokeInternal} /
 * {@link JobManager#prepareTracked} (§5 of the design), which enforce that scope
 * on dispatch. The scheduler never invents or keeps authority.</p>
 */
public class Scheduler {

	private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

	public static final long MIN_REPEAT_MS = ScheduleState.MIN_REPEAT_MS;
	public static final int MAX_DESCRIPTION_CHARS = ScheduleState.MAX_DESCRIPTION_CHARS;
	public static final String DESCRIPTION_NOT_STRING = ScheduleState.DESCRIPTION_NOT_STRING;
	public static final String DESCRIPTION_TOO_LONG = ScheduleState.DESCRIPTION_TOO_LONG;
	/** Refusal for a user reaching another user's scheduled event. */
	public static final String NOT_OWNER = "Not the owner of this scheduled event";
	/** Refusal for a capability-scoped agent reaching an event it did not schedule. */
	public static final String EVENT_ACCESS_DENIED =
		"A capability-scoped agent manages only the scheduled events it created";
	/** Why an event did not fire: the agent that scheduled it has been deleted, so it was dropped. */
	public static final String AGENT_GONE = "%s no longer exists, so its scheduled event was dropped";
	/** Refusal for work reaching a scheduler the venue has shut down. */
	public static final String SHUT_DOWN = "Scheduler is shut down";

	private final Engine engine;
	private final ReentrantLock lock = new ReentrantLock();

	private volatile boolean shutdown = false;
	/** Schedule writes during assembly take part in no update until start. */
	private boolean started = false;
	/** The latest time an update asked for: the drain starts what is due by then. */
	private long target = Long.MIN_VALUE;
	/** Events claimed whose operation's invocation has not yet returned. */
	private int starting = 0;
	/** The times of those events, counted, so an update knows what it still waits for. */
	private final TreeMap<Long, Integer> inFlight = new TreeMap<>();
	/** Updates waiting for every event due by their time to start. */
	private final List<Waiter> waiters = new ArrayList<>();

	private record Waiter(long time, CompletableFuture<Void> done) {}

	/**
	 * A claimed fire: the event record as fired, and either the prepared Job
	 * to start (tracked) or null (transient), or the error that prevented
	 * preparing it. The event's own state has already been committed.
	 */
	private record Claimed(AMap<AString, ACell> rec, JobManager.Prepared job, RuntimeException error) {}

	public Scheduler(Engine engine) {
		this.engine = engine;
	}

	// ---------------------------------------------------------------- public API

	/** Commit wake intent and the event together, then report the next due time. */
	public void setThreadWakeTime(AString owner, AgentState agent,
			AgentState.ThreadKind kind, Blob threadId, long wakeTime) {
		User user = engine.getVenueState().users().user(owner);
		if (user == null) throw new AuthException(Users.ACCOUNT_DELETED);
		ACell generation = RT.getIn(user.get(), Users.GENERATION);
		// Validate the retained agent after reading the generation. A run loop
		// from an erased account must not schedule work for its replacement.
		if (!agent.exists()) return;
		locked(() -> {
			engine.getVenueState().setAgentThreadWakeTime(owner, agent.getAgentId(), kind, threadId, wakeTime, generation);
			return null;
		});
	}

	/** Rebuild an agent's persisted wake, reporting the next due time after the local commit. */
	public boolean rescheduleAgentWake(AString owner, AString agentId) {
		return locked(() -> engine.getVenueState().rescheduleAgentWake(owner, agentId));
	}

	/**
	 * Schedule a one-shot {@code opRef(input)} to fire at absolute {@code wakeTime}
	 * millis, as the caller would invoke it then, presenting again the proofs
	 * {@code ctx} carries, tracked per the venue default. Returns the event
	 * handle (its index key).
	 */
	public Blob schedule(AString opRef, ACell input, RequestContext ctx, long wakeTime) {
		return schedule(opRef, input, ctx, wakeTime, null, null);
	}

	/**
	 * Schedule {@code opRef(input)} to fire at absolute {@code wakeTime} millis,
	 * as the caller would invoke it then, presenting again the proofs {@code ctx}
	 * carries. Returns the event handle (its index key).
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
		AString caller = ctx.getCallerDID();
		AVector<ACell> proofs = ctx.getProofs();
		AMap<AString, ACell> authority = Engine.keptAuthority(ctx);
		return locked(() -> {
			engine.getVenueState().users().requireNotDeleted(owner);
			return state().schedule(opRef, input, owner, caller, proofs, authority, wakeTime,
				rep, track, desc, now());
		});
	}

	/**
	 * Cancel a scheduled event by handle. Owner-only; a capability-scoped agent
	 * cancels only events it scheduled. Returns false if absent.
	 */
	public boolean cancel(Blob handle, RequestContext ctx) {
		return locked(() -> {
			Entry e = state().find(handle);
			// An event keeps its caller and owner for life, so this check holds for the cancel.
			if (e != null) requireAccess(e.rec(), ctx);
			return state().cancel(handle);
		});
	}

	/** Remove queued input and recurring work for an erased account. */
	void cancelUser(AString did) {
		locked(() -> {
			state().cancelUser(did);
			return null;
		});
	}

	/** Remove the events a deleted agent scheduled, found through the reverse index. */
	public void cancelCaller(AString agentDID) {
		locked(() -> {
			state().cancelCaller(agentDID);
			return null;
		});
	}

	/**
	 * Fire a scheduled event now, ahead of its time. Owner-only; a
	 * capability-scoped agent triggers only events it scheduled. A one-shot
	 * event is consumed; a recurring one stays scheduled at its unchanged next
	 * due time. Returns the invocation's result future (run as the event's caller
	 * would invoke it now). It takes no place under
	 * {@code scheduler.maxConcurrency}: the caller asked for this one.
	 */
	public CompletableFuture<ACell> trigger(Blob handle, RequestContext ctx) {
		Claimed c;
		try {
			c = locked(() -> {
				Entry e = state().find(handle);
				if (e == null) return null;
				requireAccess(e.rec(), ctx);
				return claim(e.key(), e.rec(), now(), false);
			});
		} catch (RuntimeException ex) {
			return CompletableFuture.failedFuture(ex);
		}
		if (c == null) {
			return CompletableFuture.failedFuture(new NotFoundException("No scheduled event for handle",
				(handle == null) ? null : handle.toHexString()));
		}
		return fire(c);
	}

	/**
	 * The calling user's pending events, time-ordered — including those queued
	 * by their agents; a capability-scoped agent sees only its own. Each is
	 * {@code {handle, op, caller, time, track}} plus, when present,
	 * {@code owner}, {@code input}, {@code description}, {@code created},
	 * {@code repeat}, {@code lastFired} and {@code lastJob}. {@code track} is
	 * the effective tracking that the next fire will use; {@code input} is
	 * shown as the owner's job history would show it ({@link #listedInput}).
	 * Captured {@code proofs} and a kept {@code authority} are authority, not
	 * description, and are never listed.
	 */
	public AVector<ACell> list(RequestContext ctx) {
		AccessControl access = engine.getAccessControl();
		Index<Blob, ACell> idx = index();
		AVector<ACell> out = Vectors.empty();
		long cnt = idx.count();
		for (long i = 0; i < cnt; i++) {
			var e = idx.entryAt(i);
			if (!(e.getValue() instanceof AMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> rec = (AMap<AString, ACell>) e.getValue();
			if (!access.canAccessEvent(ctx, rec)) continue;
			AMap<AString, ACell> item = Maps.of(
				K_HANDLE, e.getKey(),
				K_OP, rec.get(K_OP),
				K_TIME, rec.get(K_TIME),
				K_TRACK, CVMBool.create(isTracked(rec)));
			ACell input = listedInput(rec);
			if (input != null) item = item.assoc(K_INPUT, input);
			out = out.conj(copyIfPresent(rec, item,
				K_CALLER, K_OWNER, K_DESCRIPTION, K_CREATED, K_REPEAT, K_LAST_FIRED, K_LAST_JOB));
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
			Attribution.owner(rec));
		return (op == null) ? null : OutputRules.of(op.meta()).redact(input);
	}

	/**
	 * Takes part in the application's updates from now on, reporting when the
	 * (already-loaded) schedule's first event is due. Call once, when the
	 * venue has recovered.
	 */
	public void start() {
		// Starting a scheduler the venue already shut down is a lifecycle bug.
		if (shutdown) throw new IllegalStateException(SHUT_DOWN);
		locked(() -> { started = true; return null; });
	}

	/**
	 * Takes part in no further update. Fires already being started continue;
	 * updates still waiting for events to start fail with
	 * {@link ShuttingDownException}, since nothing more will start.
	 */
	public void shutdown() {
		shutdown = true;
		List<Waiter> stopped;
		lock.lock();
		try {
			stopped = new ArrayList<>(waiters);
			waiters.clear();
		} finally {
			lock.unlock();
		}
		for (Waiter w : stopped) w.done().completeExceptionally(new ShuttingDownException(SHUT_DOWN));
	}

	// ------------------------------------------------------------- updates

	/**
	 * Starts every event due by {@code now}, in index order and at most
	 * {@code scheduler.maxConcurrency} at once; events already due wait in the
	 * schedule until a place frees. The future completes when each of them
	 * has started, that is when its operation's invocation has returned; how
	 * it resolves is its own, and a failure to start is logged. A time no
	 * later than an earlier update's still starts whatever is due by it, such
	 * as an event added since with a past time. A scheduler not started, or
	 * shut down, starts nothing and completes at once.
	 */
	CompletableFuture<Void> update(long now) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		lock.lock();
		try {
			if (!started || shutdown) return CompletableFuture.completedFuture(null);
			if (now > target) target = now;
			waiters.add(new Waiter(now, done));
		} finally {
			lock.unlock();
		}
		drain();
		return done;
	}

	/**
	 * When the venue next needs an update: the first event's time, which may
	 * already have passed, or {@link Long#MAX_VALUE} when nothing is
	 * scheduled, every place is taken (the event that frees one reports the
	 * next time), or the scheduler is not started.
	 */
	long nextDue() {
		lock.lock();
		try {
			return nextDueLocked();
		} finally {
			lock.unlock();
		}
	}

	private long nextDueLocked() {
		if (!started || shutdown) return Long.MAX_VALUE;
		if (starting >= engine.config().getSchedulerMaxConcurrency()) return Long.MAX_VALUE;
		Index<Blob, ACell> idx = index();
		if (idx.isEmpty()) return Long.MAX_VALUE;
		ACell head = idx.entryAt(0).getValue();
		// A malformed head is due now, so the drain drops it.
		return (head instanceof AMap) ? timeOf(asMap(head)) : Long.MIN_VALUE;
	}

	/**
	 * Claims due events in index order while places are free and dispatches
	 * each to start off the lock; completes the updates whose events have all
	 * started, and reports the next due time.
	 */
	private void drain() {
		List<Long> times = new ArrayList<>();
		List<Claimed> batch = new ArrayList<>();
		List<CompletableFuture<Void>> completed = new ArrayList<>();
		long due;
		lock.lock();
		try {
			int max = engine.config().getSchedulerMaxConcurrency();
			while (started && !shutdown && starting < max) {
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
				long time = timeOf(rec);
				if (time > target) break;                      // earliest is not yet due
				// Claim: a one-shot is removed; a recurring event moves to its next
				// slot (strictly after the target, so this loop always advances).
				batch.add(claim(key, rec, target, true));
				times.add(time);
				inFlight.merge(time, 1, Integer::sum);
				starting++;
			}
			settleWaiters(completed);
			due = nextDueLocked();
		} finally {
			lock.unlock();
		}
		for (CompletableFuture<Void> f : completed) f.complete(null);
		for (int i = 0; i < batch.size(); i++) {
			Claimed c = batch.get(i);
			long time = times.get(i);
			Thread.ofVirtual().name("venue-scheduler-fire").start(() -> startClaimed(c, time));
		}
		reportDue(due);
	}

	/**
	 * Starts a claimed event and frees its place once its operation's
	 * invocation has returned, then drains again. Its outcome is only logged.
	 */
	private void startClaimed(Claimed c, long time) {
		try {
			fire(c).whenComplete((r, err) -> {
				if (err != null) log.warn("scheduled fire failed for op {}", c.rec().get(K_OP), err);
			});
		} catch (RuntimeException | Error e) {
			log.warn("scheduled fire failed to start for op {}", c.rec().get(K_OP), e);
		} finally {
			lock.lock();
			try {
				starting--;
				inFlight.computeIfPresent(time, (t, n) -> (n > 1) ? n - 1 : null);
			} finally {
				lock.unlock();
			}
			drain();
		}
	}

	/**
	 * Moves the updates whose events have all started into {@code completed}:
	 * every event due by an update's time has started once the schedule's first
	 * event and every event still starting are later than it. Under the lock.
	 */
	private void settleWaiters(List<CompletableFuture<Void>> completed) {
		if (waiters.isEmpty()) return;
		long covered = Long.MAX_VALUE;
		Index<Blob, ACell> idx = index();
		if (!idx.isEmpty()) {
			ACell head = idx.entryAt(0).getValue();
			covered = (head instanceof AMap) ? timeOf(asMap(head)) : Long.MIN_VALUE;
		}
		if (!inFlight.isEmpty()) covered = Math.min(covered, inFlight.firstKey());
		for (Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
			Waiter w = it.next();
			if (w.time() < covered) {
				completed.add(w.done());
				it.remove();
			}
		}
	}

	/** Tells the application when this venue next needs an update. Off the lock. */
	private void reportDue(long due) {
		if (due != Long.MAX_VALUE) engine.application().dueAt(due);
	}

	// ------------------------------------------------------------- recurrence

	public static AMap<AString, ACell> validateRepeat(AMap<AString, ACell> repeat) { return ScheduleState.validateRepeat(repeat); }
	public static AString validateDescription(ACell description) { return ScheduleState.validateDescription(description); }
	public static long everyOf(AMap<AString, ACell> repeat) { return ScheduleState.everyOf(repeat); }
	static long nextTime(AMap<AString, ACell> repeat, long time, long now) { return ScheduleState.nextTime(repeat, time, now); }

	// ------------------------------------------------------- bodies under the lock

	/** Owner-only, and a capability-scoped agent only for an event it scheduled. */
	private void requireAccess(AMap<AString, ACell> rec, RequestContext ctx) {
		AString user = ctx.getUserDID();
		if (user == null || !user.equals(Attribution.owner(rec))) throw new AuthException(NOT_OWNER);
		if (!engine.getAccessControl().canAccessEvent(ctx, rec)) {
			throw new AuthException(EVENT_ACCESS_DENIED);
		}
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
	 *     {@code advance} (an update), or left at its current time when not
	 *     (an early {@code trigger} does not disturb the schedule).</li>
	 * </ul>
	 * The prepared Job is persisted before this schedule update, so there is no
	 * state in which the event has been consumed but its Job is unknown. A crash
	 * between the two updates can leave a PENDING Job while retaining the event;
	 * recovery fails that unstarted Job, and a later fire prepares a new one.
	 * If the Job cannot be prepared (unresolvable op, capability
	 * denied), the event is still claimed exactly as an untracked failure
	 * would be, and the error travels with the claim for the caller to see.
	 * An event whose scheduling agent no longer exists is removed, recurring or
	 * not, and fails the same way: nothing fires as a deleted agent.
	 */
	private Claimed claim(Blob key, AMap<AString, ACell> rec, long now, boolean advance) {
		AString gone = goneAgent(rec);
		if (gone != null) {
			state().remove(key);
			return new Claimed(rec, null, new NotFoundException(AGENT_GONE.formatted(gone), gone.toString()));
		}
		JobManager.Prepared job = null;
		RuntimeException error = null;
		boolean tracked = isTracked(rec);
		try {
			engine.getVenueState().users().requireNotDeleted(Attribution.owner(rec));
			job = tracked ? engine.jobs().prepareTracked(
				RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec))
				: engine.jobs().prepareUntracked(RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec));
		} catch (RuntimeException e) {
			error = e;
		}
		state().recordFire(new Entry(key, rec), now, advance, tracked && job != null ? job.job().getID() : null);
		return new Claimed(rec, job, error);
	}

	// ------------------------------------------------------------------ firing

	/**
	 * Run a claimed fire. Tracked: start the Job prepared at claim time (its
	 * ID is already on the record). Untracked: a transient Job via
	 * {@code invokeInternal} (design §7). Either way the dispatch runs as its
	 * caller would invoke it now ({@link #fireContext}), which JobManager
	 * enforces — so a fire never has authority its caller has since lost.
	 * Returns once the operation's invocation has returned.
	 */
	private CompletableFuture<ACell> fire(Claimed c) {
		if (c.error() != null) return CompletableFuture.failedFuture(c.error());
		try {
			if (c.job() != null) return c.job().start().future();
			AMap<AString, ACell> rec = c.rec();
			return engine.jobs().invokeInternal(
				RT.ensureString(rec.get(K_OP)), rec.get(K_INPUT), fireContext(rec));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * The agent that scheduled {@code rec} when it is one of the owner's agents
	 * and no longer exists, else null. Deleting an agent removes its events
	 * ({@link #cancelCaller}); this catches any that outlived it, such as those
	 * scheduled before the reverse index recorded them.
	 */
	private AString goneAgent(AMap<AString, ACell> rec) {
		AString caller = Attribution.caller(rec);
		AString owner = Attribution.owner(rec);
		AString agentId = Engine.agentIdIn(owner, caller);
		return (agentId != null && engine.getAgent(owner, agentId) == null) ? caller : null;
	}

	/**
	 * The context a fire runs under (design §5): its caller (the owner, one of
	 * its agents or a delegate) as it would invoke the operation now, in the
	 * owner's namespace, presenting again the proofs it presented when scheduling,
	 * and with the separate authority it acted with, if it had one.
	 *
	 * @throws AuthException when the caller could not invoke it now
	 */
	RequestContext fireContext(AMap<AString, ACell> rec) {
		return engine.context(Attribution.owner(rec), Attribution.caller(rec), asVector(rec.get(K_PROOFS)),
			RT.ensureMap(rec.get(Fields.AUTHORITY)));
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
	private long now() { return engine.currentTimestamp().longValue(); }

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

	/**
	 * Runs a schedule change under the lock, then reports the venue's next due
	 * time to the application, off the lock so no other venue's lock is ever
	 * taken while this one is held.
	 */
	private <T> T locked(Supplier<T> body) {
		if (shutdown) throw new ShuttingDownException(SHUT_DOWN);
		T result;
		long due;
		lock.lock();
		try {
			result = body.get();
			due = nextDueLocked();
		} finally {
			lock.unlock();
		}
		reportDue(due);
		return result;
	}
}
