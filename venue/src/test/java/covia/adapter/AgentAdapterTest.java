package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
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
 * Tests for the AgentAdapter: create, message, trigger, request, query, and list operations.
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
			"agent:create", input, RequestContext.of(ALICE_DID));
		job.awaitResult(5000);

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
			"agent:create", input, RequestContext.of(ALICE_DID));
		job1.awaitResult(5000);

		Job job2 = engine.jobs().invokeOperation(
			"agent:create", input, RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);

		assertNotNull(result2);
		assertEquals(Strings.create("idempotent-agent"), RT.getIn(result2, Fields.AGENT_ID));
	}

	// ========== agent:message ==========

	@Test
	public void testMessageAgent() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "msg-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "msg-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"agent:message", msgInput, RequestContext.of(ALICE_DID));
		ACell result = msgJob.awaitResult(5000);

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
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		ACell msgInput = Maps.of(
			Fields.AGENT_ID, "ghost-agent",
			Fields.MESSAGE, Maps.of("content", "hello")
		);
		Job msgJob = engine.jobs().invokeOperation(
			"agent:message", msgInput, RequestContext.of(ALICE_DID));

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
			"agent:create",
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
			"agent:message", msgInput, RequestContext.of(ALICE_DID));

		try {
			msgJob.awaitResult(5000);
			fail("Should have thrown for terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, msgJob.getStatus());
		}
	}

	// ========== agent:trigger ==========

	@Test
	public void testTriggerWithEcho() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "echo-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver messages directly to avoid auto-wake race
		User echoUser = engine.getVenueState().users().get(ALICE_DID);
		for (int i = 0; i < 2; i++) {
			echoUser.agent("echo-agent").deliverMessage(Maps.of("content", "msg-" + i));
		}

		Job runJob = engine.jobs().invokeOperation(
			"agent:trigger",
			Maps.of(Fields.AGENT_ID, "echo-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

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
	public void testTriggerNoWork() {
		// Trigger with no messages/tasks — transition still runs (may act proactively)
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "empty-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job runJob = engine.jobs().invokeOperation(
			"agent:trigger",
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

	// ========== User isolation ==========

	@Test
	public void testUserIsolation() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "shared-name", Fields.MESSAGE, Maps.of("from", "alice")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "shared-name"),
			RequestContext.of(BOB_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "shared-name", Fields.MESSAGE, Maps.of("from", "bob")),
			RequestContext.of(BOB_DID)).awaitResult(5000);

		User alice = engine.getVenueState().users().get(ALICE_DID);
		User bob = engine.getVenueState().users().get(BOB_DID);

		AgentState aliceAgent = alice.agent("shared-name");
		AgentState bobAgent = bob.agent("shared-name");

		assertEquals(1, aliceAgent.getInbox().count(), "Alice's agent should have 1 message");
		assertEquals(1, bobAgent.getInbox().count(), "Bob's agent should have 1 message");
	}

	// ========== Default transition op from config ==========

	@Test
	public void testTriggerWithDefaultOperation() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "default-op-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User user0 = engine.getVenueState().users().get(ALICE_DID);
		user0.agent("default-op-agent").deliverMessage(Maps.of("content", "hello"));

		Job runJob = engine.jobs().invokeOperation(
			"agent:trigger",
			Maps.of(Fields.AGENT_ID, "default-op-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("default-op-agent");
		assertEquals(0, agent.getInbox().count(), "Inbox should be cleared");
		assertEquals(1, agent.getTimeline().count(), "Timeline should have 1 entry");
	}

	@Test
	public void testTriggerNoOperationConfigured() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "no-op-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// No config.operation — wakeAgent won't auto-run, message stays in inbox
		engine.jobs().invokeOperation(
			"agent:message",
			Maps.of(Fields.AGENT_ID, "no-op-agent", Fields.MESSAGE, Maps.of("content", "hello")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Trigger should fail — has work but no transition operation
		Job runJob = engine.jobs().invokeOperation(
			"agent:trigger",
			Maps.of(Fields.AGENT_ID, "no-op-agent"),
			RequestContext.of(ALICE_DID));

		try {
			runJob.awaitResult(5000);
			fail("Should fail without operation");
		} catch (Exception e) {
			assertEquals(Status.FAILED, runJob.getStatus());
		}
	}

	// ========== Result in run output ==========

	@Test
	public void testTriggerOutputIncludesResult() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "result-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "llmagent:chat"),
				AgentState.KEY_STATE, Maps.of(
					"config", Maps.of(
						"llmOperation", "test:llm",
						"systemPrompt", "You are helpful."
					)
				)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User resultUser = engine.getVenueState().users().get(ALICE_DID);
		resultUser.agent("result-agent").deliverMessage(Maps.of("content", "hello"));

		Job runJob = engine.jobs().invokeOperation(
			"agent:trigger",
			Maps.of(Fields.AGENT_ID, "result-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		// The trigger output should include the transition result
		ACell transitionResult = RT.getIn(result, Fields.RESULT);
		assertNotNull(transitionResult, "Trigger output should include the transition result");
		AString response = RT.ensureString(RT.getIn(transitionResult, "response"));
		assertNotNull(response, "Result should contain a response");
		assertTrue(response.toString().length() > 0, "Response should not be empty");
	}

	// ========== agent:request ==========

	@Test
	public void testRequestCreatesTask() {
		// Create agent with default operation
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit a request with wait — blocks until agent completes the task
		Job requestJob = engine.jobs().invokeOperation(
			"agent:request",
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
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "completing-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit a request with wait=true (indefinite)
		Job requestJob = engine.jobs().invokeOperation(
			"agent:request",
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
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "async-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit without wait — should return immediately with task job status
		Job requestJob = engine.jobs().invokeOperation(
			"agent:request",
			Maps.of(Fields.AGENT_ID, "async-agent", Fields.INPUT, Maps.of("data", "async")),
			RequestContext.of(ALICE_DID));

		ACell result = requestJob.awaitResult(5000);
		assertNotNull(result, "Async request should complete immediately");
		assertTrue(requestJob.isComplete(), "Request job should be COMPLETE");

		// Result is the task job data — status may be STARTED (agent hasn't run yet)
		ACell taskStatus = RT.getIn(result, Fields.STATUS);
		assertNotNull(taskStatus, "Result should contain task job status");
		ACell taskId = RT.getIn(result, Fields.ID);
		assertNotNull(taskId, "Result should contain task job ID");
	}

	@Test
	public void testRequestToNonExistentAgent() {
		Job requestJob = engine.jobs().invokeOperation(
			"agent:request",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "dead-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("dead-agent").setStatus(AgentState.TERMINATED);

		Job requestJob = engine.jobs().invokeOperation(
			"agent:request",
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
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "timeline-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job requestJob = engine.jobs().invokeOperation(
			"agent:request",
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
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "multi-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete")
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit two requests with wait
		Job req1 = engine.jobs().invokeOperation(
			"agent:request",
			Maps.of(Fields.AGENT_ID, "multi-agent", Fields.INPUT, Maps.of("n", "1"),
				Fields.WAIT, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		Job req2 = engine.jobs().invokeOperation(
			"agent:request",
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

	// ========== agent:query ==========

	@Test
	public void testQueryAgent() {
		// Create an agent with config and state
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(
				Fields.AGENT_ID, "query-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo"),
				AgentState.KEY_STATE, Maps.of("counter", 0)
			),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Query it
		Job queryJob = engine.jobs().invokeOperation(
			"agent:query",
			Maps.of(Fields.AGENT_ID, "query-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = queryJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(Strings.create("query-agent"), RT.getIn(result, Fields.AGENT_ID));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));
		assertNotNull(RT.getIn(result, AgentState.KEY_CONFIG));
		assertNotNull(RT.getIn(result, AgentState.KEY_STATE));
		assertNotNull(RT.getIn(result, AgentState.KEY_TIMELINE));
	}

	@Test
	public void testQueryNonExistentAgent() {
		Job queryJob = engine.jobs().invokeOperation(
			"agent:query",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "term-query"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("term-query").setStatus(AgentState.TERMINATED);

		// Query should still work — you can read terminated agents
		Job queryJob = engine.jobs().invokeOperation(
			"agent:query",
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
			"agent:list", Maps.empty(), RequestContext.of(BOB_DID));
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "agent-a"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "agent-b"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation(
			"agent:list", Maps.empty(), RequestContext.of(ALICE_DID));
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "alice-only"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job bobList = engine.jobs().invokeOperation(
			"agent:list", Maps.empty(), RequestContext.of(BOB_DID));
		ACell result = bobList.awaitResult(5000);

		ACell agents = RT.getIn(result, "agents");
		assertEquals(0, ((AVector<?>) agents).count());
	}

	// ========== agent:delete ==========

	@Test
	public void testDeleteAgent() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "del-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job delJob = engine.jobs().invokeOperation(
			"agent:delete",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "rem-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job delJob = engine.jobs().invokeOperation(
			"agent:delete",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.jobs().invokeOperation(
			"agent:delete",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Create again without overwrite — idempotent no-op, created=false
		Job job2 = engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "reuse-agent"),
			RequestContext.of(ALICE_DID));
		ACell result2 = job2.awaitResult(5000);
		assertEquals(CVMBool.FALSE, RT.getIn(result2, Fields.CREATED));
		assertEquals(AgentState.TERMINATED, RT.getIn(result2, Fields.STATUS));
	}

	@Test
	public void testDeleteWithRemoveThenRecreate() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "clean-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Delete with remove
		engine.jobs().invokeOperation(
			"agent:delete",
			Maps.of(Fields.AGENT_ID, "clean-agent", Fields.REMOVE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Recreate — should succeed with created=true
		Job job2 = engine.jobs().invokeOperation(
			"agent:create",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "ow-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Terminate it
		engine.jobs().invokeOperation(
			"agent:delete",
			Maps.of(Fields.AGENT_ID, "ow-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Overwrite with new config
		Job job = engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "ow-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete"),
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.CREATED));
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Config should be the new one
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("ow-agent");
		AMap<AString, ACell> config = agent.getConfig();
		assertEquals(Strings.create("test:taskcomplete"), config.get(Fields.OPERATION));
	}

	@Test
	public void testCreateOverwriteLiveAgentFails() {
		// Create a live (SLEEPING) agent
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "live-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Overwrite a non-terminated agent should fail
		Job job = engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "live-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete"),
				Fields.OVERWRITE, CVMBool.TRUE),
			RequestContext.of(ALICE_DID));

		try {
			job.awaitResult(5000);
			fail("Should fail when overwriting a non-terminated agent");
		} catch (Exception e) {
			assertEquals(Status.FAILED, job.getStatus());
		}
	}

	@Test
	public void testCreateOverwriteFalseIsNoOp() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "no-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Without overwrite — idempotent no-op
		Job job = engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "no-ow",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:taskcomplete")),
			RequestContext.of(ALICE_DID));
		ACell result = job.awaitResult(5000);

		assertEquals(CVMBool.FALSE, RT.getIn(result, Fields.CREATED));

		// Config should still be original
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("no-ow");
		assertEquals(Strings.create("test:echo"), agent.getConfig().get(Fields.OPERATION));
	}

	// ========== agent:list — filter TERMINATED ==========

	@Test
	public void testListAgentsHidesTerminated() {
		// Create two agents, delete one
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "alive"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "dead"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:delete",
			Maps.of(Fields.AGENT_ID, "dead"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Default list should hide terminated
		Job listJob = engine.jobs().invokeOperation(
			"agent:list", Maps.empty(), RequestContext.of(ALICE_DID));
		ACell result = listJob.awaitResult(5000);

		@SuppressWarnings("unchecked")
		AVector<ACell> agents = (AVector<ACell>) RT.getIn(result, "agents");
		assertEquals(1, agents.count(), "Terminated agent should be hidden");
		assertEquals(Strings.create("alive"), RT.getIn(agents.get(0), Fields.AGENT_ID));
	}

	@Test
	public void testListAgentsIncludeTerminated() {
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "alive2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:create",
			Maps.of(Fields.AGENT_ID, "dead2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:delete",
			Maps.of(Fields.AGENT_ID, "dead2"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// With includeTerminated=true
		Job listJob = engine.jobs().invokeOperation(
			"agent:list",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "doomed"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		engine.jobs().invokeOperation(
			"agent:delete",
			Maps.of(Fields.AGENT_ID, "doomed"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job listJob = engine.jobs().invokeOperation(
			"agent:list", Maps.empty(), RequestContext.of(ALICE_DID));
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "task-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("task-agent");

		Blob taskId = Blob.createRandom(new java.util.Random(42), 16);
		agent.addTask(taskId, Maps.of("question", "What is 2+2?"));
		assertEquals(1, agent.getTasks().count());

		// Cancel the task
		Job cancelJob = engine.jobs().invokeOperation(
			"agent:cancelTask",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "cancel-nf",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job cancelJob = engine.jobs().invokeOperation(
			"agent:cancelTask",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "cancel-mp",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Missing taskId
		Job job1 = engine.jobs().invokeOperation(
			"agent:cancelTask",
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
			"agent:cancelTask",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "multi-task",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
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
			"agent:cancelTask",
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
			"agent:create",
			Maps.of(Fields.AGENT_ID, "cancel-hex",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "test:echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		Job job = engine.jobs().invokeOperation(
			"agent:cancelTask",
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

		agent.deliverMessage(Maps.of("content", "hello"));
		AVector<ACell> inbox = agent.getInbox();
		assertNotNull(inbox);
		assertEquals(1, inbox.count());
		agent.deliverMessage(Maps.of("content", "world"));
		inbox = agent.getInbox();
		assertEquals(2, inbox.count());
	}
}
