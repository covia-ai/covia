package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.auth.did.DIDVerifier;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AMap;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.api.Abilities;
import covia.exception.AuthException;
import covia.exception.JobFailedException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.server.AuthMiddleware;
import covia.venue.TestEngine;
import covia.venue.TwoVenueTestServer;

/**
 * Federation coverage for issue #71 output handoffs. A foreign did:web
 * destination must enter the remote caller-authentication path rather than
 * becoming a local shadow user or silently falling back to anonymous.
 */
public class AgentOutputPathFederationTest {

	@Test
	public void outputPathFailsClosedWithoutRemoteIdentityCredential() {
		AString caller = TestEngine.uniqueDID("OutputPathFederationCaller");
		RequestContext ctx = RequestContext.of(caller);
		String agentId = "remote-output-worker";

		TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			ctx).awaitResult(10_000);

		AString remoteOwner = Strings.create(
			"did:web:localhost%3A" + TwoVenueTestServer.PORT_B);
		AString outputPath = Strings.create(remoteOwner + "/w/output-path-71");
		ACell payload = Maps.of("federated", CVMBool.TRUE);
		Job request = TwoVenueTestServer.ENGINE_A.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.INPUT, payload,
				Fields.OUTPUT_PATH, outputPath),
			ctx);
		assertThrows(JobFailedException.class, () -> request.awaitResult(10_000));
		assertTrue(request.getErrorMessage().contains("identity credential"),
			"A remote output write must explicitly authenticate as its caller");
		assertNull(TwoVenueTestServer.ENGINE_A.getVenueState().users().get(remoteOwner),
			"Foreign did:web owner must not be materialised as a local shadow user");
		assertNull(TwoVenueTestServer.ENGINE_B.getVenueState().users().get(remoteOwner),
			"Denied remote write must not materialise the destination owner either");

		// Missing authentication fails before dispatch and cannot create a remote
		// public Job as a side effect.
		RequestContext publicB = RequestContext.of(
			Strings.create(TwoVenueTestServer.DID_B + ":public"));
		Index<Blob, ACell> jobs = TwoVenueTestServer.ENGINE_B.jobs().getJobs(publicB);
		boolean remoteJobFound = false;
		for (var entry : jobs.entrySet()) {
			if (outputPath.equals(RT.getIn(entry.getValue(), Fields.INPUT, Fields.PATH))) {
				remoteJobFound = true;
				break;
			}
		}
		assertFalse(remoteJobFound,
			"A missing caller credential must not fall back to an anonymous remote write");
	}

	/** A delegate's pending write acts for the owner again only through the grant it presented. */
	@Test
	public void outputContextRecoveryActsAsADelegateOnlyThroughItsGrant() {
		Engine engine = TwoVenueTestServer.ENGINE_A;
		AKeyPair ownerKey = AKeyPair.generate();
		AKeyPair delegateKey = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(ownerKey.getAccountKey());
		AString delegate = UCAN.toDIDKey(delegateKey.getAccountKey());
		engine.getVenueState().users().create(owner);
		engine.getVenueState().users().create(delegate);
		AString grant = UCAN.create(ownerKey, delegateKey.getAccountKey(), System.currentTimeMillis() / 1000 + 3600,
			Vectors.of(Capability.create(owner, Abilities.USER_SUDO),
				Capability.create(Strings.create(owner + "/w/output"), Capability.CRUD_WRITE)),
			Vectors.empty()).toJWT(ownerKey);
		RequestContext context = engine.sudoContext(AuthMiddleware.withTransportGrants(
				RequestContext.of(delegate), Vectors.of(grant), DIDVerifier.CONVEX), owner)
			.withAgentId(Strings.create("worker")).withSessionId(Blob.fromHex("1234"));

		AMap<AString, ACell> snapshot = AgentAdapter.snapshotOutputContext(context);
		assertEquals(context.getProofs(), snapshot.get(Fields.PROOFS), "kept for the write");
		RequestContext restored = agents().restoreOutputContext(snapshot);
		assertEquals(delegate, restored.getCallerDID());
		assertEquals(owner, restored.getUserDID());
		assertEquals(context.getExecutionScope(), restored.getExecutionScope());
		assertTrue(engine.authorityCovers(restored, Strings.create("w/output"), Capability.CRUD_WRITE));
		assertFalse(engine.authorityCovers(restored, Strings.create("w/other"), Capability.CRUD_WRITE),
			"bounded by the grant, as it was");
		assertThrows(AuthException.class, () -> agents().restoreOutputContext(snapshot.dissoc(Fields.PROOFS)),
			"without its grant a delegate cannot act for the owner again");
	}

	/** An agent's pending write acts as the agent stands then, never under a copy of its caps. */
	@Test
	public void outputContextRecoveryActsAsAnAgentUnderItsCurrentCaps() {
		Engine engine = TwoVenueTestServer.ENGINE_A;
		AString owner = TestEngine.uniqueDID("OutputContextAgentOwner");
		RequestContext ownerCtx = RequestContext.of(owner);
		AVector<ACell> caps = Vectors.of(Maps.of(Capability.CAN, Strings.create("invoke")));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "worker",
			Fields.CONFIG, Maps.of(Config.CAPS, caps)), ownerCtx).awaitResult(10_000);
		RequestContext agent = engine.context(owner, Principals.agentDID(owner, Strings.create("worker")));

		AMap<AString, ACell> snapshot = AgentAdapter.snapshotOutputContext(agent);
		assertFalse(snapshot.containsKey(Config.CAPS), "the snapshot keeps no copy of the agent's caps");
		RequestContext restored = agents().restoreOutputContext(snapshot);
		assertEquals(agent.getCallerDID(), restored.getCallerDID());
		assertEquals(owner, restored.getUserDID());
		assertEquals(caps, restored.getCaps());

		AVector<ACell> narrower = Vectors.of(Maps.of(Capability.CAN, Strings.create("crud/read")));
		engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "worker",
			Fields.CONFIG, Maps.of(Config.CAPS, narrower)), ownerCtx).awaitResult(10_000);
		assertEquals(narrower, agents().restoreOutputContext(snapshot).getCaps(), "narrowing the agent narrows the write");

		// An authenticated agent-shaped subject stays in its own namespace.
		AString subject = Strings.create(owner + ":u:alice:g:worker");
		RequestContext external = agents().restoreOutputContext(AgentAdapter.snapshotOutputContext(
			RequestContext.of(subject).withCaps(Vectors.empty())));
		assertEquals(subject, external.getUserDID());
		assertEquals(Vectors.empty(), external.getCaps());
	}

	private static AgentAdapter agents() {
		return (AgentAdapter) TwoVenueTestServer.ENGINE_A.getAdapter("agent");
	}

	@Test
	public void managedUserPathRoutesByVenueDid() {
		AString path = Strings.create(
			"did:web:remote.example:u:alice:g:manager/w/pipeline/out");
		assertEquals(Strings.create("did:web:remote.example"),
			CoviaAdapter.remoteVenueFor(path));
	}
}
