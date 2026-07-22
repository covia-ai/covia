package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.HITLAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.grid.hitl.Hitl;
import covia.venue.TwoVenueTestServer;

/**
 * Cross-venue HITL (COG-16 §Cross-Venue Requests): the requester on venue A
 * asks a user whose inbox lives on venue B, via the standard federation
 * surfaces (COG-15) — no HITL-specific federation machinery exists, which is
 * the point:
 *
 * <ul>
 *   <li><b>Caller identity</b> crosses the hop as an audience-bound identity
 *       token; B attributes the request to the requester's own DID.</li>
 *   <li><b>Delivery authority</b> crosses in the proof channel: the target
 *       user's {@code hitl/request} delegation, forwarded by A, enforced by
 *       B at delivery exactly as in the local case.</li>
 *   <li><b>The Job lives on B</b> (it carries the request); the requester
 *       observes it through the federation job surfaces and receives any
 *       granted token in its output.</li>
 * </ul>
 *
 * <p>Uses the shared {@link TwoVenueTestServer} venues — nothing is spun up
 * per test; identities are fresh per test method.</p>
 */
public class HitlFederationTest {

	private static final AString OP_GRID_INVOKE = Strings.create("v/ops/grid/invoke");
	private static final AString OP_GRID_JOB_STATUS = Strings.create("v/ops/grid/job-status");

	/** Identity token: empty att, audienced to {@code venueDID} — pure proof
	 *  of the caller's identity at that venue, unusable anywhere else. */
	private static String identityToken(AKeyPair kp, String venueDID) {
		long exp = (System.currentTimeMillis() / 1000) + 300;
		return UCAN.create(kp, UCAN.fromDIDKey(Strings.create(venueDID)), exp,
			Vectors.empty(), Vectors.empty()).toJWT(kp).toString();
	}

	/** Polls the remote job through venue A until it reaches {@code wanted}
	 *  (or times out); returns the last status map seen. */
	private static AMap<AString, ACell> awaitRemoteStatus(VenueHTTP viaA, String remoteId,
			AString wanted, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		ACell lastSeen = null;
		while (System.currentTimeMillis() < deadline) {
			Job probe = viaA.invokeAndWait(OP_GRID_JOB_STATUS, Maps.of(
				Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
				"id", remoteId));
			lastSeen = probe.getData();
			if (Status.COMPLETE.equals(probe.getStatus())) {
				AMap<AString, ACell> status = RT.castMap(probe.getOutput());
				if (status != null && wanted.equals(RT.ensureString(status.get(Fields.STATUS)))) return status;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("remote job did not reach " + wanted + " within " + timeoutMs
			+ "ms; last probe: " + lastSeen);
	}

	private static String bare(String id) {
		return (id != null && id.startsWith("0x")) ? id.substring(2) : id;
	}

	/**
	 * The full COG-16 cross-venue flow: delegated delivery, remote park,
	 * response on B, completion observed (with the granted token) through A.
	 */
	@Test
	public void hitlRequestAcrossVenues() throws Exception {
		// Alice: inbox on venue B. Bob: requester, calling via venue A.
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AKeyPair bobKP = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());
		// The inbox owner must already be a registered user on venue B; HITL
		// delivery is not an account-provisioning side effect.
		TwoVenueTestServer.ENGINE_B.getVenueState().users().create(aliceDID);

		// Alice delegates hitl/request over her inbox to Bob (self-sovereign).
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		String hitlGrant = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(
				Strings.create(aliceDID + "/h/"), HITLAdapter.ABILITY_HITL_REQUEST)),
			Vectors.empty()).toJWT(aliceKP).toString();

		// Bob calls VENUE A; his ucans carry the delegation plus his identity
		// token for venue B. The grid op input carries data only (COG-15).
		VenueHTTP bobOnA = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.keyPair(bobKP));
		bobOnA.setUcans(java.util.List.of(
			hitlGrant, identityToken(bobKP, TwoVenueTestServer.DID_B)));

		Job hop = bobOnA.invokeAndWait(OP_GRID_INVOKE, Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.OPERATION, "v/ops/hitl/request",
			Fields.INPUT, Hitl.request("Report access")
				.to(aliceDID.toString())
				.description("Bob needs read access to your reports")
				.ask(Hitl.approval("access", "Grant report access?").required()
					.grant("w/reports/", "crud/read"))
				.build()));
		assertEquals(Status.COMPLETE, hop.getStatus(),
			"grid:invoke should return the remote job's status: "
				+ RT.getIn(hop.getData(), Fields.ERROR));
		AMap<AString, ACell> remote = RT.castMap(hop.getOutput());
		String remoteId = RT.ensureString(remote.get(Strings.intern("id"))).toString();
		assertEquals(Status.INPUT_REQUIRED, RT.ensureString(remote.get(Fields.STATUS)),
			"the remote HITL job parks INPUT_REQUIRED awaiting Alice");

		// The record landed in Alice's inbox ON VENUE B, attributed to Bob.
		AMap<AString, ACell> record = TwoVenueTestServer.ENGINE_B.getVenueState()
			.users().ensure(aliceDID).getHitlRequest(Strings.create(bare(remoteId)));
		assertNotNull(record, "delivery must land in Alice's inbox on venue B");
		assertEquals(bobDID, record.get(Hitl.FROM),
			"from is the requester's verified cross-venue identity");
		assertEquals(Hitl.OPEN, record.get(Hitl.STATUS));

		// Alice answers ON VENUE B, approving and echoing the offered grant.
		VenueHTTP aliceOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B), VenueAuth.keyPair(aliceKP));
		Job respond = aliceOnB.invokeAndWait(Strings.create("v/ops/hitl/respond"),
			Hitl.answer(remoteId)
				.answer("access", true)
				.echo("w/reports/", "crud/read")
				.comment("approved from venue B")
				.build());
		assertEquals(Status.COMPLETE, respond.getStatus(),
			"Alice's respond on B should complete: " + RT.getIn(respond.getData(), Fields.ERROR));

		// Bob observes completion through venue A's federation job surface and
		// receives the granted token in the output.
		AMap<AString, ACell> done = awaitRemoteStatus(bobOnA, remoteId, Status.COMPLETE, 10_000);
		assertNotNull(done, "remote job status must be observable through venue A");
		assertEquals(Status.COMPLETE, RT.ensureString(done.get(Fields.STATUS)));
		AMap<AString, ACell> output = RT.castMap(done.get(Fields.OUTPUT));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(output, "answers", "access"));

		AString jwt = RT.ensureString(output.get(Hitl.TOKEN));
		assertNotNull(jwt, "the granted token flows back in the job output");
		UCAN token = UCAN.fromJWT(jwt);
		assertEquals(bobDID, token.getAudience(), "token is audienced to the requester");
		assertEquals(Strings.create(aliceDID + "/w/reports/"),
			RT.getIn(token.getCapabilities().get(0), Capability.WITH),
			"the grant covers Alice's resource, canonicalised at issuance on B");
	}

	/**
	 * ADVERSARIAL: without the target's {@code hitl/request} delegation the
	 * remote delivery is refused — the job on B fails and nothing lands in
	 * Alice's inbox. Federation changes where the ask comes from, not what it
	 * is allowed to do.
	 */
	@Test
	public void hitlRequestAcrossVenuesDeniedWithoutDelegation() throws Exception {
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AKeyPair bobKP = AKeyPair.generate();
		TwoVenueTestServer.ENGINE_B.getVenueState().users().create(aliceDID);

		// Bob presents ONLY his identity token — no delegation from Alice.
		VenueHTTP bobOnA = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.keyPair(bobKP));
		bobOnA.setUcans(java.util.List.of(
			identityToken(bobKP, TwoVenueTestServer.DID_B)));

		Job hop = bobOnA.invokeAndWait(OP_GRID_INVOKE, Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.OPERATION, "v/ops/hitl/request",
			Fields.INPUT, Hitl.request("gimme")
				.to(aliceDID.toString())
				.ask(Hitl.approval("ok", "OK?"))
				.build()));
		assertEquals(Status.COMPLETE, hop.getStatus(),
			"grid:invoke itself succeeds — the DENIAL is the remote job's state");
		AMap<AString, ACell> remote = RT.castMap(hop.getOutput());
		String remoteId = RT.ensureString(remote.get(Strings.intern("id"))).toString();

		AMap<AString, ACell> failed = awaitRemoteStatus(bobOnA, remoteId, Status.FAILED, 10_000);
		assertNotNull(failed);
		assertEquals(Status.FAILED, RT.ensureString(failed.get(Fields.STATUS)),
			"delivery without the hitl/request delegation must fail the remote job");
		AString err = RT.ensureString(failed.get(Fields.ERROR));
		assertTrue(err != null && err.toString().contains("hitl/request"),
			"the denial names the missing ability: " + err);

		// And nothing landed in Alice's inbox on B.
		assertNull(TwoVenueTestServer.ENGINE_B.getVenueState()
			.users().ensure(aliceDID).getHitlRequest(Strings.create(bare(remoteId))),
			"a denied delivery must leave no record");
	}
}
