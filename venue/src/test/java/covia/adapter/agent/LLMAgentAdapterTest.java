package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
import covia.adapter.TestAdapter;
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

	// ========== L3 LLM timeout ==========

	@Test
	public void testLlmTimeoutFailsTransition() {
		// A hung provider call (test:never never completes) must fail the
		// transition after llmTimeoutMs, not park the run loop forever.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell config = Maps.of(
			"llmOperation", "v/test/ops/never",
			"llmTimeoutMs", 1000);
		ACell input = Maps.of(
			Fields.AGENT_ID, "llm-timeout-agent",
			AgentState.KEY_CONFIG, config,
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hang forever")));

		long start = System.currentTimeMillis();
		covia.exception.JobFailedException e = assertThrows(
			covia.exception.JobFailedException.class,
			() -> adapter.processChat(RequestContext.of(ALICE_DID), input));
		assertTrue(e.getMessage().contains("timed out"),
			"expected a timeout failure, got: " + e.getMessage());
		assertTrue(System.currentTimeMillis() - start < 60_000,
			"timeout must bound the wait");
	}

	@Test
	public void testResolveLlmTimeoutMs() {
		assertEquals(AbstractLLMAdapter.DEFAULT_LLM_TIMEOUT_MS,
			AbstractLLMAdapter.resolveLlmTimeoutMs(null));
		// Below the 1s minimum → default
		assertEquals(AbstractLLMAdapter.DEFAULT_LLM_TIMEOUT_MS,
			AbstractLLMAdapter.resolveLlmTimeoutMs(Maps.of("llmTimeoutMs", 10)));
		assertEquals(5000L,
			AbstractLLMAdapter.resolveLlmTimeoutMs(Maps.of("llmTimeoutMs", 5000)));
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
	public void testLengthLimitedResponseRetriesWithoutUsingPartialOutput() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "length-retry-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "length-then-answer-test"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "answer fully")));

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertEquals("complete after truncation retry", RT.getIn(output, Fields.RESPONSE).toString());
		AVector<ACell> turns = RT.ensureVector(RT.getIn(output, Fields.TURNS));
		assertNotNull(turns);
		assertEquals(1, turns.count(), "only the retry diagnostic precedes the final answer");
		assertEquals("system", RT.getIn(turns.get(0), "role").toString());
		assertNull(RT.getIn(turns.get(0), "toolCalls"), "partial tool calls must not execute or persist");
		assertEquals(2, RT.ensureVector(RT.getIn(output, Fields.CYCLE, Fields.INFERENCES)).count());
	}

	@Test
	public void testRepeatedLengthLimitedResponseFailsClearly() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "length-failure-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"model", "always-length-test"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "answer fully")));

		covia.exception.JobFailedException failure = assertThrows(
			covia.exception.JobFailedException.class,
			() -> adapter.processChat(RequestContext.of(ALICE_DID), input));
		assertTrue(failure.getMessage().contains("Increase maxTokens"), failure.getMessage());
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
		// not persisted to the transcript — see ContextAssemblerTest
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

		AVector<ACell> turns = RT.ensureVector(
			RT.getIn(output, Fields.TURNS));
		assertNotNull(turns, "tool exchanges must be emitted for session audit");
		assertEquals(2, turns.count(),
			"terminal assistant stays in response; only tool-call + result are emitted");
		assertEquals("assistant", RT.getIn(turns.get(0), "role").toString());
		assertNotNull(RT.getIn(turns.get(0), "toolCalls"));
		assertEquals("tool", RT.getIn(turns.get(1), "role").toString());
	}

	@Test
	public void testCompactUsesSharedFrameRepresentation() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), Maps.of(
			Fields.AGENT_ID, "flat-compact-agent",
			AgentState.KEY_CONFIG, Maps.of("llmOperation", "v/test/ops/compactllm"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "compact this"))));

		assertTrue(RT.getIn(output, Fields.RESPONSE).toString().contains("Compact verified"));
		AVector<ACell> frames = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		AVector<ACell> conversation = RT.ensureVector(
			RT.getIn(frames.get(0), AgentState.KEY_CONVERSATION));
		assertEquals(2, conversation.count(), "compacted prefix + later assistant reply");
		assertTrue(GoalTreeContext.isSegment(conversation.get(0)));
		assertEquals("assistant", RT.getIn(conversation.get(1), "role").toString());
	}

	@Test
	public void testNestedCoviaCollectionsReturnThroughToolLoop() throws Exception {
		// #334: direct covia reads were fast, but the same tiny nested values
		// stayed RUNNING indefinitely when returned as an agent tool result.
		// Exercise the real read/list operations and the complete two-call L3
		// loop with a deterministic provider. A wall-clock bound makes the
		// original failure mode an explicit regression, not a suite hang.
		RequestContext ctx = RequestContext.of(ALICE_DID);
		AVector<ACell> signals = Vectors.of(
			Maps.of("kind", "kyc", "score", 8L),
			Maps.of("kind", "sanctions", "score", 3L));
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/issue-334/signals",
			Fields.VALUE, signals), ctx).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/issue-334/sources",
			Fields.VALUE, Maps.of(
				"kyc", Maps.of("status", "clear", "provider", "acme"),
				"sanctions", Maps.of("status", "review", "provider", "globex"))),
			ctx).awaitResult(5000);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> config = Maps.of(
			"llmOperation", "v/test/ops/toolllm",
			"defaultTools", false,
			"tools", Vectors.of("v/ops/covia/read", "v/ops/covia/list"));

		for (String prompt : new String[] {"issue-334-read", "issue-334-list"}) {
			ACell output = java.util.concurrent.CompletableFuture.supplyAsync(() ->
				adapter.processChat(ctx, Maps.of(
					Fields.AGENT_ID, "issue-334-agent",
					AgentState.KEY_CONFIG, config,
					Fields.MESSAGES, Vectors.of(Maps.of("content", prompt)))))
				.get(10, java.util.concurrent.TimeUnit.SECONDS);

			AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
			assertNotNull(response);
			assertTrue(response.toString().startsWith("Tool returned: {"),
				"the provider must be able to consume the nested tool result: "
					+ response);
			AVector<ACell> turns = RT.ensureVector(RT.getIn(output, Fields.TURNS));
			assertNotNull(turns);
			ACell durableToolTurn = turns.get(1);
			assertEquals("tool", RT.getIn(durableToolTurn, "role").toString());
			assertNotNull(RT.getIn(durableToolTurn, "structuredContent"),
				"the emitted session turn must retain the typed operation result");
			assertNull(RT.getIn(durableToolTurn, "content"),
				"provider text rendering must not leak back into the durable turn");
			if (prompt.endsWith("read")) {
				assertTrue(response.toString().contains("\"score\":8"),
					"covia/read must preserve the vector's nested maps: " + response);
			} else {
				assertTrue(response.toString().contains("\"values\""),
					"covia/list field projection must preserve its nested values map: " + response);
				assertTrue(response.toString().contains("\"review\""),
					"covia/list projected values must reach the provider intact: " + response);
			}
		}

		// Exercise the framework persistence path too: Fields.TURNS is appended
		// to frames[0].conversation under g/... and must remain typed there.
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "issue-334-persist-agent",
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/ops/llmagent/chat",
				"llmOperation", "v/test/ops/toolllm",
				"defaultTools", false,
				"tools", Vectors.of("v/ops/covia/read"))), ctx).awaitResult(5000);
		ACell chatResult = engine.jobs().invokeOperation("v/ops/agent/chat", Maps.of(
			Fields.AGENT_ID, "issue-334-persist-agent",
			Fields.MESSAGE, "issue-334-read"), ctx).awaitResult(10000);
		AString sid = RT.ensureString(RT.getIn(chatResult, Fields.SESSION_ID));
		assertNotNull(sid);
		AgentState persistedAgent = engine.getVenueState().users().get(ALICE_DID)
			.agent("issue-334-persist-agent");
		AMap<AString, ACell> session = persistedAgent.getSession(Blob.fromHex(sid.toString()));
		AVector<ACell> frames = RT.ensureVector(RT.getIn(session, Fields.FRAMES));
		AVector<ACell> conversation = RT.ensureVector(
			RT.getIn(frames.get(0), AgentState.KEY_CONVERSATION));
		ACell persistedToolTurn = conversation.get(2);
		assertEquals("tool", RT.getIn(persistedToolTurn, "role").toString());
		assertEquals(signals,
			RT.getIn(persistedToolTurn, "structuredContent", Fields.VALUE));
		assertNull(RT.getIn(persistedToolTurn, "content"));
	}

	// ========== Skills index (config.skills — SKILLS.md §4) ==========

	@Test
	public void testSkillsIndexInContext() {
		// Fixture skill in the caller's workspace
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/alpha",
				"value", Maps.of("description", "Alpha skill")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> config = Maps.of(
			"llmOperation", "v/test/ops/llm",
			"skillsets", Vectors.of(Strings.create("w/skills")));

		// Inspection uses the same builder chain as processChat — the index
		// must appear as a system message in the assembled L3 input.
		AMap<AString, ACell> l3 = adapter.inspectContext(
			new ContextInspectable.Inspection(config, null, null, null, null, null), RequestContext.of(ALICE_DID));
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

	@Test
	public void testSessionIdVisibleInInspectedContext() {
		// The report-back handle: a sessioned cycle's system prompt names its
		// session id, so the agent can schedule agent:request {sessionId}
		// back into this conversation.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> l3 = adapter.inspectContext(new ContextInspectable.Inspection(
			Maps.of("llmOperation", "v/test/ops/llm"), null,
			Maps.of(Fields.ID, Blob.fromHex("11bb11bb11bb11bb11bb11bb11bb11bb")), null, null, null),
			RequestContext.of(ALICE_DID));
		AVector<ACell> messages = RT.ensureVector(RT.getIn(l3, Fields.MESSAGES));
		String prompt = RT.ensureString(RT.getIn(messages.get(0), "content")).toString();
		assertTrue(prompt.contains("Session: 11bb11bb11bb11bb11bb11bb11bb11bb"), prompt);
	}

	@Test
	public void testInspectionMirrorsLoadedContextAndRuntimeTools() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/inspection-probe", "value", "probe-visible"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AMap<AString, ACell> load = Maps.of(
			"budget", CVMLong.create(500),
			"ts", CVMLong.create(1),
			"label", Strings.create("Probe"));
		AMap<AString, ACell> session = Maps.of(
			Fields.ID, Blob.fromHex("22bb22bb22bb22bb22bb22bb22bb22bb"),
			Fields.LOADS, Maps.of("w/inspection-probe", load),
			Fields.FRAMES, Vectors.of((ACell) Maps.of(
				AgentState.KEY_CONVERSATION, Vectors.empty())));

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> l3 = adapter.inspectContext(new ContextInspectable.Inspection(
			Maps.of("llmOperation", "v/test/ops/llm",
				Fields.TOOLS, Vectors.of(Strings.create("context_load"), Strings.create("context_unload"))),
			null, session, null, null, null),
			RequestContext.of(ALICE_DID));

		String renderedMessages = convex.core.util.JSON.print(
			RT.getIn(l3, Fields.MESSAGES)).toString();
		assertTrue(renderedMessages.contains("probe-visible"), renderedMessages);
		// Loads render with their own headers; there is no separate inventory.
		assertFalse(renderedMessages.contains("[Context Map]"), renderedMessages);
		assertTrue(renderedMessages.contains("w/inspection-probe"), renderedMessages);

		AVector<ACell> tools = RT.ensureVector(RT.getIn(l3, Fields.TOOLS));
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < tools.count(); i++) {
			AString name = RT.ensureString(RT.getIn(tools.get(i), Fields.NAME));
			if (name != null) names.add(name.toString());
		}
		assertTrue(names.contains("context_load"), names.toString());
		assertTrue(names.contains("context_unload"), names.toString());

		AMap<AString, ACell> palette = RT.ensureMap(l3.get(Strings.intern("palette")));
		AVector<ACell> paletteEntries = RT.ensureVector(palette.get(Fields.TOOLS));
		assertEquals(4, paletteEntries.count(), "stable task controls plus the two declared context tools");
		for (long i = 0; i < paletteEntries.count(); i++) {
			assertEquals("harness", RT.getIn(paletteEntries.get(i), Fields.SOURCE).toString());
		}
		assertEquals(0, RT.ensureVector(palette.get(Strings.intern("unavailable"))).count());

		AVector<ACell> loadEntries = RT.ensureVector(l3.get(Fields.LOADS));
		assertEquals(1, loadEntries.count());
		assertEquals("w/inspection-probe", RT.getIn(loadEntries.get(0), Fields.REF).toString());
		assertEquals("load", RT.getIn(loadEntries.get(0), "kind").toString());
		assertEquals("resolved", RT.getIn(loadEntries.get(0), "status").toString());
		assertTrue(((CVMLong) RT.getIn(loadEntries.get(0), Fields.BYTES)).longValue() > 0);
		assertNull(l3.get(Strings.intern("prefixHashes")),
			"Convex vector equality is the cache identity; no parallel hash map is emitted");
	}

	@Test
	public void testInspectionReportsUnavailableConfiguredTools() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AMap<AString, ACell> report = adapter.inspectContext(new ContextInspectable.Inspection(
			Maps.of("llmOperation", "v/test/ops/llm",
				Fields.TOOLS, Vectors.of(Strings.create("v/ops/no/such/tool"))),
			null, null, null, null, null), RequestContext.of(ALICE_DID));
		AMap<AString, ACell> palette = RT.ensureMap(report.get(Strings.intern("palette")));
		AVector<ACell> unavailable = RT.ensureVector(palette.get(Strings.intern("unavailable")));
		assertEquals(1, unavailable.count());
		assertEquals("v/ops/no/such/tool", RT.getIn(unavailable.get(0), Fields.OPERATION).toString());
		assertNotNull(RT.getIn(unavailable.get(0), Fields.REASON));
	}

	@Test
	public void testMalformedConfigSkillsFailsTransition() {
		// A malformed config.skills is a configuration error — the transition
		// fails loudly rather than silently dropping the skills feature.
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "bad-skills-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/llm",
				"skillsets", "w/skills"),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "hello")));

		RuntimeException e = assertThrows(RuntimeException.class,
			() -> adapter.processChat(RequestContext.of(ALICE_DID), input));
		assertTrue(e.getMessage().contains("config.skills"), e.getMessage());
	}

	// ========== skill_load (SKILLS.md §5) ==========

	/** Fixture: the 'alpha' skill (body + one tool) and the probe value its
	 *  tool reads. */
	private void writeAlphaSkill() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/alpha",
				"value", Maps.of(
					"description", "Alpha skill",
					"content", Maps.of("inline", TestAdapter.SKILL_LLM_BODY),
					"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/read"))))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/probe", "value", "probe-value"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	private ToolContext skillToolCtx() {
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
		ctx.skillSources = Skills.SkillSources.ofSkillsets(
			Vectors.of((ACell) Strings.create("w/skills")));
		return ctx;
	}

	@Test public void testSkillLoadHandler() {
		writeAlphaSkill();
		ToolContext ctx = skillToolCtx();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell result = adapter.handleSkillLoad(Maps.of("name", "alpha"), ctx);
		assertTrue(RT.getIn(result, "loaded") != null, result.toString());
		assertNull(RT.getIn(result, "body"),
			"skill_load is a compact acknowledgement; the body is appended as an event");
		AVector<ACell> toolNames = RT.ensureVector(RT.getIn(result, "tools"));
		assertEquals(1, toolNames.count());
		assertEquals("covia_read", toolNames.get(0).toString());

		// The loads entry: skill-flagged, budgeted, denormalised tool refs.
		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/skills/alpha"));
		assertNotNull(meta);
		assertTrue(Skills.isSkillEntry(meta));
		assertEquals(2000L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
		assertEquals("alpha", meta.get(Strings.create("label")).toString());
		assertEquals("v/ops/covia/read",
			RT.ensureVector(meta.get(Strings.create("tools"))).get(0).toString());
	}

	@Test public void testSkillLoadValidation() {
		writeAlphaSkill();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Exactly one of name/ref
		assertTrue(adapter.handleSkillLoad(Maps.empty(), skillToolCtx())
			.toString().contains("exactly one"));
		assertTrue(adapter.handleSkillLoad(
			Maps.of("name", "alpha", "ref", "w/skills/alpha"), skillToolCtx())
			.toString().contains("exactly one"));

		// Unknown skill → diagnosable error naming it
		assertTrue(adapter.handleSkillLoad(Maps.of("name", "ghost"), skillToolCtx())
			.toString().contains("ghost"));

		// No session in scope → no writable tier
		ToolContext noSession = skillToolCtx();
		noSession.sessionInScope = false;
		assertTrue(adapter.handleSkillLoad(Maps.of("name", "alpha"), noSession)
			.toString().contains("no session in scope"));
	}

	@Test public void testSkillLoadBudgetPrecedence() {
		writeAlphaSkill();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		// Caller budget wins (clamped)
		ToolContext c1 = skillToolCtx();
		adapter.handleSkillLoad(Maps.of("name", "alpha", "budget", 50L), c1);
		assertEquals(256L, ((CVMLong) RT.getIn(
			c1.loads.get(Strings.create("w/skills/alpha")), "budget")).longValue());

		// skill.budget facet beats the default
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/budgeted",
				"value", Maps.of(
					"description", "Budgeted skill",
					"skill", Maps.of("budget", 5000L))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		ToolContext c2 = skillToolCtx();
		adapter.handleSkillLoad(Maps.of("name", "budgeted"), c2);
		assertEquals(5000L, ((CVMLong) RT.getIn(
			c2.loads.get(Strings.create("w/skills/budgeted")), "budget")).longValue());
	}

	@Test public void testSkillToolsFollowLoads() {
		// The generic rule: loads-contributed tools mirror effective loads —
		// a load activates them, an unload retracts them, mid-transition.
		writeAlphaSkill();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ToolContext ctx = skillToolCtx();

		assertEquals(0, ctx.loadTools(engine).count());
		adapter.handleSkillLoad(Maps.of("name", "alpha"), ctx);
		AVector<ACell> active = ctx.loadTools(engine);
		assertEquals(1, active.count());
		assertEquals("covia_read", RT.getIn(active.get(0), Fields.NAME).toString());
		assertEquals("v/ops/covia/read", ctx.dispatchRoutes().get("covia_read").toString());

		adapter.handleContextUnload(Maps.of("path", "w/skills/alpha"), ctx);
		assertEquals(0, ctx.loadTools(engine).count());
		assertNull(ctx.dispatchRoutes().get("covia_read"),
			"unload must retract the dispatch route as well as the visible tool");
		ACell hallucinated = adapter.dispatchTool("covia_read",
			Maps.of("path", "w/probe"), ctx.dispatchRoutes(), ctx.ctx,
			ctx.toolCallTimeoutMs);
		assertTrue(String.valueOf(hallucinated).startsWith("Error:"),
			"a manually supplied call after unload must not use the former route: "
				+ hallucinated);
	}

	@Test public void testSkillToolDedupAgainstFixedPalette() {
		// A skill declaring a tool the agent already offers (config/harness)
		// contributes nothing — and must not clobber the existing dispatch route.
		writeAlphaSkill();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ToolContext ctx = skillToolCtx();
		ctx.fixedToolNames = java.util.Set.of("covia_read");
		ctx.loadExcludedNames = java.util.Set.of("covia_read");
		adapter.handleSkillLoad(Maps.of("name", "alpha"), ctx);
		assertEquals(0, ctx.loadTools(engine).count());
		assertNull(ctx.configToolMap.get("covia_read"),
			"an excluded def must not write a dispatch route");
	}

	@Test public void testSkillLoadDedupsByContentIdentity() {
		// The same skill content under two addresses loads ONCE — skills are
		// content-addressed, so identity is the metadata hash, not the path.
		writeAlphaSkill();
		AMap<AString, ACell> sameSkill = Maps.of(
			"description", "Alpha skill",
			"content", Maps.of("inline", "Use covia_read on w/probe."),
			"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/read"))));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/skills/beta", "value", sameSkill),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ToolContext ctx = skillToolCtx();
		adapter.handleSkillLoad(Maps.of("name", "alpha"), ctx);
		ACell second = adapter.handleSkillLoad(Maps.of("name", "beta"), ctx);

		assertEquals(1, ctx.loads.count(), "identical content must not load twice");
		assertEquals(1, ctx.loadTools(engine).count());
		assertTrue(second.toString().contains("Already loaded"), second.toString());
		assertTrue(second.toString().contains("w/skills/alpha"), second.toString());
	}

	@Test public void testRepeatedSkillLoadKeepsSingleEntry() {
		// Loads are keyed by canonical path — reloading the same skill
		// overwrites, never duplicates entry or tool def.
		writeAlphaSkill();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ToolContext ctx = skillToolCtx();
		adapter.handleSkillLoad(Maps.of("name", "alpha"), ctx);
		adapter.handleSkillLoad(Maps.of("name", "alpha", "budget", 3000L), ctx);
		assertEquals(1, ctx.loads.count());
		assertEquals(1, ctx.loadTools(engine).count());
		assertEquals(3000L, ((CVMLong) RT.getIn(
			ctx.loads.get(Strings.create("w/skills/alpha")), "budget")).longValue());
	}

	@Test
	public void testSkillAdoptionDuringChat() {
		// The operative-loop proof: within ONE chat transition the mock loads
		// the skill, its body becomes visible and the palette gains covia_read
		// on the next iteration, and the tool actually dispatches — load →
		// context + palette → dispatch, one turn.
		writeAlphaSkill();
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell input = Maps.of(
			Fields.AGENT_ID, "skill-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/skillllm",
				"skillsets", Vectors.of(Strings.create("w/skills"))),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "use the alpha skill")),
			Fields.SESSION, Maps.of(Fields.ID, Strings.create("s1")));

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		AString response = RT.ensureString(RT.getIn(output, Fields.RESPONSE));
		assertNotNull(response);
		assertTrue(response.toString().contains("SKILL_TOOL_RESULT"), response.toString());
		assertTrue(response.toString().contains("probe-value"), response.toString());

		// The session tier on the output carries the skill entry (persisted
		// by the framework's loads write-back).
		AMap<AString, ACell> loads = (AMap<AString, ACell>) RT.getIn(output, Fields.LOADS);
		assertNotNull(loads, "session in scope → loads emitted on the output");
		assertTrue(Skills.isSkillEntry(loads.get(Strings.create("w/skills/alpha"))));
	}

	@Test
	public void testContextLoadAndUnloadAreVisibleWithinCurrentTransition() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/context-immediate",
				"value", "IMMEDIATE_CONTEXT_MARKER"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "context-immediate-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/skillllm"),
			Fields.MESSAGES, Vectors.of(Maps.of(
				"content", "run generic context lifecycle")),
			Fields.SESSION, Maps.of(Fields.ID, Strings.create("context-session")));

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertEquals("CONTEXT_LOAD_AND_UNLOAD_IMMEDIATE",
			RT.ensureString(RT.getIn(output, Fields.RESPONSE)).toString());
		AVector<ACell> turns = RT.ensureVector(RT.getIn(output, Fields.TURNS));
		String renderedTurns = convex.core.util.JSON.toString(turns);
		assertTrue(renderedTurns.contains("\"name\":\"loaded_context\""), renderedTurns);
		assertEquals(1, renderedTurns.split("IMMEDIATE_CONTEXT_MARKER", -1).length - 1,
			"the value appears once in the appended loaded_context result, not in its acknowledgement");

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> loads =
			(AMap<AString, ACell>) RT.getIn(output, Fields.LOADS);
		assertNotNull(loads);
		assertNull(loads.get(Strings.create("w/context-immediate")),
			"the same-cycle unload must also persist");
	}

	@Test
	public void testSkillPersistsAcrossTurns() {
		// e2e through the run loop: turn 1 adopts the skill; turn 2 sees the
		// re-rendered [Skill: alpha] body and the persisted tool palette
		// WITHOUT reloading.
		writeAlphaSkill();
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "skill-e2e-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/skillllm",
					"skillsets", Vectors.of(Strings.create("w/skills")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("skill-e2e-agent");
		Blob sid = Blob.fromHex("44440001444400014444000144440001");
		agent.ensureSession(sid, ALICE_DID);
		agent.appendSessionPending(sid, Maps.of(
			Strings.intern("content"), Strings.create("use the alpha skill")));

		engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "skill-e2e-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		agent = user.agent("skill-e2e-agent");
		AString turn1 = RT.ensureString(RT.getIn(agent.getTimeline().get(0), Fields.RESULT));
		assertTrue(turn1.toString().contains("SKILL_TOOL_RESULT"), turn1.toString());

		// The skill entry landed on the session's loads tier.
		AMap<AString, ACell> session = agent.getSession(sid);
		AMap<AString, ACell> loads = (AMap<AString, ACell>) RT.getIn(session, "loads");
		assertNotNull(loads);
		assertTrue(Skills.isSkillEntry(loads.get(Strings.create("w/skills/alpha"))));

		// Turn 2 reuses the appended skill body and tool-state event — no reload
		// and no rewrite of the initial tools vector.
		agent.appendSessionPending(sid, Maps.of(
			Strings.intern("content"), Strings.create("carry on")));
		engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "skill-e2e-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 2, 10000);

		agent = user.agent("skill-e2e-agent");
		AString turn2 = RT.ensureString(RT.getIn(agent.getTimeline().get(1), Fields.RESULT));
		assertTrue(turn2.toString().contains("SKILL_BODY_PRESENT"), turn2.toString());
		assertTrue(turn2.toString().contains("SKILL_TOOLS_ACTIVE"), turn2.toString());

		// A source behind unchanged declarative config is ambient: reseeding the
		// directory and reapplying the same config neither rewrites nor appends to
		// this session's already-materialised context.
		session = agent.getSession(sid);
		ACell initialMessages = RT.getIn(session, Fields.FRAMES, CVMLong.ZERO,
			GoalTreeContext.K_RENDERED_CONTEXT, Strings.intern("messages"));
		AVector<ACell> initialTools = RT.ensureVector(RT.getIn(session, Fields.FRAMES, CVMLong.ZERO,
			GoalTreeContext.K_RENDERED_CONTEXT, Fields.TOOLS));
		assertFalse(ToolPalette.names(initialTools).contains("covia_write"));
		engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(
			Fields.PATH, "w/skills/beta",
			Fields.VALUE, Maps.of(
				"description", "Beta catalog refresh",
				"content", Maps.of("inline", "Use covia_write when asked."),
				"skill", Maps.of("tools",
					Vectors.of(Strings.create("v/ops/covia/write"))))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(
			Fields.AGENT_ID, "skill-e2e-agent",
			Fields.CONFIG, Maps.of("skillsets", Vectors.of(Strings.create("w/skills")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		session = agent.getSession(sid);
		assertEquals(initialMessages, RT.getIn(session, Fields.FRAMES, CVMLong.ZERO,
			GoalTreeContext.K_RENDERED_CONTEXT, Strings.intern("messages")));
		assertFalse(RT.getIn(session, Fields.FRAMES, CVMLong.ZERO,
			Strings.intern("conversation")).toString().contains("Beta catalog refresh"));

		// Beta was not in this session's immutable initial manifest. Loading it
		// therefore appends a genuine tool addition; it must not rewrite tools.
		AMap<AString, ACell> betaLoad = RT.ensureMap(engine.jobs().invokeOperation(
			"v/ops/agent/step", Maps.of(
				Fields.AGENT_ID, "skill-e2e-agent",
				Fields.SESSION_ID, sid.toHexString(),
				"assistant", Maps.of("toolCalls", Vectors.of(Maps.of(
					"id", "load-beta", "name", HarnessTools.SKILL_LOAD,
					"arguments", Maps.of("name", "beta"))))),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		AMap<AString, ACell> next = RT.ensureMap(betaLoad.get(AbstractLLMAdapter.K_NEXT));
		assertEquals(initialTools, RT.ensureVector(next.get(Fields.TOOLS)),
			"a later catalog load must preserve the exact fixed manifest");
		String nextMessages = RT.ensureVector(next.get(Fields.MESSAGES)).toString();
		assertTrue(nextMessages.contains("toolAddition") && nextMessages.contains("covia_write"),
			"a later catalog tool must be appended as tool state: " + nextMessages);
		long turnsAfterAmbientUpdate = RT.ensureVector(RT.getIn(session, Fields.FRAMES, CVMLong.ZERO,
			Strings.intern("conversation"))).count();
		engine.jobs().invokeOperation("v/ops/agent/update", Maps.of(
			Fields.AGENT_ID, "skill-e2e-agent",
			Fields.CONFIG, Maps.of("skillsets", Vectors.of(Strings.create("w/skills")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(turnsAfterAmbientUpdate, RT.ensureVector(RT.getIn(agent.getSession(sid),
			Fields.FRAMES, CVMLong.ZERO, Strings.intern("conversation"))).count(),
			"an equal config must not append ambient catalog state");
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
	public void testEmptyCompleteTaskFallsBackToTurnText() {
		// The LadyByron failure shape: the model writes its answer as message
		// text and calls complete_task with no result. The harness honours the
		// text as the result (the dual of #215) instead of rejecting the call.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "text-complete-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					"model", "empty-complete-with-text-test")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("text-complete-agent");
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of("task", "review the poems"));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "text-complete-agent"),
			RequestContext.of(ALICE_DID));
		runJob.awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		assertEquals(0, agent.getTasks().count(), "empty complete_task with turn text must complete the task");
		ACell timelineEntry = agent.getTimeline().get(0);
		ACell taskResults = RT.getIn(timelineEntry, Fields.TASK_RESULTS);
		assertNotNull(taskResults, "timeline should record the completion");
		assertTrue(taskResults.toString().contains("The full review: a triumph of form over feeling."),
			"the turn's text must be delivered as the task result, got: " + taskResults);
	}

	// ========== Per-task response schema (#376) ==========

	private static AMap<AString, ACell> answerSchema() {
		return Maps.of(
			"type", "object",
			"properties", Maps.of("answer", Maps.of("type", "number")),
			"required", Vectors.of(Strings.create("answer")));
	}

	@Test
	public void testStrictSchemaRejectsThenAccepts() {
		// strict=true: a non-conforming completion is rejected back to the
		// agent with diagnostics; the corrected completion lands. The mock
		// also verifies the enforced schema was rendered into task context
		// (it fails the task if not).
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "strict-schema-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					"model", "strict-schema-test")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("strict-schema-agent");
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of(
			Fields.INPUT, Maps.of("task", "compute the answer"),
			Fields.RESPONSE_SCHEMA, answerSchema(),
			Fields.STRICT, CVMBool.TRUE));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "strict-schema-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		assertEquals(0, agent.getTasks().count(), "conforming retry must complete the task");
		ACell taskResults = RT.getIn(agent.getTimeline().get(0), Fields.TASK_RESULTS);
		assertNotNull(taskResults, "timeline entry: " + agent.getTimeline().get(0));
		assertTrue(taskResults.toString().contains("42"),
			"the conforming structured result must land, got: " + taskResults);
		assertFalse(taskResults.toString().contains("freestyle"),
			"the non-conforming first attempt must not land, got: " + taskResults);
	}

	@Test
	public void testNonStrictSchemaFreestyles() {
		// Default (strict absent): the schema is guidance — a mismatching
		// completion lands without interference.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "advisory-schema-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					"model", "strict-schema-test")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("advisory-schema-agent");
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of(
			Fields.INPUT, Maps.of("task", "compute the answer"),
			Fields.RESPONSE_SCHEMA, answerSchema()));

		engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "advisory-schema-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		TestEngine.awaitTimelineCount(agent, 1, 10000);

		// The advisory schema renders as guidance, so the mock's schema guard
		// (which looks for the enforced marker) fails the task — UNLESS the
		// mock's first freestyle attempt landed. Assert the freestyle result.
		assertEquals(0, agent.getTasks().count());
		ACell taskResults = RT.getIn(agent.getTimeline().get(0), Fields.TASK_RESULTS);
		assertNotNull(taskResults);
		assertTrue(taskResults.toString().contains("schema not rendered into task context")
				|| taskResults.toString().contains("freestyle"),
			"got: " + taskResults);
	}

	@Test
	public void testRequestStoresSchemaAndStrictOnTask() throws Exception {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "schema-request-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					"model", "no-complete-test")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "schema-request-agent",
				Fields.INPUT, Maps.of("task", "typed work"),
				Fields.RESPONSE_SCHEMA, answerSchema(),
				Fields.STRICT, CVMBool.TRUE,
				"timeout", CVMLong.create(0)),
			RequestContext.of(ALICE_DID));

		// The task record is added synchronously by handleRequest; the
		// no-complete mock never resolves it, so it stays inspectable.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("schema-request-agent");
		long deadline = System.currentTimeMillis() + 10000;
		while (agent.getTasks().count() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertEquals(1, agent.getTasks().count(), "task must be queued");
		ACell record = agent.getTasks().entryAt(0).getValue();
		assertNotNull(RT.getIn(record, Fields.RESPONSE_SCHEMA), "schema must persist on the task record");
		assertEquals(CVMBool.TRUE, RT.getIn(record, Fields.STRICT), "strict must persist on the task record");
	}

	@Test
	public void testStrictWithoutSchemaFails() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "strict-no-schema-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					"model", "no-complete-test")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "strict-no-schema-agent",
				Fields.INPUT, Maps.of("task", "typed work"),
				Fields.STRICT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		Exception e = assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertTrue(e.getMessage().contains("strict requires a responseSchema"),
			"got: " + e.getMessage());
	}

	@Test
	public void testPersistentLoadedValueStaysStableAfterToolWriteWithinSameTransition() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/live-load", "value", "LOAD_VALUE_OLD"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell input = Maps.of(
			Fields.AGENT_ID, "live-load-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/skillllm",
				"model", "load-refresh-test",
				"tools", Vectors.of(Strings.create("v/ops/covia/write")),
				Fields.LOADS, Maps.of("w/live-load", Maps.of("budget", 500L))),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "refresh the loaded value")),
			Fields.SESSION, Maps.of(Fields.ID, Strings.create("live-load-session")));

		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), input);
		assertEquals("LIVE_LOAD_STABLE",
			RT.ensureString(RT.getIn(output, Fields.RESPONSE)).toString());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testParallelCompleteTaskPairsAllResultsAndSkipsLaterSideEffects() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "parallel-task-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/taskllm",
					"model", "parallel-task-complete-test",
					"tools", Vectors.of(Strings.create("v/ops/covia/write")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job taskJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "parallel-task-agent",
				Fields.INPUT, Maps.of("task", "complete without the later write")),
			RequestContext.of(ALICE_DID));
		ACell taskResult = taskJob.awaitResult(10000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("parallel-task-agent");
		TestEngine.awaitTimelineCount(agent, 1, 10000);
		assertEquals(Status.COMPLETE, taskJob.getStatus());

		ACell read = engine.jobs().invokeOperation(
			"v/ops/covia/read",
			Maps.of("path", "w/parallel-task-should-not-write"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(read, "exists"),
			"a call after complete_task in the same batch must not execute");

		AString sid = RT.ensureString(RT.getIn(taskResult, Fields.SESSION_ID));
		assertNotNull(sid);
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sid.toString()));
		AVector<ACell> frames = RT.ensureVector(session.get(Fields.FRAMES));
		AVector<ACell> conversation = RT.ensureVector(
			RT.getIn(frames.get(0), AgentState.KEY_CONVERSATION));

		long callTurn = -1;
		for (long i = 0; i < conversation.count(); i++) {
			ACell calls = RT.getIn(conversation.get(i), "toolCalls");
			if (calls instanceof AVector<?> v && v.count() == 2) {
				callTurn = i;
				break;
			}
		}
		assertTrue(callTurn >= 0, "the parallel provider batch must be retained for audit");
		AVector<ACell> retainedCalls = RT.ensureVector(
			RT.getIn(conversation.get(callTurn), "toolCalls"));
		assertInstanceOf(AMap.class, RT.getIn(retainedCalls.get(0), "arguments"));
		assertInstanceOf(AMap.class, RT.getIn(retainedCalls.get(1), "arguments"),
			"custom Level 3 JSON strings must be canonicalised before persistence");
		ACell firstResult = conversation.get(callTurn + 1);
		ACell secondResult = conversation.get(callTurn + 2);
		assertEquals("tool", RT.getIn(firstResult, "role").toString());
		assertEquals("call_complete_task", RT.getIn(firstResult, "id").toString());
		assertEquals("tool", RT.getIn(secondResult, "role").toString());
		assertEquals("call_after_complete_task", RT.getIn(secondResult, "id").toString());
		assertEquals(CVMBool.TRUE, RT.getIn(secondResult, "isError"));

		ACell followup = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID, "parallel-task-agent",
				Fields.MESSAGE, "same session followup",
				Fields.SESSION_ID, sid),
			RequestContext.of(ALICE_DID)).awaitResult(10000);
		assertEquals("NEXT_TURN_OK", RT.getIn(followup, Fields.RESPONSE).toString(),
			"a fully paired terminal batch must leave the session reusable");
	}

	@Test
	public void testParallelFailTaskPairsAllResultsAndSkipsLaterSideEffects() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		AString agentId = Strings.create("parallel-fail-task-agent");
		AMap<AString, ACell> config = Maps.of(
			"llmOperation", "v/test/ops/taskllm",
			"model", "parallel-task-fail-test",
			"tools", Vectors.of(Strings.create("v/ops/covia/write")));
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AgentState agent = engine.getVenueState().users().get(ALICE_DID)
			.agent(agentId.toString());
		Blob taskId = Blob.createRandom(new java.util.Random(), 16);
		agent.addTask(taskId, Maps.of("task", "fail deliberately"));
		ACell output = adapter.processChat(
			RequestContext.of(ALICE_DID).withAgentId(agentId).withTaskId(taskId),
			Maps.of(
				Fields.AGENT_ID, agentId,
				AgentState.KEY_CONFIG, config,
				Fields.TASKS, Vectors.of(Maps.of(
					Fields.JOB_ID, taskId.toHexString(),
					Fields.INPUT, Maps.of("task", "fail deliberately"))),
				Fields.MESSAGES, Vectors.empty()));

		assertEquals("failed deliberately", RT.getIn(output, Fields.ERROR).toString());
		assertNull(agent.getTasks().get(taskId));
		ACell read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of("path", "w/parallel-fail-task-should-not-write"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(read, "exists"));

		AVector<ACell> turns = RT.ensureVector(RT.getIn(output, Fields.TURNS));
		assertEquals("call_fail_task", RT.getIn(turns.get(1), "id").toString());
		assertEquals("call_after_fail_task", RT.getIn(turns.get(2), "id").toString());
		assertEquals(CVMBool.TRUE, RT.getIn(turns.get(2), "isError"));
	}

	@Test
	public void testFailedTerminalLookingTaskCallDoesNotSuppressSibling() {
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell output = adapter.processChat(RequestContext.of(ALICE_DID), Maps.of(
			Fields.AGENT_ID, "failed-terminal-looking-agent",
			AgentState.KEY_CONFIG, Maps.of(
				"llmOperation", "v/test/ops/taskllm",
				"model", "failed-terminal-looking-test",
				"tools", Vectors.of(Strings.create("v/test/ops/echo"))),
			Fields.MESSAGES, Vectors.of(Maps.of("content", "continue after failure"))));

		assertNotNull(RT.getIn(output, Fields.RESPONSE));
		AVector<ACell> turns = RT.ensureVector(RT.getIn(output, Fields.TURNS));
		assertEquals("call_invalid_complete_task", RT.getIn(turns.get(1), "id").toString());
		assertEquals(CVMBool.TRUE, RT.getIn(turns.get(1), "isError"));
		assertEquals("call_after_invalid_complete_task", RT.getIn(turns.get(2), "id").toString());
		assertNull(RT.getIn(turns.get(2), "isError"),
			"a failed complete_task must not suppress later calls in the batch");
	}

	@Test
	public void testFlatAgentContextKeepsSharedConversationAppendOnly() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("flat session");
		frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "user", "content", "first"));
		frame = GoalTreeContext.appendTurn(frame, Maps.of(
			"role", "assistant", "toolCalls", Vectors.of(
				Maps.of("id", "old-call", "name", "covia_read", "arguments", "{}"))));
		frame = GoalTreeContext.appendTurn(frame, Maps.of(
			"role", "tool", "id", "old-call", "name", "covia_read", "content", "old"));
		frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "assistant", "content", "first done"));
		frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "user", "content", "second"));
		frame = GoalTreeContext.appendTurn(frame, Maps.of(
			"role", "assistant", "toolCalls", Vectors.of(
				Maps.of("id", "live-call", "name", "covia_read", "arguments", "{}"))));

		AVector<ACell> history = ContextAssembler.conversation(new ContextAssembler.Spec(
			engine, RequestContext.of(ALICE_DID), null, Maps.empty(), null, null, 0, null,
			null, null, null, Vectors.of((ACell) frame), null, null, true, null, null, null, null, null));

		assertEquals(6, history.count());
		assertEquals("first", RT.getIn(history.get(0), "content").toString());
		assertNotNull(RT.getIn(history.get(1), "toolCalls"));
		assertEquals("tool", RT.getIn(history.get(2), "role").toString());
		assertEquals("first done", RT.getIn(history.get(3), "content").toString());
		assertEquals("second", RT.getIn(history.get(4), "content").toString());
		assertNotNull(RT.getIn(history.get(5), "toolCalls"),
			"the current tool cycle also remains intact for the provider");
	}

	@Test
	public void testCappedAgentCanCompleteTask() {
		// Regression (covia#71 live testing): the task-lifecycle ops are
		// self-scoped (agentId/taskId from the RequestContext) and must stay
		// callable under a restricted config scope. A blanket invoke gate on
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
					// Restricted scope: one read grant, no invoke ability.
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

		AMap<AString, ACell> t1 = entryTokens(agent.getTimeline().get(0));
		assertNotNull(t1, "the entry's inferences must carry the cycle's tokens");
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

		AMap<AString, ACell> t2 = entryTokens(agent.getTimeline().get(1));
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
		assertNotNull(RT.getIn(rendered, "sessionTokens"),
			"context report must include the measured session totals");
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
		AtomicReference<AMap<AString, ACell>> observed = new AtomicReference<>();
		TestEngine.awaitCondition(() -> {
			AMap<AString, ACell> current = engine.jobs().getJobData(
				taskJobId, RequestContext.of(ALICE_DID));
			observed.set(current);
			return current != null && Job.isFinished(current);
		}, 15000, () -> "task job did not finish; last record=" + observed.get());
		AMap<AString, ACell> record = observed.get();
		assertNotNull(record, "task job record must exist");
		assertEquals(Status.COMPLETE, RT.getIn(record, Fields.STATUS));
		ACell jobTokens = RT.getIn(record, Fields.TOKENS);
		assertNotNull(jobTokens, "task job record must carry the cycle's token usage");
		assertTrue(RT.ensureLong(RT.getIn(jobTokens, Fields.TOTAL)).longValue() > 0);
	}

	/** The cycle's token totals as the entry records them: the sum over its
	 *  inferences' replies (#392) — the entry stores nothing derivable. */
	private static AMap<AString, ACell> entryTokens(ACell entry) {
		AVector<ACell> inferences = RT.ensureVector(RT.getIn(entry, Fields.INFERENCES));
		if (inferences == null) return null;
		long in = 0, out = 0, total = 0;
		boolean measured = false;
		for (long i = 0; i < inferences.count(); i++) {
			ACell t = RT.getIn(inferences.get(i), Fields.REPLY, Fields.TOKENS);
			if (t == null) continue;
			measured = true;
			in += RT.ensureLong(RT.getIn(t, Fields.INPUT)).longValue();
			out += RT.ensureLong(RT.getIn(t, Fields.OUTPUT)).longValue();
			total += RT.ensureLong(RT.getIn(t, Fields.TOTAL)).longValue();
		}
		return measured ? Maps.of(Fields.INPUT, in, Fields.OUTPUT, out, Fields.TOTAL, total) : null;
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
		TestEngine.awaitAgentStatus(agent, AgentState.SLEEPING, 2000);
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
		AString[] parsed = ToolPalette.parseConfigToolEntry(Strings.create("v/ops/agent/create"));
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
		AString[] parsed = ToolPalette.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/http/get", parsed[0].toString());
		assertEquals("fetch_url", parsed[1].toString());
		assertEquals("Fetch a URL", parsed[2].toString());
	}

	@Test
	public void testParseConfigToolEntryMapMinimal() {
		ACell entry = Maps.of("operation", "v/ops/agent/list");
		AString[] parsed = ToolPalette.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/agent/list", parsed[0].toString());
		assertNull(parsed[1]);
		assertNull(parsed[2]);
	}

	@Test
	public void testParseConfigToolEntryMapMissingOperation() {
		ACell entry = Maps.of("name", "orphan_tool");
		assertNull(ToolPalette.parseConfigToolEntry(entry));
	}

	@Test
	public void testParseConfigToolEntryInvalidType() {
		assertNull(ToolPalette.parseConfigToolEntry(CVMLong.create(42)));
		assertNull(ToolPalette.parseConfigToolEntry(CVMBool.TRUE));
	}

	@Test
	public void testParseConfigToolEntryNull() {
		assertNull(ToolPalette.parseConfigToolEntry(null));
	}

	// ========== Pure function: deriveToolName ==========

	@Test
	public void testDeriveToolNameOverrideWins() {
		String name = ToolPalette.deriveToolName(
			Strings.create("my_tool"),
			Strings.create("asset_tool"),
			Strings.create("adapter:op"));
		assertEquals("my_tool", name);
	}

	@Test
	public void testDeriveToolNameAssetToolNameWins() {
		String name = ToolPalette.deriveToolName(
			null,
			Strings.create("asset_tool"),
			Strings.create("adapter:op"));
		assertEquals("asset_tool", name);
	}

	@Test
	public void testDeriveToolNameFallbackColonToUnderscore() {
		String name = ToolPalette.deriveToolName(
			null, null, Strings.create("agent:create"));
		assertEquals("agent_create", name);
	}

	@Test
	public void testDeriveToolNameFallbackSlashToUnderscore() {
		String name = ToolPalette.deriveToolName(
			null, null, Strings.create("did:venue:user/o/my-tool"));
		assertEquals("did_venue_user_o_my-tool", name);
	}

	@Test
	public void testDeriveToolNameNoSpecialChars() {
		String name = ToolPalette.deriveToolName(
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
		AMap<AString, ACell> def = ToolPalette.buildToolDefinition(
			"fetch_url", Strings.create("Fetch a URL"), schema);

		assertEquals(Strings.create("fetch_url"), def.get(Strings.intern("name")));
		assertEquals(Strings.create("Fetch a URL"), def.get(Strings.intern("description")));
		assertSame(schema, def.get(Strings.intern("parameters")));
	}

	@Test
	public void testBuildToolDefinitionNullSchema() {
		AMap<AString, ACell> def = ToolPalette.buildToolDefinition(
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
		AMap<AString, ACell> def = ToolPalette.buildToolDefinition("tool", null, schema);

		assertEquals(Strings.create("tool"), def.get(Strings.intern("name")));
		assertNull(def.get(Strings.intern("description")));
	}

	@Test
	public void testBuildToolDefinitionStringSchema() {
		// Non-map schema (e.g. a string) should get default object schema
		AMap<AString, ACell> def = ToolPalette.buildToolDefinition(
			"tool", null, Strings.create("bad-schema"));
		ACell params = def.get(Strings.intern("parameters"));
		assertEquals(Strings.create("object"), RT.getIn(params, "type"));
	}

	// ========== The task boundary: TaskTools ==========

	private static ToolCycleEngine.ToolCall completeCall(String result) {
		return new ToolCycleEngine.ToolCall(Strings.create("c1"), TaskTools.COMPLETE,
			Maps.of(Fields.RESULT, result), 0);
	}

	@Test
	public void testTaskMessageNoTasks() {
		assertNull(new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null).tasks.message());
		assertNull(new ToolContext(Strings.create("agent"), null, Vectors.empty(), null, null, null).tasks.message());
	}

	@Test
	public void testTaskMessageOmitsResolvedTasks() {
		AVector<ACell> tasks = Vectors.of(
			Maps.of(Fields.JOB_ID, "aaa", Fields.INPUT, "done-task"),
			Maps.of(Fields.JOB_ID, "bbb", Fields.INPUT, "pending-task"));
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, tasks, null, null, null);
		assertTrue(ctx.tasks.outstanding());
		assertEquals(2, ctx.tasks.tools().count(), "task tools offered while a task is outstanding");
		String all = RT.ensureString(ctx.tasks.message().get(Strings.intern("content"))).toString();
		assertTrue(all.contains("aaa") && all.contains("bbb") && all.contains("[Tasks assigned to you]"), all);

		// The first outstanding task resolves (no task id on the context); the message drops it.
		ToolCycleEngine.ToolOutcome outcome = ctx.tasks.complete(completeCall("done"), null);
		assertEquals("complete", outcome.terminalStatus());
		assertEquals(Strings.create("done"), outcome.terminalValue());
		AMap<AString, ACell> msg = ctx.tasks.message();
		assertEquals(Strings.create("user"), msg.get(Strings.intern("role")));
		String content = RT.ensureString(msg.get(Strings.intern("content"))).toString();
		assertTrue(content.contains("bbb"), "Should mention outstanding task bbb");
		assertFalse(content.contains("aaa"), "Should not mention resolved task aaa");
		assertTrue(content.contains("complete_task"), "Should instruct to use complete_task");

		// All resolved: no task tail, but the immutable harness remains byte-stable.
		ctx.tasks.complete(completeCall("also done"), null);
		assertNull(ctx.tasks.message());
		assertEquals(TaskTools.DEFINITIONS, ctx.tasks.tools());
	}

	@Test
	public void testTaskResolutionPromotesIntoTheOutput() {
		AVector<ACell> tasks = Vectors.of(Maps.of(Fields.JOB_ID, "job1", Fields.INPUT, "task"));
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), tasks, null, null, null);
		assertFalse(ctx.tasks.resolved());
		AMap<AString, ACell> chat = Maps.of(Fields.RESPONSE, "Done.");
		assertSame(chat, ctx.tasks.promote(chat), "nothing resolved: the output stands");

		ctx.tasks.complete(completeCall("result1"), null);
		assertTrue(ctx.tasks.resolved());
		assertEquals(Strings.create("result1"), RT.getIn(ctx.tasks.results(), "job1", Fields.OUTPUT));
		assertEquals(Strings.create("result1"), ctx.tasks.promote(chat).get(Fields.RESPONSE),
			"the structured result is the authoritative answer");

		// A failure replaces the response with the error.
		ToolContext failing = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), tasks, null, null, null);
		failing.tasks.fail(new ToolCycleEngine.ToolCall(Strings.create("f1"), TaskTools.FAIL,
			Maps.of(Fields.ERROR, "reason"), 0), null);
		AMap<AString, ACell> failed = failing.tasks.promote(chat);
		assertEquals(Strings.create("reason"), failed.get(Fields.ERROR));
		assertNull(failed.get(Fields.RESPONSE));
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
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
		assertEquals(0, ctx.loads.count());

		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell result = adapter.handleContextLoad(
			Maps.of("path", "w/docs/rules", "budget", 1000L, "label", "Policy Rules"), ctx);
		assertTrue(result.toString().contains("loaded"));
		assertEquals(1, ctx.loads.count());
		assertNotNull(ctx.loads.get(Strings.create("w/docs/rules")));
	}

	@Test public void testContextLoadDefaultBudget() {
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/test"), ctx);

		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/test"));
		assertEquals(500L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
	}

	@Test public void testContextLoadBudgetClamped() {
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
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
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/data", "budget", 500L, "label", "first"), ctx);
		adapter.handleContextLoad(Maps.of("path", "w/data", "budget", 1000L, "label", "second"), ctx);

		assertEquals(1, ctx.loads.count());
		AMap<AString, ACell> meta = (AMap<AString, ACell>) ctx.loads.get(Strings.create("w/data"));
		assertEquals(1000L, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
		assertEquals("second", meta.get(Strings.create("label")).toString());
	}

	@Test public void testContextUnloadHandler() {
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		adapter.handleContextLoad(Maps.of("path", "w/data"), ctx);
		assertEquals(1, ctx.loads.count());

		ACell result = adapter.handleContextUnload(Maps.of("path", "w/data"), ctx);
		assertTrue(result.toString().contains("unloaded"));
		assertEquals(0, ctx.loads.count());
	}

	@Test public void testContextLoadRejectsPathOutsideAgentScope() {
		ToolContext ctx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID).withCaps(Vectors.empty()),
			null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell result = adapter.handleContextLoad(Maps.of("path", "w/private"), ctx);

		assertTrue(result.toString().contains("denied"), result.toString());
		assertEquals(0, ctx.loads.count(),
			"a denied path must not be persisted for a later unscoped render");
	}

	@Test public void testContextUnloadNotFound() {
		ToolContext ctx = new ToolContext(Strings.create("agent"), null, null, null, null, null);
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");
		ACell result = adapter.handleContextUnload(Maps.of("path", "w/missing"), ctx);
		assertTrue(result.toString().contains("Error"));
	}

	// ========== Context scope chain (#142) ==========

	/** context_unload cannot hide operator-pinned context. */
	@Test public void testUnloadRejectsConfigLoad() {
		ToolContext toolCtx = new ToolContext(Strings.create("agent"),
			RequestContext.of(ALICE_DID), null, null, null, null);
		toolCtx.outerLoads = Maps.of(Strings.create("w/pinned"),
			Maps.of(Strings.create("budget"), CVMLong.create(400)));
		LLMAgentAdapter adapter = (LLMAgentAdapter) engine.getAdapter("llmagent");

		ACell result = adapter.handleContextUnload(Maps.of("path", "w/pinned"), toolCtx);
		assertTrue(result.toString().contains("pinned_context and cannot be unloaded"), "result: " + result);
		assertEquals(0, toolCtx.loads.count(), "no masking tombstone is written");
		assertNotNull(ContextChain.effective(toolCtx.outerLoads, toolCtx.loads)
			.get(Strings.create("w/pinned")), "the pinned value remains visible");
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

	// ========== agent:context includes loads-derived exchanges (#418) ==========

	@Test
	public void testContextIncludesVolatileLoadExchanges() {
		// A watched config.loads op entry appends its first observation to the
		// local preview frame; agent:context must show the exchange too (#418) —
		// not merely report the entry resolved.
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "loads-context-agent",
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/ops/llmagent/chat",
				"llmOperation", "v/test/ops/llm",
				"loads", Maps.of("now", Maps.of(
					"op", "v/test/ops/echo",
					"input", Maps.of("ping", "pong-418"),
					"budget", 2000,
					"label", "now")))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		AMap<AString, ACell> context = RT.ensureMap(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "loads-context-agent", Fields.MESSAGE, "what is the time?"),
			RequestContext.of(ALICE_DID)).awaitResult(5000));
		AVector<ACell> messages = RT.ensureVector(context.get(Fields.MESSAGES));
		assertTrue(messages != null && messages.toString().contains("pong-418"),
			"the watched op load's exchange must be in the inspected messages: " + messages);
	}
}
