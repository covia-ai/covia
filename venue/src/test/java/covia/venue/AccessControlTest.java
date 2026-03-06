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
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		Job job = engine.jobs().invokeOperation(
			"test:echo", Maps.of("message", "hello"), aliceCtx);
		assertNotNull(job, "Should be able to invoke with authenticated context");

		// Verify caller DID flows through to job record
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> jobData = (AMap<AString, ACell>) job.getData();
		ACell caller = jobData.get(Fields.CALLER);
		assertNotNull(caller, "Job record should contain caller field");
		assertEquals(ALICE_DID, caller);
	}

	@Test
	public void testInvokeWithNullCallerThrows() {
		// callerDID is required — null should throw IllegalArgumentException
		assertThrows(IllegalArgumentException.class,
			() -> engine.jobs().invokeOperation(
				"test:echo", Maps.of("message", "hello"), RequestContext.ANONYMOUS));
	}

	// ========== Engine Integration: Job Scoping ==========

	@Test
	public void testGetJobsScopedByCaller() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		// Alice creates a job
		Job aliceJob = engine.jobs().invokeOperation(
			"test:echo", Maps.of("message", "alice"), aliceCtx);
		// Bob creates a job
		Job bobJob = engine.jobs().invokeOperation(
			"test:echo", Maps.of("message", "bob"), bobCtx);

		// Alice sees only her job
		var aliceJobs = engine.jobs().getJobs(aliceCtx);
		assertEquals(1, aliceJobs.count(), "Alice should see only her own job");
		assertNotNull(aliceJobs.get(aliceJob.getID()));

		// Bob sees only his job
		var bobJobs = engine.jobs().getJobs(bobCtx);
		assertEquals(1, bobJobs.count(), "Bob should see only his own job");
		assertNotNull(bobJobs.get(bobJob.getID()));
	}

	@Test
	public void testInternalSeesVenueJobs() {
		// Internal requests see the venue's own DID jobs
		engine.jobs().invokeOperation(
			"test:echo", Maps.of("message", "venue-internal"), RequestContext.INTERNAL);

		var venueJobs = engine.jobs().getJobs(RequestContext.INTERNAL);
		assertTrue(venueJobs.count() >= 1, "Internal should see venue's own jobs");
	}

	@Test
	public void testGetJobDataDeniedForNonOwner() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		// Use a non-completing job so it stays in activeJobs for access control check
		Job aliceJob = engine.jobs().invokeOperation(
			"test:never", Maps.of("message", "hello"), aliceCtx);

		// Alice can see her job
		assertNotNull(engine.jobs().getJobData(aliceJob.getID(), aliceCtx));

		// Bob cannot
		assertThrows(SecurityException.class,
			() -> engine.jobs().getJobData(aliceJob.getID(), bobCtx),
			"Non-owner should get SecurityException");

		// Clean up
		engine.jobs().cancelJob(aliceJob.getID(), aliceCtx);
	}

	@Test
	public void testCancelJobDeniedForNonOwner() {
		RequestContext aliceCtx = RequestContext.of(ALICE_DID);
		RequestContext bobCtx = RequestContext.of(BOB_DID);

		Job aliceJob = engine.jobs().invokeOperation(
			"test:delay", Maps.of("delay", 10000), aliceCtx);

		// Bob cannot cancel Alice's job
		assertThrows(SecurityException.class,
			() -> engine.jobs().cancelJob(aliceJob.getID(), bobCtx));

		// Alice can cancel her own job
		assertNotNull(engine.jobs().cancelJob(aliceJob.getID(), aliceCtx));
	}

	@Test
	public void testVenueJobInvisibleToExternalUser() {
		// Create a job with the venue's own DID (internal/programmatic)
		Job venueJob = engine.jobs().invokeOperation(
			"test:echo", Maps.of("message", "internal"), RequestContext.INTERNAL);

		RequestContext aliceCtx = RequestContext.of(ALICE_DID);

		// Alice cannot see the venue's job in her job list
		var aliceJobs = engine.jobs().getJobs(aliceCtx);
		assertNull(aliceJobs.get(venueJob.getID()),
			"Venue jobs should not be visible to external callers");

		// Internal sees venue's own jobs
		var internalJobs = engine.jobs().getJobs(RequestContext.INTERNAL);
		assertNotNull(internalJobs.get(venueJob.getID()),
			"Internal should see venue's own jobs");
	}
}
