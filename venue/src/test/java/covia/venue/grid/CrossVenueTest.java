package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.TwoVenueTestServer;

/**
 * Tests that exercise true cross-venue federation: two distinct
 * {@link covia.venue.server.VenueServer} instances communicating over
 * HTTP. Closes gaps that loopback-only {@code GridAdapterTest} cannot
 * cover — distinct DIDs, real network round-trip, error propagation,
 * cross-issuer trust policy.
 *
 * <p>Infrastructure lives in {@link TwoVenueTestServer}.</p>
 */
public class CrossVenueTest {

	private static final AString OP_GRID_RUN = Strings.create("v/ops/grid/run");
	private static final AString OP_STRING_CONCAT = Strings.create("v/ops/jvm/string-concat");

	// ============== Sanity: the harness has two distinct venues ==============

	@Test
	public void venuesHaveDistinctDIDs() {
		assertNotEquals(TwoVenueTestServer.DID_A, TwoVenueTestServer.DID_B,
			"Each venue must have its own DID — distinct seeds in TwoVenueTestServer");
	}

	@Test
	public void venuesHaveDistinctPorts() {
		assertNotEquals(TwoVenueTestServer.PORT_A, TwoVenueTestServer.PORT_B,
			"Each venue listens on its own ephemeral port");
	}

	// ============== grid:run from venue A targeting venue B ==============

	@Test
	public void runOperationOnRemoteVenue() throws Exception {
		// Caller hits A; A's grid:run forwards to B; B executes; result returns through A.
		Job job = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/grid/run", Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.OPERATION, "v/ops/jvm/string-concat",
			Fields.INPUT, Maps.of(
				"first", "Cross",
				"second", "Venue"
			)));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("CrossVenue", RT.getIn(job.getOutput(), "result").toString());
	}

	@Test
	public void invokeAsyncOnRemoteVenue() throws Exception {
		// grid:invoke returns the remote job's status payload (async path).
		Job job = TwoVenueTestServer.COVIA_A.invokeSync("v/ops/grid/invoke", Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.OPERATION, "v/ops/jvm/string-concat",
			Fields.INPUT, Maps.of(
				"first", "Async",
				"second", "Federate"
			)));

		assertNotNull(job);
		assertEquals(Status.COMPLETE, job.getStatus());
		// grid:invoke returns the remote job's status map
		assertEquals(Status.COMPLETE,
			RT.ensureString(RT.getIn(job.getOutput(), Fields.STATUS)));
	}

	// ============== Direct cross-venue traffic via VenueHTTP ==============

	@Test
	public void directInvokeOnVenueBHitsVenueB() throws Exception {
		// Sanity: each VenueHTTP client targets its own venue, not the other.
		// Run an op on B directly, verify the job lives on B's engine.
		Job jobOnB = TwoVenueTestServer.COVIA_B.invokeSync(
			"v/ops/jvm/string-concat",
			Maps.of("first", "On", "second", "B"));
		assertEquals(Status.COMPLETE, jobOnB.getStatus());
		assertEquals("OnB", RT.getIn(jobOnB.getOutput(), "result").toString());

		// Same op on A — different job, different engine.
		Job jobOnA = TwoVenueTestServer.COVIA_A.invokeSync(
			"v/ops/jvm/string-concat",
			Maps.of("first", "On", "second", "A"));
		assertEquals(Status.COMPLETE, jobOnA.getStatus());
		assertEquals("OnA", RT.getIn(jobOnA.getOutput(), "result").toString());

		assertNotEquals(jobOnA.getID(), jobOnB.getID(),
			"Two independent venues must mint independent job IDs");
	}

	// ============== Error propagation across venues ==============

	@Test
	public void remoteVenueUnknownOperationFailsCleanly() throws Exception {
		// Invoke a nonexistent op on B via A — A's grid:run should surface
		// the failure without hanging or producing a blank error.
		Job job = TwoVenueTestServer.COVIA_A.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.OPERATION, "v/ops/does/not/exist",
			Fields.INPUT, Maps.empty()));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus(),
			"Unknown remote op must surface as FAILED");
		AString err = RT.ensureString(RT.getIn(job.getData(), Fields.ERROR));
		assertNotNull(err, "Failure must carry a non-null error message");
		assertTrue(!err.toString().isBlank(),
			"Error message must not be blank — see GridAdapter.describeFailure");
	}

	@Test
	public void remoteVenueOperationFailureSurfacesAsFailed() throws Exception {
		// TestAdapter has v/test/ops/fail — invoke it on B via A and verify
		// the failure crosses the venue boundary intact.
		Job job = TwoVenueTestServer.COVIA_A.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.OPERATION, "v/test/ops/fail",
			Fields.INPUT, Maps.of("message", "intentional-cross-venue-failure")));

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus(),
			"Remote operation failure must propagate as FAILED through grid:run");
		AString err = RT.ensureString(RT.getIn(job.getData(), Fields.ERROR));
		assertNotNull(err);
		assertTrue(!err.toString().isBlank());
	}

	@Test
	public void unreachableRemoteVenueFailsWithoutHanging() throws Exception {
		// Point A at a port nothing is listening on. Must FAIL within the
		// VenueHTTP timeout (5s), not hang the test.
		String deadURL = "http://localhost:1"; // privileged port, refused

		long start = System.currentTimeMillis();
		Job job = TwoVenueTestServer.COVIA_A.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.VENUE, deadURL,
			Fields.OPERATION, "v/ops/jvm/string-concat",
			Fields.INPUT, Maps.of("first", "x", "second", "y")));
		long elapsed = System.currentTimeMillis() - start;

		assertNotNull(job);
		assertEquals(Status.FAILED, job.getStatus(),
			"Unreachable remote venue must FAIL, not hang");
		assertTrue(elapsed < 30_000,
			"Failure must surface well before the test framework gives up; took " + elapsed + "ms");

		AString err = RT.ensureString(RT.getIn(job.getData(), Fields.ERROR));
		assertNotNull(err, "Connect failure must carry a non-null error message");
		assertTrue(!err.toString().isBlank(),
			"Connect failure must carry a non-blank error message — regression for "
			+ "GridAdapter#describeFailure handling of null-message ConnectException");
	}

	// ============== UCAN issuer policy across venues (Phase C1) ==============

	/**
	 * Pinned current behaviour: a UCAN signed by venue A's keypair, with
	 * Bob as audience, presented to venue B as a bearer credential, is
	 * rejected. Phase C1 of UCAN enforcement requires the receiving venue
	 * to be the issuer of any token it accepts (CoviaAdapter:1270-1272).
	 *
	 * <p>The token is cryptographically valid (good signature, valid
	 * audience, in date) — the rejection is a policy decision, not a
	 * transport failure.</p>
	 *
	 * <p>When Phase C2/C3 introduces cross-issuer trust, the inverse
	 * assertion lives in {@link #crossVenueUCANIsAccepted()} (currently
	 * {@code @Disabled}).</p>
	 */
	@Test
	public void venueAIssuedUCANRejectedByVenueB() throws Exception {
		// Bob's identity (audience). Generated, not registered — this test
		// is purely about the issuer policy, not Bob's prior state on B.
		AKeyPair bobKP = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());

		// Venue A's keypair signs a UCAN that grants Bob crud/read on
		// /w/ within Bob's own namespace on venue B.
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AKeyPair venueAKP = TwoVenueTestServer.ENGINE_A.getKeyPair();
		String resource = bobDID + "/w/";
		UCAN aIssued = UCAN.create(venueAKP,
			UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(Strings.create(resource), Capability.CRUD_READ)),
			Vectors.empty());
		String jwt = aIssued.toJWT(venueAKP).toString();

		// Bob hits venue B with venue A's UCAN as a bearer.
		// AuthMiddleware will accept the bearer and set callerDID = bobDID
		// (the UCAN's audience under IETF UCAN-HTTP). The proof is then
		// available on the request, but CoviaAdapter.verifyProofs requires
		// iss == venueDID. Issuer here is venue A, not B — rejected.
		VenueHTTP bobOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B),
			VenueAuth.bearer(jwt));
		bobOnB.setUser(bobDID.toString());

		Job readJob = bobOnB.invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, bobDID + "/w/somewhere"));
		assertNotNull(readJob);
		assertEquals(Status.FAILED, readJob.getStatus(),
			"Phase C1: UCAN issuer must be the verifying venue. "
			+ "A-signed token at B must be rejected by CoviaAdapter.verifyProofs.");
	}

	/**
	 * Pinned spec gap. Phase C1 only honours venue-self-signed UCANs —
	 * see {@link #venueAIssuedUCANRejectedByVenueB()} for the matching
	 * current-behaviour test. When a future phase introduces cross-issuer
	 * trust (e.g. trusted-venues list, did:key issuer resolution, or
	 * proof-chain to a mutually-trusted root), this test should turn
	 * green — and the {@code @Disabled} should come off.
	 */
	@Test
	@Disabled("Phase C1 of UCAN enforcement only accepts venue-self-signed tokens "
		+ "(CoviaAdapter:1270-1272). Cross-issuer trust is a future phase.")
	public void crossVenueUCANIsAccepted() throws Exception {
		// When implemented, mirror venueAIssuedUCANRejectedByVenueB but
		// assert Status.COMPLETE. The trust model TBD: trusted-venues
		// list, federated proof chain, or DID-resolved issuer policy.
	}
}
