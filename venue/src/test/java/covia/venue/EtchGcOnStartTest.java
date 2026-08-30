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
import convex.core.data.Ref;
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
 * plain relaunch opens the collected file — and a failed cycle boots on the
 * untouched original.
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

	private static String storePath(String prefix) throws Exception {
		File dir = TestTemp.dir(prefix).toFile();
		return new File(dir, "venue.etch").getAbsolutePath().replace('\\', '/');
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

	/** Runs a venue on the store, writes one workspace value, closes. */
	private static void seed(String storePath, AString value) throws Exception {
		VenueServer v = VenueServer.launch(config(storePath, false));
		try {
			write(v, "w/gc-proof", value);
			v.getEngine().flush();
		} finally {
			v.close();
		}
	}

	/** A non-embedded string: longer than the embedding limit, so it is its own entry. */
	private static AString big(String tag) {
		return Strings.create(tag + "-" + "x".repeat(200));
	}

	/** Persists trees nothing references — garbage by the retention contract — and returns the file length. */
	private static long addGarbage(String storePath) throws Exception {
		EtchStore store = EtchStore.create(new File(storePath));
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
	public void collectsUnreachableDataAtStartupAndKeepsState() throws Exception {
		String storePath = storePath("etch-gc-onstart");
		AString value = Strings.create("survives collection");
		seed(storePath, value);
		long before = addGarbage(storePath);

		VenueServer collected = VenueServer.launch(config(storePath, true));
		try {
			assertEquals(value, read(collected, "w/gc-proof"), "root state must survive collection");
			EtchStore store = (EtchStore) collected.getStore();
			assertFalse(store.isGCInProgress());
			long after = store.getEtch().getDataLength();
			assertTrue(after < before, "collected store must be smaller: " + after + " vs " + before);
			// The venue writes and flushes normally on the collected store
			write(collected, "w/after-gc", Strings.create("ok"));
			collected.getEngine().flush();
		} finally {
			collected.close();
		}

		// Both handles were closed cleanly: a plain relaunch opens the store
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
	public void flagOffLeavesTheStoreAlone() throws Exception {
		String storePath = storePath("etch-gc-off");
		seed(storePath, Strings.create("kept"));
		long before = addGarbage(storePath);
		VenueServer v = VenueServer.launch(config(storePath, false));
		try {
			assertEquals(Strings.create("kept"), read(v, "w/gc-proof"));
			assertTrue(((EtchStore) v.getStore()).getEtch().getDataLength() >= before,
				"without the flag the store must not shrink");
		} finally {
			v.close();
		}
	}

	@Test
	public void freshStoreWithFlagBootsWithNothingToCollect() throws Exception {
		String storePath = storePath("etch-gc-fresh");
		VenueServer v = VenueServer.launch(config(storePath, true));
		try {
			write(v, "w/first", Strings.create("first"));
			assertEquals(Strings.create("first"), read(v, "w/first"));
		} finally {
			v.close();
		}
	}

	@Test
	public void failedSweepBootsOnTheOriginalStore() throws Exception {
		// A root whose children were never written: the sweep meets missing
		// data, so the cycle must be cancelled and the original store kept.
		File f = new File(storePath("etch-gc-broken"));
		AVector<ACell> tree = Vectors.of(big("left"), big("right"));
		EtchStore broken = EtchStore.create(f);
		broken.storeTopRef(tree.getRef(), Ref.STORED, null); // top entry only
		broken.getEtch().setRootHash(tree.getHash());
		broken.flush();
		broken.close();

		EtchStore store = EtchStore.create(f);
		try {
			EtchStore result = VenueServer.collectAtStartup(store, f, null);
			assertSame(store, result, "a failed cycle boots on the original store");
			assertFalse(store.isGCInProgress(), "the failed cycle must be cancelled");
			assertEquals(tree.getHash(), store.getRootHash(), "the original root is untouched");
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
