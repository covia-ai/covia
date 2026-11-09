package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.agent.AbstractLLMAdapter;
import covia.adapter.agent.GoalTreeContext;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.Engine;
import covia.venue.JobManager;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * One agent cycle, step by step (AGENT_LOOP.md §4.4): the run loop takes the
 * cycle's input into the conversation before the runtime runs, and the
 * cycle's outcome settles exactly the work it took. Each case runs the real
 * run loop and holds cycles at test gates, never on a clock.
 *
 * <p>Most cases run on both kinds of runtime: {@code v/test/ops/step}, which
 * leaves the conversation to the run loop and is scripted by its input, and
 * llmagent over {@code v/test/ops/failllm}, which keeps its frames live and
 * behaves as its model names.</p>
 */
public class AgentCycleTest {

	private final Engine engine = TestEngine.ENGINE;

	/** The two kinds of runtime a cycle can run. */
	private enum Runtime { STEP, LLM }

	private AgentAdapter agents() {
		return (AgentAdapter) engine.getAdapter("agent");
	}

	/** An owner of its own for each runtime a case runs on, so their agents are apart. */
	private static RequestContext owner(TestInfo info, Runtime runtime) {
		return RequestContext.of(Strings.create(TestEngine.uniqueDID(info) + "-" + runtime));
	}

	private static String gateName(String what) {
		return "cycle-" + what + "-" + Long.toHexString(System.nanoTime());
	}

	private Agent create(RequestContext owner, String agentId, AMap<AString, ACell> config) {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config), owner).awaitResult(5000);
		return engine.getVenueState().users().user(owner.getUserDID()).agent(agentId);
	}

	private Agent stepAgent(RequestContext owner, String agentId) {
		return create(owner, agentId, Maps.of(Fields.OPERATION, "v/test/ops/step"));
	}

	private Agent llmAgent(RequestContext owner, String agentId, String model) {
		return create(owner, agentId, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", "v/test/ops/failllm", "model", model));
	}

	/**
	 * An agent whose cycles hold at {@code gate} and then do what
	 * {@code outcome} names: {@code reply}, {@code fail} (the work's failure)
	 * or {@code cannot} (the agent cannot continue). A step agent is scripted
	 * by the message, so {@link #script} gives the message that does this; an
	 * LLM agent's model does it for every message.
	 */
	private Agent holding(Runtime runtime, RequestContext owner, String agentId, String gate, String outcome) {
		if (runtime == Runtime.STEP) return stepAgent(owner, agentId);
		String model = switch (outcome) {
			case "fail" -> "content";
			case "cannot" -> "setup";
			default -> "ok";
		};
		return llmAgent(owner, agentId, model + ":" + gate);
	}

	/** The message that makes a {@link #holding} agent's cycle hold and then do {@code outcome}. */
	private static String script(Runtime runtime, String gate, String outcome) {
		return (runtime == Runtime.STEP) ? "hold:" + gate + " " + outcome : outcome + " please";
	}

	/** The reason a {@link #holding} agent's failing cycle gives. */
	private static String failure(Runtime runtime, String outcome) {
		if (runtime == Runtime.STEP) return TestAdapter.STEP_FAILED;
		return TestAdapter.FAILLLM_REASON.formatted("fail".equals(outcome) ? "content" : "setup");
	}

	private Job chat(RequestContext owner, String agentId, Blob sid, String message) {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agentId, Fields.MESSAGE, Strings.create(message));
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid.toHexString()));
		return engine.jobs().invokeOperation("v/ops/agent/chat", input, owner);
	}

	private Job request(RequestContext owner, String agentId, String input) {
		return engine.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, Strings.create(input)), owner);
	}

	private void message(RequestContext owner, String agentId, AMap<AString, ACell> fields) {
		engine.jobs().invokeOperation("v/ops/agent/message",
			fields.assoc(Fields.AGENT_ID, Strings.create(agentId)), owner).awaitResult(5000);
	}

	private void op(RequestContext owner, String op, String agentId, AMap<AString, ACell> fields) {
		engine.jobs().invokeOperation("v/ops/agent/" + op,
			fields.assoc(Fields.AGENT_ID, Strings.create(agentId)), owner).awaitResult(5000);
	}

	/** Waits for the agent's run to reach a rest state, and returns it. */
	private AString awaitRest(Agent agent, RequestContext owner) throws Exception {
		return agents().awaitRunFinished(agent.getAgentId(), owner, 10_000);
	}

	private static Blob sessionOf(Job job) {
		return Blob.fromHex(RT.ensureString(job.getData().get(Fields.SESSION_ID)).toString());
	}

	private static AString hex(Job job) {
		return Strings.create(job.getID().toHexString());
	}

	private static long timelineCount(Agent agent) {
		AVector<ACell> timeline = agent.getTimeline();
		return (timeline == null) ? 0 : timeline.count();
	}

	private static long pendingCount(Agent agent, Blob sid) {
		AVector<ACell> pending = agent.getSessionPending(sid);
		return (pending == null) ? 0 : pending.count();
	}

	private static AVector<ACell> conversation(Agent agent, Blob sid) {
		AVector<ACell> frames = RT.ensureVector(RT.getIn(agent.getSession(sid), Agent.KEY_FRAMES));
		if (frames == null || frames.isEmpty()) return Vectors.empty();
		AVector<ACell> turns = GoalTreeContext.activeConversation(RT.ensureMap(frames.get(0)));
		return (turns == null) ? Vectors.empty() : turns;
	}

	/** The user turns in the session's root conversation with this content. */
	private static AVector<ACell> userTurns(Agent agent, Blob sid, String content) {
		AVector<ACell> found = Vectors.empty();
		AVector<ACell> turns = conversation(agent, sid);
		for (long i = 0; i < turns.count(); i++) {
			ACell turn = turns.get(i);
			if (Agent.ROLE_USER.equals(RT.getIn(turn, Agent.K_ROLE))
					&& Strings.create(content).equals(RT.getIn(turn, Agent.K_CONTENT))) found = found.conj(turn);
		}
		return found;
	}

	private static long assistantTurns(Agent agent, Blob sid) {
		AVector<ACell> turns = conversation(agent, sid);
		long n = 0;
		for (long i = 0; i < turns.count(); i++) {
			if (Agent.ROLE_ASSISTANT.equals(RT.getIn(turns.get(i), Agent.K_ROLE))) n++;
		}
		return n;
	}

	private static void awaitGate(TestAdapter.TestGate gate) throws InterruptedException {
		assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the cycle reaches its gate");
	}

	private static AVector<ACell> answered(Job chat) {
		return RT.ensureVector(RT.getIn(chat.awaitResult(10_000), Fields.ANSWERED));
	}

	// ========== The cycle takes its input before its runtime runs ==========

	@Test
	public void aCycleTakesItsInputBeforeItsRuntimeRuns(TestInfo info) throws Exception {
		for (Runtime runtime : Runtime.values()) {
			RequestContext owner = owner(info, runtime);
			String gateName = gateName("takes");
			try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
				Agent agent = holding(runtime, owner, "takes-input", gateName, "reply");
				String text = script(runtime, gateName, "reply");
				Job chat = chat(owner, "takes-input", null, text);
				awaitGate(gate);

				// While the runtime runs, its input is already in the conversation.
				Blob sid = sessionOf(chat);
				assertEquals(0, pendingCount(agent, sid), runtime + ": the message has left the queue");
				AVector<ACell> taken = userTurns(agent, sid, text);
				assertEquals(1, taken.count(), runtime + ": the message is a turn");
				assertEquals(hex(chat), RT.getIn(taken.get(0), Fields.JOB_ID), runtime + ": the turn names its chat");
				assertNotNull(agent.getSessionCycleEpoch(sid), runtime + ": the session is claimed");

				gate.release();
				chat.awaitResult(10_000);
				assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
				assertEquals(1, userTurns(agent, sid, text).count(), runtime + ": the message is presented once");
				assertEquals(1, assistantTurns(agent, sid), runtime + ": one reply");
				assertNull(agent.getSessionCycleEpoch(sid), runtime + ": the claim ends with the cycle");
			}
		}
	}

	@Test
	public void aMessageArrivingMidCycleWaitsForTheNextCycle(TestInfo info) throws Exception {
		for (Runtime runtime : Runtime.values()) {
			RequestContext owner = owner(info, runtime);
			String gateName = gateName("midcycle");
			try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
				Agent agent = holding(runtime, owner, "mid-cycle", gateName, "reply");
				Job first = chat(owner, "mid-cycle", null, script(runtime, gateName, "reply"));
				awaitGate(gate);
				Blob sid = sessionOf(first);
				Job second = chat(owner, "mid-cycle", sid, "second");
				assertEquals(1, pendingCount(agent, sid), runtime + ": the second message waits in the queue");
				assertEquals(Status.STARTED, first.getStatus(), runtime + ": a chat the cycle took is started");
				assertEquals(Status.PENDING, second.getStatus(), runtime + ": a queued chat is not");

				gate.release();
				assertEquals(Vectors.of(hex(first)), answered(first), runtime + ": a reply answers only what its cycle took");
				assertEquals(Vectors.of(hex(second)), answered(second), runtime + ": the second message gets its own cycle");
				assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
				assertEquals(2, timelineCount(agent), runtime.name());
				assertEquals(1, userTurns(agent, sid, "second").count(), runtime + ": presented once");
			}
		}
	}

	// ========== A failed cycle settles exactly what it took ==========

	/** The two LLM runtimes, by the name of their adapter. */
	private static final String[] LLM_RUNTIMES = {"llmagent", "goaltree"};

	/**
	 * Says "hello" to an agent whose setup is wrong and checks what its one
	 * cycle did: it took the message, which is a turn in the conversation,
	 * failed the chat with {@code reason} and suspended the agent with it.
	 * Inspecting the agent meets the same setup, so it fails the same way.
	 * Returns the chat's session.
	 */
	private Blob assertSuspendedByItsSetup(RequestContext owner, Agent agent, String reason,
			String runtime) throws Exception {
		String agentId = agent.getAgentId().toString();
		Job inspect = engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, agentId, Fields.MESSAGE, "hello"), owner);
		assertThrows(JobFailedException.class, () -> inspect.awaitResult(5000), runtime);
		assertTrue(inspect.getErrorMessage().contains(reason), runtime + ": " + inspect.getErrorMessage());

		Job chat = chat(owner, agentId, null, "hello");
		assertThrows(JobFailedException.class, () -> chat.awaitResult(10_000), runtime);
		assertTrue(chat.getErrorMessage().contains(reason), runtime + ": " + chat.getErrorMessage());
		assertEquals(Agent.SUSPENDED, awaitRest(agent, owner), runtime);
		assertTrue(agent.getError().toString().contains(reason), runtime + ": " + agent.getError());
		assertEquals(1, timelineCount(agent), runtime + ": one cycle, not one per piece of work");
		Blob sid = sessionOf(chat);
		assertEquals(0, pendingCount(agent, sid), runtime + ": the cycle took the message");
		assertEquals(1, userTurns(agent, sid, "hello").count(), runtime + ": the message is in the conversation");
		assertNull(agent.getSessionCycleEpoch(sid), runtime + ": the claim ends with the cycle");
		return sid;
	}

	/** Resumes an agent whose setup has been fixed and checks it answers in {@code sid} again. */
	private void assertRunsAgainOnResume(RequestContext owner, Agent agent, Blob sid,
			String runtime) throws Exception {
		String agentId = agent.getAgentId().toString();
		op(owner, "resume", agentId, Maps.empty());
		Job next = chat(owner, agentId, sid, "again");
		assertEquals(Vectors.of(hex(next)), answered(next), runtime);
		assertEquals(Agent.SLEEPING, awaitRest(agent, owner), runtime);
		assertNull(agent.getError(), runtime);
	}

	/**
	 * Both LLM runtimes read their system prompt at the start of every cycle,
	 * goaltree before it touches the session. A prompt that does not resolve
	 * would stop every cycle the same way, so the agent cannot continue. The
	 * message the cycle was for is still taken: it is a turn in the
	 * conversation and its chat fails with the reason. The agent is suspended
	 * after that one cycle, and runs again once the prompt resolves and it is
	 * resumed.
	 */
	@Test
	public void anUnresolvableSystemPromptSuspendsTheAgent(TestInfo info) throws Exception {
		for (String runtime : LLM_RUNTIMES) {
			RequestContext owner = RequestContext.of(Strings.create(TestEngine.uniqueDID(info) + "-" + runtime));
			AMap<AString, ACell> prompt = Maps.of("ref", "w/prompts/absent");
			Agent agent = create(owner, "no-prompt", Maps.of(
				Fields.OPERATION, "v/ops/" + runtime + "/chat",
				"llmOperation", "v/test/ops/failllm",
				"systemPrompt", prompt));
			Blob sid = assertSuspendedByItsSetup(owner, agent,
				AbstractLLMAdapter.SYSTEM_PROMPT_UNRESOLVED.formatted(prompt, ""), runtime);

			// The prompt is read again at the next cycle, so writing it is the fix.
			engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(Fields.PATH, Strings.create("w/prompts/absent"),
				Fields.VALUE, Strings.create("You are back.")), owner).awaitResult(5000);
			assertRunsAgainOnResume(owner, agent, sid, runtime);
		}
	}

	/**
	 * An LLM operation that does not resolve would fail every model call the
	 * same way, so the agent cannot continue, as when its provider refuses
	 * the setup. The cycle takes its message and suspends the agent; once
	 * the agent names an operation that resolves and is resumed, it answers.
	 */
	@Test
	public void anUnresolvableLlmOperationSuspendsTheAgent(TestInfo info) throws Exception {
		for (String runtime : LLM_RUNTIMES) {
			RequestContext owner = RequestContext.of(Strings.create(TestEngine.uniqueDID(info) + "-" + runtime));
			String absent = "v/test/ops/absent";
			Agent agent = create(owner, "no-model", Maps.of(
				Fields.OPERATION, "v/ops/" + runtime + "/chat",
				"llmOperation", absent));
			Blob sid = assertSuspendedByItsSetup(owner, agent, AbstractLLMAdapter.LLM_OPERATION_UNRESOLVED.formatted(
				absent, JobManager.CANNOT_RESOLVE_OPERATION + absent), runtime);

			op(owner, "update", "no-model", Maps.of(Fields.CONFIG, Maps.of("llmOperation", "v/test/ops/failllm")));
			assertRunsAgainOnResume(owner, agent, sid, runtime);
		}
	}

	/** A model operation whose provider does not resolve is a setup that does not resolve. */
	@Test
	public void aModelWhoseProviderDoesNotResolveSuspendsTheAgent(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Hash preset = engine.storeAsset(JSON.printPretty(Maps.of(
			Fields.NAME, "Model without a provider",
			Fields.OPERATION, Maps.of(Fields.ADAPTER, "test:failllm"),
			AbstractLLMAdapter.K_MODEL_FACET, Maps.of(
				AbstractLLMAdapter.K_MODEL_ID, "absent-model",
				AbstractLLMAdapter.K_MODEL_PROVIDER, "v/test/ops/absent"))), null);
		String ref = "a/" + preset.toHexString();
		Agent agent = create(owner, "no-provider", Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", ref));
		assertSuspendedByItsSetup(owner, agent, AbstractLLMAdapter.LLM_OPERATION_UNRESOLVED.formatted(ref, ""), "llmagent");
	}

	@Test
	public void aFailedCycleKeepsItsMessageAndFailsOnlyItsChat(TestInfo info) throws Exception {
		for (Runtime runtime : Runtime.values()) {
			RequestContext owner = owner(info, runtime);
			String gateName = gateName("fails");
			try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
				Agent agent = holding(runtime, owner, "fails-work", gateName, "fail");
				String text = script(runtime, gateName, "fail");
				Job first = chat(owner, "fails-work", null, text);
				awaitGate(gate);
				Blob sid = sessionOf(first);
				Job second = chat(owner, "fails-work", sid, "reply");

				gate.release();
				assertThrows(JobFailedException.class, () -> first.awaitResult(10_000));
				assertTrue(first.getErrorMessage().contains(failure(runtime, "fail")), first.getErrorMessage());
				// The second message waited for a cycle of its own: a step replies
				// to it, and an LLM agent whose model always fails fails it alone.
				if (runtime == Runtime.STEP) {
					assertEquals(Vectors.of(hex(second)), answered(second));
				} else {
					assertThrows(JobFailedException.class, () -> second.awaitResult(10_000));
				}
				assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
				assertNull(agent.getError(), runtime + ": a failure of the work is not a fault in the agent");
				assertEquals(2, timelineCount(agent), runtime.name());
				assertEquals(1, userTurns(agent, sid, text).count(), runtime + ": the failed cycle's message is kept");
				assertEquals(0, pendingCount(agent, sid), runtime.name());
			}
		}
	}

	@Test
	public void aMessageWithoutAChatBecomesATurnEvenWhenItsCycleFails(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = stepAgent(owner, "plain-message");
		Job opening = chat(owner, "plain-message", null, "reply");
		opening.awaitResult(10_000);
		Blob sid = sessionOf(opening);
		awaitRest(agent, owner);

		message(owner, "plain-message", Maps.of(Fields.SESSION_ID, Strings.create(sid.toHexString()),
			Fields.MESSAGE, Strings.create("fail")));
		assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
		assertEquals(2, timelineCount(agent));
		assertEquals(1, userTurns(agent, sid, "fail").count(), "the message is kept although its cycle failed");
		assertEquals(0, pendingCount(agent, sid));
	}

	@Test
	public void aCycleThatCannotContinueFailsItsChatAndKeepsTheRest(TestInfo info) throws Exception {
		for (Runtime runtime : Runtime.values()) {
			RequestContext owner = owner(info, runtime);
			String gateName = gateName("cannot");
			try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
				Agent agent = holding(runtime, owner, "cannot-continue", gateName, "cannot");
				String text = script(runtime, gateName, "cannot");
				Job first = chat(owner, "cannot-continue", null, text);
				awaitGate(gate);
				Blob sid = sessionOf(first);
				Job second = chat(owner, "cannot-continue", sid, "reply");

				gate.release();
				assertThrows(JobFailedException.class, () -> first.awaitResult(10_000));
				assertTrue(first.getErrorMessage().contains(failure(runtime, "cannot")), first.getErrorMessage());
				assertEquals(Agent.SUSPENDED, awaitRest(agent, owner), runtime.name());
				assertFalse(second.isFinished(), runtime + ": the chat the cycle never took waits");
				assertEquals(AgentAdapter.SUSPENDED_WAITING.formatted("cannot-continue", ": " + agent.getError()),
					Job.progressMessage(second.getData().get(Fields.PROGRESS)), runtime.name());
				assertEquals(1, pendingCount(agent, sid), runtime + ": its message stays queued");
				assertEquals(1, userTurns(agent, sid, text).count(), runtime + ": the taken message is kept");

				if (runtime == Runtime.LLM) op(owner, "update", "cannot-continue", Maps.of(Fields.CONFIG, Maps.of("model", "ok")));
				op(owner, "resume", "cannot-continue", Maps.empty());
				assertEquals(Vectors.of(hex(second)), answered(second), runtime + ": it runs after resume");
				assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
			}
		}
	}

	// ========== Tasks ==========

	@Test
	public void aStoppedCycleRunsItsTaskAgainAfterResume(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("stopped");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = stepAgent(owner, "stopped-task");
			String script = "hold:" + gateName + " complete";
			Job task = request(owner, "stopped-task", script);
			awaitGate(gate);
			op(owner, "suspend", "stopped-task", Maps.empty());
			gate.release(); // the stopped step sees its cycle was cancelled and does nothing more
			assertEquals(Agent.SUSPENDED, awaitRest(agent, owner));
			assertFalse(task.isFinished(), "a suspended agent keeps its task");
			Blob sid = sessionOf(task);
			assertEquals(1, userTurns(agent, sid, script).count(), "the task's input is in the conversation");

			op(owner, "resume", "stopped-task", Maps.empty());
			task.awaitResult(10_000);
			assertEquals(Status.COMPLETE, task.getStatus(), "it runs again from its conversation");
			assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
			assertEquals(1, userTurns(agent, sid, script).count(), "its input is still presented once");
		}
	}

	/**
	 * A stop revokes the cycle's claim in its own write, so the stop settles the
	 * cycle however its step ends (AGENT_LOOP.md §2.5.1). Here the step is left
	 * to run on, as a step the stop does not reach would: it tries to complete
	 * its task, which is refused, and the cycle is recorded as stopped, not
	 * failed. The agent keeps its task for its resume.
	 */
	@Test
	public void aStoppedCycleCannotResolveItsTask(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("stopped");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = stepAgent(owner, "stopped");
			Job task = request(owner, "stopped", "hold:" + gateName + " complete");
			awaitGate(gate);
			assertNotNull(agent.suspend(owner.getCallerDID(), Strings.create("stop")));
			gate.release();

			assertEquals(Agent.SUSPENDED, awaitRest(agent, owner));
			assertFalse(task.isFinished(), "a stopped cycle keeps its task: " + task.getErrorMessage());
			AVector<ACell> timeline = agent.getTimeline();
			assertEquals(CVMBool.TRUE, RT.getIn(timeline.get(timeline.count() - 1), Fields.CANCELLED),
				"the cycle is recorded as stopped, not failed");
		}
	}

	/**
	 * A resume does not undo a stop: the cycle stopped before the resume is
	 * still recorded as stopped and frees its session, and the agent then runs
	 * the task again.
	 */
	@Test
	public void aCycleStoppedThenResumedBeforeItEndsIsRecordedAndRunsAgain(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("resumed");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = stepAgent(owner, "resumed");
			Job task = request(owner, "resumed", "hold:" + gateName + " complete");
			awaitGate(gate);
			Blob sid = sessionOf(task);
			assertNotNull(agent.suspend(owner.getCallerDID(), Strings.create("stop")));
			assertNull(agent.getSessionCycleEpoch(sid), "the stop released the session");
			assertTrue(agent.tryResume());
			gate.release();

			task.awaitResult(10_000);
			assertEquals(Status.COMPLETE, task.getStatus(), "the task runs again after the resume");
			assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
			AVector<ACell> timeline = agent.getTimeline();
			assertEquals(2, timeline.count(), timeline.toString());
			assertEquals(CVMBool.TRUE, RT.getIn(timeline.get(0), Fields.CANCELLED),
				"the cycle stopped before the resume is recorded as stopped");
			assertNull(RT.getIn(timeline.get(1), Fields.CANCELLED));
		}
	}

	@Test
	public void aContinuationDuringACycleIsShownOnceInTheNextCycle(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("continuation");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = stepAgent(owner, "continued-task");
			String script = "hold:" + gateName + " yield";
			Job task = request(owner, "continued-task", script);
			awaitGate(gate);
			message(owner, "continued-task", Maps.of(Fields.TASK_ID, hex(task), Fields.MESSAGE, Strings.create("complete")));
			gate.release();

			ACell result = task.awaitResult(10_000);
			assertEquals(Strings.create("complete"), RT.getIn(result, Fields.OUTPUT, Fields.INPUT),
				"the next cycle is shown the continuation");
			assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
			Blob sid = sessionOf(task);
			assertEquals(1, userTurns(agent, sid, script).count());
			assertEquals(1, userTurns(agent, sid, "complete").count(), "the continuation is presented once");
		}
	}

	@Test
	public void aSessionNamedWithThePrefixIsHeldAsBareHex(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		stepAgent(owner, "bare-session");
		Job first = chat(owner, "bare-session", null, "reply");
		first.awaitResult(10_000);
		String bare = sessionOf(first).toHexString();

		Job second = engine.jobs().invokeOperation("v/ops/agent/chat", Maps.of(Fields.AGENT_ID, "bare-session",
			Fields.MESSAGE, Strings.create("reply"), Fields.SESSION_ID, Strings.create("0x" + bare)), owner);
		second.awaitResult(10_000);

		assertEquals(Strings.create(bare), second.getData().get(Fields.SESSION_ID));
	}

	@Test
	public void aContinuationMayNameItsTaskWithThePrefix(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("prefixed-continuation");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			stepAgent(owner, "prefixed-task");
			Job task = request(owner, "prefixed-task", "hold:" + gateName + " yield");
			awaitGate(gate);
			message(owner, "prefixed-task", Maps.of(Fields.TASK_ID, Strings.create("0x" + task.getID().toHexString()),
				Fields.MESSAGE, Strings.create("complete")));
			gate.release();

			ACell result = task.awaitResult(10_000);
			assertEquals(Strings.create("complete"), RT.getIn(result, Fields.OUTPUT, Fields.INPUT));
		}
	}

	@Test
	public void aTaskWhoseSessionIsGoneStillRuns(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("orphan");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = stepAgent(owner, "orphan-task");
			Job first = request(owner, "orphan-task", "hold:" + gateName + " complete");
			awaitGate(gate);
			Job orphan = request(owner, "orphan-task", "complete");
			op(owner, "delete-session", "orphan-task", Maps.of(Fields.SESSION_ID,
				Strings.create(sessionOf(orphan).toHexString())));
			gate.release();

			first.awaitResult(10_000);
			orphan.awaitResult(10_000);
			assertEquals(Status.COMPLETE, orphan.getStatus(), "a task without its session runs once and settles");
			assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
			assertEquals(0, agent.getTasks().count());
		}
	}

	// ========== Chats ==========

	@Test
	public void aChatTakenByAYieldingCycleIsAnsweredByTheNextReply(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Agent agent = stepAgent(owner, "yielding-chat");
		Job first = chat(owner, "yielding-chat", null, "yield");
		Blob sid = sessionOf(first);
		assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
		assertFalse(first.isFinished(), "a cycle without a reply leaves its chat waiting");
		assertEquals(0, pendingCount(agent, sid), "but its message was taken");

		Job second = chat(owner, "yielding-chat", sid, "reply");
		AVector<ACell> both = Vectors.of(hex(second), hex(first));
		assertEquals(both, answered(second), "this cycle's message first, then the one taken earlier");
		assertEquals(both, answered(first));
		assertEquals(RT.getIn(second.getOutput(), Fields.RESPONSE), RT.getIn(first.getOutput(), Fields.RESPONSE));
	}

	/**
	 * A chat waits for a reply before its message is queued. A reply landing
	 * in between answers only the chats a cycle took, so this chat waits for
	 * the reply to its own message. Session delivery accepts a message inside
	 * the intake transaction, which holds the chat in that gap.
	 */
	@Test
	public void aReplyBeforeAChatIsQueuedDoesNotAnswerIt(TestInfo info) throws Exception {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = gateName("gap");
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Agent agent = stepAgent(owner, "chat-gap");
			Job first = chat(owner, "chat-gap", null, "hold:" + gateName + " reply");
			awaitGate(gate);
			Blob sid = sessionOf(first);

			CountDownLatch waiting = new CountDownLatch(1);
			CountDownLatch queue = new CountDownLatch(1);
			RequestContext delivering = owner.withSessionDelivery((draft, session, job) -> {
				waiting.countDown();
				try {
					queue.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return true;
			});
			CompletableFuture<Job> delivered = CompletableFuture.supplyAsync(() -> agents().deliverSession(
				Maps.of(Fields.AGENT_ID, "chat-gap", Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.MESSAGE, "reply"), delivering));
			try {
				assertTrue(waiting.await(10, TimeUnit.SECONDS), "the second chat waits, its message not yet queued");
				gate.release();
				assertEquals(Vectors.of(hex(first)), answered(first), "the reply answers the chat its cycle took");
				assertEquals(Agent.SLEEPING, awaitRest(agent, owner));
			} finally {
				queue.countDown();
			}
			Job second = delivered.get(10, TimeUnit.SECONDS);
			assertEquals(Vectors.of(hex(second)), answered(second), "it is answered by the reply to its own message");
			assertEquals(Vectors.of(Strings.create("reply")), RT.getIn(second.getOutput(), Fields.RESPONSE, "saw"));
		}
	}
}
