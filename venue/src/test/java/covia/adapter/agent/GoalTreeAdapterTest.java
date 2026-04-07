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
		// Verify the 4 harness tools are defined correctly
		assertEquals(4, GoalTreeAdapter.HARNESS_TOOLS.count());

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
}
