package covia.lattice;

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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Adversarial gate references. A gate a grant names is bound to the principal
 * whose grant it is, as a bare {@code with} is, and naming it is enough for it to
 * run as a gate check: the presenter can neither substitute its own gate nor
 * need to read the grantor's.
 */
public class GateBindingTest {

	private static final String SHARED = "ALICE-GATED";

	final Engine engine = TestEngine.ENGINE;
	AKeyPair aliceKP, bobKP, carolKP;
	AString alice, bob, carol;
	RequestContext ALICE, BOB, CAROL;
	AMap<AString, ACell> allowGate, denyGate;

	@BeforeEach
	void setup() {
		aliceKP = AKeyPair.generate();
		bobKP = AKeyPair.generate();
		carolKP = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKP.getAccountKey());
		bob = UCAN.toDIDKey(bobKP.getAccountKey());
		carol = UCAN.toDIDKey(carolKP.getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
		CAROL = RequestContext.of(carol);
		allowGate = engine.resolveAsset(Strings.create("v/test/ops/allowgate"), ALICE).meta();
		denyGate = engine.resolveAsset(Strings.create("v/test/ops/denygate"), ALICE).meta();
		write("w/shared", Strings.create(SHARED), ALICE);
	}

	private void write(String path, ACell value, RequestContext ctx) {
		assertEquals(Status.COMPLETE, run("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), ctx).getStatus());
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private static long exp() {
		return System.currentTimeMillis() / 1000 + 3600;
	}

	private AMap<AString, ACell> gatedRead(String gate) {
		return Capability.create(Strings.create(alice + "/w/shared"), Capability.CRUD_READ,
			Maps.of("gate", Strings.create(gate)));
	}

	private UCAN aliceTo(AKeyPair audience, AMap<AString, ACell> cap) {
		return UCAN.create(aliceKP, audience.getAccountKey(), exp(), Vectors.of(cap), Vectors.empty());
	}

	private Job readShared(RequestContext ctx) {
		return run("v/ops/covia/read", Maps.of(Fields.PATH, alice + "/w/shared"), ctx);
	}

	private static boolean readsShared(Job job) {
		return job.getStatus() == Status.COMPLETE
			&& Strings.create(SHARED).equals(RT.getIn(job.getOutput(), Fields.VALUE));
	}

	@Test
	public void testPresenterCannotPlantTheGate() {
		write("o/gate", denyGate, ALICE);
		write("o/gate", allowGate, BOB);
		Job read = readShared(BOB.withProofs(Vectors.of(aliceTo(bobKP, gatedRead("o/gate")).toMap())));
		assertFalse(readsShared(read), "Bob's own o/gate must not stand in for Alice's");
		assertEquals(Status.FAILED, read.getStatus());
	}

	@Test
	public void testPrivateGateRunsWithoutReadAccess() {
		write("o/gate", allowGate, ALICE);
		RequestContext presented = BOB.withProofs(Vectors.of(aliceTo(bobKP, gatedRead("o/gate")).toMap()));
		assertThrows(covia.exception.AuthException.class,
			() -> engine.resolvePath(Strings.create(alice + "/o/gate"), presented),
			"Bob cannot read Alice's gate definition");
		assertTrue(readsShared(readShared(presented)), "yet naming it is enough for it to run as a gate");
	}

	@Test
	public void testLeadingSlashStillNamesTheIssuersGate() {
		write("o/gate", allowGate, BOB);
		RequestContext presented = BOB.withProofs(Vectors.of(aliceTo(bobKP, gatedRead("/o/gate")).toMap()));
		write("o/gate", denyGate, ALICE);
		assertFalse(readsShared(readShared(presented)), "Alice's refusing gate rules, not Bob's");
		write("o/gate", allowGate, ALICE);
		assertTrue(readsShared(readShared(presented)), "and her passing one admits the read");
	}

	@Test
	public void testEachLinkBindsItsGateToItsIssuer() {
		// Alice → Bob gated by Alice's o/gate; Bob → Carol gated by Bob's o/gate.
		write("o/gate", allowGate, ALICE);
		write("o/gate", allowGate, CAROL);   // Carol's namespace is never consulted
		UCAN root = aliceTo(bobKP, Capability.create(Strings.create(alice + "/w/shared"), Capability.CRUD,
			Maps.of("gate", Strings.create("o/gate"))));
		UCAN leaf = UCAN.create(bobKP, carolKP.getAccountKey(), exp(),
			Vectors.of(gatedRead("o/gate")), Vectors.of(root.toMap()));
		RequestContext presented = CAROL.withProofs(Vectors.of(leaf.toMap()));

		write("o/gate", denyGate, BOB);
		assertFalse(readsShared(readShared(presented)), "the re-delegator's gate rules on its own link");
		write("o/gate", allowGate, BOB);
		assertTrue(readsShared(readShared(presented)), "both issuers' gates pass");
		write("o/gate", denyGate, ALICE);
		assertFalse(readsShared(readShared(presented)), "the root issuer's gate still rules");
	}

	@Test
	public void testBareGateInAnUnrewritableLinkFailsClosed() {
		write("o/gate", allowGate, ALICE);
		write("o/gate", allowGate, CAROL);
		UCAN bareRoot = aliceTo(bobKP, Capability.create(Strings.create(alice + "/w/shared"), Capability.CRUD,
			Maps.of("gate", Strings.create("o/gate"))));
		UCAN absoluteRoot = aliceTo(bobKP, Capability.create(Strings.create(alice + "/w/shared"), Capability.CRUD,
			Maps.of("gate", Strings.create(alice + "/o/gate"))));
		AMap<AString, ACell> ungatedRead = Capability.create(Strings.create(alice + "/w/shared"), Capability.CRUD_READ);
		// A parent carried as a JWT string cannot be normalised in place.
		UCAN viaAbsolute = UCAN.create(bobKP, carolKP.getAccountKey(), exp(),
			Vectors.of(ungatedRead), Vectors.of(Strings.create(absoluteRoot.toJWT(aliceKP))));
		UCAN viaBare = UCAN.create(bobKP, carolKP.getAccountKey(), exp(),
			Vectors.of(ungatedRead), Vectors.of(Strings.create(bareRoot.toJWT(aliceKP))));

		assertTrue(readsShared(readShared(CAROL.withProofs(Vectors.of(viaAbsolute.toMap())))),
			"an absolute gate in a JWT link names Alice's gate");
		assertFalse(readsShared(readShared(CAROL.withProofs(Vectors.of(viaBare.toMap())))),
			"a bare gate there names no one's, so it fails closed");
	}

	@Test
	public void testOrchestrationGateCannotBorrowThePresentersSteps() {
		// Alice's gate delegates its decision to a step named bare, o/check.
		write("o/check", denyGate, ALICE);
		write("o/check", allowGate, BOB);
		write("o/gate", Maps.of("name", "check via step", "operation", Maps.of(
			"adapter", "orchestrator",
			"steps", Vectors.of(Maps.of("op", "o/check", "input", Maps.empty())),
			"result", Maps.of("result", Vectors.of(0)))), ALICE);
		Job read = readShared(BOB.withProofs(Vectors.of(aliceTo(bobKP, gatedRead("o/gate")).toMap())));
		// The gate runs with no authority beyond invoking itself, so its step can
		// be neither read nor invoked, and the gate fails closed.
		assertFalse(readsShared(read), "the presenter's planted step must not pass Alice's gate");
		assertEquals(Status.FAILED, read.getStatus());
	}
}
