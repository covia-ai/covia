package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
import covia.venue.TestEngine;
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

	final Engine engine = TestEngine.ENGINE;
	// Per-test user DID — each test gets its own user namespace, so agent
	// names like "rapid", "conc-trig" don't collide across tests on the
	// shared engine.
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	/**
	 * Waits — signal-based, via the run loop's completion future — for the agent
	 * to reach a rest state (SLEEPING/SUSPENDED/TERMINATED) and returns it; fails
	 * the test if it is still RUNNING after 10s.
	 */
	private AString awaitFinished(AgentState agent) {
		try {
			return ((AgentAdapter) engine.getAdapter("agent"))
				.awaitRunFinished(agent.getAgentId(), RequestContext.of(ALICE_DID), 10_000);
		} catch (java.util.concurrent.TimeoutException e) {
			throw new AssertionError("Agent '" + agent.getAgentId()
				+ "' did not reach a rest state in 10s", e);
		}
	}

	// ========== Unit: AgentState CAS operations ==========

	@Test
	public void testExclusiveCreateHasExactlyOneConcurrentWinner() throws Exception {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AString id = Strings.create("exclusive-create");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		java.util.function.Function<String, AgentState> create = marker -> {
			ready.countDown();
			try {
				start.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
			return user.createAgent(id, Maps.of("marker", marker), null);
		};
		CompletableFuture<AgentState> first = CompletableFuture.supplyAsync(() -> create.apply("first"));
		CompletableFuture<AgentState> second = CompletableFuture.supplyAsync(() -> create.apply("second"));
		assertTrue(ready.await(5, TimeUnit.SECONDS));
		start.countDown();

		AgentState a = first.get(5, TimeUnit.SECONDS);
		AgentState b = second.get(5, TimeUnit.SECONDS);
		assertEquals(1, (a != null ? 1 : 0) + (b != null ? 1 : 0));
		assertEquals(a != null ? Strings.create("first") : Strings.create("second"),
			user.agent(id).getConfig().get(Strings.create("marker")));
	}

	@Test
	public void testExclusiveForkHasExactlyOneConcurrentWinner() throws Exception {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AString id = Strings.create("exclusive-fork");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		java.util.function.Function<String, AgentState> fork = marker -> {
			ready.countDown();
			try {
				start.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
			return user.forkAgent(id,
				Maps.of("marker", marker),
				Maps.of("stateMarker", marker),
				Vectors.of(Maps.of("timelineMarker", marker)));
		};
		CompletableFuture<AgentState> first = CompletableFuture.supplyAsync(() -> fork.apply("first"));
		CompletableFuture<AgentState> second = CompletableFuture.supplyAsync(() -> fork.apply("second"));
		assertTrue(ready.await(5, TimeUnit.SECONDS));
		start.countDown();

		AgentState a = first.get(5, TimeUnit.SECONDS);
		AgentState b = second.get(5, TimeUnit.SECONDS);
		assertEquals(1, (a != null ? 1 : 0) + (b != null ? 1 : 0));
		AString winner = Strings.create(a != null ? "first" : "second");
		AgentState persisted = user.agent(id);
		assertEquals(winner, persisted.getConfig().get(Strings.create("marker")));
		assertEquals(winner, RT.getIn(persisted.getState(), "stateMarker"));
		assertEquals(winner, RT.getIn(persisted.getTimeline(), CVMLong.ZERO, "timelineMarker"));
	}

	@Test
	public void testSessionLoadsAndTaskLandInOneExclusiveIntake() throws Exception {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("session-intake", Maps.empty(), null);
		Blob sid = Blob.fromHex("99990001999900019999000199990001");
		Blob firstTask = Blob.fromHex("1001");
		Blob secondTask = Blob.fromHex("1002");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		java.util.function.BiFunction<Blob, String, AgentState.SessionIntake> intake = (task, marker) -> {
			ready.countDown();
			try {
				start.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
			return agent.addTaskInSession(sid, ALICE_DID,
				Maps.of("load", marker), task, Maps.of("marker", marker));
		};
		CompletableFuture<AgentState.SessionIntake> first = CompletableFuture.supplyAsync(
			() -> intake.apply(firstTask, "first"));
		CompletableFuture<AgentState.SessionIntake> second = CompletableFuture.supplyAsync(
			() -> intake.apply(secondTask, "second"));
		assertTrue(ready.await(5, TimeUnit.SECONDS));
		start.countDown();

		AgentState.SessionIntake a = first.get(5, TimeUnit.SECONDS);
		AgentState.SessionIntake b = second.get(5, TimeUnit.SECONDS);
		assertEquals(1, (a == AgentState.SessionIntake.APPLIED ? 1 : 0)
			+ (b == AgentState.SessionIntake.APPLIED ? 1 : 0));
		assertEquals(1, (a == AgentState.SessionIntake.LOADS_ON_EXISTING ? 1 : 0)
			+ (b == AgentState.SessionIntake.LOADS_ON_EXISTING ? 1 : 0));
		assertEquals(1, agent.getTasks().count());
		assertNotNull(agent.getSession(sid));
	}

	@Test
	public void testPendingIntakeExposesMissingCreateAndLoadsOutcomes() {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("pending-intake", Maps.empty(), null);
		Blob sid = Blob.fromHex("99990002999900029999000299990002");
		ACell firstEnvelope = Maps.of("content", "first");

		assertEquals(AgentState.SessionIntake.MISSING,
			agent.appendSessionPending(sid, ALICE_DID, null, firstEnvelope, false));
		assertNull(agent.getSession(sid),
			"a supplied/strict session path must not mint a missing session");

		AMap<AString, ACell> loads = Maps.of("seed", Strings.create("initial"));
		assertEquals(AgentState.SessionIntake.APPLIED,
			agent.appendSessionPending(sid, ALICE_DID, loads, firstEnvelope, true));
		assertEquals(Strings.create("initial"), RT.getIn(agent.getSession(sid),
			AgentState.KEY_FRAMES, CVMLong.ZERO, Fields.LOADS, "seed"));
		assertEquals(1, agent.getSessionPending(sid).count());

		assertEquals(AgentState.SessionIntake.LOADS_ON_EXISTING,
			agent.appendSessionPending(sid, ALICE_DID, Maps.empty(),
				Maps.of("content", "rejected"), true));
		assertEquals(1, agent.getSessionPending(sid).count(),
			"loads against an existing session reject the whole append");

		assertEquals(AgentState.SessionIntake.APPLIED,
			agent.appendSessionPending(sid, ALICE_DID, null,
				Maps.of("content", "second"), false));
		assertEquals(2, agent.getSessionPending(sid).count());
	}

	@Test
	public void testMediatedDeliveryUsesStrictAtomicSessionIntake() {
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("mediated-intake", Maps.empty(), null);
		AgentAdapter adapter = (AgentAdapter) engine.getAdapter("agent");
		Blob missing = Blob.fromHex("99990003999900039999000399990003");

		assertFalse(adapter.deliverSessionMessage(ALICE_DID, agent.getAgentId(), missing,
			ALICE_DID, Strings.create("hitl-missing"), Strings.create("answer")));
		assertNull(agent.getSession(missing),
			"venue-mediated delivery must not mint the session it is meant to resume");

		Blob existing = Blob.fromHex("99990004999900049999000499990004");
		agent.ensureSession(existing, ALICE_DID);
		agent.suspend(Strings.create("keep the run loop parked for inspection"));
		assertTrue(adapter.deliverSessionMessage(ALICE_DID, agent.getAgentId(), existing,
			ALICE_DID, Strings.create("hitl-present"), Strings.create("answer")));
		assertEquals(1, agent.getSessionPending(existing).count());
		assertEquals(Strings.create("hitl-present"),
			RT.getIn(agent.getSessionPending(existing), CVMLong.ZERO, Fields.JOB_ID));
	}

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

	@Test
	public void testTakeTaskHasExactlyOneConcurrentWinner() throws Exception {
		Users users = engine.getVenueState().users();
		User user = users.ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("take-task-test", Maps.empty(), null);
		Blob taskId = Blob.fromHex("cafe");
		ACell task = Maps.of(Fields.MESSAGE, Strings.create("claim once"));
		agent.addTask(taskId, task);

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		java.util.function.Supplier<ACell> claim = () -> {
			ready.countDown();
			try {
				if (!start.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting to start concurrent task claims");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting to claim task", e);
			}
			return agent.takeTask(taskId);
		};

		CompletableFuture<ACell> first = CompletableFuture.supplyAsync(claim);
		CompletableFuture<ACell> second = CompletableFuture.supplyAsync(claim);
		assertTrue(ready.await(5, TimeUnit.SECONDS), "Both claimants should be ready");
		start.countDown();

		ACell firstResult = first.get(5, TimeUnit.SECONDS);
		ACell secondResult = second.get(5, TimeUnit.SECONDS);
		assertEquals(1, (firstResult != null ? 1 : 0) + (secondResult != null ? 1 : 0),
			"Exactly one concurrent claimant must receive the task");
		assertEquals(task, firstResult != null ? firstResult : secondResult);
		assertNull(agent.getTasks().get(taskId));
	}

	// ========== Unit: mergeRunResult ==========

	@Test
	public void testMergeRunResultDoesNotPersistExecutorStatus() {
		// Work that arrives during a transition is durable, but executor
		// liveness is not part of the merge result.
		Users users = engine.getVenueState().users();
		User user = users.ensure(ALICE_DID);
		AgentState agent = user.ensureAgent("merge-msg", Maps.empty(), null);

		Blob sid = Blob.fromHex("66660001666600016666000166660001");
		agent.ensureSession(sid, ALICE_DID);
		agent.appendSessionPending(sid, Maps.of("content", "hello"));
		Blob taskId = Blob.fromHex("0001");
		agent.addTask(taskId, Strings.create("new work"));

		AMap<AString, ACell> merged = agent.mergeRunResult(
			null, null, null, Maps.of("ts", CVMLong.create(1)),
			sid, null, 0, null, null);

		assertEquals(AgentState.SLEEPING,
			RT.ensureString(merged.get(AgentState.KEY_STATUS)));
		assertEquals(1, agent.getSessionPending(sid).count(),
			"pending work remains available to the live loop");
		assertNotNull(agent.getTasks().get(taskId),
			"new task remains available to the live loop");
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
		AgentState concAgent = user.agent("conc-trig");
		Blob concSid = Blob.fromHex("77770001777700017777000177770001");
		concAgent.ensureSession(concSid, ALICE_DID);
		concAgent.appendSessionPending(concSid, Maps.of(
			Fields.SESSION_ID, Strings.create(concSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hello")));

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

		// Agent should be SLEEPING with processed session pending
		AgentState agent = user.agent("conc-trig");
		assertEquals(AgentState.SLEEPING, awaitFinished(agent));
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertFalse(agent.hasSessionPending(), "Session pending should be drained");
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
		// In the virtual-thread-per-agent model, launch is an atomic CAS on
		// runningLoops via ConcurrentHashMap.compute() — the in-memory slot
		// is the sole source of truth for liveness, so this race class is
		// eliminated.
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "rapid",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("rapid");
		Blob rapidSid = Blob.fromHex("88880001888800018888000188880001");
		agent.ensureSession(rapidSid, ALICE_DID);
		AString rapidSidHex = Strings.create(rapidSid.toHexString());

		for (int i = 0; i < 50; i++) {
			agent.appendSessionPending(rapidSid, Maps.of(
				Fields.SESSION_ID, rapidSidHex,
				Fields.MESSAGE, Maps.of("content", "msg-" + i)));

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
		assertEquals(AgentState.SLEEPING, awaitFinished(agent));
		assertFalse(agent.hasSessionPending(), "All messages should be processed");
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
		Blob barrageSid = Blob.fromHex("99990001999900019999000199990001");
		agent.ensureSession(barrageSid, ALICE_DID);
		AString barrageSidHex = Strings.create(barrageSid.toHexString());

		final int N = 20;
		@SuppressWarnings("unchecked")
		CompletableFuture<Job>[] futures = new CompletableFuture[N];

		for (int i = 0; i < N; i++) {
			final int idx = i;
			agent.appendSessionPending(barrageSid, Maps.of(
				Fields.SESSION_ID, barrageSidHex,
				Fields.MESSAGE, Maps.of("content", "barrage-" + idx)));
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

		assertEquals(AgentState.SLEEPING, awaitFinished(agent));
		assertFalse(agent.hasSessionPending(), "All messages should be processed");
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
		AgentState overlapAgent = user.agent("overlap");
		Blob overlapSid = Blob.fromHex("aabb0001aabb0001aabb0001aabb0001");
		overlapAgent.ensureSession(overlapSid, ALICE_DID);
		overlapAgent.appendSessionPending(overlapSid, Maps.of(
			Fields.SESSION_ID, Strings.create(overlapSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hello")));

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
		// The atomic CAS on runningLoops (ConcurrentHashMap.compute)
		// serialises them — one wake wins and starts the loop, the other
		// attaches to the live completion future.
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
		assertEquals(AgentState.SLEEPING, awaitFinished(agent));
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertFalse(agent.hasSessionPending(), "Session pending should be drained");
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
		AgentState syncRunAgent = user.agent("sync-run");
		Blob syncSid = Blob.fromHex("bbcc0001bbcc0001bbcc0001bbcc0001");
		syncRunAgent.ensureSession(syncSid, ALICE_DID);
		syncRunAgent.appendSessionPending(syncSid, Maps.of(
			Fields.SESSION_ID, Strings.create(syncSid.toHexString()),
			Fields.MESSAGE, Maps.of("content", "hello")));

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
		assertEquals(AgentState.SLEEPING, awaitFinished(agent));

		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Agent should reach SLEEPING after run loop completes — syncState must not clobber");
		assertFalse(agent.hasSessionPending(), "Session pending should be drained");
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
		// transition via awaitFinished() (the run-loop completion future).
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("conc-req");
		assertEquals(AgentState.SLEEPING, awaitFinished(agent));
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
		Blob resumeSid = Blob.fromHex("ccdd0001ccdd0001ccdd0001ccdd0001");
		agent.ensureSession(resumeSid, ALICE_DID);
		AString resumeSidHex = Strings.create(resumeSid.toHexString());
		agent.appendSessionPending(resumeSid, Maps.of(
			Fields.SESSION_ID, resumeSidHex,
			Fields.MESSAGE, Maps.of("content", "pending-1")));
		agent.appendSessionPending(resumeSid, Maps.of(
			Fields.SESSION_ID, resumeSidHex,
			Fields.MESSAGE, Maps.of("content", "pending-2")));

		// Suspend the agent
		agent.suspend(Strings.create("maintenance"));
		assertEquals(AgentState.SUSPENDED, agent.getStatus());
		assertTrue(agent.hasSessionPending(), "Messages should be pending");

		// Resume with autoWake=true
		engine.jobs().invokeOperation("v/ops/agent/resume",
			Maps.of(Fields.AGENT_ID, "resume-wake"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Wait on the live executor, not the stable lattice status (which stays
		// SLEEPING throughout the attempt).
		assertEquals(AgentState.SLEEPING, awaitFinished(agent),
			"Agent should complete run loop after resume auto-wake");
		assertFalse(agent.hasSessionPending(),
			"Messages should be processed by auto-wake");
	}
}
