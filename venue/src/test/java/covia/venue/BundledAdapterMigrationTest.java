package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.AuthAdapter;
import covia.adapter.TestAdapter;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;

/**
 * State migration of the adapters bundled in covia.jar, through the real
 * adapters and the start-up path. The built-in test adapter carries the
 * general mechanics; {@link AdapterStateMigrationTest} covers them with
 * synthetic adapters.
 */
class BundledAdapterMigrationTest {

	/** The test adapter's state at versions 0 and 1, the same shape; its synthetic steps log themselves. */
	private static final AMap<AString, ACell> TEST_STATE = Maps.of("users",
		Maps.of("did:test:bundled-scratch", Maps.of("scratch", Maps.of("note", "kept"))));

	/**
	 * Each versioned bundled adapter's state at every version, oldest first.
	 * Raising a bundled adapter's {@code stateVersion} needs a new entry here,
	 * and {@link #everyVersionedBundledAdapterHasStatesForEveryVersion} fails
	 * until it has one.
	 */
	static final Map<String, List<ACell>> STATES = Map.of(
		"test", List.of(TEST_STATE, TEST_STATE,
			TEST_STATE.assoc(TestAdapter.K_MIGRATIONS, Vectors.of(2L)),
			TEST_STATE.assoc(TestAdapter.K_MIGRATIONS, Vectors.of(2L, 3L))));

	/** New instances of every adapter in covia.jar. */
	private static List<AAdapter> bundled() {
		List<AAdapter> adapters = new ArrayList<>(Engine.builtInAdapters());
		adapters.add(new AuthAdapter());
		return adapters;
	}

	@Test void everyVersionedBundledAdapterHasStatesForEveryVersion() {
		Set<String> names = new HashSet<>();
		for (AAdapter adapter : bundled()) {
			String name = adapter.getName();
			assertTrue(names.add(name), "two bundled adapters are named " + name);
			long version = adapter.stateVersion();
			assertTrue(version >= 0, name);
			if (version == 0) {
				assertFalse(STATES.containsKey(name), name + " no longer versions its state: remove its STATES entry");
				continue;
			}
			assertNull(adapter.checkStateMigration(STATES.getOrDefault(name, List.of())), "add its states to STATES");
		}
		assertTrue(names.containsAll(STATES.keySet()), "STATES names an adapter no longer bundled: " + STATES.keySet());
	}

	@Test void versionedBundledAdaptersMigrateAtStartUp() throws Exception {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(key);
		Engine engine = new Engine(Maps.empty(), application, key);
		try {
			// A release-era venue: no :version, each versioned adapter's version 0
			// state at its legacy workspace route.
			VenueState seed = application.venue(key.getAccountKey());
			seed.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, engine.getDIDString()));
			for (var entry : STATES.entrySet()) {
				seed.users().ensure(engine.getDIDString())
					.writeInternalPath(WorkspaceRoot.parseStringPath("w/adapters/" + entry.getKey()), entry.getValue().get(0));
			}

			engine.prepareLaunch();
			VenueState venue = engine.getVenueState();
			assertEquals(VenueState.CURRENT_VERSION, venue.version());
			Map<String, Long> expected = new TreeMap<>();
			for (AAdapter adapter : bundled()) {
				List<ACell> states = STATES.get(adapter.getName());
				if (states == null) continue;
				expected.put(adapter.getName(), adapter.stateVersion());
				assertEquals(states.get(states.size() - 1), venue.adapterState(adapter.getName()).get(), adapter.getName());
				assertNull(venue.users().get(engine.getDIDString())
					.readInternalPath(WorkspaceRoot.parseStringPath("w/adapters/" + adapter.getName())));
			}
			assertEquals(expected, venue.adapterVersions(), "unversioned adapters record nothing");
		} finally {
			engine.close();
		}
	}

	/** The general mechanics, run by the real test adapter's steps across successive starts of one venue. */
	@Test void theTestAdapterRunsTheGeneralMechanics() throws Exception {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(key);
		AMap<AString, ACell> disabled = Maps.of(Config.ADAPTERS, Maps.of("test", Maps.of(Config.ENABLED, CVMBool.FALSE)));
		List<ACell> states = STATES.get("test");
		ACell migrated = states.get(states.size() - 1);
		long supported = new TestAdapter().stateVersion();

		// A disabled adapter migrates too: its legacy value moves, every step runs
		// in order, and its version is recorded.
		Engine first = new Engine(disabled, application, key);
		try {
			VenueState seed = application.venue(key.getAccountKey());
			seed.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, first.getDIDString()));
			seed.users().ensure(first.getDIDString()).writeInternalPath(WorkspaceRoot.parseStringPath("w/adapters/test"), states.get(0));
			first.prepareLaunch();
			assertEquals(supported, first.getVenueState().adapterVersion("test"));
			assertEquals(migrated, first.getVenueState().adapterState("test").get());
		} finally {
			first.close();
		}
		VenueState venue = application.venue(key.getAccountKey());

		// A current state is not rewritten by the next start, nor by an explicit migrate.
		ACell current = RT.getIn(venue.get(), Covia.ADAPTERS);
		Engine second = new Engine(Maps.empty(), application, key);
		try {
			second.prepareLaunch();
			assertSame(current, RT.getIn(second.getVenueState().get(), Covia.ADAPTERS));
			// Engine.migrate covers installed adapters; launch would install it.
			second.registerAdapter(new TestAdapter());
			second.migrate();
			assertSame(current, RT.getIn(second.getVenueState().get(), Covia.ADAPTERS));

			// State at an older version, as a restore can bring it, runs only the
			// steps from that version on the next call.
			second.getVenueState().transact(draft -> {
				draft.adapterState("test").set(states.get(2));
				draft.setAdapterVersion("test", 2);
			});
			second.migrate();
			assertEquals(supported, second.getVenueState().adapterVersion("test"));
			assertEquals(migrated, second.getVenueState().adapterState("test").get());
		} finally {
			second.close();
		}

		// A start refuses state a step does not recognise, writing nothing: the
		// current state recorded at version 1 already holds the later steps' log.
		venue.setAdapterVersion("test", 1);
		ACell mislabelled = venue.get();
		Engine unrecognised = new Engine(Maps.empty(), application, key);
		try {
			IllegalStateException error = assertThrows(IllegalStateException.class, unrecognised::prepareLaunch);
			assertEquals(TestAdapter.UNRECOGNISED_STATE.formatted(1), error.getMessage());
		} finally {
			unrecognised.close();
		}
		assertSame(mislabelled, venue.get(), "the refused start wrote nothing");

		// A start refuses stored state newer than the adapter, writing nothing.
		venue.setAdapterVersion("test", supported + 1);
		ACell before = venue.get();
		Engine refused = new Engine(Maps.empty(), application, key);
		try {
			UnsupportedVersionException error = assertThrows(UnsupportedVersionException.class, refused::prepareLaunch);
			assertEquals("test", error.adapter());
			assertEquals(supported + 1, error.stored());
			assertEquals(supported, error.supported());
		} finally {
			refused.close();
		}
		assertSame(before, venue.get(), "the refused start wrote nothing");

		// With no stored state there is nothing to misread: the adapter's own version takes over.
		venue.transact(draft -> draft.adapterState("test").set(null));
		Engine taken = new Engine(Maps.empty(), application, key);
		try {
			taken.prepareLaunch();
			assertEquals(supported, taken.getVenueState().adapterVersion("test"));
		} finally {
			taken.close();
		}
	}

	/** A step refuses state that is not its version's shape. */
	@Test void theTestAdapterStepsRefuseOtherShapes() {
		var steps = new TestAdapter().stateSteps();
		List<ACell> states = STATES.get("test");
		assertEquals(TestAdapter.UNRECOGNISED_STATE.formatted(1),
			assertThrows(IllegalStateException.class, () -> steps.get(1).apply(Vectors.of("not a map"))).getMessage());
		assertEquals(TestAdapter.UNRECOGNISED_STATE.formatted(1),
			assertThrows(IllegalStateException.class, () -> steps.get(1).apply(states.get(2))).getMessage(), "a step runs once");
		assertEquals(TestAdapter.UNRECOGNISED_STATE.formatted(2),
			assertThrows(IllegalStateException.class, () -> steps.get(2).apply(states.get(1))).getMessage(), "steps run in order");
	}
}
