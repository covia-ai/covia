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
 * A caller's context a job keeps for work that continues after the request:
 * its proofs, in the form checked on arrival, and its execution scopes, kept
 * in the job's entry, presented again when the work continues, never on the
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
		engine.jobs().keepCallerContext(job, presenting);
		String id = job.getID().toHexString();
		JobIndex index = engine.getVenueState().jobIndex();
		try {
			assertEquals(Maps.of(Fields.PROOFS, proofs, Fields.SESSION_ID, session, Fields.TASK_ID, task),
				index.entry(job.getID()).get(JobIndex.CONTEXT), "the job's entry keeps them while its work can continue");
			ACell stored = RT.getIn(engine.getVenueState().users().user(alice).cursor().path(Namespace.J).get(), job.getID());
			assertFalse(RT.ensureMap(stored).containsKey(Fields.PROOFS), "the record never holds them");
			assertNull(RT.getIn(read("j/" + id, own), Fields.VALUE, Fields.PROOFS));

			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(alice, resumed.getCallerDID());
			assertEquals(proofs, resumed.getProofs(), "work that continues presents them again");
			assertEquals(session, resumed.getSessionId(), "in the scopes it was started in");
			assertEquals(task, resumed.getTaskId());
		} finally {
			job.cancel();
		}
		assertNull(index.entry(job.getID()), "gone when the job ends");
		assertFalse(engine.jobs().keepsCallerContext(job));
		AVector<ACell> after = engine.jobs().callerContext(job).getProofs();
		assertTrue(after == null || after.isEmpty(), "a finished job presents nothing again");
	}

	/** A job that asked keeps a context even when its caller presented nothing beyond itself, unlike one that never asked. */
	@Test void aJobThatAskedKeepsAContextWhenItsCallerPresentedNothingMore() {
		RequestContext own = RequestContext.of(TestEngine.uniqueDID("KeptContextBare"));
		Job asked = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), own);
		Job never = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), own);
		engine.jobs().keepCallerContext(asked, own);
		try {
			assertEquals(Maps.empty(), engine.getVenueState().jobIndex().entry(asked.getID()).get(JobIndex.CONTEXT));
			assertTrue(engine.jobs().keepsCallerContext(asked));
			assertFalse(engine.jobs().keepsCallerContext(never));
		} finally {
			asked.cancel();
			never.cancel();
		}
	}

	/**
	 * A call to an agent on this venue that keeps no context, as one whose
	 * entry was migrated from an older store, is not followed again after a
	 * restart: it ends, and the agent's task runs on.
	 */
	@Test void aLocalAgentCallThatKeptNoContextEndsAtRecovery() {
		RequestContext own = RequestContext.of(TestEngine.uniqueDID("KeptContextLocalCall"));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "waiting",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")), own).awaitResult(10_000);
		Job call = engine.jobs().invokeOperation("v/ops/remoteagent/request",
			Maps.of(Fields.AGENT_ID, "waiting", Fields.INPUT, Maps.of("task", "wait")), own);
		TestEngine.awaitJob(call, j -> RT.getIn(RemoteJobs.descriptor(j), RemoteJobs.REMOTE_STATUS) != null,
			10_000, () -> "the call did not see its task: " + call.getData());
		Job task = engine.jobs().getJob(Job.parseID(RemoteJobs.descriptor(call).get(RemoteJobs.REMOTE_ID)), own);
		try {
			assertTrue(engine.jobs().keepsCallerContext(call), "a call to an agent here keeps its caller's context");
			engine.getVenueState().jobIndex().update(call.getID(), entry -> entry.dissoc(JobIndex.CONTEXT));
			covia.adapter.AAdapter adapter = engine.getAdapter("remoteagent");
			adapter.suspendJob(call);
			adapter.recoverJob(call);
			assertEquals(covia.grid.Status.FAILED, call.getStatus());
			assertEquals(covia.adapter.RemoteAgentAdapter.LOCAL_INTERRUPTED, call.getErrorMessage());
			assertFalse(task.isFinished(), "the agent's task runs on");
		} finally {
			task.cancel();
			engine.jobs().invokeOperation("v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "waiting"), own).awaitResult(10_000);
		}
	}

	/** A delegate's job regains its authority only through the grant it presented, as a live request would. */
	@Test void aDelegatesJobActsAgainOnlyThroughItsGrant() {
		AKeyPair ownerKey = AKeyPair.generate();
		AKeyPair delegateKey = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(ownerKey.getAccountKey());
		AString delegate = UCAN.toDIDKey(delegateKey.getAccountKey());
		engine.getVenueState().users().create(owner);
		engine.getVenueState().users().create(delegate);
		AString grant = UCAN.create(ownerKey, delegateKey.getAccountKey(), exp(),
			Vectors.of(Capability.create(owner, Abilities.USER_SUDO),
				Capability.create(Strings.create(owner + "/v/test/ops/never"), Strings.create("invoke")),
				Capability.create(Strings.create(owner + "/w/output"), Capability.CRUD_WRITE)),
			Vectors.empty()).toJWT(ownerKey);
		RequestContext sudo = engine.sudoContext(engine.withTransportGrants(
				RequestContext.of(delegate), Vectors.of(grant)), owner)
			.withAgentId(Strings.create("worker")).withSessionId(Blob.fromHex("1234"));

		Job kept = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), sudo);
		Job unkept = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), sudo);
		engine.jobs().keepCallerContext(kept, sudo);
		try {
			assertEquals(delegate, kept.getCaller());
			assertEquals(owner, kept.getOwner());
			RequestContext resumed = engine.jobs().callerContext(kept);
			assertEquals(delegate, resumed.getCallerDID());
			assertEquals(owner, resumed.getUserDID(), "in the owner's namespace, as the grant allows");
			assertEquals(sudo.getExecutionScope(), resumed.getExecutionScope());
			assertTrue(engine.authorityCovers(resumed, Strings.create("w/output"), Capability.CRUD_WRITE));
			assertFalse(engine.authorityCovers(resumed, Strings.create("w/other"), Capability.CRUD_WRITE),
				"bounded by the grant, as it was");
			assertThrows(AuthException.class, () -> engine.jobs().callerContext(unkept),
				"without its grant a delegate cannot act for the owner again");
		} finally {
			kept.cancel();
			unkept.cancel();
		}
	}

	/** A request naming an output path keeps its caller's context in its own job, for the write; the worker's task keeps none. */
	@Test void aRequestNamingAnOutputPathKeepsItsCallersContextInItsJob() {
		AKeyPair aliceKey = AKeyPair.generate();
		AString alice = UCAN.toDIDKey(aliceKey.getAccountKey());
		AVector<ACell> proofs = grantTo(aliceKey);
		RequestContext own = RequestContext.of(alice);
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "writer",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")), own).awaitResult(10_000);

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
		engine.jobs().keepCallerContext(job, agent);
		try {
			RequestContext resumed = engine.jobs().callerContext(job);
			assertEquals(agent.getCallerDID(), resumed.getCallerDID());
			assertEquals(owner, resumed.getUserDID());
			assertEquals(agent.getExecutionScope(), resumed.getExecutionScope());
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
