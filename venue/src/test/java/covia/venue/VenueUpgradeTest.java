package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.etch.EtchStore;
import covia.adapter.AAdapter;
import covia.adapter.agent.GoalTreeContext;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;

/**
 * The upgrade path as a unit test: every release-written store in
 * {@code src/test/resources/migrations} opens with this build through the
 * launch path, and comes out at the current schema with its data readable.
 * One engine per fixture, no HTTP and no restart; the durability gate's
 * {@link VenueStoreMigrationTest} adds those. A new fixture is picked up by
 * adding its archive.
 */
class VenueUpgradeTest {
	private static final AString K_CONVERSATION = Strings.create("conversation");
	private static final AString K_HISTORY_VERSION = Strings.create("historyVersion");
	private static final AString K_KEY_VERSION = Strings.create("keyVersion");

	/** Every release fixture, oldest release first: covia-0.9.11.zip before covia-0.10.0.zip. */
	static List<String> fixtures() throws Exception {
		Path dir = Path.of(VenueUpgradeTest.class.getResource("/migrations").toURI());
		try (Stream<Path> files = Files.list(dir)) {
			List<String> names = files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".zip")).sorted(BY_RELEASE).toList();
			assertFalse(names.isEmpty(), "no release fixtures found");
			return names;
		}
	}

	/** The release that wrote a fixture, from its name: covia-0.10.0.zip is 0.10.0's. */
	static String release(String fixture) {
		return fixture.substring("covia-".length(), fixture.length() - ".zip".length());
	}

	private static final Comparator<String> BY_RELEASE = Comparator.comparing(
		fixture -> Arrays.stream(release(fixture).split("\\.")).mapToInt(Integer::parseInt).toArray(), Arrays::compare);

	@TestFactory Stream<DynamicTest> releaseStoresUpgradeToTheCurrentSchema() throws Exception {
		return fixtures().stream().map(fixture -> DynamicTest.dynamicTest(fixture,
			() -> upgrade(fixture, TestTemp.dir("upgrade-" + fixture.replace(".zip", "")))));
	}

	private static void upgrade(String fixture, Path dir) throws Exception {
		AMap<AString, ACell> manifest = unpack(fixture, dir);
		AKeyPair key = AKeyPair.create(Blob.fromHex(text(manifest, "venueSeed")));
		Path file = dir.resolve("venue.etch");
		try (EtchStore store = EtchStore.create(file.toFile())) {
			// Before a start: the release's schema, read without writing.
			ACell released = store.getRootData();
			VenueState.StoredVersions stored = VenueState.peekVersions(store, key.getAccountKey()).orElseThrow();
			assertTrue(stored.venue() <= VenueState.CURRENT_VERSION, "a release fixture is never newer than this build");
			assertEquals(released, store.getRootData(), "peeking writes nothing");

			Engine engine = new Engine(VenueStoreFixture.config(file), CoviaApplication.open(store, key), key).launch();
			try {
				VenueState venue = engine.getVenueState();
				assertEquals(VenueState.CURRENT_VERSION, venue.version());
				for (AAdapter adapter : Engine.builtInAdapters()) {
					if (adapter.stateVersion() > 0) {
						assertEquals(adapter.stateVersion(), venue.adapterVersion(adapter.getName()), adapter.getName());
					}
				}

				// The release's private test adapter value moved, exactly, in the start-up transaction.
				User host = venue.users().get(engine.getDIDString());
				assertNull(host.readInternalPath(WorkspaceRoot.parseStringPath("w/adapters/test")));
				AVector<ACell> users = RT.ensureVector(manifest.get(Strings.create("users")));
				String alice = text(users.get(0), "did");
				assertEquals(Maps.of("origin", text(manifest, "coviaVersion")),
					engine.adapterState("test").read("users/" + alice + "/scratch"));

				for (ACell user : users) verifyUser(engine, user);

				// Migrating again changes nothing migration owns. The live venue's
				// other regions may move meanwhile (agent write-back, timeline).
				ACell current = venue.get();
				engine.migrate();
				for (var region : List.of(Covia.VERSION, Covia.ADAPTERS, Covia.ADAPTER_VERSIONS, Covia.USER_DELETIONS)) {
					assertSame(RT.getIn(current, region), RT.getIn(venue.get(), region), region.toString());
				}
			} finally {
				engine.close();
			}
		}
	}

	/** One release user's data, through the accessors, with each lazy form converting on use. */
	private static void verifyUser(Engine engine, ACell entry) {
		String name = text(entry, "name");
		AString did = Strings.create(text(entry, "did"));
		RequestContext ctx = RequestContext.of(did);
		User user = engine.getVenueState().users().get(did);
		assertEquals(Strings.create(name), RT.getIn(user.workspaceRoot().read(WorkspaceRoot.parseStringPath("w/profile")), "name"));

		// A job written with its prev chain reads as its current state.
		AMap<AString, ACell> job = engine.jobs().getJobData(Blob.fromHex(text(entry, "jobId")), ctx);
		assertEquals(Strings.create("COMPLETE"), job.get(Strings.create("status")));
		assertNull(job.get(Strings.create("prev")), name);

		// A secret at the release's key version decrypts, and its next write
		// takes the current key version.
		SecretStore.Keys keys = SecretStore.keys(engine.getKeyPair());
		SecretStore secrets = user.secrets();
		assertEquals("test-only-" + name, secrets.decrypt(VenueStoreFixture.SECRET, keys).toString());
		secrets.store(VenueStoreFixture.SECRET, "rewritten", keys);
		assertEquals(CVMLong.create(SecretStore.CURRENT_KEY_VERSION),
			RT.getIn(user.get(), "s", VenueStoreFixture.SECRET, K_KEY_VERSION));
		assertEquals("rewritten", secrets.decrypt(VenueStoreFixture.SECRET, keys).toString());

		// A conversation reads through the format-independent accessor, and the
		// next cycle turns a legacy frame into versioned history.
		Blob sid = Blob.fromHex(text(entry, "sessionId"));
		String baseline = "baseline message for " + name;
		assertTrue(conversation(user, sid).toString().contains(baseline), name);
		ACell reply = engine.jobs().invokeOperation("v/ops/agent/chat", Maps.of("agentId", VenueStoreFixture.AGENT,
			"sessionId", sid.toHexString(), "message", "upgraded message for " + name), ctx).awaitResult(20_000);
		assertTrue(text(reply, "response").contains("upgraded message for " + name));
		AMap<AString, ACell> frame = rootFrame(user, sid);
		assertEquals(CVMLong.ONE, frame.get(K_HISTORY_VERSION));
		assertFalse(frame.containsKey(K_CONVERSATION));
		String history = conversation(user, sid).toString();
		assertTrue(history.contains(baseline) && history.contains("upgraded message for " + name), history);
	}

	private static AMap<AString, ACell> rootFrame(User user, Blob sid) {
		AMap<AString, ACell> session = user.agent(VenueStoreFixture.AGENT).getSession(sid);
		return RT.ensureMap(RT.ensureVector(session.get(AgentState.KEY_FRAMES)).get(0));
	}

	private static AVector<ACell> conversation(User user, Blob sid) {
		return GoalTreeContext.archivedConversation(rootFrame(user, sid));
	}

	private static AMap<AString, ACell> unpack(String fixture, Path dir) throws Exception {
		try (var input = VenueUpgradeTest.class.getResourceAsStream("/migrations/" + fixture);
				var archive = new ZipInputStream(input)) {
			for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
				assertTrue(entry.getName().equals("venue.etch") || entry.getName().equals("manifest.json"), entry.getName());
				Files.copy(archive, dir.resolve(entry.getName()));
			}
		}
		AMap<AString, ACell> manifest = RT.ensureMap(JSON.parse(Files.readString(dir.resolve("manifest.json"))));
		assertEquals(text(manifest, "storeSha256"), VenueStoreFixture.sha256(dir.resolve("venue.etch")),
			"the fixture's store is the one its release wrote");
		return manifest;
	}

	private static String text(ACell record, String field) {
		return VenueStoreFixture.text(RT.getIn(record, field));
	}
}
