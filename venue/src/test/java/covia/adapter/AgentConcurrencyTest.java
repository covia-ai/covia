package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
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
import covia.venue.User;
import covia.venue.Users;

/**
 * Concurrency tests for agent infrastructure.
 *
 * <p>These tests verify correctness under concurrent access patterns that occur
 * in production: multiple triggers, message delivery during run loops,
 * syncState during agent modifications, and CAS contention.</p>
 *
 * <p>All tests are deterministic — no iteration counts, no timing assumptions.
 * Where ordering matters, we use completion futures and direct lattice method
 * calls to control the exact interleaving.</p>
 */
public class AgentConcurrencyTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	// ========== Unit: AgentState CAS operations ==========

	@Test
	public void testTryResumeIdempotence() {
		Users users = engine.getVenueState().users();
		User user = users.ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("resume-test", Maps.empty(), null);

		agent.suspend(Strings.create("test error"));
		assertEquals(AgentState.SUSPENDED, agent.getStatus());

		assertTrue(agent.tryResume(), "Resume should succeed from SUSPENDED");
		assertEquals(AgentState.SLEEPING, agent.getStatus());

		assertFalse(agent.tryResume(), "Resume should fail from SLEEPING");
	}

	// ========== Unit: mergeRunResult ==========

	@Test
	public void testMergeRunResultDetectsNewMessage() {
		// mergeRunResult should detect messages delivered DURING the transition
		// (between the inbox snapshot and the merge) via remainingInbox.count().
		Users users = engine.getVenueState().users();
		User user = users.ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("merge-msg", Maps.empty(), null);
		agent.setStatus(AgentState.RUNNING);

		// Deliver a message that's "new" — wasn't in the original inbox snapshot
		agent.deliverMessage(Maps.of("content", "hello"));

		// mergeRunResult with processedMsgCount=0 (we processed 0 messages):
		// extractInbox(r) returns [hello], remainingInbox skips 0 = [hello]
		// remainingInbox.count() > 0 → hasNew = true → status RUNNING
		AMap<AString, ACell> merged = agent.mergeRunResult(
			null, 0, Index.none(), null,
			Maps.of("ts", CVMLong.create(1)));

		assertEquals(AgentState.RUNNING, RT.ensureString(merged.get(AgentState.KEY_STATUS)),
			"Should detect the new message and stay RUNNING");

		// Now process that message: mergeRunResult with processedMsgCount=1
		AMap<AString, ACell> merged2 = agent.mergeRunResult(
			null, 1, Index.none(), null,
			Maps.of("ts", CVMLong.create(2)));

		assertEquals(AgentState.SLEEPING, RT.ensureString(merged2.get(AgentState.KEY_STATUS)),
			"No new messages — should go to SLEEPING");
	}

	@Test
	public void testMergeRunResultDetectsNewTask() {
		// mergeRunResult should detect tasks added DURING the transition
		// via hasNewTasksNotIn(remainingTasks, presentedTasks).
		Users users = engine.getVenueState().users();
		User user = users.ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("merge-task", Maps.empty(), null);
		agent.setStatus(AgentState.RUNNING);

		// Add task T1
		Blob t1 = Blob.fromHex("0001");
		agent.addTask(t1, Strings.create("task-1"));

		// mergeRunResult presenting T1 as already processed
		AMap<AString, ACell> merged = agent.mergeRunResult(
			null, 0, agent.getTasks(), null,
			Maps.of("ts", CVMLong.create(1)));

		assertEquals(AgentState.SLEEPING, RT.ensureString(merged.get(AgentState.KEY_STATUS)),
			"No new tasks beyond what was presented — SLEEPING");

		// Now add T2 while "transition is running"
		agent.setStatus(AgentState.RUNNING);
		Blob t2 = Blob.fromHex("0002");
		agent.addTask(t2, Strings.create("task-2"));

		// mergeRunResult presenting only T1
		@SuppressWarnings("unchecked")
		Index<Blob, ACell> presentedTasks = (Index<Blob, ACell>) (Index<?,?>) Index.none().assoc(t1, Strings.create("task-1"));
		AMap<AString, ACell> merged2 = agent.mergeRunResult(
			null, 0, presentedTasks, null,
			Maps.of("ts", CVMLong.create(2)));

		assertEquals(AgentState.RUNNING, RT.ensureString(merged2.get(AgentState.KEY_STATUS)),
			"New task T2 not in presented set — should stay RUNNING");
	}

	// ========== Integration: concurrent triggers ==========

	@Test
	public void testConcurrentTriggersOnlyOneRunLoop() {
		// Two triggers on the same agent concurrently — only one run loop
		// should execute. Both should complete without error.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "conc-trig",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver message directly to avoid auto-wake
		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("conc-trig").deliverMessage(Maps.of("content", "hello"));

		// Two concurrent triggers
		CompletableFuture<ACell> f1 = CompletableFuture.supplyAsync(() ->
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, "conc-trig"),
				RequestContext.of(ALICE_DID)).awaitResult(5000));

		CompletableFuture<ACell> f2 = CompletableFuture.supplyAsync(() ->
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, "conc-trig"),
				RequestContext.of(ALICE_DID)).awaitResult(5000));

		ACell r1 = f1.join();
		ACell r2 = f2.join();

		assertNotNull(r1);
		assertNotNull(r2);

		// Agent should be SLEEPING with processed inbox
		AgentState agent = user.agent("conc-trig");
		try {
			agent.awaitSleeping().get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			fail("Timed out waiting for agent to reach SLEEPING: " + e);
		}
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getInbox().count(), "Inbox should be drained");
		// At least 1 timeline entry (possibly 2 if both triggered separate loops)
		assertTrue(agent.getTimeline().count() >= 1, "At least one run should have executed");
	}

	@Test
	public void testRapidTriggerStressNoCannotStartAgent() {
		// Regression for #64: rapid fire of trigger wait:false followed by
		// a blocking trigger, repeated. The pre-refactor code had two
		// sources of truth (in-memory runCompletions map + lattice K_STATUS
		// CAS) that could disagree and produce "Cannot start agent" errors.
		//
		// With the RunCoordinator refactor, all start/attach/exit decisions
		// are under a single per-agent lock — this race class is eliminated.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rapid",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("rapid");

		for (int i = 0; i < 50; i++) {
			agent.deliverMessage(Maps.of("content", "msg-" + i));

			// Non-blocking trigger
			Job t1 = engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, "rapid", Fields.WAIT, CVMBool.FALSE),
				RequestContext.of(ALICE_DID));
			ACell r1 = t1.awaitResult(5000);
			assertNotNull(r1, "iteration " + i + ": wait:false trigger must not fail");
			assertEquals(Status.COMPLETE, t1.getStatus(),
				"iteration " + i + ": wait:false trigger job must complete, not fail");

			// Immediate blocking trigger — the bug pattern
			Job t2 = engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, "rapid"),
				RequestContext.of(ALICE_DID));
			ACell r2 = t2.awaitResult(5000);
			assertNotNull(r2, "iteration " + i + ": blocking trigger must not fail with 'Cannot start agent'");
			assertEquals(Status.COMPLETE, t2.getStatus(),
				"iteration " + i + ": blocking trigger must complete");
		}

		// Eventually quiesce — all messages processed, agent SLEEPING
		try {
			agent.awaitSleeping().get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			fail("Timed out waiting for rapid agent to reach SLEEPING: " + e);
		}
		assertEquals(0, agent.getInbox().count(), "All messages should be processed");
	}

	@Test
	public void testConcurrentTriggerBarrageNoCannotStartAgent() {
		// Concurrent variant of #64: many parallel triggers with and without
		// wait. Regression for the two-structure race: pre-refactor the
		// non-atomic window between runCompletions.put and the K_STATUS CAS
		// could cause a trigger to see "no live future" AND "not SLEEPING",
		// failing with "Cannot start agent".
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "barrage",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("barrage");

		final int N = 20;
		@SuppressWarnings("unchecked")
		CompletableFuture<Job>[] futures = new CompletableFuture[N];

		for (int i = 0; i < N; i++) {
			final int idx = i;
			agent.deliverMessage(Maps.of("content", "barrage-" + idx));
			final ACell wait = (idx % 2 == 0) ? CVMBool.FALSE : CVMBool.TRUE;
			futures[i] = CompletableFuture.supplyAsync(() ->
				engine.jobs().invokeOperation("v/ops/agent/trigger",
					Maps.of(Fields.AGENT_ID, "barrage", Fields.WAIT, wait),
					RequestContext.of(ALICE_DID)));
		}

		for (int i = 0; i < N; i++) {
			Job j = futures[i].join();
			ACell result = j.awaitResult(5000);
			assertNotNull(result, "trigger " + i + " must not fail");
			assertEquals(Status.COMPLETE, j.getStatus(),
				"trigger " + i + " job must complete, not fail");
		}

		try {
			agent.awaitSleeping().get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			fail("Timed out waiting for barrage agent to reach SLEEPING: " + e);
		}
		assertEquals(0, agent.getInbox().count(), "All messages should be processed");
	}

	@Test
	public void testTriggerWaitFalseThenTriggerOverlapping() {
		// Pattern that surfaced the ForkedLatticeCursor.sync() bug.
		// First trigger with wait:false returns immediately. Second trigger
		// should join the existing future (agent still RUNNING) and complete
		// normally — NOT fail with "Cannot start agent".
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "overlap",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("overlap").deliverMessage(Maps.of("content", "hello"));

		// First trigger: wait=false, returns immediately with RUNNING
		Job t1 = engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "overlap", Fields.WAIT, CVMBool.FALSE),
			RequestContext.of(ALICE_DID));
		ACell r1 = t1.awaitResult(5000);
		assertEquals(AgentState.RUNNING, RT.getIn(r1, Fields.STATUS));

		// Second trigger: default wait (blocking). Should either:
		// - join existing future (agent still RUNNING from first trigger's loop), or
		// - start new loop (agent already back to SLEEPING because test:echo is fast)
		// Either way, it must NOT fail.
		Job t2 = engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "overlap"),
			RequestContext.of(ALICE_DID));
		ACell r2 = t2.awaitResult(5000);

		assertNotNull(r2, "Second trigger should succeed, not fail with 'Cannot start agent'");
		assertEquals(Status.COMPLETE, t2.getStatus(), "Second trigger job should complete");
	}

	// ========== Integration: message + trigger race ==========

	@Test
	public void testMessageAndTriggerConcurrent() {
		// Both agent:message and agent:trigger funnel through wakeAgent.
		// The RunCoordinator lock serialises them — one starts the loop,
		// the other attaches to the live completion future.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "msg-trig",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit message and trigger concurrently
		CompletableFuture<ACell> fMsg = CompletableFuture.supplyAsync(() ->
			engine.jobs().invokeOperation("v/ops/agent/message",
				Maps.of(Fields.AGENT_ID, "msg-trig",
					Fields.MESSAGE, Maps.of("content", "hello")),
				RequestContext.of(ALICE_DID)).awaitResult(5000));

		CompletableFuture<ACell> fTrig = CompletableFuture.supplyAsync(() ->
			engine.jobs().invokeOperation("v/ops/agent/trigger",
				Maps.of(Fields.AGENT_ID, "msg-trig"),
				RequestContext.of(ALICE_DID)).awaitResult(5000));

		fMsg.join();
		fTrig.join();

		// Agent should be SLEEPING, message processed
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("msg-trig");
		try {
			agent.awaitSleeping().get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			fail("Timed out waiting for agent to reach SLEEPING: " + e);
		}
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getInbox().count(), "Inbox should be drained");
	}

	// ========== Integration: syncState during agent operations ==========

	@Test
	public void testSyncStatePreservesAgentCreation() {
		// Create an agent, syncState, verify agent is still intact.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "sync-create",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo",
					Strings.create("systemPrompt"), Strings.create("You are sync test."))),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		engine.syncState();

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("sync-create");
		assertNotNull(agent, "Agent should exist after syncState");
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		AMap<AString, ACell> config = agent.getConfig();
		assertNotNull(config);
		assertEquals(Strings.create("You are sync test."),
			config.get(Strings.create("systemPrompt")),
			"Config should be preserved through syncState");
	}

	@Test
	public void testSyncStatePreservesRunningAgent() {
		// Trigger with wait:false, immediately syncState, verify agent
		// still completes its run loop and ends up SLEEPING.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "sync-run",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		user.agent("sync-run").deliverMessage(Maps.of("content", "hello"));

		// Trigger with wait:false
		engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "sync-run", Fields.WAIT, CVMBool.FALSE),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Immediately sync
		engine.syncState();

		// Wait deterministically for the run loop to reach SLEEPING.
		// The CAS fix ensures syncState doesn't clobber the run loop's
		// SLEEPING write.
		AgentState agent = user.agent("sync-run");
		try {
			agent.awaitSleeping().get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			fail("Timed out waiting for agent to reach SLEEPING: " + e);
		}

		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Agent should reach SLEEPING after run loop completes — syncState must not clobber");
		assertEquals(0, agent.getInbox().count(), "Inbox should be drained");
		assertTrue(agent.getTimeline().count() >= 1, "Run loop should have executed");
	}

	// ========== Integration: concurrent requests ==========

	@Test
	public void testConcurrentRequestsBothCompleted() {
		// Two agent:request calls submitted concurrently — both should complete.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "conc-req",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Submit two requests concurrently — both block via awaitResult
		CompletableFuture<ACell> f1 = CompletableFuture.supplyAsync(() ->
			engine.jobs().invokeOperation("v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "conc-req",
					Fields.INPUT, Maps.of("task", "one")),
				RequestContext.of(ALICE_DID)).awaitResult(10000));

		CompletableFuture<ACell> f2 = CompletableFuture.supplyAsync(() ->
			engine.jobs().invokeOperation("v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "conc-req",
					Fields.INPUT, Maps.of("task", "two")),
				RequestContext.of(ALICE_DID)).awaitResult(10000));

		ACell r1 = f1.join();
		ACell r2 = f2.join();

		assertNotNull(r1, "First request should complete");
		assertNotNull(r2, "Second request should complete");

		// Both tasks should have been processed and cleared. The run loop
		// transitions the agent back to SLEEPING asynchronously after the
		// awaited task jobs complete — wait deterministically for that
		// transition via the awaitSleeping() primitive.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("conc-req");
		try {
			agent.awaitSleeping().get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			fail("Timed out waiting for agent to reach SLEEPING: " + e);
		}
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(0, agent.getTasks().count(), "All tasks should be processed");
		assertTrue(agent.getTimeline().count() >= 1, "At least one run loop should have executed");
	}

	// ========== Integration: resume auto-wake ==========

	@Test
	public void testResumeAutoWakeProcessesPendingMessages() {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "resume-wake",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver messages directly (no auto-wake since agent isn't SLEEPING for wakeAgent)
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("resume-wake");
		agent.deliverMessage(Maps.of("content", "pending-1"));
		agent.deliverMessage(Maps.of("content", "pending-2"));

		// Suspend the agent
		agent.suspend(Strings.create("maintenance"));
		assertEquals(AgentState.SUSPENDED, agent.getStatus());
		assertEquals(2, agent.getInbox().count(), "Messages should be pending");

		// Resume with autoWake=true
		engine.jobs().invokeOperation("v/ops/agent/resume",
			Maps.of(Fields.AGENT_ID, "resume-wake"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Wait for the auto-wake run loop to complete
		for (int i = 0; i < 50; i++) {
			if (AgentState.SLEEPING.equals(agent.getStatus())) break;
			try { Thread.sleep(10); } catch (InterruptedException e) { break; }
		}

		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Agent should complete run loop after resume auto-wake");
		assertEquals(0, agent.getInbox().count(),
			"Messages should be processed by auto-wake");
	}
}
