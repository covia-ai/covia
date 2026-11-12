package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.test.IntegrationTest;
import covia.venue.Engine;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TwoVenueTestServer;

/** The same controls through a second venue's real Job endpoints. */
@IntegrationTest
class JobAdapterRemoteTest {

	private final Engine source = TwoVenueTestServer.ENGINE_A;
	private final Engine destination = TwoVenueTestServer.ENGINE_B;

	private AMap<AString, ACell> target(Job job) {
		return Maps.of(Fields.ID, job.getID().toHexString(), Fields.VENUE, TwoVenueTestServer.BASE_URL_B);
	}
	private Job invoke(String op, AMap<AString, ACell> input, RequestContext ctx) {
		return source.jobs().invokeOperation("v/ops/job/" + op, input, ctx);
	}
	private ACell run(String op, Job job, RequestContext ctx) { return invoke(op, target(job), ctx).awaitResult(5000); }
	private Job remote(String op, ACell input) {
		return destination.jobs().invokeOperation("v/test/ops/" + op, input, destination.context(destination.publicPrincipal()));
	}

	@Test void remoteControlsAndMessagesUseTheHostingVenue(TestInfo info) {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		Job job = remote("never", Maps.empty());
		try {
			assertEquals(Status.STARTED, RT.getIn(run("status", job, ctx), Fields.STATUS));
			assertEquals(Status.PAUSED, RT.getIn(run("pause", job, ctx), Fields.STATUS));
			assertEquals(Status.STARTED, RT.getIn(run("resume", job, ctx), Fields.STATUS));
			assertEquals(Status.CANCELLED, RT.getIn(run("cancel", job, ctx), Fields.STATUS));
			assertEquals(Status.CANCELLED, job.getStatus());
		} finally { job.cancel(); }
		Job chat = remote("chat", Maps.empty());
		try {
			ACell ack = invoke("message", target(chat).assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "done")), ctx).awaitResult(5000);
			assertEquals(CVMBool.TRUE, RT.getIn(ack, Fields.DELIVERED));
			assertEquals(chat.getOutput(), run("result", chat, ctx));
			assertThrows(Exception.class, () -> invoke("message", target(chat)
				.assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "again")), ctx).awaitResult(5000));
			assertEquals(Status.COMPLETE, RT.getIn(run("cancel", chat, ctx), Fields.STATUS));
		} finally { chat.cancel(); }
	}

	@Test void resultWaitsNeverCancelTheRemoteWorkAndCanReattach(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		Job target = remote("never", Maps.empty());
		try {
			Job cancelled = invoke("result", target(target), ctx);
			await(cancelled, j -> Status.STARTED.equals(RT.getIn(RemoteJobs.descriptor(j), RemoteJobs.REMOTE_STATUS)));
			source.jobs().cancelJob(cancelled.getID(), ctx);
			assertEquals(Status.STARTED, target.getStatus());
			Job timed = invoke("result", target(target).assoc(Fields.TIMEOUT, CVMLong.create(20)), ctx);
			assertThrows(Exception.class, () -> timed.awaitResult(5000));
			assertEquals(JobAdapter.RESULT_TIMEOUT.formatted(20, target.getID().toHexString()), timed.getErrorMessage());
			assertFalse(target.future().isDone());

			Job wait = invoke("result", target(target), ctx);
			await(wait, j -> Status.STARTED.equals(RT.getIn(RemoteJobs.descriptor(j), RemoteJobs.REMOTE_STATUS)));
			JobAdapter adapter = (JobAdapter) source.getAdapter("job");
			adapter.suspendJob(wait);
			target.completeWith(Maps.of("answer", 42));
			adapter.recoverJob(wait);
			assertEquals(target.getOutput(), wait.awaitResult(5000));
			assertEquals(target.getID().toHexString(), RemoteJobs.remoteId(wait).toString());
		} finally { target.cancel(); }
	}

	@Test void targetFailureAndInputWaitKeepTheirMeaning(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		Job target = remote("chat", Maps.empty());
		Job wait = invoke("result", target(target), ctx);
		try {
			await(wait, j -> Status.INPUT_REQUIRED.equals(RT.getIn(RemoteJobs.descriptor(j), RemoteJobs.REMOTE_STATUS)));
			assertEquals(Status.STARTED, wait.getStatus(), "the result wait does not itself accept input");
			target.fail("test:failure", "test failure");
			assertThrows(Exception.class, () -> wait.awaitResult(5000));
			assertEquals(Status.FAILED, wait.getStatus());
			assertEquals(target.getErrorCode(), wait.getErrorCode());
		} finally { wait.cancel(); target.cancel(); }
	}

	@Test void remoteAuthorityIsExplicitAndPreservesPrivateJobAccess(TestInfo info) {
		AKeyPair key = AKeyPair.generate();
		RequestContext owner = RequestContext.of(UCAN.toDIDKey(key.getAccountKey()));
		Job target = destination.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		try {
			RequestContext caller = RequestContext.of(TestEngine.uniqueDID(info));
			assertThrows(Exception.class, () -> invoke("status", target(target), caller).awaitResult(5000));
			assertThrows(Exception.class, () -> invoke("cancel", target(target), caller).awaitResult(5000));
			assertEquals(Status.STARTED, target.getStatus());
			String credential = UcanTokens.identityToken(key, TwoVenueTestServer.DID_B, 300);
			RequestContext signed = owner.withRawUcans(Vectors.of(Strings.create(credential)));
			ACell result = invoke("cancel", target(target).assoc(Strings.create("authenticateAs"), Strings.create("caller")), signed).awaitResult(5000);
			assertEquals(Status.CANCELLED, RT.getIn(result, Fields.STATUS));
		} finally { target.cancel(); }
	}

	private static void await(Job job, Predicate<Job> condition) throws Exception {
		CompletableFuture<Void> signal = new CompletableFuture<>();
		Consumer<Job> listener = changed -> { if (condition.test(changed)) signal.complete(null); };
		job.subscribe(listener);
		try { listener.accept(job); signal.get(5, TimeUnit.SECONDS); }
		finally { job.unsubscribe(listener); }
	}
}
