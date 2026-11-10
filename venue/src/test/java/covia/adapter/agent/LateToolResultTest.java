package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.Task;
import covia.venue.AgentWatch;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.Models;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * A tool call that outlives its agent's {@code toolCallTimeoutMs} (#607): the
 * model is told the recorded job it runs as, and when it ends its outcome
 * reaches the task whose cycle made the call as its next input, or else the
 * asking session as a message, after a restart too.
 * {@code v/test/ops/hold} finishes only when the test releases its gate, so
 * the one real wait is the 1 s minimum limit, paid by the end-to-end cases.
 */
public class LateToolResultTest {

	private static final String HOLD = "v/test/ops/hold";
	private static final long LIMIT_MS = 1000;
	private static final Pattern JOB_NAMED = Pattern.compile("running as job ([0-9a-f]+)\\.");
	/** The call a test tracks by hand, as a model would have made it. */
	private static final String PARKED_CALL_ID = "call-parked";
	private static final String PARKED_TOOL = "slow_tool";

	private final Engine engine = TestEngine.ENGINE;

	private static AMap<AString, ACell> holdCall(String id, String gate, String key, String value) {
		return Maps.of("id", id, "name", HOLD, "arguments", Maps.of("gate", gate, key, value));
	}

	/** The job a still-running result names. */
	private static String jobNamed(ACell result) {
		Matcher m = JOB_NAMED.matcher(String.valueOf(result));
		assertTrue(m.find(), "the result names a job: " + result);
		return m.group(1);
	}

	private static void createAgent(Engine e, RequestContext owner, String agentId, String llm, ACell... extra) {
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat",
			"llmOperation", llm,
			AbstractLLMAdapter.K_TOOL_CALL_TIMEOUT_MS, LIMIT_MS);
		for (int i = 0; i < extra.length; i += 2) config = config.assoc((AString) extra[i], extra[i + 1]);
		e.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId,
			Fields.CONFIG, TestEngine.unrestricted(config)), owner).awaitResult(5000);
	}

	private static String conversation(Agent agent, Blob sid) {
		return GoalTreeContext.activeConversation(RT.ensureMap(
			RT.getIn(agent.getSession(sid), Fields.FRAMES, CVMLong.ZERO))).toString();
	}

	@Test
	public void aCallPastItsLimitDeliversItsOutcomeToTheSession(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		createAgent(engine, owner, "late-step", "v/test/ops/llm", Models.K_MODEL_PROFILE,
			Maps.of(Models.K_OPTIONS, Maps.of(AbstractLLMAdapter.OPT_LATE_TOOL_RESULTS,
				AbstractLLMAdapter.LATE_RESULTS_TOOL)));
		User user = engine.getVenueState().users().user(did);
		Agent agent = user.agent("late-step");
		Blob sid = Blob.fromHex("1a7e00001a7e00001a7e00001a7e0000");
		agent.ensureSession(sid, did);

		String okGate = "late-ok-" + did;
		String badGate = "late-bad-" + did;
		String okJob;
		String badJob;
		try (TestAdapter.TestGate ok = TestAdapter.createGate(okGate);
				TestAdapter.TestGate bad = TestAdapter.createGate(badGate);
				AgentWatch watch = AgentWatch.open(engine, did, "late-step")) {
			// Both calls run together, so the step pays the limit once.
			AMap<AString, ACell> stepped = RT.ensureMap(engine.jobs().invokeOperation(
				"v/ops/agent/step", Maps.of(
					Fields.AGENT_ID, "late-step",
					Fields.SESSION_ID, sid.toHexString(),
					"assistant", Maps.of("toolCalls", Vectors.of(
						holdCall("c-ok", okGate, "result", "finished-late"),
						holdCall("c-bad", badGate, "error", "broke-late")))),
				owner).awaitResult(10_000));
			AVector<ACell> calls = RT.ensureVector(stepped.get(Fields.CALLS));
			okJob = jobNamed(RT.getIn(calls.get(0), Fields.RESULT));
			badJob = jobNamed(RT.getIn(calls.get(1), Fields.RESULT));
			assertEquals(Strings.create(String.format(AbstractLLMAdapter.TOOL_STILL_RUNNING, HOLD, LIMIT_MS, okJob)),
				RT.getIn(calls.get(0), Fields.RESULT), "a call past its limit names the job it is still running as");
			assertNotNull(engine.jobs().getJobData(Blob.fromHex(okJob), owner), "a job the owner can look up");
			assertEquals(0, agent.getTimeline() == null ? 0 : agent.getTimeline().count(),
				"nothing reaches the session while the calls are still running");

			ok.release();
			bad.release();

			// Each outcome is a message in the asking session, which wakes the agent.
			watch.awaitState("both late outcomes in the session", () -> {
				if (agent.getSession(sid) == null) return false;
				String c = conversation(agent, sid);
				return c.contains("finished-late") && c.contains("broke-late");
			});
		}
		String c = conversation(agent, sid);
		assertTrue(c.contains("COMPLETE") && c.contains("FAILED"),
			"each outcome carries its status: " + c);
		assertTrue(c.contains(HOLD), "each outcome names its operation: " + c);
		assertTrue(c.contains(okJob) && c.contains(badJob), "each outcome names its job: " + c);
		assertTrue(c.contains("c-ok") && c.contains("c-bad"), "each outcome cites the model's call: " + c);
		assertEquals(0, agent.getPending().count(), "a delivered outcome leaves the pending index");
		AVector<ACell> stored = GoalTreeContext.activeConversation(RT.ensureMap(
			RT.getIn(agent.getSession(sid), Fields.FRAMES, CVMLong.ZERO)));
		int outcomes = 0;
		for (long i = 0; i < stored.count(); i++) {
			if (!Agent.SOURCE_TOOL_OUTCOME.equals(RT.getIn(stored.get(i), Agent.K_SOURCE))) continue;
			assertEquals(Principals.agentDID(did, Strings.create("late-step")), RT.getIn(stored.get(i), Fields.CALLER),
				"an outcome comes from the agent that made the call");
			outcomes++;
		}
		assertEquals(2, outcomes);

		// The agent's profile asks for late outcomes as tool results: each is
		// its own tool_call_outcome exchange, answering a call that names its job.
		AVector<ACell> messages = RT.ensureVector(RT.getIn(engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "late-step", Fields.SESSION_ID, sid.toHexString()), owner).awaitResult(10_000),
			Fields.MESSAGES));
		java.util.Set<ACell> jobs = new java.util.HashSet<>();
		for (long i = 0; i + 1 < messages.count(); i++) {
			ACell call = RT.getIn(messages.get(i), Fields.TOOL_CALLS, CVMLong.ZERO);
			if (!Strings.create(ContextAssembler.TOOL_CALL_OUTCOME_TOOL).equals(RT.getIn(call, Fields.NAME))) continue;
			ACell result = messages.get(i + 1);
			assertEquals(RT.getIn(call, Fields.ID), RT.getIn(result, Fields.ID), "the result answers its call");
			assertEquals(RT.getIn(call, Fields.ARGUMENTS, Fields.JOB_ID), RT.getIn(result, Fields.STRUCTURED_CONTENT, Fields.JOB_ID));
			jobs.add(RT.getIn(call, Fields.ARGUMENTS, Fields.JOB_ID));
		}
		assertEquals(java.util.Set.of(Strings.create(okJob), Strings.create(badJob)), jobs, "one exchange per outcome: " + messages);
		for (long i = 0; i < messages.count(); i++) {
			assertFalse(Agent.ROLE_USER.equals(RT.getIn(messages.get(i), Agent.K_ROLE))
				&& String.valueOf(RT.getIn(messages.get(i), Agent.K_CONTENT)).contains("finished-late"),
				"an outcome is not also a message");
		}
	}

	/** A cycle without a session builds its turns from its input: an outcome keeps its own provenance, not the requester's. */
	@Test
	public void aCycleWithNoSessionKeepsTheOutcomesOwnProvenance() {
		AString agentDID = Strings.create("did:key:z6MkOwner:g:worker");
		AMap<AString, ACell> input = Maps.of(
			Fields.NEW_INPUT, Maps.of(Fields.JOB_ID, "0a1b", Fields.STATUS, Status.COMPLETE),
			Fields.TASKS, Vectors.of(Maps.of(Fields.CALLER, "did:key:z6MkRequester")),
			Agent.KEY_INPUT_SOURCE, Maps.of(Fields.CALLER, agentDID,
				Agent.K_SOURCE, Agent.SOURCE_TOOL_OUTCOME));
		AVector<ACell> frames = FrameStore.appendCycleInputTurns(
			Vectors.of((ACell) GoalTreeContext.createFrame("")), null, input, 1L);
		AVector<ACell> turns = GoalTreeContext.activeConversation(RT.ensureMap(frames.get(0)));
		ACell turn = turns.get(turns.count() - 1);
		assertEquals(agentDID, RT.getIn(turn, Fields.CALLER));
		assertEquals(Agent.SOURCE_TOOL_OUTCOME, RT.getIn(turn, Agent.K_SOURCE));
	}

	@Test
	public void aCallWithNoSessionIsToldItsJobAndNothingFollows(TestInfo info) throws Exception {
		AString did = TestEngine.uniqueDID(info);
		RequestContext ctx = RequestContext.of(did).withAgentId(Strings.create("sessionless"));
		AbstractLLMAdapter adapter = (AbstractLLMAdapter) engine.getAdapter("llmagent");
		String gateName = "late-sessionless-" + did;
		Job mirror;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			AtomicReference<Job> call = new AtomicReference<>();
			engine.jobs().invokeInternal(Strings.create(HOLD), Maps.of("gate", gateName, Fields.RESULT, "out"), ctx, call::set);
			assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS));
			AString said = adapter.stillRunning(Strings.create(HOLD), call.get(), ctx, LIMIT_MS);
			String id = jobNamed(said);
			assertEquals(String.format(AbstractLLMAdapter.TOOL_STILL_RUNNING_UNTRACKED, HOLD, LIMIT_MS, id), said.toString());
			mirror = engine.jobs().getJob(Blob.fromHex(id));
			assertNotNull(mirror, "the named job is live");
			gate.release();
			TestEngine.awaitJob(mirror, Job::isFinished, 10_000, () -> "the job did not finish");
		}
		AMap<AString, ACell> record = engine.jobs().getJobData(mirror.getID(), RequestContext.of(did));
		assertEquals(Status.COMPLETE, record.get(Fields.STATUS));
		assertEquals(Strings.create("out"), record.get(Fields.OUTPUT), "the outcome waits in the job");
	}

	@Test
	public void recordToolCallsRecordsEachCallFromTheStart(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		createAgent(engine, owner, "recording", "v/test/ops/llm", AbstractLLMAdapter.K_RECORD_TOOL_CALLS, CVMBool.TRUE);
		createAgent(engine, owner, "unrecorded", "v/test/ops/llm");
		Blob sid = Blob.fromHex("5e55000000000000000000000000beef");
		for (String agentId : new String[] {"recording", "unrecorded"}) {
			engine.getVenueState().users().user(did).agent(agentId).ensureSession(sid, did);
			engine.jobs().invokeOperation("v/ops/agent/step", Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.SESSION_ID, sid.toHexString(),
				"assistant", Maps.of("toolCalls", Vectors.of(Maps.of("id", "c-" + agentId,
					"name", "v/test/ops/echo", "arguments", Maps.of("said", agentId))))),
				owner).awaitResult(10_000);
		}
		long recorded = 0, unrecorded = 0;
		for (var entry : engine.jobs().getJobs(owner).entrySet()) {
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			if (!Strings.create("v/test/ops/echo").equals(record.get(Fields.OP))) continue;
			String said = String.valueOf(RT.getIn(record, Fields.INPUT, "said"));
			if (said.equals("recording")) recorded++;
			if (said.equals("unrecorded")) unrecorded++;
		}
		assertEquals(1, recorded, "the recording agent's call is a recorded job");
		assertEquals(0, unrecorded, "another agent's call is not");
	}

	private static final AMap<AString, ACell> RESTART_CONFIG = Maps.of(
		Config.USERS, Maps.of(Config.AUTO_CREATE, true),
		Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
	private static final AString RESTART_OWNER = Strings.create("did:key:zLateCallRestart");

	/** A venue as a crash leaves it: a task parked on a late call that is still running. */
	private record Crashed(TestEngine.Restartable venue, Blob taskId, Blob mirrorId) {}

	private Crashed crashWhileACallRuns(String agentId) throws Exception {
		TestEngine.Restartable venue = TestEngine.restartable(RESTART_CONFIG);
		RequestContext owner = RequestContext.of(RESTART_OWNER);
		Engine first = venue.start();
		Engine.addDemoAssets(first);
		createAgent(first, owner, agentId, "v/test/ops/toolllm");
		String gateName = agentId + "-" + System.identityHashCode(first);
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				AgentWatch watch = AgentWatch.open(first, RESTART_OWNER, agentId)) {
			Job job = first.jobs().invokeOperation("v/ops/agent/request", Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.INPUT, Maps.of("ask", "hold:" + gateName)), owner);
			watch.awaitRest(1);
			Agent agent = first.getVenueState().users().user(RESTART_OWNER).agent(agentId);
			assertTrue(Task.isParked(agent.getTasks().get(job.getID())));
			assertEquals(1, agent.getPending().count(), "the late call waits in the pending index");
			first.syncState();
			Crashed crashed = new Crashed(venue.crashed(), job.getID(),
				agent.getPending().entryAt(0).getKey());
			first.close();
			return crashed;
		}
	}

	private static Engine restart(Crashed crashed) throws Exception {
		Engine e = crashed.venue().start();
		Engine.addDemoAssets(e);
		return e;
	}

	@Test
	public void aRestartDeliversALateCallsFailureToTheTaskWaitingOnIt() throws Exception {
		Crashed crashed = crashWhileACallRuns("late-restart");
		Engine second = restart(crashed);
		try {
			Agent agent = second.getVenueState().users().user(RESTART_OWNER).agent("late-restart");
			try (AgentWatch watch = AgentWatch.open(second, RESTART_OWNER, "late-restart")) {
				second.jobs().recoverJobs();
				((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();
				watch.awaitRest(1);
			}
			assertEquals(0, agent.getPending().count(), "the outcome left the pending index");
			AMap<AString, ACell> task = second.jobs().getJobData(crashed.taskId(), RequestContext.of(RESTART_OWNER));
			assertEquals(Status.COMPLETE, task.get(Fields.STATUS),
				"the task ran again on the outcome, and its reply is its result, rather than failing as lost");
			String shown = conversation(agent, sessionOf(task));
			assertTrue(shown.contains(crashed.mirrorId().toHexString()) && shown.contains(AAdapter.RESTARTED_DURING_EXECUTION),
				"the task's cycle was shown the call's failure: " + shown);
		} finally {
			second.close();
		}
	}

	@Test
	public void aTaskWaitingOnADeletedLateCallFailsAtRestart() throws Exception {
		Crashed crashed = crashWhileACallRuns("late-deleted");
		Engine second = restart(crashed);
		try {
			User user = second.getVenueState().users().user(RESTART_OWNER);
			assertNotNull(user.jobs().remove(crashed.mirrorId()), "the late call's job is deleted before the restart");
			second.jobs().recoverJobs();
			((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();
			Agent agent = user.agent("late-deleted");
			assertEquals(0, agent.getPending().count(), "a deleted job leaves nothing to deliver");
			AMap<AString, ACell> task = second.jobs().getJobData(crashed.taskId(), RequestContext.of(RESTART_OWNER));
			assertEquals(Status.FAILED, task.get(Fields.STATUS),
				"with nothing left to wait on, the task fails rather than stay parked");
			assertEquals(AgentAdapter.TASK_WAIT_LOST, String.valueOf(task.get(Fields.ERROR)));
		} finally {
			second.close();
		}
	}

	@Test
	public void aTaskWaitingOnACallWhoseSessionHasGoneFails(TestInfo info) throws Exception {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		createAgent(engine, owner, "late-orphan", "v/test/ops/toolllm");
		Agent agent = engine.getVenueState().users().user(did).agent("late-orphan");
		String gateName = "late-orphan-" + did;
		Job job;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				AgentWatch watch = AgentWatch.open(engine, did, "late-orphan")) {
			job = engine.jobs().invokeOperation("v/ops/agent/request", Maps.of(
				Fields.AGENT_ID, "late-orphan",
				Fields.INPUT, Maps.of("ask", "hold:" + gateName)), owner);
			watch.awaitRest(1);
			ACell task = agent.getTasks().get(job.getID());
			assertTrue(Task.isParked(task));
			agent.removeSession(Blob.fromHex(RT.ensureString(RT.getIn(task, Fields.SESSION_ID)).toString()));
			gate.release();
			TestEngine.awaitJob(job, Job::isFinished, 10_000, () -> "the stranded task did not fail");
		}
		assertEquals(AgentAdapter.TASK_OUTCOME_UNDELIVERED, job.getErrorMessage(),
			"a task whose call's outcome has nowhere to go fails rather than stay parked");
		assertEquals(0, agent.getPending().count());
	}

	@Test
	public void aTaskWaitsForItsLateCallAndRunsAgainOnItsOutcome(TestInfo info) throws Exception {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		createAgent(engine, owner, "late-task", "v/test/ops/toolllm");
		Agent agent = engine.getVenueState().users().user(did).agent("late-task");

		String gateName = "late-task-" + did;
		Job job;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				AgentWatch watch = AgentWatch.open(engine, did, "late-task")) {
			// toolllm calls hold, which outlives the limit; the model then
			// replies with text, a yield backed by the call still running.
			job = engine.jobs().invokeOperation("v/ops/agent/request", Maps.of(
				Fields.AGENT_ID, "late-task",
				Fields.INPUT, Maps.of("ask", "hold:" + gateName)), owner);
			watch.awaitRest(1);
			assertEquals(Agent.SLEEPING, agent.getStatus());
			assertTrue(Task.isParked(agent.getTasks().get(job.getID())),
				"a task waiting on a late call is parked, not failed");
			assertFalse(job.isFinished());
			gate.release();

			// The outcome arrives as the task's next input and runs it again.
			// toolllm replies with text once more, leaving nothing outstanding,
			// so that reply is the task's result.
			watch.awaitRest(2);
		}
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals(2, agent.getTimeline().count());
		assertNull(RT.getIn(agent.getTimeline().get(1), Fields.MESSAGES), "the outcome is not a session message");
		String shown = conversation(agent, sessionOf(job.getData()));
		assertTrue(shown.contains("late") && shown.contains("COMPLETE"),
			"the task's second cycle was shown the late outcome: " + shown);
	}

	private static Blob sessionOf(AMap<AString, ACell> taskJob) {
		return Blob.fromHex(RT.ensureString(taskJob.get(Fields.SESSION_ID)).toString());
	}

	/**
	 * Requests a task of a {@code step} agent, in session {@code sid} or a new
	 * one, and tracks {@code call} for it while its cycle holds at
	 * {@code cycle}, as a call that outlives its limit is tracked. The cycle
	 * then yields, and the task parks on the call.
	 */
	private Job parkOnACall(RequestContext owner, Agent agent, AgentWatch watch, int rest, Blob sid,
			TestAdapter.TestGate cycle, String cycleGate, Job call) throws Exception {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agent.getAgentId(),
			Fields.INPUT, "hold:" + cycleGate + " yield");
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid.toHexString()));
		Job task = engine.jobs().invokeOperation("v/ops/agent/request", input, owner);
		assertTrue(cycle.awaitEntered(10, TimeUnit.SECONDS), "the task's cycle did not start");
		Blob taskSession = sessionOf(RT.ensureMap(agent.getTasks().get(task.getID())));
		((AgentAdapter) engine.getAdapter("agent")).trackCall(owner.getUserDID(), agent.getAgentId(),
			taskSession, task.getID(), call, Maps.of(Fields.TOOL_CALL_ID, PARKED_CALL_ID, Fields.TOOL_NAME, PARKED_TOOL));
		cycle.release();
		watch.awaitRest(rest);
		assertTrue(Task.isParked(agent.getTasks().get(task.getID())), "the task parks on its call");
		return task;
	}

	@Test
	public void anOutcomeRunsTheTaskWaitingOnItNotAnotherInItsSession(TestInfo info) throws Exception {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "late-pair",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/step")), owner).awaitResult(5000);
		Agent agent = engine.getVenueState().users().user(did).agent("late-pair");
		String older = "late-older-" + did;
		String newer = "late-newer-" + did;
		try (TestAdapter.TestGate olderCycle = TestAdapter.createGate(older);
				TestAdapter.TestGate olderGate = TestAdapter.createGate(older + "-call");
				TestAdapter.TestGate newerCycle = TestAdapter.createGate(newer);
				TestAdapter.TestGate newerGate = TestAdapter.createGate(newer + "-call");
				AgentWatch watch = AgentWatch.open(engine, did, "late-pair")) {
			Job olderCall = engine.jobs().invokeOperation(HOLD,
				Maps.of("gate", older + "-call", Fields.RESULT, "older-outcome"), owner);
			Job newerCall = engine.jobs().invokeOperation(HOLD,
				Maps.of("gate", newer + "-call", Fields.RESULT, "newer-outcome"), owner);
			Job olderTask = parkOnACall(owner, agent, watch, 1, null, olderCycle, older, olderCall);
			Blob sid = sessionOf(RT.ensureMap(agent.getTasks().get(olderTask.getID())));
			Job newerTask = parkOnACall(owner, agent, watch, 2, sid, newerCycle, newer, newerCall);

			// The newer task's call ends first. Its outcome runs that task, though
			// the older one waits in the same session and would be picked first.
			newerGate.release();
			watch.awaitRest(3);
			assertEquals(Status.COMPLETE, newerTask.getStatus(),
				"the newer task ran on its outcome, and with nothing left to wait on its reply is its result");
			assertFalse(olderTask.isFinished());
			assertTrue(Task.isParked(agent.getTasks().get(olderTask.getID())),
				"the older task still waits on its own call");
			assertEquals(1, agent.getPending().count());
			assertNotNull(agent.getPending().get(olderCall.getID()));
			String shown = String.valueOf(RT.getIn(agent.getTimeline().get(2), Fields.RESULT));
			assertTrue(shown.contains("newer-outcome"), "the newer task's cycle was shown its outcome: " + shown);

			// Shown in the conversation as from whoever made the call: here the owner, by hand
			AVector<ACell> turns = GoalTreeContext.activeConversation(RT.ensureMap(
				RT.getIn(agent.getSession(sid), Fields.FRAMES, CVMLong.ZERO)));
			ACell outcomeTurn = null;
			for (long i = turns.count() - 1; i >= 0 && outcomeTurn == null; i--) {
				ACell turn = turns.get(i);
				if (Agent.ROLE_USER.equals(RT.getIn(turn, Agent.K_ROLE))
						&& String.valueOf(RT.getIn(turn, Agent.K_CONTENT)).contains("newer-outcome")) {
					outcomeTurn = turn;
				}
			}
			assertNotNull(outcomeTurn, "the outcome is a turn in the session's conversation");
			assertEquals(did, RT.getIn(outcomeTurn, Fields.CALLER));
			assertEquals(Agent.SOURCE_TOOL_OUTCOME, RT.getIn(outcomeTurn, Agent.K_SOURCE),
				"the venue marks it a tool call's outcome");
			assertEquals(Strings.create(PARKED_CALL_ID), RT.getIn(outcomeTurn, Agent.K_CONTENT, Fields.TOOL_CALL_ID),
				"it cites the call as the model made it");
			assertEquals(Strings.create(PARKED_TOOL), RT.getIn(outcomeTurn, Agent.K_CONTENT, Fields.TOOL_NAME));

			olderGate.release();
			watch.awaitRest(4);
			assertEquals(Status.COMPLETE, olderTask.getStatus());
			assertTrue(String.valueOf(RT.getIn(agent.getTimeline().get(3), Fields.RESULT)).contains("older-outcome"),
				"the older task's cycle was shown its own outcome");
			assertEquals(0, agent.getPending().count());
		}
	}
}
