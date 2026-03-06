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
		"config", Maps.of("llmOperation", "test:llm")
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
		ACell state = Maps.of("history", Vectors.of(
			Maps.of("role", "system", "content", "You are helpful"),
			Maps.of("role", "user", "content", "Hello"),
			Maps.of("role", "assistant", "content", "Hi!")
		));

		AVector<ACell> history = LLMAgentAdapter.extractHistory(state);
		assertEquals(3, history.count());
	}

	// ========== Direct invocation with test:llm ==========

	@Test
	public void testFirstRunWithConfig() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		assertNotNull(adapter);

		ACell input = Maps.of(
			Fields.AGENT_ID, "first-run-agent",
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "Hello world"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		ACell newState = RT.getIn(output, AgentState.KEY_STATE);
		assertNotNull(newState, "Output should contain state");
		ACell result = RT.getIn(output, Fields.RESULT);
		assertNotNull(result, "Output should contain result");

		// test:llm echoes last user message
		AString response = RT.ensureString(RT.getIn(result, "response"));
		assertNotNull(response);
		assertEquals("Hello world", response.toString());

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
			Fields.AGENT_ID, "multi-turn-agent",
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "first message"))
		);
		ACell output1 = adapter.processChat(RequestContext.of(ALICE_DID), input1);
		ACell state1 = RT.getIn(output1, AgentState.KEY_STATE);

		// Second turn — pass state from first turn
		ACell input2 = Maps.of(
			Fields.AGENT_ID, "multi-turn-agent",
			AgentState.KEY_STATE, state1,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "second message"))
		);
		ACell output2 = adapter.processChat(RequestContext.of(ALICE_DID), input2);
		ACell state2 = RT.getIn(output2, AgentState.KEY_STATE);

		// system + user1 + assistant1 + user2 + assistant2 = 5
		AVector<ACell> history = LLMAgentAdapter.extractHistory(state2);
		assertEquals(5, history.count());

		AString response = RT.ensureString(RT.getIn(output2, Fields.RESULT, "response"));
		assertEquals("second message", response.toString());
	}

	@Test
	public void testMultipleInboxMessages() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "batch-agent",
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", "message one"),
				Maps.of("content", "message two"),
				Maps.of("content", "message three")
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));

		// system + 3 user + 1 assistant = 5
		assertEquals(5, history.count());

		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertEquals("message three", response.toString());
	}

	@Test
	public void testCustomSystemPrompt() {
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "test:llm", "systemPrompt", "You are a pirate")
		);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "pirate-agent",
			AgentState.KEY_STATE, initialState,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "ahoy"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));

		AString sysContent = RT.ensureString(RT.getIn(history.get(0), "content"));
		assertEquals("You are a pirate", sysContent.toString());
	}

	// ========== Integration: full agent pipeline ==========

	@Test
	public void testEndToEndWithAgentRun() {
		createTestAgent("e2e-agent");

		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(Fields.AGENT_ID, "e2e-agent", Fields.MESSAGE, Maps.of("content", "Hello from e2e")),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(Fields.AGENT_ID, "e2e-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("e2e-agent"));
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getInbox().count());
		assertEquals(1, agent.getTimeline().count());

		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertTrue(history.count() >= 3, "Should have system + user + assistant");

		ACell timelineEntry = agent.getTimeline().get(0);
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
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.MESSAGE, Maps.of("content", "Turn 1")),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Second run
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.MESSAGE, Maps.of("content", "Turn 2")),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID)).awaitResult();

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("multi-run-agent"));

		assertEquals(2, agent.getTimeline().count());

		// system + (user1 + assistant1) + (user2 + assistant2) = 5
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertEquals(5, history.count());
	}

	@Test
	public void testEchoStillWorks() {
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, "echo-regression"),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(Fields.AGENT_ID, "echo-regression", Fields.MESSAGE, Maps.of("content", "hello")),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job job = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(Fields.AGENT_ID, "echo-regression", Fields.OPERATION, "test:echo"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	// ========== Config ==========

	@Test
	public void testConfigFromState() {
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "test:llm", "systemPrompt", "Custom prompt")
		);
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, "config-agent", AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult();

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		User user = engine.getVenueState().users().get(ALICE_DID);
		ACell agentState = user.agent(Strings.create("config-agent")).getState();

		ACell input = Maps.of(
			Fields.AGENT_ID, "config-agent",
			AgentState.KEY_STATE, agentState,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));
		AString sysContent = RT.ensureString(RT.getIn(history.get(0), "content"));
		assertEquals("Custom prompt", sysContent.toString());

		AMap<AString, ACell> returnedConfig = LLMAgentAdapter.extractConfig(
			RT.getIn(output, AgentState.KEY_STATE));
		assertNotNull(returnedConfig, "Config should be preserved in returned state");
		assertEquals(Strings.create("test:llm"),
			returnedConfig.get(Strings.intern("llmOperation")));
	}

	@Test
	public void testDefaultConfigFallbacks() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "minimal-agent",
			AgentState.KEY_STATE, TEST_STATE,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));
		AString sysContent = RT.ensureString(RT.getIn(history.get(0), "content"));
		assertTrue(sysContent.toString().contains("Covia platform"),
			"Default system prompt should mention Covia");
	}

	@Test
	public void testNullConfigUsesDefaults() {
		assertNull(LLMAgentAdapter.extractConfig(null));
		assertNull(LLMAgentAdapter.extractConfig(Maps.empty()));
	}

	// ========== Tool call loop ==========

	@Test
	public void testToolCallLoop() {
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "test:toolllm"));
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, "tool-agent", AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(Fields.AGENT_ID, "tool-agent", Fields.MESSAGE, Maps.of("content", "use a tool")),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(Fields.AGENT_ID, "tool-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("tool-agent"));

		// system + user + assistant(toolCall) + tool(result) + assistant(text)
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertTrue(history.count() >= 5,
			"Should have system + user + assistant(toolCall) + tool + assistant(text), got " + history.count());

		ACell lastMsg = history.get(history.count() - 1);
		AString lastContent = RT.ensureString(RT.getIn(lastMsg, "content"));
		assertNotNull(lastContent, "Final assistant message should have content");
		assertTrue(lastContent.toString().contains("Tool returned:"),
			"Response should reference tool output: " + lastContent);

		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());
		AString response = RT.ensureString(RT.getIn(timeline.get(0), Fields.RESULT, "response"));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));
	}

	@Test
	public void testToolCallLoopDirect() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "direct-tool-agent",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "test:toolllm")),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "do something"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));

		AVector<ACell> history = LLMAgentAdapter.extractHistory(
			RT.getIn(output, AgentState.KEY_STATE));
		assertTrue(history.count() >= 5);

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

	// ========== Response format ==========

	@Test
	public void testResponseFormatInConfig() {
		ACell responseFormat = Maps.of(
			"name", "Summary",
			"schema", Maps.of(
				"type", "object",
				"properties", Maps.of(
					"title", Maps.of("type", "string"),
					"points", Maps.of("type", "array")
				),
				"required", Vectors.of("title")
			)
		);
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "test:llm", "responseFormat", responseFormat)
		);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "format-agent",
			AgentState.KEY_STATE, initialState,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "summarise this"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AMap<AString, ACell> returnedConfig = LLMAgentAdapter.extractConfig(
			RT.getIn(output, AgentState.KEY_STATE));
		assertNotNull(returnedConfig);
		ACell returnedFormat = returnedConfig.get(Strings.intern("responseFormat"));
		assertNotNull(returnedFormat, "responseFormat should be preserved in config");

		AString name = RT.ensureString(RT.getIn(returnedFormat, "name"));
		assertEquals("Summary", name.toString());
	}

	@Test
	public void testResponseFormatJsonString() {
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "test:llm", "responseFormat", "json")
		);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "json-format-agent",
			AgentState.KEY_STATE, initialState,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "give me json"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response);
		assertEquals("give me json", response.toString());
	}

	// ========== Helper ==========

	private void createTestAgent(String name) {
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "test:llm"));
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, name, AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult();
	}
}
