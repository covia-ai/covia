package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.api.Fields;
import covia.venue.server.VenueServer;

/**
 * Startup garbage collection of the venue's Etch store (covia#451): with
 * {@code etch.gc.onStart} the venue collects the store before it serves —
 * unreachable data goes, root state stays, both handles close cleanly so a
 * plain relaunch opens the collected file. The failed-cycle path lives in
 * {@link EtchGcOnStartFailureTest}, which must run alone.
 *
 * <p>Venue launches are the expensive part, so the whole flag-off / flag-on /
 * relaunch story runs as one scenario on one store; the edge cases use a bare
 * {@link EtchStore} and no venue.</p>
 */
public class EtchGcOnStartTest {

	private static final String SEED_HEX =
		"6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a";
	private static final AString USER = Strings.create("did:key:z6Mk-test-etch-gc");
	private static final AString GC = Strings.intern("gc");
	private static final AString ON_START = Strings.intern("onStart");

	private static AMap<AString, ACell> config(String storePath, boolean gcOnStart) {
		AMap<AString, ACell> cfg = Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(SEED_HEX),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		if (gcOnStart) cfg = cfg.assoc(Config.ETCH, Maps.of(GC, Maps.of(ON_START, true)));
		return cfg;
	}

	private static File storeFile(String prefix) throws Exception {
		return new File(TestTemp.dir(prefix).toFile(), "venue.etch");
	}

	private static void write(VenueServer v, String path, ACell value) throws Exception {
		v.getEngine().jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), RequestContext.of(USER))
			.get(5, TimeUnit.SECONDS);
	}

	private static ACell read(VenueServer v, String path) throws Exception {
		ACell read = v.getEngine().jobs().invokeInternal("v/ops/covia/read",
			Maps.of(Fields.PATH, path), RequestContext.of(USER)).get(5, TimeUnit.SECONDS);
		return RT.getIn(read, Fields.VALUE);
	}

	/** A non-embedded string: longer than the embedding limit, so it is its own entry. */
	private static AString big(String tag) {
		return Strings.create(tag + "-" + "x".repeat(200));
	}

	/**
	 * Persists trees nothing references into a store the venue has not yet
	 * initialised — garbage by the retention contract — and returns the file's
	 * data length. The venue then lays its root down beside it.
	 */
	static long addGarbage(File file) throws Exception {
		EtchStore store = EtchStore.create(file);
		try {
			for (int i = 0; i < 40; i++) {
				AVector<ACell> junk = Vectors.empty();
				for (int j = 0; j < 50; j++) junk = junk.conj(big("garbage-" + i + "-" + j));
				Cells.persist(junk, store);
			}
			store.flush();
			return store.getEtch().getDataLength();
		} finally {
			store.close();
		}
	}

	@Test
	public void collectsAtStartupAndKeepsState() throws Exception {
		File file = storeFile("etch-gc-onstart");
		String storePath = file.getAbsolutePath().replace('\\', '/');
		long garbage = addGarbage(file);
		AString value = Strings.create("survives collection");

		// 1. Flag off: the venue initialises its root beside the garbage and
		// leaves the file alone.
		long before = 0;
		VenueServer plain = VenueServer.launch(config(storePath, false));
		try {
			write(plain, "w/gc-proof", value);
			plain.getEngine().flush();
			before = ((EtchStore) plain.getStore()).getEtch().getDataLength();
			assertTrue(before >= garbage, "without the flag the store must not shrink");
		} finally {
			plain.close();
		}

		// 2. Flag on: collected before serving; root state intact; the venue
		// writes and flushes normally on the collected store.
		VenueServer collected = VenueServer.launch(config(storePath, true));
		try {
			assertEquals(value, read(collected, "w/gc-proof"), "root state must survive collection");
			EtchStore store = (EtchStore) collected.getStore();
			assertFalse(store.isGCInProgress());
			long after = store.getEtch().getDataLength();
			assertTrue(after < before, "collected store must be smaller: " + after + " vs " + before);
			assertTrue(before - after > garbage / 2,
				"the garbage must be what went: reclaimed " + (before - after) + " of " + garbage);
			write(collected, "w/after-gc", Strings.create("ok"));
			collected.getEngine().flush();
		} finally {
			collected.close();
		}

		// 3. Both handles were closed cleanly: a plain relaunch opens the store
		// (adopted under the base name, or the collected file directly while
		// mappings pin the old one) and sees everything.
		VenueServer again = VenueServer.launch(config(storePath, false));
		try {
			assertEquals(value, read(again, "w/gc-proof"));
			assertEquals(Strings.create("ok"), read(again, "w/after-gc"));
		} finally {
			again.close();
		}
	}

	@Test
	public void storeWithoutARootIsSkipped() throws Exception {
		File f = storeFile("etch-gc-fresh");
		EtchStore store = EtchStore.create(f);
		try {
			assertSame(store, VenueServer.collectAtStartup(store, f, null),
				"nothing to collect: the same handle boots");
			assertFalse(store.isGCInProgress());
		} finally {
			store.close();
		}
	}

	@Test
	public void gcBlockIsValidatedFailClosed() {
		assertTrue(new Config(Maps.of(Config.ETCH, Maps.of(GC, Maps.of(ON_START, true)))).isEtchGcOnStart());
		assertFalse(new Config(Maps.of(Config.ETCH, Maps.of(GC, Maps.of(ON_START, false)))).isEtchGcOnStart());
		assertFalse(new Config(Maps.empty()).isEtchGcOnStart());
		assertThrows(IllegalArgumentException.class, () -> new Config(
			Maps.of(Config.ETCH, Maps.of(GC, Maps.of(ON_START, Strings.create("yes"))))));
		assertThrows(IllegalArgumentException.class, () -> new Config(
			Maps.of(Config.ETCH, Maps.of(GC, Maps.of(Strings.intern("onstart"), true)))));
		assertThrows(IllegalArgumentException.class, () -> new Config(
			Maps.of(Config.ETCH, Maps.of(GC, true))));
	}
}
