package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

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
import covia.adapter.AAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import convex.core.data.Blob;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for GoalTreeAdapter — the goal tree agent transition function.
 *
 * <p>Uses the shared {@link TestEngine#ENGINE} with per-test ALICE_DID.</p>
 */
public class GoalTreeAdapterTest {
	private static boolean hasNamedTool(AVector<ACell> tools, String expected) {
		if (tools == null) return false;
		for (long i = 0; i < tools.count(); i++) {
			AString name = RT.ensureString(RT.getIn(tools.get(i), Fields.NAME));
			if (name != null && expected.equals(name.toString())) return true;
		}
		return false;
	}

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;
	private RequestContext ALICE;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		ALICE = RequestContext.of(ALICE_DID);
	}

	// ========== Registration ==========

	@Test
	public void testAdapterRegistered() {
		AAdapter adapter = engine.getAdapter("goaltree");
		assertNotNull(adapter, "GoalTreeAdapter should be registered");
		assertEquals("goaltree", adapter.getName());
	}

	@Test
	public void testOperationResolvable() {
		// goaltree:chat should resolve to an operation
		Job job = engine.jobs().invokeOperation("v/ops/goaltree/chat",
			Maps.of(Fields.AGENT_ID, "test-agent",
				AgentState.KEY_CONFIG, Maps.of(
					Strings.create("llmOperation"), Strings.create("v/test/ops/llm"))),
			ALICE);
		// Should start without error (may fail on LLM call, but shouldn't NPE)
		assertNotNull(job);
		// Job should have a status field — even PENDING is fine, no need to wait
		assertNotNull(job.getStatus());
	}

	// ========== skill_load in the goal-tree loop (SKILLS.md §5, §7) ==========

	/** Fixture: the 'alpha' skill (body + one tool) and the probe value its
	 *  tool reads — same shape as LLMAgentAdapterTest's. */
	private void writeAlphaSkill() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/alpha",
				"value", Maps.of(
					"description", "Alpha skill",
					"content", Maps.of("inline", "Use covia_read on w/probe."),
					"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/read"))))),
			ALICE).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/probe", "value", "probe-value"), ALICE).awaitResult(5000);
	}

	@Test
	public void testSkillAdoptionDuringGoalRun() {
		// The operative-loop proof for goaltree: within one transition the
		// mock loads the skill (written to the FRAME tier), the palette gains
		// covia_read on the next iteration, and the tool dispatches.
		writeAlphaSkill();
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "skill-goal-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/skillllm"),
				Strings.create("skillsets"), Vectors.of(Strings.create("w/skills"))),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("use the alpha skill"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		assertTrue(response.toString().contains("SKILL_TOOL_RESULT"), response.toString());
		assertTrue(response.toString().contains("probe-value"), response.toString());

		// The skill entry landed on the root FRAME's loads tier.
		AVector<ACell> frames = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		assertNotNull(frames);
		AMap<AString, ACell> rootLoads = GoalTreeContext.getLoads(
			(AMap<AString, ACell>) frames.get(0));
		assertTrue(Skills.isSkillEntry(rootLoads.get(Strings.create("w/skills/alpha"))),
			"skill entry must persist on the frame tier: " + rootLoads);
	}

	@Test
	public void testSubgoalInheritsAndMasksSkillLoads() {
		// Frame-tier scoping: a child frame inherits the parent's skill entry
		// (copy-on-push), contributes its tools, and can mask it locally with
		// a tombstone without touching the parent.
		writeAlphaSkill();
		Skills.ResolvedSkill skill = Skills.resolveRef(engine, ALICE, Strings.create("w/skills/alpha"));
		AMap<AString, ACell> skillMeta = Skills.buildSkillLoadMeta(2000, skill);

		AMap<AString, ACell> parent = GoalTreeContext.createFrame("root goal");
		parent = GoalTreeContext.addLoad(parent, Strings.create("w/skills/alpha"), skillMeta);

		// Copy-on-push inheritance (the subgoal path: child seeded from parent loads)
		AMap<AString, ACell> child = GoalTreeContext.createFrame("sub goal",
			GoalTreeContext.getLoads(parent));
		AMap<AString, ACell> childLoads = GoalTreeContext.getLoads(child);
		assertTrue(Skills.isSkillEntry(childLoads.get(Strings.create("w/skills/alpha"))));

		// The inherited entry contributes tools in the child's effective view
		java.util.Map<String, AString> routes = new java.util.HashMap<>();
		AVector<ACell> defs = ToolPalette.loadsToolDefs(engine, ALICE,
			ContextChain.effective(childLoads), java.util.Set.of(), routes);
		assertEquals(1, defs.count());
		assertEquals("covia_read", RT.getIn(defs.get(0), Strings.intern("name")).toString());

		// Masking: unload in the child writes a tombstone; the child's
		// effective view loses body AND tools, the parent is untouched.
		AMap<AString, ACell> masked = ContextChain.unload(
			childLoads, GoalTreeContext.getLoads(parent), Strings.create("w/skills/alpha"));
		assertNotNull(masked);
		AMap<AString, ACell> childEffective = ContextChain.effective(
			GoalTreeContext.getLoads(parent), masked);
		assertEquals(0, childEffective.count());
		assertEquals(0, ToolPalette.loadsToolDefs(engine, ALICE,
			childEffective, java.util.Set.of(), new java.util.HashMap<>()).count());
		Loads.Snapshot unloaded = Loads.resolve(
			engine, ALICE, childEffective, java.util.Set.of(), Labels.BRACKET);
		ACell hallucinated = ((GoalTreeAdapter) engine.getAdapter("goaltree")).dispatchTool(
			"covia_read", Maps.of("path", "w/probe"), unloaded.routes(), ALICE,
			AbstractLLMAdapter.DEFAULT_TOOL_CALL_TIMEOUT_MS);
		assertTrue(String.valueOf(hallucinated).startsWith("Error:"),
			"a manually supplied call after unload must not retain a dispatch route: "
				+ hallucinated);
		assertTrue(Skills.isSkillEntry(
			GoalTreeContext.getLoads(parent).get(Strings.create("w/skills/alpha"))),
			"parent tier untouched by the child's mask");
	}

	@Test
	public void testMoreToolsMidLoopAdoption() {
		// The pre-existing more_tools mechanism, exercised end-to-end for the
		// first time: the mock adds v/test/ops/echo mid-run, sees it offered
		// on the next iteration, calls it, and reports the result. Also
		// exercises the loads-tools dedup baseline refresh on baseTools growth.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "more-tools-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/moretoolsllm"),
				Strings.create("tools"), Vectors.of(Strings.create("more_tools"))),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("get more tools"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		assertTrue(response.toString().contains("MORE_TOOLS_RESULT"), response.toString());
		assertTrue(response.toString().contains("mid-loop"), response.toString());
	}

	// ========== Skills index (config.skills — SKILLS.md §4) ==========

	@Test
	public void testSkillsIndexInFirstIterationContext() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/alpha",
				"value", Maps.of("description", "Alpha skill")),
			ALICE).awaitResult(5000);

		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		AMap<AString, ACell> config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"skillsets", Vectors.of(Strings.create("w/skills")));

		AMap<AString, ACell> l3 = adapter.buildFirstIterationL3Input(config, null, null, ALICE);
		AVector<ACell> messages = RT.ensureVector(RT.getIn(l3, Fields.MESSAGES));
		assertNotNull(messages);
		boolean found = false;
		for (long i = 0; i < messages.count(); i++) {
			AString c = RT.ensureString(RT.getIn(messages.get(i), "content"));
			if (c != null && c.toString().contains("[Skills]")
					&& c.toString().contains("- alpha — Alpha skill")) {
				found = true;
				break;
			}
		}
		assertTrue(found, "skills index should be injected as a system message");
	}

	/** agent:step on a goaltree agent: one root-frame iteration over an in-memory store. */
	@Test
	public void testStepRunsOneFrameIteration() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "step-goal",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/goaltree/chat",
					"llmOperation", "v/test/ops/llm",
					Fields.TOOLS, Vectors.of(Strings.create("complete"), Strings.create("subgoal"),
						Strings.create("v/test/ops/echo")))),
			ALICE).awaitResult(5000);

		// A tool call lands in the root frame; the next prompt renders the
		// frame — the input turn, then the reply and its tool result.
		AMap<AString, ACell> stepped = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-goal", Fields.MESSAGE, "Echo this",
				"assistant", Maps.of("toolCalls", Vectors.of(
					Maps.of("name", "test_echo", "arguments", Maps.of("x", 1L))))),
			ALICE).awaitResult(5000));
		assertEquals(CVMBool.FALSE, stepped.get(Strings.intern("done")));
		AVector<ACell> messages = RT.ensureVector(
			RT.getIn(stepped, Strings.intern("next"), Fields.MESSAGES));
		int user = -1, tool = -1;
		for (int i = 0; i < messages.count(); i++) {
			String role = RT.getIn(messages.get(i), "role").toString();
			ACell content = RT.getIn(messages.get(i), "content");
			if (user < 0 && "user".equals(role) && content != null
					&& content.toString().contains("Echo this")) user = i;
			if ("tool".equals(role)) tool = i;
		}
		assertTrue(user >= 0 && tool > user, "input turn then tool result: " + messages);
		AVector<ACell> calls = RT.ensureVector(stepped.get(Strings.intern("calls")));
		assertEquals(Maps.of("x", 1L), RT.getIn(calls.get(0), "result"));

		// complete ends the frame with its value — reported, nothing resolved.
		AMap<AString, ACell> done = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-goal", Fields.MESSAGE, "hi",
				"assistant", Maps.of("toolCalls", Vectors.of(
					Maps.of("name", "complete", "arguments", Maps.of("result", "done"))))),
			ALICE).awaitResult(5000));
		assertEquals(CVMBool.TRUE, done.get(Strings.intern("done")));
		assertEquals("complete", RT.getIn(done, "terminal", "name").toString());
		assertEquals(Strings.create("done"), RT.getIn(done, "terminal", "value", "result"));
		assertNull(done.get(Strings.intern("next")));
		AVector<ACell> turns = RT.ensureVector(done.get(Fields.TURNS));
		assertEquals("assistant", RT.getIn(turns.get(turns.count() - 1), "role").toString());

		// subgoal is reported, not run — it would call the model.
		AMap<AString, ACell> sub = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/step",
			Maps.of(Fields.AGENT_ID, "step-goal", Fields.MESSAGE, "hi",
				"assistant", Maps.of("toolCalls", Vectors.of(
					Maps.of("name", "subgoal", "arguments", Maps.of("goal", "sub"))))),
			ALICE).awaitResult(5000));
		String result = RT.getIn(RT.ensureVector(sub.get(Strings.intern("calls"))).get(0), "result").toString();
		assertTrue(result.contains("not executed"), result);
		assertEquals(CVMBool.FALSE, sub.get(Strings.intern("done")));
	}

	/** A subgoal's exchange is recorded under the call that produced it (#392):
	 *  the child frame is popped from the session, so the entry is where its history lives. */
	@Test
	public void testSubgoalRecordedUnderItsCall() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "subgoal-record",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/goaltree/chat",
					"llmOperation", "v/test/ops/subgoalechollm",
					Fields.TOOLS, Vectors.of(Strings.create("subgoal")))),
			ALICE).awaitResult(5000);
		ACell chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "subgoal-record", Fields.MESSAGE, "decompose this"),
			ALICE).awaitResult(15000);
		assertEquals("root done", RT.getIn(chat, Fields.RESPONSE).toString());
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("subgoal-record");
		TestEngine.awaitTimelineCount(agent, 1, 10000);
		AMap<AString, ACell> entry = RT.ensureMap(agent.getTimeline().get(0));

		AVector<ACell> inferences = RT.ensureVector(entry.get(Fields.INFERENCES));
		assertEquals(2, inferences.count(), "root: the subgoal call, then the answer: " + inferences);
		ACell call = RT.ensureVector(RT.getIn(inferences.get(0), Fields.CALLS)).get(0);
		assertEquals("subgoal", RT.getIn(call, "name").toString());
		assertEquals("complete", RT.getIn(call, Fields.RESULT, "status").toString());

		// The child frame, in the same shape, under the call.
		AMap<AString, ACell> frame = RT.ensureMap(RT.getIn(call, Fields.FRAME));
		assertNotNull(frame, "the child's record rides its call: " + call);
		AVector<ACell> childContext = RT.ensureVector(frame.get(Fields.CONTEXT));
		assertTrue(RT.getIn(childContext.get(0), "content").toString().contains(GoalTreeAdapter.CHILD_FRAME_NOTICE),
			"the child's head is new — it carries the child notice");
		assertFalse(RT.ensureVector(frame.get(Fields.TOOLS)).toString().contains("subgoal"),
			"children are not offered subgoal");
		AVector<ACell> childInferences = RT.ensureVector(frame.get(Fields.INFERENCES));
		assertEquals(2, childInferences.count(), "child: the echo call, then its answer: " + childInferences);
		String sent = RT.getIn(childInferences.get(0), Fields.SENT).toString();
		assertTrue(sent.contains("run the sub-task"), "the child's goal is its first inference's sent: " + sent);
		ACell echo = RT.ensureVector(RT.getIn(childInferences.get(0), Fields.CALLS)).get(0);
		assertEquals("v/test/ops/echo", RT.getIn(echo, "name").toString());
		assertEquals("sub done", RT.getIn(childInferences.get(1), Fields.REPLY, "content").toString());
		assertEquals("root done", RT.getIn(inferences.get(1), Fields.REPLY, "content").toString());

		// The session keeps only the root: the child's history exists nowhere else.
		AMap<AString, ACell> session = agent.getSession(
			Blob.fromHex(RT.getIn(chat, Fields.SESSION_ID).toString()));
		assertEquals(1, RT.ensureVector(RT.getIn(session, Fields.FRAMES)).count());
	}

	// ========== Tool definitions ==========

	@Test
	public void testHarnessToolRegistry() {
		// All 8 harness tools are in the registry
		assertEquals(8, GoalTreeAdapter.HARNESS_TOOL_REGISTRY.size());
		assertTrue(GoalTreeAdapter.isHarnessTool("subgoal"));
		assertTrue(GoalTreeAdapter.isHarnessTool("complete"));
		assertTrue(GoalTreeAdapter.isHarnessTool("fail"));
		assertTrue(GoalTreeAdapter.isHarnessTool("compact"));
		assertTrue(GoalTreeAdapter.isHarnessTool("context_load"));
		assertTrue(GoalTreeAdapter.isHarnessTool("context_unload"));
		assertTrue(GoalTreeAdapter.isHarnessTool("more_tools"));
		assertTrue(GoalTreeAdapter.isHarnessTool("skill_load"));
		assertFalse(GoalTreeAdapter.isHarnessTool("covia_read"));

		// Each definition has name, description, parameters
		for (var entry : GoalTreeAdapter.HARNESS_TOOL_REGISTRY.entrySet()) {
			AMap<AString, ACell> tool = entry.getValue();
			assertNotNull(tool.get(Strings.intern("name")), entry.getKey() + " should have name");
			assertNotNull(tool.get(Strings.intern("description")), entry.getKey() + " should have description");
			assertNotNull(tool.get(Strings.intern("parameters")), entry.getKey() + " should have parameters");
		}
	}

	@Test
	public void testResolveHarnessToolsFromConfig() {
		// Config with some harness tools + an operation path
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("tools"), Vectors.of(
				(ACell) Strings.create("subgoal"),
				(ACell) Strings.create("complete"),
				(ACell) Strings.create("v/ops/covia/read"), // not a harness tool — skipped
				(ACell) Strings.create("more_tools")));
		AVector<ACell> resolved = GoalTreeAdapter.resolveHarnessTools(config);
		assertEquals(3, resolved.count());
		assertEquals("subgoal", RT.ensureString(RT.getIn(resolved.get(0), "name")).toString());
		assertEquals("complete", RT.ensureString(RT.getIn(resolved.get(1), "name")).toString());
		assertEquals("more_tools", RT.ensureString(RT.getIn(resolved.get(2), "name")).toString());
	}

	@Test
	public void testResolveHarnessToolsEmptyConfig() {
		// No tools in config → no harness tools
		AVector<ACell> resolved = GoalTreeAdapter.resolveHarnessTools(null);
		assertEquals(0, resolved.count());

		// Empty tools list
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("tools"), Vectors.empty());
		resolved = GoalTreeAdapter.resolveHarnessTools(config);
		assertEquals(0, resolved.count());
	}

	// ========== Simple transition (using test:llm mock) ==========

	@Test
	public void testSimpleTransitionTextOnly() {
		// test:llm returns a simple assistant message (no tool calls)
		// This should trigger implicit complete at root level
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "test-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Hello"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		assertNotNull(output);

		// Should have state and result
		assertNotNull(RT.getIn(output, AgentState.KEY_STATE));
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Should have a response");
		assertTrue(response.toString().length() > 0, "Response should not be empty");
	}

	@Test
	public void testRootConversationSentOnceToLlm() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		AString agentId = Strings.create("capture-root-agent");
		String unique = "root-turn-appears-once";
		TestAdapter.CAPTURED_LLM_INPUT.remove(agentId);

		ACell input = Maps.of(
			Fields.AGENT_ID, agentId,
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"systemPrompt", "Test root history."),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create(unique))));

		adapter.processGoal(null, ALICE.withAgentId(agentId), input);
		ACell captured = TestAdapter.CAPTURED_LLM_INPUT.get(agentId);
		assertNotNull(captured, "mock LLM input should be captured for this agent");
		AVector<ACell> messages = RT.ensureVector(RT.getIn(captured, Fields.MESSAGES));
		int matches = 0;
		for (long i = 0; i < messages.count(); i++) {
			AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
			AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
			if ("user".equals(String.valueOf(role))
					&& unique.equals(String.valueOf(content))) matches++;
		}
		assertEquals(1, matches,
			"shared context and runFrame must not both append the root conversation");
	}

	@Test
	public void testTransitionOutputCarriesTokens() {
		// #217: every invokeLevel3 in the frame run adds its reported usage;
		// the cycle total (here: one goal call + one tool-result call) rides
		// the transition output with the total == input + output invariant.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "tokens-goal-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/toolllm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Do something"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		ACell tokens = RT.getIn(output, Fields.TOKENS);
		assertNotNull(tokens, "goal-tree cycle usage must ride the transition output");
		long in = RT.ensureLong(RT.getIn(tokens, Fields.INPUT)).longValue();
		long out = RT.ensureLong(RT.getIn(tokens, Fields.OUTPUT)).longValue();
		assertTrue(in > 0 && out > 0, "both sides measured across the tool loop");
		assertEquals(in + out, RT.ensureLong(RT.getIn(tokens, Fields.TOTAL)).longValue());
	}

	@Test
	public void testTransitionWithToolCall() {
		// test:toolllm makes one tool call (test:echo), then returns text on seeing results
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "tool-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/toolllm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Do something"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Should have a response after tool loop");
		assertTrue(response.toString().contains("Tool returned"),
			"Response should include tool result: " + response);
	}

	@Test
	public void testTransitionWithTask() {
		// Task input should become the goal description
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "task-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Echo the user's request.")),
			Fields.TASKS, Vectors.of(
				(ACell) Maps.of(
					Fields.JOB_ID, Strings.create("job-123"),
					Fields.INPUT, Strings.create("Process this invoice"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		// test:llm echoes the last user message — which should be the task description
		assertTrue(response.toString().contains("Process this invoice"),
			"Goal should contain task input: " + response);

		// New contract: transition emits {state, response} only. Task
		// completion is signalled by invoking agent:complete-task via the
		// venue op — which is a no-op in this direct call because the test
		// ctx isn't scoped with agentId/taskId.
		assertNull(RT.getIn(output, Fields.TASK_COMPLETE),
			"taskComplete flag must no longer appear on transition output");
	}

	@Test
	public void testTransitionPropagatesVenueOpFailure() {
		// Regression: completeTaskViaVenueOp used to swallow venue op
		// failures, which would orphan the caller's pending task Job
		// (caller blocks on awaitResult forever). Now failures must
		// propagate so the framework's outer catch can fail the Job.
		//
		// Trigger: scope ctx with agentId + taskId pointing at a
		// non-existent agent. The transition produces a result, then
		// invokes agent:complete-task — which fails with "agent not found"
		// because the agent was never created. The failure should bubble
		// out of processGoal as an exception.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		Blob fakeTaskId = Blob.fromHex(
			"00000000000000000000000000000001000000000000000000000000000000aa");
		RequestContext scopedCtx = ALICE
			.withAgentId(Strings.create("ghost-agent"))
			.withTaskId(fakeTaskId);

		ACell input = Maps.of(
			Fields.AGENT_ID, "ghost-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Echo the request.")),
			Fields.TASKS, Vectors.of(
				(ACell) Maps.of(
					Fields.JOB_ID, Strings.create("job-ghost"),
					Fields.INPUT, Strings.create("Process this"))));

		Exception thrown = assertThrows(Exception.class,
			() -> adapter.processGoal(null, scopedCtx, input),
			"Venue op failure must propagate, not be silently swallowed");
		String msg = thrown.getMessage();
		assertNotNull(msg, "Exception must have a message");
		assertTrue(msg.contains("not found") || msg.contains("Agent"),
			"Expected agent-not-found error, got: " + msg);
	}

	@Test
	public void testExplicitComplete() {
		// Anthropic requires every tool_use in an assistant batch to receive a
		// corresponding tool_result immediately afterwards. The fixture emits
		// complete plus a second call: complete wins, the later call is skipped
		// with an error result, and a plain assistant projection closes history.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell input = Maps.of(
			Fields.AGENT_ID, "explicit-complete-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "parallel-complete-test",
				"tools", Vectors.of(
					(ACell) Strings.create("complete"),
					(ACell) Strings.create("v/test/ops/echo"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("return 42"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		assertEquals("42", RT.ensureString(RT.getIn(output, Fields.RESPONSE, "answer")).toString());

		AVector<ACell> frames = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		AVector<ACell> conv = RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
		java.util.Set<String> resultIds = new java.util.HashSet<>();
		long callTurn = -1;
		ACell finalTurn = conv.get(conv.count() - 1);
		for (long i = 0; i < conv.count(); i++) {
			if (RT.getIn(conv.get(i), "toolCalls") != null) callTurn = i;
			if ("tool".equals(String.valueOf(RT.getIn(conv.get(i), "role")))) {
				resultIds.add(String.valueOf(RT.getIn(conv.get(i), "id")));
			}
		}
		assertTrue(callTurn >= 0);
		AVector<ACell> retainedCalls = RT.ensureVector(RT.getIn(conv.get(callTurn), "toolCalls"));
		assertInstanceOf(AMap.class, RT.getIn(retainedCalls.get(0), "arguments"));
		assertInstanceOf(AMap.class, RT.getIn(retainedCalls.get(1), "arguments"),
			"goal-tree state must retain provider-neutral structured arguments");
		assertEquals("call_complete", String.valueOf(RT.getIn(conv.get(callTurn + 1), "id")));
		assertEquals("call_after_complete", String.valueOf(RT.getIn(conv.get(callTurn + 2), "id")));
		assertEquals(CVMBool.TRUE, RT.getIn(conv.get(callTurn + 2), "isError"),
			"skipped tool calls must be explicit Anthropic error results");
		assertEquals(java.util.Set.of("call_complete", "call_after_complete"), resultIds,
			"every tool call in the batch must receive a result");
		assertEquals("assistant", String.valueOf(RT.getIn(finalTurn, "role")));
		assertNull(RT.getIn(finalTurn, "toolCalls"));
		assertTrue(String.valueOf(RT.getIn(finalTurn, "content")).contains("42"));

		ACell followup = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "explicit-complete-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "parallel-complete-test",
				"tools", Vectors.of(
					(ACell) Strings.create("complete"),
					(ACell) Strings.create("v/test/ops/echo"))),
			Fields.SESSION, Maps.of(AgentState.KEY_FRAMES, frames),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("same session followup")))));
		assertEquals("NEXT_TURN_OK", RT.getIn(followup, Fields.RESPONSE).toString(),
			"a settled terminal batch must not poison the next turn on the same frames");
	}

	@Test
	public void testTextualCompleteUsesSharedControlRecognition() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "textual-goal-complete-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "textual-goal-complete-test",
				"tools", Vectors.of((ACell) Strings.create("complete"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("return structured textually")))));

		assertEquals("done-via-text",
			RT.getIn(output, Fields.RESPONSE, "answer").toString());
	}

	@Test
	public void testEmptyCompleteUsesAssistantTurnText() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "empty-goal-complete-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "empty-goal-complete-test",
				"tools", Vectors.of((ACell) Strings.create("complete"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("answer in prose")))));

		assertEquals("answer carried by the assistant turn",
			RT.getIn(output, Fields.RESPONSE).toString());
	}

	@Test
	public void testToolFailuresRecordedOnTheCycle() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "goal-tool-failure-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "goal-tool-failure-test",
				"tools", Vectors.of((ACell) Strings.create("context_load"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("recover from a bad tool call")))));

		assertEquals("recovered from tool failure", RT.getIn(output, Fields.RESPONSE).toString());
		// The failed call is in the cycle record: isError, the message as its result (#392).
		AVector<ACell> inferences = RT.ensureVector(RT.getIn(output, Fields.CYCLE, Fields.INFERENCES));
		assertNotNull(inferences);
		ACell failed = RT.ensureVector(RT.getIn(inferences.get(0), Fields.CALLS)).get(0);
		assertEquals("context_load", RT.getIn(failed, Fields.NAME).toString());
		assertEquals(CVMBool.TRUE, RT.getIn(failed, "isError"));
		assertTrue(RT.getIn(failed, Fields.RESULT).toString().contains("path is required"));
	}

	@Test
	public void testStrictTaskSchemaRejectsThenAccepts() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "strict-goal-schema-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "strict-goal-schema-test"),
			Fields.TASKS, Vectors.of((ACell) Maps.of(
				Fields.JOB_ID, "strict-goal-job",
				Fields.INPUT, "return a typed answer",
				Fields.RESPONSE_SCHEMA, simpleSchema(),
				Fields.STRICT, CVMBool.TRUE))));

		assertEquals("corrected", RT.getIn(output, Fields.RESPONSE, "answer").toString(),
			"the invalid first completion must be rejected and retried against the task schema");
	}

	@Test
	public void testInspectionIncludesLiveLoadsContextMapAndContributedTools() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/goal-inspection-load", "value", "GOAL_LOAD_VISIBLE"),
			ALICE).awaitResult(5000);

		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		AMap<AString, ACell> config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			Fields.LOADS, Maps.of("w/goal-inspection-load", Maps.of(
				"budget", 500L,
				"tools", Vectors.of(Strings.create("v/ops/covia/read")))));

		AMap<AString, ACell> l3 = adapter.buildFirstIterationL3Input(
			config, null, null, ALICE);
		String rendered = convex.core.util.JSON.print(
			RT.getIn(l3, Fields.MESSAGES)).toString();
		assertTrue(rendered.contains("GOAL_LOAD_VISIBLE"), rendered);
		// Loads render with their own headers; there is no separate inventory.
		assertFalse(rendered.contains("[Context Map]"), rendered);
		assertTrue(rendered.contains("w/goal-inspection-load"), rendered);

		AVector<ACell> tools = RT.ensureVector(RT.getIn(l3, Fields.TOOLS));
		assertTrue(hasNamedTool(tools, "covia_read"), tools.toString());
	}

	@Test
	public void testLoadedValueRefreshesAfterToolWriteWithinSameFrame() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/live-load", "value", "LOAD_VALUE_OLD"),
			ALICE).awaitResult(5000);

		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell input = Maps.of(
			Fields.AGENT_ID, "live-load-goaltree",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/skillllm",
				"model", "load-refresh-test",
				"tools", Vectors.of(Strings.create("v/ops/covia/write")),
				Fields.LOADS, Maps.of("w/live-load", Maps.of("budget", 500L))),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of("content", "refresh the loaded value")));

		ACell output = adapter.processGoal(null, ALICE, input);
		assertEquals("LIVE_LOAD_REFRESHED",
			RT.ensureString(RT.getIn(output, Fields.RESPONSE)).toString());
	}

	@Test
	public void testExplicitFail() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "explicit-fail-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "parallel-fail-test",
				"tools", Vectors.of(
					(ACell) Strings.create("fail"),
					(ACell) Strings.create("v/ops/covia/write"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("fail deliberately")))));

		assertEquals("failed deliberately", RT.getIn(output, Fields.ERROR, "error").toString());
		ACell read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of("path", "w/parallel-fail-should-not-write"), ALICE).awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(read, "exists"),
			"a mutating sibling after fail must not execute");

		AVector<ACell> frames = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		AVector<ACell> conv = RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
		long callTurn = -1;
		for (long i = 0; i < conv.count(); i++) {
			if (RT.getIn(conv.get(i), "toolCalls") != null) callTurn = i;
		}
		assertTrue(callTurn >= 0);
		assertEquals("call_fail", RT.getIn(conv.get(callTurn + 1), "id").toString());
		assertEquals("call_after_fail", RT.getIn(conv.get(callTurn + 2), "id").toString());
		assertEquals(CVMBool.TRUE, RT.getIn(conv.get(callTurn + 2), "isError"));
	}

	@Test
	public void testMalformedTerminalCallDoesNotSuppressLaterSibling() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "malformed-terminal-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "malformed-complete-test",
				"tools", Vectors.of(
					(ACell) Strings.create("complete"),
					(ACell) Strings.create("v/test/ops/echo"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("recover from malformed complete")))));

		assertEquals("BATCH_RECOVERED", RT.getIn(output, Fields.RESPONSE).toString());
		AVector<ACell> frames = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		AVector<ACell> conv = RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
		long callTurn = -1;
		for (long i = 0; i < conv.count(); i++) {
			if (RT.getIn(conv.get(i), "toolCalls") != null) callTurn = i;
		}
		assertTrue(callTurn >= 0);
		assertEquals(CVMBool.TRUE, RT.getIn(conv.get(callTurn + 1), "isError"));
		assertNull(RT.getIn(conv.get(callTurn + 2), "isError"),
			"the sibling after a failed terminal-looking call must execute normally");
	}

	@Test
	public void testParallelNonTerminalCallsEachReceiveAResult() {
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell output = adapter.processGoal(null, ALICE, Maps.of(
			Fields.AGENT_ID, "parallel-nonterminal-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "parallel-nonterminal-test",
				"tools", Vectors.of((ACell) Strings.create("v/test/ops/echo"))),
			Fields.MESSAGES, Vectors.of((ACell) Maps.of(
				Fields.MESSAGE, Strings.create("run both calls")))));

		assertEquals("BATCH_RECOVERED", RT.getIn(output, Fields.RESPONSE).toString());
		AVector<ACell> frames = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		AVector<ACell> conv = RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (long i = 0; i < conv.count(); i++) {
			if ("tool".equals(String.valueOf(RT.getIn(conv.get(i), "role")))) {
				ids.add(String.valueOf(RT.getIn(conv.get(i), "id")));
			}
		}
		assertEquals(java.util.Set.of("call_echo_one", "call_echo_two"), ids);
	}

	// ========== Subgoal test (using test:toolllm) ==========

	@Test
	public void testCompactDeferredAndVerified() {
		// test:compactllm calls test:echo + compact in one batch, then on next
		// iteration sees the compacted segment and returns text.
		// This verifies: (1) deferred compaction doesn't orphan tool results,
		// (2) compacted segment renders as a system message, (3) goal is
		// re-injected after compaction.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "compact-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/compactllm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Test compact"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Should have a response after compact loop");
		assertTrue(response.toString().contains("Compact verified"),
			"Response should confirm segment was found: " + response);
	}

	// ========== Cancellation ==========

	private static final Blob TEST_JOB_ID = Blob.fromHex("0000000000000000");

	@Test
	public void testCancelledJobExitsImmediately() {
		// A job that is already cancelled should cause the frame loop to exit
		// on the very first iteration without making any L3 calls
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		Job job = new Job(Maps.of(Fields.STATUS, Status.PENDING, Fields.ID, TEST_JOB_ID));
		job.cancel(); // cancel before running

		ACell input = Maps.of(
			Fields.AGENT_ID, "cancel-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Hello"))));

		ACell output = adapter.processGoal(job, ALICE, input);
		// Should still return output (failed result), not throw
		assertNotNull(output);
		ACell err = RT.getIn(output, Fields.ERROR);
		assertNotNull(err, "Should report error even when cancelled");
		assertTrue(err.toString().contains("cancelled"),
			"Error should indicate cancellation: " + err);
	}

	@Test
	public void testInvokeWithCancelledJob() {
		// Test the full invoke path — cancel the job, verify it doesn't complete normally
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		Job job = engine.jobs().invokeOperation("v/ops/goaltree/chat",
			Maps.of(Fields.AGENT_ID, "invoke-cancel",
				AgentState.KEY_CONFIG, Maps.of(
					Strings.create("llmOperation"), Strings.create("v/test/ops/never"),
					Strings.create("systemPrompt"), Strings.create("Test"))),
			ALICE);

		// Cancel immediately
		job.cancel();

		TestEngine.awaitCondition(job::isFinished, 2000,
			() -> "cancelled GoalTree job did not finish (status=" + job.getStatus() + ")");
		assertTrue(job.isFinished(), "Job should be finished after cancel");
		assertEquals("CANCELLED", job.getStatus().toString());
	}

	@Test
	public void testGoalIsFirstUserMessage() {
		// Verify the goal is injected as the first conversation turn, not repeated
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "goal-msg-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Echo the message.")),
			Fields.TASKS, Vectors.of(
				(ACell) Maps.of(
					Fields.JOB_ID, Strings.create("job-goal"),
					Fields.INPUT, Strings.create("Tell me about penguins"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		// test:llm echoes the last user message
		assertTrue(response.toString().contains("penguin"),
			"Should echo the goal text: " + response);
	}

	// (testResponseFormatSchemaAcceptsValidJson removed: with the typed-outputs
	// migration shim, responseFormat agents now go through the typed-tool path
	// and reject text-only responses. The legacy text-content path it tested no
	// longer applies. See testTypedOutputsRejectsTextOnlyResponse below.)

	// ========== Typed outputs ==========

	private static AMap<AString, ACell> simpleSchema() {
		return Maps.of(
			Strings.create("type"), Strings.create("object"),
			Strings.create("properties"), Maps.of(
				Strings.create("answer"), Maps.of(
					Strings.create("type"), Strings.create("string"))),
			Strings.create("required"), Vectors.of((ACell) Strings.create("answer")),
			Strings.create("additionalProperties"), convex.core.data.prim.CVMBool.FALSE);
	}

	@Test
	public void testResolveOutputsExplicit() {
		// Explicit outputs declaration takes precedence over any responseFormat.
		AMap<AString, ACell> schema = simpleSchema();
		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), schema));
		AMap<AString, ACell> config = Maps.of(Strings.create("outputs"), outputs);
		AMap<AString, ACell> resolved = GoalTreeAdapter.resolveOutputs(config);
		assertNotNull(resolved);
		assertSame(outputs, resolved);
	}

	@Test
	public void testResolveOutputsMigratedFromResponseFormat() {
		// Migration shim: responseFormat with a schema becomes outputs.complete.schema.
		AMap<AString, ACell> schema = simpleSchema();
		AMap<AString, ACell> rf = Maps.of(
			Strings.create("name"), Strings.create("Answer"),
			Strings.create("schema"), schema);
		AMap<AString, ACell> config = Maps.of(Strings.create("responseFormat"), rf);
		AMap<AString, ACell> resolved = GoalTreeAdapter.resolveOutputs(config);
		assertNotNull(resolved, "responseFormat with schema should migrate to outputs");
		AMap<AString, ACell> completeSchema = GoalTreeAdapter.outputsCompleteSchema(resolved);
		assertEquals(schema, completeSchema, "migrated schema should match");
	}

	@Test
	public void testResolveOutputsAbsent() {
		// No outputs and no responseFormat → null (legacy untyped path).
		assertNull(GoalTreeAdapter.resolveOutputs(null));
		assertNull(GoalTreeAdapter.resolveOutputs(Maps.empty()));
		// responseFormat as a plain string (not a schema map) → null
		AMap<AString, ACell> jsonOnlyConfig = Maps.of(
			Strings.create("responseFormat"), Strings.create("json"));
		assertNull(GoalTreeAdapter.resolveOutputs(jsonOnlyConfig));
	}

	@Test
	public void testTypedCompleteToolFlattensSchema() {
		// The user's schema IS the parameters — no result wrapper
		AMap<AString, ACell> schema = simpleSchema();
		AMap<AString, ACell> tool = GoalTreeAdapter.typedCompleteTool(schema);
		assertEquals("complete", RT.ensureString(RT.getIn(tool, "name")).toString());
		ACell params = tool.get(Strings.create("parameters"));
		// Parameters are the user's schema directly
		assertEquals(schema, params);
		assertEquals(Strings.create("object"), RT.getIn(params, "type"));
		assertEquals(convex.core.data.prim.CVMBool.FALSE,
			RT.getIn(params, "additionalProperties"));
	}

	@Test
	public void testTypedFailToolUsesDefaultSchema() {
		// Without an explicit fail schema, outputsFailSchema returns the default
		// (reason + details, both required, additionalProperties false).
		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), simpleSchema()));
		AMap<AString, ACell> failSchema = GoalTreeAdapter.outputsFailSchema(outputs);
		assertNotNull(failSchema);
		assertEquals(GoalTreeAdapter.DEFAULT_FAIL_SCHEMA, failSchema);
		AMap<AString, ACell> tool = GoalTreeAdapter.typedFailTool(failSchema);
		assertEquals("fail", RT.ensureString(RT.getIn(tool, "name")).toString());
		// Parameters are the fail schema directly — no error wrapper
		ACell params = tool.get(Strings.create("parameters"));
		assertEquals(failSchema, params);
	}

	@Test
	public void testTypedFailToolHonoursOverride() {
		// Custom fail schema overrides the default
		AMap<AString, ACell> customFailSchema = Maps.of(
			Strings.create("type"), Strings.create("object"),
			Strings.create("properties"), Maps.of(
				Strings.create("code"), Maps.of(Strings.create("type"), Strings.create("string"))),
			Strings.create("required"), Vectors.of((ACell) Strings.create("code")),
			Strings.create("additionalProperties"), convex.core.data.prim.CVMBool.FALSE);
		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), simpleSchema()),
			Strings.create("fail"), Maps.of(Strings.create("schema"), customFailSchema));
		AMap<AString, ACell> failSchema = GoalTreeAdapter.outputsFailSchema(outputs);
		assertEquals(customFailSchema, failSchema);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testBuildTypedRootHarnessTools() {
		// Config with harness tools so they get included alongside typed complete/fail
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("tools"), Vectors.of(
				(ACell) Strings.create("subgoal"),
				(ACell) Strings.create("compact"),
				(ACell) Strings.create("context_load"),
				(ACell) Strings.create("context_unload")));
		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), simpleSchema()));
		AVector<ACell> tools = GoalTreeAdapter.buildTypedRootHarnessTools(outputs, config);
		assertNotNull(tools);
		// 2 (typed complete + fail) + 4 optional = 6
		assertEquals(6, tools.count());
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < tools.count(); i++) {
			names.add(RT.ensureString(RT.getIn(tools.get(i), "name")).toString());
		}
		assertTrue(names.contains("subgoal"));
		assertTrue(names.contains("complete"));
		assertTrue(names.contains("fail"));
		assertTrue(names.contains("compact"));
		assertTrue(names.contains("context_load"));
		assertTrue(names.contains("context_unload"));
		// The complete tool's parameters ARE the user's schema (flattened)
		ACell completeTool = null;
		for (long i = 0; i < tools.count(); i++) {
			ACell tool = tools.get(i);
			if ("complete".equals(RT.ensureString(RT.getIn(tool, "name")).toString())) {
				completeTool = tool;
				break;
			}
		}
		assertNotNull(completeTool);
		ACell params = RT.getIn(completeTool, "parameters");
		assertEquals(simpleSchema(), params);
	}

	@Test
	public void testBuildTypedRootHarnessToolsMinimal() {
		// No harness tools in config — only typed complete/fail auto-injected
		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), simpleSchema()));
		AVector<ACell> tools = GoalTreeAdapter.buildTypedRootHarnessTools(outputs, Maps.empty());
		assertNotNull(tools);
		assertEquals(2, tools.count()); // just complete + fail
		assertEquals("complete", RT.ensureString(RT.getIn(tools.get(0), "name")).toString());
		assertEquals("fail", RT.ensureString(RT.getIn(tools.get(1), "name")).toString());
	}

	@Test
	public void testBuildTypedRootHarnessToolsReturnsNullWithoutOutputs() {
		// No outputs → null → caller falls back to resolveHarnessTools
		assertNull(GoalTreeAdapter.buildTypedRootHarnessTools(null, null));
		assertNull(GoalTreeAdapter.buildTypedRootHarnessTools(Maps.empty(), null));
	}

	@Test
	public void testTypedOutputsRejectsTextOnlyResponse() {
		// With outputs declared and a mock LLM that only emits text (test:llm
		// echoes the user message), the harness should reject the text and
		// nudge the LLM repeatedly until MAX_ITERATIONS — never accepting the
		// text-only response as a valid completion.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), simpleSchema()));

		ACell input = Maps.of(
			Fields.AGENT_ID, "typed-text-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("outputs"), outputs),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"),
					Strings.create("anything"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		// Loop hits MAX_ITERATIONS — failure path emits `error`, not `response`.
		AString err = RT.ensureString(RT.getIn(output, Fields.ERROR));
		assertNotNull(err, "Failed transition should report error");
		assertFalse(err.toString().equals("anything"),
			"Text-only response must not be accepted under typed outputs: " + err);
	}

	@Test
	public void testFailedTransitionEmitsFramesForPostMortem() {
		// When a frame fails (here: by hitting MAX_ITERATIONS via the JSON
		// validation nudge loop), the full frame stack rides back to the
		// framework as Fields.FRAMES so mergeRunResult persists it on the
		// session record. The lattice copy IS the post-mortem — there is
		// no separate state.lastFailure snapshot.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		AMap<AString, ACell> schema = Maps.of(
			Strings.create("type"), Strings.create("object"),
			Strings.create("properties"), Maps.of(
				Strings.create("answer"), Maps.of(Strings.create("type"), Strings.create("string"))),
			Strings.create("required"), Vectors.of((ACell) Strings.create("answer")),
			Strings.create("additionalProperties"), convex.core.data.prim.CVMBool.FALSE);

		AMap<AString, ACell> responseFormat = Maps.of(
			Strings.create("name"), Strings.create("Answer"),
			Strings.create("schema"), schema);

		ACell input = Maps.of(
			Fields.AGENT_ID, "fail-debug-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("responseFormat"), responseFormat),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"),
					Strings.create("This is plain text, not JSON."))));

		ACell output = adapter.processGoal(null, ALICE, input);
		// Error path: transition must report error, not response.
		assertNotNull(RT.getIn(output, Fields.ERROR),
			"Failed transition must report error");
		// Fields.FRAMES must carry the final stack for session.frames
		// replacement in mergeRunResult.
		ACell framesOut = RT.getIn(output, Fields.FRAMES);
		assertNotNull(framesOut, "Failed transition must emit frames for post-mortem");
		assertTrue(framesOut instanceof AVector,
			"frames output must be a vector");
		assertTrue(((AVector<?>) framesOut).count() > 0,
			"frames output must contain at least the root frame");
		// state.lastFailure is retired — lattice frames are the sole record.
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNull(RT.getIn(newState, Strings.create("lastFailure")),
			"state.lastFailure is retired — frames on the session record are the post-mortem");
	}

	@Test
	public void testSuccessfulTransitionEmitsFrames() {
		// Successful runs also emit frames so session.frames[0].conversation
		// grows atomically with the timeline write. state.lastFailure is
		// never written.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "happy-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("hello"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		assertNotNull(RT.getIn(output, Fields.RESPONSE),
			"Successful transition must report response");
		ACell framesOut = RT.getIn(output, Fields.FRAMES);
		assertNotNull(framesOut, "Successful transition must emit frames");
		assertTrue(framesOut instanceof AVector);
		assertTrue(((AVector<?>) framesOut).count() > 0);
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNull(RT.getIn(newState, Strings.create("lastFailure")),
			"state.lastFailure is retired");
	}

	@Test
	public void testFramesPersistAcrossTransitions() {
		// Regression for the step-5 cutover: each transition must read the
		// persisted frame stack from session.frames, append this cycle's
		// turns, and emit the extended stack as Fields.FRAMES. Across three
		// turns on the same "session" (simulated by feeding the previous
		// output's frames back in), frames[0].conversation must grow
		// monotonically with no duplicates and no reset.
		//
		// Also guards the efficiency angle called out in
		// GetMine-ai/demo#16: each turn must contribute a bounded number
		// of envelopes (one per user message), not re-append the entire
		// prior history — otherwise conversation length grows quadratically
		// and LLM context overflows within a handful of turns.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		AMap<AString, ACell> config = Maps.of(
			Strings.create("llmOperation"), Strings.create("v/test/ops/llm"));

		// --- Turn 1: no session, adapter mints a fresh root frame.
		ACell input1 = Maps.of(
			Fields.AGENT_ID, "persist-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, config,
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Fields.MESSAGE, Strings.create("turn one"))));

		ACell out1 = adapter.processGoal(null, ALICE, input1);
		assertNotNull(RT.getIn(out1, Fields.RESPONSE), "turn 1 should succeed");
		@SuppressWarnings("unchecked")
		AVector<ACell> frames1 = (AVector<ACell>) RT.getIn(out1, Fields.FRAMES);
		assertNotNull(frames1, "turn 1 must emit frames");
		assertEquals(1, frames1.count(), "root frame only — no subgoal recursion expected");

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> root1 = (AMap<AString, ACell>) frames1.get(0);
		AString rootDesc = RT.ensureString(root1.get(Strings.intern("description")));
		@SuppressWarnings("unchecked")
		AVector<ACell> conv1 = (AVector<ACell>) root1.get(Strings.intern("conversation"));
		assertNotNull(conv1);
		long conv1Count = conv1.count();
		assertTrue(conv1Count >= 1,
			"turn 1 must record at least the user message envelope; got " + conv1Count);

		// --- Turn 2: feed turn 1's frames back as session.frames. Adapter
		// must read those and extend — not reset or duplicate.
		AMap<AString, ACell> session2 = Maps.of(AgentState.KEY_FRAMES, frames1);
		ACell input2 = Maps.of(
			Fields.AGENT_ID, "persist-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, config,
			Fields.SESSION, session2,
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Fields.MESSAGE, Strings.create("turn two"))));

		ACell out2 = adapter.processGoal(null, ALICE, input2);
		assertNotNull(RT.getIn(out2, Fields.RESPONSE), "turn 2 should succeed");
		@SuppressWarnings("unchecked")
		AVector<ACell> frames2 = (AVector<ACell>) RT.getIn(out2, Fields.FRAMES);
		assertNotNull(frames2, "turn 2 must emit frames");
		assertEquals(1, frames2.count(), "root frame count must be stable across transitions");

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> root2 = (AMap<AString, ACell>) frames2.get(0);
		assertEquals(rootDesc, RT.ensureString(root2.get(Strings.intern("description"))),
			"root frame description must be preserved across transitions");
		@SuppressWarnings("unchecked")
		AVector<ACell> conv2 = (AVector<ACell>) root2.get(Strings.intern("conversation"));
		long conv2Count = conv2.count();
		assertTrue(conv2Count > conv1Count,
			"conversation must grow after turn 2: " + conv1Count + " -> " + conv2Count);

		// Efficiency bound (issue #16): per-turn delta must be small and
		// independent of prior history length. If the adapter were
		// re-appending the whole transcript each cycle we'd see delta
		// equal to (or exceeding) conv1Count here.
		long delta2 = conv2Count - conv1Count;
		assertTrue(delta2 <= conv1Count + 2,
			"per-turn conversation growth must be bounded (not quadratic); "
				+ "turn 1 added " + conv1Count + ", turn 2 delta " + delta2);

		// Both user messages must appear exactly once — no duplicates,
		// no loss of turn 1 content.
		assertEquals(1, countTurnsMatching(conv2, "turn one"),
			"'turn one' user message must appear exactly once after turn 2");
		assertEquals(1, countTurnsMatching(conv2, "turn two"),
			"'turn two' user message must appear exactly once after turn 2");

		// --- Turn 3: one more round-trip to confirm monotonic growth.
		AMap<AString, ACell> session3 = Maps.of(AgentState.KEY_FRAMES, frames2);
		ACell input3 = Maps.of(
			Fields.AGENT_ID, "persist-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, config,
			Fields.SESSION, session3,
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Fields.MESSAGE, Strings.create("turn three"))));

		ACell out3 = adapter.processGoal(null, ALICE, input3);
		@SuppressWarnings("unchecked")
		AVector<ACell> frames3 = (AVector<ACell>) RT.getIn(out3, Fields.FRAMES);
		assertNotNull(frames3);
		assertEquals(1, frames3.count());
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> root3 = (AMap<AString, ACell>) frames3.get(0);
		@SuppressWarnings("unchecked")
		AVector<ACell> conv3 = (AVector<ACell>) root3.get(Strings.intern("conversation"));
		assertTrue(conv3.count() > conv2Count,
			"conversation must grow after turn 3: " + conv2Count + " -> " + conv3.count());
		assertEquals(1, countTurnsMatching(conv3, "turn one"));
		assertEquals(1, countTurnsMatching(conv3, "turn two"));
		assertEquals(1, countTurnsMatching(conv3, "turn three"));
	}

	/**
	 * Counts user-role turns in a conversation vector whose content
	 * stringifies to contain {@code needle}. Filters by role to avoid
	 * matching assistant turns that echo prior content (as test:llm does).
	 */
	private static int countTurnsMatching(AVector<ACell> conversation, String needle) {
		int n = 0;
		for (long i = 0; i < conversation.count(); i++) {
			ACell turn = conversation.get(i);
			AString role = RT.ensureString(RT.getIn(turn, Strings.intern("role")));
			if (role == null || !"user".equals(role.toString())) continue;
			ACell content = RT.getIn(turn, Strings.intern("content"));
			if (content == null) continue;
			if (content.toString().contains(needle)) n++;
		}
		return n;
	}

	@Test
	public void testStateCarriesNoConfigAfterTransition() {
		// Config's single home is record.config (#144): the runtime reads it
		// from the transition input and never writes it into state. Caps and
		// schema enforcement survive because record.config persists on the
		// record, not because the adapter carries a copy.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "stateful-agent",
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Be brief.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("hi"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNotNull(newState);
		assertNull(RT.getIn(newState, AbstractLLMAdapter.K_CONFIG),
			"state must not carry config");
	}

	@Test
	public void testResponseFormatSchemaRejectsPlainText() {
		// When responseFormat declares a schema and the LLM emits plain text,
		// the harness nudges the LLM. test:llm just echoes, so the loop iterates
		// until MAX_ITERATIONS and returns failure (no valid JSON ever produced).
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		AMap<AString, ACell> schema = Maps.of(
			Strings.create("type"), Strings.create("object"),
			Strings.create("properties"), Maps.of(
				Strings.create("answer"), Maps.of(Strings.create("type"), Strings.create("string"))),
			Strings.create("required"), Vectors.of((ACell) Strings.create("answer")),
			Strings.create("additionalProperties"), convex.core.data.prim.CVMBool.FALSE);

		AMap<AString, ACell> responseFormat = Maps.of(
			Strings.create("name"), Strings.create("Answer"),
			Strings.create("schema"), schema);

		ACell input = Maps.of(
			Fields.AGENT_ID, "text-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("responseFormat"), responseFormat),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"),
					Strings.create("This is plain text, not JSON."))));

		ACell output = adapter.processGoal(null, ALICE, input);
		// Loop hits MAX_ITERATIONS — failure path emits `error`, not `response`.
		AString err = RT.ensureString(RT.getIn(output, Fields.ERROR));
		assertNotNull(err, "Plain text should be rejected, producing failure");
		assertFalse(err.toString().equals("This is plain text, not JSON."),
			"Plain text response should not be accepted as complete: " + err);
	}
}
