package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.exception.JobFailedException;

public class JobTest {

	@Test public void testIDParse() {
		assertEquals(Strings.create("1234"),Job.parseID("0x1234"));
		assertEquals(Strings.create("1234"),Job.parseID(Strings.create("0x1234")));
	}
	
	@Test public void testBuild() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.PENDING
		);
		Job job=Job.create(data);
		assertFalse(job.isComplete());
		assertFalse(job.isFinished());
	}
	
	@Test public void testComplete() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.COMPLETE,
			Fields.OUTPUT,1
		);
		Job job=Job.create(data);
		assertTrue(job.isComplete());
		assertTrue(job.isFinished());
	}
	
	@Test public void testFailed() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.FAILED
		);
		Job job=Job.create(data);
		assertFalse(job.isComplete());
		assertTrue(job.isFinished());
	}

	/**
	 * Test that awaitResult() returns immediately when job completes successfully
	 */
	@Test public void testAwaitResultOnComplete() throws Exception {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.PENDING
		);
		Job job = Job.create(data);

		// Complete the job asynchronously
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {}
			job.completeWith(Strings.create("result"));
		});

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
			Fields.JOB_STATUS_FIELD,Status.PENDING
		);
		Job job = Job.create(data);

		// Fail the job asynchronously
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {}
			job.fail("Test failure");
		});

		// awaitResult should throw JobFailedException
		assertThrows(JobFailedException.class, () -> job.awaitResult());
	}

	/**
	 * Test that getFuture (via awaitResult) completes exceptionally when job is already failed
	 */
	@Test public void testAwaitResultOnAlreadyFailed() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.PENDING
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
			Fields.JOB_STATUS_FIELD,Status.PENDING
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
			Fields.JOB_STATUS_FIELD,Status.PENDING
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
			Fields.JOB_STATUS_FIELD,Status.PENDING
		);
		Job outerJob = Job.create(outerData);

		// Create inner job (simulates test:error job)
		AMap<AString,ACell> innerData = Maps.of(
			Fields.ID,"inner-456",
			Fields.JOB_STATUS_FIELD,Status.PENDING
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
			Fields.JOB_STATUS_FIELD,Status.PENDING
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
