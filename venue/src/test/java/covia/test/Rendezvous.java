package covia.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a set of tasks with all of them in flight at once, so a test can pin
 * down how concurrent callers linearise against a shared record.
 *
 * <p>Tests used to build this out of {@code CompletableFuture.supplyAsync} and a
 * start latch, which has two defects. A task parked on the latch holds its
 * common-pool worker, so a rendezvous of more tasks than the pool's parallelism
 * ({@code availableProcessors() - 1}) can never assemble at all. And when the
 * rendezvous does fail, the start latch is never released, so those workers stay
 * parked for the life of the JVM — which, under a reused surefire fork, hangs
 * every later test that awaits anything on the common pool.
 *
 * <p>This helper gives the rendezvous threads of its own and always releases the
 * barrier, so a failure here stays local to the test that caused it.
 */
public final class Rendezvous {

	/** How long to wait for the rendezvous to assemble, and for each result. */
	private static final long TIMEOUT_MS = 5000;

	private Rendezvous() {}

	/**
	 * Runs every task with all of them at the barrier, returning their results
	 * in argument order.
	 */
	@SafeVarargs
	public static <T> List<T> all(Callable<T>... tasks) throws Exception {
		return all(List.of(tasks));
	}

	/**
	 * Runs every task with all of them at the barrier, returning their results
	 * in iteration order.
	 *
	 * @param tasks the tasks to run concurrently
	 * @return each task's result, in the order the tasks were given
	 * @throws AssertionError if the tasks cannot assemble in time
	 */
	public static <T> List<T> all(List<? extends Callable<T>> tasks) throws Exception {
		int n = tasks.size();
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			List<Future<T>> futures = new ArrayList<>(n);
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					if (!start.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
						throw new TimeoutException("Rendezvous was never released");
					}
					return task.call();
				}));
			}
			if (!ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				throw new AssertionError(
					n + " tasks did not reach the rendezvous within " + TIMEOUT_MS + "ms");
			}
			start.countDown();
			List<T> results = new ArrayList<>(n);
			for (Future<T> f : futures) results.add(f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
			return results;
		} finally {
			start.countDown();
			pool.shutdownNow();
		}
	}
}
