package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.UCAN;
import convex.auth.ucan.Capability;
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
import covia.api.Abilities;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.UcanTokens;
import covia.test.IntegrationTest;
import covia.venue.Engine;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TwoVenueTestServer;
import covia.venue.JobManager;
import covia.exception.AuthException;
import covia.grid.client.VenueHTTP;
import covia.grid.auth.VenueAuth;
import java.net.URI;

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
			assertEquals(CVMBool.TRUE, RT.getIn(run("pause", job, ctx), Fields.ACCEPTED));
			assertEquals(CVMBool.TRUE, RT.getIn(run("resume", job, ctx), Fields.ACCEPTED));
			assertEquals(CVMBool.TRUE, RT.getIn(run("cancel", job, ctx), Fields.ACCEPTED));
			assertEquals(Status.CANCELLED, job.getStatus());
		} finally { job.cancel(); }
		Job chat = remote("chat", Maps.empty());
		try {
			ACell ack = invoke("message", target(chat).assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "done")), ctx).awaitResult(5000);
			assertEquals(CVMBool.TRUE, RT.getIn(ack, Fields.DELIVERED));
			assertEquals(chat.getOutput(), run("result", chat, ctx));
			assertThrows(Exception.class, () -> invoke("message", target(chat)
				.assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "again")), ctx).awaitResult(5000));
			assertEquals(CVMBool.TRUE, RT.getIn(run("cancel", chat, ctx), Fields.ACCEPTED));
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

			Job wait = invoke("result", target(target), ctx.withCaps(Vectors.of(
				Capability.create(Strings.create("v/ops/job/result"), Abilities.INVOKE))));
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
			assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.ACCEPTED));
			assertEquals(Status.CANCELLED, target.getStatus());
		} finally { target.cancel(); }
	}

	private static void await(Job job, Predicate<Job> condition) throws Exception {
		CompletableFuture<Void> signal = new CompletableFuture<>();
		Consumer<Job> listener = changed -> { if (condition.test(changed)) signal.complete(null); };
		job.subscribe(listener);
		try { listener.accept(job); signal.get(5, TimeUnit.SECONDS); }
		finally { job.unsubscribe(listener); }
	}

	@Test void explicitRemoteCredentialsSupplyAuthorityIndependentlyOfLocalJobScope() throws Exception {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		RequestContext owner = RequestContext.of(did);
		String identity = UcanTokens.identityToken(key, TwoVenueTestServer.DID_B, 300);
		RequestContext remoteOnly = owner.withCaps(Vectors.empty())
			.withRawUcans(Vectors.of(Strings.create(identity)));
		Job target = destination.jobs().invokeOperation("v/test/ops/chat", Maps.empty(), owner);
		try {
			AMap<AString, ACell> input = target(target).assoc(Strings.create("authenticateAs"), Strings.create("caller"));
			for (String operation : new String[]{"v/ops/job/status", "v/ops/grid/job-status"}) {
				assertNotNull(source.jobs().runToolOperation(Strings.create(operation), input, remoteOnly).get());
				assertThrows(Exception.class, () -> source.jobs().runToolOperation(Strings.create(operation),
					input, remoteOnly.withRawUcans(Vectors.empty())).get());
			}
			assertEquals(CVMBool.TRUE, RT.getIn(source.jobs().runToolOperation(Strings.create("v/ops/job/message"),
				input.assoc(Fields.MESSAGE, Maps.of(Fields.CONTENT, "done")), remoteOnly).get(), Fields.DELIVERED));
			for (String operation : new String[]{"v/ops/job/result", "v/ops/grid/job-result"})
				assertEquals(target.getOutput(), source.jobs().runToolOperation(Strings.create(operation), input, remoteOnly).get());
		} finally { target.cancel(); }

		// Generic and dedicated controls use the same remote credential. Local
		// invoke permission is still required for a dynamically selected operation.
		RequestContext invokeOnly = remoteOnly.withCaps(Vectors.of(Capability.create(Strings.create("v/ops/"), Abilities.INVOKE)));
		for (String operation : new String[]{"run", "invoke", "cancel"}) {
			Job running = destination.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
			try {
				AMap<AString, ACell> relay = Maps.of(Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
					Fields.OPERATION, "v/ops/job/cancel", Fields.INPUT, Maps.of(Fields.ID, running.getID().toHexString()),
					"authenticateAs", "caller");
				if ("cancel".equals(operation)) {
					AMap<AString, ACell> direct = target(running).assoc(Strings.create("authenticateAs"), Strings.create("caller"));
					for (String control : new String[]{"pause", "resume", "cancel"})
						assertEquals(JobManager.acknowledgement(running.getID()), source.jobs().runToolOperation(
							Strings.create("v/ops/job/" + control), direct, remoteOnly).get());
				} else {
					assertThrows(Exception.class, () -> source.jobs().runToolOperation(Strings.create("v/ops/grid/" + operation), relay, remoteOnly).get());
					assertEquals(Status.STARTED, running.getStatus());
					source.jobs().runToolOperation(Strings.create("v/ops/grid/" + operation), relay, invokeOnly).get();
					await(running, Job::isFinished);
				}
				assertEquals(Status.CANCELLED, running.getStatus());
			} finally { running.cancel(); }
		}
	}

	@Test void destinationEnforcesDelegatedJobRightsForDirectAndGenericCalls() throws Exception {
		AKeyPair alice = AKeyPair.generate(), bob = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(alice.getAccountKey());
		Job target = destination.jobs().invokeOperation("v/test/ops/never", Maps.empty(), RequestContext.of(owner));
		try {
			long exp = System.currentTimeMillis() / 1000 + 300;
			AString read = UCAN.createJWT(alice, bob.getAccountKey(), exp,
				Vectors.of(Capability.create(JobManager.resource(owner, target.getID()), Abilities.JOB_READ)
					.assoc(Capability.NB, Maps.of(Fields.URL, destination.config().getBaseUrl()))), Vectors.empty());
			RequestContext delegated = RequestContext.of(UCAN.toDIDKey(bob.getAccountKey()))
				.withCaps(Vectors.of(Capability.create(Strings.create("v/ops/"), Abilities.INVOKE)))
				.withRawUcans(Vectors.of(Strings.create(UcanTokens.identityToken(bob, TwoVenueTestServer.DID_B, 300)), read));
			AMap<AString, ACell> direct = target(target).assoc(Strings.create("authenticateAs"), Strings.create("caller")).assoc(Fields.OWNER, owner);
			assertNotNull(source.jobs().runToolOperation(Strings.create("v/ops/job/status"), direct, delegated).get());
			AString wrongHost = UCAN.createJWT(alice, bob.getAccountKey(), exp,
				Vectors.of(Capability.create(JobManager.resource(owner, target.getID()), Abilities.JOB_READ)
					.assoc(Capability.NB, Maps.of(Fields.URL, "https://elsewhere.example/"))), Vectors.empty());
			RequestContext elsewhere = delegated.withRawUcans(Vectors.of(delegated.getRawUcans().get(0), wrongHost));
			assertThrows(Exception.class, () -> source.jobs().runToolOperation(Strings.create("v/ops/job/status"), direct, elsewhere).get());
			assertThrows(Exception.class, () -> source.jobs().runToolOperation(Strings.create("v/ops/job/cancel"), direct, delegated).get());
			AMap<AString, ACell> relay = Maps.of(Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
				Fields.OPERATION, "v/ops/job/cancel", Fields.INPUT, Maps.of(Fields.ID, target.getID().toHexString(), Fields.OWNER, owner),
				"authenticateAs", "caller");
			assertThrows(Exception.class, () -> source.jobs().runToolOperation(Strings.create("v/ops/grid/run"), relay, delegated).get());
			assertEquals(Status.STARTED, target.getStatus());
		} finally { target.cancel(); }
	}

	@Test void localProxyPermissionCancelsOnlyItsOwnedRemoteExecution() throws Exception {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		RequestContext submitter = RequestContext.of(did)
			.withCaps(Vectors.of(Capability.create(Strings.create("v/test/ops/never"), Abilities.INVOKE)))
			.withRawUcans(Vectors.of(Strings.create(UcanTokens.identityToken(key, TwoVenueTestServer.DID_B, 300))));
		Job proxy = source.jobs().startToolOperation(Strings.create("v/ops/grid/run"),
			Maps.of(Fields.VENUE, TwoVenueTestServer.BASE_URL_B, Fields.OPERATION, "v/test/ops/never", "authenticateAs", "caller"), submitter).job();
		Job remote = null;
		try {
			await(proxy, j -> Status.STARTED.equals(RT.getIn(RemoteJobs.descriptor(j), RemoteJobs.REMOTE_STATUS)));
			remote = destination.jobs().getJob(Job.parseID(RemoteJobs.remoteId(proxy)));
			assertNotNull(remote);
			RequestContext manager = RequestContext.of(did).withCaps(Vectors.of(
				Capability.create(JobManager.resource(did, proxy.getID()), Abilities.JOB_MANAGE)));
			assertThrows(AuthException.class, () -> source.jobs().getJobData(proxy.getID(), manager));
			assertThrows(AuthException.class, () -> source.jobs().cancelJob(proxy.getID(), submitter));
			assertEquals(JobManager.acknowledgement(proxy.getID()), source.jobs().cancelJob(proxy.getID(), manager));
			await(remote, Job::isFinished);
			assertEquals(Status.CANCELLED, remote.getStatus());
		} finally { proxy.cancel(); if (remote != null) remote.cancel(); }
	}

	@Test void delegatedControlsAndColdReadsCarryOwnerAndProofsOverHttp() {
		AKeyPair alice = AKeyPair.generate(), bob = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(alice.getAccountKey());
		Job target = destination.jobs().invokeOperation("v/test/ops/never", Maps.of("private", "input"), RequestContext.of(owner));
		VenueHTTP client = VenueHTTP.create(URI.create(TwoVenueTestServer.BASE_URL_B),
			VenueAuth.bearer(UcanTokens.identityToken(bob, TwoVenueTestServer.DID_B, 300)));
		client.setJobOwner(owner);
		try {
			long exp = System.currentTimeMillis() / 1000 + 300;
			AString resource = JobManager.resource(owner, target.getID());
			AString manage = UCAN.createJWT(alice, bob.getAccountKey(), exp,
				Vectors.of(Capability.create(resource, Abilities.JOB_MANAGE)), Vectors.empty());
			client.setUcans(java.util.List.of(manage.toString()));
			assertThrows(Exception.class, () -> client.getJobStatus(target.getID()).join());
			AMap<AString, ACell> ack = client.cancelJob(target.getID());
			assertEquals(CVMBool.TRUE, ack.get(Fields.ACCEPTED));
			assertEquals(target.getID(), Job.parseID(ack.get(Fields.ID)));
			assertEquals(2, ack.count());
			AString read = UCAN.createJWT(alice, bob.getAccountKey(), exp,
				Vectors.of(Capability.create(resource, Abilities.JOB_READ)), Vectors.empty());
			client.setUcans(java.util.List.of(read.toString()));
			destination.jobs().deleteJob(target.getID()); // keep the retained record
			assertEquals(Status.CANCELLED, client.getJobStatus(target.getID()).join().get(Fields.STATUS));
			client.setJobOwner(UCAN.toDIDKey(bob.getAccountKey()));
			assertNull(client.getJobData(target.getID()).join());
		} finally { target.cancel(); }
	}
}
