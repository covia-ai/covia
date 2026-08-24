package covia.venue.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.CompletionException;

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
import covia.exception.ResponseException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.UcanTokens;
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
		assertTrue(TwoVenueTestServer.DID_A.startsWith("did:web:"));
		assertTrue(TwoVenueTestServer.DID_B.startsWith("did:web:"));
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
	 * A UCAN that venue B did not issue and that is not even audienced to venue
	 * B is rejected when presented to B as a bearer. Venue A signs a token
	 * delegating to Bob ({@code aud = Bob}); presenting it to B fails audience
	 * validation (#149) at the transport boundary — a bearer's {@code aud} must
	 * name the venue it is presented to (RFC 7519 §4.1.3), so a token minted for
	 * another party cannot be replayed to B.
	 *
	 * <p>Before #149 this same token was caught later at the proof check;
	 * audience validation now rejects it earlier, at the door, as a hard 401.
	 * Defence in depth: even past the door, the root-authority policy
	 * (covia#196) would refuse it — venue A is neither the resource owner
	 * (self-sovereign) nor venue B (custodial), so it may not root the grant.</p>
	 *
	 * <p>The inverse assertion — an <em>owner-rooted</em> token crossing venues
	 * successfully — lives in {@link #crossVenueUCANIsAccepted()}.</p>
	 */
	@Test
	public void venueAIssuedUCANRejectedByVenueB() throws Exception {
		// Bob's identity (the token's audience). Generated, not registered.
		AKeyPair bobKP = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());

		// Venue A's keypair signs a UCAN delegating crud/read to Bob.
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AKeyPair venueAKP = TwoVenueTestServer.ENGINE_A.getKeyPair();
		String resource = bobDID + "/w/";
		UCAN aIssued = UCAN.create(venueAKP,
			UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(Strings.create(resource), Capability.CRUD_READ)),
			Vectors.empty());
		String jwt = aIssued.toJWT(venueAKP).toString();

		// Bob presents venue A's UCAN to venue B. Its audience is Bob, not venue
		// B — audience validation rejects it with 401 before any job is created.
		VenueHTTP bobOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B),
			VenueAuth.bearer(jwt));
		bobOnB.setUser(bobDID.toString());

		// Venue B refuses the token, which the client raises as a typed
		// ResponseException. Asserting the type — not text in the cause chain —
		// is what makes this a test of venue B's answer: a transport failure
		// arrives as a different type and now fails the test as itself.
		CompletionException failure = assertThrows(CompletionException.class, () ->
			bobOnB.startJobAsync(Strings.create("v/ops/covia/read"),
				Maps.of(Fields.PATH, bobDID + "/w/somewhere")).join());
		assertInstanceOf(ResponseException.class, failure.getCause());
	}

	/**
	 * covia#100 / #196 — Phase C3a: a <b>self-sovereign</b> grant crosses venues.
	 * Alice ({@code did:key}, holding data on venue B) signs a delegation to Bob
	 * herself; Bob presents it to venue B over HTTP (the request-body {@code ucans}
	 * channel) while authenticating as himself (self-issued did:key JWT). Venue B
	 * verifies the token's signature at ingress through its DID-method resolver and
	 * the chain root against the resource owner ({@code RootAuthorityPolicy
	 * .SELF_SOVEREIGN}) — <b>no venue ever issued or attested anything</b>: the
	 * whole trust path is Alice's own key. This was the pinned spec gap of
	 * covia#100; formerly {@code @Disabled} under the Phase C1 venue-issuer rule.
	 */
	@Test
	public void crossVenueUCANIsAccepted() throws Exception {
		// Alice: a self-sovereign did:key identity with data hosted on venue B.
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		VenueHTTP aliceOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B), VenueAuth.keyPair(aliceKP));
		Job write = aliceOnB.invokeAndWait(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("shared content")));
		assertEquals(Status.COMPLETE, write.getStatus(), "Alice's write on B should complete");

		// Alice signs the delegation HERSELF: iss = Alice (the resource owner),
		// aud = Bob. Venue B is not the issuer — nor is any venue.
		AKeyPair bobKP = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN grant = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(
				Strings.create(aliceDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		String grantJWT = grant.toJWT(aliceKP).toString();

		// Bob authenticates to B as himself and presents Alice's grant as a proof.
		VenueHTTP bobOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B), VenueAuth.keyPair(bobKP));
		bobOnB.setUcans(java.util.List.of(grantJWT));

		Job read = bobOnB.invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, aliceDID + "/w/shared/doc"));
		assertEquals(Status.COMPLETE, read.getStatus(),
			"an owner-rooted (self-sovereign) grant must authorise across venues: "
				+ RT.getIn(read.getData(), Fields.ERROR));
		assertEquals(Strings.create("shared content"),
			RT.getIn(read.getData(), Fields.OUTPUT, "value"));

		// And the grant is exactly what authorised it: without the proof, denied.
		VenueHTTP bobNoProof = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B), VenueAuth.keyPair(bobKP));
		Job denied = bobNoProof.invokeAndWait(Strings.create("v/ops/covia/read"),
			Maps.of(Fields.PATH, aliceDID + "/w/shared/doc"));
		assertNotEquals(Status.COMPLETE, denied.getStatus(),
			"without the proof the same read must be denied");
	}

	// ============== C3a: grid hop forwards the caller's authority ==============
	//
	// Authority travels ONLY in the ucans proof channel — grid op input carries
	// data only (a credential in input would be persisted in the job record).
	// Tokens are self-describing: an identity token (empty att, audienced to the
	// TARGET venue) proves the caller there; a venue/relay delegation (issued by
	// the caller, audienced to THIS venue) instructs the venue to hop as itself.

	/** Mints an identity token: a UCAN with EMPTY att, audienced to {@code venueDID}
	 *  — pure proof of identity for that venue, unusable anywhere else. */
	private static String identityToken(AKeyPair kp, String venueDID) {
		return UcanTokens.identityToken(kp, venueDID, 300);
	}

	/**
	 * Passthrough hop: Bob calls {@code grid:run} on venue A targeting venue B,
	 * presenting (in his {@code ucans}) Alice's self-sovereign grant plus his
	 * identity token <em>for venue B</em>. A relays the tokens; B verifies Bob's
	 * own signature on the identity token (zero trust in A) and authorises the
	 * read via Alice's grant. The grid op input carries no authority at all.
	 */
	@Test
	public void gridHopForwardsCallerAuthority() throws Exception {
		// Alice: data on venue B, self-signed grant to Bob.
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		VenueHTTP aliceOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B), VenueAuth.keyPair(aliceKP));
		aliceOnB.invokeAndWait(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, "w/hop/doc", Fields.VALUE, Strings.create("hop content")));

		AKeyPair bobKP = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bobKP.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		String aliceGrant = UCAN.create(aliceKP, UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(Capability.create(
				Strings.create(aliceDID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty()).toJWT(aliceKP).toString();

		// Bob calls VENUE A. His ucans carry everything: the grant (aud = Bob)
		// and his identity token for venue B (aud = B, empty att).
		VenueHTTP bobOnA = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.keyPair(bobKP));
		bobOnA.setUcans(java.util.List.of(
			aliceGrant, identityToken(bobKP, TwoVenueTestServer.DID_B)));

		Job hop = bobOnA.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.OPERATION, "v/ops/covia/read",
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.INPUT, Maps.of(Fields.PATH, aliceDID + "/w/hop/doc")));
		assertEquals(Status.COMPLETE, hop.getStatus(),
			"grid hop with relayed caller authority should complete: "
				+ RT.getIn(hop.getData(), Fields.ERROR));
		assertEquals(Strings.create("hop content"),
			RT.getIn(hop.getData(), Fields.OUTPUT, "value"));

		// Control: without an identity token the hop is anonymous at B — the
		// grant alone (audienced to Bob) cannot authorise an anonymous caller.
		VenueHTTP bobNoIdentity = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.keyPair(bobKP));
		bobNoIdentity.setUcans(java.util.List.of(aliceGrant));
		Job anon = bobNoIdentity.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.OPERATION, "v/ops/covia/read",
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.INPUT, Maps.of(Fields.PATH, aliceDID + "/w/hop/doc")));
		assertNotEquals(Status.COMPLETE, anon.getStatus(),
			"without an identity token the hop is anonymous and must be denied");
	}

	/**
	 * Venue-as-delegate hop: Alice grants VENUE A a token carrying both her
	 * {@code crud/read} authority and the {@code venue/relay} instruction
	 * (iss = Alice, aud = venue A). No flag anywhere — the token is both the
	 * authorisation and the instruction. A hops authenticated as itself; at B
	 * the chain roots at Alice = owner.
	 */
	@Test
	public void gridHopVenueRelayExercisesDelegation() throws Exception {
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		VenueHTTP aliceOnB = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_B), VenueAuth.keyPair(aliceKP));
		aliceOnB.invokeAndWait(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, "w/deleg/doc", Fields.VALUE, Strings.create("delegated content")));

		// One token: Alice → venue A, granting read over her namespace AND the
		// relay instruction (venue/relay over her own DID).
		String toVenueA = UcanTokens.relayDelegation(aliceKP,
			TwoVenueTestServer.DID_A, 3600,
			aliceDID + "/w/", Capability.CRUD_READ.toString());

		VenueHTTP aliceOnA = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.keyPair(aliceKP));
		aliceOnA.setUcans(java.util.List.of(toVenueA));

		Job hop = aliceOnA.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.OPERATION, "v/ops/covia/read",
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.INPUT, Maps.of(Fields.PATH, aliceDID + "/w/deleg/doc")));
		assertEquals(Status.COMPLETE, hop.getStatus(),
			"venue/relay hop under the caller's delegation should complete: "
				+ RT.getIn(hop.getData(), Fields.ERROR));
		assertEquals(Strings.create("delegated content"),
			RT.getIn(hop.getData(), Fields.OUTPUT, "value"));
	}

	/**
	 * Confused-deputy guard: a relay instruction is only an instruction when
	 * ISSUED BY the authenticated caller. Carol presenting a {@code venue/relay}
	 * token Bob minted for venue A gets no relay — the hop stays anonymous and
	 * the read is denied at B.
	 */
	@Test
	public void gridHopRelayInstructionMustComeFromCaller() throws Exception {
		AKeyPair aliceKP = AKeyPair.generate();
		AString aliceDID = UCAN.toDIDKey(aliceKP.getAccountKey());
		AKeyPair bobKP = AKeyPair.generate();
		AKeyPair carolKP = AKeyPair.generate();

		// Bob (not Carol) mints a venue/relay token for venue A.
		String bobRelay = UcanTokens.relayDelegation(bobKP,
			TwoVenueTestServer.DID_A, 3600);

		// Carol presents Bob's token: issuer != caller → not an instruction from
		// Carol → anonymous hop → denied at B.
		VenueHTTP carolOnA = VenueHTTP.create(
			URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.keyPair(carolKP));
		carolOnA.setUcans(java.util.List.of(bobRelay));

		Job hop = carolOnA.invokeAndWait(OP_GRID_RUN, Maps.of(
			Fields.OPERATION, "v/ops/covia/read",
			Fields.VENUE, TwoVenueTestServer.BASE_URL_B,
			Fields.INPUT, Maps.of(Fields.PATH, aliceDID + "/w/deleg/doc")));
		assertNotEquals(Status.COMPLETE, hop.getStatus(),
			"a relay token issued by someone other than the caller must not trigger relay");
	}
}
