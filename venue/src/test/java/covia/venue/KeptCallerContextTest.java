package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDVerifier;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.lattice.Namespace;
import covia.venue.server.AuthMiddleware;

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
			ACell stored = RT.getIn(engine.getVenueState().users().get(alice).cursor().path(Namespace.J).get(), job.getID());
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
		AVector<ACell> after = engine.jobs().callerContext(job).getProofs();
		assertTrue(after == null || after.isEmpty(), "a finished job presents nothing again");
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
				Capability.create(Strings.create(owner + "/v/test/ops/never"), Strings.create("invoke"))),
			Vectors.empty()).toJWT(ownerKey);
		RequestContext sudo = engine.sudoContext(AuthMiddleware.withTransportGrants(
			RequestContext.of(delegate), Vectors.of(grant), DIDVerifier.CONVEX), owner);

		Job kept = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), sudo);
		Job unkept = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), sudo);
		engine.jobs().keepCallerContext(kept, sudo);
		try {
			assertEquals(delegate, kept.getCaller());
			assertEquals(owner, kept.getOwner());
			RequestContext resumed = engine.jobs().callerContext(kept);
			assertEquals(delegate, resumed.getCallerDID());
			assertEquals(owner, resumed.getUserDID(), "in the owner's namespace, as the grant allows");
			assertThrows(AuthException.class, () -> engine.jobs().callerContext(unkept),
				"without its grant a delegate cannot act for the owner again");
		} finally {
			kept.cancel();
			unkept.cancel();
		}
	}

	/** A pending output write keeps its requester's proofs with the task, shown to no caller. */
	@Test void anAgentRecordShowsNoOutputSnapshotsProofs() {
		AKeyPair aliceKey = AKeyPair.generate();
		AString alice = UCAN.toDIDKey(aliceKey.getAccountKey());
		AVector<ACell> proofs = grantTo(aliceKey);
		Blob task = Blob.fromHex("0d".repeat(16));
		User user = engine.getVenueState().users().ensure(alice);
		AgentState agent = user.ensureAgent("writer", Maps.empty(), null);
		AMap<AString, ACell> snapshot = Maps.of(Fields.CALLER, alice, Fields.PROOFS, proofs);
		agent.putRecord(agent.getRecord().assoc(AgentState.KEY_TASKS,
			Index.of(task, Maps.of(AgentState.KEY_OUTPUT_CONTEXT, snapshot))));
		RequestContext own = RequestContext.of(alice);
		String taskHex = task.toHexString();

		assertEquals(Maps.of(Fields.CALLER, alice),
			RT.getIn(read("g/writer/tasks/" + taskHex + "/outputContext", own), Fields.VALUE));
		assertEquals(Maps.of(Fields.CALLER, alice), RT.getIn(read("g/writer", own), Fields.VALUE,
			AgentState.KEY_TASKS, task, AgentState.KEY_OUTPUT_CONTEXT));
		assertEquals(Maps.of(Fields.CALLER, alice), RT.getIn(read("g", own), Fields.VALUE,
			Strings.create("writer"), AgentState.KEY_TASKS, task, AgentState.KEY_OUTPUT_CONTEXT));
		assertEquals(proofs, RT.getIn(agent.getTasks().get(task), AgentState.KEY_OUTPUT_CONTEXT, Fields.PROOFS),
			"the task keeps them for the write");
	}
}
