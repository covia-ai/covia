package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.data.prim.CVMBool;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;

/** Action rights, not attribution or raw storage permissions, govern Job APIs. */
class JobCapabilityTest {
	private final Engine engine = TestEngine.ENGINE;
	private final AKeyPair aliceKey = AKeyPair.generate();
	private final AKeyPair bobKey = AKeyPair.generate();
	private final AString alice = UCAN.toDIDKey(aliceKey.getAccountKey());
	private final RequestContext owner = RequestContext.of(alice);
	private final RequestContext bob = RequestContext.of(UCAN.toDIDKey(bobKey.getAccountKey()));

	private RequestContext grant(Job job, AString ability) {
		return grant(Capability.create(JobManager.resource(alice, job.getID()), ability), 300);
	}

	private RequestContext grant(AMap<AString, ACell> cap, long lifetime) {
		AString jwt = UCAN.createJWT(aliceKey, bobKey.getAccountKey(),
			engine.currentTimestamp().longValue() / 1000 + lifetime, Vectors.of(cap), Vectors.empty());
		return engine.withTransportGrants(bob, Vectors.of(jwt));
	}

	@Test void independentDelegatedRightsAndAcknowledgements() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.of("private", "input"), owner);
		Job sibling = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		try {
			RequestContext reader = grant(job, Abilities.JOB_READ);
			assertNotNull(engine.jobs().getJobData(job.getID(), reader));
			assertThrows(AuthException.class, () -> engine.jobs().getJobData(sibling.getID(), reader));
			assertThrows(AuthException.class, () -> engine.jobs().cancelJob(job.getID(), reader));
			assertThrows(AuthException.class, () -> engine.jobs().deliverMessage(job.getID(), Maps.empty(), reader));
			RequestContext manager = grant(job, Abilities.JOB_MANAGE);
			assertThrows(AuthException.class, () -> engine.jobs().getJobData(job.getID(), manager));
			assertThrows(AuthException.class, () -> engine.jobs().deliverMessage(job.getID(), Maps.empty(), manager));
			assertEquals(JobManager.acknowledgement(job.getID()), engine.jobs().pauseJob(job.getID(), manager));
			assertEquals(Status.PAUSED, job.getStatus());
			assertEquals(JobManager.acknowledgement(job.getID()), engine.jobs().resumeJob(job.getID(), manager));
			assertEquals(Status.STARTED, job.getStatus());
			AMap<AString, ACell> ack = engine.jobs().cancelJob(job.getID(), manager);
			assertEquals(Maps.of(Fields.ID, job.getID(), Fields.ACCEPTED, CVMBool.TRUE), ack);
			assertEquals(Status.CANCELLED, job.getStatus());
		} finally { job.cancel(); sibling.cancel(); }
	}

	@Test void messagePermissionDoesNotGrantReadOrManage() {
		Job chat = engine.jobs().invokeOperation("v/test/ops/chat", Maps.empty(), owner);
		try {
			RequestContext messenger = grant(chat, Abilities.JOB_MESSAGE);
			assertThrows(AuthException.class, () -> engine.jobs().getJobData(chat.getID(), messenger));
			assertThrows(AuthException.class, () -> engine.jobs().cancelJob(chat.getID(), messenger));
			engine.jobs().deliverMessage(chat.getID(), Maps.of(Fields.CONTENT, "done"), messenger);
			assertEquals(Status.COMPLETE, chat.getStatus());
		} finally { chat.cancel(); }
	}

	@Test void retainedJobsUseExplicitOwnerWithoutImpersonation() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("result", 42), owner);
		job.awaitResult(5000);
		RequestContext reader = grant(job, Abilities.JOB_READ);
		engine.jobs().deleteJob(job.getID()); // evict only; durable record remains
		assertNull(engine.jobs().getJobData(job.getID(), reader));
		assertEquals(job.getOutput(), engine.jobs().getJob(job.getID(), reader, alice).getOutput());
		assertNotEquals(alice, reader.getUserDID());
		assertThrows(AuthException.class, () -> engine.jobs().getJobData(job.getID(), bob, alice));
		RequestContext manager = grant(job, Abilities.JOB_MANAGE);
		assertEquals(JobManager.acknowledgement(job.getID()), engine.jobs().cancelJob(job.getID(), manager, null, alice));
		assertTrue(engine.jobs().deleteJob(job.getID(), manager, alice));
	}

	@Test void crudCreatorAndExpiredProofsDoNotGrantJobAuthority() {
		RequestContext creator = RequestContext.ofAgent(alice, Strings.create("worker")).withCaps(Vectors.empty());
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		try {
			AMap<AString, ACell> attributed = job.getData().assoc(Fields.CALLER, creator.getCallerDID()).assoc(Fields.OWNER, alice);
			assertFalse(engine.getAccessControl().canAccessJob(creator, attributed));
			for (AString ability : new AString[]{Capability.CRUD_READ, Capability.CRUD_WRITE, Capability.CRUD_DELETE}) {
				RequestContext raw = grant(job, ability);
				assertThrows(AuthException.class, () -> engine.jobs().getJobData(job.getID(), raw));
				assertThrows(AuthException.class, () -> engine.jobs().cancelJob(job.getID(), raw));
				assertThrows(AuthException.class, () -> engine.jobs().deliverMessage(job.getID(), Maps.empty(), raw));
			}
			RequestContext expired = grant(Capability.create(JobManager.resource(alice, job.getID()), Abilities.JOB_READ), -1);
			assertThrows(AuthException.class, () -> engine.jobs().getJobData(job.getID(), expired));
			assertThrows(AuthException.class, () -> engine.jobs().getJobs(owner.withCaps(Vectors.of(Capability.create(JobManager.resource(alice, job.getID()), Abilities.JOB_READ)))));
		} finally { job.cancel(); }
	}

	@Test void hostBoundJobGrantChecksTheConfiguredVenueOrigin() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		try {
			AMap<AString, ACell> cap = Capability.create(JobManager.resource(alice, job.getID()), Abilities.JOB_READ);
			RequestContext here = grant(cap.assoc(Capability.NB, Maps.of(Fields.URL, engine.config().getBaseUrl())), 300);
			assertNotNull(engine.jobs().getJobData(job.getID(), here));
			RequestContext elsewhere = grant(cap.assoc(Capability.NB, Maps.of(Fields.URL, "https://elsewhere.example/")), 300);
			assertThrows(AuthException.class, () -> engine.jobs().getJobData(job.getID(), elsewhere));
		} finally { job.cancel(); }
	}

	@Test void resultDeliveryRechecksTheCurrentScope() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		var caps = new java.util.concurrent.atomic.AtomicReference<AVector<ACell>>(Vectors.of(
			Capability.create(JobManager.resource(alice, job.getID()), Abilities.JOB_READ)));
		RequestContext reader = owner.readingScope(resource -> caps.get());
		var result = engine.jobs().awaitResult(job.getID(), reader, alice);
		caps.set(Vectors.empty());
		job.completeWith(Strings.create("private result"));
		var denied = assertThrows(java.util.concurrent.CompletionException.class, result::join);
		assertInstanceOf(AuthException.class, denied.getCause());
		assertEquals(Strings.create("private result"), job.getOutput());
	}

	@Test void separateAuthorityDeterminesJobRights() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo", Maps.empty(), owner);
		job.awaitResult(5000);
		assertNotNull(engine.jobs().getJobData(job.getID(), bob.withAuthorityOf(owner), alice));
		assertThrows(AuthException.class,
			() -> engine.jobs().getJobData(job.getID(), owner.withAuthorityOf(bob)));
		assertNotNull(engine.jobs().getJobData(job.getID(), owner.withAuthorityOf(grant(job, Abilities.JOB_READ))));
		assertThrows(AuthException.class,
			() -> engine.jobs().cancelJob(job.getID(), owner.withAuthorityOf(grant(job, Abilities.JOB_READ))));
	}
}
