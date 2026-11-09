package covia.venue;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * {@link VenueClock#SYSTEM}. Pending tasks wait on one daemon thread shared by
 * every application in the JVM, which ends while none is pending, so a clock
 * with nothing due holds no thread. Each task runs on a virtual thread of its
 * own.
 */
final class SystemClock implements VenueClock {

	/** Idle time after which the shared timer thread ends. */
	private static final long KEEP_ALIVE_SECONDS = 10;

	private static final ScheduledThreadPoolExecutor WAITING = waiting();

	private static ScheduledThreadPoolExecutor waiting() {
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

	@Override
	public CVMLong get() {
		return Utils.getCurrentTimestampCell();
	}

	@Override
	public Future<?> schedule(long delayMillis, Runnable task) {
		return WAITING.schedule(() -> Thread.ofVirtual().name("covia-update").start(task),
			delayMillis, TimeUnit.MILLISECONDS);
	}
}
