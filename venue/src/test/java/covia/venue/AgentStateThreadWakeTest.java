package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.AgentScheduler.ThreadKind;
import covia.venue.AgentScheduler.ThreadRef;

/**
 * Unit test for {@link AgentState#setThreadWakeTime} (B8.8 single-writer
 * helper). Uses a fresh {@link AgentScheduler} with a capturing fire
 * consumer so we can assert on the exact ref and timing without going
 * through the full {@code wakeAgent} → run-loop path (covered elsewhere).
 */
public class AgentStateThreadWakeTest {

	private static final Engine engine = TestEngine.ENGINE;
	private AString userDid;
	private RequestContext ctx;
	private AgentState agent;

	private AgentScheduler scheduler;
	private BlockingQueue<ThreadRef> fires;

	@BeforeEach
	public void setup(TestInfo info) {
		userDid = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(userDid);
		User user = engine.getVenueState().users().ensure(userDid);
		agent = user.ensureAgent("thread-wake-agent", Maps.empty(), null);

		fires = new LinkedBlockingQueue<>();
		scheduler = new AgentScheduler(fires::add);
	}

	private Blob makeSession(String hex, AString caller) {
		Blob sid = Blob.fromHex(hex);
		agent.ensureSession(sid, caller);
		return sid;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> sessionRecord(Blob sid) {
		return (AMap<AString, ACell>) agent.getSessions().get(sid);
	}

	// ========== Session wake ==========

	@Test
	public void testSessionWakeWritesLatticeAndSchedulesFire() throws Exception {
		Blob sid = makeSession("aa", userDid);
		long target = System.currentTimeMillis() + 30;

		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sid, target);

		// Lattice: session record carries wakeTime.
		AMap<AString, ACell> rec = sessionRecord(sid);
		assertNotNull(rec);
		ACell wt = rec.get(Fields.WAKE_TIME);
		assertTrue(wt instanceof CVMLong, "wakeTime should be CVMLong");
		assertEquals(target, ((CVMLong) wt).longValue());

		// Scheduler: ref is known and fires with matching identity.
		ThreadRef expected =
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.SESSION, sid);
		assertTrue(scheduler.contains(expected));

		ThreadRef fired = fires.poll(2, TimeUnit.SECONDS);
		assertNotNull(fired, "scheduler should fire within budget");
		assertEquals(expected, fired);
	}

	// ========== Task wake ==========

	@Test
	public void testTaskWakeWritesLatticeAndSchedulesFire() throws Exception {
		Blob taskId = Blob.fromHex("bb");
		// AgentState expects task entries to be maps (carries wakeTime etc.)
		agent.addTask(taskId, Maps.of(
			Fields.INPUT, Strings.create("do work")));

		long target = System.currentTimeMillis() + 30;

		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.TASK, taskId, target);

		AMap<AString, ACell> taskRec = (AMap<AString, ACell>) agent.getTasks().get(taskId);
		assertNotNull(taskRec);
		ACell wt = taskRec.get(Fields.WAKE_TIME);
		assertTrue(wt instanceof CVMLong);
		assertEquals(target, ((CVMLong) wt).longValue());

		ThreadRef expected =
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.TASK, taskId);
		assertTrue(scheduler.contains(expected));

		ThreadRef fired = fires.poll(2, TimeUnit.SECONDS);
		assertNotNull(fired);
		assertEquals(expected, fired);
	}

	// ========== Replace-semantics ==========

	@Test
	public void testReplaceOverwritesPriorWakeAndCancelsPriorFire() throws Exception {
		Blob sid = makeSession("cc", userDid);

		// First schedule — far future so we'd notice if it fired.
		long farFuture = System.currentTimeMillis() + 60_000;
		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sid, farFuture);

		// Replace with near-future target.
		long near = System.currentTimeMillis() + 30;
		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sid, near);

		// Lattice reflects the later write.
		assertEquals(near, ((CVMLong) sessionRecord(sid).get(Fields.WAKE_TIME)).longValue());

		// Exactly one fire — the first future was cancelled by the second schedule.
		ThreadRef first = fires.poll(2, TimeUnit.SECONDS);
		assertNotNull(first);
		ThreadRef extra = fires.poll(200, TimeUnit.MILLISECONDS);
		assertNull(extra, "replaced schedule must not fire");
	}

	// ========== Clear ==========

	@Test
	public void testClearRemovesFieldAndCancelsFire() throws Exception {
		Blob sid = makeSession("dd", userDid);

		// Schedule far enough out that the default test window won't fire it.
		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sid,
			System.currentTimeMillis() + 10_000);
		assertTrue(scheduler.contains(
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.SESSION, sid)));
		assertNotNull(sessionRecord(sid).get(Fields.WAKE_TIME));

		// Clear.
		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sid, 0);

		assertNull(sessionRecord(sid).get(Fields.WAKE_TIME),
			"wakeTime should be dissoc'd from the session record");
		assertFalse(scheduler.contains(
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.SESSION, sid)));

		// No fire should arrive.
		assertNull(fires.poll(150, TimeUnit.MILLISECONDS));
	}

	// ========== Missing target ==========

	@Test
	public void testMissingSessionIsNoop() throws Exception {
		Blob sid = Blob.fromHex("ee"); // never ensureSession'd

		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sid,
			System.currentTimeMillis() + 30);

		// No session, no lattice change, no scheduler registration.
		assertNull(agent.getSessions().get(sid));
		assertFalse(scheduler.contains(
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.SESSION, sid)));
		assertEquals(0, scheduler.size());
		assertNull(fires.poll(150, TimeUnit.MILLISECONDS));
	}

	@Test
	public void testMissingTaskIsNoop() throws Exception {
		Blob taskId = Blob.fromHex("ff"); // never addTask'd

		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.TASK, taskId,
			System.currentTimeMillis() + 30);

		assertNull(agent.getTasks().get(taskId));
		assertFalse(scheduler.contains(
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.TASK, taskId)));
		assertNull(fires.poll(150, TimeUnit.MILLISECONDS));
	}

	// ========== Independence of threads ==========

	@Test
	public void testSessionsAreScheduledIndependently() throws Exception {
		Blob sidA = makeSession("a0", userDid);
		Blob sidB = makeSession("b0", userDid);

		long now = System.currentTimeMillis();
		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sidA, now + 20);
		agent.setThreadWakeTime(scheduler, userDid, ThreadKind.SESSION, sidB, now + 60_000);

		// A fires; B does not (within the test window).
		ThreadRef fired = fires.poll(2, TimeUnit.SECONDS);
		assertNotNull(fired);
		assertEquals(sidA, fired.threadId());

		assertTrue(scheduler.contains(
			new ThreadRef(userDid, agent.getAgentId(), ThreadKind.SESSION, sidB)),
			"unrelated session wake should remain scheduled");
		assertNull(fires.poll(150, TimeUnit.MILLISECONDS));
	}

	// ========== Engine wiring sanity ==========

	@Test
	public void testEngineExposesScheduler() {
		AgentScheduler s = engine.scheduler();
		assertNotNull(s, "Engine should expose a non-null scheduler");
	}
}
