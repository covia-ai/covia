package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;

/**
 * Tests for the LLMAgentAdapter transition function.
 *
 * <p>Uses {@code test:llm} as the level 3 operation, which echoes the last
 * user message as the response — no real LLM needed.</p>
 */
public class LLMAgentAdapterTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	/** State with test LLM config — points at test:llm for level 3 */
	private static final ACell TEST_STATE = Maps.of(
		"config", Maps.of("llmOperation", Strings.create("test:llm"))
	);

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	// ========== History reconstruction ==========

	@Test
	public void testExtractHistoryFromNull() {
		AVector<ACell> history = LLMAgentAdapter.extractHistory(null);
		assertNotNull(history);
		assertEquals(0, history.count());
	}

	@Test
	public void testExtractHistoryFromState() {
		AVector<ACell> entries = Vectors.of(
			Maps.of("role", Strings.create("system"), "content", Strings.create("You are helpful")),
			Maps.of("role", Strings.create("user"), "content", Strings.create("Hello")),
			Maps.of("role", Strings.create("assistant"), "content", Strings.create("Hi!"))
		);
		ACell state = Maps.of("history", entries);

		AVector<ACell> history = LLMAgentAdapter.extractHistory(state);
		assertEquals(3, history.count());
	}

	// ========== Direct invocation with test:llm ==========

	@Test
	public void testFirstRunWithConfig() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		assertNotNull(adapter);

		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("first-run-agent"),
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("Hello world"))
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Verify output structure
		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNotNull(newState, "Output should contain state");
		ACell result = RT.getIn(output, Fields.RESULT);
		assertNotNull(result, "Output should contain result");

		// Verify response echoes back (test:llm echoes last user message)
		AString response = RT.ensureString(RT.getIn(result, "response"));
		assertNotNull(response);
		assertEquals("Hello world", response.toString());

		// Verify history was built
		AVector<ACell> history = LLMAgentAdapter.extractHistory(newState);
		assertEquals(3, history.count(), "Should have system + user + assistant");
		assertEquals(Strings.create("system"), RT.getIn(history.get(0), "role"));
		assertEquals(Strings.create("user"), RT.getIn(history.get(1), "role"));
		assertEquals(Strings.create("assistant"), RT.getIn(history.get(2), "role"));
	}

	@Test
	public void testMultiTurnConversation() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// First turn
		ACell input1 = Maps.of(
			Fields.AGENT_ID, Strings.create("multi-turn-agent"),
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("first message"))
			)
		);
		ACell output1 = adapter.processChat(RequestContext.of(ALICE_DID), input1);
		ACell state1 = RT.getIn(output1, AgentState.KEY_STATE);

		// Second turn — pass state from first turn
		ACell input2 = Maps.of(
			Fields.AGENT_ID, Strings.create("multi-turn-agent"),
			AgentState.KEY_STATE, state1,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("second message"))
			)
		);
		ACell output2 = adapter.processChat(RequestContext.of(ALICE_DID), input2);
		ACell state2 = RT.getIn(output2, AgentState.KEY_STATE);

		// Verify history grew
		AVector<ACell> history = LLMAgentAdapter.extractHistory(state2);
		// system + user1 + assistant1 + user2 + assistant2 = 5
		assertEquals(5, history.count());

		// Verify the response is the last user message (test:llm echoes)
		AString response = RT.ensureString(RT.getIn(output2, Fields.RESULT, "response"));
		assertEquals("second message", response.toString());
	}

	@Test
	public void testMultipleInboxMessages() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("batch-agent"),
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("message one")),
				Maps.of("content", Strings.create("message two")),
				Maps.of("content", Strings.create("message three"))
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));

		// system + 3 user + 1 assistant = 5
		assertEquals(5, history.count());

		// test:llm echoes the last user message
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertEquals("message three", response.toString());
	}

	@Test
	public void testCustomSystemPrompt() {
		ACell initialState = Maps.of(
			"config", Maps.of(
				"llmOperation", Strings.create("test:llm"),
				"systemPrompt", Strings.create("You are a pirate")
			)
		);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("pirate-agent"),
			AgentState.KEY_STATE, initialState,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("ahoy"))
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));

		// System prompt should be the custom one
		AString sysContent = RT.ensureString(RT.getIn(history.get(0), "content"));
		assertEquals("You are a pirate", sysContent.toString());
	}

	// ========== Integration: full agent pipeline ==========

	@Test
	public void testEndToEndWithAgentRun() {
		createTestAgent("e2e-agent");

		// Send message
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("e2e-agent"),
				Fields.MESSAGE, Maps.of("content", Strings.create("Hello from e2e"))
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Run with llmagent:chat transition
		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("e2e-agent"),
				Fields.OPERATION, Strings.create("llmagent:chat")
			),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Verify agent state
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("e2e-agent"));
		assertEquals(AgentState.SLEEPING, agent.getStatus());

		// Inbox should be drained
		assertEquals(0, agent.getInbox().count());

		// Timeline should have 1 entry
		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());

		// State should have conversation history
		ACell agentState = agent.getState();
		assertNotNull(agentState);
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agentState);
		assertTrue(history.count() >= 3, "Should have system + user + assistant");

		// Timeline result should contain the response
		ACell timelineEntry = timeline.get(0);
		AString response = RT.ensureString(RT.getIn(timelineEntry, Fields.RESULT, "response"));
		assertNotNull(response, "Timeline result should contain response");
		assertEquals("Hello from e2e", response.toString());
	}

	@Test
	public void testEndToEndMultiRun() {
		createTestAgent("multi-run-agent");

		// First run
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("multi-run-agent"),
				Fields.MESSAGE, Maps.of("content", Strings.create("Turn 1"))
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("multi-run-agent"),
				Fields.OPERATION, Strings.create("llmagent:chat")
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Second run
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("multi-run-agent"),
				Fields.MESSAGE, Maps.of("content", Strings.create("Turn 2"))
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("multi-run-agent"),
				Fields.OPERATION, Strings.create("llmagent:chat")
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Verify state after 2 runs
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("multi-run-agent"));

		// Timeline should have 2 entries
		assertEquals(2, agent.getTimeline().count());

		// History should have accumulated: system + (user1 + assistant1) + (user2 + assistant2) = 5
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertEquals(5, history.count());
	}

	@Test
	public void testEchoStillWorks() {
		// Regression: test:echo should still work as a transition function for agent:run
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("echo-regression")),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("echo-regression"),
				Fields.MESSAGE, Maps.of("content", Strings.create("hello"))
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job job = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("echo-regression"),
				Fields.OPERATION, Strings.create("test:echo")
			),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	// ========== Config ==========

	@Test
	public void testConfigFromState() {
		ACell initialState = Maps.of(
			"config", Maps.of(
				"llmOperation", Strings.create("test:llm"),
				"systemPrompt", Strings.create("Custom prompt")
			)
		);
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("config-agent"),
				AgentState.KEY_STATE, initialState
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		User user = engine.getVenueState().users().get(ALICE_DID);
		ACell agentState = user.agent(Strings.create("config-agent")).getState();

		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("config-agent"),
			AgentState.KEY_STATE, agentState,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("test"))
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// The custom system prompt should be in the history
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));
		AString sysContent = RT.ensureString(RT.getIn(history.get(0), "content"));
		assertEquals("Custom prompt", sysContent.toString());

		// Config should be preserved in returned state
		AMap<AString, ACell> returnedConfig = LLMAgentAdapter.extractConfig(
			RT.getIn(output, AgentState.KEY_STATE));
		assertNotNull(returnedConfig, "Config should be preserved in returned state");
		assertEquals(Strings.create("test:llm"),
			returnedConfig.get(Strings.intern("llmOperation")));
	}

	@Test
	public void testDefaultConfigFallbacks() {
		// State with only llmOperation=test:llm — other settings should use defaults
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("minimal-agent"),
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("test"))
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Should use default system prompt
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));
		AString sysContent = RT.ensureString(RT.getIn(history.get(0), "content"));
		assertTrue(sysContent.toString().contains("Covia platform"),
			"Default system prompt should mention Covia");
	}

	@Test
	public void testNullConfigUsesDefaults() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		assertNull(LLMAgentAdapter.extractConfig(null));
		assertNull(LLMAgentAdapter.extractConfig(Maps.empty()));
	}

	// ========== Tool call loop ==========

	@Test
	public void testToolCallLoop() {
		// Create agent pointing at test:toolllm which requests a tool call then resolves
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", Strings.create("test:toolllm"))
		);
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("tool-agent"),
				AgentState.KEY_STATE, initialState
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Send a message
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("tool-agent"),
				Fields.MESSAGE, Maps.of("content", Strings.create("use a tool"))
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Run — should trigger tool call loop:
		// 1. LLM returns toolCall for test:echo
		// 2. test:echo executes and returns result
		// 3. LLM sees tool result and returns text response
		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("tool-agent"),
				Fields.OPERATION, Strings.create("llmagent:chat")
			),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Verify agent state
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("tool-agent"));

		// History should contain: system + user + assistant(toolCall) + tool(result) + assistant(text)
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertTrue(history.count() >= 5,
			"Should have system + user + assistant(toolCall) + tool + assistant(text), got " + history.count());

		// The final assistant message should contain the tool result
		ACell lastMsg = history.get(history.count() - 1);
		AString lastContent = RT.ensureString(RT.getIn(lastMsg, "content"));
		assertNotNull(lastContent, "Final assistant message should have content");
		assertTrue(lastContent.toString().contains("Tool returned:"),
			"Response should reference tool output: " + lastContent);

		// Timeline result should have the response
		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());
		AString response = RT.ensureString(RT.getIn(timeline.get(0), Fields.RESULT, "response"));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));
	}

	@Test
	public void testToolCallLoopDirect() {
		// Direct processChat test for the tool loop
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = Maps.of(
			"config", Maps.of("llmOperation", Strings.create("test:toolllm"))
		);
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("direct-tool-agent"),
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", Strings.create("do something"))
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Result should contain the tool-based response
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));

		// History should have the full tool call loop recorded
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));
		assertTrue(history.count() >= 5);

		// Check that tool result message is in history
		boolean hasToolMsg = false;
		for (long i = 0; i < history.count(); i++) {
			AString role = RT.ensureString(RT.getIn(history.get(i), "role"));
			if (role != null && "tool".equals(role.toString())) {
				hasToolMsg = true;
				break;
			}
		}
		assertTrue(hasToolMsg, "History should contain a tool result message");
	}

	// ========== Helper ==========

	/**
	 * Creates a test agent with config pointing at test:llm for level 3.
	 */
	private void createTestAgent(String name) {
		ACell initialState = Maps.of(
			"config", Maps.of(
				"llmOperation", Strings.create("test:llm")
			)
		);
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(
				Fields.AGENT_ID, Strings.create(name),
				AgentState.KEY_STATE, initialState
			),
			RequestContext.of(ALICE_DID)).awaitResult();
	}
}
