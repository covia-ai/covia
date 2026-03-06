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
			"agent:message",
			Maps.of(Fields.AGENT_ID, "e2e-agent", Fields.MESSAGE, Maps.of("content", "Hello from e2e")),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job runJob = engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "e2e-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("e2e-agent");
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
			"agent:message",
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.MESSAGE, Maps.of("content", "Turn 1")),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Second run
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.MESSAGE, Maps.of("content", "Turn 2")),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "multi-run-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID)).awaitResult();

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-run-agent");

		assertEquals(2, agent.getTimeline().count());

		// system + (user1 + assistant1) + (user2 + assistant2) = 5
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertEquals(5, history.count());
	}

	@Test
	public void testEchoStillWorks() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "echo-regression"),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			"agent:message",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "config-agent", AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult();

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		User user = engine.getVenueState().users().get(ALICE_DID);
		ACell agentState = user.agent("config-agent").getState();

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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "tool-agent", AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "tool-agent", Fields.MESSAGE, Maps.of("content", "use a tool")),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job runJob = engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "tool-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("tool-agent");

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

	// ========== Built-in tools: complete_task ==========

	@Test
	public void testCompleteTaskEndToEnd() {
		// Create agent with test:taskllm — a mock LLM that calls complete_task
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "test:taskllm"));
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "task-agent",
				AgentState.KEY_STATE, initialState,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "llmagent:chat")
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Submit a request — use agent:message to add the task, then agent:run explicitly.
		// We avoid agent:request's async schedule and drive the pipeline synchronously.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		// Create a request job manually in STARTED state and add it as a task
		Job requestJob = engine.jobs().invokeOperation(
			"test:never", Maps.empty(), RequestContext.of(ALICE_DID));
		agent.addTask(requestJob.getID(), Maps.of("question", "What is 2+2?"));

		// Run the agent synchronously — level 2 (test:taskllm) will call complete_task
		Job runJob = engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "task-agent", Fields.OPERATION, "llmagent:chat"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult();

		// The request Job should be COMPLETE with the agent's output
		assertTrue(requestJob.isFinished(), "Request job should be finished");
		assertEquals("COMPLETE", requestJob.getStatus().toString());

		// Verify agent state
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getTasks().count(), "Tasks should be empty after completion");
		assertEquals(1, agent.getTimeline().count());

		// Verify timeline has taskResults
		ACell timelineEntry = agent.getTimeline().get(0);
		assertNotNull(RT.getIn(timelineEntry, Fields.TASK_RESULTS),
			"Timeline should record task completions");
	}

	@Test
	public void testCompleteTaskDirect() {
		// Test complete_task via processChat directly (no agent:request pipeline)
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Create a task job manually
		Job taskJob = engine.jobs().invokeOperation(
			"test:echo", Maps.of("echo", "task payload"), RequestContext.of(ALICE_DID));
		// Don't await — leave it in a state we can complete
		String taskJobId = taskJob.getID().toHexString();

		// Create a new job to be our "task" that the agent will complete
		Job pendingTask = engine.jobs().invokeOperation(
			"test:never", Maps.empty(), RequestContext.of(ALICE_DID));
		String pendingTaskId = pendingTask.getID().toHexString();

		// Build input with tasks
		ACell input = Maps.of(
			Fields.AGENT_ID, "direct-task-agent",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "test:taskllm")),
			Fields.TASKS, Vectors.of(Maps.of(
				Fields.JOB_ID, pendingTaskId,
				Fields.INPUT, Maps.of("question", "test?"),
				Fields.STATUS, "STARTED"
			)),
			Fields.MESSAGES, Vectors.empty()
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// The task should have been completed via the built-in tool
		ACell taskResults = RT.getIn(output, Fields.TASK_RESULTS);
		assertNotNull(taskResults, "Output should contain taskResults from complete_task tool call");

		// The pending task job should now be complete
		assertTrue(pendingTask.isFinished(), "Task job should be completed by complete_task tool");
	}

	// ========== Built-in tools: invoke ==========

	@Test
	public void testInvokeToolCallLoop() {
		// test:toolllm calls test:echo via tool call — this exercises the invoke path
		// since test:echo is not a built-in tool, it falls through to grid dispatch
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "invoke-agent",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "test:toolllm")),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "call a tool"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));
	}

	// ========== Built-in tools: message_agent ==========

	@Test
	public void testMessageAgentBuiltIn() {
		// Create two agents
		createTestAgent("sender-agent");
		createTestAgent("receiver-agent");

		// Manually call the built-in via processChat
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Use test:toolllm-style mock that calls message_agent — but we can test
		// the built-in directly by checking the tool dispatch
		// For now, verify the agent exists and can receive messages
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState receiver = user.agent("receiver-agent");
		assertEquals(0, receiver.getInbox().count());

		// Deliver via agent:message (existing path) to verify receiver works
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "receiver-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID)).awaitResult();

		assertEquals(1, receiver.getInbox().count());
	}

	// ========== Default tools are merged ==========

	@Test
	public void testDefaultToolsPresent() {
		// Verify that processChat passes default tools to level 3
		// Use test:llm which echoes — we just verify it doesn't crash with tools
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "tools-check",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "test:llm")),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hello"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// The LLM should still respond normally with default tools present
		AString response = RT.ensureString(RT.getIn(output, Fields.RESULT, "response"));
		assertEquals("hello", response.toString());
	}

	// ========== Helper ==========

	private void createTestAgent(String name) {
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "test:llm"));
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, name, AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult();
	}
}
