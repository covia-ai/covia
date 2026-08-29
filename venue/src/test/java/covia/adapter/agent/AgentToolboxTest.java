package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Deterministic contract for model replies consumed by the agent toolbox.
 *
 * <p>Every case enters through {@code agent:step}: the same reply normalisation,
 * tool registry, dispatch, result rendering and next-prompt assembly as a live
 * model cycle, without calling a model. The shared cases run unchanged against
 * both agent runtimes. Registry equality is intentional: adding a harness tool
 * without adding a behavioural case here must fail this suite.</p>
 */
public class AgentToolboxTest {

	private record AgentRuntime(String name, String operation) {}
	private record RunningAgent(String id, String sessionId) {}

	private static final AgentRuntime LLM_AGENT =
		new AgentRuntime("llmagent", "v/ops/llmagent/chat");
	private static final AgentRuntime GOAL_TREE =
		new AgentRuntime("goaltree", "v/ops/goaltree/chat");
	private static final AgentRuntime[] RUNTIMES = {LLM_AGENT, GOAL_TREE};

	private static final String CONTEXT_PATH = "w/toolbox/context";
	private static final String CONTEXT_MARKER = "TOOLBOX_CONTEXT_VALUE";
	private static final String PINNED_PATH = "w/toolbox/pinned";
	private static final String PINNED_MARKER = "TOOLBOX_PINNED_VALUE";
	private static final String PINNED_SKILL_PATH = "w/toolbox/pinned-skill";
	private static final String PINNED_SKILL_BODY = "TOOLBOX_PINNED_SKILL_BODY";
	private static final String SKILLSET = "w/toolbox/skills";
	private static final String SKILL_BODY = "TOOLBOX_SKILL_BODY";

	private final Engine engine = TestEngine.ENGINE;
	private AString userDid;
	private RequestContext user;

	@BeforeEach
	public void setup(TestInfo info) {
		userDid = TestEngine.uniqueDID(info);
		user = RequestContext.of(userDid);
		write(CONTEXT_PATH, Maps.of(
			"total", 3L,
			"bySource", Maps.of("primary", 2L, "secondary", 1L),
			"marker", CONTEXT_MARKER));
		write(PINNED_PATH, Strings.create(PINNED_MARKER));
		write(PINNED_SKILL_PATH, Maps.of(
			"description", "Pinned toolbox skill",
			"content", Maps.of("inline", PINNED_SKILL_BODY),
			"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/list")))));
		write(SKILLSET + "/alpha", Maps.of(
			"description", "Toolbox alpha skill",
			"content", Maps.of("inline", SKILL_BODY),
			"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/read")))));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void pinnedSkillsContributeTheirToolsWithoutRuntimeSkillLoad() {
		for (AgentRuntime runtime : RUNTIMES) {
			AMap<AString, ACell> loads = ((AMap<AString, ACell>) (AMap<?, ?>)
				RT.ensureMap(sharedConfig(runtime).get(Fields.LOADS)))
				.assoc(Strings.create(PINNED_SKILL_PATH), Maps.of(
					"skill", true,
					"budget", 1000L,
					"label", "Pinned toolbox skill"));
			RunningAgent agent = start(runtime, runtime.name() + "-pinned-skill",
				sharedConfig(runtime).assoc(Fields.LOADS, loads));
			AMap<AString, ACell> result = step(agent, "inspect the pinned skill",
				assistantCall("call_echo", "test_echo", Maps.of("ok", true)), null);

			assertTrue(hasSystemContent(result, PINNED_SKILL_BODY), runtime.name());
			assertTrue(hasTool(result, "covia_list"),
				runtime.name() + " did not activate the pinned skill's declared tool: " + result);
		}
	}

	@Test
	public void validModelRepliesDriveEverySharedToolboxMechanism() {
		Set<String> exercisedHarness = new HashSet<>();
		Set<String> exercisedTaskTools = new HashSet<>();

		for (AgentRuntime runtime : RUNTIMES) {
			RunningAgent agent = start(runtime, runtime.name() + "-shared", sharedConfig(runtime));
			String where = runtime.name();

			// A valid text-only model response is itself a complete iteration.
			AMap<AString, ACell> text = step(agent, "answer this", Strings.create("valid answer"), null);
			assertEquals(CVMBool.TRUE, text.get(AbstractLLMAdapter.K_DONE), where);
			assertEquals("valid answer", RT.getIn(text, Fields.RESPONSE).toString(), where);

			// Configured operation: its nested result must reach the next inference intact.
			AMap<AString, ACell> nested = Maps.of(
				"total", 3L,
				"bySource", Maps.of("primary", 2L, "secondary", 1L));
			AMap<AString, ACell> ordinary = step(agent, "use a normal tool",
				assistantCall("call_echo", "test_echo", nested), null);
			assertNextToolResult(ordinary, "call_echo", "test_echo", nested, where);

			// context_load: the loaded value, not just its receipt, is in the next prompt.
			AMap<AString, ACell> loaded = step(agent, "load context",
				assistantCall("call_context_load", HarnessTools.CONTEXT_LOAD,
					Maps.of("path", CONTEXT_PATH, "budget", 1000L, "label", "Toolbox context")), null);
			exercisedHarness.add(HarnessTools.CONTEXT_LOAD);
			assertFalse(CVMBool.TRUE.equals(loaded.get(AbstractLLMAdapter.K_DONE)), where);
			assertEquals(CVMBool.TRUE,
				RT.getIn(call(loaded, "call_context_load"), Fields.RESULT, "loaded"), where);
			assertNotNull(call(loaded, "context:call_context_load"),
				where + " context_load did not append its loaded_context event");
			assertTrue(messages(loaded).toString().contains(CONTEXT_MARKER),
				where + " context_load result was not assembled into the next prompt: " + loaded);

			// context_unload: operator-pinned context is visible but protected.
			AMap<AString, ACell> unloaded = step(agent, "unload context",
				assistantCall("call_context_unload", HarnessTools.CONTEXT_UNLOAD,
					Maps.of("path", PINNED_PATH)), null);
			exercisedHarness.add(HarnessTools.CONTEXT_UNLOAD);
			assertTrue(RT.getIn(firstCall(unloaded), Fields.RESULT).toString()
				.contains("pinned_context and cannot be unloaded"), where);
			assertTrue(messages(unloaded).toString().contains(PINNED_MARKER),
				where + " context_unload removed pinned context: " + unloaded);

			// skill_load: instructions and tool state both append; the fixed
			// dispatcher was part of the initial palette.
			AMap<AString, ACell> skill = step(agent, "load a skill",
				assistantCall("call_skill_load", HarnessTools.SKILL_LOAD,
					Maps.of("name", "alpha")), null);
			exercisedHarness.add(HarnessTools.SKILL_LOAD);
			assertTrue(hasSystemContent(skill, SKILL_BODY),
				where + " skill instructions missing from next prompt: " + skill);
			assertTrue(hasTool(skill, HarnessTools.INVOKE_TOOL), where);
			assertTrue(hasToolAddition(skill, "covia_read"),
				where + " skill-contributed operation missing from appended state: " + skill);
			assertFalse(hasTool(skill, "covia_read"),
				where + " later definition rewrote the fixed palette: " + skill);

			// more_tools uses the same append-only state path. The following
			// dispatcher call proves the new route is live without another palette.
			AMap<AString, ACell> more = step(agent, "add a tool",
				Maps.of("toolCalls", Vectors.of(
					(ACell) Maps.of("id", "call_more_tools", "name", HarnessTools.MORE_TOOLS,
						"arguments", Maps.of("operations",
							Vectors.of(Strings.create("v/test/ops/capturectx")))),
					(ACell) Maps.of("id", "call_invoke_tool", "name", HarnessTools.INVOKE_TOOL,
						"arguments", Maps.of("name", "test_capturectx", "input", Maps.empty())))), null);
			exercisedHarness.add(HarnessTools.MORE_TOOLS);
			exercisedHarness.add(HarnessTools.INVOKE_TOOL);
			assertTrue(hasToolAddition(more, "test_capturectx"),
				where + " more_tools addition missing from appended state: " + more);
			assertFalse(hasTool(more, "test_capturectx"), where);
			ACell invokedResult = RT.getIn(call(more, "call_invoke_tool"), Fields.RESULT);
			assertNotNull(invokedResult, where);
			assertFalse(invokedResult.toString().startsWith("Error:"),
				where + " fixed dispatcher did not invoke the added operation: " + more);

			// Framework task tools are conditional toolbox entries, shared by both runtimes.
			AMap<AString, ACell> completed = step(agent, null,
				assistantCall("call_complete_task", TaskTools.COMPLETE,
					Maps.of("result", Maps.of("answer", 42L))), Strings.create("produce an answer"));
			exercisedTaskTools.add(TaskTools.COMPLETE);
			assertTerminal(completed, "complete", Maps.of("answer", 42L), where);

			AMap<AString, ACell> failed = step(agent, null,
				assistantCall("call_fail_task", TaskTools.FAIL,
					Maps.of("error", "cannot complete")), Strings.create("produce an answer"));
			exercisedTaskTools.add(TaskTools.FAIL);
			assertTerminal(failed, "failed", Strings.create("cannot complete"), where);
		}

		assertEquals(HarnessTools.SHARED.keySet(), exercisedHarness,
			"every shared harness tool needs a valid-reply behavioural case");
		assertEquals(TaskTools.NAMES, exercisedTaskTools,
			"every conditional task tool needs a valid-reply behavioural case");
	}

	@Test
	public void validModelRepliesDriveEveryGoalTreeToolboxMechanism() {
		AVector<ACell> tools = sharedTools()
			.concat(Vectors.of(
				(ACell) Strings.create(GoalTreeAdapter.TOOL_SUBGOAL),
				(ACell) Strings.create(GoalTreeAdapter.TOOL_COMPLETE),
				(ACell) Strings.create(GoalTreeAdapter.TOOL_FAIL),
				(ACell) Strings.create(GoalTreeAdapter.TOOL_COMPACT)));
		AMap<AString, ACell> config = sharedConfig(GOAL_TREE).assoc(Fields.TOOLS, tools);
		RunningAgent agent = start(GOAL_TREE, "goaltree-specific", config);
		Set<String> exercised = new HashSet<>();

		AMap<AString, ACell> complete = step(agent, "complete it",
			assistantCall("call_complete", GoalTreeAdapter.TOOL_COMPLETE,
				Maps.of("answer", 42L)), null);
		exercised.add(GoalTreeAdapter.TOOL_COMPLETE);
		assertTerminal(complete, "complete", Maps.of("answer", 42L), "goaltree");

		AMap<AString, ACell> fail = step(agent, "fail it",
			assistantCall("call_fail", GoalTreeAdapter.TOOL_FAIL,
				Maps.of("reason", "blocked")), null);
		exercised.add(GoalTreeAdapter.TOOL_FAIL);
		assertTerminal(fail, "failed", Maps.of("reason", "blocked"), "goaltree");

		AMap<AString, ACell> compact = step(agent, "retain this goal",
			assistantCall("call_compact", GoalTreeAdapter.TOOL_COMPACT,
				Maps.of("summary", "TOOLBOX_COMPACT_SUMMARY")), null);
		exercised.add(GoalTreeAdapter.TOOL_COMPACT);
		assertTrue(messages(compact).toString().contains("TOOLBOX_COMPACT_SUMMARY"),
			"compact summary missing from next inference: " + compact);

		// agent:step cannot recursively call a model, so subgoal reports that exact
		// boundary. GoalTreeAdapterTest exercises the deterministic live push/run/pop.
		AMap<AString, ACell> subgoal = step(agent, "delegate it",
			assistantCall("call_subgoal", GoalTreeAdapter.TOOL_SUBGOAL,
				Maps.of("description", "do the sub-task")), null);
		exercised.add(GoalTreeAdapter.TOOL_SUBGOAL);
		assertTrue(RT.getIn(firstCall(subgoal), Fields.RESULT).toString().contains("not executed"),
			"agent:step must state its subgoal boundary: " + subgoal);

		Set<String> expected = new HashSet<>(GoalTreeAdapter.HARNESS_TOOL_REGISTRY.keySet());
		expected.removeAll(HarnessTools.SHARED.keySet());
		assertEquals(expected, exercised,
			"every goal-tree-specific harness tool needs a valid-reply behavioural case");
	}

	private AMap<AString, ACell> sharedConfig(AgentRuntime runtime) {
		return Maps.of(
			Fields.OPERATION, runtime.operation(),
			"llmOperation", "v/test/ops/llm",
			Fields.TOOLS, sharedTools(),
			"skillsets", Vectors.of(Strings.create(SKILLSET)),
			Fields.LOADS, Maps.of(PINNED_PATH,
				Maps.of("budget", 500L, "label", "Pinned toolbox context")));
	}

	private static AVector<ACell> sharedTools() {
		return Vectors.of(
			(ACell) Strings.create(HarnessTools.CONTEXT_LOAD),
			(ACell) Strings.create(HarnessTools.CONTEXT_UNLOAD),
			(ACell) Strings.create(HarnessTools.MORE_TOOLS),
			(ACell) Strings.create("v/test/ops/echo"));
	}

	private RunningAgent start(AgentRuntime runtime, String id, AMap<AString, ACell> config) {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, config), user).awaitResult(5000);
		ACell chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "establish deterministic session"),
			user).awaitResult(10000);
		ACell sid = RT.getIn(chat, Fields.SESSION_ID);
		assertNotNull(sid, runtime.name() + " did not return a session id: " + chat);
		return new RunningAgent(id, sid.toString());
	}

	private AMap<AString, ACell> step(RunningAgent agent, String message,
			ACell assistant, ACell task) {
		AMap<AString, ACell> input = Maps.of(
			Fields.AGENT_ID, agent.id(),
			Fields.SESSION_ID, agent.sessionId(),
			"assistant", assistant);
		if (message != null) input = input.assoc(Fields.MESSAGE, Strings.create(message));
		if (task != null) input = input.assoc(Strings.intern("task"), task);
		return RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step", input, user)
			.awaitResult(10000));
	}

	private static AMap<AString, ACell> assistantCall(String id, String name, ACell arguments) {
		return Maps.of("toolCalls", Vectors.of((ACell) Maps.of(
			"id", id, "name", name, "arguments", arguments)));
	}

	private void write(String path, ACell value) {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), user).awaitResult(5000);
	}

	private static AVector<ACell> messages(AMap<AString, ACell> step) {
		AVector<ACell> messages = RT.ensureVector(
			RT.getIn(step, AbstractLLMAdapter.K_NEXT, Fields.MESSAGES));
		assertNotNull(messages, "step has no next messages: " + step);
		return messages;
	}

	private static ACell firstCall(AMap<AString, ACell> step) {
		AVector<ACell> calls = RT.ensureVector(step.get(Fields.CALLS));
		assertNotNull(calls, "step has no calls: " + step);
		assertEquals(1, calls.count(), "expected one call: " + step);
		return calls.get(0);
	}

	private static ACell call(AMap<AString, ACell> step, String id) {
		AVector<ACell> calls = RT.ensureVector(step.get(Fields.CALLS));
		assertNotNull(calls, "step has no calls: " + step);
		for (long i = 0; i < calls.count(); i++) {
			ACell call = calls.get(i);
			if (id.equals(String.valueOf(RT.getIn(call, AbstractLLMAdapter.K_ID)))) return call;
		}
		fail("step has no call " + id + ": " + step);
		return null;
	}

	private static void assertNextToolResult(AMap<AString, ACell> step,
			String id, String name, ACell expected, String where) {
		assertEquals(expected, RT.getIn(firstCall(step), Fields.RESULT), where);
		for (long i = 0; i < messages(step).count(); i++) {
			ACell message = messages(step).get(i);
			if ("tool".equals(String.valueOf(RT.getIn(message, "role")))
					&& id.equals(String.valueOf(RT.getIn(message, "id")))) {
				assertEquals(name, RT.getIn(message, "name").toString(), where);
				assertEquals(expected, RT.getIn(message, Fields.STRUCTURED_CONTENT), where);
				assertNull(RT.getIn(message, "content"),
					where + " structured-only result must remain content-absent");
				return;
			}
		}
		fail(where + " next inference has no matching tool result: " + step);
	}

	private static boolean hasTool(AMap<AString, ACell> step, String name) {
		AVector<ACell> tools = RT.ensureVector(RT.getIn(step, AbstractLLMAdapter.K_NEXT, Fields.TOOLS));
		for (long i = 0; tools != null && i < tools.count(); i++) {
			if (name.equals(String.valueOf(RT.getIn(tools.get(i), Fields.NAME)))) return true;
		}
		return false;
	}

	private static boolean hasSystemContent(AMap<AString, ACell> step, String expected) {
		for (long i = 0; i < messages(step).count(); i++) {
			ACell message = messages(step).get(i);
			if ("system".equals(String.valueOf(RT.getIn(message, "role")))
					&& String.valueOf(RT.getIn(message, "content")).contains(expected)) return true;
		}
		return false;
	}

	private static boolean hasToolAddition(AMap<AString, ACell> step, String name) {
		for (long i = 0; i < messages(step).count(); i++) {
			AVector<ACell> additions = RT.ensureVector(
				RT.getIn(messages(step).get(i), HarnessTools.K_TOOL_ADDITION));
			for (long j = 0; additions != null && j < additions.count(); j++) {
				if (name.equals(String.valueOf(RT.getIn(additions.get(j), Fields.NAME)))) return true;
			}
		}
		return false;
	}

	private static void assertTerminal(AMap<AString, ACell> step,
			String status, ACell value, String where) {
		assertEquals(status, RT.getIn(step, AbstractLLMAdapter.K_TERMINAL, Fields.STATUS).toString(), where);
		assertEquals(value, RT.getIn(step, AbstractLLMAdapter.K_TERMINAL, Fields.VALUE), where);
	}
}
