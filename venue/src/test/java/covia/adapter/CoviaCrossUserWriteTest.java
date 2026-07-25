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
	public void testOwnerReadsOwnAssetByDidUrl() {
		ACell stored = run("v/ops/asset/store",
			Maps.of(Fields.METADATA, Maps.of("name", Strings.create("my-asset"))), ALICE);
		AString assetPath = RT.ensureString(RT.getIn(stored, Fields.ID));

		// Alice reads her own asset by its DID URL — own namespace, no proof.
		ACell result = run("v/ops/covia/read", Maps.of(Fields.PATH, assetPath), ALICE);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("my-asset"), RT.getIn(RT.getIn(result, "value"), "name"));
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
