package covia.adapter.agent;

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
import covia.adapter.agent.LLMAgentAdapter;
import covia.adapter.agent.LLMAgentAdapter.ToolContext;
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

	/** Record config pointing at test:llm for level 3 — passed to transitions
	 *  via KEY_CONFIG, the single config slot (#144). State carries no config. */
	private static final ACell TEST_CONFIG = Maps.of("llmOperation", "v/test/ops/llm");

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	// ========== Direct invocation with test:llm ==========

	@Test
	public void testFirstRunWithConfig() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		assertNotNull(adapter);

		ACell input = Maps.of(
			Fields.AGENT_ID, "first-run-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
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

		// Response verified above — session.history is now the sole
		// conversation record (extractTranscript removed).
	}

	@Test
	public void testLevel3FailureFailsTransition() {
		// Regression: a level-3 op that completes with a failure VALUE
		// ({status: FAILED} from Status.failure — here an unresolvable API
		// key) must fail the transition with the provider's message, NOT be
		// mistaken for an empty assistant message and silently produce
		// response:"". Without the guard in AbstractLLMAdapter.invokeLevel3
		// the missing-key failure was swallowed into an empty response.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// apiKey points at a secret that does not exist → resolves to null →
		// the langchain op returns Status.failure rather than calling out.
		ACell config = Maps.of(
			"llmOperation", "v/ops/langchain/anthropic",
			"apiKey", "s/NONEXISTENT_TEST_KEY");
		ACell input = Maps.of(
			Fields.AGENT_ID, "no-key-agent",
			AgentState.KEY_CONFIG, config,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "Hello"))
		);

		covia.exception.JobFailedException ex = assertThrows(
			covia.exception.JobFailedException.class,
			() -> adapter.processChat(RequestContext.of(ALICE_DID), input));
		assertTrue(ex.getMessage() != null && ex.getMessage().contains("API key not found"),
			"Failure should name the missing API key, was: " + ex.getMessage());
	}

	@Test
	public void testMultiTurnConversation() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// First turn
		ACell input1 = Maps.of(
			Fields.AGENT_ID, "multi-turn-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "first message"))
		);
		ACell output1 = adapter.processChat(RequestContext.of(ALICE_DID), input1);
		ACell state1 = RT.getIn(output1, AgentState.KEY_STATE);

		// Second turn — pass state from first turn; config rides on the input
		// every turn (the framework always passes record.config, #144)
		ACell input2 = Maps.of(
			Fields.AGENT_ID, "multi-turn-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			AgentState.KEY_STATE, state1,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "second message"))
		);
		ACell output2 = adapter.processChat(RequestContext.of(ALICE_DID), input2);
		ACell state2 = RT.getIn(output2, AgentState.KEY_STATE);

		AString response = RT.ensureString(RT.getIn(output2, Fields.RESPONSE));
		assertEquals("second message", response.toString());
	}

	@Test
	public void testMultiTurnDoesNotAccumulateEphemeralContext() {
		// Verify that three successive processChat turns complete cleanly
		// and each echoes the correct user message (test:llm echoes last
		// user message). Session.history is now the sole conversation
		// record — no transcript assertions needed.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell state = Maps.empty();
		for (int i = 1; i <= 3; i++) {
			ACell input = Maps.of(
				Fields.AGENT_ID, "no-bloat-agent",
				AgentState.KEY_CONFIG, TEST_CONFIG,
				AgentState.KEY_STATE, state,
				Fields.MESSAGES, Vectors.of(Maps.of("content", "turn " + i))
			);
			ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
			state = RT.getIn(output, AgentState.KEY_STATE);

			AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
			assertNotNull(response, "Each turn should produce a response");
			assertEquals("turn " + i, response.toString());
		}
	}

	@Test
	public void testSystemPromptUpdatesAcrossTurnsAreNotFrozen() {
		// System prompt freeze bug (fixed). After
		// turn 1 the agent's stored state must NOT contain a system
		// message. The next turn rebuilds the system message fresh from
		// current config, so updates apply immediately.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// First turn
		ACell input1 = Maps.of(
			Fields.AGENT_ID, "fresh-prompt-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hi"))
		);
		ACell output1 = adapter.processChat(RequestContext.of(ALICE_DID), input1);
		ACell state1 = RT.getIn(output1, AgentState.KEY_STATE);

		// Verify processChat completed and produced a response
		AString response = RT.ensureString(RT.getIn(output1, Fields.RESPONSE));
		assertNotNull(response, "First turn should produce a response");
		assertEquals("hi", response.toString());

		// State carries no config (#144) and no frozen system message —
		// the next turn rebuilds the system prompt fresh from record.config.
		assertNull(RT.getIn(state1, AgentState.KEY_CONFIG),
			"state must not carry config");
	}

	@Test
	public void testMultipleInboxMessages() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "batch-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			Fields.MESSAGES, Vectors.of(
				Maps.of("content", "message one"),
				Maps.of("content", "message two"),
				Maps.of("content", "message three")
			)
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);

		// test:llm echoes last user message
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
		ACell initialConfig = Maps.of(
			"llmOperation", "v/test/ops/llm", "systemPrompt", "You are a pirate");

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "pirate-agent",
			AgentState.KEY_CONFIG, initialConfig,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "ahoy"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Custom system prompt applied — verify response is still produced
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Should produce a response with custom system prompt");
		assertEquals("ahoy", response.toString());
	}

	// ========== Integration: full agent pipeline ==========

	@Test
	public void testEndToEndWithAgentTrigger() {
		createTestAgent("e2e-agent");

		// Deliver directly to avoid auto-wake race
		User e2eUser = engine.getVenueState().users().get(ALICE_DID);
		AgentState e2eAgent = e2eUser.agent("e2e-agent");
		Blob e2eSid = Blob.fromHex("e2e10001e2e10001e2e10001e2e10001");
		e2eAgent.ensureSession(e2eSid, ALICE_DID);
		e2eAgent.appendSessionPending(e2eSid, Maps.of(
			Strings.intern("content"), Strings.create("Hello from e2e")));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "e2e-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);
		TestEngine.awaitTimelineCount(e2eAgent, 1, 10000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("e2e-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertFalse(agent.hasSessionPending());
		assertEquals(1, agent.getTimeline().count());

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
		AgentState multiAgent = multiUser.agent("multi-run-agent");
		Blob multiSid = Blob.fromHex("11110001111100011111000111110001");
		multiAgent.ensureSession(multiSid, ALICE_DID);
		AString multiSidHex = Strings.create(multiSid.toHexString());

		// First run — deliver directly to avoid auto-wake race
		multiAgent.appendSessionPending(multiSid, Maps.of(
			Strings.intern("content"), Strings.create("Turn 1")));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "multi-run-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(multiAgent, 1, 10000);

		// Second run
		multiAgent.appendSessionPending(multiSid, Maps.of(
			Strings.intern("content"), Strings.create("Turn 2")));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "multi-run-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(multiAgent, 2, 10000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-run-agent");

		assertEquals(2, agent.getTimeline().count());
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
		AgentState echoRegAgent = echoUser.agent("echo-regression");
		Blob echoRegSid = Blob.fromHex("22220001222200012222000122220001");
		echoRegAgent.ensureSession(echoRegSid, ALICE_DID);
		echoRegAgent.appendSessionPending(echoRegSid, Maps.of(
			Strings.intern("content"), Strings.create("hello")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "echo-regression"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	// ========== Config ==========

	/** Config has a single home (#144): agent:create rejects state.config loudly. */
	@Test
	public void testCreateRejectsStateConfig() {
		ACell initialState = Maps.of(
			"config", Maps.of("llmOperation", "v/test/ops/llm", "systemPrompt", "Custom prompt")
		);
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "config-agent", AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("agent:create must reject state.config");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			assertTrue(job.getErrorMessage().contains("state.config is not supported"),
				job.getErrorMessage());
		}
	}

	@Test
	public void testDefaultConfigFallbacks() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "minimal-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Verify processChat completes and produces a response
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Should produce a response with default config");
		assertEquals("test", response.toString());
	}

	// ========== Tool call loop ==========

	@Test
	public void testToolCallLoop() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tool-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake
		User toolUser = engine.getVenueState().users().get(ALICE_DID);
		AgentState toolAgent = toolUser.agent("tool-agent");
		Blob toolSid = Blob.fromHex("33330001333300013333000133330001");
		toolAgent.ensureSession(toolSid, ALICE_DID);
		toolAgent.appendSessionPending(toolSid, Maps.of(
			Strings.intern("content"), Strings.create("use a tool")));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "tool-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);
		TestEngine.awaitTimelineCount(toolAgent, 1, 10000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("tool-agent");

		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());
		AString response = RT.ensureString(RT.getIn(timeline.get(0), Fields.RESULT));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));
	}

	@Test
	public void testMalformedToolArgumentsProduceVisibleError() {
		// #89 acceptance: an LLM emitting broken tool arguments gets a
		// structured tool error it can react to on the next turn — the tool is
		// NEVER silently invoked with an empty map. test:badargsllm emits
		// garbage arguments, then echoes the tool result it receives.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "bad-args-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/badargsllm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("bad-args-agent");
		Blob sid = Blob.fromHex("44440001444400014444000144440001");
		agent.ensureSession(sid, ALICE_DID);
		agent.appendSessionPending(sid, Maps.of(
			Strings.intern("content"), Strings.create("go")));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "bad-args-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		AString response = RT.ensureString(
			RT.getIn(user.agent("bad-args-agent").getTimeline().get(0), Fields.RESULT));
		assertNotNull(response);
		assertTrue(response.toString().contains("Error:"),
			"the LLM must see a visible tool error for its malformed arguments, got: " + response);
		assertTrue(response.toString().contains("not valid JSON"),
			"the error must say WHY the arguments were rejected, got: " + response);
	}

	@Test
	public void testToolCallLoopDirect() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "direct-tool-agent",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "do something"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		assertTrue(response.toString().contains("Tool returned:"));
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
		ACell initialConfig = Maps.of(
			"llmOperation", "v/test/ops/llm", "responseFormat", responseFormat);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "format-agent",
			AgentState.KEY_CONFIG, initialConfig,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "summarise this"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// Config is not carried in state (#144) — the responseFormat reaching
		// the L3 call is exercised by the transition completing cleanly.
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response, "Should produce a response with a responseFormat config");
	}

	@Test
	public void testResponseFormatJsonString() {
		ACell initialConfig = Maps.of(
			"llmOperation", "v/test/ops/llm", "responseFormat", "json");

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "json-format-agent",
			AgentState.KEY_CONFIG, initialConfig,
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
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm")
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
		TestEngine.awaitTimelineCount(agent, 1, 10000);

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
	public void testCappedAgentCanCompleteTask() {
		// Regression (covia#71 live testing): the task-lifecycle ops are
		// self-scoped (agentId/taskId from the RequestContext) and must stay
		// callable under a restricted config ceiling. A blanket invoke gate on
		// AgentAdapter.invokeFuture once denied complete_task to any capped
		// agent, trapping every capped worker in the tool loop until the
		// iteration limit — a capped pipeline could never finish a task.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "capped-task-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					// Restricted ceiling: one read grant, no invoke ability.
					"caps", Vectors.of(Maps.of(
						"with", Strings.create("w/allowed/"),
						"can", Strings.create("crud/read"))))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("capped-task-agent");
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of("question", "What is 2+2?"));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "capped-task-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getTasks().count(),
			"a capped agent must be able to complete its own task");
		assertNotNull(RT.getIn(agent.getTimeline().get(0), Fields.TASK_RESULTS),
			"the completion must be recorded, not the iteration-limit failure");
	}

	// ========== #217 — token usage accounting ==========

	@Test
	@SuppressWarnings("unchecked")
	public void testProcessChatOutputCarriesTokens() {
		// Mock L3 ops report UTF-8-length usage; the cycle total must ride
		// the transition output with the total == input + output invariant.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "tokens-direct-agent",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/llm"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hello tokens")));
		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		AMap<AString, ACell> tokens = (AMap<AString, ACell>) RT.getIn(output, Fields.TOKENS);
		assertNotNull(tokens, "measured usage must ride the transition output");
		long in = RT.ensureLong(RT.getIn(tokens, Fields.INPUT)).longValue();
		long out = RT.ensureLong(RT.getIn(tokens, Fields.OUTPUT)).longValue();
		long total = RT.ensureLong(RT.getIn(tokens, Fields.TOTAL)).longValue();
		assertTrue(in > 0, "prompt side must be measured");
		assertTrue(out > 0, "completion side must be measured");
		assertEquals(in + out, total);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testTokensOnTimelineSessionJobAndContext() throws Exception {
		// The full accounting pipeline over two chat cycles: timeline entries
		// carry per-cycle usage, session meta.tokens accumulates across
		// cycles, the chat job record is stamped, and agent:context renders
		// the measured session totals.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tokens-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chat1 = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "tokens-agent",
				Fields.MESSAGE, Strings.create("first message")),
			RequestContext.of(ALICE_DID));
		ACell r1 = chat1.awaitResult(10000);
		AString sid = RT.ensureString(RT.getIn(r1, Fields.SESSION_ID));
		assertNotNull(sid);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("tokens-agent");
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		AMap<AString, ACell> t1 = (AMap<AString, ACell>) RT.getIn(
			agent.getTimeline().get(0), Fields.TOKENS);
		assertNotNull(t1, "timeline entry must record the cycle's tokens");
		long total1 = RT.ensureLong(RT.getIn(t1, Fields.TOTAL)).longValue();
		assertTrue(total1 > 0);
		assertEquals(RT.ensureLong(RT.getIn(t1, Fields.INPUT)).longValue()
			+ RT.ensureLong(RT.getIn(t1, Fields.OUTPUT)).longValue(), total1);

		assertEquals(t1, RT.getIn(chat1.getData(), Fields.TOKENS),
			"chat job record must carry the cycle's tokens");

		Blob sidBlob = Blob.fromHex(sid.toString());
		long sessTotal1 = RT.ensureLong(RT.getIn(
			agent.getSession(sidBlob), "meta", "tokens", "total")).longValue();
		assertEquals(total1, sessTotal1, "session meta mirrors the first cycle");

		// Second cycle on the SAME session — totals must accumulate
		engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "tokens-agent",
				Fields.MESSAGE, Strings.create("second, longer message"),
				Fields.SESSION_ID, sid),
			RequestContext.of(ALICE_DID)).awaitResult(10000);
		TestEngine.awaitTimelineCount(agent, 2, 10000);

		AMap<AString, ACell> t2 = (AMap<AString, ACell>) RT.getIn(
			agent.getTimeline().get(1), Fields.TOKENS);
		assertNotNull(t2, "second cycle must also be measured");
		long total2 = RT.ensureLong(RT.getIn(t2, Fields.TOTAL)).longValue();
		long sessTotal = RT.ensureLong(RT.getIn(
			agent.getSession(sidBlob), "meta", "tokens", "total")).longValue();
		assertEquals(total1 + total2, sessTotal,
			"session totals accumulate across cycles");

		// agent:context surfaces the measured session usage
		ACell rendered = engine.jobs().invokeOperation(
			"v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "tokens-agent", Fields.SESSION_ID, sid),
			RequestContext.of(ALICE_DID)).awaitResult(10000);
		assertTrue(rendered.toString().contains("Session token usage (measured)"),
			"context render must include the measured session totals");
	}

	@Test
	public void testTaskJobRecordCarriesTokens() throws Exception {
		// A caller polling a task job must see what the work cost — the
		// tokens field rides the persisted job record (#217).
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "tokens-task-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "tokens-task-agent",
				Fields.INPUT, Maps.of("task", "answer the question"),
				Strings.intern("timeout"), CVMLong.create(0)),
			RequestContext.of(ALICE_DID));
		ACell snapshot = reqJob.awaitResult(5000);
		Blob taskJobId = Job.parseID(RT.getIn(snapshot, Fields.ID));
		assertNotNull(taskJobId, "async request snapshot must carry the task job id");

		// Poll the persisted record, not the active cache — a finished job is
		// evicted from the cache and lives on as its lattice record.
		AMap<AString, ACell> record = null;
		long deadline = System.currentTimeMillis() + 15000;
		while (System.currentTimeMillis() < deadline) {
			record = engine.jobs().getJobData(taskJobId, RequestContext.of(ALICE_DID));
			if (record != null && Job.isFinished(record)) break;
			Thread.sleep(25);
		}
		assertNotNull(record, "task job record must exist");
		assertEquals(Status.COMPLETE, RT.getIn(record, Fields.STATUS));
		ACell jobTokens = RT.getIn(record, Fields.TOKENS);
		assertNotNull(jobTokens, "task job record must carry the cycle's token usage");
		assertTrue(RT.ensureLong(RT.getIn(jobTokens, Fields.TOTAL)).longValue() > 0);
	}

	// ========== #215 — textual control tools, task rendering, terminal cap ==========

	@Test
	@SuppressWarnings("unchecked")
	public void testRecogniseTextualControlCall() {
		// The reported small-model pattern: control tool as plain text + JSON args
		AMap<AString, ACell> msg = Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("fail_task {\"error\": \"Task details not provided for resolution.\"}"));
		AMap<AString, ACell> rewritten = LLMAgentAdapter.recogniseTextualControlCall(msg, 3);
		assertNotNull(rewritten, "textual fail_task with JSON args must be recognised");
		AVector<ACell> calls = (AVector<ACell>) RT.getIn(rewritten, "toolCalls");
		assertEquals(1, calls.count());
		assertEquals("fail_task", RT.getIn(calls.get(0), "name").toString());
		assertEquals("Task details not provided for resolution.",
			RT.getIn(calls.get(0), "arguments", "error").toString());
		// Original text preserved as content — the transcript stays honest
		assertEquals(RT.getIn(msg, "content"), RT.getIn(rewritten, "content"));

		// complete_task with nested JSON result
		AMap<AString, ACell> ct = Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("complete_task {\"result\": {\"answer\": 42}}"));
		AMap<AString, ACell> ctRe = LLMAgentAdapter.recogniseTextualControlCall(ct, 0);
		assertNotNull(ctRe);
		AVector<ACell> ctCalls = (AVector<ACell>) RT.getIn(ctRe, "toolCalls");
		assertEquals("complete_task", RT.getIn(ctCalls.get(0), "name").toString());

		// Colon form
		assertNotNull(LLMAgentAdapter.recogniseTextualControlCall(Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("fail_task: {\"error\": \"nope\"}")), 0));

		// Bare tool name → empty args (the tool's own validation responds)
		AMap<AString, ACell> bare = LLMAgentAdapter.recogniseTextualControlCall(Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("complete_task")), 0);
		assertNotNull(bare);

		// Prose mentioning a tool name is NOT a call
		assertNull(LLMAgentAdapter.recogniseTextualControlCall(Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("complete_task is unavailable to me right now")), 0));
		// Ordinary text is untouched
		assertNull(LLMAgentAdapter.recogniseTextualControlCall(Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("The answer is 4.")), 0));
		// Malformed JSON after the tool name is left alone
		assertNull(LLMAgentAdapter.recogniseTextualControlCall(Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("fail_task {not json")), 0));
	}

	@Test
	public void testRenderTaskTextNeverEDN() {
		// Strings pass through verbatim
		assertEquals("Pay invoice AR-2214",
			LLMAgentAdapter.renderTaskText(Strings.create("Pay invoice AR-2214")));
		// Structured input renders as JSON that round-trips — never the EDN
		// {"k" "v"} form models misread (#215)
		ACell input = Maps.of("message", Strings.create("Pay invoice AR-2214"));
		String rendered = LLMAgentAdapter.renderTaskText(input);
		assertEquals(input, convex.core.util.JSON.parse(rendered),
			"rendered task text must be valid JSON preserving the input");
		assertTrue(rendered.contains("\"message\":"),
			"JSON key-colon form expected, got: " + rendered);
		assertEquals("", LLMAgentAdapter.renderTaskText(null));
	}

	@Test
	public void testTextualCompleteTaskEndToEnd() {
		// test:textctlllm emits complete_task as plain TEXT (the #215 qwen2.5
		// behaviour). The fallback must honour it and resolve the task.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "textctl-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/textctlllm")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("textctl-agent");
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of("task", "Pay invoice AR-2214"));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "textctl-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getTasks().count(),
			"textually-emitted complete_task must resolve the task");
		assertNotNull(RT.getIn(agent.getTimeline().get(0), Fields.TASK_RESULTS),
			"the completion must be recorded on the timeline");
	}

	@Test
	public void testStuckTaskFailsTerminallyAtLoopCap() throws Exception {
		// test:stubbornllm never resolves the task: every cycle is a plain-text
		// reply, so the run loop burns its whole iteration budget on one task.
		// The cap must be terminal — the caller's job FAILED with a structured
		// error and the task removed — not a sleep the wake re-check turns into
		// another full-budget burn while the job pins STARTED (#215).
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "stubborn-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/stubbornllm")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Real task job via agent:request — the pinned-STARTED symptom needs an
		// actual caller job to observe. The awaiting caller must see FAILED
		// with the structured error, never an eternal STARTED.
		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "stubborn-agent",
				Fields.INPUT, Maps.of("task", "impossible request")),
			RequestContext.of(ALICE_DID));
		Exception ex = assertThrows(Exception.class, () -> reqJob.awaitResult(30000),
			"the caller's await must terminate in failure, not hang");
		assertTrue(ex.getMessage().contains("loop iterations"),
			"error must say what happened, got: " + ex.getMessage());
		assertTrue(reqJob.isFinished(), "job must not stay STARTED forever");
		assertEquals(Status.FAILED, reqJob.getStatus());

		// The stuck task is gone; the agent is not poisoned
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("stubborn-agent");
		assertEquals(0, agent.getTasks().count(), "stuck task must be removed");
		TestEngine.awaitAgentIdle(agent, 10000);
	}

	@Test
	public void testScopedInvokeAllowsInScopeTool() {
		// #211: an invoke grant scoped to an op-path prefix admits tool calls
		// under it. toolllm makes one call to v/test/ops/echo, then reports
		// the tool result as its text response.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "scoped-ok-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm",
					"caps", Vectors.of(Maps.of(
						"with", Strings.create("v/test/ops"),
						"can", Strings.create("invoke"))))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "scoped-ok-agent",
				Fields.MESSAGE, Strings.create("use your tool")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(10000);
		String response = RT.getIn(result, Fields.RESPONSE).toString();
		assertTrue(response.contains("Tool returned"),
			"in-scope tool call must execute: " + response);
		assertFalse(response.contains("Error:"),
			"in-scope tool call must execute cleanly, not be denied: " + response);
	}

	@Test
	public void testScopedInvokeDeniesOutOfScopeTool() {
		// #211: the same agent under an invoke grant that does NOT cover the
		// tool op — the tool call is denied, the denial (naming the op) is fed
		// to the model, and the agent handles it in one cycle: no looping to
		// the iteration limit, agent back to SLEEPING.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "scoped-deny-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/toolllm",
					"caps", Vectors.of(Maps.of(
						"with", Strings.create("v/ops/schema"),
						"can", Strings.create("invoke"))))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "scoped-deny-agent",
				Fields.MESSAGE, Strings.create("use your tool")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(10000);
		String response = RT.getIn(result, Fields.RESPONSE).toString();
		assertTrue(response.contains("Capability denied"),
			"the denial must reach the model as the tool result: " + response);
		assertTrue(response.contains("v/test/ops/echo"),
			"the denial must name the blocked op: " + response);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("scoped-deny-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"a handled denial is not an agent failure — no suspension, no loop");
	}

	@Test
	public void testMaxToolIterationsPerAgentOverride() {
		// The iteration limit is config-driven: venue default 30, overridable
		// per agent via config.maxToolIterations. A tight limit of 3 fails a
		// loopllm agent fast, with the effective limit named in the error.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "tight-loop-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/loopllm",
					"maxToolIterations", CVMLong.create(3))
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job taskJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "tight-loop-agent",
				Fields.INPUT, Maps.of("task", "loop")),
			RequestContext.of(ALICE_DID));

		assertThrows(Exception.class, () -> taskJob.awaitResult(15000));
		assertEquals(Status.FAILED, taskJob.getStatus());
		String err = taskJob.getErrorMessage();
		assertTrue(err.contains("iteration limit (3)"),
			"the error must name the agent's configured limit: " + err);
	}

	@Test
	public void testToolLoopLimitFailsTask() {
		// Agent whose LLM (loopllm) ALWAYS tool-calls and never completes the
		// task — the tool loop runs to MAX_TOOL_ITERATIONS. The task Job must
		// transition to FAILED (the agent gave up) rather than hang STARTED
		// forever behind a fake-success apology (covia-ai/covia#138). The
		// iteration cap bounds CPU/IO; this asserts the give-up is terminal.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "loop-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/loopllm")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// agent:request — the request Job IS the task Job (taskId == job id).
		Job taskJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "loop-agent",
				Fields.INPUT, Maps.of("task", "do something")),
			RequestContext.of(ALICE_DID));

		// The agent runs, hits the iteration limit, fails the transition →
		// the task fails (awaitResult throws on a FAILED Job).
		assertThrows(Exception.class, () -> taskJob.awaitResult(15000),
			"a task the agent gives up on (iteration limit) must fail, not hang STARTED");
		assertEquals(Status.FAILED, taskJob.getStatus(),
			"task Job must transition to FAILED on agent give-up");

		// The give-up is a transition failure, so the agent suspends with the
		// error recorded (resumable via agent:resume) — its thread is freed and
		// the task resolved, which is the point. An agent that loops to its
		// tool-call safety limit is misbehaving, so parking it is appropriate.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("loop-agent");
		assertEquals(AgentState.SUSPENDED, agent.getStatus(),
			"agent suspends (error recorded, resumable, thread freed) after giving up");
	}

	@Test
	public void testCompleteTaskDirect() {
		// Test complete_task via processChat directly (no agent:request pipeline).
		// Since the venue op `agent:completeTask` reads (agentId, taskId) from
		// RequestContext and requires the agent + task to exist in the lattice,
		// we set those up first and scope the ctx accordingly.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		AString agentId = Strings.create("direct-task-agent");
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, agentId,
				AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/taskllm"),
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("direct-task-agent");
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of("question", "test?"));

		ACell input = Maps.of(
			Fields.AGENT_ID, agentId,
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/taskllm"),
			Fields.TASKS, Vectors.of(Maps.of(
				Fields.JOB_ID, Strings.create(taskId.toHexString()),
				Fields.INPUT, Maps.of("question", "test?")
			)),
			Fields.MESSAGES, Vectors.empty()
		);

		// Scope the ctx like the framework does per-cycle so the venue op can
		// read agentId + taskId from the RequestContext.
		RequestContext ctx = RequestContext.of(ALICE_DID)
			.withAgentId(agentId)
			.withTaskId(taskId);

		ACell output = adapter.processChat(ctx, input);
		assertNotNull(output);

		// New contract: the LLM tool wrapper invokes the venue op (which
		// removes the task entry and parks a deferred completion). The
		// transition output carries {state, response} only — no
		// taskComplete flag. The framework drains deferred completions
		// after the cycle's mergeRunResult.
		assertNull(RT.getIn(output, Fields.TASK_COMPLETE),
			"taskComplete flag must no longer appear on transition output");
		assertNotNull(RT.getIn(output, Fields.RESPONSE),
			"Output should carry the task's structured response");
		// Venue op should have removed the task entry from the agent's Index
		assertNull(agent.getTasks().get(taskId),
			"Task entry should be removed by the venue op");
	}

	// ========== Built-in tools: invoke ==========

	@Test
	public void testInvokeToolCallLoop() {
		// test:toolllm calls test:echo via tool call — this exercises the invoke path
		// since test:echo is not a built-in tool, it falls through to grid dispatch
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "invoke-agent",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/toolllm"),
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
		assertFalse(receiver.hasSessionPending());

		// Deliver via agent:message (existing path) to verify receiver works.
		// Delivery is verified through the op's result envelope — the receiver's
		// run loop wakes on delivery and may drain session.pending before this
		// test could read it, so internal pending state is not assertable here.
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "receiver-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELIVERED));
	}

	// ========== Default tools are merged ==========

	@Test
	public void testDefaultToolsPresent() {
		// Verify that processChat passes default tools to level 3
		// Use test:llm which echoes — we just verify it doesn't crash with tools
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "tools-check",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/llm"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hello"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);

		// The LLM should still respond normally with default tools present
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertEquals("hello", response.toString());
	}

	// extractConfig removed with the config/state.config collapse (#144) —
	// config has a single home (record.config) and is never read from state.

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

	// ========== Dispatch consistency — no internal coercion (#89) ==========
	// parseToolArguments (the wire-boundary parse) is covered in
	// AbstractLLMAdapterTest; these lock in that INTERNAL dispatch preserves
	// types exactly and behaves identically on both tool-dispatch paths.

	@Test
	public void testConfigAndGridDispatchPreserveInputIdentically() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		RequestContext ctx = RequestContext.of(ALICE_DID);
		AMap<AString, ACell> input = Maps.of("agentId", "Charlie", "n", CVMLong.create(7));

		// Config-mapped path: LLM tool name resolves through configToolMap.
		java.util.Map<String, AString> toolMap = new java.util.HashMap<>();
		toolMap.put("my_echo", Strings.create("v/test/ops/echo"));
		ACell viaConfig = adapter.dispatchTool("my_echo", input, toolMap, ctx, 5000);

		// Grid-dispatch path: tool name IS the op reference.
		ACell viaGrid = adapter.dispatchTool("v/test/ops/echo", input,
			new java.util.HashMap<>(), ctx, 5000);

		// Same op, same input, both paths: identical result — the
		// Bob/Charlie divergence (#89) cannot recur.
		assertEquals(input, viaConfig, "config path must pass the input through exactly");
		assertEquals(viaConfig, viaGrid, "both dispatch paths must behave identically");
	}

	@Test
	public void testInternalDispatchDoesNotReparseStrings() {
		// A string input to internal dispatch STAYS a string — even when it
		// looks like JSON. Normalisation happens once at the LLM wire
		// boundary, never inside the dispatch chain.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		RequestContext ctx = RequestContext.of(ALICE_DID);
		AString jsonish = Strings.create("{\"key\":\"value\"}");

		ACell result = adapter.dispatchTool("v/test/ops/echo", jsonish,
			new java.util.HashMap<>(), ctx, 5000);
		assertEquals(jsonish, result, "internal dispatch must not silently parse string inputs");
	}

	// ========== Pure function: parseConfigToolEntry ==========

	@Test
	public void testParseConfigToolEntryString() {
		AString[] parsed = ContextBuilder.parseConfigToolEntry(Strings.create("v/ops/agent/create"));
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
		AString[] parsed = ContextBuilder.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/http/get", parsed[0].toString());
		assertEquals("fetch_url", parsed[1].toString());
		assertEquals("Fetch a URL", parsed[2].toString());
	}

	@Test
	public void testParseConfigToolEntryMapMinimal() {
		ACell entry = Maps.of("operation", "v/ops/agent/list");
		AString[] parsed = ContextBuilder.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/agent/list", parsed[0].toString());
		assertNull(parsed[1]);
		assertNull(parsed[2]);
	}

	@Test
	public void testParseConfigToolEntryMapMissingOperation() {
		ACell entry = Maps.of("name", "orphan_tool");
		assertNull(ContextBuilder.parseConfigToolEntry(entry));
	}

	@Test
	public void testParseConfigToolEntryInvalidType() {
		assertNull(ContextBuilder.parseConfigToolEntry(CVMLong.create(42)));
		assertNull(ContextBuilder.parseConfigToolEntry(CVMBool.TRUE));
	}

	@Test
	public void testParseConfigToolEntryNull() {
		assertNull(ContextBuilder.parseConfigToolEntry(null));
	}

	// ========== Pure function: deriveToolName ==========

	@Test
	public void testDeriveToolNameOverrideWins() {
		String name = ContextBuilder.deriveToolName(
			Strings.create("my_tool"),
			Strings.create("asset_tool"),
			Strings.create("adapter:op"));
		assertEquals("my_tool", name);
	}

	@Test
	public void testDeriveToolNameAssetToolNameWins() {
		String name = ContextBuilder.deriveToolName(
			null,
			Strings.create("asset_tool"),
			Strings.create("adapter:op"));
		assertEquals("asset_tool", name);
	}

	@Test
	public void testDeriveToolNameFallbackColonToUnderscore() {
		String name = ContextBuilder.deriveToolName(
			null, null, Strings.create("agent:create"));
		assertEquals("agent_create", name);
	}

	@Test
	public void testDeriveToolNameFallbackSlashToUnderscore() {
		String name = ContextBuilder.deriveToolName(
			null, null, Strings.create("did:venue:user/o/my-tool"));
		assertEquals("did_venue_user_o_my-tool", name);
	}

	@Test
	public void testDeriveToolNameNoSpecialChars() {
		String name = ContextBuilder.deriveToolName(
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
		AMap<AString, ACell> def = ContextBuilder.buildToolDefinition(
			"fetch_url", Strings.create("Fetch a URL"), schema);

		assertEquals(Strings.create("fetch_url"), def.get(Strings.intern("name")));
		assertEquals(Strings.create("Fetch a URL"), def.get(Strings.intern("description")));
		assertSame(schema, def.get(Strings.intern("parameters")));
	}

	@Test
	public void testBuildToolDefinitionNullSchema() {
		AMap<AString, ACell> def = ContextBuilder.buildToolDefinition(
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
		AMap<AString, ACell> def = ContextBuilder.buildToolDefinition("tool", null, schema);

		assertEquals(Strings.create("tool"), def.get(Strings.intern("name")));
		assertNull(def.get(Strings.intern("description")));
	}

	@Test
	public void testBuildToolDefinitionStringSchema() {
		// Non-map schema (e.g. a string) should get default object schema
		AMap<AString, ACell> def = ContextBuilder.buildToolDefinition(
			"tool", null, Strings.create("bad-schema"));
		ACell params = def.get(Strings.intern("parameters"));
		assertEquals(Strings.create("object"), RT.getIn(params, "type"));
	}

	// ========== Pure function: buildOutstandingTaskMessage ==========

	@Test
	public void testBuildOutstandingTaskMessageNoTasks() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		assertNull(LLMAgentAdapter.buildOutstandingTaskMessage(ctx));
	}

	@Test
	public void testBuildOutstandingTaskMessageEmptyTasks() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, Vectors.empty(), null, null, null);
		assertNull(LLMAgentAdapter.buildOutstandingTaskMessage(ctx));
	}

	@Test
	public void testBuildOutstandingTaskMessageAllResolved() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "task1")
		);
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null);
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
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null);
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
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null);

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
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
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
		ACell config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of("v/ops/agent/create", "v/ops/agent/list")
		);

		ACell input = Maps.of(
			Fields.AGENT_ID, "custom-tools-agent",
			AgentState.KEY_CONFIG, config,
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

		ACell config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of(
				Maps.of("operation", "v/ops/agent/create",
					"name", "make_agent",
					"description", "Create a new agent")
			)
		);

		ACell input = Maps.of(
			Fields.AGENT_ID, "map-tools-agent",
			AgentState.KEY_CONFIG, config,
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

		ACell config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of(
				"v/ops/agent/create",
				Maps.of("operation", "v/ops/covia/read", "name", "read_data"),
				"v/ops/agent/list"
			)
		);

		ACell input = Maps.of(
			Fields.AGENT_ID, "mixed-tools-agent",
			AgentState.KEY_CONFIG, config,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
	}

	@Test
	public void testDefaultToolsFalse() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"defaultTools", CVMBool.FALSE
		);

		ACell input = Maps.of(
			Fields.AGENT_ID, "no-defaults-agent",
			AgentState.KEY_CONFIG, config,
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

		ACell config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"defaultTools", CVMBool.FALSE,
			"tools", Vectors.of("v/ops/agent/create")
		);

		ACell input = Maps.of(
			Fields.AGENT_ID, "custom-only-agent",
			AgentState.KEY_CONFIG, config,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
	}

	@Test
	public void testInvalidToolEntrySkipped() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Include invalid entries (number, bool) — should be skipped gracefully
		ACell config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"tools", Vectors.of(
				CVMLong.create(42),       // invalid
				"v/ops/agent/create",           // valid
				CVMBool.TRUE,             // invalid
				"nonexistent:operation"   // valid format but won't resolve
			)
		);

		ACell input = Maps.of(
			Fields.AGENT_ID, "invalid-tools-agent",
			AgentState.KEY_CONFIG, config,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "test"))
		);

		// Should not throw — invalid entries silently skipped
		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertNotNull(output);
	}

	// ========== Helper ==========

	private void createTestAgent(String name) {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, name,
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	// ========== Context load/unload tests ==========

	@Test public void testContextLoadHandler() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		assertEquals(0, ctx.loads.count());

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell result = adapter.handleContextLoad(
			Maps.of("path", "w/docs/rules", "budget", 1000L, "label", "Policy Rules"), ctx);
		assertTrue(result.toString().contains("loaded"));
		assertEquals(1, ctx.loads.count());
		assertNotNull(ctx.loads.get(Strings.create("w/docs/rules")));
	}

	@Test public void testContextLoadDefaultBudget() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/test"), ctx);

		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/test"));
		assertEquals(500L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
	}

	@Test public void testContextLoadBudgetClamped() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
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
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/data", "budget", 500L, "label", "first"), ctx);
		adapter.handleContextLoad(Maps.of("path", "w/data", "budget", 1000L, "label", "second"), ctx);

		assertEquals(1, ctx.loads.count());
		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/data"));
		assertEquals(1000L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
		assertEquals("second", meta.get(Strings.create("label")).toString());
	}

	@Test public void testContextUnloadHandler() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/data"), ctx);
		assertEquals(1, ctx.loads.count());

		ACell result = adapter.handleContextUnload(Maps.of("path", "w/data"), ctx);
		assertTrue(result.toString().contains("unloaded"));
		assertEquals(0, ctx.loads.count());
	}

	@Test public void testContextUnloadNotFound() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell result = adapter.handleContextUnload(Maps.of("path", "w/missing"), ctx);
		assertTrue(result.toString().contains("Error"));
	}

	// ========== Context scope chain (#142) ==========

	/** context_unload masks an operator-pinned load with a nil tombstone —
	 *  this conversation only; the pin itself is untouched. */
	@Test public void testUnloadMasksConfigLoad() {
		ToolContext toolCtx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		toolCtx.outerLoads = Maps.of(Strings.create("w/pinned"),
			Maps.of(Strings.create("budget"), CVMLong.create(400)));
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell result = adapter.handleContextUnload(Maps.of("path", "w/pinned"), toolCtx);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "unloaded"), "result: " + result);
		assertTrue(toolCtx.loads.containsKey(Strings.create("w/pinned")), "tombstone written");
		assertNull(toolCtx.loads.get(Strings.create("w/pinned")));

		// Re-loading overwrites the tombstone (local un-mask).
		ACell reload = adapter.handleContextLoad(Maps.of("path", "w/pinned"), toolCtx);
		assertEquals(CVMBool.TRUE, RT.getIn(reload, "loaded"), "result: " + reload);
		assertNotNull(toolCtx.loads.get(Strings.create("w/pinned")));
	}

	/** A cycle with no session in scope has no writable tier. */
	@Test public void testContextToolsRequireSession() {
		ToolContext toolCtx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		toolCtx.sessionInScope = false;
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		assertTrue(adapter.handleContextLoad(Maps.of("path", "w/x"), toolCtx)
			.toString().contains("no session in scope"));
		assertTrue(adapter.handleContextUnload(Maps.of("path", "w/x"), toolCtx)
			.toString().contains("no session in scope"));
	}

	/** The transition output carries the session tier for the framework's
	 *  session merge — and only when a session is in scope. */
	@Test public void testProcessChatEmitsSessionLoads() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> tier = Maps.of(Strings.create("w/notes"),
			Maps.of(Strings.create("budget"), CVMLong.create(300)));

		ACell withSession = adapter.processChat(RequestContext.of(ALICE_DID), Maps.of(
			Fields.AGENT_ID, "loads-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			Fields.SESSION, Maps.of(Fields.SESSION_ID, Strings.create("aa00"), Fields.LOADS, tier),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hi"))));
		assertEquals(tier, RT.getIn(withSession, Fields.LOADS),
			"session tier rides the output for the framework merge");

		ACell withoutSession = adapter.processChat(RequestContext.of(ALICE_DID), Maps.of(
			Fields.AGENT_ID, "loads-agent",
			AgentState.KEY_CONFIG, TEST_CONFIG,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hi"))));
		assertNull(RT.getIn(withoutSession, Fields.LOADS),
			"no session in scope → no loads on the output");
	}

	// ========== Wrong-runtime harness tool calls fail diagnosably (#143) ==========

	/**
	 * A call to another runtime's harness tool is a normal runtime tool failure —
	 * but the error must name the actual reason ("goaltree harness tool"), not the
	 * generic "cannot resolve operation" a catalog miss produces. The tool result
	 * is the agent-visible surface; venue logs are not.
	 */
	@Test
	public void testWrongRuntimeHarnessToolFailsDiagnosably() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		RequestContext ctx = RequestContext.of(ALICE_DID);

		ACell result = adapter.dispatchTool("subgoal", Maps.empty(), Map.of(), ctx, 1000);
		String msg = RT.ensureString(result).toString();
		assertTrue(msg.startsWith("Error:"), msg);
		assertTrue(msg.contains("goaltree harness tool"), msg);
		assertTrue(msg.contains("llmagent"), msg);

		// The reverse direction: llmagent's harness names under goaltree.
		GoalTreeAdapter goaltree = (GoalTreeAdapter) engine.getAdapter("goaltree");
		ACell reverse = goaltree.dispatchTool("complete_task", Maps.empty(), Map.of(), ctx, 1000);
		String reverseMsg = RT.ensureString(reverse).toString();
		assertTrue(reverseMsg.contains("llmagent harness tool"), reverseMsg);
		assertTrue(reverseMsg.contains("goaltree"), reverseMsg);

		// A name shared by both runtimes reports both providers.
		ACell shared = adapter.dispatchTool("context_load", Maps.empty(), Map.of(), ctx, 1000);
		String sharedMsg = RT.ensureString(shared).toString();
		assertTrue(sharedMsg.contains("goaltree, llmagent"), sharedMsg);

		// A genuinely unknown name keeps the ordinary resolution failure.
		ACell unknown = adapter.dispatchTool("no_such_tool_xyz", Maps.empty(), Map.of(), ctx, 1000);
		String unknownMsg = RT.ensureString(unknown).toString();
		assertTrue(unknownMsg.startsWith("Error:"), unknownMsg);
		assertFalse(unknownMsg.contains("harness tool"), unknownMsg);
	}
}
