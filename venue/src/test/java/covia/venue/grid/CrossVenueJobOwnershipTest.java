package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.Engine;
import covia.venue.TwoVenueTestServer;

/**
 * #102 — cross-venue job ownership. A caller must never read another caller's
 * job on a remote venue via {@code grid:jobStatus} / {@code grid:jobResult}.
 *
 * <p>Examined boundary: {@code GridAdapter.selectVenue} connects to a remote
 * venue with {@code VenueAuth.none()} and does <b>not</b> forward the caller's
 * identity ({@code lv.setUser} runs only on the local branch). So a cross-venue
 * job read reaches the remote venue <b>anonymously</b>; the remote's
 * {@code AccessControl.canAccessJob} (owner-only, `jobCaller == callerDID`)
 * denies it. Fail-closed: there is no identity-forwarding surface to spoof
 * today. When cross-venue identity forwarding lands (Phase C3 / owner-rooted
 * UCAN trust), the remote must authenticate the forwarded identity
 * cryptographically — this test pins the current boundary so that change is
 * a deliberate, tested step, not a silent regression.</p>
 */
public class CrossVenueJobOwnershipTest {

	/** A bearer token for {@code kp}, audienced to {@code engine}'s venue. */
	private static String bearerFor(AKeyPair kp, Engine engine) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		return UCAN.create(kp, engine.getAccountKey(), exp, Vectors.empty(), Vectors.empty())
			.toJWT(kp).toString();
	}

	private static VenueHTTP authed(String baseUrl, AKeyPair kp, Engine engine) {
		VenueHTTP c = VenueHTTP.create(URI.create(baseUrl), VenueAuth.bearer(bearerFor(kp, engine)));
		c.setTimeout(5000);
		return c;
	}

	@Test
	public void crossVenueJobStatusAndResultDoNotLeakOwnedJob() throws Exception {
		// Bob authenticates to venue B (distinct DID) and submits a never-
		// completing job — it stays active/owned in B's cache.
		AKeyPair bob = AKeyPair.generate();
		VenueHTTP bobOnB = authed(TwoVenueTestServer.BASE_URL_B, bob, TwoVenueTestServer.ENGINE_B);
		Job bobJob = bobOnB.invoke(Strings.create("v/test/ops/never"), Maps.empty()).get();
		Blob bobJobId = bobJob.getID();
		assertNotNull(bobJobId);

		// Sanity: Bob, calling B directly, sees his own job.
		assertNotNull(bobOnB.getJobStatus(bobJobId).get(),
			"the owner must see his own job on the hosting venue");

		AString bobJobHex = Strings.create(bobJobId.toHexString());
		AString venueB = Strings.create(TwoVenueTestServer.BASE_URL_B);

		// Alice (a different DID) on venue A tries to read Bob's job on B via
		// the grid ops. The cross-venue hop carries no identity → B sees an
		// anonymous caller → canAccessJob denies (403) → the grid op FAILS.
		AKeyPair alice = AKeyPair.generate();
		VenueHTTP aliceOnA = authed(TwoVenueTestServer.BASE_URL_A, alice, TwoVenueTestServer.ENGINE_A);

		Job statusJob = aliceOnA.invokeAndWait(Strings.create("v/ops/grid/job-status"),
			Maps.of(Fields.VENUE, venueB, Fields.ID, bobJobHex), 5000);
		assertEquals(Status.FAILED, statusJob.getStatus(),
			"cross-venue grid:jobStatus must not reveal another caller's job: " + statusJob.getData());

		Job resultJob = aliceOnA.invokeAndWait(Strings.create("v/ops/grid/job-result"),
			Maps.of(Fields.VENUE, venueB, Fields.ID, bobJobHex,
				Fields.TIMEOUT, convex.core.data.prim.CVMLong.create(1000)), 5000);
		assertEquals(Status.FAILED, resultJob.getStatus(),
			"cross-venue grid:jobResult must not reveal another caller's output: " + resultJob.getData());
	}

	/**
	 * The other read surface: the cross-user lattice path
	 * {@code covia:read did:<owner>/j/<id>}. Unlike {@code grid:jobStatus}
	 * (owner-only via {@code canAccessJob}), this path is gated by UCAN proofs
	 * ({@code verifyProofs}) — but with <b>no</b> proof it is fail-closed.
	 */
	@Test
	public void crossUserJobLatticeReadDeniedWithoutProof() throws Exception {
		// Bob submits a job on B (his owned job record lives at <bobDID>/j/<id>).
		AKeyPair bob = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bob.getAccountKey());
		VenueHTTP bobOnB = authed(TwoVenueTestServer.BASE_URL_B, bob, TwoVenueTestServer.ENGINE_B);
		Job bobJob = bobOnB.invoke(Strings.create("v/test/ops/never"), Maps.empty()).get();
		AString jobPath = bobDID.append("/j/" + bobJob.getID().toHexString());

		// Alice (different DID) on B reads did:<bob>/j/<id> with no UCAN → denied.
		AKeyPair alice = AKeyPair.generate();
		VenueHTTP aliceOnB = authed(TwoVenueTestServer.BASE_URL_B, alice, TwoVenueTestServer.ENGINE_B);
		Job readJob = aliceOnB.invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, jobPath), 5000);
		assertEquals(Status.FAILED, readJob.getStatus(),
			"cross-user covia:read of another caller's j/ must be denied without a proof: "
				+ readJob.getData());
		assertTrue(String.valueOf(readJob.getErrorMessage()).contains("denied")
				|| String.valueOf(readJob.getErrorMessage()).toLowerCase().contains("access"),
			"denial should be an access error: " + readJob.getErrorMessage());

		// Sanity: Bob reading his own job via the DID-URL path succeeds.
		Job ownRead = bobOnB.invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, jobPath), 5000);
		assertEquals(Status.COMPLETE, ownRead.getStatus(),
			"the owner reads their own j/ record: " + ownRead.getData());
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(ownRead.getData(), Fields.OUTPUT, Strings.create("exists")));
	}
}
