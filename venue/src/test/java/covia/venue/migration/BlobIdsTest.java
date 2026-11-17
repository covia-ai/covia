package covia.venue.migration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import covia.adapter.HITLAdapter;
import covia.api.Fields;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.Agent;
import covia.venue.HitlRequest;
import covia.venue.JobIndex;
import covia.venue.Session;
import covia.venue.Task;
import covia.venue.TestAgents;
import covia.venue.User;
import covia.venue.VenueState;

/** The {@link BlobIds} part of venue step 2 → 3 as a function of the venue value. */
class BlobIdsTest {
	private static final AString VENUE = Strings.create("did:test:venue");
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final MigrationStep STEP = new BlobIds();

	private static final Blob SESSION = Blob.fromHex("51".repeat(16));
	private static final Blob TASK = Blob.fromHex("01".repeat(16));
	private static final Blob CHAT = Blob.fromHex("02".repeat(16));
	private static final Blob CALL = Blob.fromHex("03".repeat(16));
	private static final Blob ASK = Blob.fromHex("04".repeat(16));
	private static final Blob COMPACTION = Blob.fromHex("05".repeat(16));
	private static final Blob MIRROR = Blob.fromHex("06".repeat(16));
	private static final Blob REMOTE = Blob.fromHex("07".repeat(16));
	/** A remote agent's own session id, as its venue gave it. */
	private static final AString REMOTE_SESSION = Strings.create("ctx-given-elsewhere");
	/** The expiry event of the request {@code ASK} asks, and an event of another operation. */
	private static final Blob EXPIRY = Blob.fromHex("7f".repeat(8) + "08".repeat(8));
	private static final Blob OTHER_EVENT = Blob.fromHex("7f".repeat(8) + "09".repeat(8));
	private static final AString CONTINUATION_IDS = Strings.create("continuationIds");

	private static AString hex(Blob id) {
		return Strings.create(id.toHexString());
	}

	/** An envelope as a version 2 venue queued it: its ids as hex, with the session it was queued on. */
	private static AMap<AString, ACell> legacyEnvelope(Blob job) {
		AMap<AString, ACell> envelope = Maps.of(Fields.CALLER, ALICE, Fields.SESSION_ID, hex(SESSION), Fields.MESSAGE, "hi");
		return (job == null) ? envelope : envelope.assoc(Fields.JOB_ID, hex(job));
	}

	/** A version 2 venue value whose agent holds every id the part converts as hex text. */
	private static ACell legacy() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE).assoc(Covia.VERSION, CVMLong.create(2)));
		User alice = venue.users().ensure(ALICE);
		Agent worker = TestAgents.ensure(alice, Strings.create("worker"), Maps.empty(), null);
		AMap<AString, ACell> turn = Maps.of(Agent.K_ROLE, Agent.ROLE_USER, Agent.K_CONTENT, "hi", Fields.JOB_ID, hex(CHAT));
		AMap<AString, ACell> segment = Maps.of(Strings.create("summary"), "earlier", Fields.ITEMS, Vectors.of(turn),
			Fields.JOB_ID, hex(COMPACTION));
		AMap<AString, ACell> session = Maps.of(
			Agent.KEY_PENDING, Vectors.of(legacyEnvelope(CHAT), legacyEnvelope(null)),
			Agent.KEY_FRAMES, Vectors.of(Maps.of(Fields.HISTORY, Vectors.of(segment, turn))));
		AMap<AString, ACell> timelineEntry = Maps.of(
			Fields.SESSION_ID, hex(SESSION),
			Fields.TASKS, Vectors.of(Maps.of(Fields.JOB_ID, hex(TASK), Fields.INPUT, "work")),
			Fields.PENDING, Vectors.of(Maps.of(Fields.JOB_ID, hex(ASK), Fields.SNAPSHOT, Maps.of(Fields.TASK_ID, hex(TASK)))),
			Fields.MESSAGES, Vectors.of(legacyEnvelope(CHAT)),
			Fields.TASK_RESULTS, Maps.of(hex(TASK), Maps.of(Fields.STATUS, "COMPLETE")));
		worker.cursor().set(worker.getRecord()
			.assoc(Agent.KEY_TASKS, Index.<Blob, ACell>none().assoc(TASK, Maps.of(
				Fields.INPUT, "work", Fields.SESSION_ID, hex(SESSION),
				Task.KEY_CONTINUATIONS, Vectors.of(legacyEnvelope(null).assoc(Fields.TASK_ID, Strings.create("0x" + hex(TASK)))),
				CONTINUATION_IDS, Vectors.of(hex(CALL)))))
			.assoc(Agent.KEY_IN_CYCLE, Maps.of(Strings.create("epoch"), Blob.fromHex("09"), Fields.SESSION_ID, hex(SESSION)))
			.assoc(Agent.KEY_PENDING, Index.<Blob, ACell>none()
				.assoc(CALL, Maps.of(Fields.SESSION_ID, hex(SESSION), Fields.TASK_ID, hex(TASK)))
				.assoc(ASK, Maps.of(Fields.TASK_ID, hex(TASK))))
			.assoc(Agent.KEY_RESOLVED, Index.<Blob, ACell>none()
				.assoc(TASK, Maps.of(Fields.ID, TASK, Fields.STATUS, "COMPLETE", Fields.SESSION_ID, hex(SESSION))))
			.assoc(Agent.KEY_TIMELINE, Vectors.of(timelineEntry))
			.assoc(Agent.KEY_SESSIONS, Index.<Blob, ACell>none().assoc(SESSION, session)));
		alice.jobs().put(TASK, Maps.of(Fields.ID, TASK, Fields.ADAPTER, "agent", Fields.SESSION_ID, hex(SESSION)));
		alice.jobs().put(CHAT, Maps.of(Fields.ID, CHAT, Fields.ADAPTER, "agent", Fields.SESSION_ID, hex(SESSION),
			Fields.OUTPUT, Maps.of(Fields.SESSION_ID, hex(SESSION), Fields.RESPONSE, "hello")));
		alice.jobs().put(MIRROR, Maps.of(Fields.ID, MIRROR, Fields.ADAPTER, "agent", BlobIds.MIRROR_OF, hex(CALL)));
		alice.jobs().put(REMOTE, Maps.of(Fields.ID, REMOTE, Fields.ADAPTER, "remoteagent", Fields.SESSION_ID, REMOTE_SESSION));
		Covia.entry(alice.cursor(), Namespace.H).set(Maps.of(Covia.WORKSPACE_DATA, Maps.of(hex(ASK),
			Maps.of(Hitl.ID, hex(ASK), Hitl.STATUS, Hitl.OPEN, Fields.SESSION_ID, hex(SESSION)))));
		ACell value = RT.assocIn(venue.get(), Index.of(ASK, Maps.of(JobIndex.OWNER, ALICE,
			JobIndex.ADAPTER_DATA, Maps.of(Fields.SESSION_ID, hex(SESSION), Strings.create("expiryEvent"), EXPIRY))), Covia.JOBS);
		return RT.assocIn(value, Maps.of(BlobIds.EVENTS, Index.<Blob, ACell>none()
			.assoc(EXPIRY, Maps.of(Fields.OP, HITLAdapter.EXPIRE_OP, Fields.INPUT, Maps.of(Hitl.USER, ALICE, Hitl.ID, hex(ASK))))
			.assoc(OTHER_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.INPUT, Maps.of(Hitl.ID, "its own")))), Covia.SCHEDULE);
	}

	private static AMap<AString, ACell> job(ACell value, Blob id) {
		return VenueState.create(AKeyPair.generate()).rebind(Cursors.root(value)).users().user(ALICE).jobs().getRecord(id);
	}

	private static Agent worker(ACell value) {
		return VenueState.create(AKeyPair.generate()).rebind(Cursors.root(value)).users().user(ALICE).agent("worker");
	}

	@Test void anAgentsRecordHoldsItsIdsAsBlobs() {
		Agent worker = worker(STEP.apply(legacy()));
		assertEquals(SESSION, Task.sessionId(worker.getTasks().get(TASK)));
		assertEquals(SESSION, RT.getIn(worker.getRecord(), Agent.KEY_IN_CYCLE, Fields.SESSION_ID));
		assertEquals(Maps.of(Fields.SESSION_ID, SESSION, Fields.TASK_ID, TASK), worker.getPending().get(CALL));
		assertTrue(worker.hasPendingFor(TASK), "an open wait is found by its task");
		assertEquals(SESSION, RT.getIn(worker.getResolved().get(TASK), Fields.SESSION_ID));
	}

	@Test void aQueuedMessageKeepsOnlyItsChatJob() {
		Agent worker = worker(STEP.apply(legacy()));
		AMap<AString, ACell> chat = RT.ensureMap(worker.session(SESSION).pending().get(0));
		assertEquals(CHAT, Session.chatJob(chat));
		assertEquals(Maps.of(Fields.CALLER, ALICE, Fields.MESSAGE, "hi", Fields.JOB_ID, CHAT), chat,
			"the session it is queued on says where it belongs");
		assertEquals(Maps.of(Fields.CALLER, ALICE, Fields.MESSAGE, "hi"), worker.session(SESSION).pending().get(1));
		assertEquals(Maps.of(Fields.CALLER, ALICE, Fields.MESSAGE, "hi"),
			RT.getIn(worker.getTasks().get(TASK), Task.KEY_CONTINUATIONS, 0), "nor does a task's");
		assertEquals(Vectors.of(hex(CALL)), RT.getIn(worker.getTasks().get(TASK), CONTINUATION_IDS),
			"message ids are the sender's, compared as given");
	}

	@Test void aSessionsTurnsNameTheirJobsAsBlobs() {
		Agent worker = worker(STEP.apply(legacy()));
		ACell history = RT.getIn(worker.session(SESSION).frames().get(0), Fields.HISTORY);
		assertEquals(COMPACTION, RT.getIn(history, 0, Fields.JOB_ID));
		assertEquals(CHAT, RT.getIn(history, 0, Fields.ITEMS, 0, Fields.JOB_ID), "a compacted segment's turns too");
		assertEquals(CHAT, RT.getIn(history, 1, Fields.JOB_ID));
		assertEquals(Strings.create("hi"), RT.getIn(history, 1, Agent.K_CONTENT), "what the model was shown is left as written");
	}

	@Test void aTimelineEntryNamesItsWorkByBlob() {
		ACell entry = worker(STEP.apply(legacy())).getRecord().get(Agent.KEY_TIMELINE);
		entry = ((convex.core.data.AVector<?>) entry).get(0);
		assertEquals(SESSION, RT.getIn(entry, Fields.SESSION_ID));
		assertEquals(TASK, RT.getIn(entry, Fields.TASKS, 0, Fields.JOB_ID));
		assertEquals(ASK, RT.getIn(entry, Fields.PENDING, 0, Fields.JOB_ID));
		assertEquals(TASK, RT.getIn(entry, Fields.PENDING, 0, Fields.SNAPSHOT, Fields.TASK_ID));
		assertEquals(CHAT, RT.getIn(entry, Fields.MESSAGES, 0, Fields.JOB_ID));
		assertEquals(Index.of(TASK, Maps.of(Fields.STATUS, "COMPLETE")), RT.getIn(entry, Fields.TASK_RESULTS),
			"task results are keyed by task");
	}

	@Test void aJobRecordNamesItsAgentSessionAndMirroredCallAsBlobs() {
		ACell migrated = STEP.apply(legacy());
		assertEquals(SESSION, job(migrated, TASK).get(Fields.SESSION_ID), "a request names its session");
		assertEquals(SESSION, job(migrated, CHAT).get(Fields.SESSION_ID), "a chat names its session");
		assertEquals(Maps.of(Fields.SESSION_ID, hex(SESSION), Fields.RESPONSE, "hello"), job(migrated, CHAT).get(Fields.OUTPUT),
			"a finished job's result is what its caller was given");
		assertEquals(CALL, job(migrated, MIRROR).get(BlobIds.MIRROR_OF), "a mirror names its call");
		assertEquals(REMOTE_SESSION, job(migrated, REMOTE).get(Fields.SESSION_ID),
			"another adapter's session id is another system's, kept as given");
	}

	@Test void aHitlRequestIsKeyedByItsJobAndNamesItsIdsAsBlobs() {
		ACell migrated = STEP.apply(legacy());
		User alice = VenueState.create(AKeyPair.generate()).rebind(Cursors.root(migrated)).users().user(ALICE);
		assertEquals(Maps.of(Hitl.ID, ASK, Hitl.STATUS, Hitl.OPEN, Fields.SESSION_ID, SESSION), alice.inbox().get(ASK));
		assertEquals(1, alice.inbox().getAll().count());
		assertEquals(SESSION, RT.getIn(migrated, Covia.JOBS, ASK, JobIndex.ADAPTER_DATA, Fields.SESSION_ID),
			"the session a request's job entry keeps");
		assertEquals(ASK, RT.getIn(migrated, Covia.SCHEDULE, BlobIds.EVENTS, EXPIRY, Fields.INPUT, Hitl.ID),
			"the request an expiry is given");
		assertEquals(Strings.create("its own"), RT.getIn(migrated, Covia.SCHEDULE, BlobIds.EVENTS, OTHER_EVENT, Fields.INPUT, Hitl.ID),
			"another operation's input is its own");
	}

	@Test void anInboxKeyThatIsNotAnIdIsRefused() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE));
		Covia.entry(venue.users().ensure(ALICE).cursor(), Namespace.H)
			.set(Maps.of(Covia.WORKSPACE_DATA, Maps.of("req-1", Maps.of(Hitl.STATUS, Hitl.OPEN))));
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> STEP.apply(venue.get()));
		assertTrue(refused.getMessage().startsWith(BlobIds.NOT_AN_ID));
	}

	@Test void migratingAgainChangesNothing() {
		ACell migrated = STEP.apply(legacy());
		assertSame(migrated, STEP.apply(migrated));
	}

	@Test void aVenueWithNothingToConvertComesBackAsTheSameCell() {
		assertNull(STEP.apply(null), "a fresh venue");
		ACell noUsers = Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE);
		assertSame(noUsers, STEP.apply(noUsers));
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE));
		Agent agent = TestAgents.ensure(venue.users().ensure(ALICE), Strings.create("worker"), Maps.empty(), null);
		agent.addTaskInSession(SESSION, ALICE, null, TASK, Maps.of(Fields.INPUT, "work", Fields.SESSION_ID, SESSION));
		venue.users().user(ALICE).inbox().open(HitlRequest.record(ASK, ALICE, null, Strings.create("worker"), SESSION, null,
			Strings.create("Go ahead?"), null, Vectors.empty(), 1, null));
		assertSame(venue.get(), STEP.apply(venue.get()));
	}

	@Test void anIdThatIsNotOneIsRefused() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE));
		Agent agent = TestAgents.ensure(venue.users().ensure(ALICE), Strings.create("worker"), Maps.empty(), null);
		agent.cursor().set(agent.getRecord().assoc(Agent.KEY_TASKS,
			Index.<Blob, ACell>none().assoc(TASK, Maps.of(Fields.SESSION_ID, "not an id"))));
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> STEP.apply(venue.get()));
		assertTrue(refused.getMessage().startsWith(BlobIds.NOT_AN_ID));
	}
}
