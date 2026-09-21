package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

class RemoteJobsTest {
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
			assertThrows(IllegalStateException.class, () -> observer.credentials(job));
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
				assertThrows(IllegalStateException.class, () -> observer.credentials(job));
			});
			job.completeWith(Strings.create("done"), data -> RemoteJobs.observed(data, Status.COMPLETE));
			checked.join();
		} finally { engine.close(); }
	}
}
