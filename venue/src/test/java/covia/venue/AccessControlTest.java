package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

public class AccessControlTest {

	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final AString BOB_DID = Strings.create("did:key:z6MkBob");

	Engine engine;

	@BeforeEach
	public void setup() throws IOException {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	// ========== AccessControl Unit Tests ==========

	@Test
	public void testAccessControlExists() {
		AccessControl ac = engine.getAccessControl();
		assertNotNull(ac, "AccessControl should be initialised");
		assertNotNull(ac.getCursor(), "Auth cursor should be initialised");
	}

	@Test
	public void testInternalBypassesAll() {
		AccessControl ac = engine.getAccessControl();

		// Internal can access any job, even those without :caller
		AMap<AString, ACell> job = Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L));
		assertTrue(ac.canAccessJob(RequestContext.INTERNAL, job));

		// Internal can access job owned by Alice
		AMap<AString, ACell> aliceJob = job.assoc(Fields.CALLER, ALICE_DID);
		assertTrue(ac.canAccessJob(RequestContext.INTERNAL, aliceJob));
	}

	@Test
	public void testOwnerCanAccess() {
		AccessControl ac = engine.getAccessControl();

		AMap<AString, ACell> aliceJob = Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.CALLER, ALICE_DID,
			Fields.UPDATED, CVMLong.create(1000L));

		assertTrue(ac.canAccessJob(RequestContext.of(ALICE_DID), aliceJob),
			"Owner should be able to access their own job");
	}

	@Test
	public void testNonOwnerDenied() {
		AccessControl ac = engine.getAccessControl();

		AMap<AString, ACell> aliceJob = Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.CALLER, ALICE_DID,
			Fields.UPDATED, CVMLong.create(1000L));

		assertFalse(ac.canAccessJob(RequestContext.of(BOB_DID), aliceJob),
			"Non-owner should be denied access");
	}

	@Test
	public void testAnonymousDenied() {
		AccessControl ac = engine.getAccessControl();

		AMap<AString, ACell> aliceJob = Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.CALLER, ALICE_DID,
			Fields.UPDATED, CVMLong.create(1000L));

		assertFalse(ac.canAccessJob(RequestContext.ANONYMOUS, aliceJob),
			"Anonymous should be denied access");
	}

	@Test
	public void testVenueInternalJobDenied() {
		AccessControl ac = engine.getAccessControl();

		// Job without :caller field (venue-internal)
		AMap<AString, ACell> internalJob = Maps.of(
			Fields.STATUS, Status.PENDING,
			Fields.UPDATED, CVMLong.create(1000L));

		assertFalse(ac.canAccessJob(RequestContext.of(ALICE_DID), internalJob),
			"Venue-internal jobs should only be visible to internal requests");
	}

	@Test
	public void testNullJobDataDenied() {
		AccessControl ac = engine.getAccessControl();
		assertFalse(ac.canAccessJob(RequestContext.of(ALICE_DID), null));
	}

	@Test
	public void testOperationAccessPermissive() {
		AccessControl ac = engine.getAccessControl();

		// Phase 2: all operations are accessible
		assertTrue(ac.canAccessOperation(RequestContext.ANONYMOUS, null));
		assertTrue(ac.canAccessOperation(RequestContext.of(ALICE_DID), null));
		assertTrue(ac.canAccessOperation(RequestContext.INTERNAL, null));
	}

	// ========== RequestContext Properties ==========

	@Test
	public void testRequestContextProperties() {
		assertTrue(RequestContext.INTERNAL.isInternal());
		assertFalse(RequestContext.INTERNAL.isAuthenticated());
		assertFalse(RequestContext.INTERNAL.isAnonymous());

		assertFalse(RequestContext.ANONYMOUS.isInternal());
		assertFalse(RequestContext.ANONYMOUS.isAuthenticated());
		assertTrue(RequestContext.ANONYMOUS.isAnonymous());

		RequestContext authed = RequestContext.of(ALICE_DID);
		assertFalse(authed.isInternal());
		assertTrue(authed.isAuthenticated());
		assertFalse(authed.isAnonymous());

		assertSame(RequestContext.ANONYMOUS, RequestContext.of(null));
	}

	// ========== Engine Integration: Invoke with Context ==========

	@Test
	public void testInvokeWithRequestContext() {
		Job job1 = engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "hello"), RequestContext.ANONYMOUS);
		assertNotNull(job1, "Should be able to invoke with anonymous context");

		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		Job job2 = engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "hello"), aliceCtx);
		assertNotNull(job2, "Should be able to invoke with authenticated context");

		// Verify caller DID flows through to job record
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> jobData = (AMap<AString, ACell>) job2.getData();
		ACell caller = jobData.get(Fields.CALLER);
		assertNotNull(caller, "Job record should contain caller field");
		assertEquals(ALICE_DID, caller);
	}

	// ========== Engine Integration: Job Scoping ==========

	@Test
	public void testGetJobsScopedByCaller() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		// Alice creates a job
		Job aliceJob = engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "alice"), aliceCtx);
		// Bob creates a job
		Job bobJob = engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "bob"), bobCtx);

		// Alice sees only her job
		var aliceJobs = engine.getJobs(aliceCtx);
		assertEquals(1, aliceJobs.count(), "Alice should see only her own job");
		assertNotNull(aliceJobs.get(aliceJob.getID()));

		// Bob sees only his job
		var bobJobs = engine.getJobs(bobCtx);
		assertEquals(1, bobJobs.count(), "Bob should see only his own job");
		assertNotNull(bobJobs.get(bobJob.getID()));
	}

	@Test
	public void testInternalSeesAllJobs() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "alice"), aliceCtx);
		engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "bob"), bobCtx);

		var allJobs = engine.getJobs(RequestContext.INTERNAL);
		assertTrue(allJobs.count() >= 2, "Internal should see all jobs");
	}

	@Test
	public void testGetJobDataDeniedForNonOwner() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		Job aliceJob = engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "hello"), aliceCtx);

		// Alice can see her job
		assertNotNull(engine.getJobData(aliceJob.getID(), aliceCtx));

		// Bob cannot
		assertThrows(SecurityException.class,
			() -> engine.getJobData(aliceJob.getID(), bobCtx),
			"Non-owner should get SecurityException");
	}

	@Test
	public void testCancelJobDeniedForNonOwner() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		Job aliceJob = engine.invokeOperation(
			Strings.create("test:delay"), Maps.of("delay", 10000), aliceCtx);

		// Bob cannot cancel Alice's job
		assertThrows(SecurityException.class,
			() -> engine.cancelJob(aliceJob.getID(), bobCtx));

		// Alice can cancel her own job
		assertNotNull(engine.cancelJob(aliceJob.getID(), aliceCtx));
	}

	@Test
	public void testJobWithoutCallerInvisibleToExternal() {
		// Create a job without caller (internal/programmatic)
		Job internalJob = engine.invokeOperation(
			Strings.create("test:echo"), Maps.of("message", "internal"));

		RequestContext aliceCtx = RequestContext.of(ALICE_DID);

		// Alice cannot see the internal job
		var aliceJobs = engine.getJobs(aliceCtx);
		assertNull(aliceJobs.get(internalJob.getID()),
			"Venue-internal jobs should not be visible to external callers");

		// Internal can see it
		var internalJobs = engine.getJobs(RequestContext.INTERNAL);
		assertNotNull(internalJobs.get(internalJob.getID()),
			"Internal should see venue-internal jobs");
	}
}
