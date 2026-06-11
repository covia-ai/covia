package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.AgentState.ThreadKind;

/**
 * Unit test for {@link AgentState#setThreadWakeTime} and the per-agent-earliest
 * model it drives (GRID_SCHEDULER.md §8). Per-thread {@code wakeTime} stays
 * authoritative on the lattice; the agent holds at most one {@code agent:wake}
 * event in the venue grid scheduler, armed at the earliest of them. Assertions
 * read the owner-scoped {@code Scheduler.list} output rather than any in-memory
 * structure — the actual fire path is covered behaviourally elsewhere. Wake
 * times are far-future so nothing fires mid-test.
 */
public class AgentStateThreadWakeTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final long FAR = 600_000L;   // 10 min — safely beyond the test window

	private AString userDid;
	private RequestContext ctx;
	private AgentState agent;

	@BeforeEach
	public void setup(TestInfo info) {
		userDid = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(userDid);
		User user = engine.getVenueState().users().ensure(userDid);
		agent = user.ensureAgent("thread-wake-agent", Maps.empty(), null);
	}

	private Scheduler sched() { return engine.gridScheduler(); }

	/** This owner's pending wake events, soonest first. */
	private AVector<ACell> wakeEvents() { return sched().list(ctx); }

	@SuppressWarnings("unchecked")
	private static long timeOf(ACell event) {
		return ((CVMLong) ((AMap<AString, ACell>) event).get(Scheduler.K_TIME)).longValue();
	}

	@SuppressWarnings("unchecked")
	private static AString opOf(ACell event) {
		return (AString) ((AMap<AString, ACell>) event).get(Scheduler.K_OP);
	}

	private Blob makeSession(String hex) {
		Blob sid = Blob.fromHex(hex);
		agent.ensureSession(sid, userDid);
		return sid;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> sessionRecord(Blob sid) {
		return (AMap<AString, ACell>) agent.getSessions().get(sid);
	}

	// ========== Session wake ==========

	@Test
	public void testSessionWakeWritesLatticeAndArmsEvent() {
		Blob sid = makeSession("aa");
		long target = System.currentTimeMillis() + FAR;

		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sid, target);

		// Lattice: session record carries wakeTime.
		ACell wt = sessionRecord(sid).get(Fields.WAKE_TIME);
		assertTrue(wt instanceof CVMLong, "wakeTime should be CVMLong");
		assertEquals(target, ((CVMLong) wt).longValue());

		// Scheduler: exactly one agent:wake event, at the requested time.
		AVector<ACell> events = wakeEvents();
		assertEquals(1, events.count(), "one wake event should be armed");
		assertEquals(AgentState.TRIGGER_OP, opOf(events.get(0)));
		assertEquals(target, timeOf(events.get(0)));
	}

	// ========== Task wake ==========

	@Test
	public void testTaskWakeWritesLatticeAndArmsEvent() {
		Blob taskId = Blob.fromHex("bb");
		agent.addTask(taskId, Maps.empty());
		long target = System.currentTimeMillis() + FAR;

		agent.setThreadWakeTime(sched(), userDid, ThreadKind.TASK, taskId, target);

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> taskRec = (AMap<AString, ACell>) agent.getTasks().get(taskId);
		assertEquals(target, ((CVMLong) taskRec.get(Fields.WAKE_TIME)).longValue());

		AVector<ACell> events = wakeEvents();
		assertEquals(1, events.count());
		assertEquals(target, timeOf(events.get(0)));
	}

	// ========== Replace-semantics ==========

	@Test
	public void testReplaceOverwritesPriorWake() {
		Blob sid = makeSession("cc");
		long first = System.currentTimeMillis() + FAR;
		long second = first + FAR;

		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sid, first);
		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sid, second);

		// Lattice reflects the later write; still exactly one armed event, at the new time.
		assertEquals(second, ((CVMLong) sessionRecord(sid).get(Fields.WAKE_TIME)).longValue());
		AVector<ACell> events = wakeEvents();
		assertEquals(1, events.count(), "replace must not leave a second event");
		assertEquals(second, timeOf(events.get(0)));
	}

	// ========== Earliest across threads ==========

	@Test
	public void testSingleEventAtEarliestAcrossThreads() {
		Blob sidA = makeSession("a0");
		Blob sidB = makeSession("b0");
		long now = System.currentTimeMillis();
		long earlier = now + FAR;
		long later   = now + FAR * 2;

		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sidA, earlier);
		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sidB, later);

		// One event for the agent, armed at the earliest of the two threads.
		AVector<ACell> events = wakeEvents();
		assertEquals(1, events.count(), "agent holds at most one wake event");
		assertEquals(earlier, timeOf(events.get(0)));

		// Clearing the earlier thread re-arms at the next earliest (the later one).
		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sidA, 0);
		assertNull(sessionRecord(sidA).get(Fields.WAKE_TIME));
		AVector<ACell> after = wakeEvents();
		assertEquals(1, after.count());
		assertEquals(later, timeOf(after.get(0)));
	}

	// ========== Clear ==========

	@Test
	public void testClearRemovesFieldAndEvent() {
		Blob sid = makeSession("dd");
		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sid,
			System.currentTimeMillis() + FAR);
		assertEquals(1, wakeEvents().count());

		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sid, 0);

		assertNull(sessionRecord(sid).get(Fields.WAKE_TIME),
			"wakeTime should be dissoc'd from the session record");
		assertEquals(0, wakeEvents().count(), "no event should remain after clear");
	}

	// ========== Missing target ==========

	@Test
	public void testMissingSessionIsNoop() {
		Blob sid = Blob.fromHex("ee"); // never ensureSession'd
		agent.setThreadWakeTime(sched(), userDid, ThreadKind.SESSION, sid,
			System.currentTimeMillis() + FAR);

		assertNull(agent.getSessions().get(sid));
		assertEquals(0, wakeEvents().count());
	}

	@Test
	public void testMissingTaskIsNoop() {
		Blob taskId = Blob.fromHex("ff"); // never addTask'd
		agent.setThreadWakeTime(sched(), userDid, ThreadKind.TASK, taskId,
			System.currentTimeMillis() + FAR);

		assertNull(agent.getTasks().get(taskId));
		assertEquals(0, wakeEvents().count());
	}

	// ========== Engine wiring sanity ==========

	@Test
	public void testEngineExposesGridScheduler() {
		assertNotNull(engine.gridScheduler(), "Engine should expose a non-null grid scheduler");
	}
}
