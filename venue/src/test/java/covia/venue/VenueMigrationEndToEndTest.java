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
	private static final long SEEDED_THROUGH = 2;

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
	private static final AString REQUEST = Strings.create("e2e-request");
	/** An open HITL request with a timeout, whose expiry version 1 kept only in memory. */
	private static final AString TIMED_REQUEST = Strings.create("e2ebe2ebe2ebe2ebe2ebe2ebe2ebe2eb");
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
		CoviaApplication application = CoviaApplication.create();
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
				turn("user", "recent question"), turn("assistant", "recent answer"));
			AMap<AString, ACell> root = Maps.of("description", "legacy session", "conversation", conversation,
				Fields.LOADS, Maps.of("w/frame-notes", Maps.empty()));
			AgentState agent = user.ensureAgent(AGENT,
				Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm"), null);
			AMap<AString, ACell> session = agent.ensureSession(SESSION, USER)
				.assoc(Fields.FRAMES, Vectors.of(root)).assoc(Fields.LOADS, Maps.of("w/notes", Maps.empty()));
			agent.putRecord(agent.getRecord().assoc(AgentState.KEY_SESSIONS, agent.getSessions().assoc(SESSION, session)));

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
			user.inbox().put(REQUEST, Maps.of(Hitl.ID, REQUEST, Strings.create("from"), agentDID,
				Hitl.STATUS, Hitl.OPEN));
			user.inbox().put(TIMED_REQUEST, Maps.of(Hitl.ID, TIMED_REQUEST, Strings.create("from"), USER,
				Hitl.STATUS, Hitl.OPEN, Hitl.CREATED, 500L, Hitl.EXPIRES, 1_000L));
			AgentState parked = user.ensureAgent(PARKED, Maps.empty(), null);
			parked.putRecord(parked.getRecord().assoc(AgentState.KEY_STATUS, AgentState.SUSPENDED)
				.assoc(AgentState.KEY_TASKS, Index.<Blob, ACell>none().assoc(PARKED_TASK,
					Maps.of(Strings.create("outputContext"), Maps.of(Fields.CALLER, USER, Strings.create("user"), USER)))));
			seed.cursor().updateAndGet(value -> RT.assocIn(value, Maps.of(Strings.create("events"), Index.of(EVENT,
				Maps.of(Fields.OP, "v/test/ops/echo", Fields.OWNER, USER, Strings.create("actor"), agentDID,
					Strings.create("by"), agentDID, Strings.create("time"), Long.MAX_VALUE))), Covia.SCHEDULE));

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
			assertEquals(Maps.of(Fields.ID, AGENT_JOB, Fields.STATUS, Status.COMPLETE, Fields.CALLER, agentDID, Fields.OWNER, USER),
				migrated.jobs().getRecord(AGENT_JOB).dissoc(Fields.UPDATED));
			assertEquals(Maps.of(Hitl.ID, REQUEST, Hitl.CALLER, agentDID, Hitl.OWNER, USER, Hitl.STATUS, Hitl.OPEN),
				migrated.inbox().get(REQUEST));
			assertEquals(Maps.empty(), migrated.agent(PARKED).getTasks().get(PARKED_TASK),
				"a pending write's context is the request's to keep, not the worker's");
			assertEquals(Maps.of(Fields.OP, "v/test/ops/echo", Fields.CALLER, agentDID, Fields.OWNER, USER,
				Strings.create("time"), Long.MAX_VALUE), venue.schedule().events().get(EVENT));
			AMap<AString, ACell> expiry = null;
			for (var event : venue.schedule().events().entrySet()) {
				if (covia.adapter.HITLAdapter.EXPIRE_OP.equals(RT.getIn(event.getValue(), Fields.OP))) {
					assertNull(expiry, "one expiry for the one timed request");
					expiry = RT.ensureMap(event.getValue());
				}
			}
			assertNotNull(expiry, "the timed request's expiry is scheduled");
			assertEquals(covia.adapter.HITLAdapter.expiryInput(USER, TIMED_REQUEST), expiry.get(Fields.INPUT));
			assertEquals(CVMLong.create(1_000), expiry.get(Strings.create("time")), "at the request's expires time");
			assertEquals(venueDID, expiry.get(Fields.CALLER), "made and fired by the venue");
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
			assertEquals(4, venue.jobIndex().all().count(), "only the unfinished jobs have entries");
			assertEquals(job, venue.users().user(USER).cursor().path(Namespace.J, JOB).get(), "the history chain is gone");
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
			assertTrue(before.containsKey(AgentState.KEY_CONVERSATION));
			assertNotNull(user.agent(AGENT).getSession(SESSION).get(Fields.LOADS));
			ACell reply = engine.jobs().invokeOperation("v/ops/agent/chat", Maps.of(Fields.AGENT_ID, AGENT,
				Fields.SESSION_ID, SESSION.toHexString(), Fields.MESSAGE, "after migration"), ctx).awaitResult(20_000);
			assertTrue(RT.getIn(reply, "response").toString().contains("after migration"));
			AMap<AString, ACell> frame = rootFrame(user);
			assertEquals(CVMLong.ONE, frame.get(HISTORY_VERSION));
			assertFalse(frame.containsKey(AgentState.KEY_CONVERSATION));
			String history = GoalTreeContext.archivedConversation(frame).toString();
			assertTrue(history.contains("old question") && history.contains("recent answer") && history.contains("after migration"), history);
			assertNull(user.agent(AGENT).getSession(SESSION).get(Fields.LOADS));
			assertEquals(Maps.of("w/frame-notes", Maps.empty(), "w/notes", Maps.empty()), frame.get(Fields.LOADS));

			// Migrating again changes nothing migration owns.
			ACell current = venue.get();
			engine.migrate();
			for (var region : List.of(Covia.VERSION, Covia.ADAPTERS, Covia.ADAPTER_VERSIONS, Covia.USER_DELETIONS, Covia.JOBS)) {
				assertSame(RT.getIn(current, region), RT.getIn(venue.get(), region), region.toString());
			}
		} finally {
			engine.close();
		}
	}

	private static AMap<AString, ACell> rootFrame(User user) {
		return RT.ensureMap(RT.getIn(user.agent(AGENT).getSession(SESSION), AgentState.KEY_FRAMES, CVMLong.ZERO));
	}
}
