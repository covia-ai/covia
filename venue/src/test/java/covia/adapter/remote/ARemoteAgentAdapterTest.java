package covia.adapter.remote;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import convex.core.data.*;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

class ARemoteAgentAdapterTest {
	private final Engine engine = Engine.createTemp(null);
	private final Probe adapter = new Probe();
	ARemoteAgentAdapterTest() { engine.registerAdapter(adapter); }
	@AfterEach void close() { engine.close(); }

	static class Interaction implements RemoteInteraction {
		final AtomicInteger cancelled = new AtomicInteger();
		AMap<AString, ACell> delivered;
		@Override public void cancel() { cancelled.incrementAndGet(); }
		@Override public void deliver(AMap<AString, ACell> record) { delivered = record; }
	}
	static class Probe extends ARemoteAgentAdapter<Interaction> {
		final AtomicInteger starts = new AtomicInteger();
		final AtomicInteger ordinary = new AtomicInteger();
		@Override public String getName() { return "remoteprobe"; }
		@Override public String getDescription() { return "Test interaction lifecycle"; }
		@Override protected boolean isInteractionOperation(String operation) { return !"admin".equals(operation); }
		@Override protected boolean returnsFirstReply(String operation) { return "session".equals(operation); }
		@Override protected void startInteraction(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			starts.incrementAndGet(); bindInteraction(job, new Interaction());
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			ordinary.incrementAndGet(); return CompletableFuture.completedFuture(Strings.create("admin"));
		}
	}
	private static Job job() { return Job.create(Maps.of(Fields.ID, Blob.fromHex("00112233445566778899aabbccddeeff"), Fields.STATUS, Status.PENDING)); }
	private static AMap<AString, ACell> meta(String operation) { return Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "remoteprobe:" + operation)); }

	@Test void startsOnceAndReleasesTerminalBindings() {
		Job job = job();
		adapter.invoke(job, engine.venueContext(), meta("task"), null);
		Interaction live = adapter.interaction(job);
		assertNotNull(live);
		adapter.invoke(job, engine.venueContext(), meta("task"), null);
		assertEquals(1, adapter.starts.get());
		AMap<AString, ACell> record = Maps.of(Fields.MESSAGE, Maps.of("parts", Vectors.of(Maps.of("data", Maps.of("x", 1)))));
		adapter.handleMessage(job, record);
		assertSame(record, live.delivered);
		job.completeWith(Strings.create("done"));
		assertNull(adapter.interaction(job));
		ARemoteAgentAdapter.publishProgress(job, Maps.of("late", true));
		assertNull(job.getData().get(ARemoteAgentAdapter.PROGRESS));
		assertThrows(IllegalStateException.class, () -> adapter.handleMessage(job, record));
	}

	@Test void cancellationBeforeAndAfterAcceptance() {
		Job job = job(); job.cancel();
		adapter.invoke(job, engine.venueContext(), meta("task"), null);
		assertEquals(0, adapter.starts.get());
		Interaction late = new Interaction(); adapter.bindInteraction(job, late);
		assertEquals(1, late.cancelled.get()); assertNull(adapter.interaction(job));
		Job running = job(); adapter.invoke(running, engine.venueContext(), meta("task"), null);
		Interaction live = adapter.interaction(running);
		assertSame(live, adapter.bindInteraction(running, new Interaction()));
		running.cancel(); running.cancel();
		assertEquals(1, live.cancelled.get()); assertNull(adapter.interaction(running));
	}

	@Test void conversationReplyDoesNotCompleteTaskOrConversation() {
		Job job = job(); adapter.invoke(job, engine.venueContext(), meta("session"), null);
		CompletableFuture<ACell> first = adapter.resultFuture(job, meta("session"), null);
		CompletableFuture<ACell> task = adapter.resultFuture(job, meta("task"), null);
		job.update(data -> data.assoc(Fields.OUTPUT, Strings.create("reply")).assoc(Fields.STATUS, Status.INPUT_REQUIRED));
		assertEquals(Strings.create("reply"), first.join()); assertFalse(task.isDone());
		assertNotNull(adapter.interaction(job));
		assertEquals(Strings.create("reply"), adapter.resultFuture(job, meta("session"), null).join());
		job.cancel(); assertTrue(task.isCompletedExceptionally());
	}

	@Test void failedLateCancellationStillReleasesBinding() {
		Job job = job(); job.cancel();
		RuntimeException failure = new IllegalStateException("test cancellation failure");
		Interaction late = new Interaction() { @Override public void cancel() { throw failure; } };
		assertSame(failure, assertThrows(RuntimeException.class, () -> adapter.bindInteraction(job, late)));
		assertNull(adapter.interaction(job));
	}

	@Test void refusesMissingBindingsAndPreservesAdministrativeDispatch() {
		Job job = job();
		assertEquals(ARemoteAgentAdapter.NO_INTERACTION,
			assertThrows(IllegalStateException.class, () -> adapter.handleMessage(job, Maps.empty())).getMessage());
		adapter.invoke(job, engine.venueContext(), meta("admin"), null);
		assertEquals(Strings.create("admin"), job.future().join());
		assertEquals(1, adapter.ordinary.get()); assertEquals(0, adapter.starts.get());
	}

	@Test void deniedAuthorityCannotStartExternalWork() {
		Job job = job();
		assertThrows(RuntimeException.class, () -> adapter.invoke(job, engine.venueContext().withCaps(Vectors.empty()), meta("task"), null));
		assertEquals(Status.FAILED, job.getStatus()); assertEquals(0, adapter.starts.get());
	}
}
