package covia.venue.migration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Principals;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.Agent;
import covia.venue.JobIndex;

/** The {@link OneCaller} part of venue step 3 → 4 as a function of the venue value. */
class OneCallerTest {
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString BOB = Strings.create("did:test:bob-delegate");
	private static final AString CAROL = Strings.create("did:test:carol");
	private static final AString WORKER = Principals.agentDID(ALICE, Strings.create("worker"));
	private static final MigrationStep STEP = new OneCaller();

	private static final Blob OWN = Blob.fromHex("01".repeat(16));
	private static final Blob AGENTS = Blob.fromHex("02".repeat(16));
	private static final Blob DELEGATES = Blob.fromHex("03".repeat(16));
	private static final Blob DELEGATES_DONE = Blob.fromHex("04".repeat(16));
	private static final Blob SELF_ASK = Blob.fromHex("11".repeat(16));
	private static final Blob CAROLS_ASK = Blob.fromHex("12".repeat(16));
	private static final Blob AGENTS_ASK = Blob.fromHex("13".repeat(16));
	private static final Blob DELEGATES_ASK = Blob.fromHex("14".repeat(16));
	private static final Blob OWN_EVENT = Blob.fromHex("7f".repeat(8) + "21".repeat(8));
	private static final Blob AGENTS_EVENT = Blob.fromHex("7f".repeat(8) + "22".repeat(8));
	private static final Blob DELEGATES_EVENT = Blob.fromHex("7f".repeat(8) + "23".repeat(8));

	private static AMap<AString, ACell> job(AString caller, AString owner, AString status) {
		AMap<AString, ACell> job = Maps.of(Fields.STATUS, status, Fields.CALLER, caller);
		return (owner == null) ? job : job.assoc(Fields.OWNER, owner);
	}

	private static AMap<AString, ACell> request(AString caller, AString owner, AString status) {
		AMap<AString, ACell> request = Maps.of(Hitl.STATUS, status, Hitl.CALLER, caller);
		return (owner == null) ? request : request.assoc(Hitl.OWNER, owner);
	}

	private static AMap<AString, ACell> event(AString caller, AString owner) {
		AMap<AString, ACell> event = Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, caller);
		return (owner == null) ? event : event.assoc(Fields.OWNER, owner);
	}

	private static AMap<AString, ACell> inbox(Index<Blob, ACell> requests) {
		return Maps.of(Covia.WORKSPACE_DATA, requests);
	}

	/** A version 2 venue value holding each kind of record, as its own caller, an agent's and a delegate's. */
	private static ACell legacy() {
		Index<Blob, ACell> jobs = Index.<Blob, ACell>none()
			.assoc(OWN, job(ALICE, null, Status.COMPLETE))
			.assoc(AGENTS, job(WORKER, ALICE, Status.STARTED))
			.assoc(DELEGATES, job(BOB, ALICE, Status.STARTED))
			.assoc(DELEGATES_DONE, job(BOB, ALICE, Status.COMPLETE));
		Index<Blob, ACell> alicesInbox = Index.<Blob, ACell>none()
			.assoc(SELF_ASK, request(ALICE, null, Hitl.OPEN))
			.assoc(CAROLS_ASK, request(CAROL, null, Hitl.OPEN))
			.assoc(AGENTS_ASK, request(WORKER, ALICE, Hitl.OPEN));
		Index<Blob, ACell> carolsInbox = Index.<Blob, ACell>none().assoc(DELEGATES_ASK, request(BOB, ALICE, Hitl.OPEN));
		AMap<AString, ACell> worker = Maps.of(Agent.KEY_TASKS, Index.<Blob, ACell>none()
			.assoc(DELEGATES, Maps.of(Fields.INPUT, "work for the delegate")).assoc(AGENTS, Maps.of(Fields.INPUT, "own work")));
		AMap<AString, ACell> alice = Maps.of(Namespace.J, jobs, Namespace.H, inbox(alicesInbox),
			Namespace.G, Maps.of(Strings.create("worker"), worker));
		AMap<AString, ACell> carol = Maps.of(Namespace.H, inbox(carolsInbox));
		Index<Blob, ACell> events = Index.<Blob, ACell>none()
			.assoc(OWN_EVENT, event(ALICE, null))
			.assoc(AGENTS_EVENT, event(WORKER, ALICE))
			.assoc(DELEGATES_EVENT, event(BOB, ALICE));
		return Index.<Keyword, ACell>none()
			.assoc(Covia.USER_DATA, Maps.of(ALICE, alice, CAROL, carol))
			.assoc(Covia.JOBS, Index.<Blob, ACell>none()
				.assoc(AGENTS, Maps.of(JobIndex.OWNER, ALICE)).assoc(DELEGATES, Maps.of(JobIndex.OWNER, ALICE)))
			.assoc(Covia.SCHEDULE, Maps.of(OneCaller.EVENTS, events, OneCaller.BY_ACTOR,
				Maps.of(WORKER, Sets.of(AGENTS_EVENT), BOB, Sets.of(DELEGATES_EVENT))));
	}

	private static ACell at(ACell venue, Object... path) {
		return RT.getIn(venue, path);
	}

	@Test void aRecordNamesOneCallerLeftOutWhereItIsTheAccount() {
		ACell venue = STEP.apply(legacy());
		assertEquals(Maps.of(Fields.STATUS, Status.COMPLETE), at(venue, Covia.USER_DATA, ALICE, Namespace.J, OWN));
		assertEquals(Maps.of(Fields.STATUS, Status.STARTED, Fields.CALLER, WORKER), at(venue, Covia.USER_DATA, ALICE, Namespace.J, AGENTS),
			"an agent's job names the agent, and no owner");
		ACell inbox = at(venue, Covia.USER_DATA, ALICE, Namespace.H, Covia.WORKSPACE_DATA);
		assertEquals(Maps.of(Hitl.STATUS, Hitl.OPEN), at(inbox, SELF_ASK), "a request the inbox's user asked itself names no caller");
		assertEquals(request(CAROL, null, Hitl.OPEN), at(inbox, CAROLS_ASK), "another user's request keeps its caller");
		assertEquals(request(WORKER, null, Hitl.OPEN), at(inbox, AGENTS_ASK));
		ACell events = at(venue, Covia.SCHEDULE, OneCaller.EVENTS);
		assertEquals(event(ALICE, null), at(events, OWN_EVENT), "an event, kept venue-wide, always names its caller");
		assertEquals(event(WORKER, null), at(events, AGENTS_EVENT));
		assertEquals(Sets.of(AGENTS_EVENT), at(venue, Covia.SCHEDULE, OneCaller.BY_ACTOR, WORKER));
	}

	@Test void aDelegatesUnfinishedWorkEndsAndItsRecordsKeepNamingIt() {
		ACell venue = STEP.apply(legacy());
		AMap<AString, ACell> ended = RT.ensureMap(at(venue, Covia.USER_DATA, ALICE, Namespace.J, DELEGATES));
		assertEquals(Maps.of(Fields.STATUS, Status.FAILED, Fields.CALLER, BOB,
			Fields.ERROR, Strings.create(OneCaller.DELEGATE_WORK_ENDED)), ended);
		assertNull(at(venue, Covia.JOBS, DELEGATES), "its entry goes");
		assertNotNull(at(venue, Covia.JOBS, AGENTS));
		assertNull(at(venue, Covia.USER_DATA, ALICE, Namespace.G, Strings.create("worker"), Agent.KEY_TASKS, DELEGATES),
			"and the task it was to an agent");
		assertNotNull(at(venue, Covia.USER_DATA, ALICE, Namespace.G, Strings.create("worker"), Agent.KEY_TASKS, AGENTS));
		assertEquals(job(BOB, null, Status.COMPLETE), at(venue, Covia.USER_DATA, ALICE, Namespace.J, DELEGATES_DONE),
			"a finished one keeps naming the delegate");
		assertEquals(request(BOB, null, Hitl.CANCELLED),
			at(venue, Covia.USER_DATA, CAROL, Namespace.H, Covia.WORKSPACE_DATA, DELEGATES_ASK),
			"its open request is cancelled in the inbox that keeps it");
		assertNull(at(venue, Covia.SCHEDULE, OneCaller.EVENTS, DELEGATES_EVENT), "its event goes");
		assertNull(at(venue, Covia.SCHEDULE, OneCaller.BY_ACTOR, BOB), "with its index entry");
	}

	@Test void aCurrentVenueComesBackAsTheSameCell() {
		ACell once = STEP.apply(legacy());
		assertSame(once, STEP.apply(once));
	}

	@Test void anImportConvertsAsTheAccountItLandsIn() {
		ACell alice = at(legacy(), Covia.USER_DATA, ALICE);
		ACell imported = OneCaller.userState(alice, ALICE);
		assertEquals(Maps.of(Hitl.STATUS, Hitl.OPEN), at(imported, Namespace.H, Covia.WORKSPACE_DATA, SELF_ASK));
		assertEquals(request(WORKER, null, Hitl.OPEN), at(imported, Namespace.H, Covia.WORKSPACE_DATA, AGENTS_ASK));
		assertSame(imported, OneCaller.userState(imported, ALICE));
	}
}
