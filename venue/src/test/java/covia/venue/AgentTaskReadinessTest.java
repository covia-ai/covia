package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;

/**
 * When a queued task is ready to run ({@link Agent#isTaskReady}): a task
 * that is not parked always is, and a parked one only once something new is
 * there for it. Each case passes its own {@code now}, so no clock is involved.
 */
public class AgentTaskReadinessTest {

	private static final Blob SID = Blob.fromHex("5e55000000000000000000000000beef");
	private static final Blob TASK = Blob.fromHex("7a5c000000000000000000000000beef");
	private static final long NOW = 1_000_000L;

	private AString did;
	private Agent agent;

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		agent = TestEngine.ENGINE.getVenueState().users().ensure(did)
			.ensureAgent("readiness", Maps.empty(), null);
		agent.ensureSession(SID, did);
		agent.tasks().add(TASK, Maps.of(
			Fields.INPUT, Maps.of("ask", "something"),
			Fields.SESSION_ID, Strings.create(SID.toHexString()),
			Fields.CREATED, CVMLong.create(1)));
	}

	private ACell row() {
		return agent.getTasks().get(TASK);
	}

	/** Starts a cycle on the task, as the run loop does: its next input is shown. */
	private void startCycle() {
		assertEquals(Agent.CycleStart.NO_SESSION,
			agent.beginCycle(TASK, row(), null, Strings.create("claim"), Vectors.empty(), 0));
	}

	/** Shows the task its input and parks it, as a cycle that yields does. */
	private void presentAndPark() {
		startCycle();
		agent.tasks().task(TASK).park(0, 1);
		assertTrue(Task.isParked(row()));
	}

	@Test
	public void aTaskNotParkedIsReady() {
		assertTrue(agent.isTaskReady(row(), NOW), "a fresh task is ready");
		startCycle();
		assertTrue(agent.isTaskReady(row(), NOW),
			"a task shown its input but not parked was interrupted mid-cycle, and runs again");
	}

	@Test
	public void aParkedTaskWithNothingNewIsNotReady() {
		presentAndPark();
		assertFalse(agent.isTaskReady(row(), NOW));
		assertFalse(agent.hasReadyTask(NOW), "a parked task is not work");
	}

	@Test
	public void aParkedTaskIsReadyOnceItsWakeTimeComes() {
		presentAndPark();
		agent.setThreadWakeTime(Agent.ThreadKind.TASK, TASK, NOW);
		assertFalse(agent.isTaskReady(row(), NOW - 1), "not before its wake time");
		assertTrue(agent.isTaskReady(row(), NOW), "from its wake time on");
	}

	@Test
	public void aParkedTaskIsReadyWhenItsSessionHasAMessage() {
		presentAndPark();
		agent.appendSessionPending(SID, Maps.of(Fields.MESSAGE, "an answer"));
		assertTrue(agent.isTaskReady(row(), NOW), "a human's answer arrives in the task's session");
	}

	@Test
	public void aParkedTaskIsReadyWhenAContinuationArrives() {
		presentAndPark();
		assertTrue(agent.appendTaskContinuation(TASK, SID, Maps.of(Fields.MESSAGE, "more"), null));
		assertTrue(agent.isTaskReady(row(), NOW));
	}

	@Test
	public void startingACycleUnparksTheTask() {
		presentAndPark();
		assertNull(Task.nextInput(row()), "nothing new to show");
		startCycle();
		assertFalse(Task.isParked(row()),
			"a task in a cycle is not parked, so a restart mid-cycle runs it again");
	}

	@Test
	public void parkingWithAStaleRevisionDoesNothing() {
		startCycle();
		agent.tasks().task(TASK).park(7, 1);
		assertFalse(Task.isParked(row()));
	}

	@Test
	public void anOpenWaitIsMatchedToItsTask() {
		Blob ask = Blob.fromHex("a5c0000000000000000000000000beef");
		assertFalse(agent.hasPendingFor(TASK));
		agent.addPending(ask, Maps.of(Fields.TASK_ID, Strings.create(TASK.toHexString())));
		assertTrue(agent.hasPendingFor(TASK));
		assertFalse(agent.hasPendingFor(Blob.fromHex("0000000000000000000000000000beef")));
		agent.removePending(ask);
		assertFalse(agent.hasPendingFor(TASK));
	}
}
