package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestLogs;

/**
 * An agent is suspended only when it cannot continue: its model setup is
 * wrong, its provider cannot be reached, or the venue fails unexpectedly. It
 * keeps its queue while suspended, and every other failure fails only the work
 * it was on (AGENT_LOOP.md §3.4). Each case runs the real run loop over
 * {@code v/test/ops/failllm}, which fails the way its model names.
 *
 * <p>Isolated because the venue-fault case silences an ERROR log, and that
 * level change is global.</p>
 */
@Isolated
public class AgentSuspensionTest {

	private final Engine engine = TestEngine.ENGINE;

	private AgentAdapter agents() {
		return (AgentAdapter) engine.getAdapter("agent");
	}

	private Agent create(RequestContext owner, String agentId, String model) {
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat",
				"llmOperation", "v/test/ops/failllm", "model", model)), owner).awaitResult(5000);
		return engine.getVenueState().users().user(owner.getUserDID()).agent(agentId);
	}

	private Job request(RequestContext owner, String agentId, String ask) {
		return engine.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, Maps.of("ask", ask)), owner);
	}

	/** Waits for the agent's run to reach a rest state, and returns it. */
	private AString awaitRest(Agent agent, RequestContext owner) throws Exception {
		return agents().awaitRunFinished(agent.getAgentId(), owner, 10_000);
	}

	private static String progress(Job job) {
		return Job.progressMessage(job.getData().get(Fields.PROGRESS));
	}

	/** The note a Job waiting on this suspended agent carries. */
	private static String suspendedNote(Agent agent) {
		return AgentAdapter.SUSPENDED_WAITING.formatted(agent.getAgentId(), ": " + agent.getError());
	}

	private static long timelineCount(Agent agent) {
		AVector<ACell> timeline = agent.getTimeline();
		return (timeline == null) ? 0 : timeline.count();
	}

	@Test
	public void aBrokenSetupSuspendsTheAgentWhichKeepsItsQueueUntilResumed(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "suspension-" + Long.toHexString(System.nanoTime());
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = create(owner, "broken-setup", "setup:" + gateName);

			// Hold the first cycle in its model call so a second task queues behind it.
			Job first = request(owner, "broken-setup", "first");
			assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the first cycle reaches its model call");
			Job second = request(owner, "broken-setup", "second");
			gate.release();

			assertEquals(Agent.SUSPENDED, awaitRest(agent, owner));
			assertTrue(agent.getError().toString().contains(TestAdapter.FAILLLM_REASON.formatted("setup")),
				"the suspension names its cause: " + agent.getError());
			assertEquals(1, timelineCount(agent), "the agent stops after the cycle that could not continue");
			assertEquals(2, agent.getTasks().count(), "a suspended agent keeps its queue");
			for (Job waiting : new Job[] {first, second}) {
				assertFalse(waiting.isFinished());
				assertEquals(suspendedNote(agent), progress(waiting), "a waiting job says why it waits");
			}

			// Fix the setup and resume: the notes clear, and both tasks run.
			engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "broken-setup",
				Fields.CONFIG, Maps.of("llmOperation", "v/test/ops/taskllm", "model", "complete")),
				owner).awaitResult(5000);
			engine.jobs().invokeOperation("v/ops/agent/resume",
				Maps.of(Fields.AGENT_ID, "broken-setup"), owner).awaitResult(5000);
			for (Job waiting : new Job[] {first, second}) {
				waiting.awaitResult(10_000);
				assertEquals(Status.COMPLETE, waiting.getStatus());
				assertNull(progress(waiting), "resuming clears the note");
			}
			assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
		}
	}

	@Test
	public void anUnreachableProviderOrAFailureValueSuspendsTheAgent(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		for (String model : new String[] {"unavailable", "value"}) {
			Agent agent = create(owner, "cannot-continue-" + model, model);
			Job waiting = request(owner, agent.getAgentId().toString(), "go");
			assertEquals(Agent.SUSPENDED, awaitRest(agent, owner), model);
			assertTrue(agent.getError().toString().contains(TestAdapter.FAILLLM_REASON.formatted(model)),
				model + ": " + agent.getError());
			assertFalse(waiting.isFinished(), model + ": a suspended agent keeps its queue");
			assertEquals(suspendedNote(agent), progress(waiting), model);
		}
	}

	@Test
	public void aVenueFaultSuspendsTheAgent(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "venue-fault", "bug");
		// The failed model call is logged at ERROR, as an unexpected failure
		// must be, once, by the Job that reports it.
		TestLogs.quiet(covia.exception.FaultException.class, () -> {
			Job waiting = request(owner, "venue-fault", "go");
			assertEquals(Agent.SUSPENDED, awaitRest(agent, owner));
			assertFalse(waiting.isFinished(), "a suspended agent keeps its queue");
			assertEquals(suspendedNote(agent), progress(waiting));
		});
	}

	@Test
	public void aFailureOfTheWorkFailsOnlyThatWork(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "bad-content", "content");
		String reason = TestAdapter.FAILLLM_REASON.formatted("content");

		Job first = request(owner, "bad-content", "first");
		assertThrows(Exception.class, () -> first.awaitResult(10_000));
		assertTrue(first.getErrorMessage().contains(reason), first.getErrorMessage());
		assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
		assertNull(agent.getError(), "a failure of the work is not a fault in the agent");

		Job second = request(owner, "bad-content", "second");
		assertThrows(Exception.class, () -> second.awaitResult(10_000));
		assertTrue(second.getErrorMessage().contains(reason), "the next task gets its own cycle");
		assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
		assertEquals(2, timelineCount(agent));
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void aChatTakenByACycleThatCannotContinueFails(TestInfo info) throws Exception {
		// Its message is already in the conversation and would never be
		// presented again, so its caller is told rather than left waiting.
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = create(owner, "chat-cannot-continue", "setup");
		Job chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cannot-continue", Fields.MESSAGE, Strings.create("hello")), owner);
		assertThrows(Exception.class, () -> chat.awaitResult(10_000));
		assertTrue(chat.getErrorMessage().contains(TestAdapter.FAILLLM_REASON.formatted("setup")),
			chat.getErrorMessage());
		assertEquals(Agent.SUSPENDED, agent.getStatus(), "the agent is suspended before its caller hears");
	}
}
