package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.api.Fields;

/**
 * Online Etch GC on the <em>shared</em> {@link TestEngine} while the rest of
 * the suite is using it (covia#452). This is the real concurrency test for
 * "safe while running": the sweep runs under whatever the parallel test
 * classes are doing to the engine at the time, plus this test's own writers,
 * and afterwards the engine must keep serving on its original store handle —
 * exactly what a live venue does after {@code venue:gc}.
 *
 * <p>Runs the cycle directly on {@link TestEngine#STORE} rather than through
 * the operation (the shared engine has no host, hence no store seam). One
 * cycle per JVM, as for a venue; the successor is parked for close at exit.</p>
 */
public class SharedEngineOnlineGcTest {

	private static AString big(String tag) {
		return Strings.create(tag + "-" + "y".repeat(200));
	}

	private static void write(Engine engine, AString user, String path, ACell value) throws Exception {
		engine.jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), RequestContext.of(user)).get(10, TimeUnit.SECONDS);
	}

	private static ACell read(Engine engine, AString user, String path) throws Exception {
		ACell r = engine.jobs().invokeInternal("v/ops/covia/read",
			Maps.of(Fields.PATH, path), RequestContext.of(user)).get(10, TimeUnit.SECONDS);
		return RT.getIn(r, Fields.VALUE);
	}

	@Test
	public void collectsTheSharedStoreWhileTheSuiteRuns() throws Exception {
		Engine engine = TestEngine.ENGINE;
		EtchStore store = TestEngine.STORE;
		assertFalse(store.isGCInProgress(), "only this test collects the shared store");

		// Own writers: several users appending non-embedded values throughout
		// the sweep, so live novelty lands in the target mid-cycle whatever the
		// rest of the suite happens to be doing.
		AtomicBoolean stop = new AtomicBoolean();
		int writerCount = 4;
		CountDownLatch gcStarted = new CountDownLatch(1);
		CountDownLatch firstAttempts = new CountDownLatch(writerCount);
		List<AtomicInteger> counts = new ArrayList<>();
		List<Thread> writers = new ArrayList<>();
		List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
		for (int w = 0; w < writerCount; w++) {
			AString user = TestEngine.uniqueDID("gc-writer-" + w);
			AtomicInteger count = new AtomicInteger();
			counts.add(count);
			Thread t = Thread.ofVirtual().name("gc-writer-" + w).start(() -> {
				try {
					gcStarted.await();
					while (!stop.get()) {
						int i = count.get();
						try {
							write(engine, user, "w/gc-load/" + i, big("load-" + i));
							count.incrementAndGet();
						} finally {
							if (i == 0) firstAttempts.countDown();
						}
					}
				} catch (Throwable e) {
					failures.add(e);
				}
			});
			writers.add(t);
		}

		try {
			store.startGC();
			assertTrue(store.isGCInProgress());
			gcStarted.countDown();
			assertTrue(firstAttempts.await(15, TimeUnit.SECONDS),
				"each writer must attempt a write during the cycle");
			assertTrue(failures.isEmpty(), "writers must not fail during the cycle: " + failures);
			for (AtomicInteger c : counts) assertTrue(c.get() > 0,
				"each writer must have written during the cycle");
			store.transferGC();
			assertTrue(store.isGCComplete());
			assertTrue(store.verifyGC().isEmpty(), "sweep must leave nothing reachable behind");
		} finally {
			stop.set(true);
			gcStarted.countDown();
			for (Thread t : writers) t.join(TimeUnit.SECONDS.toMillis(30));
		}
		assertTrue(failures.isEmpty(), "writers must not fail during the cycle: " + failures);

		// Everything the live root reaches — including what was written during
		// the sweep — is in the target, then cut over.
		engine.flush();
		assertTrue(store.verifyGC().isEmpty(), "live writes during the cycle must be in the target");
		EtchStore successor = store.completeGC();
		TestEngine.adoptCollected(successor);
		assertFalse(store.isGCInProgress());
		assertTrue(successor.getEtch().getDataLength() > 0);

		// The engine keeps serving on the old handle: everything written before
		// and during the cycle resolves, new writes land, barriers work — and so
		// does every test class still running alongside.
		for (int w = 0; w < writerCount; w++) {
			AString user = TestEngine.uniqueDID("gc-writer-" + w);
			int n = counts.get(w).get();
			for (int i = 0; i < n; i += Math.max(1, n / 5)) {
				assertEquals(big("load-" + i), read(engine, user, "w/gc-load/" + i), "user " + w + " entry " + i);
			}
			write(engine, user, "w/after-gc", Strings.create("after"));
			assertEquals(Strings.create("after"), read(engine, user, "w/after-gc"));
		}
		engine.flush();
	}
}
