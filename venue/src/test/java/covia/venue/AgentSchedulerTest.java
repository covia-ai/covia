package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.venue.AgentScheduler.ThreadKind;
import covia.venue.AgentScheduler.ThreadRef;

/**
 * Standalone validation for {@link AgentScheduler} (B8.8 first slice).
 *
 * <p>Exercises the STPE-backed wrapper without any lattice wiring —
 * the fire action is a test consumer that records refs into a queue
 * or decrements a latch.</p>
 */
public class AgentSchedulerTest {

	private static final AString USER = Strings.create("did:test:user");
	private static final AString AGENT = Strings.create("did:test:agent");

	private static ThreadRef sessionRef(String hex) {
		return new ThreadRef(USER, AGENT, ThreadKind.SESSION, Blob.fromHex(hex));
	}

	private static ThreadRef taskRef(String hex) {
		return new ThreadRef(USER, AGENT, ThreadKind.TASK, Blob.fromHex(hex));
	}

	@Test
	public void testScheduleAndFire() throws Exception {
		BlockingQueue<ThreadRef> fires = new LinkedBlockingQueue<>();
		AgentScheduler sched = new AgentScheduler(fires::add);
		try {
			ThreadRef ref = sessionRef("aa");
			long now = System.currentTimeMillis();
			sched.schedule(ref, now + 30);
			assertTrue(sched.contains(ref));
			assertEquals(1, sched.size());

			ThreadRef fired = fires.poll(2, TimeUnit.SECONDS);
			assertNotNull(fired, "fire action was not invoked");
			assertEquals(ref, fired);

			// Fire clears the handles slot.
			// Poll briefly — fire() removes before dispatching, but the
			// dispatched vthread is independent so size drops immediately.
			long deadline = System.currentTimeMillis() + 500;
			while (sched.size() != 0 && System.currentTimeMillis() < deadline) {
				Thread.sleep(5);
			}
			assertEquals(0, sched.size());
			assertFalse(sched.contains(ref));
		} finally {
			sched.shutdown();
		}
	}

	@Test
	public void testPastWakeTimeFiresImmediately() throws Exception {
		CountDownLatch fired = new CountDownLatch(1);
		AgentScheduler sched = new AgentScheduler(r -> fired.countDown());
		try {
			sched.schedule(taskRef("01"), System.currentTimeMillis() - 10_000);
			assertTrue(fired.await(2, TimeUnit.SECONDS), "past wakeTime should fire promptly");
		} finally {
			sched.shutdown();
		}
	}

	@Test
	public void testCancelBeforeFire() throws Exception {
		AtomicInteger fires = new AtomicInteger();
		AgentScheduler sched = new AgentScheduler(r -> fires.incrementAndGet());
		try {
			ThreadRef ref = sessionRef("bb");
			sched.schedule(ref, System.currentTimeMillis() + 10_000);
			assertTrue(sched.contains(ref));

			sched.cancel(ref);
			assertFalse(sched.contains(ref));
			assertEquals(0, sched.size());

			// Cancelling a missing ref is a no-op.
			sched.cancel(ref);

			// Give the STPE a chance to (not) fire.
			Thread.sleep(100);
			assertEquals(0, fires.get());
		} finally {
			sched.shutdown();
		}
	}

	@Test
	public void testReplaceSemantics() throws Exception {
		// Schedule a far-future wake, then replace with a near-future wake.
		// Only the second should fire.
		BlockingQueue<ThreadRef> fires = new LinkedBlockingQueue<>();
		AgentScheduler sched = new AgentScheduler(fires::add);
		try {
			ThreadRef ref = sessionRef("cc");
			sched.schedule(ref, System.currentTimeMillis() + 60_000);
			sched.schedule(ref, System.currentTimeMillis() + 30);

			ThreadRef fired = fires.poll(2, TimeUnit.SECONDS);
			assertNotNull(fired, "second schedule should fire");
			assertEquals(ref, fired);

			// Exactly one fire — the first future was cancelled.
			ThreadRef extra = fires.poll(150, TimeUnit.MILLISECONDS);
			assertTrue(extra == null, "first (replaced) schedule must not fire");
		} finally {
			sched.shutdown();
		}
	}

	@Test
	public void testMultipleIndependentRefs() throws Exception {
		BlockingQueue<ThreadRef> fires = new LinkedBlockingQueue<>();
		AgentScheduler sched = new AgentScheduler(fires::add);
		try {
			ThreadRef a = sessionRef("aa");
			ThreadRef b = sessionRef("bb");
			ThreadRef c = taskRef("cc");

			long now = System.currentTimeMillis();
			sched.schedule(a, now + 20);
			sched.schedule(b, now + 40);
			sched.schedule(c, now + 60);
			assertEquals(3, sched.size());

			ThreadRef f1 = fires.poll(2, TimeUnit.SECONDS);
			ThreadRef f2 = fires.poll(2, TimeUnit.SECONDS);
			ThreadRef f3 = fires.poll(2, TimeUnit.SECONDS);
			assertEquals(a, f1);
			assertEquals(b, f2);
			assertEquals(c, f3);
		} finally {
			sched.shutdown();
		}
	}

	@Test
	public void testShutdownCancelsPending() throws Exception {
		AtomicInteger fires = new AtomicInteger();
		AgentScheduler sched = new AgentScheduler(r -> fires.incrementAndGet());

		ThreadRef ref = sessionRef("dd");
		sched.schedule(ref, System.currentTimeMillis() + 10_000);
		assertTrue(sched.contains(ref));

		sched.shutdown();
		assertEquals(0, sched.size());

		// Schedules after shutdown are dropped.
		sched.schedule(ref, System.currentTimeMillis() + 10);

		Thread.sleep(100);
		assertEquals(0, fires.get());
	}

	@Test
	public void testThreadRefEquality() {
		ThreadRef a1 = new ThreadRef(USER, AGENT, ThreadKind.SESSION, Blob.fromHex("aa"));
		ThreadRef a2 = new ThreadRef(USER, AGENT, ThreadKind.SESSION, Blob.fromHex("aa"));
		ThreadRef b = new ThreadRef(USER, AGENT, ThreadKind.TASK, Blob.fromHex("aa"));
		assertEquals(a1, a2);
		assertEquals(a1.hashCode(), a2.hashCode());
		assertFalse(a1.equals(b));
		assertSame(ThreadKind.SESSION, a1.kind());
	}
}
