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
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;

/** The {@link JobStates} part of venue step 3 → 4 as a function of the venue value, after {@link OneCaller}. */
class JobStatesTest {
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString CONTROLLER = Strings.create("did:test:carol-controller");
	private static final AString WORKER = Principals.agentDID(ALICE, Strings.create("worker"));
	private static final MigrationStep STEP = MigrationStep.of(new OneCaller(), new JobStates());

	private static final Blob OWN = Blob.fromHex("01".repeat(16));
	private static final Blob AGENTS = Blob.fromHex("02".repeat(16));
	private static final Blob BARE = Blob.fromHex("03".repeat(16));
	private static final Blob SESSION = Blob.fromHex("5e".repeat(16));
	private static final Blob OWN_EVENT = Blob.fromHex("7f".repeat(8) + "21".repeat(8));
	private static final Blob BARE_EVENT = Blob.fromHex("7f".repeat(8) + "22".repeat(8));

	private static final ACell PROOFS = Vectors.of(Strings.create("a proof"));

	private static AMap<AString, ACell> started(AString caller) {
		return Maps.of(Fields.STATUS, Status.STARTED, Fields.CALLER, caller);
	}

	/**
	 * A version 2 venue value: Alice's job that kept her proofs and a session,
	 * her agent's job that kept, as version 2 did, the controller it acted
	 * with, a job whose adapter kept nothing, and two events.
	 */
	private static ACell legacy() {
		Index<Blob, ACell> jobs = Index.<Blob, ACell>none()
			.assoc(OWN, started(ALICE))
			.assoc(AGENTS, started(WORKER).assoc(Fields.OWNER, ALICE))
			.assoc(BARE, started(ALICE));
		AMap<AString, ACell> controllers = Maps.of(Fields.CALLER, CONTROLLER, Fields.PROOFS, Vectors.of(Strings.create("its own")));
		Index<Blob, ACell> entries = Index.<Blob, ACell>none()
			.assoc(OWN, Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.PROOFS, PROOFS, Fields.SESSION_ID, SESSION)))
			.assoc(AGENTS, Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.AUTHORITY, controllers)))
			.assoc(BARE, Maps.of(JobIndex.OWNER, ALICE));
		Index<Blob, ACell> events = Index.<Blob, ACell>none()
			.assoc(OWN_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, ALICE, Fields.PROOFS, PROOFS))
			.assoc(BARE_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, WORKER, Fields.OWNER, ALICE,
				Fields.AUTHORITY, controllers));
		return Index.<Keyword, ACell>none()
			.assoc(Covia.USER_DATA, Maps.of(ALICE, Maps.of(Namespace.J, jobs)))
			.assoc(Covia.JOBS, entries)
			.assoc(Covia.SCHEDULE, Maps.of(JobStates.EVENTS, events));
	}

	private static AMap<AString, ACell> frame(AString authority) {
		return Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, authority));
	}

	private static ACell at(ACell venue, Object... path) {
		return RT.getIn(venue, path);
	}

	@Test void anUnfinishedJobKeepsItsCallersFrameWithWhatItKept() {
		ACell venue = STEP.apply(legacy());
		assertEquals(frame(ALICE).assoc(Fields.PROOFS, PROOFS).assoc(Fields.SESSION_ID, SESSION),
			at(venue, Covia.JOBS, OWN, JobIndex.CONTEXT), "a record that names no caller is its account's");
		assertEquals(frame(WORKER), at(venue, Covia.JOBS, AGENTS, JobIndex.CONTEXT),
			"an agent's job acts as the agent, never with the controller version 2 kept");
		assertEquals(Maps.of(JobIndex.OWNER, ALICE), at(venue, Covia.JOBS, BARE),
			"a job whose adapter kept nothing keeps no state, so is never carried on as its caller");
	}

	@Test void anEventKeepsItsCallersFrameAsItsContext() {
		ACell venue = STEP.apply(legacy());
		AMap<AString, ACell> own = RT.ensureMap(at(venue, Covia.SCHEDULE, JobStates.EVENTS, OWN_EVENT));
		assertEquals(frame(ALICE).assoc(Fields.PROOFS, PROOFS), own.get(Fields.CONTEXT));
		assertFalse(own.containsKey(Fields.PROOFS), "its proofs are in its state");
		AMap<AString, ACell> agents = RT.ensureMap(at(venue, Covia.SCHEDULE, JobStates.EVENTS, BARE_EVENT));
		assertEquals(frame(WORKER), agents.get(Fields.CONTEXT));
		assertFalse(agents.containsKey(Fields.AUTHORITY));
		assertEquals(WORKER, agents.get(Fields.CALLER), "it keeps naming its caller");
	}

	@Test void aCurrentVenueComesBackAsTheSameCell() {
		ACell once = STEP.apply(legacy());
		assertSame(once, new JobStates().apply(once));
	}

	/**
	 * A store a snapshot took to version 3 kept, as the authority its work
	 * acted with, the access token or callable that narrowed it.
	 */
	@Test void whatNarrowedSnapshotWorkStaysOnIt() {
		Blob viaToken = Blob.fromHex("04".repeat(16)), viaCallable = Blob.fromHex("05".repeat(16));
		Blob viaController = Blob.fromHex("06".repeat(16)), eventViaToken = Blob.fromHex("7f".repeat(8) + "23".repeat(8));
		AMap<AString, ACell> token = Maps.of(Fields.OWNER, ALICE, Fields.NAME, "tool", Fields.ID, Blob.fromHex("aa".repeat(16)));
		AMap<AString, ACell> callable = Maps.of(Fields.OWNER, ALICE, Fields.NAME, "shared");
		AMap<AString, ACell> controllers = Maps.of(Fields.CALLER, CONTROLLER, Fields.TOKEN, token);
		ACell snapshot = Index.<Keyword, ACell>none()
			.assoc(Covia.USER_DATA, Maps.of(ALICE, Maps.of(Namespace.J, Index.<Blob, ACell>none()
				.assoc(viaToken, started(ALICE)).assoc(viaCallable, started(WORKER).assoc(Fields.OWNER, ALICE))
				.assoc(viaController, started(WORKER).assoc(Fields.OWNER, ALICE)))))
			.assoc(Covia.JOBS, Index.<Blob, ACell>none()
				.assoc(viaToken, Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.AUTHORITY, Maps.of(Fields.TOKEN, token))))
				.assoc(viaCallable, Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.AUTHORITY, Maps.of(Fields.CALLABLE, callable))))
				.assoc(viaController, Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.AUTHORITY, controllers))))
			.assoc(Covia.SCHEDULE, Maps.of(JobStates.EVENTS, Index.<Blob, ACell>none().assoc(eventViaToken,
				Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, ALICE, Fields.AUTHORITY, Maps.of(Fields.TOKEN, token)))));

		ACell venue = STEP.apply(snapshot);
		assertEquals(frame(ALICE).assoc(Fields.TOKEN, token), at(venue, Covia.JOBS, viaToken, JobIndex.CONTEXT),
			"work a token narrowed keeps the token");
		assertEquals(Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, WORKER, Fields.CALLER, WORKER), Fields.CALLABLE, callable),
			at(venue, Covia.JOBS, viaCallable, JobIndex.CONTEXT), "a callable's call keeps the callable and its caller");
		assertEquals(frame(WORKER), at(venue, Covia.JOBS, viaController, JobIndex.CONTEXT),
			"a token on a controller goes with the controller");
		assertEquals(frame(ALICE).assoc(Fields.TOKEN, token),
			at(venue, Covia.SCHEDULE, JobStates.EVENTS, eventViaToken, Fields.CONTEXT), "so does an event's");
		assertSame(venue, STEP.apply(venue));
	}
}
