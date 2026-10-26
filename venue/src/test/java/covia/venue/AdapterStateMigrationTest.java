package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.lattice.WorkspaceRoot;

/** Adapters migrate their own state schema when they register (#621). */
class AdapterStateMigrationTest {
	private static final String NAME = "versioned";
	private static final AString SCHEMA = Strings.create("schema");

	/** State at version 0 is a vector; 1 wraps it as {items}; 2 adds {schema: 2}. */
	private static final class Versioned extends AAdapter {
		private final String name;
		private final long version;
		final AtomicInteger steps = new AtomicInteger();

		Versioned(String name, long version) {
			this.name = name;
			this.version = version;
		}

		@Override public String getName() { return name; }
		@Override public String getDescription() { return "Adapter with versioned state, for tests"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
		@Override public long stateVersion() { return version; }
		@Override public ACell migrateState(long from, ACell state) {
			steps.incrementAndGet();
			return switch ((int) from) {
				case 0 -> Maps.of("items", state);
				case 1 -> RT.ensureMap(state).assoc(SCHEMA, CVMLong.create(2));
				default -> super.migrateState(from, state);
			};
		}
	}

	private static Engine prepared(AMap<AString, ACell> config) throws IOException {
		AKeyPair key = AKeyPair.generate();
		return new Engine(config, CoviaApplication.create(key), key).prepare();
	}

	@Test void legacyStateMigratesOnceInOrderWithItsVersion() throws Exception {
		Engine engine = prepared(Maps.empty());
		try {
			VenueState venue = engine.getVenueState();
			ACell[] legacyPath = WorkspaceRoot.parseStringPath("w/adapters/" + NAME);
			venue.users().get(engine.getDIDString()).writeInternalPath(legacyPath, Vectors.of("a", "b"));

			Versioned adapter = new Versioned(NAME, 2);
			engine.registerAdapter(adapter);
			assertEquals(2, adapter.steps.get());
			assertEquals(Maps.of("items", Vectors.of("a", "b")).assoc(SCHEMA, CVMLong.create(2)), venue.adapterState(NAME).get());
			assertEquals(2, venue.adapterVersion(NAME));
			assertNull(venue.users().get(engine.getDIDString()).readInternalPath(legacyPath), "the legacy route moves first");
			assertSame(adapter, engine.getAdapter(NAME));

			ACell state = venue.adapterState(NAME).get();
			Versioned again = new Versioned(NAME, 2);
			engine.registerAdapter(again);
			assertEquals(0, again.steps.get(), "a current state runs no step");
			assertSame(state, venue.adapterState(NAME).get(), "a current state is not rewritten");
			assertEquals(2, venue.adapterVersion(NAME));
		} finally {
			engine.close();
		}
	}

	@Test void newerStateIsRefusedBeforeAnythingChanges() throws Exception {
		Engine engine = prepared(Maps.empty());
		try {
			VenueState venue = engine.getVenueState();
			venue.adapterState(NAME).set(Vectors.of("future"));
			venue.setAdapterVersion(NAME, 3);
			ACell before = venue.get();

			var error = assertThrows(UnsupportedVersionException.class, () -> engine.registerAdapter(new Versioned(NAME, 1)));
			assertEquals(VenueState.UNSUPPORTED_ADAPTER_VERSION.formatted(NAME, 3L, 1L), error.getMessage());
			assertEquals(NAME, error.adapter());
			assertEquals(3, error.stored());
			assertEquals(1, error.supported());
			// An adapter that does not version its state cannot read versioned state either.
			assertThrows(UnsupportedVersionException.class, () -> engine.registerAdapter(new Versioned(NAME, 0)));
			assertSame(before, venue.get());
			assertNull(engine.getRegisteredAdapter(NAME));
		} finally {
			engine.close();
		}
	}

	@Test void aFailingStepLeavesStateAndVersionUnchanged() throws Exception {
		Engine engine = prepared(Maps.empty());
		try {
			VenueState venue = engine.getVenueState();
			venue.adapterState(NAME).set(Vectors.of("a"));
			ACell before = venue.get();

			var error = assertThrows(IllegalStateException.class, () -> engine.registerAdapter(new Versioned(NAME, 3)));
			assertEquals(AAdapter.NO_STATE_MIGRATION.formatted(NAME, 2L), error.getMessage());
			assertSame(before, venue.get(), "earlier steps roll back with the failing one");
			assertEquals(0, venue.adapterVersion(NAME));
			assertNull(engine.getRegisteredAdapter(NAME));
		} finally {
			engine.close();
		}
	}

	@Test void disabledAdaptersMigrateToo() throws Exception {
		Engine engine = prepared(Maps.of(Config.ADAPTERS, Maps.of(NAME, Maps.of(Config.ENABLED, CVMBool.FALSE))));
		try {
			VenueState venue = engine.getVenueState();
			venue.adapterState(NAME).set(Vectors.of("a"));

			engine.registerAdapter(new Versioned(NAME, 1));
			assertFalse(engine.hasAdapter(NAME));
			assertNotNull(engine.getRegisteredAdapter(NAME), "parked as disabled");
			assertEquals(Maps.of("items", Vectors.of("a")), venue.adapterState(NAME).get());
			assertEquals(1, venue.adapterVersion(NAME));
		} finally {
			engine.close();
		}
	}

	@Test void freshStateIsStampedAndUnversionedAdaptersRecordNothing() throws Exception {
		Engine engine = prepared(Maps.empty());
		try {
			VenueState venue = engine.getVenueState();
			Versioned fresh = new Versioned(NAME, 2);
			engine.registerAdapter(fresh);
			assertEquals(0, fresh.steps.get(), "no state, so no step");
			assertNull(venue.adapterState(NAME).get());
			assertEquals(2, venue.adapterVersion(NAME));

			engine.registerAdapter(new Versioned("unversioned", 0));
			assertEquals(Map.of(NAME, 2L), venue.adapterVersions());
		} finally {
			engine.close();
		}
	}

	@Test void registrationNeedsAPreparedEngineAndAValidVersion() throws Exception {
		AKeyPair key = AKeyPair.generate();
		Engine unprepared = new Engine(Maps.empty(), CoviaApplication.create(key), key);
		try {
			assertEquals(Engine.ADAPTER_BEFORE_PREPARE,
				assertThrows(IllegalStateException.class, () -> unprepared.registerAdapter(new Versioned(NAME, 1))).getMessage());
		} finally {
			unprepared.close();
		}
		Engine engine = prepared(Maps.empty());
		try {
			assertEquals(Engine.INVALID_STATE_VERSION + NAME,
				assertThrows(IllegalStateException.class, () -> engine.registerAdapter(new Versioned(NAME, -1))).getMessage());
		} finally {
			engine.close();
		}
	}
}
