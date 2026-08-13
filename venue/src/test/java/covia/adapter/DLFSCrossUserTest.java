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
import convex.core.lang.RT;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * covia#98 — cross-user DLFS access via owner-issued UCAN.
 *
 * <p>DLFS drives are per-user (signed with the owner's key). A caller reaches
 * another user's drive by naming it as a DID-URL {@code drive} reference
 * ({@code did:key:zAlice/docs}); the venue authorises the read against the
 * owner-scoped resource {@code <ownerDID>/dlfs/<drive>/<path>} via the
 * presented UCAN proofs — the same {@code CapabilityChecker.proofsCover} gate
 * the {@code /w/} workspace cross-user reads use (see {@code UCANTest}).</p>
 *
 * <p>Reads, writes and deletes are all permitted when the presented proof
 * authorises the required ability ({@code crud/read} / {@code crud/write} /
 * {@code crud/delete}); a mutation lands on the owner's drive under the
 * owner's key (custodial).</p>
 *
 * <p>Per-test key pairs give fresh Alice/Bob/Carol DIDs each method, so Alice's
 * {@code docs} drive is isolated across tests on the shared engine.</p>
 */
public class DLFSCrossUserTest {

	final Engine engine = TestEngine.ENGINE;
	private AKeyPair ALICE_KP;
	private AKeyPair BOB_KP;
	private AKeyPair CAROL_KP;
	private AString ALICE_DID;
	private AString BOB_DID;
	private AString CAROL_DID;
	private RequestContext ALICE;
	private RequestContext BOB;

	/** DID-URL drive reference naming Alice's {@code docs} drive. */
	private String aliceDocs;

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
		aliceDocs = ALICE_DID.toString() + "/docs";

		// Alice creates a drive and writes files under shared/ and private/.
		run("v/ops/dlfs/create-drive", Maps.of("name", "docs"), ALICE);
		run("v/ops/dlfs/mkdir", Maps.of("drive", "docs", "path", "shared"), ALICE);
		run("v/ops/dlfs/mkdir", Maps.of("drive", "docs", "path", "private"), ALICE);
		run("v/ops/dlfs/write", Maps.of(
			"drive", "docs", "path", "shared/report.md", "content", "quarterly numbers"), ALICE);
		run("v/ops/dlfs/write", Maps.of(
			"drive", "docs", "path", "private/secret.md", "content", "top secret"), ALICE);
	}

	// ========== Helpers (mirror UCANTest) ==========

	private ACell run(String op, ACell input, RequestContext ctx) {
		return engine.jobs().invokeOperation(op, input, ctx).awaitResult(5000);
	}

	/** An owner-signed UCAN granting {@code audience} the {@code ability} over
	 *  {@code <ownerDID><path>} (path already includes the leading '/'). */
	private AMap<AString, ACell> issueToken(AString audience, AString ownerDID,
			String path, String ability, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		assertEquals(ALICE_DID, ownerDID, "test helper only owns Alice's signing key");
		String withURI = ownerDID.toString() + path;
		UCAN token = UCAN.create(
			ALICE_KP,
			UCAN.fromDIDKey(audience),
			exp,
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

	/** A token the resource OWNER signs directly (root issuer == owner, empty prf)
	 *  — self-sovereign, the venue is NOT the issuer. */
	private static AMap<AString, ACell> ownerToken(AKeyPair ownerKP, AString audience,
			String withURI, String ability, long ttlSeconds) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		UCAN token = UCAN.create(ownerKP, UCAN.fromDIDKey(audience), exp,
			Vectors.of(Capability.create(Strings.create(withURI), Strings.create(ability))),
			Vectors.empty());
		return token.toMap();
	}

	/** A delegation hop: {@code issuerKP} grants {@code audience} the (narrowed)
	 *  capability, carrying {@code parent} as its proof chain. */
	private static AMap<AString, ACell> delegate(AKeyPair issuerKP, AString audience,
			String withURI, String ability, long ttlSeconds, AMap<AString, ACell> parent) {
		long exp = (System.currentTimeMillis() / 1000) + ttlSeconds;
		UCAN token = UCAN.create(issuerKP, UCAN.fromDIDKey(audience), exp,
			Vectors.of(Capability.create(Strings.create(withURI), Strings.create(ability))),
			Vectors.of(parent));
		return token.toMap();
	}

	// ========== Additive authority (config grant OR proof) ==========

	@Test
	public void testConfigCappedCallerUsesProofAdditively() {
		// Alice is capped to read only her own w/reports — her scope does NOT
		// reach Bob's namespace. A self-sovereign proof from Bob grants her read of
		// his resource; under the additive model that authorises regardless of her
		// scope. This is the core behaviour change, checked at the seam directly.
		AVector<ACell> aliceCaps = Vectors.of(
			Capability.create(Strings.create("w/reports"), Capability.CRUD_READ));
		AString bobResource = Strings.create(BOB_DID + "/dlfs/docs/shared/report.md");
		AMap<AString, ACell> bobGrant = ownerToken(BOB_KP, ALICE_DID,
			BOB_DID + "/dlfs/docs/", "crud/read", 3600);

		RequestContext cappedNoProof = RequestContext.of(ALICE_DID).withCaps(aliceCaps);
		RequestContext cappedWithProof = withProofs(cappedNoProof, bobGrant);

		assertFalse(engine.authorityCovers(cappedNoProof, bobResource, Capability.CRUD_READ),
			"capped caller, no proof — the scope does not reach Bob");
		assertTrue(engine.authorityCovers(cappedWithProof, bobResource, Capability.CRUD_READ),
			"capped caller + valid proof — additive, authorised despite the scope");
	}

	// ========== Self-sovereign + delegation chains (covia#196) ==========

	@Test
	public void testSelfSovereignGrantAuthorises() {
		// Alice signs her OWN root granting Bob read of her drive — the venue is not
		// the issuer. Authorised via the self-sovereign root-authority arm.
		AMap<AString, ACell> token = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/dlfs/docs/", "crud/read", 3600);
		Job read = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		assertEquals("quarterly numbers", RT.getIn(read.awaitResult(5000), "content").toString());
	}

	@Test
	public void testDelegationChainAuthorisesWithAttenuation() {
		// Alice → Bob (all of docs/) → Carol (only shared/), self-sovereign root.
		AMap<AString, ACell> root = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/dlfs/docs/", "crud", 3600);
		AMap<AString, ACell> toCarol = delegate(BOB_KP, CAROL_DID,
			ALICE_DID + "/dlfs/docs/shared/", "crud/read", 3600, root);
		RequestContext carol = withProofs(RequestContext.of(CAROL_DID), toCarol);

		// Covered subpath — allowed through the chain.
		Job shared = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"), carol);
		assertEquals("quarterly numbers", RT.getIn(shared.awaitResult(5000), "content").toString());

		// Outside Carol's narrowed grant — denied.
		Job priv = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "private/secret.md"), carol);
		assertThrows(Exception.class, () -> priv.awaitResult(5000));
	}

	@Test
	public void testEscalatedRedelegationRefused() {
		// Alice → Bob (shared/ only) → Carol (claims all of docs/ — broader than Bob held).
		AMap<AString, ACell> root = ownerToken(ALICE_KP, BOB_DID,
			ALICE_DID + "/dlfs/docs/shared/", "crud/read", 3600);
		AMap<AString, ACell> escalated = delegate(BOB_KP, CAROL_DID,
			ALICE_DID + "/dlfs/docs/", "crud/read", 3600, root); // escalation: broader path
		RequestContext carol = withProofs(RequestContext.of(CAROL_DID), escalated);

		// Reading private/ via the escalated grant is refused — per-hop attenuation holds.
		Job priv = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "private/secret.md"), carol);
		assertThrows(Exception.class, () -> priv.awaitResult(5000));
	}

	// ========== Cross-user read ==========

	@Test
	public void testCrossUserReadWithValidProof() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/read", 3600);

		Job read = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		ACell result = read.awaitResult(5000);

		assertEquals("quarterly numbers", RT.getIn(result, "content").toString());

		Job fileRead = engine.jobs().invokeOperation("v/ops/file/read",
			Maps.of("path", ALICE_DID + "/dlfs/docs/shared/report.md"),
			withProofs(BOB, token));
		assertEquals("quarterly numbers",
			RT.getIn(fileRead.awaitResult(5000), "content").toString());
	}

	@Test
	public void testCrossUserReadDeniedWithoutProof() {
		Job read = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			BOB);
		assertThrows(Exception.class, () -> read.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadSubpathAttenuation() {
		// Grant narrowed to shared/ only.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID,
			"/dlfs/docs/shared/", "crud/read", 3600);

		// Covered path — allowed.
		Job shared = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		assertEquals("quarterly numbers", RT.getIn(shared.awaitResult(5000), "content").toString());

		// Sibling path not covered by the grant — denied.
		Job priv = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "private/secret.md"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> priv.awaitResult(5000));
	}

	@Test
	public void testCrossUserListWithProof() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/read", 3600);

		Job list = engine.jobs().invokeOperation("v/ops/dlfs/list",
			Maps.of("drive", aliceDocs, "path", "shared"),
			withProofs(BOB, token));
		ACell result = list.awaitResult(5000);
		AVector<?> entries = RT.ensureVector(RT.getIn(result, "entries"));
		assertNotNull(entries);
		assertTrue(entries.toString().contains("report.md"), "listing should include report.md");
	}

	// ========== Adversarial ==========

	@Test
	public void testCrossUserReadDeniedExpiredToken() {
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/read", -3600);

		Job read = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> read.awaitResult(5000));
	}

	@Test
	public void testCrossUserReadDeniedWrongAudience() {
		// Token issued to Carol, but Bob presents it.
		AMap<AString, ACell> token = issueToken(CAROL_DID, ALICE_DID, "/dlfs/docs/", "crud/read", 3600);

		Job read = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> read.awaitResult(5000));
	}

	@Test
	public void testForgedSignatureDenied() {
		// Token signed with a random key (issuer != venue) — rejected.
		AKeyPair fakeKP = AKeyPair.generate();
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN forged = UCAN.create(fakeKP, UCAN.fromDIDKey(BOB_DID), exp,
			Vectors.of(Capability.create(
				Strings.create(ALICE_DID + "/dlfs/docs/"), Capability.CRUD_READ)),
			Vectors.empty());

		Job read = engine.jobs().invokeOperation("v/ops/dlfs/read",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, forged.toMap()));
		assertThrows(Exception.class, () -> read.awaitResult(5000));
	}

	// ========== Cross-user write / delete (authorised) ==========

	@Test
	public void testCrossUserWriteWithProof() {
		// A crud/write grant authorises a write to Alice's drive.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/write", 3600);

		Job write = engine.jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", aliceDocs, "path", "shared/frombob.md", "content", "hello from bob"),
			withProofs(BOB, token));
		write.awaitResult(5000); // no throw

		// The write landed on Alice's drive — Alice reads it back.
		ACell result = run("v/ops/dlfs/read",
			Maps.of("drive", "docs", "path", "shared/frombob.md"), ALICE);
		assertEquals("hello from bob", RT.getIn(result, "content").toString());
	}

	@Test
	public void testCrossUserWriteDeniedWithReadGrant() {
		// A read grant does not authorise a write.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/read", 3600);

		Job write = engine.jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", aliceDocs, "path", "shared/inject.md", "content", "nope"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> write.awaitResult(5000));

		// Alice's drive is untouched — the file was never created.
		Job list = engine.jobs().invokeOperation("v/ops/dlfs/list",
			Maps.of("drive", "docs", "path", "shared"), ALICE);
		assertFalse(RT.ensureVector(RT.getIn(list.awaitResult(5000), "entries"))
			.toString().contains("inject.md"), "unauthorised write must not create a file");
	}

	@Test
	public void testCrossUserDeleteWithProof() {
		// A crud/delete grant authorises removing a file from Alice's drive.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/delete", 3600);

		Job del = engine.jobs().invokeOperation("v/ops/dlfs/delete",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		del.awaitResult(5000); // no throw

		// The file is gone from Alice's drive.
		Job list = engine.jobs().invokeOperation("v/ops/dlfs/list",
			Maps.of("drive", "docs", "path", "shared"), ALICE);
		assertFalse(RT.ensureVector(RT.getIn(list.awaitResult(5000), "entries"))
			.toString().contains("report.md"), "authorised delete should remove the file");
	}

	@Test
	public void testBroaderCrudGrantCoversWrite() {
		// A parent "crud" grant covers the narrower crud/write the write op needs —
		// authorisation goes through Capability.covers, not exact can matching.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud", 3600);

		Job write = engine.jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", aliceDocs, "path", "shared/broad.md", "content", "covered by crud"),
			withProofs(BOB, token));
		write.awaitResult(5000); // no throw

		ACell result = run("v/ops/dlfs/read",
			Maps.of("drive", "docs", "path", "shared/broad.md"), ALICE);
		assertEquals("covered by crud", RT.getIn(result, "content").toString());
	}

	@Test
	public void testCrossUserDeleteDeniedWithReadGrant() {
		// A read grant does not authorise a delete.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud/read", 3600);

		Job del = engine.jobs().invokeOperation("v/ops/dlfs/delete",
			Maps.of("drive", aliceDocs, "path", "shared/report.md"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> del.awaitResult(5000));

		// The file is still there.
		Job list = engine.jobs().invokeOperation("v/ops/dlfs/list",
			Maps.of("drive", "docs", "path", "shared"), ALICE);
		assertTrue(RT.ensureVector(RT.getIn(list.awaitResult(5000), "entries"))
			.toString().contains("report.md"), "unauthorised delete must not remove the file");
	}

	@Test
	public void testCrossUserWriteAssetRefResolvesAsCaller() {
		// Confused-deputy pin: a caller-supplied `asset` ref in a cross-user write
		// must resolve under the CALLER's authority, never the drive owner's — a
		// drive-scoped grant must not read the owner's other namespaces.
		// Alice holds a private op-shaped value (resolvable as an Asset) in her w/.
		run("v/ops/covia/write", Maps.of(
			"path", "w/secret-op",
			"value", Maps.of("name", "hidden", "operation", Maps.of("adapter", "test:echo"))), ALICE);

		// Bob has a full crud grant on Alice's drive — but `asset: "w/secret-op"`
		// resolves in BOB's namespace (where it doesn't exist), so the write fails.
		AMap<AString, ACell> token = issueToken(BOB_DID, ALICE_DID, "/dlfs/docs/", "crud", 3600);
		Job steal = engine.jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", aliceDocs, "path", "shared/steal.md", "asset", "w/secret-op"),
			withProofs(BOB, token));
		assertThrows(Exception.class, () -> steal.awaitResult(5000),
			"caller asset ref must not resolve in the owner's namespace");

		// And nothing landed on Alice's drive.
		Job list = engine.jobs().invokeOperation("v/ops/dlfs/list",
			Maps.of("drive", "docs", "path", "shared"), ALICE);
		assertFalse(RT.ensureVector(RT.getIn(list.awaitResult(5000), "entries"))
			.toString().contains("steal.md"), "failed write must not create a file");
	}

	// ========== Own-drive regression (behaviour unchanged) ==========

	@Test
	public void testOwnDriveReadUnaffected() {
		// Alice reading her own drive by bare name — no proofs, no DID-URL.
		ACell result = run("v/ops/dlfs/read",
			Maps.of("drive", "docs", "path", "shared/report.md"), ALICE);
		assertEquals("quarterly numbers", RT.getIn(result, "content").toString());
	}
}
