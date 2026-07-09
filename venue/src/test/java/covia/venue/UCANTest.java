package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.CapabilityChecker;
import covia.venue.server.AuthMiddleware;

/**
 * Tests for the UCAN capability flow: issue tokens, present as proofs,
 * verify cross-user access.
 *
 * <p>Uses {@link TestEngine#ENGINE}. Per-test key pairs (and therefore
 * per-test Alice/Bob/Carol DIDs) isolate user namespaces — Alice's
 * {@code w/shared/doc} and {@code w/private/secret} written in setup
 * live under a different DID for each test method, so writes don't
 * collide across tests on the shared engine.</p>
 */
public class UCANTest {

	final Engine engine = TestEngine.ENGINE;
	private final AKeyPair venueKP = engine.getKeyPair();
	private final AString venueDID = engine.getDIDString();

	// Per-test key pairs — fresh DIDs each test method so writes to
	// /w/shared/doc and /w/private/secret are user-namespaced and isolated.
	private AKeyPair ALICE_KP;
	private AKeyPair BOB_KP;
	private AKeyPair CAROL_KP;
	private AString ALICE_DID;
	private AString BOB_DID;
	private AString CAROL_DID;
	private RequestContext ALICE;
	private RequestContext BOB;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_KP = AKeyPair.generate();
		BOB_KP = AKeyPair.generate();
		CAROL_KP = AKeyPair.generate();
		ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
		BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
		CAROL_DID = UCAN.toDIDKey(CAROL_KP.getAccountKey());
		ALICE = RequestContext.of(ALICE_DID);
		BOB = RequestContext.of(BOB_DID);

		// Alice writes some workspace data
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("shared content")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/private/secret", Fields.VALUE, Strings.create("private content")),
			ALICE).awaitResult(5000);
	}

	// ========== #131 — self-attenuation ceiling on the direct invoke path ==========
	//
	// The owner is the authority over its own namespace; the venue enforces. A
	// self-ceiling is an owner-authored attenuation over the owner's own
	// resources (iss == aud == caller). withTransportAuth derives it (via
	// CapabilityChecker.selfCapabilities) and sets it as caps; enforceCaps applies
	// it against owner-scoped (canonical) resources. A token NOT authored by the
	// owner (e.g. venue-signed) forms no self-ceiling.

	private static final long HOUR = 3600;

	/** A token the owner signs for itself (iss == aud == owner) — the authority
	 *  over its own namespace attenuating its own session. */
	private AString ownerToken(AKeyPair ownerKP, AVector<ACell> caps) {
		return UCAN.createJWT(ownerKP, ownerKP.getAccountKey(),
			(System.currentTimeMillis() / 1000) + HOUR, caps, null);
	}

	/** A venue-signed token (iss == venue) audienced to the owner — not the
	 *  owner's own authority, so it forms no self-ceiling. */
	private AString venueToken(AString audienceDID, AVector<ACell> caps) {
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, audienceDID, UCAN.ATT, caps,
				UCAN.EXP, CVMLong.create((System.currentTimeMillis() / 1000) + HOUR)), ALICE);
		return RT.ensureString(RT.getIn(job.awaitResult(5000), "token"));
	}

	/** One capability over an absolute (owner-scoped) resource. */
	private static AVector<ACell> caps(String withResource, AString can) {
		return Vectors.of((ACell) Capability.create(Strings.create(withResource), can));
	}

	@Test
	public void testSelfCapabilitiesNullArgsFailClosed() {
		long now = System.currentTimeMillis() / 1000;
		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(
			Vectors.of(ownerToken(ALICE_KP, caps(ALICE_DID + "/w/health", Capability.CRUD))));
		assertNull(CapabilityChecker.selfCapabilities(null, ALICE_DID, ALICE_DID, now));
		assertNull(CapabilityChecker.selfCapabilities(proofs, null, ALICE_DID, now));
		assertNull(CapabilityChecker.selfCapabilities(proofs, ALICE_DID, null, now));
	}

	@Test
	public void testSelfCapabilitiesOwnerAuthoredIncluded() {
		// Owner signs an attenuation for itself → forms the ceiling.
		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(
			Vectors.of(ownerToken(ALICE_KP, caps(ALICE_DID + "/w/health", Capability.CRUD))));
		AVector<ACell> derived = CapabilityChecker.selfCapabilities(
			proofs, ALICE_DID, ALICE_DID, System.currentTimeMillis() / 1000);
		assertNotNull(derived);
		assertEquals(1L, derived.count());
	}

	@Test
	public void testSelfCapabilitiesNonOwnerIssuerExcluded() {
		// Venue-signed (iss == venue, not the owner) is not the owner's own
		// authority → no self-ceiling. The owner is the authority over its namespace.
		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(
			Vectors.of(venueToken(ALICE_DID, caps(ALICE_DID + "/w/health", Capability.CRUD))));
		assertNull(CapabilityChecker.selfCapabilities(
			proofs, ALICE_DID, ALICE_DID, System.currentTimeMillis() / 1000));
	}

	@Test
	public void testAttenuatedTokenRestrictsInvoke() {
		// Owner-authored, scoped to its own w/health only.
		AString jwt = ownerToken(ALICE_KP, caps(ALICE_DID + "/w/health", Capability.CRUD));
		RequestContext rctx = AuthMiddleware.withTransportAuth(
			RequestContext.of(ALICE_DID), jwt, null);
		assertNotNull(rctx.getCaps(), "an owner-authored attenuation must set a ceiling");

		// In scope: own w/health write allowed (resource canonicalises to
		// <ALICE_DID>/w/health/bp, covered by the <ALICE_DID>/w/health grant).
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/health/bp", Fields.VALUE, Strings.create("120/80")),
			rctx).awaitResult(5000);

		// Out of scope: own w/other write denied — the ceiling, deterministically.
		// enforceCaps throws synchronously from invokeOperation, so wrap that call.
		Throwable ex = assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/other/x", Fields.VALUE, Strings.create("nope")), rctx)
				.awaitResult(5000));
		assertTrue(messageChain(ex).contains("Capability denied"),
			"out-of-scope op must be denied: " + messageChain(ex));
	}

	@Test
	public void testNoTokenIsUnrestricted() {
		// No bearer/ucans → no ceiling → full authority over own namespace
		// (regression guard: existing token-less callers are unaffected).
		RequestContext rctx = AuthMiddleware.withTransportAuth(
			RequestContext.of(ALICE_DID), null, null);
		assertNull(rctx.getCaps());
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/other/free", Fields.VALUE, Strings.create("ok")),
			rctx).awaitResult(5000);  // no throw
	}

	private static String messageChain(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c.getMessage() != null) sb.append(c.getMessage()).append('\n');
		}
		return sb.toString();
	}

	// ========== ucan:issue ==========

	@Test
	public void testIssueToken() {
		long exp = (System.currentTimeMillis() / 1000) + 3600;

		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			ALICE);
		ACell result = job.awaitResult(5000);

		// ucan:issue returns {"token": "<jwt>"}
		AString jwt = RT.ensureString(RT.getIn(result, "token"));
		assertNotNull(jwt, "ucan:issue should return a JWT token");
		assertTrue(jwt.toString().contains("."), "Token should be a JWT with dot-separated parts");

		// Validate the JWT round-trips correctly
		UCAN parsed = UCAN.fromJWT(jwt);
		assertNotNull(parsed, "JWT should parse as a valid UCAN");
		assertEquals(venueDID, parsed.getIssuer());
		assertEquals(BOB_DID, parsed.getAudience());
	}

	@Test
	public void testIssueRejectsOtherUserNamespace() {
		// Alice cannot issue a token for Bob's namespace
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(BOB_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	// ========== Cross-user read with proof ==========

	/**
	 * covia#196: a SELF-SOVEREIGN grant — the resource owner signs the root
	 * directly, the venue is NOT the issuer — authorises a cross-user read.
	 * This is the covia#100 enabler: cross-venue tokens rooted by the owner
	 * verify without naming the verifying venue.
	 */
	@Test
	public void testSelfSovereignWorkspaceGrant() {
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		UCAN token = UCAN.create(ALICE_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token.toMap()));
		assertEquals(Strings.create("shared content"),
			RT.getIn(readJob.awaitResult(5000), "value"));
	}

	/**
	 * covia#196: a root signed by a third party — neither the resource owner
	 * (self-sovereign) nor the venue (custodial) — is refused. This is the
	 * root-authority check the migration added: without it, anyone could mint
	 * grants over anyone's resources.
	 */
	@Test
	public void testThirdPartyRootDenied() {
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		// Carol signs a "grant" over ALICE's workspace, audienced to Bob.
		UCAN token = UCAN.create(CAROL_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token.toMap()));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000),
			"a third-party root must not authorise access to Alice's resources");
	}

	@Test
	public void testCrossUserReadWithValidProof() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/read", 3600);

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		ACell result = readJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("shared content"), RT.getIn(result, "value"));
	}

	/**
	 * covia#102: reading a job is the same right as reading any {@code j/} path,
	 * and is delegable. A {@code crud/read} grant on the owner's {@code /j/}
	 * lets the grantee read the job via BOTH {@code getJobData} (the job path)
	 * and {@code covia:read did:<owner>/j/<id>} (the lattice path) — one shared
	 * check. But a read grant does NOT authorise a job mutation.
	 */
	@Test
	public void testCrossUserJobReadWithProofButNotMutation() {
		// Alice runs a never-completing job → active, owned by Alice.
		covia.grid.Job aliceJob = engine.jobs().invokeOperation(
			Strings.create("v/test/ops/never"), Maps.empty(), ALICE);
		convex.core.data.Blob jobId = aliceJob.getID();

		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/j/", "crud/read", 3600);

		// Path 1 — the job path (getJobData, as grid:jobStatus/GET /jobs/{id} use).
		AMap<AString, ACell> viaJobData = engine.jobs().getJobData(jobId, withProofs(BOB, token));
		assertNotNull(viaJobData, "a crud/read grant on /j/ authorises reading the job");
		assertEquals(ALICE_DID, RT.ensureString(viaJobData.get(Fields.CALLER)));

		// Path 2 — the lattice path (covia:read). Same right, same check.
		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID.append("/j/" + jobId.toHexString())),
			withProofs(BOB, token));
		assertEquals(CVMBool.TRUE, RT.getIn(readJob.awaitResult(5000), "exists"));

		// Without a proof: denied (fail-closed).
		assertThrows(Exception.class, () -> engine.jobs().getJobData(jobId, BOB));

		// A read grant is not a write grant: mutation stays owner-only.
		assertThrows(Exception.class, () -> engine.jobs().cancelJob(jobId, withProofs(BOB, token)));

		engine.jobs().cancelJob(jobId); // cleanup the never-job
	}

	@Test
	public void testCrossUserReadSubpathAttenuation() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/shared/", "crud/read", 3600);

		// Bob can read /w/shared/doc
		Job readShared = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertEquals(Strings.create("shared content"),
			RT.getIn(readShared.awaitResult(5000), "value"));

		// Bob cannot read /w/private/secret (path not covered)
		Job readPrivate = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/private/secret"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readPrivate.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedWithoutProof() {
		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			BOB);
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedExpiredToken() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/read", -3600);

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedWrongAudience() {
		// Token issued to Carol, but Bob presents it
		AMap<AString, ACell> token = issueToken(CAROL_DID, ALICE_DID, "/w/", "crud/read", 3600);

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadWildcardAbility() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "*", 3600);

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertEquals(Strings.create("shared content"),
			RT.getIn(readJob.awaitResult(5000), "value"));
	}

	@Test
	public void testCrossUserListWithProof() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/read", 3600);

		Job listJob = engine.jobs().invokeOperation("v/ops/covia/list",
			Maps.of(Fields.PATH, ALICE_DID + "/w"),
			withProofs(BOB, token));
		ACell result = listJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
	}

	// ========== JWT transport round-trip (regression for #87) ==========

	/**
	 * Regression for covia#87: a token issued by {@code ucan:issue} (which
	 * returns a JWT string), presented through the transport path via
	 * {@link UCANValidator#parseTransportUCANs}, must authorise the
	 * corresponding cross-user read.
	 *
	 * <p>Prior to the fix this always returned "Access denied" because
	 * {@code CoviaAdapter.verifyProofs} re-ran {@code UCAN.verifySignature()}
	 * — which verifies the stored signature against CVM-encoded payload
	 * bytes — on a token whose signature actually covers base64url JWT
	 * bytes. The redundant re-check has been removed; signatures are
	 * verified once at {@code parseTransportUCANs} and trusted from there.</p>
	 */
	@Test
	public void testCrossUserReadViaJWTTransport() {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		Job issueJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			ALICE);
		AString jwt = RT.ensureString(RT.getIn(issueJob.awaitResult(5000), "token"));
		assertNotNull(jwt);

		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(Vectors.of(jwt));
		assertNotNull(proofs, "Valid JWT should verify at transport ingress");
		assertEquals(1L, proofs.count());

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			BOB.withProofs(proofs));
		ACell result = readJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("shared content"), RT.getIn(result, "value"));
	}

	/**
	 * Tampered JWT signatures must be rejected at the transport trust
	 * boundary — they must never reach {@code RequestContext.proofs}.
	 */
	@Test
	public void testTamperedJWTRejectedAtIngress() {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		Job issueJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			ALICE);
		String jwt = RT.ensureString(RT.getIn(issueJob.awaitResult(5000), "token")).toString();

		// Flip a character in the signature segment (last dot-separated part)
		int lastDot = jwt.lastIndexOf('.');
		char c = jwt.charAt(lastDot + 1);
		char flipped = (c == 'A') ? 'B' : 'A';
		String tampered = jwt.substring(0, lastDot + 1) + flipped + jwt.substring(lastDot + 2);

		AVector<ACell> proofs = UCANValidator.parseTransportUCANs(
			Vectors.of(Strings.create(tampered)));
		assertNull(proofs, "Tampered JWT must not produce a verified proof");
	}

	// ========== Adversarial ==========

	@Test
	public void testForgedSignatureDenied() {
		// Create a token signed with a random key (not the venue)
		AKeyPair fakeKP = AKeyPair.generate();
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN forged = UCAN.create(fakeKP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, forged.toMap()));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testWrongAbilityDenied() {
		// Token grants crud/write but request needs crud/read
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/write", 3600);

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	// ========== Helper ==========

	/**
	 * Issues a venue-signed UCAN token for testing.
	 * The venue DID is the issuer (resource owner for all hosted data).
	 */
	/**
	 * Issues a venue-signed UCAN token. The 'with' is a full DID URL.
	 */
	private AMap<AString, ACell> issueToken(AString audience, AString ownerDID, String path, String ability, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		String withURI = ownerDID.toString() + path;
		UCAN token = UCAN.create(
			venueKP,
			UCAN.fromDIDKey(audience),
			exp,
			Vectors.of(Capability.create(Strings.create(withURI), Strings.create(ability))),
			Vectors.empty());
		return token.toMap();
	}

	/**
	 * Creates a RequestContext with proofs attached.
	 */
	private static RequestContext withProofs(RequestContext base, AMap<AString, ACell>... tokens) {
		AVector<ACell> proofs = Vectors.empty();
		for (var t : tokens) proofs = proofs.conj(t);
		return base.withProofs(proofs);
	}
}
