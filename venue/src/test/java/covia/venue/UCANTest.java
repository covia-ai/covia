package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
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

/**
 * Tests for the UCAN capability flow: issue tokens, present as proofs,
 * verify cross-user access.
 */
public class UCANTest {

	private Engine engine;
	private AKeyPair venueKP;
	private AString venueDID;

	// Real key pairs so DIDs are valid multikey-encoded
	private static final AKeyPair ALICE_KP = AKeyPair.generate();
	private static final AKeyPair BOB_KP = AKeyPair.generate();
	private static final AKeyPair CAROL_KP = AKeyPair.generate();
	private static final AString ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
	private static final AString BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
	private static final AString CAROL_DID = UCAN.toDIDKey(CAROL_KP.getAccountKey());
	private static final RequestContext ALICE = RequestContext.of(ALICE_DID);
	private static final RequestContext BOB = RequestContext.of(BOB_DID);

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
		venueKP = engine.getKeyPair();
		venueDID = engine.getDIDString();

		// Alice writes some workspace data
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/shared/doc", Fields.VALUE, Strings.create("shared content")),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/private/secret", Fields.VALUE, Strings.create("private content")),
			ALICE).awaitResult(5000);
	}

	// ========== ucan:issue ==========

	@Test
	public void testIssueToken() {
		long exp = (System.currentTimeMillis() / 1000) + 3600;

		Job job = engine.jobs().invokeOperation("ucan:issue",
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
		Job job = engine.jobs().invokeOperation("ucan:issue",
			Maps.of(
				UCAN.AUD, BOB_DID,
				UCAN.ATT, Vectors.of(Capability.create(
					Strings.create(BOB_DID + "/w/"), Capability.CRUD_READ)),
				UCAN.EXP, CVMLong.create(exp)),
			ALICE);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
	}

	// ========== Cross-user read with proof ==========

	@Test
	public void testCrossUserReadWithValidProof() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/read", 3600);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		ACell result = readJob.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("shared content"), RT.getIn(result, "value"));
	}

	@Test
	public void testCrossUserReadSubpathAttenuation() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/shared/", "crud/read", 3600);

		// Bob can read /w/shared/doc
		Job readShared = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertEquals(Strings.create("shared content"),
			RT.getIn(readShared.awaitResult(5000), "value"));

		// Bob cannot read /w/private/secret (path not covered)
		Job readPrivate = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/private/secret"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readPrivate.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedWithoutProof() {
		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			BOB);
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedExpiredToken() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/read", -3600);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedWrongAudience() {
		// Token issued to Carol, but Bob presents it
		AMap<AString, ACell> token = issueToken(CAROL_DID, ALICE_DID, "/w/", "crud/read", 3600);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadWildcardAbility() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "*", 3600);

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, token));
		assertEquals(Strings.create("shared content"),
			RT.getIn(readJob.awaitResult(5000), "value"));
	}

	@Test
	public void testCrossUserListWithProof() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/read", 3600);

		Job listJob = engine.jobs().invokeOperation("covia:list",
			Maps.of(Fields.PATH, ALICE_DID + "/w"),
			withProofs(BOB, token));
		ACell result = listJob.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("Map"), RT.getIn(result, "type"));
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

		Job readJob = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, ALICE_DID + "/w/shared/doc"),
			withProofs(BOB, forged.toMap()));
		assertThrows(Exception.class, () -> readJob.awaitResult(5000));
	}

	@Test
	public void testWrongAbilityDenied() {
		// Token grants crud/write but request needs crud/read
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/w/", "crud/write", 3600);

		Job readJob = engine.jobs().invokeOperation("covia:read",
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
