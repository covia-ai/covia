package covia.venue;

import java.util.concurrent.Future;
import java.util.function.Supplier;

import convex.core.data.prim.CVMLong;

/**
 * A lattice application's time: what time it is, and how the application is
 * woken when something falls due. The service's main class chooses one and
 * hands it to the {@link CoviaApplication}, once per host however many venues
 * are mounted there.
 *
 * <p>{@link #SYSTEM} is the system clock and a timer. {@link DrivenClock} is a
 * time its holder moves, for tests and embedders that step the venue
 * themselves. An embedder with a scheduler of its own implements this.</p>
 */
public interface VenueClock extends Supplier<CVMLong> {

	/** The system clock, with a timer that runs each task when it falls due. */
	VenueClock SYSTEM = new SystemClock();

	/**
	 * Runs a task after a delay on this clock, off the calling thread. The
	 * caller holds no lock the task needs.
	 *
	 * @param delayMillis Delay in milliseconds; zero means as soon as possible
	 * @param task The task
	 * @return The pending task: cancelling it stops the task
	 */
	Future<?> schedule(long delayMillis, Runnable task);
}
