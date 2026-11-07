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
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
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
		write(SKILLSET + "/beta", Maps.of(
			"description", "Second toolbox tool bundle",
			"content", Maps.of("inline", "TOOLBOX_SECOND_SKILL_BODY"),
			"skill", Maps.of("tools", Vectors.of(
				Strings.create("v/ops/covia/read"),
				Strings.create("v/test/ops/capturectx")))));
	}

	@Test
	public void harnessDefinitionsProjectOrdinaryOperationMetadata() {
		for (var entry : HarnessTools.SHARED.entrySet()) {
			assertToolDefinition(entry.getValue(), entry.getKey());
		}
		for (String name : Set.of(GoalTreeAdapter.TOOL_SUBGOAL,
				GoalTreeAdapter.TOOL_COMPLETE, GoalTreeAdapter.TOOL_FAIL)) {
			assertToolDefinition(GoalTreeAdapter.HARNESS_TOOL_REGISTRY.get(name), name);
		}

		// The registered task operations and their harness aliases share the exact
		// description and input-schema values; only the provider-facing name differs.
		assertTaskAlias("/adapters/agent/completeTask.json", TaskTools.COMPLETE);
		assertTaskAlias("/adapters/agent/failTask.json", TaskTools.FAIL);
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
	public void cachedBindingsDoNotFollowAmbientOperationMetadataChanges() {
		String path = "w/toolbox/mutable-operation";
		for (AgentRuntime runtime : RUNTIMES) {
			write(path, operation("stable_tool"));
			AMap<AString, ACell> config = sharedConfig(runtime).assoc(Fields.TOOLS,
				(AVector<ACell>) sharedTools().concat(
					Vectors.of((ACell) Strings.create(path))));
			RunningAgent agent = start(runtime, runtime.name() + "-stable-binding", config);

			// The catalog path is ambient. Its new metadata must not reinterpret
			// the schema and route already rendered into this session.
			write(path, operation("changed_tool"));
			AMap<AString, ACell> called = step(agent, "use the original advertised tool",
				assistantCall("call_stable", "stable_tool", Maps.of("ok", true)), null);
			assertEquals(Maps.of("ok", true),
				RT.getIn(call(called, "call_stable"), Fields.RESULT), runtime.name());

			AMap<AString, ACell> cachedContext = RT.ensureMap(engine.jobs().invokeOperation(
				"v/ops/agent/context", Maps.of(
					Fields.AGENT_ID, agent.id(), Fields.SESSION_ID, agent.sessionId()), user)
				.awaitResult(10000));
			assertEquals(path,
				RT.getIn(paletteEntry(cachedContext, "stable_tool"), Fields.OPERATION).toString());
			assertNull(findPaletteEntry(cachedContext, "changed_tool"),
				"ambient metadata must not rewrite cached tool identity");
		}
	}

	@Test
	public void validModelRepliesDriveEverySharedToolboxMechanism() {
		Set<String> exercisedHarness = new HashSet<>();
		Set<String> exercisedTaskTools = new HashSet<>();

		for (AgentRuntime runtime : RUNTIMES) {
			RunningAgent agent = start(runtime, runtime.name() + "-shared", sharedConfig(runtime));
			String where = runtime.name();
			AMap<AString, ACell> cachedContext = RT.ensureMap(engine.jobs().invokeOperation(
				"v/ops/agent/context", Maps.of(
					Fields.AGENT_ID, agent.id(), Fields.SESSION_ID, agent.sessionId()), user)
				.awaitResult(10000));
			assertNull(findPaletteEntry(cachedContext, "covia_read"),
				where + " discoverable skill schema leaked into the fixed palette");
			assertFalse(hasTool(RT.ensureVector(cachedContext.get(Fields.TOOLS)), "covia_read"),
				where + " discoverable skill schema leaked into provider tools");

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
			ACell loadedEvent = callNamed(loaded, ContextAssembler.LOADED_CONTEXT_TOOL);
			assertTrue(ToolCallIds.valid(RT.ensureString(
				RT.getIn(loadedEvent, AbstractLLMAdapter.K_ID))),
				where + " context_load appended an invalid provider call id");
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

			// A discoverable skill operation is not a provider tool before load.
			AMap<AString, ACell> direct = step(agent, "use a skill-declared tool",
				assistantCall("call_direct_read", "covia_read",
					Maps.of("path", CONTEXT_PATH)), null);
			ACell directResult = RT.getIn(call(direct, "call_direct_read"), Fields.RESULT);
			assertTrue(directResult.toString().startsWith("Error:"),
				where + " dispatched a skill tool that was never loaded: " + direct);

			// skill_load appends both trusted instructions and exact tool state.
			// invoke_tool is a later barrier in the same batch and sees the new route.
			AMap<AString, ACell> skill = step(agent, "load a skill",
				Maps.of("toolCalls", Vectors.of(
					(ACell) Maps.of("id", "call_skill_load", "name", HarnessTools.SKILL_LOAD,
						"arguments", Maps.of("name", "alpha")),
					(ACell) Maps.of("id", "call_skill_read", "name", HarnessTools.INVOKE_TOOL,
						"arguments", Maps.of("name", "covia_read",
							"input", Maps.of("path", CONTEXT_PATH))),
					// The same call with input serialised as a JSON string — what a
					// model emits on large payloads (#508). input is the target
					// tool's arguments and can only be an object, so the string is
					// parsed and the two calls must behave identically; the
					// alternative was a null path reaching covia_read and a
					// misleading error coming back.
					(ACell) Maps.of("id", "call_skill_read_str", "name", HarnessTools.INVOKE_TOOL,
						"arguments", Maps.of("name", "covia_read",
							"input", Strings.create("{\"path\": \"" + CONTEXT_PATH + "\"}"))))), null);
			exercisedHarness.add(HarnessTools.SKILL_LOAD);
			exercisedHarness.add(HarnessTools.INVOKE_TOOL);
			assertTrue(hasSystemContent(skill, SKILL_BODY),
				where + " skill instructions missing from next prompt: " + skill);
			assertTrue(hasTool(skill, HarnessTools.INVOKE_TOOL), where);
			assertFalse(hasTool(skill, "covia_read"),
				where + " loaded skill rewrote the fixed provider tools: " + skill);
			assertTrue(hasToolAddition(skill, "covia_read"),
				where + " loaded skill did not append its exact tool definition: " + skill);
			assertFalse(RT.getIn(call(skill, "call_skill_read"), Fields.RESULT).toString()
				.startsWith("Error:"), where + " loaded skill route was not active immediately: " + skill);
			assertEquals(RT.getIn(call(skill, "call_skill_read"), Fields.RESULT),
				RT.getIn(call(skill, "call_skill_read_str"), Fields.RESULT),
				where + " stringified invoke_tool input did not dispatch like the object form: " + skill);

			// more_tools is a tool-only load. The parallel dispatcher call proves
			// the new route is live immediately without rewriting the fixed palette.
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
			AString toolLoadPath = RT.ensureString(RT.getIn(
				call(more, "call_more_tools"), Fields.RESULT, AbstractLLMAdapter.K_PATH));
			assertNotNull(toolLoadPath, where + " more_tools did not return its load key: " + more);

			// compact is one shared conversation primitive. The summary is
			// assistant memory and the archived inputs are not repeated visibly.
			AMap<AString, ACell> compact = step(agent, "retain this work",
				assistantCall("call_compact", HarnessTools.COMPACT,
					Maps.of("summary", "TOOLBOX_COMPACT_SUMMARY")), null);
			exercisedHarness.add(HarnessTools.COMPACT);
			String[] reply = FrameTools.COMPACTED.split("%d");
			String compacted = String.valueOf(result(compact, "call_compact"));
			assertTrue(compacted.startsWith(reply[0]) && compacted.endsWith(reply[1]),
				where + " answers compact as every runtime does: " + compacted);
			assertTrue(messages(compact).toString().contains("TOOLBOX_COMPACT_SUMMARY"),
				where + " compact summary missing from next inference: " + compact);
			assertFalse(hasSystemContent(compact, "TOOLBOX_COMPACT_SUMMARY"),
				where + " elevated agent-authored compacted memory to system authority");

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

	/**
	 * A name the session cannot call is answered with what would make it
	 * callable, and nothing runs (#643): the regression was a skill tool called
	 * before its skill was loaded falling through to grid dispatch.
	 */
	@Test
	public void unboundToolNamesAreAnsweredWithoutRunning() {
		for (AgentRuntime runtime : RUNTIMES) {
			RunningAgent agent = start(runtime, runtime.name() + "-unbound", sharedConfig(runtime));
			String where = runtime.name();
			AMap<AString, ACell> step = step(agent, "call tools by name",
				Maps.of("toolCalls", Vectors.of(
					invoke("call_unloaded", "covia_read", Maps.of("path", CONTEXT_PATH)),
					invoke("call_near_miss", "test-echo", Maps.of("ok", true)),
					invoke("call_unknown", "records_read", Maps.empty()),
					invoke("call_harness", HarnessTools.CONTEXT_LOAD, Maps.of("path", CONTEXT_PATH)),
					invoke("call_reference", "v/test/ops/echo", Maps.of("ok", true)))), null);

			assertEquals(Strings.create("Error: " + String.format(HarnessTools.TOOL_NOT_LOADED,
				"covia_read", "[alpha, beta]")), result(step, "call_unloaded"), where);
			assertEquals(Strings.create("Error: " + String.format(HarnessTools.TOOL_NEAR_MISS,
				"test-echo", "test_echo")), result(step, "call_near_miss"), where);
			assertEquals(Strings.create("Error: " + String.format(HarnessTools.NO_SUCH_TOOL, "records_read")
				+ "; " + HarnessTools.USE_TOOL_LIST), result(step, "call_unknown"), where);
			assertEquals(Strings.create("Error: " + String.format(AbstractLLMAdapter.CALL_HARNESS_DIRECTLY,
				HarnessTools.CONTEXT_LOAD)), result(step, "call_harness"), where);
			assertFalse(messages(step).toString().contains(CONTEXT_MARKER),
				where + " a refused call loaded or read the context: " + step);
			// A full operation reference still dispatches as one.
			assertEquals(Maps.of("ok", true), result(step, "call_reference"), where);
		}
	}

	/** A harness tool runs only where it is offered: one the agent was not
	 *  given is refused as such, named directly or through invoke_tool, and
	 *  nothing runs. */
	@Test
	public void unofferedHarnessToolsAreRefused() {
		String notOffered = "Error: " + AbstractLLMAdapter.HARNESS_NOT_OFFERED;
		for (AgentRuntime runtime : RUNTIMES) {
			// more_tools brings invoke_tool and context_unload; not context_load or compact.
			AMap<AString, ACell> config = Maps.of(
				Fields.OPERATION, runtime.operation(),
				"llmOperation", "v/test/ops/llm",
				Fields.TOOLS, Vectors.of(Strings.create(HarnessTools.MORE_TOOLS)));
			RunningAgent agent = start(runtime, runtime.name() + "-unoffered", config);
			String where = runtime.name();
			AMap<AString, ACell> step = step(agent, "call tools it was not given",
				Maps.of("toolCalls", Vectors.of(
					Maps.of("id", "call_load", "name", HarnessTools.CONTEXT_LOAD,
						"arguments", Maps.of("path", CONTEXT_PATH)),
					invoke("call_compact", HarnessTools.COMPACT, Maps.of("summary", "earlier work")))), null);

			assertEquals(Strings.create(String.format(notOffered, HarnessTools.CONTEXT_LOAD)),
				result(step, "call_load"), where);
			assertEquals(Strings.create(String.format(notOffered, HarnessTools.COMPACT)),
				result(step, "call_compact"), where);
			assertFalse(messages(step).toString().contains(CONTEXT_MARKER),
				where + " a refused call loaded the context: " + step);
		}
	}

	/** agent:step answers compact with the count a live cycle gives: its frame
	 *  takes the reply and each earlier result as they arrive. compactllm's first
	 *  reply calls test_echo, then compact. */
	@Test
	public void stepAnswersCompactAsALiveCycleDoes() {
		for (AgentRuntime runtime : RUNTIMES) {
			String id = runtime.name() + "-compact-count";
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, runtime.operation(),
					"llmOperation", "v/test/ops/compactllm",
					Fields.TOOLS, Vectors.of(
						Strings.create(HarnessTools.COMPACT), Strings.create("v/test/ops/echo")))),
				user).awaitResult(5000);
			AMap<AString, ACell> stepped = step(new RunningAgent(id, null), "compact this",
				Maps.of("toolCalls", Vectors.of(
					Maps.of("id", "call_e1", "name", "test_echo", "arguments", Maps.of("input", "hello")),
					Maps.of("id", "call_c1", "name", HarnessTools.COMPACT,
						"arguments", Maps.of("summary", "Made an echo call.")))), null);

			ACell chat = engine.jobs().invokeOperation("v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, id, Fields.MESSAGE, "compact this"), user).awaitResult(10000);
			Blob sid = Blob.fromHex(RT.getIn(chat, Fields.SESSION_ID).toString());
			AMap<AString, ACell> root = RT.ensureMap(RT.getIn(engine.getVenueState().users().user(userDid)
				.agent(id).getSession(sid), Fields.FRAMES, CVMLong.ZERO));
			ACell live = archivedToolResult(GoalTreeContext.archivedConversation(root), "call_c1");
			assertNotNull(live, runtime.name() + " live cycle did not record compact: " + root);
			assertEquals(live, result(stepped, "call_c1"), runtime.name());
		}
	}

	/** A tool call's result in a conversation, looking inside compacted segments. */
	private static ACell archivedToolResult(AVector<ACell> conversation, String id) {
		for (long i = 0; conversation != null && i < conversation.count(); i++) {
			ACell turn = conversation.get(i);
			ACell found = GoalTreeContext.isSegment(turn)
				? archivedToolResult(RT.ensureVector(RT.getIn(turn, GoalTreeContext.K_ITEMS)), id)
				: ("tool".equals(String.valueOf(RT.getIn(turn, "role")))
					&& id.equals(String.valueOf(RT.getIn(turn, "id")))) ? RT.getIn(turn, "content") : null;
			if (found != null) return found;
		}
		return null;
	}

	/** With few tools, a name nothing provides is answered with all of them. */
	@Test
	public void unknownNameListsAShortToolList() {
		for (AgentRuntime runtime : RUNTIMES) {
			AMap<AString, ACell> config = Maps.of(
				Fields.OPERATION, runtime.operation(),
				"llmOperation", "v/test/ops/llm",
				Fields.TOOLS, Vectors.of(Strings.create("v/test/ops/echo")));
			RunningAgent agent = start(runtime, runtime.name() + "-short-list", config);
			AMap<AString, ACell> step = step(agent, "call a tool that does not exist",
				assistantCall("call_unknown", "records_read", Maps.empty()), null);
			assertEquals(Strings.create("Error: " + HarnessTools.noSuchTool("records_read",
				Set.of(TaskTools.COMPLETE, TaskTools.FAIL, "test_echo"))),
				result(step, "call_unknown"), runtime.name());
		}
	}

	private static ACell invoke(String id, String name, ACell input) {
		return Maps.of("id", id, "name", HarnessTools.INVOKE_TOOL,
			"arguments", Maps.of("name", name, "input", input));
	}

	private static ACell result(AMap<AString, ACell> step, String id) {
		return RT.getIn(call(step, id), Fields.RESULT);
	}

	@Test
	public void validModelRepliesDriveEveryGoalTreeToolboxMechanism() {
		AVector<ACell> tools = sharedTools()
			.concat(Vectors.of(
				(ACell) Strings.create(GoalTreeAdapter.TOOL_SUBGOAL),
				(ACell) Strings.create(GoalTreeAdapter.TOOL_COMPLETE),
				(ACell) Strings.create(GoalTreeAdapter.TOOL_FAIL)));
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

		// agent:step cannot recursively call a model, so subgoal reports that exact
		// boundary. GoalTreeAdapterTest exercises the deterministic live push/run/pop.
		AMap<AString, ACell> subgoal = step(agent, "delegate it",
			assistantCall("call_subgoal", GoalTreeAdapter.TOOL_SUBGOAL,
				Maps.of("description", "do the sub-task")), null);
		exercised.add(GoalTreeAdapter.TOOL_SUBGOAL);
		assertTrue(RT.getIn(firstCall(subgoal), Fields.RESULT).toString().contains(GoalTreeAdapter.STEP_SUBGOAL_NOTE),
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
			(ACell) Strings.create(HarnessTools.COMPACT),
			(ACell) Strings.create("v/test/ops/echo"));
	}

	private static AMap<AString, ACell> operation(String toolName) {
		return Maps.of(
			Fields.NAME, toolName,
			Fields.DESCRIPTION, "Mutable toolbox operation",
			Fields.OPERATION, Maps.of(
				Fields.ADAPTER, "test:echo",
				Fields.TOOL_NAME, toolName,
				Fields.INPUT, Maps.of("type", "object")));
	}

	private static void assertTaskAlias(String resource, String aliasName) {
		AMap<AString, ACell> operation = HarnessTools.definition(resource);
		AMap<AString, ACell> alias = tool(TaskTools.DEFINITIONS, aliasName);
		assertEquals(operation.get(AbstractLLMAdapter.K_DESCRIPTION),
			alias.get(AbstractLLMAdapter.K_DESCRIPTION));
		assertEquals(operation.get(AbstractLLMAdapter.K_PARAMETERS),
			alias.get(AbstractLLMAdapter.K_PARAMETERS));
		assertEquals(aliasName, alias.get(AbstractLLMAdapter.K_NAME).toString());
	}

	private static void assertToolDefinition(AMap<AString, ACell> definition, String expectedName) {
		assertNotNull(definition, expectedName);
		assertEquals(expectedName, definition.get(AbstractLLMAdapter.K_NAME).toString());
		assertInstanceOf(AString.class, definition.get(AbstractLLMAdapter.K_DESCRIPTION));
		assertInstanceOf(AMap.class, definition.get(AbstractLLMAdapter.K_PARAMETERS));
	}

	private static AMap<AString, ACell> tool(AVector<ACell> definitions, String name) {
		for (long i = 0; i < definitions.count(); i++) {
			AMap<AString, ACell> definition = RT.ensureMap(definitions.get(i));
			if (name.equals(String.valueOf(definition.get(AbstractLLMAdapter.K_NAME)))) return definition;
		}
		fail("missing tool definition " + name);
		return null;
	}

	private static ACell paletteEntry(AMap<AString, ACell> context, String name) {
		ACell entry = findPaletteEntry(context, name);
		if (entry != null) return entry;
		fail("missing palette entry " + name + ": " + context);
		return null;
	}

	private static ACell findPaletteEntry(AMap<AString, ACell> context, String name) {
		AVector<ACell> entries = RT.ensureVector(
			RT.getIn(context, "palette", Fields.TOOLS));
		for (long i = 0; entries != null && i < entries.count(); i++) {
			ACell entry = entries.get(i);
			if (name.equals(String.valueOf(RT.getIn(entry, Fields.NAME)))) return entry;
		}
		return null;
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

	private static ACell callNamed(AMap<AString, ACell> step, String name) {
		AVector<ACell> calls = RT.ensureVector(step.get(Fields.CALLS));
		assertNotNull(calls, "step has no calls: " + step);
		for (long i = 0; i < calls.count(); i++) {
			ACell call = calls.get(i);
			if (name.equals(String.valueOf(RT.getIn(call, AbstractLLMAdapter.K_NAME)))) return call;
		}
		fail("step has no call named " + name + ": " + step);
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
		return hasTool(tools, name);
	}

	private static boolean hasTool(AVector<ACell> tools, String name) {
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
