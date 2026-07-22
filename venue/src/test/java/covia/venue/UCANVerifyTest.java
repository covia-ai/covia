package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.grid.auth.UcanTokens;

/**
 * Tests for {@code ucan:verify} — the diagnostic op that verifies a token
 * against the venue's trust policy and explains the verdict. Covers validity,
 * chain depth / root issuer, per-capability root-authority verdicts
 * (owner / venue / refused), the optional would-it-authorise check, and
 * diagnosable failure reasons. Also exercises the {@link UcanTokens}
 * client-side minting helpers end-to-end against a real engine.
 */
public class UCANVerifyTest {

	private static final Engine engine;
	static {
		engine = Engine.createTemp(Maps.of(
			Config.HOSTNAME, Strings.create("verify.test.covia.example"),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
	}

	private AKeyPair ALICE_KP;
	private AKeyPair BOB_KP;
	private AString ALICE_DID;
	private AString BOB_DID;
	private RequestContext ALICE;

	@AfterAll
	static void closeEngine() {
		engine.close();
	}

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_KP = AKeyPair.generate();
		BOB_KP = AKeyPair.generate();
		ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
		BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
		ALICE = RequestContext.of(ALICE_DID);
	}

	private ACell verify(ACell input) {
		return engine.jobs().invokeOperation("v/ops/ucan/verify", input, ALICE).awaitResult(5000);
	}

	// ========== Valid tokens ==========

	@Test
	public void testOwnerSignedGrantVerifiesAsOwnerRooted() {
		// Minted with the client-side helper — exercises UcanTokens.grant too.
		String jwt = UcanTokens.grant(ALICE_KP, BOB_DID.toString(),
			ALICE_DID + "/w/shared/", "crud/read", 3600);

		ACell r = verify(Maps.of("token", jwt));
		assertEquals(CVMBool.TRUE, RT.getIn(r, "valid"));
		assertEquals(ALICE_DID, RT.getIn(r, "iss"));
		assertEquals(BOB_DID, RT.getIn(r, "aud"));
		assertEquals(ALICE_DID, RT.getIn(r, "rootIssuer"));
		assertEquals(0L, RT.ensureLong(RT.getIn(r, "chainDepth")).longValue());
		AVector<?> att = RT.ensureVector(RT.getIn(r, "att"));
		assertEquals(Strings.create("owner"), RT.getIn(att.get(0), "rootAuthority"));
	}

	@Test
	public void testVenueIssuedTokenVerifiesAsVenueRooted() {
		// Issue via the venue op, then verify the returned JWT.
		AString managed = engine.managedUserDID(Strings.create("verify-custodial"));
		engine.getVenueState().users().ensure(managed);
		ACell issued = engine.jobs().invokeOperation("v/ops/ucan/issue", Maps.of(
			UCAN.AUD, BOB_DID,
			UCAN.ATT, Vectors.of(Capability.create(
				Strings.create(managed + "/w/"), Capability.CRUD_READ)),
			UCAN.EXP, (System.currentTimeMillis() / 1000) + 3600),
			RequestContext.of(managed)).awaitResult(5000);
		AString jwt = RT.ensureString(RT.getIn(issued, "token"));
		assertNotNull(jwt);

		ACell r = verify(Maps.of("token", jwt));
		assertEquals(CVMBool.TRUE, RT.getIn(r, "valid"));
		assertEquals(engine.getDIDString(), RT.getIn(r, "rootIssuer"));
		AVector<?> att = RT.ensureVector(RT.getIn(r, "att"));
		assertEquals(Strings.create("venue"), RT.getIn(att.get(0), "rootAuthority"));
	}

	@Test
	public void testThirdPartyRootIsRefused() {
		// Bob signs a "grant" over ALICE's namespace — valid signature, but
		// Bob is neither the owner nor the venue: the capability is refused.
		String jwt = UcanTokens.grant(BOB_KP, ALICE_DID.toString(),
			ALICE_DID + "/w/", "crud/read", 3600);

		ACell r = verify(Maps.of("token", jwt));
		assertEquals(CVMBool.TRUE, RT.getIn(r, "valid"), "signature IS valid");
		AVector<?> att = RT.ensureVector(RT.getIn(r, "att"));
		assertEquals(Strings.create("refused"), RT.getIn(att.get(0), "rootAuthority"),
			"a third-party root must be refused even though the signature verifies");
	}

	@Test
	public void testChainDepthAndRoot() {
		// Alice → Bob (root), Bob → Carol (leaf, narrowed). In JWT transport the
		// prf entries are the parents' JWT strings (validateJWT recurses on them).
		AKeyPair carolKP = AKeyPair.generate();
		AString carolDID = UCAN.toDIDKey(carolKP.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		AString rootJWT = UCAN.createJWT(ALICE_KP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(Strings.create(ALICE_DID + "/w/"), Capability.CRUD)),
			Vectors.empty());
		AString leafJWT = UCAN.createJWT(BOB_KP, UCAN.fromDIDKey(carolDID), exp,
			Vectors.of(Capability.create(Strings.create(ALICE_DID + "/w/shared/"), Capability.CRUD_READ)),
			Vectors.of(rootJWT));

		ACell r = verify(Maps.of("token", leafJWT));
		assertEquals(CVMBool.TRUE, RT.getIn(r, "valid"));
		assertEquals(1L, RT.ensureLong(RT.getIn(r, "chainDepth")).longValue());
		assertEquals(ALICE_DID, RT.getIn(r, "rootIssuer"), "root of the chain is Alice");
		AVector<?> att = RT.ensureVector(RT.getIn(r, "att"));
		assertEquals(Strings.create("owner"), RT.getIn(att.get(0), "rootAuthority"));
	}

	// ========== Would-it-authorise ==========

	@Test
	public void testAuthorisesCheck() {
		String jwt = UcanTokens.grant(ALICE_KP, BOB_DID.toString(),
			ALICE_DID + "/w/shared/", "crud/read", 3600);

		// Covered request for the right audience → authorises.
		ACell yes = verify(Maps.of(
			"token", jwt,
			"with", ALICE_DID + "/w/shared/doc", "can", "crud/read",
			"aud", BOB_DID));
		assertEquals(CVMBool.TRUE, RT.getIn(yes, "authorises"));

		// Uncovered sibling → does not authorise.
		ACell no = verify(Maps.of(
			"token", jwt,
			"with", ALICE_DID + "/w/private/doc", "can", "crud/read",
			"aud", BOB_DID));
		assertEquals(CVMBool.FALSE, RT.getIn(no, "authorises"));

		// Right resource, wrong audience (defaults to caller = Alice, not Bob).
		ACell wrongAud = verify(Maps.of(
			"token", jwt,
			"with", ALICE_DID + "/w/shared/doc", "can", "crud/read"));
		assertEquals(CVMBool.FALSE, RT.getIn(wrongAud, "authorises"));
	}

	// ========== Diagnosable failures ==========

	@Test
	public void testExpiredTokenExplained() {
		String jwt = UcanTokens.grant(ALICE_KP, BOB_DID.toString(),
			ALICE_DID + "/w/", "crud/read", -3600);
		ACell r = verify(Maps.of("token", jwt));
		assertEquals(CVMBool.FALSE, RT.getIn(r, "valid"));
		assertTrue(RT.ensureString(RT.getIn(r, "reason")).toString().contains("expired"),
			"reason should name expiry: " + RT.getIn(r, "reason"));
	}

	@Test
	public void testGarbageTokenExplained() {
		ACell r = verify(Maps.of("token", "not-a-jwt-at-all"));
		assertEquals(CVMBool.FALSE, RT.getIn(r, "valid"));
		assertTrue(RT.ensureString(RT.getIn(r, "reason")).toString().contains("unparseable"),
			"reason should say unparseable: " + RT.getIn(r, "reason"));
	}

	// ========== UcanTokens helpers (shape) ==========

	@Test
	public void testIdentityTokenShape() {
		String jwt = UcanTokens.identityToken(ALICE_KP, engine.getDIDString().toString(), 300);
		ACell r = verify(Maps.of("token", jwt));
		assertEquals(CVMBool.TRUE, RT.getIn(r, "valid"));
		assertEquals(ALICE_DID, RT.getIn(r, "iss"));
		assertEquals(engine.getDIDString(), RT.getIn(r, "aud"), "audienced to the venue");
		assertEquals(0L, RT.ensureVector(RT.getIn(r, "att")).count(), "empty att — pure identity");
	}

	@Test
	public void testRelayDelegationShape() {
		String jwt = UcanTokens.relayDelegation(ALICE_KP, engine.getDIDString().toString(), 300,
			ALICE_DID + "/w/", "crud/read");
		ACell r = verify(Maps.of("token", jwt));
		assertEquals(CVMBool.TRUE, RT.getIn(r, "valid"));
		AVector<?> att = RT.ensureVector(RT.getIn(r, "att"));
		assertEquals(2L, att.count(), "venue/relay instruction + the substantive grant");
		assertEquals(Strings.create(UcanTokens.VENUE_RELAY), RT.getIn(att.get(0), "can"));
	}
}
