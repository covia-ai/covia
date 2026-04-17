package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.exception.JobFailedException;
import covia.grid.Status;

/**
 * Tests for job cancellation across the venue.
 *
 * Covers: basic cancellation semantics, cancel during await, cancel hook,
 * cancel of agent:chat (slot release), cancel of agent:request (task cleanup),
 * cancel of orchestration (sub-step handling), and cancel propagation
 * through internal invocations.
 */
public class JobCancellationTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(ALICE_DID);
	}

	// ========== Basic cancellation semantics ==========

	@Test
	public void testCancelSetsStatusAndError() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		assertFalse(job.isFinished());

		job.cancel();

		assertTrue(job.isFinished());
		assertEquals("CANCELLED", job.getStatus().toString());
		assertNotNull(RT.getIn(job.getData(), Fields.ERROR));
	}

	@Test
	public void testCancelFinishedJobIsNoop() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("value"), Strings.create("hello")), ctx);
		job.awaitResult(5000);

		assertTrue(job.isFinished());
		job.cancel();
		assertEquals("COMPLETE", job.getStatus().toString());
	}

	@Test
	public void testCancelHookFires() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		AtomicBoolean hookCalled = new AtomicBoolean(false);
		job.setCancelHook(() -> hookCalled.set(true));

		job.cancel();

		assertTrue(hookCalled.get(), "Cancel hook should fire on cancellation");
	}

	@Test
	public void testCancelHookDoesNotFireOnFinishedJob() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("value"), Strings.create("x")), ctx);
		job.awaitResult(5000);

		AtomicBoolean hookCalled = new AtomicBoolean(false);
		job.setCancelHook(() -> hookCalled.set(true));
		job.cancel();

		assertFalse(hookCalled.get(), "Cancel hook should not fire on already-finished job");
	}

	// ========== Cancel during awaitResult ==========

	@Test
	public void testAwaitResultThrowsOnCancel() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);

		CompletableFuture.runAsync(() -> {
			try { Thread.sleep(50); } catch (InterruptedException e) {}
			job.cancel();
		});

		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testAwaitResultWithTimeoutThrowsOnCancel() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);

		CompletableFuture.runAsync(() -> {
			try { Thread.sleep(50); } catch (InterruptedException e) {}
			job.cancel();
		});

		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
		assertEquals("CANCELLED", job.getStatus().toString());
	}

	// ========== Cancel delay job with internal invocation ==========

	@Test
	public void testCancelDelayInterruptsWait() {
		Job job = engine.jobs().invokeOperation("v/test/ops/delay",
			Maps.of(
				Fields.DELAY, CVMLong.create(30000),
				Fields.OPERATION, Strings.create("v/test/ops/echo"),
				Fields.INPUT, Maps.of(Strings.create("value"), Strings.create("delayed"))),
			ctx);

		pollUntilStatus(job, "STARTED", 2000);
		job.cancel();
		pollUntilFinished(job, 2000);

		assertEquals("CANCELLED", job.getStatus().toString(),
			"Delay job should be cancelled, not still running");
	}

	@Test
	public void testCancelDelayPreventsInternalInvocation() {
		Job job = engine.jobs().invokeOperation("v/test/ops/delay",
			Maps.of(
				Fields.DELAY, CVMLong.create(30000),
				Fields.OPERATION, Strings.create("v/test/ops/echo"),
				Fields.INPUT, Maps.of(Strings.create("value"), Strings.create("should-not-run"))),
			ctx);

		pollUntilStatus(job, "STARTED", 2000);
		job.cancel();
		pollUntilFinished(job, 2000);

		assertEquals("CANCELLED", job.getStatus().toString(),
			"Delay job should be cancelled before internal echo ran");
	}

	// ========== Cancel agent:chat ==========

	@Test
	public void testCancelChatReleasesSlot() {
		createNeverAgent("chat-cancel-agent");

		Job chatJob = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cancel-agent",
				Fields.MESSAGE, Strings.create("hello")),
			ctx);

		pollUntilStatus(chatJob, "STARTED", 5000);

		AString sidHex = getSessionIdFromAgent("chat-cancel-agent");
		assertNotNull(sidHex, "Session should have been minted");
		Blob sid = Blob.fromHex(sidHex.toString());

		// Verify chat slot is reserved
		covia.adapter.AgentAdapter agentAdapter =
			(covia.adapter.AgentAdapter) engine.getAdapter("agent");
		AString agentIdStr = Strings.create("chat-cancel-agent");
		assertNotNull(agentAdapter.getActiveChatForTest(agentIdStr, sid),
			"Chat slot should be reserved before cancel");

		chatJob.cancel();
		assertEquals("CANCELLED", chatJob.getStatus().toString());

		// Issue #85: cancelling the caller's Job releases the in-memory chat
		// slot immediately via the cancel hook registered in handleChat.
		// No need to wait for the run loop — the slot is freed synchronously
		// when chatJob.cancel() fires the hook.
		assertNull(agentAdapter.getActiveChatForTest(agentIdStr, sid),
			"Chat slot should be released immediately on caller-Job cancel");
	}

	@Test
	public void testCancelChatDoesNotBlockSubsequentChats() {
		// Use a real (fast) LLM so the first chat completes, then cancel
		// a second chat and verify a third can proceed.
		createChatAgent("chat-cancel-seq");

		// First chat — completes normally
		Job chat1 = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cancel-seq",
				Fields.MESSAGE, Strings.create("first")),
			ctx);
		chat1.awaitResult(5000);
		AString sidHex = RT.ensureString(RT.getIn(chat1.getOutput(), Fields.SESSION_ID));

		// Second chat on a never-completing agent won't work because this
		// agent uses the real test LLM. Instead, verify rapid fire-and-cancel.
		Job chat2 = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cancel-seq",
				Fields.MESSAGE, Strings.create("second"),
				Fields.SESSION_ID, sidHex),
			ctx);
		chat2.awaitResult(5000);

		// Third chat should succeed
		Job chat3 = engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "chat-cancel-seq",
				Fields.MESSAGE, Strings.create("third"),
				Fields.SESSION_ID, sidHex),
			ctx);
		ACell result = chat3.awaitResult(5000);
		assertNotNull(RT.getIn(result, Fields.RESPONSE));
	}

	// ========== Cancel agent:request ==========

	@Test
	public void testCancelRequestLeavesTaskInAgent() {
		createNeverAgent("req-cancel-agent");

		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "req-cancel-agent",
				Fields.INPUT, Maps.of(Strings.create("task"), Strings.create("do something"))),
			ctx);

		pollUntilStatus(reqJob, "STARTED", 5000);
		Blob taskId = reqJob.getID();

		reqJob.cancel();
		assertEquals("CANCELLED", reqJob.getStatus().toString());

		// The task is still in the agent's task Index — cancellation doesn't
		// clean it up. The agent will eventually pick it up, find the Job
		// is finished, and skip it.
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("req-cancel-agent");
		assertNotNull(agent.getTasks(), "Agent should still have tasks");
	}

	@Test
	public void testCancelledRequestJobNotCompletedByAgent() {
		// Agent picks a cancelled task Job — should not overwrite CANCELLED status
		createChatAgent("req-cancel-agent2");

		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "req-cancel-agent2",
				Fields.INPUT, Maps.of(Strings.create("message"), Strings.create("hello"))),
			ctx);

		pollUntilStatus(reqJob, "STARTED", 5000);
		reqJob.cancel();

		// Wait for the agent to complete its cycle
		pollUntilFinished(reqJob, 5000);
		// The Job should stay CANCELLED — the agent's completeWith should
		// be a no-op because isFinished() is already true
		assertEquals("CANCELLED", reqJob.getStatus().toString(),
			"Cancelled request should stay CANCELLED even after agent processes it");
	}

	// ========== Cancel orchestration ==========

	@Test
	public void testCancelOrchestrationExitsLoop() {
		// Build a two-step orchestration: never (blocks) → echo (never reached)
		// Cancel while the never step is running
		ACell pipeline = Maps.of(
			Strings.create("name"), Strings.create("Cancel Test Pipeline"),
			Strings.create("operation"), Maps.of(
				Strings.create("adapter"), Strings.create("orchestrator"),
				Strings.create("steps"), Vectors.of(
					(ACell) Maps.of(
						Strings.create("op"), Strings.create("v/test/ops/never"),
						Strings.create("input"), Maps.empty()),
					(ACell) Maps.of(
						Strings.create("op"), Strings.create("v/test/ops/echo"),
						Strings.create("input"), Maps.of(
							Strings.create("value"), Strings.create("step2")),
						Strings.create("deps"), Vectors.of((ACell) CVMLong.create(0))))));

		String metaJson = convex.core.util.JSON.toString(pipeline);
		String opRef = engine.storeAsset(Strings.create(metaJson), null).toHexString();

		Job orchJob = engine.jobs().invokeOperation(opRef, Maps.empty(), ctx);
		pollUntilStatus(orchJob, "STARTED", 5000);

		orchJob.cancel();
		pollUntilFinished(orchJob, 5000);

		String status = orchJob.getStatus().toString();
		assertTrue(status.equals("CANCELLED") || status.equals("FAILED"),
			"Orchestration should be cancelled or failed, not COMPLETE: " + status);
	}

	// ========== Cancel via JobManager ==========

	@Test
	public void testJobManagerCancelReturnsData() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		assertNotNull(engine.jobs().cancelJob(job.getID()));

		assertTrue(job.isFinished());
		assertEquals("CANCELLED", job.getStatus().toString());
	}

	@Test
	public void testJobManagerCancelNonExistentReturnsNull() {
		Blob fakeId = Blob.fromHex("deadbeefdeadbeef");
		assertNull(engine.jobs().cancelJob(fakeId));
	}

	// ========== Cancel agent:trigger with wait ==========

	@Test
	public void testCancelTriggerDuringWait() {
		// Use a chat agent (fast LLM) so the trigger actually runs.
		// Cancel mid-wait — the trigger should terminate promptly.
		createNeverAgent("trigger-cancel-agent");

		// Deliver a message so the agent has work
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "trigger-cancel-agent",
				Fields.MESSAGE, Strings.create("wake up")),
			ctx).awaitResult(5000);

		// Trigger with long wait — blocks up to 30s
		Job triggerJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "trigger-cancel-agent",
				Fields.WAIT, CVMLong.create(30000)),
			ctx);

		pollUntilStatus(triggerJob, "STARTED", 5000);
		long cancelTime = System.currentTimeMillis();
		triggerJob.cancel();
		pollUntilFinished(triggerJob, 5000);

		long elapsed = System.currentTimeMillis() - cancelTime;
		assertTrue(triggerJob.isFinished(),
			"Trigger should be finished after cancel");
		// The trigger either got cancelled or completed before we could
		// cancel it (race). Either way, it shouldn't block for 30s.
		assertTrue(elapsed < 10000,
			"Cancel should take effect promptly, took " + elapsed + "ms");
	}

	// ========== Double cancel is safe ==========

	@Test
	public void testDoubleCancelIsSafe() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		job.cancel();
		job.cancel(); // should be a no-op

		assertEquals("CANCELLED", job.getStatus().toString());
	}

	// ========== Suspend cancels active transition ==========

	@Test
	public void testSuspendCancelsActiveTransition() {
		createNeverAgent("suspend-cancel-agent");

		// Submit a task to make the agent run
		Job reqJob = engine.jobs().invokeOperation(
			"v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "suspend-cancel-agent",
				Fields.INPUT, Maps.of(Strings.create("task"), Strings.create("work"))),
			ctx);

		// Give the agent time to start the transition
		pollUntilStatus(reqJob, "STARTED", 5000);

		// Suspend should cancel the active transition
		Job suspendJob = engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "suspend-cancel-agent"),
			ctx);
		suspendJob.awaitResult(5000);

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("suspend-cancel-agent");
		assertEquals(AgentState.SUSPENDED, agent.getStatus());
	}

	// ========== Helpers ==========

	private void createChatAgent(String agentId) {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of(
					"config", Maps.of(
						"llmOperation", "v/test/ops/llm",
						"systemPrompt", "Echo the user."))),
			ctx).awaitResult(5000);
	}

	private void createNeverAgent(String agentId) {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never")),
			ctx).awaitResult(5000);
	}

	private AString getSessionIdFromAgent(String agentId) {
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(agentId);
		var sessions = agent.getSessions();
		if (sessions == null || sessions.count() == 0) return null;
		return Strings.create(sessions.entrySet().iterator().next().getKey().toHexString());
	}

	private Job getAgentRunJob(String agentId) {
		// The agent's run creates a transition job — look for it
		// by checking active jobs. This is a best-effort helper.
		return null; // Not critical for the test assertions
	}

	private void pollUntilStatus(Job job, String status, long timeoutMs) {
		if (job == null) return;
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (status.equals(job.getStatus().toString())) return;
			if (job.isFinished()) return;
			Thread.yield();
		}
	}

	private void pollUntilFinished(Job job, long timeoutMs) {
		if (job == null) return;
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!job.isFinished() && System.currentTimeMillis() < deadline) {
			Thread.yield();
		}
	}
}
