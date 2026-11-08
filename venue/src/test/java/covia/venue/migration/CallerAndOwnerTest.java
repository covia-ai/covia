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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.Principals;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.Agent;
import covia.venue.User;
import covia.venue.VenueState;

/** The {@link CallerAndOwner} part of venue step 1 → 2 as a function of the venue value. */
class CallerAndOwnerTest {
	private static final AString VENUE = Strings.create("did:test:venue");
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString BOB = Strings.create("did:test:bob");
	private static final AString WORKER = Principals.agentDID(ALICE, Strings.create("worker"));
	private static final AString RETIRED = Principals.agentDID(ALICE, Strings.create("retired"));
	private static final AString GHOST = Principals.agentDID(ALICE, Strings.create("ghost"));
	private static final AString ACTOR = Strings.create("actor");
	private static final AString BY = Strings.create("by");
	private static final AString FROM = Strings.create("from");
	private static final AString REQUESTER_USER = Strings.create("requesterUser");
	private static final AString USER = Strings.create("user");
	private static final AString OUTPUT_CONTEXT = Strings.create("outputContext");
	private static final AString CAPS = Strings.create("caps");
	private static final AString EVENTS = Strings.create("events");
	private static final AString BY_ACTOR = Strings.create("byActor");
	private static final MigrationStep STEP = new CallerAndOwner();

	private static final Blob AGENT_JOB = Blob.fromHex("01".repeat(16));
	private static final Blob OWN_JOB = Blob.fromHex("02".repeat(16));
	private static final Blob AGENT_EVENT = Blob.fromHex("03".repeat(16));
	private static final Blob DELEGATE_EVENT = Blob.fromHex("04".repeat(16));
	private static final Blob OWN_EVENT = Blob.fromHex("05".repeat(16));
	private static final Blob TASK = Blob.fromHex("06".repeat(16));
	private static final Blob OLD_TASK = Blob.fromHex("07".repeat(16));
	private static final Blob OWN_TASK = Blob.fromHex("08".repeat(16));

	/** A version 1 venue value holding every older shape the part converts. */
	private static ACell legacy() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE).assoc(Covia.VERSION, CVMLong.ONE));
		User alice = venue.users().ensure(ALICE);
		alice.cursor().path(Namespace.J).set(Index.<Blob, ACell>none()
			.assoc(AGENT_JOB, Maps.of(Fields.ID, AGENT_JOB, Fields.STATUS, "COMPLETE", Fields.CALLER, ALICE, ACTOR, WORKER))
			.assoc(OWN_JOB, Maps.of(Fields.ID, OWN_JOB, Fields.STATUS, "COMPLETE", Fields.CALLER, ALICE)));

		alice.inbox().put(Strings.create("agent"), Maps.of(Hitl.ID, "agent", FROM, WORKER, REQUESTER_USER, ALICE));
		alice.inbox().put(Strings.create("legacy-agent"), Maps.of(Hitl.ID, "legacy-agent", FROM, WORKER));
		alice.inbox().put(Strings.create("legacy-retired"), Maps.of(Hitl.ID, "legacy-retired", FROM, RETIRED));
		alice.inbox().put(Strings.create("legacy-ghost"), Maps.of(Hitl.ID, "legacy-ghost", FROM, GHOST));
		alice.inbox().put(Strings.create("stranger"), Maps.of(Hitl.ID, "stranger", FROM, BOB));

		Agent worker = alice.ensureAgent(Strings.create("worker"), Maps.empty(), null);
		worker.putRecord(worker.getRecord().assoc(Agent.KEY_TASKS, Index.<Blob, ACell>none()
			.assoc(TASK, Maps.of(OUTPUT_CONTEXT, Maps.of(Fields.CALLER, WORKER, USER, ALICE, CAPS, Maps.empty())))
			.assoc(OLD_TASK, Maps.of(OUTPUT_CONTEXT, Maps.of(Fields.CALLER, WORKER)))
			.assoc(OWN_TASK, Maps.of(OUTPUT_CONTEXT, Maps.of(Fields.CALLER, BOB, USER, BOB)))));
		Agent retired = alice.ensureAgent(Strings.create("retired"), Maps.empty(), null);
		retired.putRecord(retired.getRecord().assoc(Agent.KEY_STATUS, Agent.TERMINATED));

		AMap<AString, ACell> schedule = Maps.of(
			EVENTS, Index.<Blob, ACell>none()
				.assoc(AGENT_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.OWNER, ALICE, ACTOR, WORKER, BY, WORKER))
				.assoc(DELEGATE_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.OWNER, ALICE, ACTOR, BOB, BY, BOB))
				.assoc(OWN_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.OWNER, ALICE, BY, ALICE)),
			BY_ACTOR, Maps.of(WORKER, convex.core.data.Sets.of(AGENT_EVENT), BOB, convex.core.data.Sets.of(DELEGATE_EVENT)));
		return RT.assocIn(venue.get(), schedule, Covia.SCHEDULE);
	}

	private static VenueState read(ACell value) {
		return VenueState.create(AKeyPair.generate()).withCursor(Cursors.root(value));
	}

	@Test void jobsNameTheirCallerAndOwner() {
		User alice = read(STEP.apply(legacy())).users().user(ALICE);
		assertEquals(Maps.of(Fields.ID, AGENT_JOB, Fields.STATUS, "COMPLETE", Fields.CALLER, WORKER, Fields.OWNER, ALICE),
			alice.jobs().getRecord(AGENT_JOB));
		assertEquals(Maps.of(Fields.ID, OWN_JOB, Fields.STATUS, "COMPLETE", Fields.CALLER, ALICE),
			alice.jobs().getRecord(OWN_JOB), "a job its owner made names no other owner");
	}

	@Test void scheduledEventsNameTheirCallerAndOwner() {
		ACell migrated = STEP.apply(legacy());
		Index<?, ?> events = (Index<?, ?>) RT.getIn(migrated, Covia.SCHEDULE, EVENTS);
		assertEquals(Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, WORKER, Fields.OWNER, ALICE), events.get(AGENT_EVENT));
		assertEquals(Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, BOB, Fields.OWNER, ALICE), events.get(DELEGATE_EVENT));
		assertEquals(Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, ALICE), events.get(OWN_EVENT));
		assertEquals(RT.getIn(legacy(), Covia.SCHEDULE, BY_ACTOR), RT.getIn(migrated, Covia.SCHEDULE, BY_ACTOR),
			"the index already keys each event by its new caller");
	}

	@Test void hitlRequestsNameTheirCallerAndOwner() {
		User alice = read(STEP.apply(legacy())).users().user(ALICE);
		assertEquals(Maps.of(Hitl.ID, "agent", Hitl.CALLER, WORKER, Hitl.OWNER, ALICE),
			alice.inbox().get(Strings.create("agent")));
		assertEquals(Maps.of(Hitl.ID, "legacy-agent", Hitl.CALLER, WORKER, Hitl.OWNER, ALICE),
			alice.inbox().get(Strings.create("legacy-agent")),
			"a request naming only an agent that exists belongs to that agent's owner");
		assertEquals(Maps.of(Hitl.ID, "legacy-retired", Hitl.CALLER, RETIRED),
			alice.inbox().get(Strings.create("legacy-retired")), "nor a terminated one");
		assertEquals(Maps.of(Hitl.ID, "legacy-ghost", Hitl.CALLER, GHOST),
			alice.inbox().get(Strings.create("legacy-ghost")), "a name alone puts no principal in another namespace");
		assertEquals(Maps.of(Hitl.ID, "stranger", Hitl.CALLER, BOB), alice.inbox().get(Strings.create("stranger")));
	}

	@Test void outputSnapshotsNameTheirOwner() {
		Agent worker = read(STEP.apply(legacy())).users().user(ALICE).agent("worker");
		assertEquals(Maps.of(Fields.CALLER, WORKER, Fields.OWNER, ALICE),
			RT.getIn(worker.getTasks().get(TASK), OUTPUT_CONTEXT), "and keeps no copy of its caller's caps");
		assertEquals(Maps.of(Fields.CALLER, WORKER, Fields.OWNER, ALICE),
			RT.getIn(worker.getTasks().get(OLD_TASK), OUTPUT_CONTEXT), "one from before 0.10.0 takes the legacy namespace");
		assertEquals(Maps.of(Fields.CALLER, BOB), RT.getIn(worker.getTasks().get(OWN_TASK), OUTPUT_CONTEXT));
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
		venue.users().ensure(ALICE).cursor().path(Namespace.J)
			.set(Index.<Blob, ACell>none().assoc(OWN_JOB, Maps.of(Fields.ID, OWN_JOB, Fields.CALLER, ALICE)));
		assertSame(venue.get(), STEP.apply(venue.get()));
	}

	@Test void theLegacyNamespaceIsDecidedByTheVenuesAgents() {
		ACell venue = legacy();
		assertEquals(ALICE, CallerAndOwner.legacyNamespace(venue, WORKER));
		assertEquals(RETIRED, CallerAndOwner.legacyNamespace(venue, RETIRED));
		assertEquals(GHOST, CallerAndOwner.legacyNamespace(venue, GHOST));
		assertEquals(BOB, CallerAndOwner.legacyNamespace(venue, BOB));
	}
}
