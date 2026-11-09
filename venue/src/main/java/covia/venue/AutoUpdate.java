package covia.venue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import covia.exception.ShuttingDownException;

/**
 * An application's update timer, which it has unless it runs on a
 * {@link DrivenClock}: calls {@link CoviaApplication#update()} when the
 * earliest next event across the mounted venues is due by the application's
 * clock. A venue whose next event moves earlier tells it ({@link #dueAt});
 * after each update it asks every venue again. Each update runs on a virtual
 * thread of its own.
 *
 * <p>Pending wake-ups wait on one daemon thread shared by every timer in the
 * JVM, which ends while none is pending, so a timer with nothing due holds no
 * thread.</p>
 */
final class AutoUpdate implements AutoCloseable {

	/** Idle time after which the shared timer thread ends. */
	private static final long KEEP_ALIVE_SECONDS = 10;

	/** Waits for every timer's next wake-up, then hands the update to a virtual thread. */
	private static final ScheduledThreadPoolExecutor WAKE_UPS = wakeUps();

	private static ScheduledThreadPoolExecutor wakeUps() {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "covia-update-timer");
			t.setDaemon(true);
			return t;
		});
		executor.setRemoveOnCancelPolicy(true);
		executor.setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	private final CoviaApplication application;
	private final CompletableFuture<Throwable> failure = new CompletableFuture<>();

	/** The pending wake-up, or null. Guarded by this. */
	private ScheduledFuture<?> armed;
	/** The time the pending wake-up is for, or {@link Long#MAX_VALUE}. Guarded by this. */
	private long armedAt = Long.MAX_VALUE;
	private boolean closed;

	AutoUpdate(CoviaApplication application) {
		this.application = application;
	}

	/**
	 * Completes with the first failure an update or a look at the venues' next
	 * due times raised. The timer keeps running after it.
	 */
	CompletionStage<Throwable> failure() {
		return failure;
	}

	/** Stops the timer: no further update starts. One already running finishes. */
	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		if (armed != null) armed.cancel(false);
		armed = null;
		armedAt = Long.MAX_VALUE;
	}

	/** Wakes by {@code time} on the application's clock, if not already waking sooner. */
	synchronized void dueAt(long time) {
		if (closed || time >= armedAt) return;
		if (armed != null) armed.cancel(false);
		armedAt = time;
		long now = application.venueContext().currentTimestamp().longValue();
		long delay = (time <= now) ? 0 : time - now;
		armed = WAKE_UPS.schedule(
			() -> Thread.ofVirtual().name("covia-update").start(this::tick),
			delay, TimeUnit.MILLISECONDS);
	}

	/**
	 * Arms for the earliest time any venue next needs an update. Takes the
	 * venues' locks, so it runs where no lock is held: on an update's own thread.
	 */
	private void refresh() {
		long due;
		try {
			due = application.nextDue();
		} catch (RuntimeException | Error e) {
			failure.complete(e);
			return;
		}
		if (due != Long.MAX_VALUE) dueAt(due);
	}

	/**
	 * Forgets the wake-up that has fired before updating, so a venue reporting
	 * an earlier time during the update arms afresh, then looks again.
	 */
	private void tick() {
		synchronized (this) {
			if (closed) return;
			armed = null;
			armedAt = Long.MAX_VALUE;
		}
		try {
			application.update().whenComplete((r, e) -> {
				Throwable cause = (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
				// A venue closing during the update is no fault of the timer's.
				if (cause != null && !(cause instanceof ShuttingDownException)) failure.complete(cause);
			});
		} catch (RuntimeException | Error e) {
			failure.complete(e);
		}
		refresh();
	}
}
