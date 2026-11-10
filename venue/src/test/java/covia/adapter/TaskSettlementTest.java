package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.AgentWatch;
import covia.venue.Config;
import covia.venue.DrivenClock;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.Task;
import covia.venue.TestEngine;

/**
 * How a task the run loop picked is settled (AGENT_TASKS.md §5–§6), told
 * through the scripted {@code step} agent: its reply is the result unless it
 * resolved or parked the task, and a parked task stays open until its delay
 * ends, when it is brought back unless it was settled meanwhile. The venue runs
 * on a driven clock, so a park's delay ends only when the test moves it.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class TaskSettlementTest {

	private static final long MINUTE = 60_000L;
	private static final DrivenClock CLOCK = DrivenClock.at(Instant.parse("2023-01-01T00:00:00Z"));

	private static Engine engine;

	@BeforeAll
	static void launch() {
		engine = Engine.createTemp(CLOCK, Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void close() {
		engine.close();
	}

	private static long now() {
		return engine.currentTimestamp().longValue();
	}

	private static RequestContext owner(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		engine.getVenueState().users().ensure(did);
		return RequestContext.of(did);
	}

	private static Agent stepAgent(RequestContext owner, String agentId) {
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId,
			Fields.CONFIG, TestEngine.unrestricted(Maps.of(Fields.OPERATION, "v/test/ops/step"))), owner).awaitResult(5000);
		return engine.getVenueState().users().user(owner.getUserDID()).agent(agentId);
	}

	private static Job request(RequestContext owner, String agentId, String script) {
		return request(owner, agentId, script, Maps.empty());
	}

	private static Job request(RequestContext owner, String agentId, String script, AMap<AString, ACell> extra) {
		return engine.jobs().invokeOperation("v/ops/agent/request",
			extra.assoc(Fields.AGENT_ID, Strings.create(agentId)).assoc(Fields.INPUT, Strings.create(script)), owner);
	}

	@Test
	public void aReplyIsTheResultOfATaskTheAgentLeftUnsettled(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		Agent agent = stepAgent(owner, "replies");
		Job job = request(owner, "replies", "answer this");
		ACell result = job.awaitResult(10_000);
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals(Strings.create("answer this"), RT.getIn(result, Fields.OUTPUT, Fields.INPUT),
			"the cycle's reply is the result: " + result);
		AgentWatch.awaitIdle(engine, owner.getUserDID(), agent);
		assertEquals(0, agent.getTasks().count());
		assertEquals(1, agent.getTimeline().count());
	}

	@Test
	public void aReplyAStrictSchemaRejectsFailsTheTask(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		stepAgent(owner, "nonconforming");
		Job job = request(owner, "nonconforming", "answer this", Maps.of(
			Fields.RESPONSE_SCHEMA, Maps.of("type", "integer"), Fields.STRICT, CVMBool.TRUE));
		assertThrows(Exception.class, () -> job.awaitResult(10_000));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().startsWith(AgentAdapter.RESULT_NONCONFORMING),
			"a reply is judged as complete_task judges a result: " + job.getErrorMessage());
	}

	@Test
	public void aParkedTaskStaysOpenAndComesBackWhenItsDelayEnds(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		Agent agent = stepAgent(owner, "parks");
		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "parks")) {
			long parkedAt = now();
			Job job = request(owner, "parks", "park:" + MINUTE);
			watch.awaitRest(1);
			ACell row = agent.getTasks().get(job.getID());
			assertTrue(Task.isParked(row), "the park keeps the task open");
			assertEquals(CVMLong.create(parkedAt + MINUTE), RT.getIn(row, Fields.WAKE_TIME));
			assertFalse(job.isFinished(), "the reply after a park goes to the conversation, not the task");
			assertEquals(Status.STARTED, job.getStatus());

			CLOCK.advance(MINUTE);
			engine.application().update().get(10, TimeUnit.SECONDS);
			watch.awaitRest(2);
			assertNotNull(RT.getIn(agent.getTimeline().get(1), Fields.TASKS),
				"the end of its delay presents the task again");
		}
	}

	@Test
	public void aTaskSettledWhileParkedIsNotBroughtBack(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		Agent agent = stepAgent(owner, "parks-then-cancelled");
		try (AgentWatch watch = AgentWatch.open(engine, owner.getUserDID(), "parks-then-cancelled")) {
			Job job = request(owner, "parks-then-cancelled", "park:" + MINUTE);
			watch.awaitRest(1);
			assertTrue(Task.isParked(agent.getTasks().get(job.getID())));

			engine.jobs().invokeOperation("v/ops/agent/cancel-task", Maps.of(
				Fields.AGENT_ID, "parks-then-cancelled",
				Fields.TASK_ID, job.getID().toHexString()), owner).awaitResult(5000);
			assertEquals(Status.CANCELLED, job.getStatus());
			assertNull(agent.getTasks().get(job.getID()), "the task and its wake are gone");

			CLOCK.advance(MINUTE);
			engine.application().update().get(10, TimeUnit.SECONDS);
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, "parks-then-cancelled", Fields.FORCE, false), owner).awaitResult(5000);
			assertEquals(1, agent.getTimeline().count(), "the revisit found nothing to do");
		}
	}

	@Test
	public void aTaskParkedAndThenResolvedInOneCycleIsResolved(TestInfo info) throws Exception {
		RequestContext owner = owner(info);
		Agent agent = stepAgent(owner, "parks-then-fails");
		Job job = request(owner, "parks-then-fails", "park:" + MINUTE + " failtask");
		assertThrows(Exception.class, () -> job.awaitResult(10_000));
		assertEquals(TestAdapter.STEP_FAILED, job.getErrorMessage());
		AgentWatch.awaitIdle(engine, owner.getUserDID(), agent);
		assertNull(agent.getTasks().get(job.getID()), "a resolution after the park settles the task");
	}

	@Test
	public void aParkNeedsATaskInScope(TestInfo info) {
		RequestContext owner = owner(info);
		stepAgent(owner, "no-task");
		Job park = engine.jobs().invokeOperation("v/ops/agent/park-task",
			Maps.of(Fields.DELAY, CVMLong.create(MINUTE)), owner);
		assertThrows(Exception.class, () -> park.awaitResult(5000));
		assertEquals(Status.FAILED, park.getStatus());
	}
}
