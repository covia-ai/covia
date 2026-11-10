package covia.venue;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A condition a test waits on, checked again each time the code it watches
 * calls {@link #signal()} — a stub peer after handling a request, say — so the
 * test waits on that code's own progress rather than polling it.
 */
public final class TestSignal {

	/** How long a wait lasts before it fails the test. */
	public static final long TIMEOUT_MS = 10_000;

	/** Wakes every waiter to check its condition again. */
	public synchronized void signal() {
		notifyAll();
	}

	/**
	 * Waits until {@code condition} holds, checked now and after each
	 * {@link #signal()}.
	 *
	 * @throws AssertionError if interrupted or the condition does not hold within {@link #TIMEOUT_MS}
	 */
	public synchronized void await(String what, BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
		while (!condition.getAsBoolean()) {
			long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
			if (remaining <= 0) throw new AssertionError("Timed out waiting for " + what);
			try {
				wait(remaining);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted waiting for " + what, e);
			}
		}
	}
}
