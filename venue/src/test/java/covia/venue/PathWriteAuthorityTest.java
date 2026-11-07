package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.CoviaPath;

/**
 * Writes to another user's paths under a delegated grant. Creating a key
 * changes the map that holds it, so a write that creates any key of its path
 * also needs authority at the nearest level that exists.
 */
public class PathWriteAuthorityTest {

	final Engine engine = TestEngine.ENGINE;
	AKeyPair aliceKP, bobKP;
	AString alice;
	RequestContext ALICE, BOB;

	@BeforeEach
	void setup() {
		aliceKP = AKeyPair.generate();
		bobKP = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKP.getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(UCAN.toDIDKey(bobKP.getAccountKey()));
		assertEquals(Status.COMPLETE, write("w/reports", Maps.empty(), ALICE).getStatus());
	}

	/** Runs covia:write; a refusal before any Job exists becomes a failed Job. */
	private Job write(String path, ACell value, RequestContext ctx) {
		Job job;
		try {
			job = engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, path, Fields.VALUE, value), ctx);
		} catch (RuntimeException refused) {
			return Job.failure(String.valueOf(refused.getMessage()));
		}
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private RequestContext bobWith(String with) {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		return BOB.withProofs(Vectors.of(UCAN.create(aliceKP, bobKP.getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(alice + "/" + with), Capability.CRUD_WRITE)),
			Vectors.empty()).toMap()));
	}

	private ACell aliceRead(String path) {
		return engine.getVenueState().users().user(alice).resolve(CoviaPath.parse(path)).cursor().getOrDefault(null);
	}

	@Test
	public void testCreatingAKeyNeedsAuthorityOverItsMap() {
		RequestContext leafOnly = bobWith("w/reports/q3");
		assertEquals(Status.FAILED, write(alice + "/w/reports/q3", CVMLong.ONE, leafOnly).getStatus(),
			"creating q3 changes w/reports, which the grant does not cover");
		assertNull(aliceRead("w/reports/q3"));

		assertEquals(Status.COMPLETE, write("w/reports/q3", CVMLong.ONE, ALICE).getStatus());
		assertEquals(Status.COMPLETE, write(alice + "/w/reports/q3", CVMLong.create(2), leafOnly).getStatus(),
			"replacing a value creates nothing");
		assertEquals(CVMLong.create(2), aliceRead("w/reports/q3"));
	}

	@Test
	public void testCreatedLevelsAreAuthorisedWhereThePathExists() {
		Job created = write(alice + "/w/reports/2026/q3", CVMLong.ONE, bobWith("w/reports"));
		assertEquals(Status.COMPLETE, created.getStatus());
		assertEquals(CVMBool.TRUE, RT.getIn(created.getOutput(), Fields.PATH_CREATED));
		assertEquals(CVMLong.ONE, aliceRead("w/reports/2026/q3"));

		assertEquals(Status.FAILED, write(alice + "/w/drafts/q3", CVMLong.ONE, bobWith("w/drafts/q3")).getStatus());
		assertNull(aliceRead("w/drafts"), "a refused write creates no level");
	}
}
