package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.AgentWatch;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * An agent that, during its own cycle, triggers itself (an {@code agent:trigger}
 * of itself, or a scheduled event whose operation is a request to itself) is not
 * held by its own loop: the call returns at once and the work runs after the
 * cycle. Other callers keep their waits. See GRID_SCHEDULER.md (Trigger) and
 * AGENT_LOOP.md (agent:trigger, agent:request).
 */
public class AgentTriggersItselfTest {

	private static final long HOUR = 3_600_000L;
	/** Bound on a call that should return at once: far below a request's 5 s wait. */
	private static final long PROMPT_MS = 2_000L;

	private final Engine engine = TestEngine.ENGINE;
	private AString owner;
	private RequestContext ownerCtx;
	private AString agentId;
	private RequestContext agentCtx;
	private String gateName;

	private static AString s(String x) { return Strings.create(x); }

	@BeforeEach
	public void setup(TestInfo info) {
		owner = TestEngine.uniqueDID(info);
		engine.getVenueState().users().create(owner);
		ownerCtx = RequestContext.of(owner);
		agentId = s("self-trigger");
		gateName = "self-trigger-" + info.getTestMethod().orElseThrow().getName();
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/step", "acceptTasks", true,
					"caps", "unrestricted")),
			ownerCtx).awaitResult(5000);
		// The agent as it acts now, as its tool calls do
		agentCtx = engine.context(owner, Principals.agentDID(owner, agentId));
	}

	/** Starts a cycle that stays live at the gate, then completes its task once released. */
	private Job holdCycle(TestAdapter.TestGate gate) throws InterruptedException {
		Job held = engine.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, s("hold:" + gateName + " complete")),
			ownerCtx);
		assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the agent's cycle should reach the gate");
		return held;
	}

	/**
	 * Calls {@code op} on a thread of its own: {@code invokeInternal} runs the
	 * operation on the calling thread, so a call the held cycle blocks would
	 * otherwise block the test before it could release the gate.
	 */
	private CompletableFuture<ACell> call(String op, ACell input, RequestContext ctx) {
		return CompletableFuture.supplyAsync(() -> engine.jobs().invokeInternal(op, input, ctx).join(),
			r -> Thread.ofVirtual().start(r));
	}

	private ACell asAgent(String op, ACell input) throws Exception {
		return call(op, input, agentCtx).get(PROMPT_MS, TimeUnit.MILLISECONDS);
	}

	private ACell schedule(String op, ACell input) throws Exception {
		return schedule(op, input, false);
	}

	private ACell schedule(String op, ACell input, boolean track) throws Exception {
		ACell scheduled = asAgent("v/ops/scheduler/schedule", Maps.of(
			s("operation"), s(op), s("input"), input, s("after"), CVMLong.create(HOUR),
			s("track"), CVMBool.create(track)));
		ACell handle = RT.getIn(scheduled, s("handle"));
		assertNotNull(handle, "schedule must return a handle");
		return handle;
	}

	/**
	 * The case reported: the agent schedules a request to itself and triggers it
	 * from its cycle. The trigger gives the request's own answer, its task while
	 * the task waits, rather than waiting for a task its own cycle holds up.
	 */
	@Test
	public void triggeringARequestToItselfReturnsTheTaskAtOnce() throws Exception {
		triggerRequestToItself(false);
	}

	/** A tracked fire's task is a recorded Job, which the job tools follow. */
	@Test
	public void aTrackedRequestToItselfCanBeFollowed() throws Exception {
		triggerRequestToItself(true);
	}

	private void triggerRequestToItself(boolean track) throws Exception {
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job held = holdCycle(gate);
			ACell handle = schedule("v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, s("complete")), track);

			ACell triggered = asAgent("v/ops/scheduler/trigger", Maps.of(s("handle"), handle));
			assertEquals(CVMBool.TRUE, RT.getIn(triggered, s("triggered")));
			ACell id = RT.getIn(triggered, s("result"), Fields.ID);
			assertTrue(id instanceof Blob, "the trigger should name the queued task");
			Blob taskId = (Blob) id;
			Agent agent = engine.getVenueState().users().user(owner).agent(agentId);
			assertNotNull(agent.getTasks().get(taskId), "the task waits for the next cycle");
			if (track) {
				ACell status = asAgent("v/ops/job/status", Maps.of(Fields.ID, s(taskId.toHexString())));
				assertFalse(Status.COMPLETE.equals(RT.getIn(status, Fields.STATUS)));
			}
			// Its live handle, taken while the held cycle keeps it waiting: once
			// released, the next cycle may finish it, and a finished job leaves
			// the live set, before this thread looks again
			Job task = engine.jobs().getJob(taskId);
			assertNotNull(task, "the waiting task should be live");

			gate.release();
			held.awaitResult(10_000);
			task.awaitResult(10_000);
			assertEquals(Status.COMPLETE, task.getStatus(), "the request runs once the cycle has ended");
		}
	}

	/**
	 * The agent's own {@code agent:trigger} from its cycle returns at once, while
	 * its owner's trigger, by default, still waits for the held cycle and the
	 * cycle it asked for.
	 */
	@Test
	public void triggeringItselfReturnsAtOnceWhileOthersWait() throws Exception {
		Agent agent = engine.getVenueState().users().user(owner).agent(agentId);
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job held = holdCycle(gate);
			CompletableFuture<ACell> ownerTrigger = call(
				"v/ops/agent/trigger", Maps.of(Fields.AGENT_ID, agentId), ownerCtx);

			ACell self = asAgent("v/ops/agent/trigger", Maps.of(Fields.AGENT_ID, agentId));
			assertEquals(Agent.RUNNING, RT.getIn(self, Fields.STATUS));
			assertFalse(ownerTrigger.isDone(), "the owner's trigger waits for the held cycle");

			gate.release();
			held.awaitResult(10_000);
			ownerTrigger.get(10, TimeUnit.SECONDS);
			assertEquals(2, agent.getTimeline().count(), "the owner's trigger returned after its own cycle");
		}
	}

	/**
	 * Another caller's trigger during the cycle asks for a cycle after it: the
	 * running cycle took its input before the trigger, so the next one runs for
	 * it, as for a message that arrives during a cycle.
	 */
	@Test
	public void anotherCallersTriggerDuringTheCycleRunsTheNextCycle() throws Exception {
		Agent agent = engine.getVenueState().users().user(owner).agent(agentId);
		try (AgentWatch watch = AgentWatch.open(engine, owner, agentId);
				TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job held = holdCycle(gate);
			ACell triggered = call("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, agentId, Fields.WAIT, CVMBool.FALSE), ownerCtx)
				.get(PROMPT_MS, TimeUnit.MILLISECONDS);
			assertEquals(Agent.RUNNING, RT.getIn(triggered, Fields.STATUS));

			gate.release();
			held.awaitResult(10_000);
			watch.awaitRest(2);
		}
		assertEquals(2, agent.getTimeline().count(), "the trigger ran a cycle after the held one");
	}

	/** The agent's own trigger from its cycle asks for no further cycle. */
	@Test
	public void triggeringItselfAsksForNoFurtherCycle() throws Exception {
		Agent agent = engine.getVenueState().users().user(owner).agent(agentId);
		try (AgentWatch watch = AgentWatch.open(engine, owner, agentId);
				TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job held = holdCycle(gate);
			asAgent("v/ops/agent/trigger", Maps.of(Fields.AGENT_ID, agentId));

			gate.release();
			held.awaitResult(10_000);
			watch.awaitRest(1);
		}
		assertEquals(1, agent.getTimeline().count());
		assertFalse(agent.hasDueWake(Long.MAX_VALUE), "no wake waits for the agent");
	}

	/** A scheduled {@code agent:trigger} of itself, triggered from its cycle, returns at once too. */
	@Test
	public void triggeringAScheduledWakeOfItselfReturnsAtOnce() throws Exception {
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job held = holdCycle(gate);
			ACell handle = schedule("v/ops/agent/trigger", Maps.of(Fields.AGENT_ID, agentId));

			ACell triggered = asAgent("v/ops/scheduler/trigger", Maps.of(s("handle"), handle));
			assertEquals(CVMBool.TRUE, RT.getIn(triggered, s("triggered")));
			assertEquals(Agent.RUNNING, RT.getIn(triggered, s("result"), Fields.STATUS));

			gate.release();
			held.awaitResult(10_000);
		}
	}
}
