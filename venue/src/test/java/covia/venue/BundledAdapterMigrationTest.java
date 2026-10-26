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
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.AuthAdapter;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;

/**
 * State migration of the adapters bundled in covia.jar, through the real
 * adapters and the start-up path. The built-in test adapter carries the
 * general mechanics; {@link AdapterStateMigrationTest} covers them with
 * synthetic adapters.
 */
class BundledAdapterMigrationTest {

	/** The test adapter's state at versions 0 and 1: the same shape. */
	private static final ACell TEST_STATE = Maps.of("users",
		Maps.of("did:test:bundled-scratch", Maps.of("scratch", Maps.of("note", "kept"))));

	/**
	 * Each versioned bundled adapter's state at every version, oldest first.
	 * Raising a bundled adapter's {@code stateVersion} needs a new entry here,
	 * and {@link #everyVersionedBundledAdapterHasStatesForEveryVersion} fails
	 * until it has one.
	 */
	static final Map<String, List<ACell>> STATES = Map.of(
		"test", List.of(TEST_STATE, TEST_STATE));

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
			List<ACell> states = STATES.get(name);
			assertNotNull(states, name + " is at state version " + version + ": add its state at every version to STATES");
			assertEquals(version + 1, states.size(), name + " is at state version " + version + ": STATES needs versions 0 to " + version);
			// From every older version, the steps reach the current state.
			for (int from = 0; from < version; from++) {
				ACell state = states.get(from);
				for (long v = from; v < version; v++) state = adapter.migrateState(v, state);
				assertEquals(states.get((int) version), state, name + " from version " + from);
			}
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

	/** The general mechanics, run by the real test adapter across successive starts of one venue. */
	@Test void theTestAdapterRunsTheGeneralMechanics() throws Exception {
		AKeyPair key = AKeyPair.generate();
		CoviaApplication application = CoviaApplication.create(key);
		AMap<AString, ACell> disabled = Maps.of(Config.ADAPTERS, Maps.of("test", Maps.of(Config.ENABLED, CVMBool.FALSE)));

		// A disabled adapter migrates too: its legacy value moves and its version is recorded.
		Engine first = new Engine(disabled, application, key);
		try {
			VenueState seed = application.venue(key.getAccountKey());
			seed.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, first.getDIDString()));
			seed.users().ensure(first.getDIDString()).writeInternalPath(WorkspaceRoot.parseStringPath("w/adapters/test"), TEST_STATE);
			first.prepareLaunch();
			assertEquals(1, first.getVenueState().adapterVersion("test"));
			assertEquals(TEST_STATE, first.getVenueState().adapterState("test").get());
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
			second.registerAdapter(new covia.adapter.TestAdapter());
			second.migrate();
			assertSame(current, RT.getIn(second.getVenueState().get(), Covia.ADAPTERS));

			// State recorded at an older version, as a restore can bring it, migrates on the next call.
			second.getVenueState().setAdapterVersion("test", 0);
			second.migrate();
			assertEquals(1, second.getVenueState().adapterVersion("test"));
			assertSame(current, RT.getIn(second.getVenueState().get(), Covia.ADAPTERS), "version 1's step keeps the shape");

			// Stored state recorded by a newer build.
			second.getVenueState().setAdapterVersion("test", 5);
		} finally {
			second.close();
		}

		// A start refuses stored state newer than the adapter, writing nothing.
		ACell before = venue.get();
		Engine refused = new Engine(Maps.empty(), application, key);
		try {
			UnsupportedVersionException error = assertThrows(UnsupportedVersionException.class, refused::prepareLaunch);
			assertEquals("test", error.adapter());
			assertEquals(5, error.stored());
			assertEquals(1, error.supported());
		} finally {
			refused.close();
		}
		assertSame(before, venue.get(), "the refused start wrote nothing");

		// With no stored state there is nothing to misread: the adapter's own version takes over.
		venue.transact(draft -> draft.adapterState("test").set(null));
		Engine taken = new Engine(Maps.empty(), application, key);
		try {
			taken.prepareLaunch();
			assertEquals(1, taken.getVenueState().adapterVersion("test"));
		} finally {
			taken.close();
		}
	}
}
