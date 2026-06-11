package covia.venue;

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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ALatticeCursor;
import covia.exception.AuthException;

/**
 * Per-venue scheduler for deferred grid-operation invocations. Fires any grid
 * operation at a future wall-clock time; an agent wake is one consumer (a
 * scheduled {@code agent:trigger}). Design in {@code venue/docs/GRID_SCHEDULER.md}.
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
 * <p><b>No escalation.</b> An event captures the owner's DID plus the proofs and
 * caps presented at schedule time, and firing replays exactly those via
 * {@link JobManager#invokeScheduled} (§5 of the design). The scheduler never
 * invents authority.</p>
 */
public class Scheduler {

	private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

	/** Event record field: the operation reference to invoke (AString). */
	static final AString K_OP = Strings.intern("op");
	/** Event record field: the input cell passed to the operation. */
	static final AString K_INPUT = Strings.intern("input");
	/** Event record field: the owner DID (identity the operation runs as). */
	static final AString K_OWNER = Strings.intern("owner");
	/** Event record field: captured UCAN proofs (AVector), replayed at fire time. */
	static final AString K_PROOFS = Strings.intern("proofs");
	/** Event record field: captured capability attenuations (AVector). */
	static final AString K_CAPS = Strings.intern("caps");
	/** Event record field: absolute wake time in millis (CVMLong). */
	static final AString K_TIME = Strings.intern("time");
	/** Slot wrapper field: strictly-increasing stamp; the whole value with the
	 *  higher stamp wins the (rare) merge, so deletions survive (CVMLong). */
	static final AString K_UPDATED = Strings.intern("updated");
	/** Slot wrapper field: the events {@link Index} (key = wakeTime||id). */
	static final AString K_EVENTS = Strings.intern("events");
	/** List-result field: the event handle (its index key). */
	static final AString K_HANDLE = Strings.intern("handle");

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
	 * Schedule {@code opRef(input)} to fire at absolute {@code wakeTime} millis,
	 * running as the caller with the proofs/caps carried in {@code ctx} captured
	 * for replay. Returns the event handle (its index key).
	 */
	public Blob schedule(AString opRef, ACell input, RequestContext ctx, long wakeTime) {
		AString owner = ctx.getCallerDID();
		if (owner == null) throw new AuthException("Scheduling requires an authenticated caller");
		AVector<ACell> proofs = ctx.getProofs();
		AVector<ACell> caps = ctx.getCaps();
		return onTimer(() -> doSchedule(opRef, input, owner, proofs, caps, wakeTime));
	}

	/** Cancel a scheduled event by handle. Owner-only. Returns false if absent. */
	public boolean cancel(Blob handle, RequestContext ctx) {
		return onTimer(() -> doCancel(handle, ctx.getCallerDID()));
	}

	/**
	 * Fire a scheduled event now, ahead of its time, removing it. Owner-only.
	 * Returns the invocation's result future (run with the event's stapled
	 * authority). The deterministic test hook as well as a real "run it now".
	 */
	public CompletableFuture<ACell> trigger(Blob handle, RequestContext ctx) {
		AMap<AString, ACell> rec;
		try {
			rec = onTimer(() -> doClaim(handle, ctx.getCallerDID()));
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		if (rec == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("No scheduled event for handle"));
		}
		return invokeFor(rec);
	}

	/** The caller's pending events, each as {@code {handle, op, time}}, time-ordered. */
	public AVector<ACell> list(RequestContext ctx) {
		AString caller = ctx.getCallerDID();
		Index<Blob, ACell> idx = index();
		AVector<ACell> out = Vectors.empty();
		long cnt = idx.count();
		for (long i = 0; i < cnt; i++) {
			var e = idx.entryAt(i);
			if (!(e.getValue() instanceof AMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> rec = (AMap<AString, ACell>) e.getValue();
			if (caller == null || !caller.equals(rec.get(K_OWNER))) continue;
			out = out.conj(Maps.of(
				K_HANDLE, e.getKey(),
				K_OP, rec.get(K_OP),
				K_TIME, rec.get(K_TIME)));
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

	// ------------------------------------------------------- timer-thread bodies

	private Blob doSchedule(AString opRef, ACell input, AString owner,
			AVector<ACell> proofs, AVector<ACell> caps, long wakeTime) {
		Blob key = mintKey(wakeTime);
		AMap<AString, ACell> rec = Maps.of(
			K_OP, opRef,
			K_OWNER, owner,
			K_TIME, CVMLong.create(wakeTime));
		if (input != null) rec = rec.assoc(K_INPUT, input);
		if (proofs != null) rec = rec.assoc(K_PROOFS, proofs);
		if (caps != null) rec = rec.assoc(K_CAPS, caps);
		final AMap<AString, ACell> frec = rec;
		putEvents(events -> events.assoc(key, frec));
		return key;
	}

	private boolean doCancel(Blob key, AString caller) {
		ACell rec = index().get(key);
		if (!(rec instanceof AMap)) return false;
		requireOwner(asMap(rec), caller);
		putEvents(events -> events.dissoc(key));
		return true;
	}

	/** Read + remove an event (the claim), owner-checked. Returns null if absent. */
	private AMap<AString, ACell> doClaim(Blob key, AString caller) {
		ACell rec = index().get(key);
		if (!(rec instanceof AMap)) return null;
		requireOwner(asMap(rec), caller);
		putEvents(events -> events.dissoc(key));
		return asMap(rec);
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
			putEvents(events -> events.dissoc(key));       // claim
			dispatchFire(rec);
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
	private void dispatchFire(AMap<AString, ACell> rec) {
		Thread.ofVirtual().name("venue-scheduler-fire").start(() ->
			invokeFor(rec).whenComplete((r, err) -> {
				if (err != null) log.warn("scheduled fire failed for op {}", rec.get(K_OP), err);
			}));
	}

	/** Build the owner+proofs+caps context and dispatch the operation (zero-Job, cap-enforced). */
	private CompletableFuture<ACell> invokeFor(AMap<AString, ACell> rec) {
		AString opRef = RT.ensureString(rec.get(K_OP));
		ACell input = rec.get(K_INPUT);
		AString owner = RT.ensureString(rec.get(K_OWNER));
		RequestContext ctx = RequestContext.of(owner);
		AVector<ACell> proofs = asVector(rec.get(K_PROOFS));
		if (proofs != null) ctx = ctx.withProofs(proofs);
		AVector<ACell> caps = asVector(rec.get(K_CAPS));
		if (caps != null) ctx = ctx.withCaps(caps);
		return engine.jobs().invokeScheduled(opRef, input, ctx);
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

	private static long timeOf(AMap<AString, ACell> rec) {
		ACell t = rec.get(K_TIME);
		return (t instanceof CVMLong l) ? l.longValue() : 0L;   // malformed → due now
	}

	private static void requireOwner(AMap<AString, ACell> rec, AString caller) {
		if (caller == null || !caller.equals(rec.get(K_OWNER))) {
			throw new AuthException("Not the owner of this scheduled event");
		}
	}

	/** 16-byte key: 8-byte big-endian wakeTime (orders the index) + 8 random bytes (uniqueness). */
	private static Blob mintKey(long wakeTime) {
		byte[] bs = new byte[16];
		Utils.writeLong(bs, 0, wakeTime);
		Utils.writeLong(bs, 8, ThreadLocalRandom.current().nextLong());
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
