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
		ACell input = Maps.of(Fields.AGENT_ID, "my-assistant");
		Job job = engine.jobs().invokeOperation(
			"agent:create", input, RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult();

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
			"agent:create", input, RequestContext.of(ALICE_DID));
		job.awaitResult();

		User user = engine.getVenueState().users().get(ALICE_DID);
		assertNotNull(user);
		AgentState agent = user.agent("configured-agent");
		assertNotNull(agent);
		AMap<AString, ACell> storedConfig = agent.getConfig();
		assertNotNull(storedConfig, "Config should be stored");
	}

	@Test
	public void testCreateMissingAgentId() {
		ACell input = Maps.of("foo", "bar");
		Job job = engine.jobs().invokeOperation(
			"agent:create", input, RequestContext.of(ALICE_DID));

		try {
			job.awaitResult();
			fail("Should have thrown due to missing agentId");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testCreateIdempotent() {
		ACell input = Maps.of(Fields.AGENT_ID, "idempotent-agent");

		Job job1 = engine.jobs().invokeOperation(
			"agent:create", input, RequestContext.of(ALICE_DID));
		job1.awaitResult();

		Job job2 = engine.jobs().invokeOperation(
			"agent:create", input, RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult();

		assertNotNull(result2);
		assertEquals(Strings.create("idempotent-agent"), RT.getIn(result2, Fields.AGENT_ID));
	}

	// ========== agent:message ==========

	@Test
	public void testMessageAgent() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "msg-agent"),
			RequestContext.of(ALICE_DID)).awaitResult();

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "msg-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"agent:message", msgInput, RequestContext.of(ALICE_DID));
		ACell result = msgJob.awaitResult();

		assertNotNull(result);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.DELIVERED));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("msg-agent");
		AVector<ACell> inbox = agent.getInbox();
		assertNotNull(inbox, "Inbox should not be null after message");
		assertEquals(1, inbox.count(), "Inbox should have 1 message");
	}

	@Test
	public void testMessageNonExistentAgent() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "other-agent"),
			RequestContext.of(ALICE_DID)).awaitResult();

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "ghost-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"agent:message", msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult();
			fail("Should have thrown for non-existent agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	@Test
	public void testMessageTerminatedAgent() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "term-agent"),
			RequestContext.of(ALICE_DID)).awaitResult();

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("term-agent");
		agent.setStatus(AgentState.TERMINATED);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "term-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"agent:message", msgInput, RequestContext.of(ALICE_DID));

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
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "echo-agent"),
			RequestContext.of(ALICE_DID)).awaitResult();

		for (int i = 0; i < 2; i++) {
			engine.jobs().invokeOperation(
				"agent:message",
				Maps.of(Fields.AGENT_ID, "echo-agent", Fields.MESSAGE, Maps.of("content", "msg-" + i)),
				RequestContext.of(ALICE_DID)).awaitResult();
		}

		Job runJob = engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "echo-agent", Fields.OPERATION, "test:echo"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("echo-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus(), "Status should be sleeping after run");

		AVector<ACell> inbox = agent.getInbox();
		assertEquals(0, inbox.count(), "Inbox should be empty after run");

		AVector<ACell> timeline = agent.getTimeline();
		assertNotNull(timeline, "Timeline should not be null");
		assertEquals(1, timeline.count(), "Timeline should have 1 entry");

		assertNull(agent.getError(), "Error should be null after successful run");
	}

	@Test
	public void testRunNoMessages() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "empty-agent"),
			RequestContext.of(ALICE_DID)).awaitResult();

		Job runJob = engine.jobs().invokeOperation(
			"agent:run",
			Maps.of(Fields.AGENT_ID, "empty-agent", Fields.OPERATION, "test:echo"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
	}

	// ========== User isolation ==========

	@Test
	public void testUserIsolation() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(ALICE_DID)).awaitResult();
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "shared-name", Fields.MESSAGE, Maps.of("from", "alice")),
			RequestContext.of(ALICE_DID)).awaitResult();

		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(BOB_DID)).awaitResult();
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "shared-name", Fields.MESSAGE, Maps.of("from", "bob")),
			RequestContext.of(BOB_DID)).awaitResult();

		User alice = engine.getVenueState().users().get(ALICE_DID);
		User bob = engine.getVenueState().users().get(BOB_DID);

		AgentState aliceAgent = alice.agent("shared-name");
		AgentState bobAgent = bob.agent("shared-name");

		assertEquals(1, aliceAgent.getInbox().count(), "Alice's agent should have 1 message");
		assertEquals(1, bobAgent.getInbox().count(), "Bob's agent should have 1 message");
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

		agent.deliverMessage(Maps.of("content", "hello"));
		AVector<ACell> inbox = agent.getInbox();
		assertNotNull(inbox);
		assertEquals(1, inbox.count());
		agent.deliverMessage(Maps.of("content", "world"));
		inbox = agent.getInbox();
		assertEquals(2, inbox.count());
	}
}
