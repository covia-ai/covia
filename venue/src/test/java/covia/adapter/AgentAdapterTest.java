package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
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
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;
import covia.venue.VenueState;

/**
 * Tests for the AgentAdapter: create, message, trigger, request, query, and list operations.
 *
 * <p>Uses the shared {@link TestEngine#ENGINE}; each test gets unique
 * ALICE_DID / BOB_DID via {@link TestEngine#uniqueDID(TestInfo)} so agent
 * names and user state don't collide across tests.</p>
 */
public class AgentAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	// ALICE_DID / BOB_DID are per-test (not static) so each test sees a fresh
	// user namespace within the shared engine.
	private AString ALICE_DID;
	private AString BOB_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		BOB_DID = Strings.create(ALICE_DID.toString() + "-bob");
	}

	// ========== agent:create ==========

	@Test
	public void testCreateAgent() {
		ACell input = Maps.of(Fields.AGENT_ID, "my-assistant");
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "Create should return a result");
		assertEquals(Strings.create("my-assistant"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	@Test
	public void testCreateAgentWithConfig() {
		ACell input = Maps.of(
			Fields.AGENT_ID, "configured-agent",
			Fields.CONFIG, Maps.of("model", "gpt-4", "temperature", "0.7")
		);
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		job.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNotNull(user);
		AgentState agent = user.agent("configured-agent");
		assertNotNull(agent);
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertNotNull(storedConfig, "Config should be stored");
	}

	@Test
	public void testCreateAgentWithConfigFromWorkspacePath() {
		// Store a template map in the caller's workspace
		AMap<AString, ACell> template = Maps.of(
			Strings.create("systemPrompt"), Strings.create("You read data."),
			Strings.create("model"), Strings.create("gpt-4"));

		engine.jobs().invokeOperation(
			"v/ops/covia/write",
			Maps.of(
				Fields.PATH, Strings.create("w/templates/reader"),
				Fields.VALUE, template),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Create an agent from a workspace-path reference
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "reader-from-template",
				Fields.CONFIG, Strings.create("w/templates/reader")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("reader-from-template"), RT.getIn(result, Fields.AGENT_ID));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("reader-from-template");
		assertNotNull(agent);
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertNotNull(storedConfig);
		assertEquals(Strings.create("You read data."),
			storedConfig.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("gpt-4"),
			storedConfig.get(Strings.create("model")));
	}

	@Test
	public void testCreateAgentFromStandardTemplateReader() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "reader-bot",
				Fields.CONFIG, Strings.create("v/agents/templates/reader")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("reader-bot"), RT.getIn(result, Fields.AGENT_ID));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("reader-bot");
		assertNotNull(agent);
		AMap<AString, ACell> config = agent.getConfig();
		assertNotNull(config);
		// Template supplies a systemPrompt and a tools vector
		assertNotNull(config.get(Strings.create("systemPrompt")));
		assertNotNull(config.get(Strings.create("tools")));
		// Reader template has defaultTools=false
		assertEquals(CVMBool.FALSE, config.get(Strings.create("defaultTools")));
	}

	@Test
	public void testCreateAgentFromStandardTemplateWorker() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "data-worker",
				Fields.CONFIG, Strings.create("v/agents/templates/worker")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("data-worker");
		assertNotNull(agent);
		AMap<AString, ACell> config = agent.getConfig();
		// Worker template includes covia:write in tools
		AVector<ACell> tools = RT.ensureVector(config.get(Strings.create("tools")));
		assertNotNull(tools);
		boolean hasWrite = false;
		for (long i = 0; i < tools.count(); i++) {
			if (Strings.create("v/ops/covia/write").equals(tools.get(i))) {
				hasWrite = true;
				break;
			}
		}
		assertTrue(hasWrite, "worker template should include covia:write");
	}

	@Test
	public void testCreateAgentWithConfigRefFailsIfMissing() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "ghost-template",
				Fields.CONFIG, Strings.create("w/templates/does-not-exist")),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should fail when config reference cannot be resolved");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testCreateAgentExtractsEmbeddedState() {
		// Template with embedded state field — should be extracted as initial state
		AMap<AString, ACell> template = Maps.of(
			Strings.create("systemPrompt"), Strings.create("You have memory."),
			AgentState.KEY_STATE, Maps.of(Strings.create("memory"), Strings.create("pre-loaded")));

		engine.jobs().invokeOperation(
			"v/ops/covia/write",
			Maps.of(
				Fields.PATH, Strings.create("w/templates/stateful"),
				Fields.VALUE, template),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "stateful-agent",
				Fields.CONFIG, Strings.create("w/templates/stateful")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("stateful-agent");
		assertNotNull(agent);

		// systemPrompt lives in config, embedded state is not in config
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertEquals(Strings.create("You have memory."),
			storedConfig.get(Strings.create("systemPrompt")));
		assertNull(storedConfig.get(AgentState.KEY_STATE),
			"state field should be extracted out of config");

		// Embedded state is used as initial state
		ACell state = agent.getState();
		assertNotNull(state);
		assertEquals(Strings.create("pre-loaded"), RT.getIn(state, Strings.create("memory")));
	}

	@Test
	public void testCreateMissingAgentId() {
		ACell input = Maps.of("foo", "bar");
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should have thrown due to missing agentId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testCreateIdempotent() {
		ACell input = Maps.of(Fields.AGENT_ID, "idempotent-agent");

		Job job1 = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		job1.awaitResult(5000);

		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/create", input, RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);

		assertNotNull(result2);
		assertEquals(Strings.create("idempotent-agent"), RT.getIn(result2, Fields.AGENT_ID));
	}

	// ========== agent:fork ==========

	@Test
	public void testForkAgentBasic() {
		// Create source agent with config and some state
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "source-agent",
				Fields.CONFIG, Maps.of(
					Strings.create("systemPrompt"), Strings.create("You are source."),
					Strings.create("model"), Strings.create("gpt-4")),
				AgentState.KEY_STATE, Maps.of(Strings.create("memory"), Strings.create("original"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork it
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("source-agent"),
				Fields.AGENT_ID, "fork-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("fork-agent"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CREATED));
		assertEquals(Strings.create("source-agent"), RT.getIn(result, Strings.create("forkedFrom")));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Fork should have the same config and state as source
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState fork = user.agent("fork-agent");
		assertNotNull(fork);
		AMap<AString, ACell> forkConfig = fork.getConfig();
		assertEquals(Strings.create("You are source."),
			forkConfig.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("gpt-4"),
			forkConfig.get(Strings.create("model")));
		assertEquals(Strings.create("original"),
			RT.getIn(fork.getState(), Strings.create("memory")));
	}

	@Test
	public void testForkAgentWithConfigOverride() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "base",
				Fields.CONFIG, Maps.of(
					Strings.create("systemPrompt"), Strings.create("Original prompt"),
					Strings.create("model"), Strings.create("gpt-4"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork with config override — systemPrompt changes, model stays
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("base"),
				Fields.AGENT_ID, "variant",
				Fields.CONFIG, Maps.of(
					Strings.create("systemPrompt"), Strings.create("Override prompt"))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState variant = user.agent("variant");
		AMap<AString, ACell> vc = variant.getConfig();
		assertEquals(Strings.create("Override prompt"),
			vc.get(Strings.create("systemPrompt")));
		assertEquals(Strings.create("gpt-4"), vc.get(Strings.create("model")),
			"Non-overridden fields should come from source");
	}

	@Test
	public void testForkAgentFreshCollections() {
		// Create source with messages and tasks
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "busy-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver a message to source
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID, "busy-agent",
				Fields.MESSAGE, Maps.of("content", "hello")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("busy-agent"),
				Fields.AGENT_ID, "busy-fork"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState fork = user.agent("busy-fork");
		assertFalse(fork.hasSessionPending(), "Fork should have no pending messages");
		assertEquals(0, fork.getTasks().count(), "Fork should have no tasks");

		// Source still has its messages
		AgentState source = user.agent("busy-agent");
		assertTrue(source.hasSessionPending(), "Source session pending is not touched");
	}

	@Test
	public void testForkAgentIncludeTimeline() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "timeline-source"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork WITHOUT timeline
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("timeline-source"),
				Fields.AGENT_ID, "no-timeline-fork"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Fork WITH timeline
		engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("timeline-source"),
				Fields.AGENT_ID, "with-timeline-fork",
				Strings.create("includeTimeline"), CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		// Both forks exist
		assertNotNull(user.agent("no-timeline-fork"));
		assertNotNull(user.agent("with-timeline-fork"));
		// Both have empty timelines (source had none), and status SLEEPING
		assertEquals(AgentState.SLEEPING, user.agent("no-timeline-fork").getStatus());
		assertEquals(AgentState.SLEEPING, user.agent("with-timeline-fork").getStatus());
	}

	@Test
	public void testForkMissingSource() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("ghost"),
				Fields.AGENT_ID, "fork"),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail when source doesn't exist");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testForkTargetAlreadyExists() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "a1"), RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "a2"), RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fork",
			Maps.of(
				Strings.create("sourceId"), Strings.create("a1"),
				Fields.AGENT_ID, "a2"),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail when target already exists");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== agent:message ==========

	@Test
	public void testMessageAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "msg-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "msg-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message", msgInput, RequestContext.of(ALICE_DID));
		ACell result = msgJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELIVERED));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("msg-agent");
		assertTrue(agent.hasSessionPending(), "Agent should have pending message after delivery");
	}

	@Test
	public void testMessageNonExistentAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "other-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "ghost-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message", msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult(5000);
			fail("Should have thrown for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	@Test
	public void testMessageTerminatedAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "term-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("term-agent");
		agent.setStatus(AgentState.TERMINATED);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "term-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message", msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult(5000);
			fail("Should have thrown for terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	// ========== agent:chat ==========

	/**
	 * Standard LLM-backed chat agent for chat tests. Uses {@code v/test/ops/llm}
	 * which echoes the last user message as the assistant content; the
	 * {@code llmagent:chat} transition surfaces that as the {@code response}
	 * value the framework completes the chat Job with.
	 */
	private void createChatAgent(String agentId) {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of(
					"config", Maps.of(
						"llmOperation", "v/test/ops/llm",
						"systemPrompt", "Echo the user."
					)
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	@Test
	public void testChatMintsSessionAndReturnsResponse() {
		createChatAgent("chat-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));

		ACell result = chatJob.awaitResult(5000);
		assertNotNull(result, "Chat must complete with a response");
		assertEquals(Strings.create("chat-agent"), RT.getIn(result, Fields.AGENT_ID));
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sidHex, "Chat response must include the minted sessionId");
		assertNotNull(RT.getIn(result, Fields.RESPONSE), "Chat response must include the agent's response");

		// Chat slot is now in-memory (not on the lattice). The follow-up
		// regression is covered by testChatRejectsConcurrentOnSameSession
		// and testCancelChatReleasesSlot, so no direct slot assertion here.
	}

	/**
	 * Regression guard for issue #85 — internal adapter-to-adapter calls
	 * (transition dispatch, LLM sub-invocation, tool calls) must use
	 * {@code JobManager.invokeInternal} which returns a plain
	 * {@link java.util.concurrent.CompletableFuture} and creates zero Jobs.
	 * Pre-refactor a single {@code agent:chat} spawned 3 Jobs:
	 * the chat Job, a transition Job ({@code llmagent:chat}), and an LLM
	 * sub-invocation Job ({@code test:llm}). Post-refactor only the
	 * caller's chat Job should exist.
	 */
	@Test
	public void testChatProducesExactlyOneJob() {
		createChatAgent("chat-count-agent");
		RequestContext ctx = RequestContext.of(ALICE_DID);

		long before = engine.jobs().getJobs(ctx).count();

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-count-agent", Fields.MESSAGE, Strings.create("hi")),
			ctx);
		chatJob.awaitResult(5000);

		long after = engine.jobs().getJobs(ctx).count();
		assertEquals(1, after - before,
			"agent:chat must produce exactly 1 Job — pre-refactor created 3 "
			+ "(chat + transition + llm sub-invocation)");
	}

	@Test
	public void testChatContinuesKnownSession() {
		createChatAgent("chat-cont-agent");

		// First chat — mint session
		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cont-agent", Fields.MESSAGE, Strings.create("first")),
			RequestContext.of(ALICE_DID));
		ACell firstResult = first.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(firstResult, Fields.SESSION_ID));
		assertNotNull(sidHex);

		// Second chat — echo session id
		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-cont-agent",
				Fields.SESSION_ID, sidHex,
				Fields.MESSAGE,    Strings.create("second")),
			RequestContext.of(ALICE_DID));
		ACell secondResult = second.awaitResult(5000);
		assertNotNull(secondResult);
		assertEquals(sidHex, RT.getIn(secondResult, Fields.SESSION_ID),
			"Second chat must echo the same session id");
	}

	@Test
	public void testChatRejectsUnknownSession() {
		createChatAgent("chat-reject-agent");

		// Random non-existent sid (well-formed hex, never minted)
		String fakeSid = "00000000000000000000000000000000";

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-reject-agent",
				Fields.SESSION_ID, Strings.create(fakeSid),
				Fields.MESSAGE,    Strings.create("hi")),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Chat with unknown sessionId must fail");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chatJob.getStatus());
		}
	}

	@Test
	public void testChatRejectsConcurrentOnSameSession() throws InterruptedException {
		// Use a long-running LLM op so the first chat stays in flight while
		// the second arrives. v/test/ops/delay holds the transition open.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "chat-busy-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of(
					"config", Maps.of(
						// Wrap delay around the L3 call by using the standard llm op
						// but with a slow llm. Easiest: use test:delay-llm if it exists,
						// otherwise just attempt a second call quickly.
						"llmOperation", "v/test/ops/llm",
						"systemPrompt", "Echo the user."
					)
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("chat-busy-agent");

		// Pre-create a session and reserve its in-memory chat slot with a
		// never-finished Job to force the second-chat-on-busy-session error
		// path deterministically.
		Blob sid = Blob.fromHex("11111111111111111111111111111111");
		agent.ensureSession(sid, ALICE_DID);
		AgentAdapter agentAdapter = (AgentAdapter) engine.getAdapter("agent");
		// A never-completing placeholder Job holds the slot.
		Job placeholder = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		agentAdapter.reserveChatSlotForTest(
			Strings.create("chat-busy-agent"), sid, placeholder);

		// Now an agent_chat on the same session must fail fast
		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "chat-busy-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("hi")),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Concurrent chat on same session must be rejected");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chatJob.getStatus());
		}
	}

	@Test
	public void testChatRequiresMessage() {
		createChatAgent("chat-msg-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-msg-agent"),
			RequestContext.of(ALICE_DID));

		try {
			chatJob.awaitResult(5000);
			fail("Chat without message must fail");
		} catch (Exception e) {
			assertEquals(Status.FAILED, chatJob.getStatus());
		}
	}

	// ========== S3a — session.history append ==========

	/**
	 * After a successful chat, the framework must have appended a user turn
	 * (the chat message) and an assistant turn (the response) to the
	 * picked session's history vector. Each turn carries
	 * {role, content, ts, source}; meta.turns increments accordingly.
	 *
	 * <p>Note: S3a only appends turns derived from picked-task input or
	 * leanResponse. Chat messages go via inbox in S3a and are NOT yet
	 * surfaced as user turns from history (that's S3b). So we expect
	 * exactly one assistant turn, sourced from the transition.</p>
	 */
	@Test
	public void testTransitionAppendsResponseToSessionHistory() {
		createChatAgent("hist-resp-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "hist-resp-agent", Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sidHex);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-resp-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));
		assertNotNull(session, "Session record must exist");

		AVector<ACell> history = (AVector<ACell>) session.get(Strings.intern("history"));
		assertNotNull(history, "Session must have a history vector");
		assertEquals(2, history.count(),
			"Chat cycle appends user turn (chat message) + assistant turn");

		AMap<AString, ACell> userTurn = (AMap<AString, ACell>) history.get(0);
		assertEquals(AgentState.ROLE_USER, userTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_CHAT, userTurn.get(AgentState.K_SOURCE));
		assertEquals(Strings.create("hello"), userTurn.get(AgentState.K_CONTENT));

		AMap<AString, ACell> assistantTurn = (AMap<AString, ACell>) history.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, assistantTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_TRANSITION, assistantTurn.get(AgentState.K_SOURCE));
		assertNotNull(assistantTurn.get(AgentState.K_CONTENT), "Turn must carry content");
		assertNotNull(assistantTurn.get(AgentState.K_TURN_TS), "Turn must carry timestamp");
		assertTrue(assistantTurn.get(AgentState.K_TURN_TS) instanceof CVMLong, "ts must be CVMLong");

		// meta.turns should be 2 (user + assistant)
		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(Strings.intern("meta"));
		assertEquals(CVMLong.create(2), meta.get(Strings.intern("turns")),
			"meta.turns must reflect appended turn count");
	}

	/**
	 * When a task is picked, its input becomes a user/request turn appended
	 * to the picked session's history alongside the assistant response.
	 */
	@Test
	public void testTransitionAppendsTaskInputAsUserTurn() {
		// Use v/test/ops/taskcomplete — it completes the task in a single
		// cycle and returns a `response`, giving us both user and assistant
		// turns deterministically.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "hist-task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Blob sid = Blob.fromHex("22222222222222222222222222222222");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-task-agent");
		agent.ensureSession(sid, ALICE_DID);

		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(
				Fields.AGENT_ID, "hist-task-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.INPUT, Strings.create("do thing"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		reqJob.awaitResult(5000);

		try { agent.awaitSleeping().get(5, java.util.concurrent.TimeUnit.SECONDS); }
		catch (Exception e) { fail("Agent did not return to SLEEPING: " + e); }

		AMap<AString, ACell> session = agent.getSession(sid);
		AVector<ACell> history = (AVector<ACell>) session.get(Strings.intern("history"));
		assertEquals(2, history.count(),
			"Picked task + response cycle appends two turns (user, assistant)");

		AMap<AString, ACell> userTurn = (AMap<AString, ACell>) history.get(0);
		assertEquals(AgentState.ROLE_USER, userTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_REQUEST, userTurn.get(AgentState.K_SOURCE));
		assertEquals(Strings.create("do thing"), userTurn.get(AgentState.K_CONTENT));

		AMap<AString, ACell> assistantTurn = (AMap<AString, ACell>) history.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, assistantTurn.get(AgentState.K_ROLE));
		assertEquals(AgentState.SOURCE_TRANSITION, assistantTurn.get(AgentState.K_SOURCE));
	}

	/**
	 * Errored cycles append no turns. {@code leanError != null} must skip
	 * history population entirely so the audit trail doesn't claim a
	 * conversation turn that the agent failed to produce.
	 */
	@Test
	public void testErrorResponseDoesNotAppendTurn() {
		// Use the test echo-llm agent but break the L3 op to force an error path.
		// Simplest: create an agent whose transition op returns an error.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "hist-err-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of(
					"config", Maps.of(
						"llmOperation", "v/test/ops/error",
						"systemPrompt", "x"
					)
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "hist-err-agent", Fields.MESSAGE, Strings.create("x")),
			RequestContext.of(ALICE_DID));
		try { chatJob.awaitResult(5000); } catch (Exception ignored) {}

		// Find the minted session via agent state — chat slot should be cleared
		// either way, but we want any session that exists to have empty history.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-err-agent");
		var sessions = agent.getSessions();
		if (sessions.count() == 0) return; // No session minted — nothing to assert
		for (var entry : sessions.entrySet()) {
			AMap<AString, ACell> session = (AMap<AString, ACell>) entry.getValue();
			AVector<ACell> history = (AVector<ACell>) session.get(Strings.intern("history"));
			assertEquals(0, history.count(),
				"Errored cycle must not append any turns to history");
		}
	}

	/**
	 * History must accumulate across multiple chat turns on the same session,
	 * with order preserved (oldest first) and meta.turns reflecting the
	 * cumulative count.
	 */
	@Test
	public void testHistoryCarriesAcrossMultipleCycles() {
		createChatAgent("hist-multi-agent");

		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "hist-multi-agent", Fields.MESSAGE, Strings.create("one")),
			RequestContext.of(ALICE_DID));
		ACell firstResult = first.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(firstResult, Fields.SESSION_ID));

		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(
				Fields.AGENT_ID,   "hist-multi-agent",
				Fields.SESSION_ID, sidHex,
				Fields.MESSAGE,    Strings.create("two")),
			RequestContext.of(ALICE_DID));
		second.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("hist-multi-agent");
		AMap<AString, ACell> session = agent.getSession(Blob.fromHex(sidHex.toString()));
		AVector<ACell> history = (AVector<ACell>) session.get(Strings.intern("history"));
		assertEquals(4, history.count(),
			"Two chat cycles: each appends user+assistant = 4 turns total");

		// Turn order: [user1, assistant1, user2, assistant2]
		assertEquals(AgentState.ROLE_USER, RT.getIn(history.get(0), AgentState.K_ROLE));
		assertEquals(Strings.create("one"), RT.getIn(history.get(0), AgentState.K_CONTENT));
		assertEquals(AgentState.ROLE_ASSISTANT, RT.getIn(history.get(1), AgentState.K_ROLE));
		assertEquals(AgentState.ROLE_USER, RT.getIn(history.get(2), AgentState.K_ROLE));
		assertEquals(Strings.create("two"), RT.getIn(history.get(2), AgentState.K_CONTENT));
		assertEquals(AgentState.ROLE_ASSISTANT, RT.getIn(history.get(3), AgentState.K_ROLE));

		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(Strings.intern("meta"));
		assertEquals(CVMLong.create(4), meta.get(Strings.intern("turns")));

		// Order preserved: ts of all turns must be non-decreasing
		long prev = 0;
		for (long i = 0; i < history.count(); i++) {
			long ts = ((CVMLong) RT.getIn(history.get(i), AgentState.K_TURN_TS)).longValue();
			assertTrue(ts >= prev, "History order must be chronological at index " + i);
			prev = ts;
		}
	}

	// ========== S3b — session map in transition input + dual-write ==========

	/**
	 * S3b dual-write: a chat must land in both {@code agent.inbox} (legacy)
	 * and {@code session.pending} (new). After the cycle runs, both should
	 * be drained for the consumed session.
	 */
	@Test
	public void testChatDualWritesToSessionPending() throws Exception {
		// Create the agent, mint a session manually, then deliver a chat —
		// but block the run loop so we can observe the dual-write before the
		// cycle drains it. Simplest: pre-reserve the chat slot so the actual
		// agent_chat fails fast, leaving the manually-injected message visible.
		// Even simpler: use agent_message (no slot reservation) and observe.
		createChatAgent("s3b-dualwrite-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-dualwrite-agent");

		// Suspend agent so messages queue without being consumed
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "s3b-dualwrite-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Blob sid = Blob.fromHex("33333333333333333333333333333333");
		agent.ensureSession(sid, ALICE_DID);

		Job msgJob = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID,   "s3b-dualwrite-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("hi there")),
			RequestContext.of(ALICE_DID));
		msgJob.awaitResult(5000);

		// Session pending should hold the message
		AVector<ACell> sessionPending = agent.getSessionPending(sid);
		assertEquals(1, sessionPending.count(),
			"session.pending must hold the message");
		// Envelope shape preserved on both sides
		AMap<AString, ACell> envelope = (AMap<AString, ACell>) sessionPending.get(0);
		assertEquals(Strings.create("hi there"), envelope.get(Fields.MESSAGE));
	}

	/**
	 * The session record carried into the transition under
	 * {@code Fields.SESSION} must have the full {parties, meta, c, history,
	 * pending} shape (id is associated by the run loop). We verify by
	 * driving a chat cycle and asserting:
	 *  (a) the session record on the lattice carries every expected key, and
	 *  (b) the cycle observed and drained {@code session.pending} (proves
	 *      the run loop snapshotted the record and passed the count to the
	 *      atomic merge).
	 */
	@Test
	public void testTransitionReceivesSessionMap() throws Exception {
		createChatAgent("s3b-shape-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-shape-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "s3b-shape-agent",
				Fields.MESSAGE, Strings.create("hi")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		Blob sid = Blob.fromHex(sidHex.toString());

		agent.awaitSleeping().get(5, java.util.concurrent.TimeUnit.SECONDS);

		AMap<AString, ACell> session = agent.getSession(sid);
		assertNotNull(session, "Session record must exist on lattice");
		// Keys the run loop assembles into the transition input map
		assertNotNull(session.get(Strings.intern("meta")),    "session must have meta");
		assertNotNull(session.get(Strings.intern("c")),       "session must have c");
		assertNotNull(session.get(Strings.intern("history")), "session must have history");
		assertNotNull(session.get(Strings.intern("pending")), "session must have pending");
		// parties lives under meta
		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(Strings.intern("meta"));
		assertNotNull(meta.get(Strings.intern("parties")), "meta.parties must exist");

		// Drain proof — the run loop did snapshot session.pending and pass
		// its count to mergeRunResult. (The chat-handler dual-write puts the
		// message in session.pending; if the merge didn't drain it, count > 0.)
		assertEquals(0, ((AVector<?>) session.get(Strings.intern("pending"))).count(),
			"session.pending must have been drained by the cycle");
	}

	/**
	 * After a cycle consumes the session's pending messages, the session
	 * pending vector must be drained. Tail messages arriving during the
	 * transition are preserved (tested implicitly via count semantics in
	 * the merge — here we just verify the basic drain).
	 */
	@Test
	public void testSessionPendingDrainsAfterCycle() throws Exception {
		createChatAgent("s3b-drain-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-drain-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "s3b-drain-agent",
				Fields.MESSAGE, Strings.create("hello")),
			RequestContext.of(ALICE_DID));
		ACell result = chatJob.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		Blob sid = Blob.fromHex(sidHex.toString());

		agent.awaitSleeping().get(5, java.util.concurrent.TimeUnit.SECONDS);

		AVector<ACell> sessionPending = agent.getSessionPending(sid);
		assertEquals(0, sessionPending.count(),
			"session.pending must be drained after the cycle consumes its messages");
	}

	/**
	 * Symmetric to the chat dual-write test but for {@code agent_message}
	 * — confirms the dual-write happens regardless of which intake op
	 * delivered the message.
	 */
	@Test
	public void testMessageDualWritesToSessionPending() throws Exception {
		createChatAgent("s3b-msg-dualwrite-agent");
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("s3b-msg-dualwrite-agent");

		// Suspend so messages queue
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "s3b-msg-dualwrite-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Blob sid = Blob.fromHex("55555555555555555555555555555555");
		agent.ensureSession(sid, ALICE_DID);

		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID,   "s3b-msg-dualwrite-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("a")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(
				Fields.AGENT_ID,   "s3b-msg-dualwrite-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE,    Strings.create("b")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		AVector<ACell> pending = agent.getSessionPending(sid);
		assertEquals(2, pending.count(), "Both messages must appear in session.pending");
		assertEquals(Strings.create("a"),
			((AMap<AString, ACell>) pending.get(0)).get(Fields.MESSAGE));
		assertEquals(Strings.create("b"),
			((AMap<AString, ACell>) pending.get(1)).get(Fields.MESSAGE));
	}

	// ========== S3c — adapters prefer session.history / session.pending ==========

	/**
	 * S3c read priority: when a transition input carries both
	 * {@code session.pending} (S3b dual-write) and the legacy
	 * {@code Fields.MESSAGES}, {@link AgentAdapter#effectiveMessages} must
	 * return {@code session.pending} — reading both would duplicate.
	 */
	@Test
	public void testEffectiveMessagesPrefersSessionPending() {
		AVector<ACell> sessionPending = Vectors.of(
			Maps.of(Fields.MESSAGE, Strings.create("from-session")));
		AVector<ACell> legacyMessages = Vectors.of(
			Maps.of(Fields.MESSAGE, Strings.create("from-legacy")));
		AMap<AString, ACell> session = Maps.of(
			AgentState.KEY_PENDING, sessionPending,
			AgentState.KEY_HISTORY, Vectors.empty());
		AMap<AString, ACell> input = Maps.of(
			Fields.SESSION,  session,
			Fields.MESSAGES, legacyMessages);

		AVector<ACell> effective = AgentAdapter.effectiveMessages(input);
		assertEquals(1, effective.count(),
			"Must take session.pending only — not concatenate with messages");
		assertEquals(Strings.create("from-session"),
			RT.getIn(effective.get(0), Fields.MESSAGE),
			"Must be the session.pending entry, not the legacy messages entry");
	}

	/**
	 * S3c read priority fallback: with no session in the input,
	 * {@link AgentAdapter#effectiveMessages} returns {@code Fields.MESSAGES}.
	 * Returns empty (never null) when neither source has anything.
	 */
	@Test
	public void testEffectiveMessagesFallsBackToInputMessages() {
		AVector<ACell> legacyMessages = Vectors.of(
			Maps.of(Fields.MESSAGE, Strings.create("legacy-only")));
		AMap<AString, ACell> input = Maps.of(Fields.MESSAGES, legacyMessages);

		AVector<ACell> effective = AgentAdapter.effectiveMessages(input);
		assertEquals(1, effective.count());
		assertEquals(Strings.create("legacy-only"),
			RT.getIn(effective.get(0), Fields.MESSAGE));

		// Empty input → empty (not null)
		AVector<ACell> empty = AgentAdapter.effectiveMessages(Maps.empty());
		assertNotNull(empty, "Must return empty vector, not null");
		assertEquals(0, empty.count());
	}

	/**
	 * S3c read priority for transcript: {@link AgentAdapter#sessionHistory}
	 * returns the {@code session.history} vector when a session is present,
	 * else {@code null} so callers can fall back to their own state.
	 */
	@Test
	public void testSessionHistoryHelper() {
		AVector<ACell> turns = Vectors.of(
			Maps.of(AgentState.K_ROLE, AgentState.ROLE_USER,
				AgentState.K_CONTENT, Strings.create("hi")));
		AMap<AString, ACell> session = Maps.of(
			AgentState.KEY_HISTORY, turns,
			AgentState.KEY_PENDING, Vectors.empty());
		AMap<AString, ACell> withSession = Maps.of(Fields.SESSION, session);
		AVector<ACell> got = AgentAdapter.sessionHistory(withSession);
		assertNotNull(got);
		assertEquals(1, got.count());

		// No session → null sentinel (caller falls back to state.transcript)
		assertNull(AgentAdapter.sessionHistory(Maps.empty()),
			"Null when no session in scope");
	}

	/**
	 * S3c transcript conversion: ContextBuilder.withSessionHistory must
	 * convert each turn envelope {role, content, ts, source} into a plain
	 * LLM message {role, content}. Tool-call interleaving from across-turn
	 * tool sequences is not preserved (this is the documented contract).
	 */
	@Test
	public void testWithSessionHistoryConvertsTurnsToLLMMessages() {
		AVector<ACell> turns = Vectors.of(
			(ACell) Maps.of(
				AgentState.K_ROLE,    AgentState.ROLE_USER,
				AgentState.K_CONTENT, Strings.create("what's 2+2?"),
				AgentState.K_TURN_TS, CVMLong.create(100L),
				AgentState.K_SOURCE,  AgentState.SOURCE_REQUEST),
			(ACell) Maps.of(
				AgentState.K_ROLE,    AgentState.ROLE_ASSISTANT,
				AgentState.K_CONTENT, Strings.create("4"),
				AgentState.K_TURN_TS, CVMLong.create(200L),
				AgentState.K_SOURCE,  AgentState.SOURCE_TRANSITION));

		ContextBuilder builder = new ContextBuilder(engine, RequestContext.of(ALICE_DID));
		ContextBuilder.ContextResult result = builder
			.withSessionHistory(turns)
			.withTools()
			.build();

		AVector<ACell> llmMessages = result.history();
		assertEquals(2, llmMessages.count(), "Two turns → two LLM messages");

		AMap<AString, ACell> first = (AMap<AString, ACell>) llmMessages.get(0);
		assertEquals(AgentState.ROLE_USER, first.get(Strings.intern("role")));
		assertEquals(Strings.create("what's 2+2?"), first.get(Strings.intern("content")));
		// ts and source are dropped — vendor APIs require {role, content} only
		assertNull(first.get(AgentState.K_TURN_TS), "ts must be dropped");
		assertNull(first.get(AgentState.K_SOURCE),  "source must be dropped");

		AMap<AString, ACell> second = (AMap<AString, ACell>) llmMessages.get(1);
		assertEquals(AgentState.ROLE_ASSISTANT, second.get(Strings.intern("role")));
		assertEquals(Strings.create("4"), second.get(Strings.intern("content")));
	}

	// ========== agent:trigger ==========

	@Test
	public void testTriggerWithEcho() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "echo-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver messages directly to avoid auto-wake race
		User echoUser = engine.getVenueState().users().get(ALICE_DID);
		AgentState echoAgent = echoUser.agent("echo-agent");
		Blob echoSid = Blob.fromHex("aaaa0001aaaa0001aaaa0001aaaa0001");
		echoAgent.ensureSession(echoSid, ALICE_DID);
		AString echoSidHex = Strings.create(echoSid.toHexString());
		for (int i = 0; i < 2; i++) {
			echoAgent.appendSessionPending(echoSid, Maps.of(
				Fields.SESSION_ID, echoSidHex,
				Fields.MESSAGE, Maps.of("content", "msg-" + i)));
		}

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "echo-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("echo-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus(), "Status should be sleeping after run");

		assertFalse(agent.hasSessionPending(), "Session pending should be empty after run");

		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline, "Timeline should not be null");
		assertEquals(1, timeline.count(), "Timeline should have 1 entry");

		assertNull(agent.getError(), "Error should be null after successful run");
	}

	@Test
	public void testTriggerDefaultBlocksUntilComplete() {
		// Backward compat: no `wait` field → block until run loop finishes (status SLEEPING).
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "block-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u = engine.getVenueState().users().get(ALICE_DID);
		AgentState blockAgent = u.agent("block-agent");
		Blob blockSid = Blob.fromHex("bbbb0001bbbb0001bbbb0001bbbb0001");
		blockAgent.ensureSession(blockSid, ALICE_DID);
		blockAgent.appendSessionPending(blockSid, Maps.of(
			Fields.SESSION_ID, Strings.create(blockSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "block-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS),
			"Default wait should block until run loop completes");
	}

	@Test
	public void testTriggerWaitFalseReturnsImmediately() {
		// wait=false → return immediately with status RUNNING. Run loop still
		// executes in the background; caller polls via agent:info or
		// covia:read path=g/<agent>/status.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "async-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u2 = engine.getVenueState().users().get(ALICE_DID);
		AgentState asyncAgent = u2.agent("async-agent");
		Blob asyncSid = Blob.fromHex("cccc0001cccc0001cccc0001cccc0001");
		asyncAgent.ensureSession(asyncSid, ALICE_DID);
		asyncAgent.appendSessionPending(asyncSid, Maps.of(
			Fields.SESSION_ID, Strings.create(asyncSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "async-agent", Fields.WAIT, CVMBool.FALSE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.RUNNING, RT.getIn(result, Fields.STATUS),
			"wait=false should return RUNNING without blocking");
		assertEquals(Strings.create("async-agent"), RT.getIn(result, Fields.AGENT_ID));
	}

	@Test
	public void testTriggerWaitIntegerTimeout() {
		// wait=<ms> → block up to that many ms, return running if timed out.
		// Using test:echo which is effectively instant — result should be SLEEPING
		// because the run loop finishes well within the timeout.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "to-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User u3 = engine.getVenueState().users().get(ALICE_DID);
		AgentState toAgent = u3.agent("to-agent");
		Blob toSid = Blob.fromHex("dddd0001dddd0001dddd0001dddd0001");
		toAgent.ensureSession(toSid, ALICE_DID);
		toAgent.appendSessionPending(toSid, Maps.of(
			Fields.SESSION_ID, Strings.create(toSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hi")));

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "to-agent", Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(6000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS),
			"wait=5000 should wait for fast run loop to finish");
	}

	@Test
	public void testTriggerNoWork() {
		// Trigger with no messages/tasks — transition still runs (may act proactively)
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "empty-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "empty-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Transition was invoked even with no work
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("empty-agent");
		assertEquals(1, agent.getTimeline().count(), "Transition should have run once");
	}

	/**
	 * Regression for #64 — phantom RUNNING state. If the agent's lattice status
	 * shows RUNNING but no live run exists (crash-recovery remnant, stale
	 * write, or race that slips past clean-exit), a subsequent trigger must
	 * still be able to start a fresh loop. The coord is the source of truth
	 * for liveness — wakeAgent corrects the lattice under the lock.
	 *
	 * <p>Deterministic: we force the phantom by writing status=RUNNING
	 * directly while no run is live (fresh agent, no triggers yet), then
	 * issue a normal trigger. Without the fix this fails with "Cannot start
	 * agent"; with the fix the trigger recovers and completes.</p>
	 */
	@Test
	public void testPhantomRunningRecovery() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "phantom-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("phantom-agent");

		// Force the phantom: status=RUNNING with no coord.completion
		agent.setStatus(AgentState.RUNNING);
		assertEquals(AgentState.RUNNING, agent.getStatus());

		// Trigger must recover — not fail with "Cannot start agent"
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "phantom-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertNotNull(result, "Trigger should recover from phantom RUNNING");
		assertEquals(Status.COMPLETE, job.getStatus(),
			"Job should complete, not fail with 'Cannot start agent'");
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Agent should be SLEEPING after the recovered run");
	}

	// ========== User isolation ==========

	@Test
	public void testUserIsolation() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "shared-name", Fields.MESSAGE, Maps.of("from", "alice")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(BOB_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "shared-name", Fields.MESSAGE, Maps.of("from", "bob")),
			RequestContext.of(BOB_DID)).awaitResult(5000);

		User alice = engine.getVenueState().users().get(ALICE_DID);
		User bob = engine.getVenueState().users().get(BOB_DID);

		AgentState aliceAgent = alice.agent("shared-name");
		AgentState bobAgent = bob.agent("shared-name");

		assertTrue(aliceAgent.hasSessionPending(), "Alice's agent should have pending message");
		assertTrue(bobAgent.hasSessionPending(), "Bob's agent should have pending message");
	}

	// ========== Default transition op from config ==========

	@Test
	public void testTriggerWithDefaultOperation() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "default-op-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User user0 = engine.getVenueState().users().get(ALICE_DID);
		AgentState defAgent = user0.agent("default-op-agent");
		Blob defSid = Blob.fromHex("eeee0001eeee0001eeee0001eeee0001");
		defAgent.ensureSession(defSid, ALICE_DID);
		defAgent.appendSessionPending(defSid, Maps.of(
			Fields.SESSION_ID, Strings.create(defSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hello")));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "default-op-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("default-op-agent");
		assertFalse(agent.hasSessionPending(), "Session pending should be cleared");
		assertEquals(1, agent.getTimeline().count(), "Timeline should have 1 entry");
	}

	@Test
	public void testInfoOmitsEmptyStateConfig() {
		// New agents (non-definition path) have state=null — info should not
		// include a spurious empty stateConfig field. Regression for RT.ensureMap
		// returning Maps.empty() for null input.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "no-state-agent",
				Fields.CONFIG, Strings.create("v/agents/templates/reader")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell info = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "no-state-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertNotNull(info);
		// stateConfig should be absent entirely (not present as empty map)
		assertNull(RT.getIn(info, Strings.create("stateConfig")),
			"Template-created agent should have no stateConfig field in info output");
	}

	@Test
	public void testCreateAutoDefaults() {
		// Agent created with no config gets sensible defaults
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "auto-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("auto-agent");
		assertNotNull(agent);

		// Should have auto-set operation: llmagent:chat
		AMap<AString, ACell> config = agent.getConfig();
		assertNotNull(config);
		assertEquals(Strings.create("v/ops/llmagent/chat"), config.get(Fields.OPERATION),
			"Auto-default should set operation to llmagent:chat");
	}

	// ========== Result in run output ==========

	@Test
	public void testTriggerOutputIncludesResult() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "result-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of(
					"config", Maps.of(
						"llmOperation", "v/test/ops/llm",
						"systemPrompt", "You are helpful."
					)
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User resultUser = engine.getVenueState().users().get(ALICE_DID);
		AgentState resultAgent = resultUser.agent("result-agent");
		Blob resultSid = Blob.fromHex("ffff0001ffff0001ffff0001ffff0001");
		resultAgent.ensureSession(resultSid, ALICE_DID);
		resultAgent.appendSessionPending(resultSid, Maps.of(
			Strings.intern("content"), Strings.create("hello")));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "result-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		// Trigger output's `result` is now the bare lean response value (Sub-stage 2.4),
		// not a {response: ...} wrapper.
		AString response = RT.ensureString(RT.getIn(result, Fields.RESULT));
		assertNotNull(response, "Trigger output should include the transition response");
		assertTrue(response.toString().length() > 0, "Response should not be empty");
	}

	// ========== agent:request ==========

	@Test
	public void testRequestCreatesTask() {
		// Create agent with default operation
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit a request with wait — blocks until agent completes the task
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "task-agent", Fields.INPUT, Maps.of("question", "What is 2+2?"),
				Fields.WAIT, CVMLong.create(5000)),
			RequestContext.of(ALICE_DID));

		ACell result = requestJob.awaitResult(5000);
		assertNotNull(result, "Request should be completed by the agent");

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		// Task should be removed from tasks after completion
		assertEquals(0, agent.getTasks().count(), "Tasks should be empty after completion");
	}

	@Test
	public void testRequestTaskCompletion() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "completing-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit a request with wait=true (indefinite)
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "completing-agent", Fields.INPUT, Maps.of("data", "hello"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		// Wait for completion
		ACell result = requestJob.awaitResult(5000);
		assertNotNull(result);

		// Result is the task job data; its output should contain what test:taskcomplete returns
		ACell completed = RT.getIn(result, Fields.OUTPUT, "completed");
		assertNotNull(completed, "Task output should contain 'completed' from test:taskcomplete");
	}

	@Test
	public void testRequestAsync() {
		// Use v/test/ops/never as the transition so the agent starts but
		// never completes. This makes the async-submit assertion
		// deterministic — the job genuinely cannot finish, so isFinished()
		// is reliably false regardless of timing.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "async-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit request — should return immediately with a non-finished Job.
		// Behavioural guarantee: agent_request is non-blocking; the caller
		// can poll or awaitResult(timeout) on the returned Job.
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "async-agent", Fields.INPUT, Maps.of("data", "async")),
			RequestContext.of(ALICE_DID));

		// Job must not be finished — the agent's transition is "never"
		assertFalse(requestJob.isFinished(),
			"Job should not be finished when the agent transition never completes");

		// awaitResult with a short timeout should throw rather than block forever
		try {
			requestJob.awaitResult(100);
			fail("awaitResult should throw when the job cannot complete within the timeout");
		} catch (covia.exception.JobFailedException e) {
			// Expected — timeout
		}
	}

	@Test
	public void testRequestToNonExistentAgent() {
		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "ghost-agent", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		try {
			requestJob.awaitResult(5000);
			fail("Should fail for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, requestJob.getStatus());
		}
	}

	@Test
	public void testRequestToTerminatedAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dead-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("dead-agent").setStatus(AgentState.TERMINATED);

		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "dead-agent", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		try {
			requestJob.awaitResult(5000);
			fail("Should fail for terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, requestJob.getStatus());
		}
	}

	@Test
	public void testRequestTimelineIncludesTaskResults() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "timeline-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job requestJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "timeline-agent", Fields.INPUT, Maps.of("task", "audit"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		requestJob.awaitResult(5000);

		// Check the timeline
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("timeline-agent");
		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline, "Timeline should exist");
		assertEquals(1, timeline.count(), "Should have one timeline entry");

		// Timeline entry should contain taskResults
		ACell entry = timeline.get(0);
		ACell taskResults = RT.getIn(entry, Fields.TASK_RESULTS);
		assertNotNull(taskResults, "Timeline entry should include taskResults");
	}

	@Test
	public void testMultipleRequestsProcessed() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "multi-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit two requests with wait
		Job req1 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "multi-agent", Fields.INPUT, Maps.of("n", "1"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		Job req2 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "multi-agent", Fields.INPUT, Maps.of("n", "2"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		// Both should complete eventually
		req1.awaitResult(5000);
		req2.awaitResult(5000);

		assertTrue(req1.isComplete(), "Request 1 should be complete");
		assertTrue(req2.isComplete(), "Request 2 should be complete");

		// All tasks should be cleared
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-agent");
		assertEquals(0, agent.getTasks().count(), "All tasks should be cleared");
	}

	// ========== run loop — one task per cycle (Sub-stage 2.2) ==========

	/**
	 * Single-task case: the transition should receive a one-element tasks vector,
	 * recorded on the timeline entry.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testSingleTaskTimelineHasOneTask() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "single-task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "single-task-agent", Fields.INPUT, Maps.of("q", "one"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		req.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("single-task-agent");
		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count(), "Should produce exactly one cycle");

		AVector<ACell> tasksOnEntry = (AVector<ACell>) RT.getIn(timeline.get(0), Fields.TASKS);
		assertNotNull(tasksOnEntry, "Timeline entry should record the picked task");
		assertEquals(1, tasksOnEntry.count(), "Cycle should pick exactly one task");
	}

	/**
	 * Multi-task case: the run loop should fan tasks out across cycles.
	 * Two queued tasks → two timeline entries, each with a one-element tasks vector.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testMultiTaskFansOutAcrossCycles() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "fanout-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req1 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "fanout-agent", Fields.INPUT, Maps.of("n", "1"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		Job req2 = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "fanout-agent", Fields.INPUT, Maps.of("n", "2"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		req1.awaitResult(5000);
		req2.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("fanout-agent");
		AVector<ACell> timeline = agent.getTimeline();

		// Each cycle that picks a task records a tasks vector. Cycles with no
		// task picked (e.g., a final inbox-only or wake-only cycle) omit it.
		long cyclesThatPickedATask = 0;
		for (long i = 0; i < timeline.count(); i++) {
			AVector<ACell> picked = (AVector<ACell>) RT.getIn(timeline.get(i), Fields.TASKS);
			if (picked == null || picked.count() == 0) continue;
			assertEquals(1, picked.count(),
				"Each cycle must pick at most one task — cycle " + i + " picked " + picked.count());
			cyclesThatPickedATask++;
		}
		assertEquals(2, cyclesThatPickedATask,
			"Two queued tasks must fan out across exactly two cycles");
		assertEquals(0, agent.getTasks().count(), "All tasks should be cleared");
	}

	// ========== lean transition contract (Sub-stage 3) ==========

	/**
	 * Lean transition returns {response, taskComplete}. Framework must
	 * synthesize a taskResults entry for the picked task so the calling Job
	 * receives the response as its output. Uses the in-suite taskcomplete op
	 * which is now itself written against the lean contract — this test
	 * verifies the full round trip including framework translation.
	 */
	@Test
	public void testLeanTransitionCompletesTask() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "lean-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell userPayload = Maps.of("q", "lean-please");
		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "lean-agent", Fields.INPUT, userPayload,
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell envelope = req.awaitResult(5000);

		ACell output = RT.getIn(envelope, Fields.OUTPUT);
		assertNotNull(output, "Lean transition must produce output via framework synthesis");
		ACell completed = RT.getIn(output, Strings.create("completed"));
		assertEquals(userPayload, completed,
			"Lean transition's response.completed should echo newInput");
		assertEquals(Status.COMPLETE, RT.ensureString(RT.getIn(envelope, Fields.STATUS)));
	}

	// ========== Sub-stage 2.7c — agent:complete-task / agent:fail-task contract ==========

	/**
	 * Direct invocation of {@code agent:complete-task} without an enclosing
	 * cycle context (no agentId/taskId in RequestContext) must fail —
	 * callers cannot complete arbitrary tasks; the op only accepts the task
	 * the framework currently has in scope.
	 */
	@Test
	public void testCompleteTaskRejectsUnscopedCall() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/complete-task",
			Maps.of(Fields.RESULT, Strings.create("nope")),
			RequestContext.of(ALICE_DID));
		assertThrows(covia.exception.JobFailedException.class, () -> job.awaitResult(2000),
			"complete-task must reject calls without (agentId, taskId) scope");
		assertEquals(Status.FAILED, job.getStatus());
	}

	@Test
	public void testFailTaskRejectsUnscopedCall() {
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/fail-task",
			Maps.of(Fields.ERROR, Strings.create("oops")),
			RequestContext.of(ALICE_DID));
		assertThrows(covia.exception.JobFailedException.class, () -> job.awaitResult(2000),
			"fail-task must reject calls without (agentId, taskId) scope");
		assertEquals(Status.FAILED, job.getStatus());
	}

	/**
	 * Ordering invariant: a caller's {@code awaitResult} must observe the
	 * cycle's timeline write before returning. The venue op parks a deferred
	 * completion; the framework drains it after {@code mergeRunResult}, so
	 * the timeline is durable by the time the pending Job completes.
	 *
	 * <p>If this regresses, completion would race with the merge and the
	 * caller could see an empty timeline immediately after a successful
	 * task return — exactly the bug fixed in S2.7c‑2.</p>
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testCallerSeesTimelineAfterAwait() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ordering-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "ordering-agent",
				Fields.INPUT, Maps.of("q", "ordering"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		req.awaitResult(5000);

		// awaitResult returned — timeline MUST be visible at this point.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("ordering-agent");
		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline);
		assertEquals(1, timeline.count(),
			"Timeline entry must be persisted before awaitResult returns");
		ACell taskResults = RT.getIn(timeline.get(0), Fields.TASK_RESULTS);
		assertNotNull(taskResults, "Cycle must record taskResults from the deferred completion");
	}

	/**
	 * Envelope shape: a successful task completion produces a Job envelope
	 * with id/status/output, and the agent's task Index is empty afterward.
	 * Verifies the venue op cleans up state and the framework forwards the
	 * envelope unchanged to the caller.
	 */
	@Test
	public void testCompleteTaskEnvelopeShape() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "envelope-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job req = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "envelope-agent",
				Fields.INPUT, Maps.of("q", "envelope"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell envelope = req.awaitResult(5000);

		assertEquals(Status.COMPLETE, RT.ensureString(RT.getIn(envelope, Fields.STATUS)));
		assertNotNull(RT.getIn(envelope, Fields.ID), "Envelope must carry the task/job id");
		assertNotNull(RT.getIn(envelope, Fields.OUTPUT), "Envelope must carry the task output");

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("envelope-agent");
		assertEquals(0, agent.getTasks().count(),
			"Venue op must remove the task entry from the agent's Index");
	}

	// ========== agent:request — sync/async consistency ==========

	@Test
	public void testRequestSyncViaAwaitResult() {
		// The standard sync pattern: invokeOperation + awaitResult.
		// No 'wait' param needed — the Job lifecycle handles blocking.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "sync-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "sync-agent", Fields.INPUT, Maps.of("q", "test")),
			RequestContext.of(ALICE_DID));

		// Job is not yet finished
		assertFalse(job.isFinished());

		// awaitResult blocks until the run loop completes the Job
		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertTrue(job.isComplete());

		// Output is the task result
		ACell output = RT.getIn(result, Fields.OUTPUT);
		assertNotNull(output, "Sync result should contain task output");
	}

	@Test
	public void testRequestAsyncViaPoll() {
		// The standard async pattern: invokeOperation, return immediately, poll later.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "poll-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "poll-agent", Fields.INPUT, Maps.of("q", "poll")),
			RequestContext.of(ALICE_DID));

		// Job is STARTED, not COMPLETE — async client can return this to the caller
		assertEquals(Status.STARTED, job.getStatus());
		assertFalse(job.isFinished());

		// Simulate polling: wait then check
		job.awaitResult(5000);
		assertTrue(job.isComplete(), "Job should be complete after agent processes the task");

		// Can retrieve the result via getOutput after polling
		ACell output = job.getOutput();
		assertNotNull(output, "Polling should retrieve the output");
	}

	// ========== session minting (Stage 1) ==========

	@Test
	public void testMessageMintsSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-msg"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-msg", Fields.MESSAGE, "hi"),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AString sid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sid, "Message response should carry a minted sessionId");
		assertEquals(32, sid.count(), "sessionId should be 16-byte hex (32 chars)");

		// Session record created lazily
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-msg");
		Blob sidBlob = Blob.fromHex(sid.toString());
		AMap<AString, ACell> session = agent.getSession(sidBlob);
		assertNotNull(session, "Session record should be created");
		assertNotNull(session.get(Strings.intern("c")));
		assertNotNull(session.get(Strings.intern("history")));
		assertNotNull(session.get(Strings.intern("pending")));
		assertNotNull(session.get(Strings.intern("meta")));
	}

	@Test
	public void testMessageReusesProvidedSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-reuse"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// First call mints a sid
		Job first = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-reuse", Fields.MESSAGE, "one"),
			RequestContext.of(ALICE_DID));
		AString sid = RT.ensureString(RT.getIn(first.awaitResult(5000), Fields.SESSION_ID));
		assertNotNull(sid);

		// Second call with same sid — echoed back, no new session created
		Job second = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-reuse", Fields.MESSAGE, "two",
				Fields.SESSION_ID, sid),
			RequestContext.of(ALICE_DID));
		ACell result2 = second.awaitResult(5000);
		assertEquals(sid, RT.ensureString(RT.getIn(result2, Fields.SESSION_ID)));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-reuse");
		assertEquals(1, agent.getSessions().count(),
			"Reusing an existing sid must not create a second session");
	}

	@Test
	public void testRequestMintsSessionAndAttachesToTaskRow() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "session-req",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit async so we can inspect the task row while it's still pending
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "session-req", Fields.INPUT, Maps.of("q", "hello")),
			RequestContext.of(ALICE_DID));

		// Job is still pending (never completes) — inspect the task row
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-req");
		assertEquals(1, agent.getTasks().count(), "Task should be queued");
		// Take the only task row and assert it carries a sessionId
		var entry = agent.getTasks().entrySet().iterator().next();
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> row = (AMap<AString, ACell>) entry.getValue();
		AString taskSid = RT.ensureString(row.get(Fields.SESSION_ID));
		assertNotNull(taskSid, "Task row should record the session it belongs to");

		// And the session itself exists
		Blob sidBlob = Blob.fromHex(taskSid.toString());
		assertNotNull(agent.getSession(sidBlob), "Session record should be created");

		// Clean up: cancel the task so the test doesn't leave a running loop
		job.cancel();
	}

	@Test
	public void testRequestResponseEnvelopeCarriesSessionId() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "session-env",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "session-env", Fields.INPUT, Maps.of("q", "ping")),
			RequestContext.of(ALICE_DID));

		ACell result = job.awaitResult(5000);
		AString sid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sid, "Completed request envelope must include sessionId");
	}

	@Test
	public void testTriggerMintsSession() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-trig",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "session-trig", Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		AString sid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		assertNotNull(sid, "Trigger response should carry a sessionId");

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("session-trig");
		assertEquals(1, agent.getSessions().count());
	}

	@Test
	public void testInvalidSessionIdFails() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "session-bad"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "session-bad", Fields.MESSAGE, "hi",
				Fields.SESSION_ID, "not-hex-zz"),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail for malformed sessionId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== agent:query ==========

	@Test
	public void testQueryAgent() {
		// Create an agent with config and state
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, "query-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo"),
				AgentState.KEY_STATE, Maps.of("counter", 0)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Query it
		Job queryJob = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "query-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = queryJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("query-agent"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
		assertNotNull(RT.getIn(result, AgentState.KEY_CONFIG));
		// Summary returns timelineLength and tasks count, not full state
		assertNotNull(RT.getIn(result, Strings.intern("timelineLength")));
	}

	@Test
	public void testQueryNonExistentAgent() {
		Job queryJob = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "ghost"),
			RequestContext.of(ALICE_DID));
		try {
			queryJob.awaitResult(5000);
			fail("Should fail for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, queryJob.getStatus());
		}
	}

	@Test
	public void testQueryTerminatedAgent() {
		// Create and terminate an agent
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "term-query"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("term-query").setStatus(AgentState.TERMINATED);

		// Query should still work — you can read terminated agents
		Job queryJob = engine.jobs().invokeOperation(
			"v/ops/agent/info",
			Maps.of(Fields.AGENT_ID, "term-query"),
			RequestContext.of(ALICE_DID));
		ACell result = queryJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.TERMINATED, RT.getIn(result, Fields.STATUS));
	}

	// ========== agent:list ==========

	@Test
	public void testListAgentsEmpty() {
		// New user with no agents
		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(BOB_DID));
		ACell result = listJob.awaitResult(5000);

		assertNotNull(result);
		ACell agents = RT.getIn(result, "agents");
		assertNotNull(agents);
		assertTrue(agents instanceof AVector);
		assertEquals(0, ((AVector<?>) agents).count());
	}

	@Test
	public void testListAgents() {
		// Create two agents
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "agent-a"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "agent-b"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		ACell agents = RT.getIn(result, "agents");
		assertTrue(agents instanceof AVector);
		assertEquals(2, ((AVector<?>) agents).count());

		// Each entry should have agentId, status, tasks count
		@SuppressWarnings("unchecked")
		AVector<ACell> agentList = (AVector<ACell>) agents;
		for (long i = 0; i < agentList.count(); i++) {
			ACell entry = agentList.get(i);
			assertNotNull(RT.getIn(entry, Fields.AGENT_ID));
			assertEquals(AgentState.SLEEPING, RT.getIn(entry, Fields.STATUS));
			assertNotNull(RT.getIn(entry, Fields.TASKS));
		}
	}

	@Test
	public void testListAgentsIsolation() {
		// Alice's agents should not appear in Bob's list
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "alice-only"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job bobList = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(BOB_DID));
		ACell result = bobList.awaitResult(5000);

		ACell agents = RT.getIn(result, "agents");
		assertEquals(0, ((AVector<?>) agents).count());
	}

	// ========== agent:delete ==========

	@Test
	public void testDeleteAgent() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "del-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job delJob = engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "del-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = delJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.TERMINATED, RT.getIn(result, Fields.STATUS));

		// Record still exists with TERMINATED status
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("del-agent");
		assertNotNull(agent, "Agent record should still exist");
		assertEquals(AgentState.TERMINATED, agent.getStatus());
	}

	@Test
	public void testDeleteAgentWithRemove() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rem-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job delJob = engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "rem-agent", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = delJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.REMOVED));

		// Record should be gone
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("rem-agent");
		assertNull(agent, "Agent record should be removed");
	}

	@Test
	public void testDeleteThenRecreate() {
		// Delete without remove — name is blocked
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Create again without overwrite — idempotent no-op, created=false
		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(result2, Fields.CREATED));
		assertEquals(AgentState.TERMINATED, RT.getIn(result2, Fields.STATUS));
	}

	@Test
	public void testDeleteWithRemoveThenRecreate() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "clean-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Delete with remove
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "clean-agent", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Recreate — should succeed with created=true
		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "clean-agent"),
			RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result2, Fields.CREATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result2, Fields.STATUS));
	}

	// ========== agent:create with overwrite ==========

	@Test
	public void testCreateOverwriteTerminated() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ow-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Terminate it
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "ow-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Overwrite with new config
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ow-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete"),
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CREATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Config should be the new one
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("ow-agent");
		AMap<AString, ACell> config = agent.getConfig();
		assertEquals(Strings.create("v/test/ops/taskcomplete"), config.get(Fields.OPERATION));
	}

	@Test
	public void testCreateOverwriteSleepingUpdatesInPlace() {
		// Create a SLEEPING agent with initial config and let it accrue some
		// state we want to preserve across the update
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "live-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Manually seed session pending so we can verify it survives
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState pre = user.agent("live-ow");
		Blob owSid = Blob.fromHex("abab0001abab0001abab0001abab0001");
		pre.ensureSession(owSid, ALICE_DID);
		pre.appendSessionPending(owSid, Strings.create("hello"));
		long preTs = pre.getTs();

		// Overwrite a SLEEPING agent — in-place update, status preserved
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "live-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete"),
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.FALSE, RT.getIn(result, Fields.CREATED), "should be updated, not created");
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.UPDATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Config replaced
		AgentState post = user.agent("live-ow");
		assertEquals(Strings.create("v/test/ops/taskcomplete"), post.getConfig().get(Fields.OPERATION));
		// Session pending preserved (the unprocessed "hello" message is still there)
		assertTrue(post.hasSessionPending(), "session pending should survive in-place update");
		AVector<ACell> owPending = post.getSessionPending(owSid);
		assertEquals(1, owPending.count());
		assertEquals(Strings.create("hello"), owPending.get(0));
		// ts advanced
		assertTrue(post.getTs() >= preTs, "ts should advance on update");
	}

	@Test
	public void testCreateOverwriteSuspendedUpdatesInPlace() {
		// Create then suspend the agent — error state, dormant
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "susp-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("susp-ow").suspend(Strings.create("simulated failure"));
		assertEquals(AgentState.SUSPENDED, user.agent("susp-ow").getStatus());

		// Overwrite SUSPENDED — in-place update, error and status preserved
		ACell result = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "susp-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete"),
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(CVMBool.FALSE, RT.getIn(result, Fields.CREATED));
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.UPDATED));

		AgentState post = user.agent("susp-ow");
		assertEquals(Strings.create("v/test/ops/taskcomplete"), post.getConfig().get(Fields.OPERATION));
		// Status stays SUSPENDED — caller can resume separately
		assertEquals(AgentState.SUSPENDED, post.getStatus());
		assertEquals(Strings.create("simulated failure"), post.getError(),
			"error should be preserved across in-place update");
	}

	@Test
	public void testCreateOverwriteRunningFails() {
		// Create then force the agent into RUNNING (simulating an active transition)
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "run-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("run-ow").setStatus(AgentState.RUNNING);

		// Overwrite RUNNING must fail loudly — race risk
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "run-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete"),
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		try {
			job.awaitResult(5000);
			fail("Should fail when overwriting a RUNNING agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
			// Error message should mention RUNNING and how to recover
			String err = job.getErrorMessage();
			assertTrue(err != null && err.contains("RUNNING"),
				"error should mention RUNNING: " + err);
		}

		// Original config must be untouched
		assertEquals(Strings.create("v/test/ops/echo"),
			user.agent("run-ow").getConfig().get(Fields.OPERATION));
	}

	@Test
	public void testCreateOverwriteFalseIsNoOp() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Without overwrite — idempotent no-op
		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "no-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.FALSE, RT.getIn(result, Fields.CREATED));

		// Config should still be original
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("no-ow");
		assertEquals(Strings.create("v/test/ops/echo"), agent.getConfig().get(Fields.OPERATION));
	}

	// ========== agent:list — filter TERMINATED ==========

	@Test
	public void testListAgentsHidesTerminated() {
		// Create two agents, delete one
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "alive"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dead"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "dead"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Default list should hide terminated
		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(1, agents.count(), "Terminated agent should be hidden");
		assertEquals(Strings.create("alive"), RT.getIn(agents.get(0), Fields.AGENT_ID));
	}

	@Test
	public void testListAgentsIncludeTerminated() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "alive2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dead2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "dead2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// With includeTerminated=true
		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list",
			Maps.of(Fields.INCLUDE_TERMINATED, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(2, agents.count(), "Should include both agents when includeTerminated=true");
	}

	@Test
	public void testListAgentsAllTerminated() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "doomed"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "doomed"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation(
			"v/ops/agent/list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(0, agents.count(), "All terminated — list should be empty");
	}

	// ========== agent:cancelTask ==========

	@Test
	public void testCancelTask() {
		// Create agent and add a task
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		Blob taskId = Blob.createRandom(new java.util.Random(42), 16);
		agent.addTask(taskId, Maps.of("question", "What is 2+2?"));
		assertEquals(1, agent.getTasks().count());

		// Cancel the task
		Job cancelJob = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "task-agent",
				Fields.TASK_ID, taskId.toHexString()),
			RequestContext.of(ALICE_DID));
		ACell result = cancelJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CANCELLED));
		assertEquals(Strings.create("task-agent"), RT.getIn(result, Fields.AGENT_ID));

		// Task should be gone
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void testCancelTaskNotFound() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "cancel-nf",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job cancelJob = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "cancel-nf",
				Fields.TASK_ID, "0000000000000000deadbeefdeadbeef"),
			RequestContext.of(ALICE_DID));

		try {
			cancelJob.awaitResult(5000);
			fail("Should fail — task does not exist");
		} catch (Exception e) {
			assertEquals(Status.FAILED, cancelJob.getStatus());
		}
	}

	@Test
	public void testCancelTaskMissingParams() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "cancel-mp",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Missing taskId
		Job job1 = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "cancel-mp"),
			RequestContext.of(ALICE_DID));
		try {
			job1.awaitResult(5000);
			fail("Should fail — taskId missing");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job1.getStatus());
		}

		// Missing agentId
		Job job2 = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.TASK_ID, "abcd"),
			RequestContext.of(ALICE_DID));
		try {
			job2.awaitResult(5000);
			fail("Should fail — agentId missing");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job2.getStatus());
		}
	}

	@Test
	public void testCancelTaskMultiple() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "multi-task",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("multi-task");

		java.util.Random rng = new java.util.Random(123);
		Blob task1 = Blob.createRandom(rng, 16);
		Blob task2 = Blob.createRandom(rng, 16);
		Blob task3 = Blob.createRandom(rng, 16);
		agent.addTask(task1, Maps.of("q", "one"));
		agent.addTask(task2, Maps.of("q", "two"));
		agent.addTask(task3, Maps.of("q", "three"));
		assertEquals(3, agent.getTasks().count());

		// Cancel the middle one
		engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "multi-task",
				Fields.TASK_ID, task2.toHexString()),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertEquals(2, agent.getTasks().count());
		assertNull(agent.getTasks().get(task2), "Cancelled task should be gone");
		assertNotNull(agent.getTasks().get(task1), "Other tasks should remain");
		assertNotNull(agent.getTasks().get(task3), "Other tasks should remain");
	}

	@Test
	public void testCancelTaskInvalidHex() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "cancel-hex",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"v/ops/agent/cancel-task",
			Maps.of(Fields.AGENT_ID, "cancel-hex",
				Fields.TASK_ID, "not-valid-hex!!!"),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should fail — invalid hex");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	// ========== AgentState.removeTask ==========

	@Test
	public void testAgentStateRemoveTask() {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("rm-task-agent", null, null);

		Blob taskId = Blob.createRandom(new java.util.Random(0), 16);
		agent.addTask(taskId, Maps.of("data", "test"));
		assertEquals(1, agent.getTasks().count());

		agent.removeTask(taskId);
		assertEquals(0, agent.getTasks().count());
	}

	@Test
	public void testAgentStateRemoveNonexistentTask() {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("rm-noop-agent", null, null);

		Blob taskId = Blob.createRandom(new java.util.Random(1), 16);
		// Removing a task that doesn't exist should be a no-op
		agent.removeTask(taskId);
		assertEquals(0, agent.getTasks().count());
	}

	// ========== AgentState lifecycle ==========

	@Test
	public void testAgentStateLifecycle() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure("did:key:zTest");

		AgentState agent = user.ensureAgent("lifecycle-agent", null, null);
		assertTrue(agent.exists());
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertTrue(agent.getTs() > 0, "Agent should have a ts after creation");
		assertEquals(0, agent.getTasks().count(), "New agent should have empty tasks");
		assertEquals(0, agent.getPending().count(), "New agent should have empty pending");

		agent.setStatus(AgentState.RUNNING);
		assertEquals(AgentState.RUNNING, agent.getStatus());

		agent.setStatus(AgentState.SUSPENDED);
		assertEquals(AgentState.SUSPENDED, agent.getStatus());

		agent.setStatus(AgentState.SLEEPING);
		assertEquals(AgentState.SLEEPING, agent.getStatus());

		assertNull(agent.getError());
		agent.setError(Strings.create("something went wrong"));
		assertEquals(Strings.create("something went wrong"), agent.getError());
		agent.clearError();
		assertNull(agent.getError());

		Blob mutSid = Blob.fromHex("cdcd0001cdcd0001cdcd0001cdcd0001");
		agent.ensureSession(mutSid, ALICE_DID);
		agent.appendSessionPending(mutSid, Maps.of("content", "hello"));
		AVector<ACell> pending = agent.getSessionPending(mutSid);
		assertNotNull(pending);
		assertEquals(1, pending.count());
		agent.appendSessionPending(mutSid, Maps.of("content", "world"));
		pending = agent.getSessionPending(mutSid);
		assertEquals(2, pending.count());
	}

	// ========== agent:update merge semantics ==========

	@Test
	public void testUpdateMergesStateConfig() {
		// Create agent with full config
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "merge-test",
			Strings.create("overwrite"), convex.core.data.prim.CVMBool.TRUE,
			AgentState.KEY_STATE, Maps.of(AgentState.KEY_CONFIG, Maps.of(
				Strings.create("model"), Strings.create("gpt-4.1-mini"),
				Strings.create("systemPrompt"), Strings.create("You are a test agent"),
				Strings.create("tools"), Vectors.of(
					(ACell) Strings.create("v/ops/covia/read"),
					(ACell) Strings.create("v/ops/covia/write")),
				Strings.create("caps"), Vectors.of(
					(ACell) Maps.of(Strings.create("with"), Strings.create("w/"), Strings.create("can"), Strings.create("crud"))))));
		engine.jobs().invokeOperation("v/ops/agent/create", createInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Update just the model — other fields should survive
		ACell updateInput = Maps.of(
			Fields.AGENT_ID, "merge-test",
			AgentState.KEY_STATE, Maps.of(AgentState.KEY_CONFIG, Maps.of(
				Strings.create("model"), Strings.create("gpt-5.4-mini"))));
		engine.jobs().invokeOperation("v/ops/agent/update", updateInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Verify: model changed, everything else preserved
		ACell config = engine.resolvePath(Strings.create("g/merge-test/state/config"), RequestContext.of(ALICE_DID));
		assertEquals(Strings.create("gpt-5.4-mini"), RT.getIn(config, Strings.create("model")));
		assertEquals(Strings.create("You are a test agent"), RT.getIn(config, Strings.create("systemPrompt")));
		assertNotNull(RT.getIn(config, Strings.create("tools")), "tools should survive model update");
		assertNotNull(RT.getIn(config, Strings.create("caps")), "caps should survive model update");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testUpdateNullValueDocumentsCurrentBehaviour() {
		// Document current behaviour: setting a config field to null via update
		// stores null at that key — it does NOT remove the key. To remove a field,
		// recreate the agent with overwrite:true. This test pins down the
		// behaviour so any change to it shows up in the diff.
		ACell createInput = Maps.of(
			Fields.AGENT_ID, "null-test",
			Strings.create("overwrite"), CVMBool.TRUE,
			AgentState.KEY_STATE, Maps.of(AgentState.KEY_CONFIG, Maps.of(
				Strings.create("model"), Strings.create("gpt-4o"),
				Strings.create("systemPrompt"), Strings.create("Original prompt"))));
		engine.jobs().invokeOperation("v/ops/agent/create", createInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Update systemPrompt to null
		ACell updateInput = Maps.of(
			Fields.AGENT_ID, "null-test",
			AgentState.KEY_STATE, Maps.of(AgentState.KEY_CONFIG, Maps.of(
				Strings.create("systemPrompt"), null)));
		engine.jobs().invokeOperation("v/ops/agent/update", updateInput, RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Verify: model preserved, systemPrompt key still exists with null value
		ACell config = engine.resolvePath(Strings.create("g/null-test/state/config"), RequestContext.of(ALICE_DID));
		assertEquals(Strings.create("gpt-4o"), RT.getIn(config, Strings.create("model")));
		AMap<AString, ACell> configMap = (AMap<AString, ACell>) config;
		assertTrue(configMap.containsKey(Strings.create("systemPrompt")),
			"key should still exist after setting to null (current behaviour)");
		assertNull(configMap.get(Strings.create("systemPrompt")),
			"value should be null (current behaviour — to fully remove, recreate with overwrite:true)");
	}

	// ========== Templates as lattice data (v/agents/templates/) ==========

	@Test
	@SuppressWarnings("unchecked")
	public void testTemplatesDiscoverableInLattice() {
		// covia_list path=v/agents/templates returns the 7 standard templates
		Job job = engine.jobs().invokeOperation(
			"v/ops/covia/list",
			Maps.of(Strings.create("path"), Strings.create("v/agents/templates")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals(CVMLong.create(7), RT.getIn(result, Strings.create("count")));
		AVector<ACell> keys = RT.ensureVector(RT.getIn(result, Strings.create("keys")));
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < keys.count(); i++) names.add(keys.get(i).toString());
		assertTrue(names.containsAll(java.util.List.of(
			"minimal", "reader", "worker", "manager", "analyst", "full", "goaltree")));
	}

	@Test
	public void testManagerTemplateRuntimeToolPalette() {
		// Create from v/agents/templates/manager and verify the runtime tool palette
		// includes both operation tools and harness tools (subgoal/compact/more_tools).
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "mgr-runtime",
					Fields.CONFIG, Strings.create("v/agents/templates/manager")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		java.util.Set<String> names = runtimeToolNames("mgr-runtime");

		// Operation tools resolved from paths
		assertTrue(names.contains("agent_create"), "manager should have agent_create");
		assertTrue(names.contains("agent_request"), "manager should have agent_request");
		assertTrue(names.contains("grid_run"), "manager should have grid_run");
		assertTrue(names.contains("covia_read"), "manager should have covia_read");
		// Harness tools resolved by name
		assertTrue(names.contains("subgoal"), "manager should have subgoal");
		assertTrue(names.contains("compact"), "manager should have compact");
		assertTrue(names.contains("more_tools"), "manager should have more_tools");
		// No leakage of operation paths as tool names
		assertFalse(names.contains("v/ops/agent/create"), "operation path should not appear as tool name");
	}

	@Test
	public void testGoaltreeTemplateGetsAllSevenHarnessTools() {
		// template:goaltree explicitly lists all 7 harness tools — verify all resolve
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "gt-runtime",
					Fields.CONFIG, Strings.create("v/agents/templates/goaltree")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		java.util.Set<String> names = runtimeToolNames("gt-runtime");
		for (String harness : new String[]{"subgoal", "complete", "fail", "compact",
		                                    "context_load", "context_unload", "more_tools"}) {
			assertTrue(names.contains(harness), "goaltree should have " + harness);
		}
	}

	@Test
	public void testReaderTemplateHasNoHarnessTools() {
		// template:reader is read-only data analysis — operations only, no harness tools
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rdr-runtime",
					Fields.CONFIG, Strings.create("v/agents/templates/reader")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		java.util.Set<String> names = runtimeToolNames("rdr-runtime");
		// Operations: yes
		assertTrue(names.contains("covia_read"), "reader should have covia_read");
		assertTrue(names.contains("covia_list"), "reader should have covia_list");
		// Harness: no
		assertFalse(names.contains("subgoal"), "reader should NOT have subgoal");
		assertFalse(names.contains("compact"), "reader should NOT have compact");
		assertFalse(names.contains("more_tools"), "reader should NOT have more_tools");
	}

	/** Builds the L3 input via the same code path as agent:context and returns tool names. */
	@SuppressWarnings("unchecked")
	private java.util.Set<String> runtimeToolNames(String agentId) {
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(agentId);
		assertNotNull(agent, "agent " + agentId + " should exist");
		covia.adapter.agent.GoalTreeAdapter adapter =
			(covia.adapter.agent.GoalTreeAdapter) engine.getAdapter("goaltree");
		AMap<AString, ACell> l3 = adapter.buildFirstIterationL3Input(
			agent.getConfig(), agent.getState(), null, RequestContext.of(ALICE_DID));
		AVector<ACell> tools = RT.ensureVector(l3.get(Strings.create("tools")));
		assertNotNull(tools, "L3 input should have a tools array");
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < tools.count(); i++) {
			ACell name = ((AMap<AString, ACell>) tools.get(i)).get(Strings.create("name"));
			if (name != null) names.add(name.toString());
		}
		return names;
	}
}
