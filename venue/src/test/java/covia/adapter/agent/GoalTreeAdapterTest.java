package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Tests for GoalTreeAdapter — the goal tree agent transition function.
 */
public class GoalTreeAdapterTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final RequestContext ALICE = RequestContext.of(ALICE_DID);

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
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
		Job job = engine.jobs().invokeOperation("goaltree:chat",
			Maps.of(Fields.AGENT_ID, "test-agent",
				AgentState.KEY_CONFIG, Maps.of(
					Strings.create("llmOperation"), Strings.create("test:llm"))),
			ALICE);
		// Should start without error (may fail on LLM call, but shouldn't NPE)
		assertNotNull(job);
		// Wait briefly — the job runs async
		try { Thread.sleep(500); } catch (InterruptedException e) {}
		// Job should exist (started or completed)
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
				Strings.create("llmOperation"), Strings.create("test:llm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Hello"))));

		ACell output = adapter.processGoal(ALICE, input);
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
				Strings.create("llmOperation"), Strings.create("test:toolllm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Do something"))));

		ACell output = adapter.processGoal(ALICE, input);
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
				Strings.create("llmOperation"), Strings.create("test:llm"),
				Strings.create("systemPrompt"), Strings.create("Echo the user's request.")),
			Fields.TASKS, Vectors.of(
				(ACell) Maps.of(
					Fields.JOB_ID, Strings.create("job-123"),
					Fields.INPUT, Strings.create("Process this invoice"))));

		ACell output = adapter.processGoal(ALICE, input);
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
			Strings.create("Something went wrong"));
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
				Strings.create("llmOperation"), Strings.create("test:compactllm"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("content"), Strings.create("Test compact"))));

		ACell output = adapter.processGoal(ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response, "Should have a response after compact loop");
		assertTrue(response.toString().contains("Compact verified"),
			"Response should confirm segment was found: " + response);
	}

	@Test
	public void testGoalIsFirstUserMessage() {
		// Verify the goal is injected as the first conversation turn, not repeated
		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");

		ACell input = Maps.of(
			Fields.AGENT_ID, "goal-msg-agent",
			AgentState.KEY_STATE, null,
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("test:llm"),
				Strings.create("systemPrompt"), Strings.create("Echo the message.")),
			Fields.TASKS, Vectors.of(
				(ACell) Maps.of(
					Fields.JOB_ID, Strings.create("job-goal"),
					Fields.INPUT, Strings.create("Tell me about penguins"))));

		ACell output = adapter.processGoal(ALICE, input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		// test:llm echoes the last user message
		assertTrue(response.toString().contains("penguin"),
			"Should echo the goal text: " + response);
	}
}
