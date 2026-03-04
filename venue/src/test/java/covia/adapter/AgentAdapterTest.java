package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.VenueState;

/**
 * Tests for the AgentAdapter: create, message, and run operations.
 */
public class AgentAdapterTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final AString BOB_DID = Strings.create("did:key:z6MkBob");

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	// ========== agent:create ==========

	@Test
	public void testCreateAgent() {
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("my-assistant")
		);
		Job job = engine.jobs().invokeOperation(
			Strings.create("agent:create"), input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult();

		assertNotNull(result, "Create should return a result");
		assertEquals(Strings.create("my-assistant"),
			RT.getIn(result, Fields.AGENT_ID));
		assertEquals(AgentState.SLEEPING,
			RT.getIn(result, Fields.STATUS));
	}

	@Test
	public void testCreateAgentWithConfig() {
		AMap<AString, ACell> config = Maps.of(
			"model", Strings.create("gpt-4"),
			"temperature", Strings.create("0.7")
		);
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("configured-agent"),
			Fields.CONFIG, config
		);
		Job job = engine.jobs().invokeOperation(
			Strings.create("agent:create"), input, RequestContext.of(ALICE_DID));
		job.awaitResult();

		// Verify config is stored in lattice
		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNotNull(user);
		AgentState agent = user.agent(Strings.create("configured-agent"));
		assertNotNull(agent);
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertNotNull(storedConfig, "Config should be stored");
	}

	@Test
	public void testCreateMissingAgentId() {
		ACell input = Maps.of("foo", Strings.create("bar"));
		Job job = engine.jobs().invokeOperation(
			Strings.create("agent:create"), input, RequestContext.of(ALICE_DID));

		// Should fail because agentId is missing
		try {
			job.awaitResult();
			fail("Should have thrown due to missing agentId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testCreateIdempotent() {
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create("idempotent-agent")
		);

		// First create
		Job job1 = engine.jobs().invokeOperation(
			Strings.create("agent:create"), input, RequestContext.of(ALICE_DID));
		job1.awaitResult();

		// Second create should succeed (no-op)
		Job job2 = engine.jobs().invokeOperation(
			Strings.create("agent:create"), input, RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult();

		assertNotNull(result2);
		assertEquals(Strings.create("idempotent-agent"),
			RT.getIn(result2, Fields.AGENT_ID));
	}

	// ========== agent:message ==========

	@Test
	public void testMessageAgent() {
		// Create agent first
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("msg-agent")),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Send a message
		ACell msgInput = Maps.of(
			Fields.AGENT_ID, Strings.create("msg-agent"),
			Fields.MESSAGE, Maps.of("content", Strings.create("hello"))
		);
		Job msgJob = engine.jobs().invokeOperation(
			Strings.create("agent:message"), msgInput, RequestContext.of(ALICE_DID));
		ACell result = msgJob.awaitResult();

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELIVERED));

		// Verify inbox has 1 message (vector)
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("msg-agent"));
		AVector<ACell> inbox = agent.getInbox();
		assertNotNull(inbox, "Inbox should not be null after message");
		assertEquals(1, inbox.count(), "Inbox should have 1 message");
	}

	@Test
	public void testMessageNonExistentAgent() {
		// Ensure user exists but without the agent
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("other-agent")),
			RequestContext.of(ALICE_DID)).awaitResult();

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, Strings.create("ghost-agent"),
			Fields.MESSAGE, Maps.of("content", Strings.create("hello"))
		);
		Job msgJob = engine.jobs().invokeOperation(
			Strings.create("agent:message"), msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult();
			fail("Should have thrown for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	@Test
	public void testMessageTerminatedAgent() {
		// Create agent, then terminate it
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("term-agent")),
			RequestContext.of(ALICE_DID)).awaitResult();

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("term-agent"));
		agent.setStatus(AgentState.TERMINATED);

		// Message should be rejected
		ACell msgInput = Maps.of(
			Fields.AGENT_ID, Strings.create("term-agent"),
			Fields.MESSAGE, Maps.of("content", Strings.create("hello"))
		);
		Job msgJob = engine.jobs().invokeOperation(
			Strings.create("agent:message"), msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult();
			fail("Should have thrown for terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	// ========== agent:run ==========

	@Test
	public void testRunWithEcho() {
		// Create agent
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("echo-agent")),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Send 2 messages
		for (int i = 0; i < 2; i++) {
			engine.jobs().invokeOperation(
				Strings.create("agent:message"),
				Maps.of(
					Fields.AGENT_ID, Strings.create("echo-agent"),
					Fields.MESSAGE, Maps.of("content", Strings.create("msg-" + i))
				),
				RequestContext.of(ALICE_DID)).awaitResult();
		}

		// Run with test:echo transition
		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("echo-agent"),
				Fields.OPERATION, Strings.create("test:echo")
			),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Verify agent state
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("echo-agent"));
		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Status should be sleeping after run");

		// Inbox should be drained
		AVector<ACell> inbox = agent.getInbox();
		assertEquals(0, inbox.count(), "Inbox should be empty after run");

		// Timeline should have 1 entry (one run processes entire inbox)
		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline, "Timeline should not be null");
		assertEquals(1, timeline.count(), "Timeline should have 1 entry");

		// Error should be cleared
		assertNull(agent.getError(), "Error should be null after successful run");
	}

	@Test
	public void testRunNoMessages() {
		// Create agent
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("empty-agent")),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Run with no messages — should be a no-op
		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("empty-agent"),
				Fields.OPERATION, Strings.create("test:echo")
			),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	// ========== User isolation ==========

	@Test
	public void testUserIsolation() {
		// Alice creates agent and sends message
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("shared-name")),
			RequestContext.of(ALICE_DID)).awaitResult();
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("shared-name"),
				Fields.MESSAGE, Maps.of("from", Strings.create("alice"))
			),
			RequestContext.of(ALICE_DID)).awaitResult();

		// Bob creates same-named agent and sends message
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(Fields.AGENT_ID, Strings.create("shared-name")),
			RequestContext.of(BOB_DID)).awaitResult();
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("shared-name"),
				Fields.MESSAGE, Maps.of("from", Strings.create("bob"))
			),
			RequestContext.of(BOB_DID)).awaitResult();

		// Each user's agent should have exactly 1 message
		User alice = engine.getVenueState().users().get(ALICE_DID);
		User bob = engine.getVenueState().users().get(BOB_DID);

		AgentState aliceAgent = alice.agent(Strings.create("shared-name"));
		AgentState bobAgent = bob.agent(Strings.create("shared-name"));

		assertEquals(1, aliceAgent.getInbox().count(),
			"Alice's agent should have 1 message");
		assertEquals(1, bobAgent.getInbox().count(),
			"Bob's agent should have 1 message");
	}

	// ========== AgentState lifecycle ==========

	@Test
	public void testAgentStateLifecycle() {
		AKeyPair kp = AKeyPair.generate();
		VenueState vs = VenueState.create(kp);
		User user = vs.users().ensure(Strings.create("did:key:zTest"));

		// Create agent
		AgentState agent = user.ensureAgent(Strings.create("lifecycle-agent"), null, null);
		assertTrue(agent.exists());
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertTrue(agent.getTs() > 0, "Agent should have a ts after creation");

		// Status transitions
		agent.setStatus(AgentState.RUNNING);
		assertEquals(AgentState.RUNNING, agent.getStatus());

		agent.setStatus(AgentState.SUSPENDED);
		assertEquals(AgentState.SUSPENDED, agent.getStatus());

		agent.setStatus(AgentState.SLEEPING);
		assertEquals(AgentState.SLEEPING, agent.getStatus());

		// Error set/clear
		assertNull(agent.getError());
		agent.setError(Strings.create("something went wrong"));
		assertEquals(Strings.create("something went wrong"), agent.getError());
		agent.clearError();
		assertNull(agent.getError());

		// Message delivery (vector inbox)
		agent.deliverMessage(Maps.of("content", Strings.create("hello")));
		AVector<ACell> inbox = agent.getInbox();
		assertNotNull(inbox);
		assertEquals(1, inbox.count());
		agent.deliverMessage(Maps.of("content", Strings.create("world")));
		inbox = agent.getInbox();
		assertEquals(2, inbox.count());
	}
}
