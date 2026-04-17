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
import convex.core.lang.RT;
import covia.adapter.AAdapter;
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

	// ========== Tool definitions ==========

	@Test
	public void testHarnessToolRegistry() {
		// All 7 harness tools are in the registry
		assertEquals(7, GoalTreeAdapter.HARNESS_TOOL_REGISTRY.size());
		assertTrue(GoalTreeAdapter.isHarnessTool("subgoal"));
		assertTrue(GoalTreeAdapter.isHarnessTool("complete"));
		assertTrue(GoalTreeAdapter.isHarnessTool("fail"));
		assertTrue(GoalTreeAdapter.isHarnessTool("compact"));
		assertTrue(GoalTreeAdapter.isHarnessTool("context_load"));
		assertTrue(GoalTreeAdapter.isHarnessTool("context_unload"));
		assertTrue(GoalTreeAdapter.isHarnessTool("more_tools"));
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
		// Use test:toolllm which calls test:echo — but we want to test complete()
		// Instead, create a mock input where the LLM would call complete
		// For now, test that FrameResult.complete works correctly
		GoalTreeAdapter.FrameResult result = GoalTreeAdapter.FrameResult.complete(
			Maps.of(Strings.create("answer"), Strings.create("42")));
		assertEquals("complete", result.status());
		assertNotNull(result.value());
	}

	@Test
	public void testExplicitFail() {
		GoalTreeAdapter.FrameResult result = GoalTreeAdapter.FrameResult.failed(
			Strings.create("Something went wrong"), Vectors.empty());
		assertEquals("failed", result.status());
		assertEquals("Something went wrong", RT.ensureString(result.value()).toString());
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
	public void testCancelledJobDuringToolLoop() {
		// Use test:toolllm which makes a tool call then returns text.
		// Cancel the job after it starts — the second iteration should detect cancellation.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		Job job = new Job(Maps.of(Fields.STATUS, Status.STARTED, Fields.ID, TEST_JOB_ID));

		ACell input = Maps.of(
			Fields.AGENT_ID, "cancel-loop-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/toolllm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Do something"))));

		// Run in a thread so we can cancel mid-flight
		var future = java.util.concurrent.CompletableFuture.supplyAsync(
			() -> adapter.processGoal(job, ALICE, input));

		// Brief pause to let first iteration start, then cancel
		try { Thread.sleep(100); } catch (InterruptedException e) {}
		job.cancel();

		ACell output = future.join();
		assertNotNull(output);
		// Either completed (response) or cancelled (error) — exactly one is set
		ACell response = RT.getIn(output, Fields.RESPONSE);
		ACell err = RT.getIn(output, Fields.ERROR);
		assertTrue(response != null || err != null,
			"Should have either a response or an error");
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

		// Poll for finish — bounded, deterministic, no fixed sleep waste
		long deadline = System.currentTimeMillis() + 2000;
		while (!job.isFinished() && System.currentTimeMillis() < deadline) {
			Thread.yield();
		}

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
	public void testFailedFrameConversationPersistedToState() {
		// When a frame fails (here: by hitting MAX_ITERATIONS via the JSON
		// validation nudge loop), the deepest frame's conversation must be
		// persisted under state.lastFailure for post-mortem debugging.
		// Without this, the only post-failure visibility into agent loops
		// is the live process log — which evaporates on restart.
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
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNotNull(newState);
		ACell lastFailure = RT.getIn(newState, Strings.create("lastFailure"));
		assertNotNull(lastFailure, "Failed transition must persist lastFailure debug info");
		assertNotNull(RT.getIn(lastFailure, Strings.create("error")),
			"lastFailure must include the error value");
		ACell conversation = RT.getIn(lastFailure, Strings.create("conversation"));
		assertNotNull(conversation, "lastFailure must include the conversation");
		assertTrue(conversation instanceof AVector,
			"conversation should be a vector of messages");
		assertTrue(((AVector<?>) conversation).count() > 0,
			"conversation should not be empty after a failed run");
	}

	@Test
	public void testSuccessfulFrameLeavesNoLastFailure() {
		// Successful runs should not leave a lastFailure trace — that
		// would mislead debugging by surfacing stale data.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "happy-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("hello"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNotNull(newState);
		ACell lastFailure = RT.getIn(newState, Strings.create("lastFailure"));
		assertNull(lastFailure, "Successful runs should not leave a lastFailure trace");
	}

	@Test
	public void testStateConfigPreservedAcrossTransitions() {
		// state.config (where caps, responseFormat, prompt etc. are stored at agent
		// create time) must survive a transition. Wiping it would silently strip
		// schema enforcement on every invocation after the first.
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		AMap<AString, ACell> stateConfig = Maps.of(
			Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
			Strings.create("systemPrompt"), Strings.create("Be brief."));

		ACell input = Maps.of(
			Fields.AGENT_ID, "stateful-agent",
			AgentState.KEY_STATE, Maps.of(AbstractLLMAdapter.K_CONFIG, stateConfig),
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("hi"))));

		ACell output = adapter.processGoal(null, ALICE, input);
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNotNull(newState);
		ACell preservedConfig = RT.getIn(newState, AbstractLLMAdapter.K_CONFIG);
		assertNotNull(preservedConfig, "state.config must survive the transition");
		assertEquals(stateConfig, preservedConfig,
			"state.config must be preserved verbatim");
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
