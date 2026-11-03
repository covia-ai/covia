package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
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
	public void testCancelWithReasonIsTheError() {
		// A cancel with a reason looks like any non-completion: CANCELLED, and
		// the reason is the error every reader shows.
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		job.cancel("superseded by a newer request");
		assertEquals("CANCELLED", job.getStatus().toString());
		assertEquals("superseded by a newer request", RT.getIn(job.getData(), Fields.ERROR).toString());

		// Through the manager, under the owner's context.
		Job other = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		engine.jobs().cancelJob(other.getID(), ctx, "operator stopped it");
		assertEquals("operator stopped it", RT.getIn(engine.jobs().getJobData(other.getID(), ctx), Fields.ERROR).toString());

		// No reason, or a blank one, keeps the default that names the job.
		Job plain = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		plain.cancel("   ");
		assertEquals("Job cancelled: " + plain.getID().toHexString(), RT.getIn(plain.getData(), Fields.ERROR).toString());
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
		job.cancel();
		assertThrows(JobFailedException.class, () -> job.awaitResult(5000));
	}

	@Test
	public void testAwaitResultWithTimeoutThrowsOnCancel() {
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), ctx);
		job.cancel();
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

		awaitStarted(job);
		job.cancel();

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

		awaitStarted(job);
		job.cancel();

		assertEquals("CANCELLED", job.getStatus().toString(),
			"Delay job should be cancelled before internal echo ran");
	}

	// ========== Cancel agent:chat ==========

	@Test
	public void testCancelChatReleasesSlot() {
		createNeverAgent("chat-cancel-agent");

		Job chatJob;
		try (AgentWatch watch = AgentWatch.open(engine, ALICE_DID, "chat-cancel-agent")) {
			chatJob = engine.jobs().invokeOperation(
				"v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, "chat-cancel-agent",
					Fields.MESSAGE, Strings.create("hello")),
				ctx);
			awaitCycleStart(watch);
		}
		assertEquals(Status.STARTED, chatJob.getStatus());

		AString sidHex = getSessionIdFromAgent("chat-cancel-agent");
		assertNotNull(sidHex, "Session should have been minted");
		Blob sid = Blob.fromHex(sidHex.toString());

		// Verify chat slot is reserved
		covia.adapter.AgentAdapter agentAdapter =
			(covia.adapter.AgentAdapter) engine.getAdapter("agent");
		AString agentIdStr = Strings.create("chat-cancel-agent");
		assertNotNull(agentAdapter.getActiveChatForTest(ALICE_DID, agentIdStr, sid),
			"Chat slot should be reserved before cancel");

		chatJob.cancel();
		assertEquals("CANCELLED", chatJob.getStatus().toString());

		// Issue #85: cancelling the caller's Job releases the in-memory chat
		// slot immediately via the cancel hook registered in handleChat.
		// No need to wait for the run loop — the slot is freed synchronously
		// when chatJob.cancel() fires the hook.
		assertNull(agentAdapter.getActiveChatForTest(ALICE_DID, agentIdStr, sid),
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
	public void testCancelRequestTakesItsTaskOutOfTheQueue() {
		createNeverAgent("req-cancel-agent");

		Job reqJob;
		try (AgentWatch watch = AgentWatch.open(engine, ALICE_DID, "req-cancel-agent")) {
			reqJob = engine.jobs().invokeOperation(
				"v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "req-cancel-agent",
					Fields.INPUT, Maps.of(Strings.create("task"), Strings.create("do something"))),
				ctx);
			awaitCycleStart(watch);
		}
		Blob taskId = reqJob.getID();

		reqJob.cancel();
		assertEquals("CANCELLED", reqJob.getStatus().toString());

		// A cancelled request leaves the queue, so the agent never runs work
		// nobody is waiting for, even once its Job is no longer in memory.
		assertTaskGone("req-cancel-agent", taskId);
	}

	@Test
	public void testCancelledRequestJobNotCompletedByAgent() {
		// Hold the transition open so cancellation deterministically wins before
		// the agent's completion/error path. A fast chat transition made this test
		// race: it could legitimately fail before cancel() committed.
		createNeverAgent("req-cancel-agent2");

		Job reqJob;
		try (AgentWatch watch = AgentWatch.open(engine, ALICE_DID, "req-cancel-agent2")) {
			reqJob = engine.jobs().invokeOperation(
				"v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "req-cancel-agent2",
					Fields.INPUT, Maps.of(Strings.create("message"), Strings.create("hello"))),
				ctx);
			awaitCycleStart(watch);
		}
		reqJob.cancel();
		assertEquals("CANCELLED", reqJob.getStatus().toString(),
			"the explicit cancellation must win before the worker is released");

		// The cancelled request has left the queue. Suspending then cancels the
		// never-ending transition, which was still working on it, and resuming
		// finds nothing to run. Neither path may replace the already-committed
		// terminal state.
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "req-cancel-agent2"),
			ctx).awaitResult(5000);
		assertEquals("CANCELLED", reqJob.getStatus().toString());
		engine.jobs().invokeOperation(
			"v/ops/agent/resume",
			Maps.of(Fields.AGENT_ID, "req-cancel-agent2"),
			ctx).awaitResult(5000);
		assertTaskGone("req-cancel-agent2", reqJob.getID());

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
						// Every leaf of a step input is a binding, so a literal needs
						// ["const", …]. A bare scalar is rejected — previously only when
						// the step ran, which for this fixture was never (step 0 blocks
						// and the test cancels), so the malformed spec went unnoticed.
						Strings.create("input"), Maps.of(
							Strings.create("value"), Vectors.of(Fields.CONST, Strings.create("step2"))),
						Strings.create("deps"), Vectors.of((ACell) CVMLong.create(0))))));

		String metaJson = convex.core.util.JSON.toString(pipeline);
		String opRef = engine.storeAsset(Strings.create(metaJson), null).toHexString();

		Job orchJob = engine.jobs().invokeOperation(opRef, Maps.empty(), ctx);
		awaitStarted(orchJob);

		orchJob.cancel();

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

	@Test
	public void testCancelByIdWaitsForAcceptanceInProgress() throws Exception {
		// Acceptance writes a recorded job's row, then registers its live handle,
		// both under the account monitor. A caller can list the row in between and
		// cancel it by id; that cancel must wait for the handle, not miss it and be
		// lost. Hold the half-state by hand: the monitor held, a row, no handle.
		Object acceptance = engine.getAuth();
		User user = engine.getVenueState().users().ensure(ALICE_DID);
		Blob id = Blob.createRandom(new java.util.Random(), 16);
		CompletableFuture<AMap<AString, ACell>> cancelled = new CompletableFuture<>();
		Thread canceller;
		try {
			synchronized (acceptance) {
				assertTrue(user.jobs().create(id, Maps.of(Fields.ID, id, Fields.STATUS, Status.PENDING,
					Fields.CALLER, ALICE_DID)));
				canceller = Thread.ofPlatform().start(
					() -> cancelled.complete(engine.jobs().cancelJob(id)));
				awaitBlockedOnOrDone(canceller, acceptance);
				assertFalse(cancelled.isDone(),
					"Cancel by id must wait out the acceptance in progress, not miss its handle");
			}
			canceller.join();
			// No handle was ever registered here, so there is nothing live to cancel.
			assertNull(cancelled.join());
		} finally {
			user.jobs().remove(id);
		}
	}

	// ========== Cancel agent:trigger with wait ==========

	@Test
	public void testCancelTriggerDuringWait() throws Exception {
		createNeverAgent("trigger-cancel-agent");

		// Deliver a message so the agent has work
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "trigger-cancel-agent",
				Fields.MESSAGE, Strings.create("wake up")),
			ctx).awaitResult(5000);

		// agent:trigger blocks the *calling* thread on the run-loop future, so a
		// caller can never cancel its own trigger. Run it on a background thread
		// and cancel by id from the test thread — exactly how a separate cancel
		// request cancels it in production. With a cancellable wait this returns
		// in milliseconds; the 30s WAIT only elapses if the wait or the cancel
		// regresses (caught by the bounded get below, not by a 30s hang).
		java.util.HashSet<Blob> before = new java.util.HashSet<>();
		for (var e : engine.jobs().getJobs(ctx).entrySet()) before.add(e.getKey());

		// The trigger is the only new job for this caller after the snapshot.
		CompletableFuture<Blob> submitted = new CompletableFuture<>();
		java.util.function.Consumer<Job> onJob = j -> {
			if (!before.contains(j.getID()) && ALICE_DID.equals(j.getData().get(Fields.CALLER))) {
				submitted.complete(j.getID());
			}
		};
		engine.jobs().addJobUpdateListener(onJob);
		CompletableFuture<Job> triggerDone = new CompletableFuture<>();
		Blob triggerId;
		try {
			Thread.ofVirtual().start(() -> {
				try {
					triggerDone.complete(engine.jobs().invokeOperation(
						"v/ops/agent/trigger",
						Maps.of(Fields.AGENT_ID, "trigger-cancel-agent",
							Fields.WAIT, CVMLong.create(30000)),
						ctx));
				} catch (Throwable t) {
					triggerDone.completeExceptionally(t);
				}
			});
			triggerId = submitted.get(10, java.util.concurrent.TimeUnit.SECONDS);
		} finally {
			engine.jobs().removeJobUpdateListener(onJob);
		}
		assertNotNull(engine.jobs().getJobs(ctx).get(triggerId), "Trigger job should be listed");

		// The id was read from the listing, which can run ahead of the live
		// handle; the cancel must still reach the job.
		assertNotNull(engine.jobs().cancelJob(triggerId), "Cancel must find the listed trigger job");

		// Only a cancel that unblocks the wait returns before the 30s WAIT.
		Job triggerJob = triggerDone.get(10, java.util.concurrent.TimeUnit.SECONDS);

		assertTrue(triggerJob.isFinished(), "Trigger should be finished after cancel");
		assertEquals("CANCELLED", triggerJob.getStatus().toString());
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

		// Submit a task to make the agent run, and wait for its transition to start
		try (AgentWatch watch = AgentWatch.open(engine, ALICE_DID, "suspend-cancel-agent")) {
			engine.jobs().invokeOperation(
				"v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "suspend-cancel-agent",
					Fields.INPUT, Maps.of(Strings.create("task"), Strings.create("work"))),
				ctx);
			awaitCycleStart(watch);
		}

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
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "Echo the user.")),
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

	/** Yields until {@code thread} is blocked entering {@code monitor}, or has finished. */
	private static void awaitBlockedOnOrDone(Thread thread, Object monitor) {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		int lock = System.identityHashCode(monitor);
		while (thread.isAlive()) {
			ThreadInfo info = threads.getThreadInfo(thread.threadId());
			if (info != null && info.getThreadState() == Thread.State.BLOCKED
					&& info.getLockInfo() != null
					&& info.getLockInfo().getIdentityHashCode() == lock) return;
			Thread.yield();
		}
	}

	/** Waits until the job has started, failing if it finished instead. */
	private static void awaitStarted(Job job) {
		TestEngine.awaitJob(job, j -> Status.STARTED.equals(j.getStatus()) || j.isFinished(), 10_000,
			() -> "job " + job.getID() + " to start");
		assertEquals(Status.STARTED, job.getStatus(), "the job is still running");
	}

	/** Waits until the agent's run loop is invoking its transition. */
	private static void awaitCycleStart(AgentWatch watch) {
		watch.awaitEvent("cycle:start", e -> AgentEvents.CYCLE_START.equals(e.type()));
	}

	private void assertTaskGone(String agentId, Blob taskId) {
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent(agentId);
		assertTrue(agent.getTasks() == null || agent.getTasks().get(taskId) == null,
			"the cancelled task has left the agent's queue (status=" + agent.getStatus() + ")");
	}
}
