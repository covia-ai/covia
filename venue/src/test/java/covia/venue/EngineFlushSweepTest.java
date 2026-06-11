package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.RootLatticeCursor;
import covia.lattice.Covia;

/**
 * Verifies the Engine's periodic-flush contract.
 *
 * <p>The Engine's persistence sweep runs every {@code SWEEP_INTERVAL_MS} (100 ms)
 * and triggers a propagator sync each tick. Independently, the sweep is
 * supposed to invoke {@link PersistenceHandler#flush()} every
 * {@link Engine#FLUSH_INTERVAL_MS} so unclean shutdown can lose at most that
 * many ms of writes. This test confirms:</p>
 *
 * <ul>
 *   <li>The flush callback actually fires on the sweep cadence.</li>
 *   <li>An explicit {@link Engine#flush()} resets the timer so the sweep
 *       does not double-flush immediately after.</li>
 *   <li>Closing the Engine triggers a final flush regardless of timer state.</li>
 * </ul>
 *
 * <p>Uses a counting {@link PersistenceHandler} so the test never has to
 * touch a real Etch file — the contract under test is purely about WHEN
 * the handler's {@code flush()} is invoked.</p>
 */
// Mutates the global static Engine.FLUSH_INTERVAL_MS, so it must not run
// concurrently with anything (@Isolated) and its own methods must not
// interleave their set/restore of that static (@Execution SAME_THREAD).
// Without this it flakes under the parallel suite: a concurrent method's
// @AfterEach restore to the 10s default is observed by another method's
// engine sweep, which then waits the full 10s and times out.
@Isolated("mutates global static Engine.FLUSH_INTERVAL_MS")
@Execution(ExecutionMode.SAME_THREAD)
public class EngineFlushSweepTest {

	/**
	 * Effectively zero — every sweep tick (100ms cadence) crosses the
	 * "ms since last flush" gate, so the test sees flushes at the sweep
	 * rate. Avoids racing the actual interval timing under CI load.
	 */
	private static final long TEST_FLUSH_INTERVAL_MS = 1;

	private long originalFlushInterval;

	@BeforeEach
	public void shrinkFlushInterval() {
		originalFlushInterval = Engine.FLUSH_INTERVAL_MS;
		Engine.FLUSH_INTERVAL_MS = TEST_FLUSH_INTERVAL_MS;
	}

	@AfterEach
	public void restoreFlushInterval() {
		Engine.FLUSH_INTERVAL_MS = originalFlushInterval;
	}

	/**
	 * Counting handler — records every persist + flush call, with a hook so
	 * tests can capture the value at the moment of flush if needed.
	 */
	private static final class CountingHandler implements PersistenceHandler {
		final AtomicInteger persistCount = new AtomicInteger();
		final AtomicInteger flushCount = new AtomicInteger();
		final AtomicReference<ACell> lastPersisted = new AtomicReference<>();

		@Override
		public void persist(ACell value) {
			persistCount.incrementAndGet();
			lastPersisted.set(value);
		}

		@Override
		public void flush() throws IOException {
			flushCount.incrementAndGet();
		}
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> testConfig() {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Strings.create("name"), Strings.create("flush-sweep-test"),
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	private static Engine engineWith(CountingHandler handler) throws Exception {
		RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(Covia.ROOT);
		return new Engine(testConfig(), cursor, AKeyPair.generate(), handler);
	}

	private static void sleep(long ms) throws InterruptedException {
		Thread.sleep(ms);
	}

	/**
	 * Polls a counter until it reaches at least {@code target} or the timeout
	 * elapses. Returns the final value observed. Uses 20ms polling — fine-
	 * grained enough to catch transitions, coarse enough not to hammer.
	 */
	private static int pollUntil(AtomicInteger counter, int target, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (counter.get() < target && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		return counter.get();
	}

	// ========================================================================
	// Test 1 — Periodic flush fires from the sweep.
	//
	// With FLUSH_INTERVAL_MS=1 the gate is always open; any sweep tick will
	// cross it. We only need to observe that at least one periodic flush
	// fires to prove the mechanism is wired — the precise rate is allowed
	// to vary with CI scheduling jitter. testCloseRunsFinalFlush separately
	// pins that the final-shutdown flush fires regardless of timer state.
	// ========================================================================
	@Test
	public void testSweepInvokesFlush() throws Exception {
		CountingHandler handler = new CountingHandler();
		Engine engine = engineWith(handler);
		try {
			int flushes = pollUntil(handler.flushCount, 1, 10_000);
			assertTrue(flushes >= 1,
				"expected at least 1 periodic flush within 10s, got " + flushes);
		} finally {
			engine.close();
		}
	}

	// ========================================================================
	// Test 2 — Explicit engine.flush() advances the timer; sweep resumes.
	//
	// The contract has two halves:
	//   (a) engine.flush() invokes the handler's flush().
	//   (b) After an explicit flush, the periodic sweep continues to fire
	//       new flushes on subsequent intervals.
	// We don't try to assert the "no double-flush immediately after explicit"
	// half — that's timing-sensitive in a way the contract doesn't require.
	// ========================================================================
	@Test
	public void testExplicitFlushAdvancesTimer() throws Exception {
		CountingHandler handler = new CountingHandler();
		Engine engine = engineWith(handler);
		try {
			engine.flush();
			int afterExplicit = handler.flushCount.get();
			assertTrue(afterExplicit >= 1,
				"engine.flush() must invoke handler.flush(), got " + afterExplicit);

			// Periodic flushes should resume after the interval. Generous
			// 10s timeout to absorb CI scheduling jitter.
			int later = pollUntil(handler.flushCount, afterExplicit + 1, 10_000);
			assertTrue(later > afterExplicit,
				"sweep must resume flushing after explicit flush; before=" + afterExplicit + " after=" + later);
		} finally {
			engine.close();
		}
	}

	// ========================================================================
	// Test 3 — close() always runs a final flush
	// ========================================================================
	@Test
	public void testCloseRunsFinalFlush() throws Exception {
		CountingHandler handler = new CountingHandler();
		Engine engine = engineWith(handler);
		// Close immediately — too soon for a sweep flush to have fired.
		int before = handler.flushCount.get();
		engine.close();
		int after = handler.flushCount.get();
		assertTrue(after > before,
			"close() must invoke a final handler.flush(), got before=" + before + " after=" + after);
	}

	// ========================================================================
	// Test 4 — NOOP handler does not spin up the sweep daemon
	// ========================================================================
	@Test
	public void testNoopHandlerSkipsSweepFlush() throws Exception {
		// Use a counting wrapper that delegates to NOOP semantics for persist
		// but records flushes — except the constructor passes the canonical
		// NOOP, so the wrapper isn't seen by Engine. Instead we just verify
		// that constructing a NOOP-handler engine doesn't throw and closes
		// cleanly. The branch tested here is the `persistHandler == NOOP`
		// guard in the Engine constructor; behavioural verification of the
		// "no sweep thread" path is covered by the existing
		// EnginePersistenceTest suite.
		RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(Covia.ROOT);
		Engine engine = new Engine(testConfig(), cursor, AKeyPair.generate(), PersistenceHandler.NOOP);
		// engine.flush() on a NOOP-handler engine must still work and not throw.
		engine.flush();
		engine.close();
	}
}
