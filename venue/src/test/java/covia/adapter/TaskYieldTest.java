package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.venue.Agent;
import covia.venue.AgentEvents;
import covia.venue.Task;
import covia.venue.AgentWatch;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * A task that yields runs again only when something it waits on brings it
 * back: a person's answer, a call past its time limit, or a wake time. One
 * that yields with none of these fails at once (AGENT_LOOP.md §4.6). Every
 * case is driven by test transitions and lattice state, with no clock waits:
 * the tests wait on the run loop's own events. A wake time's own scenario runs
 * on both kinds of clock in {@code BothClocksTest}.
 */
public class TaskYieldTest {

	private final Engine engine = TestEngine.ENGINE;

	private Agent create(RequestContext owner, String agentId, AMap<AString, ACell> config,
			AMap<AString, ACell> state) {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config);
		if (state != null) input = input.assoc(Agent.KEY_STATE, state);
		engine.jobs().invokeOperation("v/ops/agent/create", input, owner).awaitResult(5000);
		return engine.getVenueState().users().user(owner.getUserDID()).agent(agentId);
	}

	private Job request(RequestContext owner, String agentId, String ask) {
		return engine.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, Maps.of("ask", ask)), owner);
	}

	private static long timelineCount(Agent agent) {
		AVector<ACell> timeline = agent.getTimeline();
		return (timeline == null) ? 0 : timeline.count();
	}

	@Test
	public void aYieldWithNothingOutstandingFailsTheTaskAtOnce(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "idle-yield", Maps.of(Fields.OPERATION, "v/test/ops/echo"), null);

		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "idle-yield")) {
			Job job = request(owner, "idle-yield", "do something");
			assertThrows(Exception.class, () -> job.awaitResult(10_000));
			assertEquals(Status.FAILED, job.getStatus());
			assertEquals(AgentAdapter.TASK_YIELDED_IDLE, job.getErrorMessage());
			watch.awaitRest(1);
		}
		assertEquals(1, timelineCount(agent), "the task ran once, not until the loop cap");
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void aTaskThatAsksAPersonWaitsForTheAnswer(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "ask-yield", Maps.of(Fields.OPERATION, "v/test/ops/askhuman"), null);

		Job job;
		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "ask-yield")) {
			job = request(owner, "ask-yield", "decide for me");
			watch.awaitRest(1);
		}
		assertTrue(Task.isParked(agent.getTasks().get(job.getID())),
			"an open ask from the task's cycle parks the task");
		assertTrue(agent.hasPendingFor(job.getID()));
		assertFalse(job.isFinished());

		respond(owner, agent, "carry on");
		ACell result = job.awaitResult(10_000);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertTrue(result.toString().contains("carry on"), "the answer completes the task: " + result);
		assertEquals(2, timelineCount(agent), "the task ran once to ask and once on the answer");
	}

	@Test
	public void aTaskThatAsksThroughTheLlmHarnessWaitsForTheAnswer(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "harness-ask", Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/toolllm",
			Fields.TOOLS, Vectors.of(Strings.create("v/ops/hitl/request"))), null);

		// toolllm asks through hitl_request, then replies with text: a yield
		// backed by the ask, which the task id carried through the harness
		// ties to this task.
		Job job;
		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "harness-ask")) {
			job = request(owner, "harness-ask", "ask-human");
			watch.awaitRest(1);
		}
		assertTrue(Task.isParked(agent.getTasks().get(job.getID())),
			"an ask made through the LLM harness parks its task");
		assertFalse(job.isFinished());

		// The answer runs the task again. toolllm replies with text once more,
		// which leaves nothing outstanding, so this time the task fails as idle.
		respond(owner, agent, "carry on");
		assertThrows(Exception.class, () -> job.awaitResult(10_000));
		assertEquals(AgentAdapter.TASK_YIELDED_IDLE, job.getErrorMessage());
		assertEquals(2, timelineCount(agent));
	}

	@Test
	public void aTaskWokenEveryCycleStillStopsAtTheLoopCap(TestInfo info) throws Exception {
		// A wake time already passed is honoured every cycle, so this task is
		// always ready; the iteration cap remains the backstop (#215).
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "past-wake",
			Maps.of(Fields.OPERATION, "v/test/ops/wakeresponse"),
			Maps.of(Fields.WAKE_TIME, CVMLong.create(1)));

		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "past-wake")) {
			Job job = request(owner, "past-wake", "spin");
			assertThrows(Exception.class, () -> job.awaitResult(10_000));
			assertTrue(job.getErrorMessage().contains("loop iterations"),
				"the cap fails the task: " + job.getErrorMessage());
			watch.awaitEvent("run:end", e -> AgentEvents.RUN_END.equals(e.type()));
		}
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void failTaskFailsOnlyItsTaskAndLeavesTheAgentRunning(TestInfo info) throws Exception {
		// fail_task is the agent deciding a task cannot be done: a semantic
		// failure of that task (AGENT_SESSIONS.md §6.3), not a technical error
		// in the agent, which is what suspends it (#88). Through the real run
		// loop, the task's Job fails with the agent's reason and the agent
		// stays up for the next task.
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "fails-a-task", Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/taskllm",
			"model", "parallel-task-fail-test"), null);
		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "fails-a-task")) {
			Job first = request(owner, "fails-a-task", "cannot be done");
			assertThrows(Exception.class, () -> first.awaitResult(10_000));
			assertEquals("failed deliberately", first.getErrorMessage());
			watch.awaitRest(1);
			assertEquals(Agent.SLEEPING, agent.getStatus(),
				"failing a task is not a fault in the agent: " + agent.getError());

			Job second = request(owner, "fails-a-task", "nor can this");
			assertThrows(Exception.class, () -> second.awaitResult(10_000));
			assertEquals("failed deliberately", second.getErrorMessage(),
				"the next task gets its own cycle and its own outcome");
			watch.awaitRest(2);
		}
		assertEquals(2, timelineCount(agent));
		assertEquals(Agent.SLEEPING, agent.getStatus());
	}

	/** Answers the agent's one open ask, as its owner. */
	private void respond(RequestContext owner, Agent agent, String answer) {
		AString askId = null;
		for (var entry : agent.getPending().entrySet()) askId = Strings.create(entry.getKey().toHexString());
		assertNotNull(askId, "the agent should have an open ask");
		engine.jobs().invokeOperation("v/ops/hitl/respond",
			Hitl.answer(askId.toString()).answer("next", answer).build(), owner).awaitResult(5000);
	}
}
