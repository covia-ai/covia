package covia.venue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;

/**
 * The engine's own durability barriers: {@link Engine#flush()} and the final
 * flush on {@link Engine#close()}. Periodic sync and flush belong to whoever
 * opened the host ({@code VenueServerHostWorkersTest}); the engine runs neither.
 */
public class EngineFlushTest {

	/** Counts the store's durability barriers. */
	private static final class CountingStore extends convex.core.store.MemoryStore {
		final AtomicInteger flushCount = new AtomicInteger();

		@Override
		public void flush() {
			flushCount.incrementAndGet();
		}
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> testConfig() {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Strings.create("name"), Strings.create("flush-test"),
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	private static Engine engineWith(CountingStore store) throws Exception {
		Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
		AKeyPair key = AKeyPair.generate();
		return new Engine(testConfig(), CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, store), key), key).start();
	}

	@Test
	public void testFlushInvokesTheStoreBarrier() throws Exception {
		CountingStore store = new CountingStore();
		Engine engine = engineWith(store);
		try {
			int before = store.flushCount.get();
			engine.flush();
			assertTrue(store.flushCount.get() > before, "engine.flush() must reach the store's barrier");
		} finally {
			engine.close();
		}
	}

	@Test
	public void testCloseRunsFinalFlush() throws Exception {
		CountingStore store = new CountingStore();
		Engine engine = engineWith(store);
		int before = store.flushCount.get();
		engine.close();
		assertTrue(store.flushCount.get() > before, "close() must run a final flush");
	}

	@Test
	public void testInMemoryApplicationFlushes() throws Exception {
		AKeyPair key = AKeyPair.generate();
		Engine engine = new Engine(testConfig(), CoviaApplication.create(key), key).start();
		try {
			engine.flush();
		} finally {
			engine.close();
		}
	}
}
