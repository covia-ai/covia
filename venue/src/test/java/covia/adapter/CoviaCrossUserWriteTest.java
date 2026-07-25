package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * covia#295 — cross-user lattice CRUD writes via an owner-issued UCAN.
 *
 * <p>Symmetry check for {@code covia:write}/{@code append}/{@code delete}: a
 * caller holding a self-sovereign {@code crud/write} (or {@code crud/delete})
 * grant rooted in the resource owner may mutate the owner's {@code w/}
 * namespace via a DID-URL path ({@code did:key:zAlice/w/x}), exactly as the same
 * proof authorises a cross-user read. Before #295 the write half was
 * hard-rejected before any proof check ("Cross-user / DID-URL write paths are
 * not supported"); it now flows through the single proof-gated resolver reads
 * use, so a valid grant is exercisable and an absent one still fails closed.
 *
 * <p>This is the covia-adapter counterpart of {@link DLFSCrossUserTest}, which
 * already covered the DLFS drive path. Per-test key pairs give fresh Alice/Bob
 * DIDs so namespaces are isolated across methods on the shared engine.</p>
 */
public class CoviaCrossUserWriteTest {

	final Engine engine = TestEngine.ENGINE;
	private AKeyPair ALICE_KP;
	private AKeyPair BOB_KP;
	private AString ALICE_DID;
	private AString BOB_DID;
	private RequestContext ALICE;
	private RequestContext BOB;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_KP = AKeyPair.generate();
		BOB_KP = AKeyPair.generate();
		ALICE_DID = UCAN.toDIDKey(ALICE_KP.getAccountKey());
		BOB_DID = UCAN.toDIDKey(BOB_KP.getAccountKey());
		ALICE = RequestContext.of(ALICE_DID);
		BOB = RequestContext.of(BOB_DID);
	}

	// ========== Helpers (mirror DLFSCrossUserTest / UCANTest) ==========

	private ACell run(String op, ACell input, RequestContext ctx) {
		return engine.jobs().invokeOperation(op, input, ctx).awaitResult(5000);
	}

	/** A token the resource OWNER signs directly (root issuer == owner, empty prf)
	 *  — self-sovereign, the venue is NOT the issuer. {@code withURI} is the full
	 *  owner-scoped resource, e.g. {@code <ALICE_DID>/w/notes}. */
	private static AMap<AString, ACell> ownerToken(AKeyPair ownerKP, AString audience,
			String withURI, String ability, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		UCAN token = UCAN.create(ownerKP, UCAN.fromDIDKey(audience), exp,
			Vectors.of(Capability.create(Strings.create(withURI), Strings.create(ability))),
			Vectors.empty());
		return token.toMap();
	}

	@SafeVarargs
	private static RequestContext withProofs(RequestContext base, AMap<AString, ACell>... tokens) {
		AVector<ACell> proofs = Vectors.empty();
		for (var t : tokens) proofs = proofs.conj(t);
		return base.withProofs(proofs);
	}

	// ========== Cross-user write (authorised) ==========

	@Test
	public void testCrossUserWriteWithProof() {
		// Alice signs Bob a crud/write grant over her w/x; Bob writes there.
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/x", "crud/write", 3600);

		run("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/x", Fields.VALUE, Strings.create("from bob")),
			withProofs(BOB, grant));  // no throw

		// The write landed in Alice's namespace — Alice reads it back with a bare path.
		ACell read = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/x"), ALICE);
		assertEquals(CVMBool.TRUE, RT.getIn(read, "exists"));
		assertEquals(Strings.create("from bob"), RT.getIn(read, "value"));
	}

	@Test
	public void testCrossUserWriteToDeepPathWithProof() {
		// A grant over the top-level key covers a deeper write beneath it.
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/data", "crud/write", 3600);

		run("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/data/nested/field",
				Fields.VALUE, Strings.create("deep")),
			withProofs(BOB, grant));

		ACell read = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/data/nested/field"), ALICE);
		assertEquals(Strings.create("deep"), RT.getIn(read, "value"));
	}

	@Test
	public void testBroaderCrudGrantCoversWrite() {
		// A parent "crud" grant covers the narrower crud/write the op needs —
		// authorisation goes through Capability.covers, not exact can matching.
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/x", "crud", 3600);

		run("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/x", Fields.VALUE, Strings.create("covered by crud")),
			withProofs(BOB, grant));

		assertEquals(Strings.create("covered by crud"),
			RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/x"), ALICE), "value"));
	}

	// ========== Cross-user append (authorised) ==========

	@Test
	public void testCrossUserAppendWithProof() {
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/events", "crud/write", 3600);

		run("v/ops/covia/append",
			Maps.of(Fields.PATH, ALICE_DID + "/w/events", Fields.VALUE, Strings.create("ev1")),
			withProofs(BOB, grant));

		ACell read = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/events"), ALICE);
		assertEquals(CVMBool.TRUE, RT.getIn(read, "exists"));
		assertEquals(Vectors.of(Strings.create("ev1")), RT.getIn(read, "value"));
	}

	// ========== Cross-user delete (authorised) ==========

	@Test
	public void testCrossUserDeleteWithProof() {
		// Alice has a value; a crud/delete grant lets Bob remove it.
		run("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/target", Fields.VALUE, Strings.create("doomed")), ALICE);
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/target", "crud/delete", 3600);

		ACell del = run("v/ops/covia/delete",
			Maps.of(Fields.PATH, ALICE_DID + "/w/target"), withProofs(BOB, grant));
		assertEquals(CVMBool.TRUE, RT.getIn(del, "deleted"));

		// The value is gone from Alice's namespace.
		assertEquals(CVMBool.FALSE,
			RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/target"), ALICE), "exists"));
	}

	// ========== Fail-closed: missing / wrong / expired grant ==========

	@Test
	public void testCrossUserWriteDeniedWithoutProof() {
		// No proof at all — fails closed (the null-scope fast path must not authorise).
		Job write = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/x", Fields.VALUE, Strings.create("nope")), BOB);
		assertThrows(Exception.class, () -> write.awaitResult(5000));

		// Alice's namespace is untouched — the key was never created.
		assertEquals(CVMBool.FALSE,
			RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/x"), ALICE), "exists"),
			"unauthorised write must not create the value");
	}

	@Test
	public void testCrossUserWriteDeniedWithReadGrant() {
		// A read grant does not authorise a write.
		AMap<AString, ACell> readGrant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/x", "crud/read", 3600);
		Job write = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/x", Fields.VALUE, Strings.create("nope")),
			withProofs(BOB, readGrant));
		assertThrows(Exception.class, () -> write.awaitResult(5000));

		assertEquals(CVMBool.FALSE,
			RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/x"), ALICE), "exists"));
	}

	@Test
	public void testCrossUserDeleteDeniedWithReadGrant() {
		run("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/keep", Fields.VALUE, Strings.create("safe")), ALICE);
		AMap<AString, ACell> readGrant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/keep", "crud/read", 3600);

		Job del = engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, ALICE_DID + "/w/keep"), withProofs(BOB, readGrant));
		assertThrows(Exception.class, () -> del.awaitResult(5000));

		// Still there.
		assertEquals(Strings.create("safe"),
			RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/keep"), ALICE), "value"));
	}

	@Test
	public void testCrossUserWriteDeniedWithExpiredProof() {
		AMap<AString, ACell> expired = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/x", "crud/write", -3600);  // already expired
		Job write = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/x", Fields.VALUE, Strings.create("stale")),
			withProofs(BOB, expired));
		assertThrows(Exception.class, () -> write.awaitResult(5000));
	}

	@Test
	public void testCrossUserWriteDeniedWithWrongResourceProof() {
		// A crud/write grant over w/other does not authorise a write to w/x.
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/w/other", "crud/write", 3600);
		Job write = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/x", Fields.VALUE, Strings.create("nope")),
			withProofs(BOB, grant));
		assertThrows(Exception.class, () -> write.awaitResult(5000));
	}

	// ========== Cross-user asset (/a/) reads — private, NOT open-by-hash (covia#295) ==========

	@Test
	public void testCrossUserAssetReadDeniedWithoutProof() {
		// Alice stores a private asset in her own /a/ store.
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of("name", Strings.create("alice-secret"))), ALICE);
		AString assetPath = RT.ensureString(RT.getIn(stored, Fields.ID));  // did:alice/a/<hash>

		// Bob cannot read it by naming the DID URL: the hash is an ID, not a read
		// capability — knowing it does not entitle him to another user's asset. The
		// covia:read path reaches Alice's private store via the named DID, so this
		// MUST fail closed at the gate.
		Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, assetPath), BOB);
		assertThrows(Exception.class, () -> read.awaitResult(5000),
			"a cross-user /a/ read must be gated, not open by hash");
	}

	@Test
	public void testCrossUserAssetReadWithProof() {
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of("name", Strings.create("shared-asset"))), ALICE);
		AString assetPath = RT.ensureString(RT.getIn(stored, Fields.ID));

		// A crud/read grant from Alice over that asset authorises Bob's read —
		// delegated, exactly like a workspace read.
		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID, assetPath.toString(), "crud/read", 3600);
		ACell result = run("v/ops/covia/read", Maps.of(Fields.PATH, assetPath), withProofs(BOB, grant));
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("shared-asset"), RT.getIn(RT.getIn(result, "value"), "name"));
	}

	@Test
	public void testCrossUserPrivateOperationCannotBeInvokedWithoutAssetRead() {
		// Operation resolution is itself a read of Alice's private /a/ record.
		// The adapter's later invoke check must not be able to hide an
		// unauthorised definition lookup performed before dispatch.
		ACell stored = run("v/ops/asset/store", Maps.of(
			Fields.METADATA, Maps.of(
				Fields.NAME, Strings.create("alice-private-operation"),
				Fields.OPERATION, Maps.of(
					Fields.ADAPTER, Strings.create("json:select")))), ALICE);
		AString operation = RT.ensureString(RT.getIn(stored, Fields.ID));

		assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation(operation, Maps.of(
				"key", "answer",
				"cases", Maps.of("answer", "private")), BOB),
			"resolving another user's private operation requires asset/read");

		AMap<AString, ACell> readGrant = ownerToken(ALICE_KP, BOB_DID,
			operation.toString(), "asset/read", 3600);
		ACell result = run(operation.toString(), Maps.of(
			"key", "answer",
			"cases", Maps.of("answer", "shared")), withProofs(BOB, readGrant));
		assertEquals(Strings.create("shared"), RT.getIn(result, "result"),
			"an explicit asset/read delegation makes the definition invokable");
	}

	@Test
	public void testCopyChecksTheCrossUserSourceBeforeWritingLocally() {
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of(Fields.NAME, "alice-copy-source")), ALICE);
		AString source = RT.ensureString(RT.getIn(stored, Fields.ID));
		AMap<AString, ACell> copyInput = Maps.of(
			"from", source,
			"to", Strings.create("w/copied-from-alice"));

		Job denied = engine.jobs().invokeOperation("v/ops/covia/copy", copyInput, BOB);
		assertThrows(Exception.class, () -> denied.awaitResult(5000),
			"copy must not treat an unrestricted own scope as cross-user read authority");

		AMap<AString, ACell> readGrant = ownerToken(ALICE_KP, BOB_DID,
			source.toString(), "crud/read", 3600);
		run("v/ops/covia/copy", copyInput, withProofs(BOB, readGrant));
		ACell copied = run("v/ops/covia/read",
			Maps.of(Fields.PATH, "w/copied-from-alice"), BOB);
		assertEquals(Strings.create("alice-copy-source"),
			RT.getIn(copied, Fields.VALUE, Fields.NAME));
	}

	@Test
	public void testCrossUserAssetGetWithProof() {
		// asset:get of another user's asset works WITH read rights — the gate
		// resolves the OWNER's store (a is like w: denied without rights, served
		// with them). Uses the asset/read ability that asset:get enforces.
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of("name", Strings.create("shared-via-get"))), ALICE);
		AString assetPath = RT.ensureString(RT.getIn(stored, Fields.ID));  // did:alice/a/<hash>

		AMap<AString, ACell> grant = ownerToken(ALICE_KP, BOB_DID, assetPath.toString(), "asset/read", 3600);
		ACell got = run("v/ops/asset/get", Maps.of(Fields.ID, assetPath), withProofs(BOB, grant));
		assertEquals(CVMBool.TRUE, RT.getIn(got, "exists"));
		assertEquals(Strings.create("shared-via-get"), RT.getIn(RT.getIn(got, "value"), "name"));
	}

	@Test
	public void testCrossUserAssetGetDeniedWithoutProof() {
		// Symmetric denial via asset:get (the covia#295 report was asset visibility).
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of("name", Strings.create("private-asset"))), ALICE);
		AString assetPath = RT.ensureString(RT.getIn(stored, Fields.ID));
		Job get = engine.jobs().invokeOperation("v/ops/asset/get", Maps.of(Fields.ID, assetPath), BOB);
		assertThrows(Exception.class, () -> get.awaitResult(5000),
			"asset:get of another user's asset without rights is a denial, not exists:false");
	}

	@Test
	public void testOwnerReadsOwnAssetByDidUrl() {
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of("name", Strings.create("my-asset"))), ALICE);
		AString assetPath = RT.ensureString(RT.getIn(stored, Fields.ID));

		// Alice reads her own asset by its DID URL — own namespace, no proof.
		ACell result = run("v/ops/covia/read", Maps.of(Fields.PATH, assetPath), ALICE);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("my-asset"), RT.getIn(RT.getIn(result, "value"), "name"));
	}

	// ========== Adversarial: DIDs whose id contains colons (did:web :u:/:g:) ==========

	private static final AString ALICE_WEB = Strings.create("did:web:venue.example:u:alice");
	private static final AString BOB_WEB   = Strings.create("did:web:venue.example:u:bob");

	@Test
	public void testColonDidForeignReadDenied() {
		// Security property: the gate must still see a FOREIGN owner for a did:web
		// user DID with :u: segments, and deny without a proof.
		RequestContext alice = RequestContext.of(ALICE_WEB);
		Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, BOB_WEB + "/w/secret"), alice);
		assertThrows(Exception.class, () -> read.awaitResult(5000),
			"a foreign colon-DID resource must be gated");
	}

	@Test
	public void testColonDidForeignWriteDenied() {
		RequestContext alice = RequestContext.of(ALICE_WEB);
		Job write = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, BOB_WEB + "/w/x", Fields.VALUE, Strings.create("nope")), alice);
		assertThrows(Exception.class, () -> write.awaitResult(5000),
			"a foreign colon-DID write must be gated");
	}

	@Test
	public void testColonDidOwnNamespaceConsistent() {
		// Consistency property: a did:web user writes to its own namespace via the
		// explicit DID form, then reads it back via the bare path. Both must address
		// the SAME namespace — owner detection must agree between the write
		// (resolveDIDURL) and the bare read (getUserCursor), for a colon-id DID.
		RequestContext alice = RequestContext.of(ALICE_WEB);
		run("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_WEB + "/w/foo", Fields.VALUE, Strings.create("mine")), alice);
		ACell read = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/foo"), alice);
		assertEquals(CVMBool.TRUE, RT.getIn(read, "exists"),
			"own explicit-DID write must be visible via the bare path");
		assertEquals(Strings.create("mine"), RT.getIn(read, "value"));
	}

	@Test
	public void testAgentReachesOwnerNamespaceViaExplicitDid() {
		// An agent sub-principal (<owner>:g:carol) works inside its OWNER's
		// namespace. A write to <owner>/w/… by explicit DID must be treated as own
		// (getUserDID projects the agent to its owner), landing where the owner's
		// bare reads look — not gated as cross-user.
		RequestContext agent = RequestContext.of(Strings.create(ALICE_DID.toString() + ":g:carol"));
		run("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/agentnote", Fields.VALUE, Strings.create("by carol")), agent);
		ACell read = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/agentnote"), ALICE);
		assertEquals(Strings.create("by carol"), RT.getIn(read, "value"),
			"an agent's write to its owner's namespace must be the owner's own data");
	}

	@Test
	public void testPrefixLookalikeDidIsForeign() {
		// A resource DID that merely STARTS WITH the caller's DID (a different
		// principal sharing a prefix) must be foreign — owner comparison is exact
		// equality, never prefix matching.
		RequestContext alice = RequestContext.of(ALICE_DID);
		AString lookalike = Strings.create(ALICE_DID.toString() + "evil");
		Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, lookalike + "/w/x"), alice);
		assertThrows(Exception.class, () -> read.awaitResult(5000),
			"a prefix-lookalike DID must be foreign, not own");
	}

	@Test
	public void testVenueNonCatalogNamespaceNotPublic() {
		// The venue-catalog public rule (crossUserAllows) is /a/-only: the venue's
		// OTHER namespaces (e.g. its own workspace) are NOT exposed by it.
		RequestContext alice = RequestContext.of(ALICE_DID);
		AString venueW = Strings.create(engine.getDIDString().toString() + "/w/venue-secret");
		Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, venueW), alice);
		assertThrows(Exception.class, () -> read.awaitResult(5000),
			"the venue's non-/a/ namespaces must not be public via the catalog rule");
	}

	// ========== Own namespace via explicit DID (previously hard-rejected) ==========

	@Test
	public void testOwnNamespaceWriteWithExplicitDid() {
		// Writing to your own namespace via an explicit DID URL now behaves exactly
		// like a bare path (before #295 it hit the blanket DID-URL write reject).
		run("v/ops/covia/write",
			Maps.of(Fields.PATH, ALICE_DID + "/w/mine", Fields.VALUE, Strings.create("my data")),
			ALICE);
		ACell read = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/mine"), ALICE);
		assertEquals(Strings.create("my data"), RT.getIn(read, "value"));
	}
}
