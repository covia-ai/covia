package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.exception.InvalidInputException;
import covia.exception.UnsupportedException;
import covia.grid.HexId;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.AgentWatch;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * An agent takes tasks only when its config sets {@code acceptTasks: true}
 * (AGENT_TASKS.md §2). Without it {@code agent:request} is refused, and the
 * agent's cycles, {@code agent:context} and {@code agent:step} offer no task
 * tools and no no-task notice, on both runtimes, unless a cycle carries a task
 * the agent had queued before; chat and trigger work as they did.
 */
public class AcceptTasksTest {

	private static final String[] RUNTIMES = {"v/ops/llmagent/chat", "v/ops/goaltree/chat"};
	private static final AString ACCEPT_TASKS = Strings.intern("acceptTasks");

	private final Engine engine = TestEngine.ENGINE;
	private AString owner;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		owner = TestEngine.uniqueDID(info);
		engine.getVenueState().users().ensure(owner);
		ctx = RequestContext.of(owner);
	}

	@Test
	public void aRequestToAnAgentThatTakesNoTasksIsRefusedAndLeavesNothingBehind() throws Exception {
		Agent agent = create("no-tasks", config(RUNTIMES[0], "v/test/ops/taskllm", false));
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, "no-tasks", Fields.INPUT, "do this");

		ExecutionException refused = assertThrows(ExecutionException.class, () ->
			engine.jobs().invokeInternal("v/ops/agent/request", input, ctx).get(5, TimeUnit.SECONDS));
		assertInstanceOf(UnsupportedException.class, refused.getCause());
		assertEquals(AgentAdapter.TASKS_NOT_ACCEPTED.formatted("no-tasks"), refused.getCause().getMessage());

		Job job = engine.jobs().invokeOperation("v/ops/agent/request", input, ctx);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertEquals(Status.FAILED, job.getStatus());
		assertNull(job.getData().get(Fields.SESSION_ID), "the refused request names no session");
		assertEquals(0, agent.getTasks().count(), "no task is queued");
		assertEquals(0, agent.getSessions().count(), "no session is minted");
		assertEquals(0, agent.getTimeline().count(), "no cycle runs");
	}

	@Test
	public void acceptTasksMustBeABooleanAndUpdatingItOnLetsRequestsIn() throws Exception {
		ExecutionException atCreate = assertThrows(ExecutionException.class, () ->
			engine.jobs().invokeInternal("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "typed",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete", ACCEPT_TASKS, "yes")), ctx)
				.get(5, TimeUnit.SECONDS));
		assertInstanceOf(InvalidInputException.class, atCreate.getCause());

		Agent agent = create("typed", Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete"));
		ExecutionException atUpdate = assertThrows(ExecutionException.class, () ->
			engine.jobs().invokeInternal("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "typed",
				Fields.CONFIG, Maps.of(ACCEPT_TASKS, 1L)), ctx).get(5, TimeUnit.SECONDS));
		assertInstanceOf(InvalidInputException.class, atUpdate.getCause());
		assertNull(agent.getConfig().get(ACCEPT_TASKS), "the refused update changed nothing");

		engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(Fields.AGENT_ID, "typed",
			Fields.CONFIG, Maps.of(ACCEPT_TASKS, CVMBool.TRUE)), ctx).awaitResult(5000);
		Job request = engine.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "typed", Fields.INPUT, "work"), ctx);
		assertNotNull(request.awaitResult(10_000), "the agent takes the request once its config sets acceptTasks");
		assertEquals(Status.COMPLETE, request.getStatus());
	}

	@Test
	public void theContextOffersTaskToolsAndTheNoTaskNoticeOnlyToAnAgentThatTakesTasks() {
		for (String runtime : RUNTIMES) {
			String without = name(runtime) + "-without";
			String with = name(runtime) + "-with";
			create(without, config(runtime, "v/test/ops/llm", false));
			create(with, config(runtime, "v/test/ops/llm", true));

			AMap<AString, ACell> plain = context(without);
			assertTrue(Collections.disjoint(TaskTools.NAMES, names(plain.get(Fields.TOOLS))),
				runtime + " offered task tools without the key: " + plain.get(Fields.TOOLS));
			assertTrue(names(plain.get(Fields.TOOLS)).contains("test_echo"), runtime + ": " + plain.get(Fields.TOOLS));
			assertTrue(Collections.disjoint(TaskTools.NAMES, names(RT.getIn(plain, "palette", Fields.TOOLS))),
				runtime + " listed task tools in the palette without the key");
			assertFalse(plain.get(Fields.MESSAGES).toString().contains(ContextAssembler.NO_TASK_NOTICE),
				runtime + " carried the no-task notice without the key");

			AMap<AString, ACell> accepting = context(with);
			assertTrue(names(accepting.get(Fields.TOOLS)).containsAll(TaskTools.NAMES),
				runtime + ": " + accepting.get(Fields.TOOLS));
			assertTrue(names(RT.getIn(accepting, "palette", Fields.TOOLS)).containsAll(TaskTools.NAMES), runtime);
			assertTrue(accepting.get(Fields.MESSAGES).toString().contains(ContextAssembler.NO_TASK_NOTICE),
				runtime + " lost the no-task notice with the key");
		}
	}

	@Test
	public void aStepAnswersATaskToolAsNotOfferedUnlessTheCallCarriesATask() {
		for (String runtime : RUNTIMES) {
			String id = name(runtime) + "-step";
			create(id, config(runtime, "v/test/ops/llm", false));
			AMap<AString, ACell> complete = Maps.of("toolCalls", Vectors.of((ACell) Maps.of(
				"id", "call_ct", "name", TaskTools.COMPLETE, "arguments", Maps.of("result", "done"))));

			AMap<AString, ACell> chat = step(id, complete, null);
			assertEquals(Strings.create("Error: " + String.format(AbstractLLMAdapter.HARNESS_NOT_OFFERED, TaskTools.COMPLETE)),
				RT.getIn(call(chat, "call_ct"), Fields.RESULT), runtime);
			assertNull(RT.getIn(chat, AbstractLLMAdapter.K_TERMINAL), runtime);

			AMap<AString, ACell> task = step(id, complete, Strings.create("a task queued before"));
			assertEquals("complete", String.valueOf(RT.getIn(task, AbstractLLMAdapter.K_TERMINAL, Fields.STATUS)),
				runtime + " did not resolve the carried task: " + task);
		}
	}

	@Test
	public void aTaskQueuedBeforeTheKeyExistedRunsWithTheTaskToolsForItsCycleOnly() {
		for (String runtime : RUNTIMES) {
			String id = name(runtime) + "-legacy";
			Agent agent = create(id, config(runtime, "v/test/ops/taskllm", false));
			Blob sid = chat(agent, null, "hello");
			ACell rendering = rendering(agent, sid);
			assertNotNull(rendering, runtime + " persisted no rendering");
			assertTrue(Collections.disjoint(TaskTools.NAMES, names(RT.getIn(rendering, Fields.TOOLS))), runtime);

			// A task queued before the agent stopped taking them: written into the record
			Blob taskId = Blob.fromHex(runtime.contains("goaltree") ? "a77a5c0de002" : "a77a5c0de001");
			assertEquals(Agent.SessionIntake.APPLIED, agent.addTaskInSession(sid, owner, null, taskId,
				Maps.of(Fields.INPUT, "finish the queued work", Fields.SESSION_ID, sid)));
			try (AgentWatch watch = AgentWatch.open(engine, owner, id)) {
				engine.jobs().invokeOperation("v/ops/agent/trigger",
					Maps.of(Fields.AGENT_ID, id, Fields.FORCE, CVMBool.FALSE), ctx).awaitResult(5000);
				watch.awaitRest(1);
			}
			assertEquals(0, agent.getTasks().count(), runtime + " left the task queued");
			AMap<AString, ACell> taskCycle = lastCycle(agent);
			assertTrue(names(taskCycle.get(Fields.TOOLS)).containsAll(TaskTools.NAMES),
				runtime + " did not offer the task tools to the task's cycle: " + taskCycle.get(Fields.TOOLS));
			ACell resolved = RT.getIn(taskCycle, Fields.INFERENCES, 0, Fields.CALLS, 0);
			assertEquals(TaskTools.COMPLETE, String.valueOf(RT.getIn(resolved, Fields.NAME)), runtime + ": " + resolved);
			assertNull(RT.getIn(resolved, "isError"), runtime + " refused complete_task: " + resolved);
			assertEquals(Status.COMPLETE, RT.getIn(taskCycle, Fields.TASK_RESULTS, taskId, Fields.STATUS),
				runtime + ": " + taskCycle.get(Fields.TASK_RESULTS));
			assertEquals(rendering, rendering(agent, sid), runtime + " changed the session's rendering for the task");

			chat(agent, sid, "and now?");
			AMap<AString, ACell> after = lastCycle(agent);
			assertTrue(Collections.disjoint(TaskTools.NAMES, names(after.get(Fields.TOOLS))),
				runtime + " kept offering the task tools: " + after.get(Fields.TOOLS));
			assertFalse(after.toString().contains(ContextAssembler.NO_TASK_NOTICE), runtime);
		}
	}

	@Test
	public void aRenderingWrittenBeforeTheChangeSendsNoTaskToolsOnItsNextCycle() {
		for (String runtime : RUNTIMES) {
			String id = name(runtime) + "-upgrade";
			Agent agent = create(id, config(runtime, "v/test/ops/llm", false));
			Blob sid = chat(agent, null, "hello");

			// The rendering as the venue wrote it before: renderer version 2, the task tools first
			AMap<AString, ACell> current = RT.ensureMap(rendering(agent, sid));
			AString start = Strings.intern("baseToolStart");
			AString end = Strings.intern("baseToolEnd");
			long shift = TaskTools.DEFINITIONS.count();
			@SuppressWarnings("unchecked")
			AVector<ACell> oldTools = (AVector<ACell>) TaskTools.DEFINITIONS.concat(RT.ensureVector(current.get(Fields.TOOLS)));
			writeRendering(agent, sid, current
				.assoc(Strings.intern("renderVersion"), CVMLong.create(2))
				.assoc(Fields.TOOLS, oldTools)
				.assoc(start, CVMLong.create(((CVMLong) current.get(start)).longValue() + shift))
				.assoc(end, CVMLong.create(((CVMLong) current.get(end)).longValue() + shift)));

			chat(agent, sid, "again");
			AMap<AString, ACell> after = lastCycle(agent);
			assertTrue(Collections.disjoint(TaskTools.NAMES, names(after.get(Fields.TOOLS))),
				runtime + " sent the old rendering's task tools: " + after.get(Fields.TOOLS));
			assertTrue(Collections.disjoint(TaskTools.NAMES, names(RT.getIn(rendering(agent, sid), Fields.TOOLS))),
				runtime + " kept the old rendering");
		}
	}

	@Test
	public void chatAndTriggerWorkWithoutTheKey() {
		for (String runtime : RUNTIMES) {
			String id = name(runtime) + "-talk";
			Agent agent = create(id, config(runtime, "v/test/ops/taskllm", false));
			try (AgentWatch watch = AgentWatch.open(engine, owner, id)) {
				ACell reply = engine.jobs().invokeOperation("v/ops/agent/chat",
					Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "hello"), ctx).awaitResult(10_000);
				assertNotNull(RT.getIn(reply, Fields.RESPONSE), runtime + " did not answer the chat: " + reply);
				watch.awaitRest(1);
			}

			try (AgentWatch watch = AgentWatch.open(engine, owner, id)) {
				engine.jobs().invokeOperation("v/ops/agent/trigger",
					Maps.of(Fields.AGENT_ID, id), ctx).awaitResult(5000);
				watch.awaitRest(1);
			}
			assertEquals(2, agent.getTimeline().count(), runtime + " ran no cycle for the trigger");
			assertEquals(Agent.SLEEPING, agent.getStatus(), runtime);
			assertNull(Agent.error(agent.getRecord()), runtime);
		}
	}

	// ========== Helpers ==========

	private static String name(String runtime) {
		return runtime.contains("goaltree") ? "goaltree" : "llmagent";
	}

	private static AMap<AString, ACell> config(String runtime, String llm, boolean acceptTasks) {
		AMap<AString, ACell> config = Maps.of(Fields.OPERATION, runtime, "llmOperation", llm,
			Fields.TOOLS, Vectors.of(Strings.create("v/test/ops/echo")));
		return acceptTasks ? config.assoc(ACCEPT_TASKS, CVMBool.TRUE) : config;
	}

	private Agent create(String id, AMap<AString, ACell> config) {
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, TestEngine.unrestricted(config)), ctx).awaitResult(5000);
		return engine.getVenueState().users().user(owner).agent(id);
	}

	/** Chats on {@code sid}, or a new session when null, and waits for the agent to rest. */
	private Blob chat(Agent agent, Blob sid, String message) {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agent.getAgentId(), Fields.MESSAGE, message);
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid.toHexString()));
		ACell reply;
		try (AgentWatch watch = AgentWatch.open(engine, owner, agent.getAgentId())) {
			reply = engine.jobs().invokeOperation("v/ops/agent/chat", input, ctx).awaitResult(10_000);
			watch.awaitRest(1);
		}
		ACell named = RT.getIn(reply, Fields.SESSION_ID);
		Blob session = HexId.parse(named);
		assertNotNull(session, "the chat named no session: " + reply);
		return session;
	}

	private AMap<AString, ACell> context(String id) {
		return RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "hello"), ctx).awaitResult(5000));
	}

	private AMap<AString, ACell> step(String id, AMap<AString, ACell> assistant, ACell task) {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "hello", "assistant", assistant);
		if (task != null) input = input.assoc(Strings.intern("task"), task);
		return RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step", input, ctx).awaitResult(10_000));
	}

	private static ACell call(AMap<AString, ACell> step, String id) {
		AVector<ACell> calls = RT.ensureVector(step.get(Fields.CALLS));
		assertNotNull(calls, "the step made no calls: " + step);
		for (long i = 0; i < calls.count(); i++) {
			if (id.equals(String.valueOf(RT.getIn(calls.get(i), AbstractLLMAdapter.K_ID)))) return calls.get(i);
		}
		return fail("the step made no call " + id + ": " + step);
	}

	private static AMap<AString, ACell> lastCycle(Agent agent) {
		AVector<ACell> timeline = agent.getTimeline();
		return RT.ensureMap(timeline.get(timeline.count() - 1));
	}

	private static ACell rendering(Agent agent, Blob sid) {
		return RT.getIn(agent.session(sid).getRecord(), Fields.FRAMES, 0, GoalTreeContext.K_RENDERED_CONTEXT);
	}

	private static void writeRendering(Agent agent, Blob sid, AMap<AString, ACell> rendering) {
		AMap<AString, ACell> session = agent.session(sid).getRecord();
		AVector<ACell> frames = RT.ensureVector(session.get(Fields.FRAMES));
		AMap<AString, ACell> root = RT.ensureMap(frames.get(0));
		root = root.assoc(GoalTreeContext.K_RENDERED_CONTEXT, rendering);
		agent.cursor().set(agent.getRecord().assoc(Agent.KEY_SESSIONS,
			agent.getSessions().assoc(sid, session.assoc(Fields.FRAMES, frames.assoc(0, root)))));
	}

	/** The names of a vector of tool definitions, palette entries or names. */
	private static Set<String> names(ACell tools) {
		Set<String> names = new HashSet<>();
		AVector<ACell> v = RT.ensureVector(tools);
		for (long i = 0; v != null && i < v.count(); i++) {
			ACell t = v.get(i);
			ACell n = (t instanceof AMap) ? RT.getIn(t, Fields.NAME) : t;
			if (n != null) names.add(n.toString());
		}
		return names;
	}
}
