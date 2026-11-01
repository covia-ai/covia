package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
		Engine engine = Engine.createTemp(null);
		try (RemoteJobs observer = new RemoteJobs(engine, 1, 2)) {
			Job job = job();
			CountDownLatch recorded = new CountDownLatch(1);
			job.subscribe(j -> {
				if (Strings.create("unknown").equals(convex.core.lang.RT.getIn(j.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME))) recorded.countDown();
			});
			job.setCancelHook(() -> observer.attemptCancellation(job, "remote", () -> {
				throw new java.io.IOException("private-token at secret URL");
			}));
			job.cancel();
			assertTrue(recorded.await(5, TimeUnit.SECONDS));
			assertEquals(Strings.create("IOException"), convex.core.lang.RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.CANCELLATION_ERROR_TYPE));
			assertFalse(job.getData().toString().contains("private-token"));
			assertEquals(Status.CANCELLED, job.getStatus());
		} finally { engine.close(); }
	}

	private static Job job() {
		return Job.create(Maps.of(Fields.ID, Blob.fromHex("00112233445566778899aabbccddeeff"), Fields.STATUS, Status.STARTED));
	}

	@Test void repeatedTimeoutsDoNotFailExecutionAndOnlyChangesEnterHistory() throws Exception {
		Engine engine = Engine.createTemp(null);
		try (RemoteJobs observer = new RemoteJobs(engine, 1, 2)) {
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
		} finally { engine.close(); }
	}

	@Test void suspensionFencesOldResponseAndReattachmentCanComplete() throws Exception {
		Engine engine = Engine.createTemp(null);
		try (RemoteJobs observer = new RemoteJobs(engine, 1, 2)) {
			Job job = job();
			observer.prepare(job, "test", "test://peer", Maps.empty());
			observer.accepted(job, "remote");
			CompletableFuture<AMap<AString, ACell>> stale = new CompletableFuture<>();
			CountDownLatch started = new CountDownLatch(1);
			observer.observe(job, () -> { started.countDown(); return stale; }, data -> job.completeWith(Strings.create("stale")));
			assertTrue(started.await(5, TimeUnit.SECONDS));
			observer.suspend(job);
			stale.complete(Maps.empty());
			assertEquals(Status.STARTED, job.getStatus());
			assertEquals(Strings.create("suspended"), RemoteJobs.descriptor(job).get(RemoteJobs.OBSERVATION));
			observer.observe(job, () -> CompletableFuture.completedFuture(Maps.empty()), data -> job.completeWith(Strings.create("fresh")));
			assertEquals(Strings.create("fresh"), job.future().get(5, TimeUnit.SECONDS));
		} finally { engine.close(); }
	}

	@Test void closeWaitsForAPollInFlightAndSendsNothingAfter() throws Exception {
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
		Thread closer = Thread.ofPlatform().start(() -> { observer.close(); closeReturned.set(true); });
		awaitParked(closer);
		assertTrue(closer.isAlive(), "close returned while a poll was in flight");
		release.countDown();
		closer.join();
		assertFalse(sentAfterClose.get());
		assertEquals(1, sent.get());
	}

	@Test void closeWaitsForACancellationInFlightAndSendsNoneAfter() throws Exception {
		RemoteJobs observer = new RemoteJobs(TestEngine.ENGINE, 1, 2);
		Job job = job();
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		job.setCancelHook(() -> observer.attemptCancellation(job, "remote", () -> {
			sending.countDown();
			release.await();
			return RemoteJobs.cancellationResult(Status.CANCELLED);
		}));
		job.cancel();
		assertTrue(sending.await(5, TimeUnit.SECONDS));
		Thread closer = Thread.ofPlatform().start(observer::close);
		awaitParked(closer);
		assertTrue(closer.isAlive(), "close returned while a cancellation was in flight");
		release.countDown();
		closer.join();
		assertEquals(RemoteJobs.OUTCOME_ACKNOWLEDGED, RT.getIn(job.getData(), Fields.CANCELLATION, RemoteJobs.OUTCOME));
		// Once closed, a cancellation sends nothing and records why
		Job late = job();
		AtomicInteger sent = new AtomicInteger();
		late.setCancelHook(() -> observer.attemptCancellation(late, "remote", () -> {
			sent.incrementAndGet();
			return RemoteJobs.cancellationResult(Status.CANCELLED);
		}));
		late.cancel();
		AMap<AString, ACell> cancellation = RT.ensureMap(late.getData().get(Fields.CANCELLATION));
		assertEquals(RemoteJobs.OUTCOME_UNKNOWN, cancellation.get(RemoteJobs.OUTCOME));
		assertEquals(Strings.create(RemoteJobs.CLOSED), cancellation.get(RemoteJobs.CANCELLATION_ERROR));
		assertEquals(0, sent.get());
	}

	/** Returns once {@code thread} has parked, as close does to wait for a request, or ended. */
	private static void awaitParked(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.isAlive() && thread.getState() != Thread.State.WAITING) {
			assertTrue(System.nanoTime() < deadline, "close neither parked nor returned");
			Thread.onSpinWait();
		}
	}

	@Test void credentialsAreEncryptedOutsideJobHistoryAndMissingCredentialsDoNotChangeOutcome() {
		Engine engine = Engine.createTemp(null);
		try (RemoteJobs observer = new RemoteJobs(engine, 1, 2)) {
			Job job = job();
			AMap<AString, ACell> saved = Maps.of("token", "private-token");
			observer.prepare(job, "test", "test://peer", saved);
			assertEquals(saved, observer.credentials(job));
			assertFalse(job.getData().toString().contains("private-token"));
			AString ref = (AString) RemoteJobs.descriptor(job).get(RemoteJobs.AUTH_REF);
			engine.getVenueState().users().ensure(engine.getDIDString()).secrets().delete(ref);
			assertThrows(UnavailableException.class, () -> observer.credentials(job));
			observer.unavailable(job, new IllegalStateException("private-token"));
			assertEquals(Status.STARTED, job.getStatus());
			assertFalse(job.getData().toString().contains("private-token"));
		} finally { engine.close(); }
	}

	@Test void terminalEvidenceAndCredentialCleanupPrecedeResultContinuation() {
		Engine engine = Engine.createTemp(null);
		try (RemoteJobs observer = new RemoteJobs(engine, 1, 2)) {
			Job job = job();
			observer.prepare(job, "test", "test://peer", Maps.of("token", "private-token"));
			observer.accepted(job, "remote");
			CompletableFuture<Void> checked = job.future().thenAccept(result -> {
				assertEquals(Status.COMPLETE, RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_STATUS));
				assertThrows(UnavailableException.class, () -> observer.credentials(job));
			});
			job.completeWith(Strings.create("done"), data -> RemoteJobs.observed(data, Status.COMPLETE));
			checked.join();
		} finally { engine.close(); }
	}
}
