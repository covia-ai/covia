package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.exception.JobPollingFailedException;

public class JobTest {

	@Test
	public void testLateCancelHookRunsExactlyOnceAndSeesCommittedState() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.PENDING));
		job.cancel();
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		job.setCancelHook(() -> {
			assertEquals(Status.CANCELLED, job.getStatus());
			calls.incrementAndGet();
		});
		job.cancel();
		job.completeWith(Strings.create("too late"));
		assertEquals(1, calls.get());
		assertEquals(Status.CANCELLED, job.getStatus());
	}

	@Test
	public void testTerminalCleanupPrecedesResultContinuation() {
		var released = new java.util.concurrent.atomic.AtomicBoolean();
		Job job = new Job(Maps.of(Fields.STATUS, Status.STARTED)) {
			@Override public void onFinish(AMap<AString, ACell> next) { released.set(true); }
		};
		var next = job.future().thenRun(() -> {
			assertEquals(Status.COMPLETE, job.getStatus());
			assertTrue(released.get(), "the next step must not compete with the completed job's permit");
		});
		job.completeWith(null);
		next.join();
	}

	@Test
	public void testCancellationLosingToCompletionDoesNotRunHook() throws Exception {
		var entered = new java.util.concurrent.CountDownLatch(1);
		var release = new java.util.concurrent.CountDownLatch(1);
		Job job = new Job(Maps.of(Fields.STATUS, Status.STARTED)) {
			@Override public AMap<AString, ACell> processUpdate(AMap<AString, ACell> next) {
				if (Status.CANCELLED.equals(next.get(Fields.STATUS))) {
					entered.countDown();
					awaitLatch(release);
				}
				return next;
			}
		};
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		job.setCancelHook(calls::incrementAndGet);
		var cancelling = CompletableFuture.runAsync(job::cancel);
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			job.completeWith(Strings.create("winner"));
		} finally { release.countDown(); }
		cancelling.get(5, TimeUnit.SECONDS);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals(0, calls.get());
	}

	@Test
	public void testCompetingCancellationsOnlyRunWinningHook() throws Exception {
		var ready = new java.util.concurrent.CountDownLatch(2);
		Job job = new Job(Maps.of(Fields.STATUS, Status.STARTED)) {
			@Override public AMap<AString, ACell> processUpdate(AMap<AString, ACell> next) {
				ready.countDown();
				awaitLatch(ready);
				return next;
			}
		};
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		job.setCancelHook(calls::incrementAndGet);
		var first = CompletableFuture.runAsync(job::cancel);
		var second = CompletableFuture.runAsync(job::cancel);
		CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
		assertEquals(1, calls.get());
		assertEquals(Status.CANCELLED, job.getStatus());
	}

	@Test
	public void testLosingFailureCannotReplaceWinningCause() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		job.setPreserveFailureCause(true);
		var winner = new IllegalArgumentException("winner");
		job.fail(winner);
		job.fail(new IllegalStateException("loser"));
		assertEquals("winner", job.getErrorMessage());
		var error = assertThrows(java.util.concurrent.CompletionException.class, () -> job.future().join());
		org.junit.jupiter.api.Assertions.assertSame(winner, error.getCause());
	}

	@Test
	public void testFailureCauseIsPublishedWithTerminalState() throws Exception {
		var committed = new java.util.concurrent.CountDownLatch(1);
		var release = new java.util.concurrent.CountDownLatch(1);
		Job job = new Job(Maps.of(Fields.STATUS, Status.STARTED)) {
			@Override public void onUpdate(AMap<AString, ACell> next) {
				committed.countDown();
				awaitLatch(release);
			}
		};
		job.setPreserveFailureCause(true);
		var winner = new LinkageError("missing class");
		var update = CompletableFuture.runAsync(() -> job.fail(winner));
		try {
			assertTrue(committed.await(5, TimeUnit.SECONDS));
			job.fail(new IllegalStateException("loser"));
			var error = assertThrows(java.util.concurrent.CompletionException.class, () -> job.future().join());
			org.junit.jupiter.api.Assertions.assertSame(winner, error.getCause());
		} finally { release.countDown(); }
		update.get(5, TimeUnit.SECONDS);
	}

	@Test
	public void testThrowingCancelHookStillCompletesAndFinishesJob() {
		var finished = new java.util.concurrent.atomic.AtomicInteger();
		Job job = new Job(Maps.of(Fields.STATUS, Status.STARTED)) {
			@Override public void onFinish(AMap<AString, ACell> next) { finished.incrementAndGet(); }
		};
		var future = job.future();
		job.setCancelHook(() -> { throw new IllegalStateException("cleanup failed"); });
		assertThrows(IllegalStateException.class, job::cancel);
		assertEquals(Status.CANCELLED, job.getStatus());
		assertTrue(future.isCompletedExceptionally());
		assertEquals(1, finished.get());
	}

	@Test
	public void testStartAndResumeCommitBeforeContinuation() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.PENDING));
		assertTrue(job.start(() -> assertEquals(Status.STARTED, job.getStatus())));
		assertFalse(job.start(() -> { throw new AssertionError("already started"); }));
		job.setPauseHook(() -> assertEquals(Status.PAUSED, job.getStatus()));
		job.pause();
		job.setResumeHook(() -> {
			assertEquals(Status.STARTED, job.getStatus());
			job.setStatus(Status.INPUT_REQUIRED);
		});
		job.resume();
		assertEquals(Status.INPUT_REQUIRED, job.getStatus(), "continuation's next state must not be overwritten");
	}

	@Test
	public void testContinuationErrorFailsJobAndCancelledStartDoesNoWork() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.PENDING));
		assertThrows(LinkageError.class, () -> job.start(() -> { throw new LinkageError("broken module"); }));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.future().isCompletedExceptionally());
		Job cancelled = Job.create(Maps.of(Fields.STATUS, Status.PENDING));
		cancelled.cancel();
		assertFalse(cancelled.start(() -> { throw new AssertionError("must not execute"); }));
	}

	private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
		try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}

	@Test
	public void testTimedAwaitDoesNotFailAuthoritativeJob() {
		Job job = Job.create(Maps.of(
			Fields.ID, Blob.parse("0x00112233445566778899aabbccddeeff"),
			Fields.STATUS, Status.STARTED));

		assertThrows(JobPollingFailedException.class, () -> job.awaitResult(10));
		assertEquals(Status.STARTED, job.getStatus(),
			"a caller-side wait timeout must not mutate the authoritative job");

		job.completeWith(Strings.create("eventual result"));
		assertEquals(Strings.create("eventual result"), job.awaitResult(100));
	}

	@Test
	public void testPollingFailureDoesNotChangeLastKnownStatus() {
		Job job = Job.create(Maps.of(
			Fields.ID, Blob.parse("0x00112233445566778899aabbccddee00"),
			Fields.STATUS, Status.STARTED));
		job.future();
		job.pollingFailed(new RuntimeException("transport lost"));

		assertThrows(JobPollingFailedException.class, job::awaitResult);
		assertEquals(Status.STARTED, job.getStatus());
	}

	// ========== Observation failure retained without a future (#513) ==========

	@Test
	public void testPollingFailureBeforeLazyFutureDoesNotHangLateWaiter() {
		Job job = Job.create(Maps.of(
			Fields.ID, Blob.parse("0x00112233445566778899aabbccddee01"),
			Fields.STATUS, Status.STARTED));
		RuntimeException cause = new RuntimeException("transport lost");
		job.pollingFailed(cause);

		CompletableFuture<ACell> f = job.future();
		assertTrue(f.isCompletedExceptionally(), "a late future must settle, not hang");
		JobPollingFailedException ex = assertThrows(JobPollingFailedException.class, job::awaitResult);
		assertSame(cause, ex.getCause());
		assertEquals(job.getID(), ex.getJobId());
		assertEquals(Status.STARTED.toString(), ex.getLastKnownStatus());
		assertEquals(Status.STARTED, job.getStatus(),
			"loss of observation never touches the authoritative record");
	}

	@Test
	public void testPollingFailureIsRetainedForSetFuture() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		job.pollingFailed(new RuntimeException("transport lost"));
		CompletableFuture<ACell> f = new CompletableFuture<>();
		job.setFuture(f);
		assertTrue(f.isCompletedExceptionally());
	}

	@Test
	public void testFirstObservationFailureWins() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		RuntimeException first = new RuntimeException("first");
		job.pollingFailed(first);
		job.pollingFailed(new RuntimeException("second"));
		JobPollingFailedException ex = assertThrows(JobPollingFailedException.class, job::awaitResult);
		assertSame(first, ex.getCause());
	}

	@Test
	public void testAuthoritativeCompletionWinsForLateWaiter() throws Exception {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		job.pollingFailed(new RuntimeException("transport lost"));
		job.completeWith(Strings.create("done"));
		assertEquals(Strings.create("done"), job.future().get(1, TimeUnit.SECONDS),
			"a waiter arriving after the job actually finished gets the result, not a stale observation failure");
	}

	@Test
	public void testConcurrentFutureCreationDuringPollingFailure() throws Exception {
		for (int round = 0; round < 20; round++) {
			Job job = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
			var start = new java.util.concurrent.CountDownLatch(1);
			var creators = new java.util.ArrayList<CompletableFuture<CompletableFuture<ACell>>>();
			for (int i = 0; i < 4; i++) {
				creators.add(CompletableFuture.supplyAsync(() -> {
					try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
					return job.future();
				}));
			}
			CompletableFuture<Void> failer = CompletableFuture.runAsync(() -> {
				try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
				job.pollingFailed(new RuntimeException("transport lost"));
			});
			start.countDown();
			failer.get(5, TimeUnit.SECONDS);
			CompletableFuture<ACell> shared = job.future();
			for (var c : creators) {
				assertSame(shared, c.get(5, TimeUnit.SECONDS), "one future per job");
			}
			assertThrows(ExecutionException.class, () -> shared.get(5, TimeUnit.SECONDS),
				"every ordering of future creation and polling failure settles the future");
		}
	}

	@Test public void testIDParse() {
		assertEquals(Blob.parse("0x1234"),Job.parseID("0x1234"));
		assertEquals(Blob.parse("0x1234"),Job.parseID(Strings.create("0x1234")));
	}
	
	@Test public void testBuild() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job=Job.create(data);
		assertFalse(job.isComplete());
		assertFalse(job.isFinished());
	}
	
	@Test public void testComplete() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.COMPLETE,
			Fields.OUTPUT,1
		);
		Job job=Job.create(data);
		assertTrue(job.isComplete());
		assertTrue(job.isFinished());
	}
	
	@Test public void testFailed() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.FAILED
		);
		Job job=Job.create(data);
		assertFalse(job.isComplete());
		assertTrue(job.isFinished());
	}

	@Test
	public void testPauseAndResumeErrorsExplainExpectedStateOrAction() {
		Job pending = Job.create(Maps.of(Fields.ID, "pending", Fields.STATUS, Status.PENDING));
		IllegalStateException pendingPause = assertThrows(IllegalStateException.class, pending::pause);
		assertTrue(pendingPause.getMessage().contains("expected STARTED"));

		Job running = Job.create(Maps.of(Fields.ID, "running", Fields.STATUS, Status.STARTED));
		IllegalStateException unsupportedPause = assertThrows(IllegalStateException.class, running::pause);
		assertTrue(unsupportedPause.getMessage().contains("cancel it if appropriate"));

		IllegalStateException runningResume = assertThrows(IllegalStateException.class, running::resume);
		assertTrue(runningResume.getMessage().contains("expected PAUSED, INPUT_REQUIRED, or AUTH_REQUIRED"));
	}

	/**
	 * Test that awaitResult() returns immediately when job completes successfully
	 */
	@Test public void testAwaitResultOnComplete() throws Exception {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job = Job.create(data);

		// Complete the job asynchronously
		CompletableFuture.runAsync(() -> job.completeWith(Strings.create("result")));

		// awaitResult should return the result
		ACell result = job.awaitResult();
		assertEquals(Strings.create("result"), result);
	}

	/**
	 * Test that awaitResult() throws JobFailedException when job fails
	 */
	@Test public void testAwaitResultOnFail() throws Exception {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job = Job.create(data);

		// Fail the job asynchronously
		CompletableFuture.runAsync(() -> job.fail("Test failure"));

		// awaitResult should throw JobFailedException
		assertThrows(JobFailedException.class, () -> job.awaitResult());
	}

	/**
	 * Test that getFuture (via awaitResult) completes exceptionally when job is already failed
	 */
	@Test public void testAwaitResultOnAlreadyFailed() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job = Job.create(data);

		// Fail the job first
		job.fail("Already failed");
		assertTrue(job.isFinished());

		// Now awaitResult should throw immediately
		assertThrows(JobFailedException.class, () -> job.awaitResult());
	}

	/**
	 * Test the race condition scenario: getFuture called after job already finished
	 * This simulates what happens when gridRun calls awaitResult after the async task has already failed
	 */
	@Test public void testRaceConditionFutureAfterFail() throws Exception {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job = Job.create(data);

		// Fail the job immediately (simulating fast async failure)
		job.fail("Fast failure");

		// Now try awaitResult - this should NOT hang
		CompletableFuture<ACell> future = CompletableFuture.supplyAsync(() -> {
			return job.awaitResult();
		});

		// Should complete within 1 second (not hang)
		assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
	}

	/**
	 * Test cancel() properly completes the future
	 */
	@Test public void testAwaitResultOnCancel() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job = Job.create(data);

		// Cancel the job
		job.cancel();

		// awaitResult should throw JobFailedException
		assertThrows(JobFailedException.class, () -> job.awaitResult());
	}

	/**
	 * Simulates the exact gridRun scenario:
	 * 1. Outer job is created (grid:run)
	 * 2. Async task starts, creates inner job
	 * 3. Inner job fails
	 * 4. Outer job's async task catches failure and calls outerJob.fail()
	 * 5. MCP calls outerJob.awaitResult()
	 *
	 * The bug was that outerJob.awaitResult() would hang because the future wasn't being completed
	 */
	@Test public void testNestedJobFailurePattern() throws Exception {
		// Create outer job (simulates grid:run job)
		AMap<AString,ACell> outerData = Maps.of(
			Fields.ID,"outer-123",
			Fields.STATUS,Status.PENDING
		);
		Job outerJob = Job.create(outerData);

		// Create inner job (simulates test:error job)
		AMap<AString,ACell> innerData = Maps.of(
			Fields.ID,"inner-456",
			Fields.STATUS,Status.PENDING
		);
		Job innerJob = Job.create(innerData);

		// Simulate async task that:
		// 1. Waits on inner job
		// 2. On inner failure, fails the outer job
		CompletableFuture<Void> asyncTask = CompletableFuture.runAsync(() -> {
			// Fail inner job immediately
			innerJob.fail("Inner operation failed");

			// Now try to await inner result - this should throw
			try {
				innerJob.awaitResult();
			} catch (JobFailedException e) {
				// Expected - now fail the outer job
				outerJob.fail("Inner job failed: " + e.getMessage());
			}
		});

		// Wait for async task to complete
		asyncTask.get(5, TimeUnit.SECONDS);

		// Outer job should be finished
		assertTrue(outerJob.isFinished(), "Outer job should be finished");
		assertEquals(Status.FAILED, outerJob.getStatus());

		// Now awaitResult on outer job should NOT hang - it should throw immediately
		CompletableFuture<Object> awaitFuture = CompletableFuture.supplyAsync(() -> {
			try {
				return (Object)outerJob.awaitResult();
			} catch (JobFailedException e) {
				return e; // Return exception as result
			}
		});

		// Should complete within 1 second
		Object result = awaitFuture.get(1, TimeUnit.SECONDS);
		assertTrue(result instanceof JobFailedException, "Should have received JobFailedException");
	}

	/**
	 * Test the specific timing issue: future created AFTER job already failed
	 */
	@Test public void testFutureCreatedAfterFailure() throws Exception {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.STATUS,Status.PENDING
		);
		Job job = Job.create(data);

		// Fail the job immediately - before any future is created
		job.fail("Immediate failure");

		// Verify job is failed
		assertTrue(job.isFinished());
		assertFalse(job.isComplete());
		assertEquals(Status.FAILED, job.getStatus());

		// Now call awaitResult - this creates the future AFTER job is already failed
		// This must NOT hang
		CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
			try {
				return (Object)job.awaitResult();
			} catch (JobFailedException e) {
				return e;
			}
		});

		// Must complete within 1 second
		Object result = future.get(1, TimeUnit.SECONDS);
		assertTrue(result instanceof JobFailedException);
	}
}
