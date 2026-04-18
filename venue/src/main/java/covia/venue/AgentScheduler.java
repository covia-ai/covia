package covia.venue;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.AString;
import convex.core.data.Blob;

/**
 * Per-thread wake scheduler for agents (B8.8). Wraps a single
 * {@link ScheduledThreadPoolExecutor} keyed by {@link ThreadRef} — sessions
 * and tasks each carry their own {@code wakeTime}, and the scheduler fires
 * {@code wakeAgent} when a thread's wake becomes due.
 *
 * <p>Design in {@code venue/docs/SCHEDULER.md §7}. Key invariants:</p>
 * <ul>
 *   <li>The lattice carries authoritative {@code wakeTime}; this scheduler's
 *       index is in-memory and rebuilt from the lattice on boot.</li>
 *   <li>All {@code schedule}/{@code cancel} calls come from
 *       {@code AgentState.setThreadWakeTime} — the single-writer helper.
 *       Per-ref concurrency is zero (virtual-thread-per-agent serialises
 *       writers), so no per-ref locking is needed here.</li>
 *   <li>The fire action dispatches onto a fresh virtual thread so the STPE
 *       timer thread never stalls on lattice writes or Etch I/O.</li>
 * </ul>
 */
public class AgentScheduler {

	private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

	/** Identity of a scheduled thread (session or task) on a specific agent. */
	public enum ThreadKind {
		SESSION,
		TASK
	}

	/**
	 * Reference to a scheduled thread. Equality is value-based (all four
	 * fields) so the same session/task on the same agent always maps to the
	 * same slot in {@link #handles}.
	 */
	public record ThreadRef(AString userDid, AString agentId, ThreadKind kind, Blob threadId) {
		public ThreadRef {
			Objects.requireNonNull(userDid, "userDid");
			Objects.requireNonNull(agentId, "agentId");
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(threadId, "threadId");
		}
	}

	private final ScheduledThreadPoolExecutor timer;
	private final ConcurrentMap<ThreadRef, ScheduledFuture<?>> handles = new ConcurrentHashMap<>();
	private final Consumer<ThreadRef> fireAction;
	private final LongSupplier clock;
	private volatile boolean shutdown = false;

	/**
	 * Constructs a scheduler with an injected fire action (invoked on a
	 * fresh virtual thread per fire) and clock. Production callers use
	 * {@link #AgentScheduler(Consumer)}.
	 */
	public AgentScheduler(Consumer<ThreadRef> fireAction, LongSupplier clock) {
		this.fireAction = Objects.requireNonNull(fireAction, "fireAction");
		this.clock = Objects.requireNonNull(clock, "clock");
		ThreadFactory tf = r -> {
			Thread t = Executors.defaultThreadFactory().newThread(r);
			t.setName("agent-scheduler");
			t.setDaemon(true);
			return t;
		};
		this.timer = new ScheduledThreadPoolExecutor(1, tf);
		this.timer.setRemoveOnCancelPolicy(true);
	}

	/** Uses {@code System.currentTimeMillis()} as the clock. */
	public AgentScheduler(Consumer<ThreadRef> fireAction) {
		this(fireAction, System::currentTimeMillis);
	}

	/**
	 * Install a fire for {@code ref} at absolute {@code wakeTime} millis.
	 * Cancels any prior future for the same ref. Past wake times fire
	 * immediately (delay clamped to zero).
	 */
	public void schedule(ThreadRef ref, long wakeTime) {
		if (shutdown) return;
		long delay = Math.max(0L, wakeTime - clock.getAsLong());
		ScheduledFuture<?> next = timer.schedule(() -> fire(ref), delay, TimeUnit.MILLISECONDS);
		ScheduledFuture<?> prev = handles.put(ref, next);
		if (prev != null) prev.cancel(false);
	}

	/** Remove any scheduled fire for {@code ref}. No-op if absent. */
	public void cancel(ThreadRef ref) {
		ScheduledFuture<?> f = handles.remove(ref);
		if (f != null) f.cancel(false);
	}

	/** Number of scheduled refs (diagnostics / tests). */
	public int size() {
		return handles.size();
	}

	/** True iff {@code ref} has a scheduled future that has not fired or been cancelled. */
	public boolean contains(ThreadRef ref) {
		ScheduledFuture<?> f = handles.get(ref);
		return f != null && !f.isDone();
	}

	/**
	 * Stop the scheduler. Fires already dispatched onto virtual threads
	 * continue to completion; pending futures are cancelled.
	 */
	public void shutdown() {
		shutdown = true;
		for (ScheduledFuture<?> f : handles.values()) f.cancel(false);
		handles.clear();
		timer.shutdownNow();
	}

	private void fire(ThreadRef ref) {
		// Clear our slot before dispatching so a re-schedule landing
		// concurrently with this fire installs a fresh future.
		handles.remove(ref);
		Thread.ofVirtual().name("agent-scheduler-fire-" + ref.agentId()).start(() -> {
			try {
				fireAction.accept(ref);
			} catch (Throwable t) {
				log.warn("scheduler fire failed for {}", ref, t);
			}
		});
	}
}
