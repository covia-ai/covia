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
	public void testHarnessToolDefinitions() {
		// Verify the 6 harness tools are defined correctly
		assertEquals(6, GoalTreeAdapter.HARNESS_TOOLS.count());

		// Check each tool has name, description, parameters
		for (long i = 0; i < GoalTreeAdapter.HARNESS_TOOLS.count(); i++) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> tool = (AMap<AString, ACell>) GoalTreeAdapter.HARNESS_TOOLS.get(i);
			assertNotNull(tool.get(Strings.intern("name")), "Tool " + i + " should have name");
			assertNotNull(tool.get(Strings.intern("description")), "Tool " + i + " should have description");
			assertNotNull(tool.get(Strings.intern("parameters")), "Tool " + i + " should have parameters");
		}
	}

	@Test
	public void testHarnessToolNames() {
		assertEquals("subgoal", RT.ensureString(RT.getIn(GoalTreeAdapter.HARNESS_TOOLS.get(0), "name")).toString());
		assertEquals("complete", RT.ensureString(RT.getIn(GoalTreeAdapter.HARNESS_TOOLS.get(1), "name")).toString());
		assertEquals("fail", RT.ensureString(RT.getIn(GoalTreeAdapter.HARNESS_TOOLS.get(2), "name")).toString());
		assertEquals("compact", RT.ensureString(RT.getIn(GoalTreeAdapter.HARNESS_TOOLS.get(3), "name")).toString());
		assertEquals("context_load", RT.ensureString(RT.getIn(GoalTreeAdapter.HARNESS_TOOLS.get(4), "name")).toString());
		assertEquals("context_unload", RT.ensureString(RT.getIn(GoalTreeAdapter.HARNESS_TOOLS.get(5), "name")).toString());
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response);
		// test:llm echoes the last user message — which should be the task description
		assertTrue(response.toString().contains("Process this invoice"),
			"Goal should contain task input: " + response);

		// Task should be auto-completed
		ACell taskResults = RT.getIn(output, Fields.TASK_RESULTS);
		assertNotNull(taskResults, "Should have taskResults");
		assertNotNull(RT.getIn(taskResults, Strings.create("job-123")),
			"Task job-123 should be completed");
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
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
		ACell response = RT.getIn(output, Fields.RESULT, "response");
		assertNotNull(response, "Should have a response even when cancelled");
		assertTrue(response.toString().contains("cancelled"),
			"Response should indicate cancellation: " + response);
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
		// Either completed (if first iteration finished before cancel) or cancelled
		ACell response = RT.getIn(output, Fields.RESULT, "response");
		assertNotNull(response, "Should have a response");
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
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
	public void testTypedCompleteToolWrapsSchema() {
		AMap<AString, ACell> schema = simpleSchema();
		AMap<AString, ACell> tool = GoalTreeAdapter.typedCompleteTool(schema);
		assertEquals("complete", RT.ensureString(RT.getIn(tool, "name")).toString());
		// parameters: object with single 'result' property, additionalProperties: false
		ACell params = tool.get(Strings.create("parameters"));
		assertEquals(Strings.create("object"), RT.getIn(params, "type"));
		assertEquals(convex.core.data.prim.CVMBool.FALSE,
			RT.getIn(params, "additionalProperties"));
		ACell required = RT.getIn(params, "required");
		assertEquals(Vectors.of(Strings.create("result")), required);
		// The user's schema is preserved verbatim under properties.result
		ACell resultSchema = RT.getIn(params, "properties", "result");
		assertEquals(schema, resultSchema);
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
		ACell errorSchema = RT.getIn(tool, "parameters", "properties", "error");
		assertEquals(failSchema, errorSchema);
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
	public void testBuildTypedRootHarnessTools() {
		// When outputs is set, the typed root harness tool list contains
		// subgoal + typed complete + typed fail + compact + context_load + context_unload
		AMap<AString, ACell> outputs = Maps.of(
			Strings.create("complete"), Maps.of(Strings.create("schema"), simpleSchema()));
		AVector<ACell> tools = GoalTreeAdapter.buildTypedRootHarnessTools(outputs);
		assertNotNull(tools);
		assertEquals(6, tools.count());
		// Names: subgoal, complete, fail, compact, context_load, context_unload
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
		// The complete tool's parameters carry the user's schema
		ACell completeTool = null;
		for (long i = 0; i < tools.count(); i++) {
			ACell tool = tools.get(i);
			if ("complete".equals(RT.ensureString(RT.getIn(tool, "name")).toString())) {
				completeTool = tool;
				break;
			}
		}
		assertNotNull(completeTool);
		ACell resultSchema = RT.getIn(completeTool, "parameters", "properties", "result");
		assertEquals(simpleSchema(), resultSchema);
	}

	@Test
	public void testBuildTypedRootHarnessToolsReturnsNullWithoutOutputs() {
		// No outputs → null → caller falls back to static HARNESS_TOOLS
		assertNull(GoalTreeAdapter.buildTypedRootHarnessTools(null));
		assertNull(GoalTreeAdapter.buildTypedRootHarnessTools(Maps.empty()));
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response);
		// test:llm only echoes — it can't make tool calls — so the loop will
		// always reject the text and eventually hit MAX_ITERATIONS.
		assertFalse(response.toString().equals("anything"),
			"Text-only response must not be accepted under typed outputs: " + response);
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
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		// Should NOT be the original plain text — harness should have rejected it.
		// With test:llm echoing, the loop hits max iterations and returns
		// "Max iterations reached" as the failure value.
		assertNotNull(response);
		assertFalse(response.toString().equals("This is plain text, not JSON."),
			"Plain text response should not be accepted as complete: " + response);
	}
}
