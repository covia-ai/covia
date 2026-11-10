package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;

class RemoteJobsTest {
	@Test void transportFailureRetainsOnlySafeEvidence() throws Exception {
		try (RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2)) {
			Job job = job(j -> observer.attemptCancellation(j, "remote", () -> {
				throw new java.io.IOException("private-token at secret URL");
			}));
			CountDownLatch recorded = new CountDownLatch(1);
			job.subscribe(j -> {
				if (Strings.create("unknown").equals(convex.core.lang.RT.getIn(j.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME))) recorded.countDown();
			});
			job.cancel();
			assertTrue(recorded.await(5, TimeUnit.SECONDS));
			assertEquals(Strings.create("IOException"), convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.CANCELLATION_ERROR_TYPE));
			assertFalse(job.getData().toString().contains("private-token"));
			assertEquals(Status.CANCELLED, job.getStatus());
		}
	}

	private static final AMap<AString, ACell> STARTED_JOB = Maps.of(Fields.ID, Blob.fromHex("00112233445566778899aabbccddeeff"), Fields.STATUS, Status.STARTED);

	private static Job job() {
		return Job.create(STARTED_JOB);
	}

	/** A job that does {@code onCancelled} when it is cancelled, as a venue job stops its work. */
	private static Job job(java.util.function.Consumer<Job> onCancelled) {
		return new Job(STARTED_JOB) {
			@Override protected void onCancelled() { onCancelled.accept(this); }
		};
	}

	/** A job the venue recorded for this test's user, with an entry to keep its connection in. */
	private static Job recorded(TestInfo info) {
		return TestEngine.ENGINE.jobs().invokeOperation(Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "test:never")),
			Maps.empty(), RequestContext.of(TestEngine.uniqueDID(info)));
	}

	@Test void repeatedTimeoutsDoNotFailExecutionAndOnlyChangesEnterHistory() throws Exception {
		try (RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2)) {
			Job job = job();
			observer.prepare(job, "test", "test://peer", Maps.empty());
			observer.accepted(job, "remote");
			AtomicInteger polls = new AtomicInteger();
			AtomicInteger updates = new AtomicInteger();
			job.subscribe(j -> updates.incrementAndGet());
			observer.observe(job, () -> {
				if (polls.incrementAndGet() <= 100) return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("timeout"));
				return CompletableFuture.completedFuture(Maps.of(Fields.OUTPUT, "done"));
			}, data -> job.completeWith(data.get(Fields.OUTPUT)));
			assertEquals(Strings.create("done"), job.future().get(5, TimeUnit.SECONDS));
			assertEquals(101, polls.get());
			assertTrue(updates.get() < 5, "unchanged failures must not grow the durable history");
		}
	}

	@Test void suspensionFencesOldResponseAndReattachmentCanComplete(TestInfo info) throws Exception {
		try (RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2)) {
			Job job = recorded(info);
			observer.prepare(job, "test", "test://peer", Maps.empty());
			observer.accepted(job, "remote");
			CompletableFuture<AMap<AString, ACell>> stale = new CompletableFuture<>();
			CountDownLatch started = new CountDownLatch(1);
			observer.observe(job, () -> { started.countDown(); return stale; }, data -> job.completeWith(Strings.create("stale")));
			assertTrue(started.await(5, TimeUnit.SECONDS));
			observer.suspend(job);
			stale.complete(Maps.empty());
			assertEquals(Status.STARTED, job.getStatus());
			assertEquals(Strings.create("suspended"), observer.kept(job).get(RemoteJobs.OBSERVATION));
			AtomicBoolean late = new AtomicBoolean();
			observer.received(job, () -> late.set(true));
			assertFalse(late.get(), "a late callback is fenced");
			observer.observe(job, () -> CompletableFuture.completedFuture(Maps.empty()), data -> job.completeWith(Strings.create("fresh")));
			assertEquals(Strings.create("fresh"), job.future().get(5, TimeUnit.SECONDS));
		}
	}

	@Test void closeLetsAPollInFlightRunWithinTheGraceWindow() throws Exception {
		RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2);
		Job job = job();
		observer.prepare(job, "test", "test://peer", Maps.empty());
		observer.accepted(job, "remote");
		CountDownLatch polling = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean closeReturned = new AtomicBoolean();
		AtomicBoolean sentAfterClose = new AtomicBoolean();
		AtomicInteger sent = new AtomicInteger();
		observer.observe(job, () -> {
			polling.countDown();
			// Blocked as discovery, a venue challenge or the transport can be
			try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
			sent.incrementAndGet();
			sentAfterClose.set(closeReturned.get());
			return CompletableFuture.completedFuture(Maps.empty());
		}, data -> {});
		assertTrue(polling.await(5, TimeUnit.SECONDS));
		Thread closer = Thread.ofPlatform().start(() -> { observer.close(WINDOW_OPEN); closeReturned.set(true); });
		awaitParked(closer);
		assertTrue(closer.isAlive(), "close returned while a poll was in flight");
		release.countDown();
		closer.join();
		assertFalse(sentAfterClose.get());
		assertEquals(1, sent.get());
	}

	@Test void closeLetsACancellationInFlightRecordItsOutcomeWithinTheGraceWindow() throws Exception {
		RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2);
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Job job = job(j -> observer.attemptCancellation(j, "remote", () -> {
			sending.countDown();
			release.await();
			return RemoteJobs.cancellationResult(Status.CANCELLED);
		}));
		job.cancel();
		assertTrue(sending.await(5, TimeUnit.SECONDS));
		Thread closer = Thread.ofPlatform().start(() -> observer.close(WINDOW_OPEN));
		awaitParked(closer);
		assertTrue(closer.isAlive(), "close returned while a cancellation was in flight");
		release.countDown();
		closer.join();
		assertEquals(RemoteJobs.OUTCOME_ACKNOWLEDGED, RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME));
	}

	@Test void closeGivesUpOnARequestWhenTheGraceWindowEnds() throws Exception {
		RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2);
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<CompletableFuture<Void>> attempt = new AtomicReference<>();
		Job job = job(j -> attempt.set(observer.attemptCancellation(j, "remote", () -> {
			sending.countDown();
			release.await();
			return RemoteJobs.cancellationResult(Status.CANCELLED);
		})));
		job.cancel();
		assertTrue(sending.await(5, TimeUnit.SECONDS));
		// The window has already ended, so close returns with the request still running
		observer.close(System.nanoTime());
		release.countDown();
		attempt.get().get(5, TimeUnit.SECONDS);
		// Its answer is ignored: the attempt began and nothing established its outcome
		assertEquals(RemoteJobs.OUTCOME_REQUESTED, RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME));
		// Once closed, a cancellation sends nothing and records why
		AtomicInteger sent = new AtomicInteger();
		Job late = job(j -> observer.attemptCancellation(j, "remote", () -> {
			sent.incrementAndGet();
			return RemoteJobs.cancellationResult(Status.CANCELLED);
		}));
		late.cancel();
		AMap<AString, ACell> cancellation = RT.ensureMap(late.getData().get(Fields.CANCELLATION));
		assertEquals(RemoteJobs.OUTCOME_UNKNOWN, cancellation.get(RemoteJobs.OUTCOME));
		assertEquals(Strings.create(RemoteJobs.CLOSED), cancellation.get(RemoteJobs.CANCELLATION_ERROR));
		assertEquals(0, sent.get());
	}

	/** A grace window that outlasts any test. */
	private static final long WINDOW_OPEN = System.nanoTime() + TimeUnit.DAYS.toNanos(1);

	/** Returns once {@code thread} has parked, as close does to wait for a request, or ended. */
	private static void awaitParked(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.isAlive() && thread.getState() != Thread.State.WAITING
				&& thread.getState() != Thread.State.TIMED_WAITING) {
			assertTrue(System.nanoTime() < deadline, "close neither parked nor returned");
			Thread.onSpinWait();
		}
	}

	@Test void theConnectionIsKeptOffTheRecordAndItsLossDoesNotChangeTheOutcome(TestInfo info) {
		try (RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2)) {
			Job job = recorded(info);
			AMap<AString, ACell> saved = Maps.of("token", "private-token");
			observer.prepare(job, "test", "test://peer", saved);
			assertEquals(saved, observer.connection(job));
			assertEquals(Strings.create("test://peer"), observer.target(job));
			assertFalse(job.getData().toString().contains("private-token"));
			assertNull(RemoteJobs.descriptor(job), "how the venue follows the job is its own, never on the record");
			TestEngine.ENGINE.jobs().updateAdapterData(job, kept -> null);
			assertEquals(RemoteJobs.NO_CONNECTION,
				assertThrows(UnavailableException.class, () -> observer.connection(job)).getMessage());
			observer.unavailable(job, new IllegalStateException("private-token"));
			assertEquals(Status.STARTED, job.getStatus());
			assertFalse(job.getData().toString().contains("private-token"));
			TestEngine.ENGINE.jobs().cancelJob(job.getID()); // the never-job would outlive the test on the shared venue
		}
	}

	@Test void terminalEvidenceAndConnectionRemovalPrecedeResultContinuation(TestInfo info) {
		try (RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2)) {
			Job job = recorded(info);
			observer.prepare(job, "test", "test://peer", Maps.of("token", "private-token"));
			observer.accepted(job, "remote");
			CompletableFuture<Void> checked = job.future().thenAccept(result -> {
				assertEquals(Status.COMPLETE, RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_STATUS));
				assertThrows(UnavailableException.class, () -> observer.connection(job));
			});
			job.completeWith(Strings.create("done"), data -> RemoteJobs.observed(data, Status.COMPLETE));
			checked.join();
		}
	}
}
