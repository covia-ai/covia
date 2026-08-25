package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.exception.JobFailedException;
import covia.venue.RequestContext;
import covia.venue.TwoVenueTestServer;

/**
 * Federation coverage for issue #71 output handoffs. A foreign did:web
 * destination must enter the remote caller-authentication path rather than
 * becoming a local shadow user or silently falling back to anonymous.
 */
public class AgentOutputPathFederationTest {

	@Test
	public void outputPathFailsClosedWithoutRemoteIdentityCredential() {
		AString caller = Strings.create("did:key:zOutputPathFederationCaller");
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

	@Test
	public void managedUserPathRoutesByVenueDid() {
		AString path = Strings.create(
			"did:web:remote.example:u:alice:g:manager/w/pipeline/out");
		assertEquals(Strings.create("did:web:remote.example"),
			CoviaAdapter.remoteVenueFor(path));
	}
}
