package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.agent.GoalTreeContext;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.hitl.Hitl;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.CoviaPath;

/**
 * Venue migration end to end, in process: one venue built at version 0,
 * holding every older shape this build still supports for Covia and the
 * bundled adapters, goes through every venue version to the current one. The
 * eager parts are checked after the start-up transaction, and the lazy parts
 * after the venue next uses them. The older states are built here, never
 * read from a release fixture; {@link VenueUpgradeIT} opens those.
 */
class VenueMigrationEndToEndTest {

	/**
	 * The venue version this test seeds shapes for. A new venue version needs
	 * the shapes its step converts seeded here, and fails until it has them.
	 */
	private static final long SEEDED_THROUGH = 3;

	private static final AString USER = Strings.create("did:test:migration-e2e");
	private static final Path OLD_DELETIONS =CoviaPath.parse("w/user-deletions");
	/** Keyed by an adapter that never registers, so start-up leaves the deletion pending. */
	private static final ACell PENDING = Maps.of("unloaded", Maps.of("did:test:deleted", CVMLong.ONE));
	private static final ACell UNLOADED = Maps.of("note", "an unversioned adapter's state");
	private static final AString PREV = Strings.create("prev");
	private static final Blob JOB = Blob.fromHex("e2e0e2e0e2e0e2e0e2e0e2e0e2e0e2e0");
	private static final String SECRET = "E2E_SECRET";
	/** A connected account as the oauth adapter once kept it: a user secret named oauth/<provider>. */
	private static final String LEGACY_GRANT = "oauth/e2e";
	private static final String AGENT = "migration-e2e-agent";
	private static final Blob SESSION = Blob.fromHex("e2e1e2e1e2e1e2e1e2e1e2e1e2e1e2e1");
	private static final AString HISTORY_VERSION = Strings.create("historyVersion");
	/** Records of calls as version 1 named their caller and owner. */
	private static final Blob AGENT_JOB = Blob.fromHex("e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2");
	private static final Blob EVENT = Blob.fromHex("7fffffffffffffffe2e3e2e3e2e3e2e3");
	/** A HITL request the parked agent asked in its session, open, keyed by its job's id in hex. */
	private static final Blob ASK = Blob.fromHex("e2f0e2f0e2f0e2f0e2f0e2f0e2f0e2f0");
	private static final String PARKED = "migration-e2e-parked";
	private static final Blob PARKED_TASK = Blob.fromHex("e2e4e2e4e2e4e2e4e2e4e2e4e2e4e2e4");
	/** A job still running when a version 1 venue stopped, with no entry: that version kept none. */
	private static final Blob RUNNING_JOB = Blob.fromHex("e2e5e2e5e2e5e2e5e2e5e2e5e2e5e2e5");
	/** A grid hop that signed in as its caller, with the sign-in credential 0.10.0 kept in a venue secret. */
	private static final Blob REMOTE_JOB = Blob.fromHex("e2e6e2e6e2e6e2e6e2e6e2e6e2e6e2e6");
	private static final String REMOTE_TARGET = "https://peer.migration.test";
	/** A finished grid hop whose record showed how the venue followed it, as version 1 did. */
	private static final Blob FINISHED_REMOTE_JOB = Blob.fromHex("e2e7e2e7e2e7e2e7e2e7e2e7e2e7e2e7");
	/** A running grid hop whose connection secret is gone: nothing reaches its remote job again. */
	private static final Blob LOST_REMOTE_JOB = Blob.fromHex("e2e8e2e8e2e8e2e8e2e8e2e8e2e8e2e8");
	/** A running call to an agent on this venue, which kept no caller context then. */
	private static final Blob LOCAL_CALL = Blob.fromHex("e2e9e2e9e2e9e2e9e2e9e2e9e2e9e2e9");
	private static final String LOCAL_TASK = "e2eae2eae2eae2eae2eae2eae2eae2ea";
	/** Ids version 2 stored as hex text: a task's session, an open wait for it, a queued chat and a turn's job. */
	private static final Blob PARKED_SESSION = Blob.fromHex("e2ebe2ebe2ebe2ebe2ebe2ebe2ebe2eb");
	private static final Blob PARKED_WORK = Blob.fromHex("e2ece2ece2ece2ece2ece2ece2ece2ec");
	private static final Blob PARKED_ASK = Blob.fromHex("e2ede2ede2ede2ede2ede2ede2ede2ed");
	private static final Blob PARKED_CHAT = Blob.fromHex("e2eee2eee2eee2eee2eee2eee2eee2ee");
	private static final Blob TURN_JOB = Blob.fromHex("e2efe2efe2efe2efe2efe2efe2efe2ef");
	/** An admin/sudo delegate, and another user, of the 0.11.0 venue. */
	private static final AString DELEGATE = Strings.create("did:test:migration-e2e-delegate");
	private static final AString OTHER = Strings.create("did:test:migration-e2e-other");
	/** The delegate's unfinished job, its open request in the other user's inbox and its event, all for the user. */
	private static final Blob DELEGATE_JOB = Blob.fromHex("e2d0e2d0e2d0e2d0e2d0e2d0e2d0e2d0");
	private static final Blob DELEGATE_ASK = Blob.fromHex("e2d1e2d1e2d1e2d1e2d1e2d1e2d1e2d1");
	private static final Blob DELEGATE_EVENT = Blob.fromHex("7fffffffffffffffe2d2e2d2e2d2e2d2");
	/** A request the other user asked of the user. */
	private static final Blob OTHER_ASK = Blob.fromHex("e2d3e2d3e2d3e2d3e2d3e2d3e2d3e2d3");

	private static AString hex(Blob id) {
		return Strings.create(id.toHexString());
	}

	/** A running remote job's {@code delegation} as 0.10.0 recorded it. */
	private static AMap<AString, ACell> followed(String target, String remoteId, AString authRef) {
		return Maps.of(RemoteJobs.PROTOCOL, "covia", RemoteJobs.TARGET, target, RemoteJobs.OBSERVATION, "healthy",
			RemoteJobs.OWNS_REMOTE, true, Strings.create("authRef"), authRef,
			RemoteJobs.REMOTE_ID, remoteId, RemoteJobs.REMOTE_STATUS, Status.STARTED);
	}

	private static Path adapterPath(String name) {
		return CoviaPath.parse("w/adapters/" + name);
	}

	private static AMap<AString, ACell> turn(String role, String content) {
		return Maps.of("role", role, "content", content);
	}

	@Test void aVersionZeroVenueReachesTheCurrentVersionWithEveryOlderShapeCurrent() throws Exception {
		assertEquals(SEEDED_THROUGH, VenueState.CURRENT_VERSION, "seed the shapes the new venue version's step converts");
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = TestEngine.application();
		Engine engine = application.mount(key, Maps.empty());
		try {
			AString venueDID = engine.getDIDString();
			VenueState seed = application.venue(key.getAccountKey());
			// No :version: the venue is at version 0.
			seed.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, venueDID));
			User host = seed.users().ensure(venueDID);
			host.writeInternalPath(OLD_DELETIONS, PENDING);
			for (var entry : BundledAdapterMigrationTest.STATES.entrySet()) {
				host.writeInternalPath(adapterPath(entry.getKey()), entry.getValue().get(0));
			}
			host.writeInternalPath(adapterPath("unloaded"), UNLOADED);

			User user = seed.users().ensure(USER);
			// A finished job with the pre-#611 history chain.
			AMap<AString, ACell> job = Maps.of(Fields.ID, JOB, Fields.CALLER, USER, Fields.STATUS, Status.COMPLETE,
				Fields.OUTPUT, Maps.of("answer", 42L), Fields.UPDATED, 123L);
			AMap<AString, ACell> legacyJob = job.assoc(PREV, Maps.of(Fields.STATUS, Status.STARTED,
				PREV, Maps.of(Fields.STATUS, Status.PENDING)));
			user.cursor().path(Namespace.J).set(Index.<Blob, ACell>none().assoc(JOB, legacyJob));
			// A secret encrypted under key version 1, before key versions were recorded.
			SecretStoreTest.storeVersion1(user, SECRET, "kept", key);
			user.secrets().store(LEGACY_GRANT, "{\"provider\":\"e2e\",\"accessToken\":\"e2e-access\",\"expiresAt\":0,"
				+ "\"obtained\":1,\"scope\":\"read\"}", SecretStore.keys(key));
			// An agent session with a legacy root frame and session-level loads.
			user.writeInternalPath(CoviaPath.parse("w/notes"), Strings.create("session notes"));
			user.writeInternalPath(CoviaPath.parse("w/frame-notes"), Strings.create("frame notes"));
			AVector<ACell> conversation = Vectors.of(
				GoalTreeContext.createSegment("earlier", Vectors.of(turn("user", "old question"), turn("assistant", "old answer"))),
				turn("user", "recent question").assoc(Fields.JOB_ID, hex(TURN_JOB)), turn("assistant", "recent answer"));
			AMap<AString, ACell> root = Maps.of("description", "legacy session", "conversation", conversation,
				Fields.LOADS, Maps.of("w/frame-notes", Maps.empty()));
			Agent agent = TestAgents.ensure(user, AGENT,
				Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm"), null);
			agent.sessions().create(SESSION, USER, null);
			AMap<AString, ACell> session = agent.session(SESSION).getRecord()
				.assoc(Fields.FRAMES, Vectors.of(root)).assoc(Fields.LOADS, Maps.of("w/notes", Maps.empty()));
			agent.cursor().set(agent.getRecord().assoc(Agent.KEY_SESSIONS, agent.getSessions().assoc(SESSION, session)));

			// Records of calls as version 1 named them: a job one of the user's
			// agents made, its scheduled event, a HITL request it asked from
			// before the requester's namespace was recorded, and a task's output
			// snapshot on a suspended agent, which does not run.
			AString agentDID = Principals.agentDID(USER, Strings.create(AGENT));
			AMap<AString, ACell> agentJob = Maps.of(Fields.ID, AGENT_JOB, Fields.STATUS, Status.COMPLETE,
				Fields.CALLER, USER, Strings.create("actor"), agentDID);
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(AGENT_JOB, agentJob));
			AMap<AString, ACell> running = Maps.of(Fields.ID, RUNNING_JOB, Fields.STATUS, Status.STARTED,
				Fields.CALLER, USER, Fields.OP, "v/test/ops/never", Fields.ADAPTER, "test");
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(RUNNING_JOB, running));
			AString remoteRef = Strings.create("remote-job-" + REMOTE_JOB.toHexString());
			// Remote jobs' records as 0.10.0 wrote them: how the venue followed each one beside what its caller can follow
			AMap<AString, ACell> remote = Maps.of(Fields.ID, REMOTE_JOB, Fields.STATUS, Status.STARTED,
				Fields.CALLER, USER, Fields.OP, "v/ops/grid/run", Fields.ADAPTER, "grid",
				RemoteJobs.DELEGATION, followed(REMOTE_TARGET, "remote-1", remoteRef));
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(REMOTE_JOB, remote));
			AMap<AString, ACell> finishedRemote = Maps.of(Fields.ID, FINISHED_REMOTE_JOB, Fields.STATUS, Status.COMPLETE,
				Fields.CALLER, USER, Fields.OP, "v/ops/grid/run", Fields.ADAPTER, "grid",
				RemoteJobs.DELEGATION, Maps.of(RemoteJobs.PROTOCOL, "covia", RemoteJobs.TARGET, REMOTE_TARGET,
					RemoteJobs.OWNS_REMOTE, true, RemoteJobs.REMOTE_ID, "remote-2", RemoteJobs.REMOTE_STATUS, Status.COMPLETE,
					RemoteJobs.OBSERVATION, "healthy"));
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(FINISHED_REMOTE_JOB, finishedRemote));
			AMap<AString, ACell> lostRemote = Maps.of(Fields.ID, LOST_REMOTE_JOB, Fields.STATUS, Status.STARTED,
				Fields.CALLER, USER, Fields.OP, "v/ops/grid/run", Fields.ADAPTER, "grid",
				RemoteJobs.DELEGATION, followed(REMOTE_TARGET, "remote-3", Strings.create("remote-job-" + LOST_REMOTE_JOB.toHexString())));
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(LOST_REMOTE_JOB, lostRemote));
			host.secrets().store(remoteRef, Strings.create("{\"authenticateAs\":\"caller\",\"bearerToken\":\"e2e-sign-in\"}"),
				SecretStore.keys(key));
			// A call to an agent on this venue, whose kept connection 0.10.0 left empty
			AString localRef = Strings.create("remote-job-" + LOCAL_CALL.toHexString());
			AMap<AString, ACell> localCall = Maps.of(Fields.ID, LOCAL_CALL, Fields.STATUS, Status.STARTED,
				Fields.CALLER, USER, Fields.OP, "v/ops/remoteagent/request", Fields.ADAPTER, "remoteagent",
				Strings.create("remoteAgent"), "helper",
				RemoteJobs.DELEGATION, followed(venueDID.toString(), LOCAL_TASK, localRef).assoc(RemoteJobs.PROTOCOL, Strings.create("covia-agent-local")));
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(LOCAL_CALL, localCall));
			host.secrets().store(localRef, Strings.create("{\"operationMetadata\":{\"operation\":{\"adapter\":\"remoteagent:request\"}}}"),
				SecretStore.keys(key));
			AString parkedDID = Principals.agentDID(USER, Strings.create(PARKED));
			user.cursor().path(Namespace.H).set(Maps.of(Covia.WORKSPACE_DATA, Maps.of(hex(ASK), Maps.of(
				Hitl.ID, hex(ASK), Strings.create("from"), parkedDID, Hitl.STATUS, Hitl.OPEN,
				Hitl.AGENT, PARKED, Fields.SESSION_ID, hex(PARKED_SESSION), Hitl.TITLE, "Go ahead?", Hitl.ASKS, Vectors.empty()),
				hex(OTHER_ASK), Maps.of(Hitl.ID, hex(OTHER_ASK), Strings.create("from"), OTHER, Strings.create("requesterUser"), OTHER,
					Hitl.STATUS, Hitl.OPEN, Hitl.TITLE, "From the other user", Hitl.ASKS, Vectors.empty()))));
			// Work an admin/sudo delegate left for the user before admin/sudo acted as the user
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(DELEGATE_JOB, Maps.of(
				Fields.ID, DELEGATE_JOB, Fields.STATUS, Status.STARTED, Fields.CALLER, USER, Strings.create("actor"), DELEGATE,
				Fields.OP, "v/test/ops/never", Fields.ADAPTER, "test")));
			seed.users().ensure(OTHER).cursor().path(Namespace.H).set(Maps.of(Covia.WORKSPACE_DATA, Maps.of(hex(DELEGATE_ASK), Maps.of(
				Hitl.ID, hex(DELEGATE_ASK), Strings.create("from"), DELEGATE, Strings.create("requesterUser"), USER,
				Hitl.STATUS, Hitl.OPEN, Hitl.TITLE, "Asked by the delegate", Hitl.ASKS, Vectors.empty()))));
			AMap<AString, ACell> askJob = Maps.of(Fields.ID, ASK, Fields.STATUS, Status.INPUT_REQUIRED,
				Fields.CALLER, USER, Strings.create("actor"), parkedDID, Fields.OP, "v/ops/hitl/request", Fields.ADAPTER, "hitl");
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(ASK, askJob));
			Agent parked = TestAgents.ensure(user, PARKED, Maps.empty(), null);
			parked.cursor().set(parked.getRecord().assoc(Agent.KEY_STATUS, Agent.SUSPENDED)
				.assoc(Agent.KEY_TASKS, Index.<Blob, ACell>none().assoc(PARKED_TASK,
					Maps.of(Strings.create("outputContext"), Maps.of(Fields.CALLER, USER, Strings.create("user"), USER)))
					.assoc(PARKED_WORK, Maps.of(Fields.INPUT, "work", Fields.SESSION_ID, hex(PARKED_SESSION))))
				.assoc(Agent.KEY_PENDING, Index.of(PARKED_ASK, Maps.of(Fields.TASK_ID, hex(PARKED_WORK))))
				.assoc(Agent.KEY_TIMELINE, Vectors.of(Maps.of(Fields.SESSION_ID, hex(PARKED_SESSION),
					Fields.TASK_RESULTS, Maps.of(hex(PARKED_WORK), Maps.of(Fields.STATUS, Status.COMPLETE)))))
				.assoc(Agent.KEY_SESSIONS, Index.of(PARKED_SESSION, Maps.of(Agent.KEY_PENDING, Vectors.of(Maps.of(
					Fields.MESSAGE, "queued", Fields.SESSION_ID, hex(PARKED_SESSION), Fields.JOB_ID, hex(PARKED_CHAT)))))));
			// The queued chat's own record, naming its session in hex
			AMap<AString, ACell> parkedChat = Maps.of(Fields.ID, PARKED_CHAT, Fields.STATUS, Status.PENDING,
				Fields.CALLER, USER, Fields.OP, "v/ops/agent/chat", Fields.ADAPTER, "agent",
				Fields.AGENT_ID, PARKED, Fields.ADDRESS, USER + "/g/" + PARKED, Fields.SESSION_ID, hex(PARKED_SESSION));
			user.cursor().path(Namespace.J).updateAndGet(jobs -> ((Index<Blob, ACell>) jobs).assoc(PARKED_CHAT, parkedChat));
			seed.cursor().updateAndGet(value -> RT.assocIn(value, Maps.of(Strings.create("events"), Index.of(EVENT,
				Maps.of(Fields.OP, "v/test/ops/echo", Fields.OWNER, USER, Strings.create("actor"), agentDID,
					Strings.create("by"), agentDID, Strings.create("time"), Long.MAX_VALUE),
				DELEGATE_EVENT, Maps.of(Fields.OP, "v/test/ops/echo", Fields.OWNER, USER, Strings.create("actor"), DELEGATE,
					Strings.create("by"), DELEGATE, Strings.create("time"), Long.MAX_VALUE))), Covia.SCHEDULE));
			Map<AString, ACell> releasedAgents = ReleasedAgents.seed(seed, USER);

			// Before a start: the read-only check finds version 0 and writes nothing.
			application.sync();
			application.host().sync();
			ACell seeded = application.hostStore().getRootData();
			VenueState.StoredVersions stored = VenueState.peekVersions(application.hostStore(), key.getAccountKey()).orElseThrow();
			assertEquals(new VenueState.StoredVersions(0, Map.of()), stored);
			assertEquals(seeded, application.hostStore().getRootData());

			// The start-up transaction: every eager part is current in storage.
			engine.prepareLaunch();
			VenueState venue = engine.getVenueState();
			assertEquals(VenueState.CURRENT_VERSION, venue.version());
			assertEquals(PENDING, RT.getIn(venue.get(), Covia.USER_DELETIONS));
			assertNull(venue.users().user(venueDID).readInternalPath(OLD_DELETIONS));
			Map<String, Long> versions = new TreeMap<>();
			for (var entry : BundledAdapterMigrationTest.STATES.entrySet()) {
				List<ACell> states = entry.getValue();
				versions.put(entry.getKey(), (long) states.size() - 1);
				assertEquals(states.get(states.size() - 1), venue.adapterState(entry.getKey()).get(), entry.getKey());
				assertNull(venue.users().user(venueDID).readInternalPath(adapterPath(entry.getKey())), entry.getKey());
			}
			assertEquals(versions, venue.adapterVersions());
			User migrated = venue.users().user(USER);
			assertEquals(Maps.of(Fields.ID, AGENT_JOB, Fields.STATUS, Status.COMPLETE, Fields.CALLER, agentDID),
				migrated.jobs().getRecord(AGENT_JOB).dissoc(Fields.UPDATED), "an agent's job names the agent alone");
			assertEquals(Maps.of(Fields.ID, DELEGATE_JOB, Fields.STATUS, Status.FAILED, Fields.CALLER, DELEGATE,
					Fields.ERROR, covia.venue.migration.OneCaller.DELEGATE_WORK_ENDED, Fields.OP, "v/test/ops/never", Fields.ADAPTER, "test"),
				migrated.jobs().getRecord(DELEGATE_JOB).dissoc(Fields.UPDATED), "a delegate's unfinished job ends, naming the delegate");
			assertNull(venue.jobIndex().entry(DELEGATE_JOB), "and leaves no entry");
			assertEquals(Hitl.CANCELLED, venue.users().user(OTHER).inbox().get(DELEGATE_ASK).get(Hitl.STATUS),
				"a delegate's open request is cancelled where it is kept");
			assertEquals(DELEGATE, venue.users().user(OTHER).inbox().get(DELEGATE_ASK).get(Hitl.CALLER));
			assertFalse(venue.users().user(OTHER).inbox().get(DELEGATE_ASK).containsKey(Hitl.OWNER));
			assertEquals(Maps.of(Hitl.ID, OTHER_ASK, Hitl.CALLER, OTHER, Hitl.STATUS, Hitl.OPEN, Hitl.TITLE, "From the other user",
				Hitl.ASKS, Vectors.empty()), migrated.inbox().get(OTHER_ASK), "another user's request keeps its caller");
			assertNull(venue.schedule().events().get(DELEGATE_EVENT), "a delegate's event goes");
			assertEquals(Maps.of(Hitl.ID, ASK, Hitl.CALLER, parkedDID, Hitl.STATUS, Hitl.OPEN,
					Hitl.AGENT, PARKED, Fields.SESSION_ID, PARKED_SESSION, Hitl.TITLE, "Go ahead?", Hitl.ASKS, Vectors.empty()),
				migrated.inbox().get(ASK), "a request is keyed by its job's id, which it names as a Blob, as its session");
			assertEquals(Maps.empty(), migrated.agent(PARKED).getTasks().get(PARKED_TASK),
				"a pending write's context is the request's to keep, not the worker's");
			Agent parkedNow = migrated.agent(PARKED);
			assertEquals(PARKED_SESSION, Task.sessionId(parkedNow.getTasks().get(PARKED_WORK)), "ids stored as hex are Blobs");
			assertTrue(parkedNow.hasPendingFor(PARKED_WORK), "an open wait names its task");
			assertEquals(Maps.of(Fields.MESSAGE, "queued", Fields.JOB_ID, PARKED_CHAT),
				parkedNow.session(PARKED_SESSION).pending().get(0), "a queued chat names its job, and no longer its session");
			assertEquals(Maps.of(Fields.SESSION_ID, PARKED_SESSION,
					Fields.TASK_RESULTS, Index.of(PARKED_WORK, Maps.of(Fields.STATUS, Status.COMPLETE))),
				parkedNow.getTimeline().get(0));
			assertEquals(TURN_JOB, RT.getIn(rootFrame(migrated), Agent.KEY_CONVERSATION, 1, Fields.JOB_ID), "a turn names its job");
			assertEquals(PARKED_SESSION, migrated.jobs().getRecord(PARKED_CHAT).get(Fields.SESSION_ID), "a chat's record names its session");
			assertEquals(Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, agentDID,
				Strings.create("time"), Long.MAX_VALUE), venue.schedule().events().get(EVENT), "an event names its caller alone");
			assertEquals(Maps.of(JobIndex.OWNER, USER), venue.jobIndex().entry(RUNNING_JOB));
			assertEquals(Maps.of(JobIndex.OWNER, USER, JobIndex.ADAPTER_DATA, Maps.of("authenticateAs", "caller",
					RemoteJobs.PROTOCOL, "covia", RemoteJobs.TARGET, REMOTE_TARGET, RemoteJobs.OWNS_REMOTE, true)),
				venue.jobIndex().entry(REMOTE_JOB), "the remote job's connection, without its sign-in credential, and how the venue follows it");
			assertEquals(Maps.of(RemoteJobs.REMOTE_ID, "remote-1", RemoteJobs.REMOTE_STATUS, Status.STARTED),
				RT.getIn(migrated.jobs().getRecord(REMOTE_JOB), RemoteJobs.DELEGATION), "its record shows only what its caller can follow");
			assertEquals(Maps.of(RemoteJobs.REMOTE_ID, "remote-2", RemoteJobs.REMOTE_STATUS, Status.COMPLETE),
				RT.getIn(migrated.jobs().getRecord(FINISHED_REMOTE_JOB), RemoteJobs.DELEGATION), "a finished job's too");
			assertEquals(Maps.of(JobIndex.OWNER, USER, JobIndex.ADAPTER_DATA, Maps.of(RemoteJobs.PROTOCOL, "covia",
				RemoteJobs.TARGET, REMOTE_TARGET, RemoteJobs.OWNS_REMOTE, true)), venue.jobIndex().entry(LOST_REMOTE_JOB), "no connection to carry");
			assertEquals(Maps.of(JobIndex.OWNER, USER, JobIndex.ADAPTER_DATA, Maps.of(RemoteJobs.PROTOCOL, "covia-agent-local",
				RemoteJobs.TARGET, venueDID, RemoteJobs.OWNS_REMOTE, true)), venue.jobIndex().entry(LOCAL_CALL),
				"a local call keeps no connection, and no caller context");
			assertEquals(Maps.of(RemoteJobs.REMOTE_ID, LOCAL_TASK, RemoteJobs.REMOTE_STATUS, Status.STARTED),
				RT.getIn(migrated.jobs().getRecord(LOCAL_CALL), RemoteJobs.DELEGATION));
			assertFalse(venue.users().user(venueDID).secrets().exists(remoteRef));
			assertEquals(6, venue.jobIndex().all().count(), "only the unfinished jobs have entries");
			assertEquals(job.dissoc(Fields.CALLER), venue.users().user(USER).cursor().path(Namespace.J, JOB).get(),
				"the history chain is gone, and a caller that is the account is left out");
			ReleasedAgents.assertUntouched(venue, USER, releasedAgents);
			// Lazy parts are still in their older shapes.
			assertNull(RT.getIn(venue.get(), Covia.ADAPTERS, Strings.create("unloaded")));
			assertTrue(venue.users().user(USER).secrets().exists(LEGACY_GRANT));

			engine.launch();
			RequestContext ctx = RequestContext.of(USER);
			user = venue.users().user(USER);

			// The running job is recovered from its entry, as any job is after a restart.
			assertEquals(Status.FAILED, RT.getIn(engine.jobs().getJobData(RUNNING_JOB, ctx), Fields.STATUS));
			assertNull(venue.jobIndex().entry(RUNNING_JOB));
			Job remoteJob = engine.jobs().getJob(REMOTE_JOB, ctx);
			assertEquals(RemoteJobs.CREDENTIAL_NOT_KEPT.formatted("remote-1", REMOTE_TARGET), remoteJob.getErrorMessage());
			// A remote job with nothing kept to reach it is no longer followed, and says so
			Job lost = engine.jobs().getJob(LOST_REMOTE_JOB, ctx);
			assertEquals(Status.STARTED, lost.getStatus());
			assertEquals(Strings.create(RemoteJobs.UNREACHABLE_BECAUSE.formatted("remote-3", REMOTE_TARGET, RemoteJobs.NO_CONNECTION)),
				RT.getIn(lost.getData(), RemoteJobs.DELEGATION, RemoteJobs.NOT_FOLLOWED));
			// A local call that kept no caller context is not followed again; its task is untouched
			assertEquals(covia.adapter.RemoteAgentAdapter.LOCAL_INTERRUPTED,
				engine.jobs().getJob(LOCAL_CALL, ctx).getErrorMessage());
			// The queued chat is still waiting for the cycle that takes its message
			assertEquals(Status.PENDING, engine.jobs().getJob(PARKED_CHAT, ctx).getStatus());
			// The open request still waits, and its answer reaches the session that asked
			assertEquals(Status.INPUT_REQUIRED, engine.jobs().getJob(ASK, ctx).getStatus());
			engine.jobs().invokeOperation("v/ops/hitl/respond", Hitl.reject(ASK.toHexString(), "Not now"), ctx).awaitResult(10_000);
			AVector<ACell> queued = user.agent(PARKED).session(PARKED_SESSION).pending();
			assertEquals(ASK, RT.getIn(queued.get(queued.count() - 1), Fields.MESSAGE, Hitl.ID), "the answer is queued on the asking session");

			// An unversioned adapter's state moves when it is first used.
			assertEquals(UNLOADED, venue.adapterState("unloaded").get());
			assertNull(venue.users().user(venueDID).readInternalPath(adapterPath("unloaded")));

			assertEquals(job, engine.jobs().getJobData(JOB, ctx));

			// A version 1 secret decrypts, and its next write takes the current key version.
			SecretStore.Keys keys = SecretStore.keys(engine.getKeyPair());
			assertEquals("kept", user.secrets().decrypt(SECRET, keys).toString());
			assertNull(RT.getIn(user.get(), Namespace.S, SECRET, "keyVersion"));
			user.secrets().store(SECRET, "rewritten", keys);
			assertEquals(CVMLong.create(SecretStore.CURRENT_KEY_VERSION), RT.getIn(user.get(), Namespace.S, SECRET, "keyVersion"));
			assertEquals("rewritten", user.secrets().decrypt(SECRET, keys).toString());

			// A connected account kept as a secret moves to the oauth adapter's state on first use.
			ACell connections = engine.jobs().invokeOperation("v/ops/oauth/status", Maps.empty(), ctx).awaitResult(5000);
			assertEquals(Strings.create("e2e"), RT.getIn(connections, "connections", CVMLong.ZERO, "provider"));
			assertFalse(user.secrets().exists(LEGACY_GRANT));
			assertNotNull(venue.adapterState("oauth").read("users/" + USER + "/e2e"));

			// The session's next cycle turns the legacy frame into versioned
			// history and folds the session loads into the root frame.
			AMap<AString, ACell> before = rootFrame(user);
			assertTrue(before.containsKey(Agent.KEY_CONVERSATION));
			assertNotNull(user.agent(AGENT).session(SESSION).getRecord().get(Fields.LOADS));
			ACell reply = engine.jobs().invokeOperation("v/ops/agent/chat", Maps.of(Fields.AGENT_ID, AGENT,
				Fields.SESSION_ID, SESSION.toHexString(), Fields.MESSAGE, "after migration"), ctx).awaitResult(20_000);
			assertTrue(RT.getIn(reply, "response").toString().contains("after migration"));
			AMap<AString, ACell> frame = rootFrame(user);
			assertEquals(CVMLong.ONE, frame.get(HISTORY_VERSION));
			assertFalse(frame.containsKey(Agent.KEY_CONVERSATION));
			String history = GoalTreeContext.archivedConversation(frame).toString();
			assertTrue(history.contains("old question") && history.contains("recent answer") && history.contains("after migration"), history);
			assertNull(user.agent(AGENT).session(SESSION).getRecord().get(Fields.LOADS));
			assertEquals(Maps.of("w/frame-notes", Maps.empty(), "w/notes", Maps.empty()), frame.get(Fields.LOADS));

			// Migrating again changes nothing migration owns.
			ACell current = venue.get();
			engine.migrate();
			for (var region : List.of(Covia.VERSION, Covia.ADAPTERS, Covia.ADAPTER_VERSIONS, Covia.USER_DELETIONS, Covia.JOBS)) {
				assertSame(RT.getIn(current, region), RT.getIn(venue.get(), region), region.toString());
			}

			ReleasedAgents.assertActAsConfigured(engine, USER);
		} finally {
			engine.close();
		}
	}

	private static AMap<AString, ACell> rootFrame(User user) {
		return RT.ensureMap(RT.getIn(user.agent(AGENT).session(SESSION).getRecord(), Agent.KEY_FRAMES, CVMLong.ZERO));
	}
}
