package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * covia#254: an authenticated caller is at least as privileged as the
 * anonymous public caller — access to the PUBLIC user's resources follows
 * the public capability scope (default read-only), for any caller.
 *
 * <p>Parity is scope-governed, never hard-coded: what these tests assert
 * under the default scope widens automatically (and deliberately —
 * caveat emptor) when an operator widens {@code auth.public.caps}.</p>
 */
public class AuthenticatedPublicAccessTest {

	private final Engine engine = TestEngine.ENGINE;

	private AString PUBLIC_DID;
	private RequestContext PUBLIC;
	private AString ALICE_DID;
	private RequestContext ALICE;
	private AString BOB_DID;
	private RequestContext BOB;

	@BeforeEach
	public void setup(TestInfo info) {
		PUBLIC_DID = Strings.create(engine.getDIDString().toString() + ":public");
		PUBLIC = RequestContext.of(PUBLIC_DID);
		ALICE_DID = TestEngine.uniqueDID(info);
		ALICE = RequestContext.of(ALICE_DID);
		BOB_DID = TestEngine.uniqueDID(info + "-bob");
		BOB = RequestContext.of(BOB_DID);
	}

	// ========== Lattice reads ==========

	@Test
	public void testAuthenticatedReadsPublicWorkspace() {
		// Anonymous callers write public data as their own namespace.
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/pub-doc", Fields.VALUE, Strings.create("public content")),
			PUBLIC).awaitResult(5000);

		// An authenticated caller reads it via the DID-URL path — no proof needed.
		ACell result = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, PUBLIC_DID + "/w/pub-doc"), ALICE).awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("public content"), RT.getIn(result, "value"));

		// Parity, not blanket cross-user access: Bob's data stays protected.
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/private-doc", Fields.VALUE, Strings.create("bob content")),
			BOB).awaitResult(5000);
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, BOB_DID + "/w/private-doc"), ALICE).awaitResult(5000),
			"non-public cross-user reads still require a delegation proof");
	}

	@Test
	public void testPublicWriteParityDeniedByDefault() {
		// The default public scope is read-only, and cross-user DID-URL
		// writes remain blocked — write parity arrives only if/when the write
		// path supports public-targeted cursors under a widened scope.
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, PUBLIC_DID + "/w/x", Fields.VALUE, Strings.create("nope")),
			ALICE).awaitResult(5000));
	}

	// ========== Job reads ==========

	@Test
	public void testAuthenticatedReadsPublicJob() {
		// ACTIVE public job — readable via the job surface (hot-cache path,
		// canReadJob's public-scope fallback).
		Job activeJob = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), PUBLIC);
		try {
			AMap<AString, ACell> viaAlice = engine.jobs().getJobData(activeJob.getID(), ALICE);
			assertNotNull(viaAlice, "active public-owned jobs are readable per the public scope");
			assertEquals(PUBLIC_DID, viaAlice.get(Fields.CALLER));

			// Parity, not blanket access: Bob's active job stays owner-only.
			Job bobJob = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), BOB);
			try {
				assertThrows(AuthException.class,
					() -> engine.jobs().getJobData(bobJob.getID(), ALICE),
					"another user's job must stay unreadable without a proof");
			} finally {
				engine.jobs().cancelJob(bobJob.getID(), BOB);
			}
		} finally {
			engine.jobs().cancelJob(activeJob.getID(), PUBLIC);
		}

		// TERMINAL public job — cross-user terminal reads go via the DID-URL
		// lattice path (same rule as delegated reads), hitting the
		// verifyProofs public-scope fallback.
		Job doneJob = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of("text", "hello"), PUBLIC);
		doneJob.awaitResult(5000);
		ACell read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, PUBLIC_DID + "/j/" + doneJob.getID().toHexString()),
			ALICE).awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(read, "exists"),
			"terminal public-owned jobs are readable via the DID-URL path");
		assertEquals(PUBLIC_DID, RT.getIn(read, "value", Fields.CALLER.toString()));
	}

	// ========== Secret resolution fallback (use-only, never disclosure) ==========

	@Test
	public void testSecretResolutionFallsBackToPublicStore() {
		// Operator-style provisioning into the public store.
		engine.jobs().invokeOperation("v/ops/secret/set",
			Maps.of("name", "PUB_FALLBACK_KEY", "value", "public-value"),
			PUBLIC).awaitResult(5000);

		// Authenticated resolution falls back to the public store...
		assertEquals("public-value", engine.resolveSecret("s/PUB_FALLBACK_KEY", ALICE));
		// ...and the public caller still resolves its own store directly.
		assertEquals("public-value", engine.resolveSecret("s/PUB_FALLBACK_KEY", PUBLIC));

		// The caller's OWN secret of the same name always shadows the public one.
		engine.jobs().invokeOperation("v/ops/secret/set",
			Maps.of("name", "PUB_FALLBACK_KEY", "value", "alice-own"),
			ALICE).awaitResult(5000);
		assertEquals("alice-own", engine.resolveSecret("s/PUB_FALLBACK_KEY", ALICE));
	}

	@Test
	public void testSecretExtractionRemainsClosed() {
		// covia#254 ruling: extraction is SCOPE-governed, not hard-coded —
		// today it is universally denied (gated implementation pending). When
		// implemented it must require secret/decrypt, which the DEFAULT public
		// scope withholds; an operator widening auth.public.caps to include
		// it gets exactly what they asked for (caveat emptor). This test pins
		// the closed state so that change is a conscious decision.
		engine.jobs().invokeOperation("v/ops/secret/set",
			Maps.of("name", "NO_EXTRACT", "value", "sensitive"),
			PUBLIC).awaitResult(5000);

		Job asPublic = engine.jobs().invokeOperation("v/ops/secret/extract",
			Maps.of("name", "NO_EXTRACT"), PUBLIC);
		assertThrows(Exception.class, () -> asPublic.awaitResult(5000));
		assertEquals(Status.FAILED, asPublic.getStatus(),
			"the shared public identity must not extract stored secret values");

		Job asAlice = engine.jobs().invokeOperation("v/ops/secret/extract",
			Maps.of("name", "NO_EXTRACT"), ALICE);
		assertThrows(Exception.class, () -> asAlice.awaitResult(5000));
		assertEquals(Status.FAILED, asAlice.getStatus(),
			"the resolution fallback must never become a disclosure channel");
	}
}
