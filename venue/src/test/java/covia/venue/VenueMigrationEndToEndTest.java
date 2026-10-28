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
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.agent.GoalTreeContext;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.WorkspaceRoot;

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
	private static final long SEEDED_THROUGH = 1;

	private static final AString USER = Strings.create("did:test:migration-e2e");
	private static final ACell[] OLD_DELETIONS = WorkspaceRoot.parseStringPath("w/user-deletions");
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

	private static ACell[] adapterPath(String name) {
		return WorkspaceRoot.parseStringPath("w/adapters/" + name);
	}

	private static AMap<AString, ACell> turn(String role, String content) {
		return Maps.of("role", role, "content", content);
	}

	@Test void aVersionZeroVenueReachesTheCurrentVersionWithEveryOlderShapeCurrent() throws Exception {
		assertEquals(SEEDED_THROUGH, VenueState.CURRENT_VERSION, "seed the shapes the new venue version's step converts");
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(key);
		Engine engine = new Engine(Maps.empty(), application, key);
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
			user.writeInternalPath(WorkspaceRoot.parseStringPath("w/notes"), Strings.create("session notes"));
			user.writeInternalPath(WorkspaceRoot.parseStringPath("w/frame-notes"), Strings.create("frame notes"));
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

			// Before a start: the read-only check finds version 0 and writes nothing.
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
			assertNull(venue.users().get(venueDID).readInternalPath(OLD_DELETIONS));
			Map<String, Long> versions = new TreeMap<>();
			for (var entry : BundledAdapterMigrationTest.STATES.entrySet()) {
				List<ACell> states = entry.getValue();
				versions.put(entry.getKey(), (long) states.size() - 1);
				assertEquals(states.get(states.size() - 1), venue.adapterState(entry.getKey()).get(), entry.getKey());
				assertNull(venue.users().get(venueDID).readInternalPath(adapterPath(entry.getKey())), entry.getKey());
			}
			assertEquals(versions, venue.adapterVersions());
			// Lazy parts are still in their older shapes.
			assertNull(RT.getIn(venue.get(), Covia.ADAPTERS, Strings.create("unloaded")));
			assertEquals(legacyJob, venue.users().get(USER).cursor().path(Namespace.J, JOB).get());
			assertTrue(venue.users().get(USER).secrets().exists(LEGACY_GRANT));

			engine.launch();
			RequestContext ctx = RequestContext.of(USER);
			user = venue.users().get(USER);

			// An unversioned adapter's state moves when it is first used.
			assertEquals(UNLOADED, venue.adapterState("unloaded").get());
			assertNull(venue.users().get(venueDID).readInternalPath(adapterPath("unloaded")));

			// A job reads as its current state, and its next write drops the chain.
			assertEquals(job, engine.jobs().getJobData(JOB, ctx));
			assertTrue(user.job(JOB).appendHistory(Maps.of(Fields.MESSAGE_ID, "after migration")));
			assertFalse(user.getJob(JOB).containsKey(PREV));
			assertEquals(user.getJob(JOB), user.cursor().path(Namespace.J, JOB).get());

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
			for (var region : List.of(Covia.VERSION, Covia.ADAPTERS, Covia.ADAPTER_VERSIONS, Covia.USER_DELETIONS)) {
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
