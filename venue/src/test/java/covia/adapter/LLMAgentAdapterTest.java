package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.HashMap;
import java.util.Map;

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
import covia.adapter.LLMAgentAdapter.ToolContext;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * Tests for the LLMAgentAdapter transition function.
 *
 * <p>Uses {@code test:llm} as the level 3 operation, which echoes the last
 * user message as the response — no real LLM needed.</p>
 *
 * <p>Uses the shared {@link TestEngine#ENGINE} with per-test ALICE_DID for
 * isolation.</p>
 */
public class LLMAgentAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	// ALICE_DID is per-test (not static) so each test sees a fresh user
	// namespace within the shared engine.
	private AString ALICE_DID;

	/** State with test LLM config — points at test:llm for level 3 */
	private static final ACell TEST_STATE = Maps.of(
		"config", Maps.of("llmOperation", "v/test/ops/llm")
	);

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
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

		// test:llm echoes last user message
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Output should contain response");
		assertEquals("Hello world", response.toString());

		// Transcript holds only real conversation turns — system messages,
		// [Context Map], and other ephemeral context are NOT persisted.
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(newState);
		assertEquals(2, transcript.count(), "Transcript: user + assistant");
		assertEquals(Strings.create("user"), RT.getIn(transcript.get(0), "role"));
		assertEquals(Strings.create("assistant"), RT.getIn(transcript.get(1), "role"));
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

		// Transcript: user1 + assistant1 + user2 + assistant2 = 4
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(state2);
		assertEquals(4, transcript.count());

		AString response = RT.ensureString(RT.getIn(output2, Fields.RESPONSE));
		assertEquals("second message", response.toString());
	}

	@Test
	public void testTranscriptDoesNotAccumulateEphemeralContext() {
		// AGENT_CONTEXT_PLAN.md §2.1 — the duplication bug. Two turns of
		// processChat must produce a transcript containing only the real
		// conversation turns. No system messages, no [Context Map], no
		// context entries — those are ephemeral and rebuilt fresh per turn.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = TEST_STATE;
		// Three turns
		for (int i = 1; i <= 3; i++) {
			ACell input = Maps.of(
				Fields.AGENT_ID, "no-bloat-agent",
				AgentState.KEY_STATE, state,
				Fields.MESSAGES, Vectors.of(Maps.of("content", "turn " + i))
			);
			ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
			state = RT.getIn(output, AgentState.KEY_STATE);
		}

		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(state);
		// Three (user + assistant) pairs
		assertEquals(6, transcript.count());

		// Verify NO system messages and NO [Context Map] in the transcript
		for (long i = 0; i < transcript.count(); i++) {
			AString role = RT.ensureString(RT.getIn(transcript.get(i), "role"));
			assertNotEquals("system", role.toString(),
				"Transcript should never contain system messages, found one at index " + i);
			AString content = RT.ensureString(RT.getIn(transcript.get(i), "content"));
			if (content != null) {
				assertFalse(content.toString().contains("[Context Map]"),
					"Transcript should never contain [Context Map] entries, found one at index " + i);
			}
		}

		// Roles alternate user, assistant, user, assistant, user, assistant
		assertEquals(Strings.create("user"),      RT.getIn(transcript.get(0), "role"));
		assertEquals(Strings.create("assistant"), RT.getIn(transcript.get(1), "role"));
		assertEquals(Strings.create("user"),      RT.getIn(transcript.get(2), "role"));
		assertEquals(Strings.create("assistant"), RT.getIn(transcript.get(3), "role"));
		assertEquals(Strings.create("user"),      RT.getIn(transcript.get(4), "role"));
		assertEquals(Strings.create("assistant"), RT.getIn(transcript.get(5), "role"));
	}

	@Test
	public void testSystemPromptUpdatesAcrossTurnsAreNotFrozen() {
		// AGENT_CONTEXT_PLAN.md §2.2 — system prompt freeze bug. After
		// turn 1 the agent's stored state must NOT contain a system
		// message. The next turn rebuilds the system message fresh from
		// current config, so updates apply immediately.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = TEST_STATE;
		// First turn
		ACell input1 = Maps.of(
			Fields.AGENT_ID, "fresh-prompt-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hi"))
		);
		ACell output1 = adapter.processChat(RequestContext.of(ALICE_DID), input1);
		ACell state1 = RT.getIn(output1, AgentState.KEY_STATE);

		// Verify state1 has NO system message anywhere
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(state1);
		for (long i = 0; i < transcript.count(); i++) {
			AString role = RT.ensureString(RT.getIn(transcript.get(i), "role"));
			assertNotEquals("system", role.toString(),
				"Persisted state must not contain a frozen system message");
		}
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
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(
			RT.getIn(output, AgentState.KEY_STATE));

		// Transcript: 3 inbox messages (user) + 1 assistant = 4
		assertEquals(4, transcript.count());

		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertEquals("message three", response.toString());
	}

	@Test
	public void testCustomSystemPrompt() {
		// processChat should accept and apply a custom systemPrompt without
		// error. The system message itself is rebuilt fresh per turn and
		// not persisted to the transcript — see ContextBuilderTest
		// .testSystemPromptIncludesLatticeReference for the assertion that
		// the prompt actually reaches the LLM context.
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "v/test/ops/llm", "systemPrompt", "You are a pirate")
		);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "pirate-agent",
			AgentState.KEY_STATE, initialState,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "ahoy"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(
			RT.getIn(output, AgentState.KEY_STATE));
		// Just the real conversation: user + assistant
		assertEquals(2, transcript.count());
		assertEquals(Strings.create("user"), RT.getIn(transcript.get(0), "role"));
		assertEquals(Strings.create("assistant"), RT.getIn(transcript.get(1), "role"));
	}

	// ========== Integration: full agent pipeline ==========

	@Test
	public void testEndToEndWithAgentTrigger() {
		createTestAgent("e2e-agent");

		// Deliver directly to avoid auto-wake race
		User e2eUser = engine.getVenueState().users().get(ALICE_DID);
		e2eUser.agent("e2e-agent").deliverMessage(Maps.of("content", "Hello from e2e"));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "e2e-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("e2e-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getInbox().count());
		assertEquals(1, agent.getTimeline().count());

		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(agent.getState());
		assertTrue(transcript.count() >= 2, "Transcript should have user + assistant");

		ACell timelineEntry = agent.getTimeline().get(0);
		// Timeline `result` is the bare lean response (Sub-stage 2.4) — the
		// framework no longer wraps it in {response: ...}.
		AString response = RT.ensureString(RT.getIn(timelineEntry, Fields.RESULT));
		assertNotNull(response, "Timeline result should contain response");
		assertEquals("Hello from e2e", response.toString());
	}

	@Test
	public void testEndToEndMultiTrigger() {
		createTestAgent("multi-run-agent");
		User multiUser = engine.getVenueState().users().get(ALICE_DID);

		// First run — deliver directly to avoid auto-wake race
		multiUser.agent("multi-run-agent").deliverMessage(Maps.of("content", "Turn 1"));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "multi-run-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Second run
		multiUser.agent("multi-run-agent").deliverMessage(Maps.of("content", "Turn 2"));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "multi-run-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-run-agent");

		assertEquals(2, agent.getTimeline().count());

		// Transcript: user1 + assistant1 + user2 + assistant2 = 4
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(agent.getState());
		assertEquals(4, transcript.count());
	}

	@Test
	public void testEchoStillWorks() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "echo-regression",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake
		User echoUser = engine.getVenueState().users().get(ALICE_DID);
		echoUser.agent("echo-regression").deliverMessage(Maps.of("content", "hello"));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "echo-regression"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	// ========== Config ==========

	@Test
	public void testConfigFromState() {
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "v/test/ops/llm", "systemPrompt", "Custom prompt")
		);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "config-agent", AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

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

		// Custom systemPrompt application is verified in
		// ContextBuilderTest.testSystemPromptIncludesLatticeReference.
		// Here we just check that processChat ran cleanly and that the
		// returned state preserves the merged config.
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(
			RT.getIn(output, AgentState.KEY_STATE));
		assertEquals(2, transcript.count(), "Transcript: user + assistant");

		AMap<AString, ACell> returnedConfig = LLMAgentAdapter.extractConfig(
			RT.getIn(output, AgentState.KEY_STATE));
		assertNotNull(returnedConfig, "Config should be preserved in returned state");
		assertEquals(Strings.create("v/test/ops/llm"),
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

		// Default system prompt is built fresh per turn (not persisted).
		// Default content is verified in ContextBuilderTest. Here we just
		// confirm processChat completes and persists the conversation.
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(
			RT.getIn(output, AgentState.KEY_STATE));
		assertEquals(2, transcript.count(), "Transcript: user + assistant");
	}

	@Test
	public void testNullConfigUsesDefaults() {
		assertNull(LLMAgentAdapter.extractConfig(null));
		assertNull(LLMAgentAdapter.extractConfig(Maps.empty()));
	}

	// ========== Tool call loop ==========

	@Test
	public void testToolCallLoop() {
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "v/test/ops/toolllm"));
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tool-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake
		User toolUser = engine.getVenueState().users().get(ALICE_DID);
		toolUser.agent("tool-agent").deliverMessage(Maps.of("content", "use a tool"));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "tool-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("tool-agent");

		// Transcript: user + assistant(toolCall) + tool(result) + assistant(text) = 4
		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(agent.getState());
		assertTrue(transcript.count() >= 4,
			"Transcript should have user + assistant(toolCall) + tool + assistant(text), got " + transcript.count());

		ACell lastMsg = transcript.get(transcript.count() - 1);
		AString lastContent = RT.ensureString(RT.getIn(lastMsg, "content"));
		assertNotNull(lastContent, "Final assistant message should have content");
		assertTrue(lastContent.toString().contains("Tool returned:"),
			"Response should reference tool output: " + lastContent);

		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());
		AString response = RT.ensureString(RT.getIn(timeline.get(0), Fields.RESULT));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));
	}

	@Test
	public void testToolCallLoopDirect() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "direct-tool-agent",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "v/test/ops/toolllm")),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "do something"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));

		AVector<ACell> transcript = LLMAgentAdapter.extractTranscript(
			RT.getIn(output, AgentState.KEY_STATE));
		assertTrue(transcript.count() >= 4);

		boolean hasToolMsg = false;
		for (long i = 0; i < transcript.count(); i++) {
			AString role = RT.ensureString(RT.getIn(transcript.get(i), "role"));
			if (role != null && "tool".equals(role.toString())) {
				hasToolMsg = true;
				break;
			}
		}
		assertTrue(hasToolMsg, "Transcript should contain a tool result message");
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
			"config", Maps.of("llmOperation", "v/test/ops/llm", "responseFormat", responseFormat)
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
			"config", Maps.of("llmOperation", "v/test/ops/llm", "responseFormat", "json")
		);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "json-format-agent",
			AgentState.KEY_STATE, initialState,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "give me json"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		assertEquals("give me json", response.toString());
	}

	// ========== Built-in tools: complete_task ==========

	@Test
	public void testCompleteTaskEndToEnd() {
		// Create agent with test:taskllm — a mock LLM that calls complete_task
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "v/test/ops/taskllm"));
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "task-agent",
				AgentState.KEY_STATE, initialState,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Add task directly and trigger — avoid agent:request's async wake
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		// Tasks are pure lattice data — use a generated Blob ID, not a Job
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of("question", "What is 2+2?"));

		// Trigger the agent — level 2 (test:taskllm) will call complete_task
		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "task-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);

		// Verify agent state — task should be removed after completion
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

		// Tasks are pure lattice data — use a synthetic hex ID
		String taskId = Blob.createRandom(new java.util.Random(), 16).toHexString();

		// Build input with tasks
		ACell input = Maps.of(
			Fields.AGENT_ID, "direct-task-agent",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "v/test/ops/taskllm")),
			Fields.TASKS, Vectors.of(Maps.of(
				Fields.JOB_ID, taskId,
				Fields.INPUT, Maps.of("question", "test?")
			)),
			Fields.MESSAGES, Vectors.empty()
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Lean contract: complete_task surfaces as taskComplete=true plus a
		// non-null response (the structured task output overrides the chat
		// text). The framework synthesises the per-task taskResults entry.
		assertEquals(CVMBool.TRUE, RT.getIn(output, Fields.TASK_COMPLETE),
			"Output should signal taskComplete=true after complete_task tool call");
		assertNotNull(RT.getIn(output, Fields.RESPONSE),
			"Output should carry the task's structured response");
	}

	// ========== Built-in tools: invoke ==========

	@Test
	public void testInvokeToolCallLoop() {
		// test:toolllm calls test:echo via tool call — this exercises the invoke path
		// since test:echo is not a built-in tool, it falls through to grid dispatch
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "invoke-agent",
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "v/test/ops/toolllm")),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "call a tool"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
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
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "receiver-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

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
			AgentState.KEY_STATE, Maps.of("config", Maps.of("llmOperation", "v/test/ops/llm")),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hello"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// The LLM should still respond normally with default tools present
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertEquals("hello", response.toString());
	}

	// ========== Pure function: extractConfig ==========

	@Test
	public void testExtractConfigNull() {
		assertNull(LLMAgentAdapter.extractConfig(null));
	}

	@Test
	public void testExtractConfigEmptyState() {
		assertNull(LLMAgentAdapter.extractConfig(Maps.empty()));
	}

	@Test
	public void testExtractConfigPresent() {
		AMap<AString, ACell> config = Maps.of("llmOperation", "v/test/ops/llm");
		ACell state = Maps.of("config", config);
		AMap<AString, ACell> result = LLMAgentAdapter.extractConfig(state);
		assertNotNull(result);
		assertEquals(Strings.create("v/test/ops/llm"), result.get(Strings.intern("llmOperation")));
	}

	@Test
	public void testExtractConfigNonMap() {
		// config is a string — should return null
		ACell state = Maps.of("config", Strings.create("not-a-map"));
		assertNull(LLMAgentAdapter.extractConfig(state));
	}

	// ========== Pure function: extractHistory ==========

	@Test
	public void testExtractHistoryEmptyState() {
		AVector<ACell> h = LLMAgentAdapter.extractHistory(Maps.empty());
		assertEquals(0, h.count());
	}

	@Test
	public void testExtractHistoryNonVector() {
		ACell state = Maps.of("history", Strings.create("not-a-vector"));
		AVector<ACell> h = LLMAgentAdapter.extractHistory(state);
		assertEquals(0, h.count());
	}

	// ========== Pure function: getConfigValue ==========

	@Test
	public void testGetConfigValuePresent() {
		AMap<AString, ACell> config = Maps.of("model", "gpt-4");
		AString result = LLMAgentAdapter.getConfigValue(config, Strings.intern("model"), null);
		assertEquals("gpt-4", result.toString());
	}

	@Test
	public void testGetConfigValueMissing() {
		AMap<AString, ACell> config = Maps.of("model", "gpt-4");
		AString def = Strings.create("default-val");
		AString result = LLMAgentAdapter.getConfigValue(config, Strings.intern("nonexistent"), def);
		assertSame(def, result);
	}

	@Test
	public void testGetConfigValueNullConfig() {
		AString def = Strings.create("fallback");
		AString result = LLMAgentAdapter.getConfigValue(null, Strings.intern("key"), def);
		assertSame(def, result);
	}

	@Test
	public void testGetConfigValueNonString() {
		// Value is a long, not a string — should return default
		AMap<AString, ACell> config = Maps.of("count", CVMLong.create(42));
		AString result = LLMAgentAdapter.getConfigValue(config, Strings.intern("count"), Strings.create("def"));
		assertEquals("def", result.toString());
	}

	// ========== Pure function: ensureParsedInput ==========

	@Test
	public void testEnsureParsedInputNull() {
		ACell result = LLMAgentAdapter.ensureParsedInput(null);
		assertEquals(Maps.empty(), result);
	}

	@Test
	public void testEnsureParsedInputMap() {
		AMap<AString, ACell> map = Maps.of("key", "value");
		ACell result = LLMAgentAdapter.ensureParsedInput(map);
		assertSame(map, result);
	}

	@Test
	public void testEnsureParsedInputJsonString() {
		AString jsonStr = Strings.create("{\"name\": \"alice\", \"age\": 30}");
		ACell result = LLMAgentAdapter.ensureParsedInput(jsonStr);
		assertTrue(result instanceof AMap, "Should parse JSON string into a map");
		assertEquals(Strings.create("alice"), RT.getIn(result, "name"));
		assertEquals(CVMLong.create(30), RT.getIn(result, "age"));
	}

	@Test
	public void testEnsureParsedInputInvalidJsonString() {
		AString garbage = Strings.create("not valid json {{{");
		ACell result = LLMAgentAdapter.ensureParsedInput(garbage);
		// Should return the original string when parsing fails
		assertSame(garbage, result);
	}

	@Test
	public void testEnsureParsedInputVector() {
		AVector<ACell> vec = Vectors.of(Strings.create("a"), Strings.create("b"));
		ACell result = LLMAgentAdapter.ensureParsedInput(vec);
		assertSame(vec, result);
	}

	// ========== Pure function: parseConfigToolEntry ==========

	@Test
	public void testParseConfigToolEntryString() {
		AString[] parsed = LLMAgentAdapter.parseConfigToolEntry(Strings.create("v/ops/agent/create"));
		assertNotNull(parsed);
		assertEquals("v/ops/agent/create", parsed[0].toString());
		assertNull(parsed[1]); // no name override
		assertNull(parsed[2]); // no description override
	}

	@Test
	public void testParseConfigToolEntryMapFull() {
		ACell entry = Maps.of(
			"operation", "v/ops/http/get",
			"name", "fetch_url",
			"description", "Fetch a URL"
		);
		AString[] parsed = LLMAgentAdapter.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/http/get", parsed[0].toString());
		assertEquals("fetch_url", parsed[1].toString());
		assertEquals("Fetch a URL", parsed[2].toString());
	}

	@Test
	public void testParseConfigToolEntryMapMinimal() {
		ACell entry = Maps.of("operation", "v/ops/agent/list");
		AString[] parsed = LLMAgentAdapter.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/agent/list", parsed[0].toString());
		assertNull(parsed[1]);
		assertNull(parsed[2]);
	}

	@Test
	public void testParseConfigToolEntryMapMissingOperation() {
		ACell entry = Maps.of("name", "orphan_tool");
		assertNull(LLMAgentAdapter.parseConfigToolEntry(entry));
	}

	@Test
	public void testParseConfigToolEntryInvalidType() {
		assertNull(LLMAgentAdapter.parseConfigToolEntry(CVMLong.create(42)));
		assertNull(LLMAgentAdapter.parseConfigToolEntry(CVMBool.TRUE));
	}

	@Test
	public void testParseConfigToolEntryNull() {
		assertNull(LLMAgentAdapter.parseConfigToolEntry(null));
	}

	// ========== Pure function: deriveToolName ==========

	@Test
	public void testDeriveToolNameOverrideWins() {
		String name = LLMAgentAdapter.deriveToolName(
			Strings.create("my_tool"),
			Strings.create("asset_tool"),
			Strings.create("adapter:op"));
		assertEquals("my_tool", name);
	}

	@Test
	public void testDeriveToolNameAssetToolNameWins() {
		String name = LLMAgentAdapter.deriveToolName(
			null,
			Strings.create("asset_tool"),
			Strings.create("adapter:op"));
		assertEquals("asset_tool", name);
	}

	@Test
	public void testDeriveToolNameFallbackColonToUnderscore() {
		String name = LLMAgentAdapter.deriveToolName(
			null, null, Strings.create("agent:create"));
		assertEquals("agent_create", name);
	}

	@Test
	public void testDeriveToolNameFallbackSlashToUnderscore() {
		String name = LLMAgentAdapter.deriveToolName(
			null, null, Strings.create("did:venue:user/o/my-tool"));
		assertEquals("did_venue_user_o_my-tool", name);
	}

	@Test
	public void testDeriveToolNameNoSpecialChars() {
		String name = LLMAgentAdapter.deriveToolName(
			null, null, Strings.create("simple"));
		assertEquals("simple", name);
	}

	// ========== Pure function: buildToolDefinition ==========

	@Test
	public void testBuildToolDefinitionWithSchema() {
		AMap<AString, ACell> schema = Maps.of(
			"type", "object",
			"properties", Maps.of("url", Maps.of("type", "string")),
			"required", Vectors.of("url")
		);
		AMap<AString, ACell> def = LLMAgentAdapter.buildToolDefinition(
			"fetch_url", Strings.create("Fetch a URL"), schema);

		assertEquals(Strings.create("fetch_url"), def.get(Strings.intern("name")));
		assertEquals(Strings.create("Fetch a URL"), def.get(Strings.intern("description")));
		assertSame(schema, def.get(Strings.intern("parameters")));
	}

	@Test
	public void testBuildToolDefinitionNullSchema() {
		AMap<AString, ACell> def = LLMAgentAdapter.buildToolDefinition(
			"my_tool", Strings.create("Does stuff"), null);

		assertEquals(Strings.create("my_tool"), def.get(Strings.intern("name")));
		// Should get default schema with type: "object"
		ACell params = def.get(Strings.intern("parameters"));
		assertNotNull(params);
		assertEquals(Strings.create("object"), RT.getIn(params, "type"));
	}

	@Test
	public void testBuildToolDefinitionNullDescription() {
		AMap<AString, ACell> schema = Maps.of("type", "object");
		AMap<AString, ACell> def = LLMAgentAdapter.buildToolDefinition("tool", null, schema);

		assertEquals(Strings.create("tool"), def.get(Strings.intern("name")));
		assertNull(def.get(Strings.intern("description")));
	}

	@Test
	public void testBuildToolDefinitionStringSchema() {
		// Non-map schema (e.g. a string) should get default object schema
		AMap<AString, ACell> def = LLMAgentAdapter.buildToolDefinition(
			"tool", null, Strings.create("bad-schema"));
		ACell params = def.get(Strings.intern("parameters"));
		assertEquals(Strings.create("object"), RT.getIn(params, "type"));
	}

	// ========== Pure function: isKnownTask ==========

	@Test
	public void testIsKnownTaskFound() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "task1"),
			Maps.of(Fields.JOB_ID, "bbb", Fields.INPUT, "task2")
		);
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null, null);

		assertTrue(LLMAgentAdapter.isKnownTask(Strings.create("aaa"), ctx));
		assertTrue(LLMAgentAdapter.isKnownTask(Strings.create("bbb"), ctx));
	}

	@Test
	public void testIsKnownTaskNotFound() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "task1")
		);
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null, null);

		assertFalse(LLMAgentAdapter.isKnownTask(Strings.create("zzz"), ctx));
	}

	@Test
	public void testIsKnownTaskNullTasks() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		assertFalse(LLMAgentAdapter.isKnownTask(Strings.create("aaa"), ctx));
	}

	@Test
	public void testIsKnownTaskEmptyTasks() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, Vectors.empty(), null, null, null, null);
		assertFalse(LLMAgentAdapter.isKnownTask(Strings.create("aaa"), ctx));
	}

	// ========== Pure function: isAlreadyCompleted ==========

	@Test
	public void testIsAlreadyCompletedTrue() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		ctx.recordTaskResult(Strings.create("aaa"),
			Maps.of(Fields.STATUS, Status.COMPLETE));

		assertTrue(LLMAgentAdapter.isAlreadyCompleted(Strings.create("aaa"), ctx));
	}

	@Test
	public void testIsAlreadyCompletedFalse() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		ctx.recordTaskResult(Strings.create("aaa"),
			Maps.of(Fields.STATUS, Status.COMPLETE));

		assertFalse(LLMAgentAdapter.isAlreadyCompleted(Strings.create("bbb"), ctx));
	}

	@Test
	public void testIsAlreadyCompletedNullResults() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		assertFalse(LLMAgentAdapter.isAlreadyCompleted(Strings.create("aaa"), ctx));
	}

	// ========== Pure function: buildOutstandingTaskMessage ==========

	@Test
	public void testBuildOutstandingTaskMessageNoTasks() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		assertNull(LLMAgentAdapter.buildOutstandingTaskMessage(ctx));
	}

	@Test
	public void testBuildOutstandingTaskMessageEmptyTasks() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, Vectors.empty(), null, null, null, null);
		assertNull(LLMAgentAdapter.buildOutstandingTaskMessage(ctx));
	}

	@Test
	public void testBuildOutstandingTaskMessageAllResolved() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "task1")
		);
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null, null);
		ctx.recordTaskResult(Strings.create("aaa"),
			Maps.of(Fields.STATUS, Status.COMPLETE));

		assertNull(LLMAgentAdapter.buildOutstandingTaskMessage(ctx));
	}

	@Test
	public void testBuildOutstandingTaskMessageSomeOutstanding() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "done-task"),
			Maps.of(Fields.JOB_ID, "bbb", Fields.INPUT, "pending-task")
		);
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null, null);
		ctx.recordTaskResult(Strings.create("aaa"),
			Maps.of(Fields.STATUS, Status.COMPLETE));

		AMap<AString, ACell> msg = LLMAgentAdapter.buildOutstandingTaskMessage(ctx);
		assertNotNull(msg);
		assertEquals(Strings.create("user"), msg.get(Strings.intern("role")));

		String content = RT.ensureString(msg.get(Strings.intern("content"))).toString();
		assertTrue(content.contains("bbb"), "Should mention outstanding task bbb");
		assertFalse(content.contains("aaa"), "Should not mention resolved task aaa");
		assertTrue(content.contains("complete_task"), "Should instruct to use complete_task");
	}

	@Test
	public void testBuildOutstandingTaskMessageAllOutstanding() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "task-one"),
			Maps.of(Fields.JOB_ID, "bbb", Fields.INPUT, "task-two")
		);
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null, null);

		AMap<AString, ACell> msg = LLMAgentAdapter.buildOutstandingTaskMessage(ctx);
		assertNotNull(msg);
		String content = RT.ensureString(msg.get(Strings.intern("content"))).toString();
		assertTrue(content.contains("aaa"));
		assertTrue(content.contains("bbb"));
		assertTrue(content.contains("[Tasks assigned to you]"));
	}

	// ========== ToolContext: recordTaskResult ==========

	@Test
	public void testToolContextRecordTaskResult() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		assertNull(ctx.taskResults);

		ctx.recordTaskResult(Strings.create("job1"),
			Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, "result1"));
		assertNotNull(ctx.taskResults);
		assertEquals(1, ctx.taskResults.count());

		ctx.recordTaskResult(Strings.create("job2"),
			Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR, "reason"));
		assertEquals(2, ctx.taskResults.count());

		// Verify contents
		assertEquals(Status.COMPLETE, RT.getIn(ctx.taskResults, "job1", Fields.STATUS));
		assertEquals(Status.FAILED, RT.getIn(ctx.taskResults, "job2", Fields.STATUS));
	}

	// ========== Integration: buildConfigTools with engine ==========

	@Test
	public void testBuildConfigToolsStringEntries() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		Map<String, AString> configToolMap = new HashMap<>();
		AVector<ACell> toolsVec = Vectors.of(
			(ACell) Strings.create("v/ops/agent/create"),
			(ACell) Strings.create("v/ops/agent/list")
		);

		// Use reflection-free approach: call processChat with tools in config
		// and verify the tools are present by checking configToolMap
		// Actually, buildConfigTools is private — but we can test via processChat
		// and check output state. However, the refactored pure helpers cover
		// the logic. Let's test the integration path instead.

		// Create agent with custom tools config, call processChat, verify it works
		ACell state = Maps.of("config", Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of("v/ops/agent/create", "v/ops/agent/list")
		));

		ACell input = Maps.of(
			Fields.AGENT_ID, "custom-tools-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
		// Should complete without error — tools resolved successfully
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertEquals("test", response.toString());
	}

	@Test
	public void testBuildConfigToolsMapEntries() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = Maps.of("config", Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of(
				Maps.of("operation", "v/ops/agent/create",
					"name", "make_agent",
					"description", "Create a new agent")
			)
		));

		ACell input = Maps.of(
			Fields.AGENT_ID, "map-tools-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertEquals("test", response.toString());
	}

	@Test
	public void testBuildConfigToolsMixedEntries() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = Maps.of("config", Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of(
				"v/ops/agent/create",
				Maps.of("operation", "v/ops/covia/read", "name", "read_data"),
				"v/ops/agent/list"
			)
		));

		ACell input = Maps.of(
			Fields.AGENT_ID, "mixed-tools-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
	}

	@Test
	public void testDefaultToolsFalse() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = Maps.of("config", Maps.of(
			"llmOperation", "v/test/ops/llm",
			"defaultTools", CVMBool.FALSE
		));

		ACell input = Maps.of(
			Fields.AGENT_ID, "no-defaults-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertEquals("test", response.toString());
	}

	@Test
	public void testDefaultToolsFalseWithCustomTools() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = Maps.of("config", Maps.of(
			"llmOperation", "v/test/ops/llm",
			"defaultTools", CVMBool.FALSE,
			"tools", Vectors.of("v/ops/agent/create")
		));

		ACell input = Maps.of(
			Fields.AGENT_ID, "custom-only-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
	}

	@Test
	public void testInvalidToolEntrySkipped() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Include invalid entries (number, bool) — should be skipped gracefully
		ACell state = Maps.of("config", Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of(
				CVMLong.create(42),       // invalid
				"v/ops/agent/create",           // valid
				CVMBool.TRUE,             // invalid
				"nonexistent:operation"   // valid format but won't resolve
			)
		));

		ACell input = Maps.of(
			Fields.AGENT_ID, "invalid-tools-agent",
			AgentState.KEY_STATE, state,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		// Should not throw — invalid entries silently skipped
		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
	}

	// ========== Helper ==========

	private void createTestAgent(String name) {
		ACell initialState = Maps.of("config", Maps.of("llmOperation", "v/test/ops/llm"));
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, name,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	// ========== Context load/unload tests ==========

	@Test public void testContextLoadHandler() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		assertEquals(0, ctx.loads.count());

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell result = adapter.handleContextLoad(
			Maps.of("path", "w/docs/rules", "budget", 1000L, "label", "Policy Rules"), ctx);
		assertTrue(result.toString().contains("loaded"));
		assertEquals(1, ctx.loads.count());
		assertNotNull(ctx.loads.get(Strings.create("w/docs/rules")));
	}

	@Test public void testContextLoadDefaultBudget() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/test"), ctx);

		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/test"));
		assertEquals(500L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
	}

	@Test public void testContextLoadBudgetClamped() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Over max
		adapter.handleContextLoad(Maps.of("path", "w/big", "budget", 99999L), ctx);
		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/big"));
		assertEquals(10_000L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());

		// Under min
		adapter.handleContextLoad(Maps.of("path", "w/tiny", "budget", 10L), ctx);
		AMap<AString, ACell> meta2 = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/tiny"));
		assertEquals(256L, ((CVMLong) meta2.get(Strings.create("budget"))).longValue());
	}

	@Test public void testContextLoadOverwritesSamePath() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/data", "budget", 500L, "label", "first"), ctx);
		adapter.handleContextLoad(Maps.of("path", "w/data", "budget", 1000L, "label", "second"), ctx);

		assertEquals(1, ctx.loads.count());
		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/data"));
		assertEquals(1000L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
		assertEquals("second", meta.get(Strings.create("label")).toString());
	}

	@Test public void testContextUnloadHandler() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/data"), ctx);
		assertEquals(1, ctx.loads.count());

		ACell result = adapter.handleContextUnload(Maps.of("path", "w/data"), ctx);
		assertTrue(result.toString().contains("unloaded"));
		assertEquals(0, ctx.loads.count());
	}

	@Test public void testContextUnloadNotFound() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell result = adapter.handleContextUnload(Maps.of("path", "w/missing"), ctx);
		assertTrue(result.toString().contains("Error"));
	}

	@Test public void testExtractLoadsRoundTrip() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/docs/rules"), Maps.of(Strings.create("budget"), CVMLong.create(500)));
		ACell state = Maps.of(Strings.create("loads"), loads);
		AMap<AString, ACell> extracted = LLMAgentAdapter.extractLoads(state);
		assertEquals(1, extracted.count());
		assertNotNull(extracted.get(Strings.create("w/docs/rules")));

		// Null state
		assertEquals(0, LLMAgentAdapter.extractLoads(null).count());
	}
}
