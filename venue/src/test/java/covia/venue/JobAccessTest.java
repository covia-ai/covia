package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Sharing the owner's namespace is proximity, not permission: a capability-scoped
 * agent reaches the jobs it launched, or those its caps cover, never every job of
 * its owner. And a job is reached by id only with the right to read it.
 */
public class JobAccessTest {

	private static final AString INVOKE = Strings.intern("invoke");

	final Engine engine = TestEngine.ENGINE;
	AString alice, bob;
	RequestContext ALICE, BOB;

	@BeforeEach
	void setup() {
		alice = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		bob = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try { job.awaitResult(10000); } catch (Exception ignored) {}
		return job;
	}

	private RequestContext agent(AVector<ACell> caps) {
		return RequestContext.ofAgent(alice, Strings.create("worker")).withCaps(caps);
	}

	private static AVector<ACell> invokeOnly() {
		return Vectors.of(Maps.of(Capability.CAN, INVOKE));
	}

	private Job jobResult(Job of, RequestContext ctx) {
		return run("v/ops/grid/job-result", Maps.of(Fields.ID, of.getID().toHexString(),
			Fields.TIMEOUT, CVMLong.create(5000)), ctx);
	}

	private static void assertDenied(Job job) {
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(JobManager.JOB_ACCESS_DENIED), job.getErrorMessage());
	}

	@Test
	public void testScopedAgentCannotReadOwnersJobs() {
		run("v/ops/covia/write", Maps.of(Fields.PATH, "w/private", Fields.VALUE, "OWNER-ONLY"), ALICE);
		Job ownerRead = run("v/ops/covia/read", Maps.of(Fields.PATH, "w/private"), ALICE);
		assertEquals(Status.COMPLETE, ownerRead.getStatus());

		RequestContext worker = agent(invokeOnly());
		assertDenied(jobResult(ownerRead, worker));
		assertThrows(AuthException.class, () -> engine.jobs().getJobData(ownerRead.getID(), worker));
	}

	@Test
	public void testScopedAgentReadsJobsItLaunched() {
		RequestContext worker = agent(invokeOnly());
		Job own = run("v/test/ops/echo", Maps.of("echo", "mine"), worker);
		assertEquals(Status.COMPLETE, own.getStatus(), own.getErrorMessage());
		Job result = jobResult(own, worker);
		assertEquals(Status.COMPLETE, result.getStatus(), result.getErrorMessage());
		assertEquals(Strings.create("mine"), RT.getIn(result.getOutput(), "echo"));
	}

	@Test
	public void testCapsOverJobsGrantAccessByAbility() {
		Job ownerJob = run("v/test/ops/echo", Maps.of("echo", "shared"), ALICE);
		RequestContext reader = agent(Vectors.of(Maps.of(Capability.CAN, INVOKE),
			Capability.create(Strings.create("j/"), Capability.CRUD_READ)));
		assertEquals(Status.COMPLETE, jobResult(ownerJob, reader).getStatus());
		// Reading is not managing
		assertThrows(AuthException.class, () -> engine.jobs().deleteJob(ownerJob.getID(), reader));
	}

	@Test
	public void testLiveJobIsNotReachableByIdAlone() {
		Job pending = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ALICE);
		try {
			assertDenied(jobResult(pending, BOB));
			assertDenied(jobResult(pending, agent(invokeOnly())));
		} finally {
			engine.jobs().cancelJob(pending.getID(), ALICE);
		}
	}
}
