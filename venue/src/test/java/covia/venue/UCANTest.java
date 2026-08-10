package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
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

	private static final Engine engine;
	static {
		engine = Engine.createTemp(Maps.of(
			Config.HOSTNAME, Strings.create("ucan.test.covia.example"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
	}
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
	private AString CUSTODIAL_DID;
	private RequestContext ALICE;
	private RequestContext BOB;
	private RequestContext CUSTODIAL;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_KP = AKeyPair.generate();
		BOB_KP = AKeyPair.generate();
		CAROL_KP = AKeyPair.generate();
		ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
		BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
		CAROL_DID = UCAN.toDIDKey(CAROL_KP.getAccountKey());
		String method = info.getTestMethod().map(m -> m.getName()).orElse("unknown");
		CUSTODIAL_DID = engine.managedUserDID(Strings.create("ucan-" + method));
		engine.getVenueState().users().ensure(CUSTODIAL_DID);
		ALICE = RequestContext.of(ALICE_DID);
		BOB = RequestContext.of(BOB_DID);
		CUSTODIAL = RequestContext.of(CUSTODIAL_DID);

		// Alice writes some workspace data
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("shared content")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/private/secret", Fields.VALUE, Strings.create("private content")),
			ALICE).awaitResult(5000);
	}

	private static final long HOUR = 3600;

	@AfterAll
	static void closeEngine() {
		engine.close();
	}

	@Test
	public void testNoTokenIsUnrestricted() {
		// No bearer/ucans → no scope → full authority over own namespace.
		// Presented proofs are additive grants, never subtractive: to act with
		// reduced authority, use a narrower Authority (Authority.of(did, grants))
		// or present only the UCANs the request needs.
		RequestContext rctx = AuthMiddleware.withTransportAuth(
			RequestContext.of(ALICE_DID), null, null);
		assertNull(rctx.getCaps());
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/other/free", Fields.VALUE, Strings.create("ok")),
			rctx).awaitResult(5000);  // no throw
	}

	// ========== ucan:issue ==========

	@Test
	public void testIssueToken() {
		long exp = (System.currentTimeMillis() / 1000) + 3600;

		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			CUSTODIAL);
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
	public void testIssueExplicitNullExpiryMintsNonExpiringToken() {
		long before = System.currentTimeMillis() / 1000;
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, null),
			CUSTODIAL);

		AString jwt = RT.ensureString(RT.getIn(job.awaitResult(5000), "token"));
		UCAN parsed = UCAN.fromJWT(jwt);
		assertNotNull(parsed);
		assertNull(parsed.getExpiry(),
			"exp:null must mint a genuinely non-expiring token (Convex #678, covia#322)");
		assertNotNull(UCANValidator.validateJWT(jwt, before),
			"a non-expiring token must validate now");
		assertNotNull(UCANValidator.validateJWT(jwt, Long.MAX_VALUE - 1),
			"a non-expiring token must validate at any future horizon");
	}

	@Test
	public void testIssueMissingExpiryDefaultsToNoExpiry() {
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ))),
			CUSTODIAL);
		AString jwt = RT.ensureString(RT.getIn(job.awaitResult(5000), "token"));
		UCAN parsed = UCAN.fromJWT(jwt);
		assertNotNull(parsed);
		assertNull(parsed.getExpiry(),
			"omitted API input mints the same explicit exp: null as an explicit null");
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
			CUSTODIAL);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testIssueRejectsVenueRootForSelfSovereignCaller() {
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			ALICE);
		Exception denied = assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(denied.getMessage().contains("must sign the UCAN with their own key"));
	}

	@Test
	public void testIssueRejectsVenueRootForExternalDidWebCaller() {
		AString external = Strings.create("did:web:identity.example:u:eve");
		engine.getVenueState().users().ensure(external);
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(external + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			RequestContext.of(external));
		Exception denied = assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(denied.getMessage().contains("not controlled by this venue"));
	}

	@Test
	public void testGrantingProofCannotTurnVenueIntoSelfSovereignRoot() {
		long now = System.currentTimeMillis() / 1000;
		UCAN ownerGrantingRight = UCAN.create(ALICE_KP, UCAN.fromDIDKey(BOB_DID), now + 2 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/w/"), Strings.create("grant/crud/read"))),
			Vectors.empty());

		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, CAROL_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(BOB, ownerGrantingRight.toMap()));

		Exception denied = assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(denied.getMessage().contains("must sign the UCAN with their own key"),
			"even a valid granting proof cannot make the venue a root for a self-sovereign DID");
	}

	@Test
	public void testManagedDidWebCanBeAudienceWithoutAUserKey() {
		AString audience = engine.managedUserDID(Strings.create("ucan-audience"));
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, audience,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			CUSTODIAL);

		UCAN token = UCAN.fromJWT(RT.ensureString(RT.getIn(job.awaitResult(5000), "token")));
		assertEquals(audience, token.getAudience());
		assertEquals(venueDID, token.getIssuer(),
			"the venue signs for custodial users; the audience needs no independent key");
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
	public void testIssueCanonicalisesBareWith() {
		// Custodial issuance: the venue signs (iss = venue), so a bare path from
		// the caller MUST be absolutised to the CALLER's DID before signing —
		// stored-bare would mean the venue's own namespace, not the caller's.
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create("w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			CUSTODIAL);
		AString jwt = RT.ensureString(RT.getIn(job.awaitResult(5000), "token"));
		UCAN parsed = UCAN.fromJWT(jwt);
		assertEquals(Strings.create(CUSTODIAL_DID + "/w/"),
			RT.getIn(parsed.getCapabilities().get(0), Capability.WITH),
			"a bare with must be canonicalised to the issuer-principal's namespace");

		// An empty with would silently grant the whole namespace — rejected;
		// whole-namespace grants must be written explicitly.
		Job emptyJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(Strings.create(""), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			CUSTODIAL);
		assertThrows(Exception.class, () -> emptyJob.awaitResult(5000));

		// Scheme forms have no DID owner — not issuable; use the path form.
		Job schemeJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(Strings.create("dlfs://docs/x"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			CUSTODIAL);
		assertThrows(Exception.class, () -> schemeJob.awaitResult(5000));
	}

	@Test
	public void testBareWithBindsToIssuer() {
		// Self-sovereign: a bare `with` means THE TOKEN ISSUER's own namespace,
		// resolved at evaluation against the signed iss. Alice signing bare "w/"
		// grants Alice's workspace — nothing else.
		long exp = (System.currentTimeMillis() / 1000) + HOUR;
		UCAN token = UCAN.create(ALICE_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create("w/"), Capability.CRUD_READ)),
			Vectors.empty());

		// Positive: Bob reads Alice's doc with Alice's bare-with grant.
		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token.toMap()));
		assertEquals(Strings.create("shared content"),
			RT.getIn(readJob.awaitResult(5000), "value"));

		// Negative: the SAME token must not reach any other principal's
		// namespace — bare binds to iss, never the presenter or the target.
		Job carolJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, CAROL_DID + "/w/shared/doc"),
			withProofs(BOB, token.toMap()));
		assertThrows(Exception.class, () -> carolJob.awaitResult(5000));

		// Negative: a third party signing a bare with grants only their OWN
		// namespace — it must not canonicalise against the target's.
		UCAN carolToken = UCAN.create(CAROL_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create("w/"), Capability.CRUD_READ)),
			Vectors.empty());
		Job aliceViaCarol = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, carolToken.toMap()));
		assertThrows(Exception.class, () -> aliceViaCarol.awaitResult(5000),
			"a bare-with grant binds to its issuer, not to the requested target");
	}

	// ========== Granting rights at issuance (COG-17) ==========
	//
	// grant/X binds token PRODUCTION: the issuance surface mints over another
	// principal's resource only when the caller's presented proofs cover
	// grant/<can> on it. Verification stays grant-agnostic.

	/** Steward mints a read grant under a held granting right, end-to-end. */
	@Test
	public void testIssueWithGrantingRight() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("carol content")),
			CUSTODIAL).awaitResult(5000);

		long now = System.currentTimeMillis() / 1000;
		UCAN grantRight = UCAN.create(venueKP, UCAN.fromDIDKey(ALICE_DID), now + 2 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/crud/read"))),
			Vectors.empty());

		// Alice mints for Bob, inside the granting right's validity window.
		Job issueJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/shared/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, grantRight.toMap()));
		AString jwt = RT.ensureString(RT.getIn(issueJob.awaitResult(5000), "token"));
		assertNotNull(jwt, "a held granting right must let the surface mint");

		// End-to-end: Bob reads Carol's doc with the minted, chain-free token.
		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, CUSTODIAL_DID + "/w/shared/doc"),
			withProofs(BOB, UCAN.fromJWT(jwt).toMap()));
		assertEquals(Strings.create("carol content"),
			RT.getIn(readJob.awaitResult(5000), "value"));
	}

	/** ADVERSARIAL: a USE right is not a granting right — holding crud/read
	 *  does not let the surface mint crud/read for someone else. */
	@Test
	public void testIssueWithUseRightOnlyDenied() {
		long now = System.currentTimeMillis() / 1000;
		UCAN useRight = UCAN.create(venueKP, UCAN.fromDIDKey(ALICE_DID), now + 2 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/shared/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, useRight.toMap()));
		assertThrows(Exception.class, () -> job.awaitResult(5000),
			"holding crud/read must not mint — grant/crud/read is required");
	}

	/** ADVERSARIAL: the granting right's resource scope binds — a right over
	 *  w/shared/ cannot mint over w/private/. */
	@Test
	public void testIssueGrantingRightWrongResourceDenied() {
		long now = System.currentTimeMillis() / 1000;
		UCAN grantRight = UCAN.create(venueKP, UCAN.fromDIDKey(ALICE_DID), now + 2 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/shared/"), Strings.create("grant/crud/read"))),
			Vectors.empty());
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/private/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, grantRight.toMap()));
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	/** ADVERSARIAL: grant/crud/read mints exactly crud/read — not crud/write,
	 *  and not another grant/crud/read (stewardship re-grant needs grant/grant/…). */
	@Test
	public void testIssueGrantingRightWrongAbilityDenied() {
		long now = System.currentTimeMillis() / 1000;
		UCAN grantRight = UCAN.create(venueKP, UCAN.fromDIDKey(ALICE_DID), now + 2 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/crud/read"))),
			Vectors.empty());

		Job writeJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_WRITE)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, grantRight.toMap()));
		assertThrows(Exception.class, () -> writeJob.awaitResult(5000),
			"grant/crud/read must not mint crud/write");

		Job regrantJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/crud/read"))),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, grantRight.toMap()));
		assertThrows(Exception.class, () -> regrantJob.awaitResult(5000),
			"re-granting the granting right requires grant/grant/crud/read");
	}

	/** ADVERSARIAL: minted authority must not outlive the granting right that
	 *  enables it — an exp beyond the right's validity is refused. */
	@Test
	public void testIssueOutlivingGrantingRightDenied() {
		long now = System.currentTimeMillis() / 1000;
		UCAN grantRight = UCAN.create(venueKP, UCAN.fromDIDKey(ALICE_DID), now + HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/crud/read"))),
			Vectors.empty());

		Job longJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + 2 * HOUR)),
			withProofs(ALICE, grantRight.toMap()));
		assertThrows(Exception.class, () -> longJob.awaitResult(5000),
			"minted exp beyond the granting right's validity must be refused");

		Job shortJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR / 2)),
			withProofs(ALICE, grantRight.toMap()));
		assertNotNull(RT.getIn(shortJob.awaitResult(5000), "token"),
			"an exp inside the granting right's validity must mint");
	}

	/** ADVERSARIAL: a "granting right" signed by a non-owner third party roots
	 *  no authority — the surface must refuse to mint under it. */
	@Test
	public void testIssueThirdPartyGrantingRightDenied() {
		long now = System.currentTimeMillis() / 1000;
		// Bob (who does not control the managed namespace) "grants" Alice a granting right over it.
		UCAN rogue = UCAN.create(BOB_KP, UCAN.fromDIDKey(ALICE_DID), now + 2 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/crud/read"))),
			Vectors.empty());
		Job job = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, rogue.toMap()));
		assertThrows(Exception.class, () -> job.awaitResult(5000),
			"a third-party granting right must root nothing");
	}

	/** Recursion: grant/grant/crud/read appoints a steward (mints grant/crud/read),
	 *  who can then mint reads — but the appointer cannot mint reads directly. */
	@Test
	public void testIssueRecursiveGrantingRight() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("carol content")),
			CUSTODIAL).awaitResult(5000);

		long now = System.currentTimeMillis() / 1000;
		UCAN metaRight = UCAN.create(venueKP, UCAN.fromDIDKey(ALICE_DID), now + 3 * HOUR,
			Vectors.of(Capability.create(
				Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/grant/crud/read"))),
			Vectors.empty());

		// grant/grant/crud/read does NOT cover grant/crud/read — no direct use-grants.
		Job directJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(ALICE, metaRight.toMap()));
		assertThrows(Exception.class, () -> directJob.awaitResult(5000),
			"grant/grant/crud/read must not mint crud/read directly");

		// Appoint Bob as steward: mint grant/crud/read for him.
		Job stewardJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/"), Strings.create("grant/crud/read"))),
				UCAN.EXP, CVMLong.create(now + 2 * HOUR)),
			withProofs(ALICE, metaRight.toMap()));
		AString stewardJwt = RT.ensureString(RT.getIn(stewardJob.awaitResult(5000), "token"));
		assertNotNull(stewardJwt, "the meta-right must appoint a steward");

		// The steward mints a read for Dave, who then reads Carol's doc.
		AKeyPair DAVE_KP = AKeyPair.generate();
		AString DAVE_DID = UCAN.toDIDKey(DAVE_KP.getAccountKey());
		Job daveGrantJob = engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, DAVE_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(CUSTODIAL_DID + "/w/shared/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(now + HOUR)),
			withProofs(BOB, UCAN.fromJWT(stewardJwt).toMap()));
		AString daveJwt = RT.ensureString(RT.getIn(daveGrantJob.awaitResult(5000), "token"));

		Job readJob = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, CUSTODIAL_DID + "/w/shared/doc"),
			withProofs(RequestContext.of(DAVE_DID), UCAN.fromJWT(daveJwt).toMap()));
		assertEquals(Strings.create("carol content"),
			RT.getIn(readJob.awaitResult(5000), "value"));
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
	public void testCrossUserReadEnforcesGateFromDelegationRoot() {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AString resource = Strings.create(ALICE_DID + "/w/");

		UCAN deniedRoot = UCAN.create(ALICE_KP, CAROL_KP.getAccountKey(), exp,
			Vectors.of(Capability.create(resource, Capability.CRUD,
				Maps.of("gate", "v/test/ops/denygate"))),
			Vectors.empty());
		UCAN deniedLeaf = UCAN.create(CAROL_KP, BOB_KP.getAccountKey(), exp,
			Vectors.of(Capability.create(resource, Capability.CRUD_READ)),
			Vectors.of(deniedRoot.toMap()));

		Job denied = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, deniedLeaf.toMap()));
		assertThrows(Exception.class, () -> denied.awaitResult(5000),
			"a leaf delegation must not drop its parent's denying gate");

		UCAN allowedRoot = UCAN.create(ALICE_KP, CAROL_KP.getAccountKey(), exp,
			Vectors.of(Capability.create(resource, Capability.CRUD,
				Maps.of("gate", "v/test/ops/allowgate"))),
			Vectors.empty());
		UCAN allowedLeaf = UCAN.create(CAROL_KP, BOB_KP.getAccountKey(), exp,
			Vectors.of(Capability.create(resource, Capability.CRUD_READ)),
			Vectors.of(allowedRoot.toMap()));

		Job allowed = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, allowedLeaf.toMap()));
		assertEquals(Strings.create("shared content"),
			RT.getIn(allowed.awaitResult(5000), "value"),
			"a passing root gate permits the attenuated leaf grant");
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
		AString jwt = UCAN.createJWT(ALICE_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
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
		String jwt = UCAN.createJWT(ALICE_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/w/"), Capability.CRUD_READ)),
			Vectors.empty()).toString();

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

	/** Issues an owner-signed UCAN token. The 'with' is a full DID URL. */
	private AMap<AString, ACell> issueToken(AString audience, AString ownerDID, String path, String ability, long ttlSeconds) {
		assertEquals(ALICE_DID, ownerDID, "test helper only owns Alice's signing key");
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		String withURI = ownerDID.toString() + path;
		UCAN token = UCAN.create(
			ALICE_KP,
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
