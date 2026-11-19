package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.lattice.Namespace;

/**
 * The state a recorded job keeps from admission until it finishes, for work
 * that continues after the request: its frame, the grants it presented, in
 * the form checked on arrival, its route and its execution scopes, kept in
 * the job's entry, acted in again when the work continues, never on the
 * record, and gone when the job ends.
 */
class KeptCallerContextTest {

	private static final Engine engine = TestEngine.ENGINE;

	private static long exp() {
		return System.currentTimeMillis() / 1000 + 3600;
	}

	/** A verified grant from a fresh user to {@code audience}, as a proof vector. */
	private static AVector<ACell> grantTo(AKeyPair audience) {
		AKeyPair grantor = AKeyPair.generate();
		AString with = Strings.create(UCAN.toDIDKey(grantor.getAccountKey()) + "/w/shared");
		return Vectors.of(UCAN.create(grantor, audience.getAccountKey(), exp(),
			Vectors.of(Capability.create(with, Capability.CRUD_READ)), Vectors.empty()).toMap());
	}

	private static ACell read(String path, RequestContext ctx) {
		return engine.jobs().invokeOperation("v/ops/covia/read", Maps.of(Fields.PATH, path), ctx).awaitResult(10_000);
	}

	@Test void aJobKeepsItsCallersContextInItsEntryUntilItEnds() {
		AKeyPair aliceKey = AKeyPair.generate();
		AString alice = UCAN.toDIDKey(aliceKey.getAccountKey());
		AVector<ACell> proofs = grantTo(aliceKey);
		Blob session = Blob.fromHex("5e".repeat(16));
		Blob task = Blob.fromHex("7a".repeat(16));
		RequestContext presenting = RequestContext.of(alice).withProofs(proofs).withSessionId(session).withTaskId(task);
		RequestContext own = RequestContext.of(alice);

		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), presenting);
		String id = job.getID().toHexString();
		JobIndex index = engine.getVenueState().jobIndex();
		try {
			assertEquals(Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, alice), Fields.PROOFS, proofs,
				Fields.ROUTE, presenting.getRoute(), Fields.SESSION_ID, session, Fields.JOB_ID, job.getID(), Fields.TASK_ID, task),
				index.entry(job.getID()).get(JobIndex.CONTEXT), "the job's entry keeps them while its work can continue");
			ACell stored = RT.getIn(engine.getVenueState().users().user(alice).cursor().path(Namespace.J).get(), job.getID());
			assertFalse(RT.ensureMap(stored).containsKey(Fields.PROOFS), "the record never holds them");
			assertNull(RT.getIn(read("j/" + id, own), Fields.VALUE, Fields.PROOFS));

			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(alice, resumed.getAuthorityDID());
			assertEquals(proofs, resumed.getProofs(), "work that continues presents them again");
			assertEquals(session, resumed.getSessionId(), "in the scopes it was started in");
			assertEquals(task, resumed.getTaskId());
			assertEquals(presenting.getRoute(), resumed.getRoute(), "as the same request, on the route it came by");
			assertNull(resumed.getOrigin(), "with nobody present in it");
		} finally {
			job.cancel();
		}
		assertNull(index.entry(job.getID()), "gone when the job ends");
		AuthException ended = assertThrows(AuthException.class, () -> engine.jobs().callerContext(job),
			"a finished job never acts again");
		assertEquals(JobManager.NO_STATE + job.getID(), ended.getMessage());
	}

	/**
	 * Every recorded job keeps a state, whatever its operation, even when its
	 * caller presented nothing beyond itself, as work the venue starts for a
	 * principal does: its frame alone.
	 */
	@Test void everyRecordedJobKeepsAStateWhenItsCallerPresentedNothingMore() {
		AString did = TestEngine.uniqueDID("KeptContextBare");
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), engine.context(did));
		try {
			assertEquals(Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, did), Fields.JOB_ID, job.getID()),
				engine.getVenueState().jobIndex().entry(job.getID()).get(JobIndex.CONTEXT));
			assertEquals(did, engine.jobs().callerContext(job).getAuthorityDID());
		} finally {
			job.cancel();
		}
	}


	/**
	 * A call to an agent on this venue that keeps no state, as one whose
	 * entry came from a store that kept none, is not followed again after a
	 * restart: it ends, and the agent's task runs on.
	 */
	@Test void aLocalAgentCallThatKeptNoStateEndsAtRecovery() {
		RequestContext own = RequestContext.of(TestEngine.uniqueDID("KeptContextLocalCall"));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "waiting",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never", "acceptTasks", true)), own).awaitResult(10_000);
		Job call = engine.jobs().invokeOperation("v/ops/remoteagent/request",
			Maps.of(Fields.AGENT_ID, "waiting", Fields.INPUT, Maps.of("task", "wait")), own);
		TestEngine.awaitJob(call, j -> RT.getIn(RemoteJobs.descriptor(j), RemoteJobs.REMOTE_STATUS) != null,
			10_000, () -> "the call did not see its task: " + call.getData());
		Job task = engine.jobs().getJob(Job.parseID(RemoteJobs.descriptor(call).get(RemoteJobs.REMOTE_ID)), own);
		try {
			assertNotNull(engine.getVenueState().jobIndex().entry(call.getID()).get(JobIndex.CONTEXT),
				"a call to an agent here keeps its state");
			engine.getVenueState().jobIndex().update(call.getID(), entry -> entry.dissoc(JobIndex.CONTEXT));
			covia.adapter.AAdapter adapter = engine.getAdapter("remoteagent");
			adapter.suspendJob(call);
			adapter.recoverJob(call);
			assertEquals(covia.grid.Status.FAILED, call.getStatus());
			assertEquals(covia.adapter.RemoteAgentAdapter.CALLER_CANNOT_ACT.formatted(JobManager.NO_STATE + call.getID(),
				task.getID()), call.getErrorMessage());
			assertFalse(task.isFinished(), "the agent's task runs on");
		} finally {
			task.cancel();
			engine.jobs().invokeOperation("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "waiting"), own).awaitResult(10_000);
		}
	}

	/**
	 * Work acts again only as a principal of the account it is kept in: a
	 * record there naming another, as one a delegate left before admin/sudo
	 * acted as the user does, regains no authority.
	 */
	@Test void workKeptInAnotherAccountDoesNotActAsItsCaller() {
		AString owner = TestEngine.uniqueDID("KeptOwner");
		AString delegate = TestEngine.uniqueDID("KeptDelegate");
		AuthException refused = assertThrows(AuthException.class, () -> engine.context(owner, delegate, Vectors.empty()));
		assertEquals(Engine.NOT_IN_ACCOUNT.formatted(delegate, owner), refused.getMessage());
		assertEquals(owner, engine.context(owner, owner, Vectors.empty()).getAuthorityDID(), "the account itself acts again");
	}

	/**
	 * A job made under admin/sudo is the user's own: it records the user and
	 * acts again with the user's whole authority, whatever becomes of the
	 * delegate's grant.
	 */
	@Test void aJobMadeUnderSudoIsTheUsersOwn() {
		AKeyPair ownerKey = AKeyPair.generate();
		AKeyPair delegateKey = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(ownerKey.getAccountKey());
		AString delegate = UCAN.toDIDKey(delegateKey.getAccountKey());
		engine.getVenueState().users().create(owner);
		engine.getVenueState().users().create(delegate);
		AString grant = UCAN.create(ownerKey, delegateKey.getAccountKey(), exp(),
			Vectors.of(Capability.create(owner, Abilities.ADMIN_SUDO)), Vectors.empty()).toJWT(ownerKey);
		RequestContext sudo = engine.sudoContext(engine.withTransportGrants(
			RequestContext.of(delegate), Vectors.of(grant)), owner);

		Job kept = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), sudo);
		try {
			assertEquals(owner, kept.getCaller(), "the user acted");
			assertEquals(owner, JobManager.accountOf(kept), "in the user's own account");
			RequestContext resumed = engine.jobs().callerContext(kept);
			assertEquals(owner, resumed.getAuthorityDID());
			assertNull(resumed.getOrigin(), "nobody is present in work carried on");
			assertNotNull(sudo.getRoute());
			assertEquals(sudo.getRoute(), resumed.getRoute(), "which still names the delegate and the handover to the user");
			assertTrue(engine.authorityCovers(resumed, Strings.create("w/other"), Capability.CRUD_WRITE),
				"with the user's whole authority");
		} finally {
			kept.cancel();
		}
	}

	/** A request naming an output path keeps its caller's context in its own job, for the write; the worker's task keeps none. */
	@Test void aRequestNamingAnOutputPathKeepsItsCallersContextInItsJob() {
		AKeyPair aliceKey = AKeyPair.generate();
		AString alice = UCAN.toDIDKey(aliceKey.getAccountKey());
		AVector<ACell> proofs = grantTo(aliceKey);
		RequestContext own = RequestContext.of(alice);
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "writer",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never", "acceptTasks", true)), own).awaitResult(10_000);

		Job request = engine.jobs().invokeOperation("v/ops/agent/request", Maps.of(Fields.AGENT_ID, "writer",
			Fields.INPUT, Maps.of("q", "write"), Fields.OUTPUT_PATH, "w/out"), own.withProofs(proofs));
		try {
			assertEquals(proofs, RT.getIn(engine.getVenueState().jobIndex().entry(request.getID()), JobIndex.CONTEXT, Fields.PROOFS),
				"the request's job keeps them for the write");
			assertEquals(proofs, engine.jobs().callerContext(request).getProofs(), "the write presents them again");
			AMap<AString, ACell> task = RT.ensureMap(engine.getVenueState().users().user(alice).agent("writer")
				.getTasks().get(request.getID()));
			assertNotNull(task);
			assertFalse(task.containsKey(Fields.OUTPUT_PATH), "the request's input names the path");
			assertFalse(task.containsKey(Strings.create("outputContext")), "the worker keeps nothing of its requester's context");
		} finally {
			engine.jobs().invokeOperation("v/ops/agent/cancel-task", Maps.of(Fields.AGENT_ID, "writer",
				Fields.TASK_ID, request.getID().toHexString()), own).awaitResult(10_000);
			engine.jobs().invokeOperation("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "writer"), own).awaitResult(10_000);
		}
		assertNull(engine.getVenueState().jobIndex().entry(request.getID()), "gone when the request ends");
	}

	/** An agent's job acts as the agent stands now, never under a copy of its caps. */
	@Test void anAgentsJobActsUnderItsCurrentCaps() {
		AString owner = TestEngine.uniqueDID("KeptContextAgentOwner");
		RequestContext ownerCtx = RequestContext.of(owner);
		AVector<ACell> caps = Vectors.of(Maps.of(Capability.CAN, Strings.create("invoke")));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "worker",
			Fields.CONFIG, Maps.of(Config.CAPS, caps)), ownerCtx).awaitResult(10_000);
		RequestContext agent = engine.context(owner, Principals.agentDID(owner, Strings.create("worker")))
			.withAgentId(Strings.create("worker"));

		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), agent);
		try {
			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(agent.getAuthorityDID(), resumed.getAuthorityDID());
			assertEquals(owner, resumed.getUserDID());
			assertEquals(agent.getAgentId(), resumed.getAgentId());
			assertEquals(job.getID(), resumed.getJobId(), "in the job's own scope, as it ran");
			assertEquals(caps, resumed.getCaps());

			AVector<ACell> narrower = Vectors.of(Maps.of(Capability.CAN, Strings.create("crud/read")));
			engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "worker",
				Fields.CONFIG, Maps.of(Config.CAPS, narrower)), ownerCtx).awaitResult(10_000);
			assertEquals(narrower, engine.jobs().callerContext(job).getCaps(), "narrowing the agent narrows the work");
		} finally {
			job.cancel();
		}
	}
}
