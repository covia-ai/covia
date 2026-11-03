package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.AgentWatch;
import covia.venue.Config;
import covia.venue.CoviaApplication;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * A tool call that outlives its agent's {@code toolCallTimeoutMs} (#607): the
 * model is told the recorded job it runs as, and when it ends its outcome
 * reaches the asking session as a message, after a restart too.
 * {@code v/test/ops/hold} finishes only when the test releases its gate, so
 * the one real wait is the 1 s minimum limit, paid by the end-to-end cases.
 */
public class LateToolResultTest {

	private static final String HOLD = "v/test/ops/hold";
	private static final long LIMIT_MS = 1000;
	private static final Pattern JOB_NAMED = Pattern.compile("running as job ([0-9a-f]+)\\.");

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
		e.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config),
			owner).awaitResult(5000);
	}

	private static String conversation(AgentState agent, Blob sid) {
		return GoalTreeContext.activeConversation(RT.ensureMap(
			RT.getIn(agent.getSession(sid), Fields.FRAMES, CVMLong.ZERO))).toString();
	}

	@Test
	public void aCallPastItsLimitDeliversItsOutcomeToTheSession(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		createAgent(engine, owner, "late-step", "v/test/ops/llm");
		User user = engine.getVenueState().users().get(did);
		AgentState agent = user.agent("late-step");
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
		assertEquals(0, agent.getPending().count(), "a delivered outcome leaves the pending index");
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
			engine.getVenueState().users().get(did).agent(agentId).ensureSession(sid, did);
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

	@Test
	public void aRestartDeliversALateCallsFailureToTheTaskWaitingOnIt() throws Exception {
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
		Root<Index<Keyword, ACell>> cursor = Cursors.root(Index.<Keyword, ACell>none());
		AKeyPair keyPair = AKeyPair.generate();
		AString did = Strings.create("did:key:zLateCallRestart");
		RequestContext owner = RequestContext.of(did);

		Engine first = new Engine(config, CoviaApplication.connect(new LatticeHost<>(cursor, null)), keyPair).start();
		Engine.addDemoAssets(first);
		createAgent(first, owner, "late-restart", "v/test/ops/toolllm");
		String gateName = "late-restart-" + System.identityHashCode(first);
		Job job;
		Blob mirrorId;
		Root<Index<Keyword, ACell>> crashed;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				AgentWatch watch = AgentWatch.open(first, did, "late-restart")) {
			job = first.jobs().invokeOperation("v/ops/agent/request", Maps.of(
				Fields.AGENT_ID, "late-restart",
				Fields.INPUT, Maps.of("ask", "hold:" + gateName)), owner);
			watch.awaitRest(1);
			AgentState agent = first.getVenueState().users().get(did).agent("late-restart");
			assertTrue(AgentState.isParked(agent.getTasks().get(job.getID())));
			assertEquals(1, agent.getPending().count(), "the late call waits in the pending index");
			mirrorId = agent.getPending().entryAt(0).getKey();
			first.syncState();
			// The store as a crash would leave it, with the call still running
			crashed = Cursors.root(cursor.get());
			first.close();
		}

		Engine second = new Engine(config, CoviaApplication.connect(new LatticeHost<>(crashed, null)), keyPair).start();
		try {
			Engine.addDemoAssets(second);
			AgentState agent = second.getVenueState().users().get(did).agent("late-restart");
			try (AgentWatch watch = AgentWatch.open(second, did, "late-restart")) {
				second.jobs().recoverJobs();
				((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();
				watch.awaitRest(1);
			}
			assertEquals(0, agent.getPending().count(), "the outcome left the pending index");
			AMap<AString, ACell> task = second.jobs().getJobData(job.getID(), owner);
			assertEquals(AgentAdapter.TASK_YIELDED_IDLE, String.valueOf(task.get(Fields.ERROR)),
				"the task ran again on the outcome rather than failing as lost");
			String shown = String.valueOf(RT.getIn(agent.getTimeline().get(1), Fields.MESSAGES));
			assertTrue(shown.contains(mirrorId.toHexString()) && shown.contains(AAdapter.RESTARTED_DURING_EXECUTION),
				"the task's cycle was shown the call's failure: " + shown);
		} finally {
			second.close();
		}
	}

	@Test
	public void aTaskWaitsForItsLateCallAndRunsAgainOnItsOutcome(TestInfo info) throws Exception {
		AString did = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(did);
		createAgent(engine, owner, "late-task", "v/test/ops/toolllm");
		AgentState agent = engine.getVenueState().users().get(did).agent("late-task");

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
			assertEquals(AgentState.SLEEPING, agent.getStatus());
			assertTrue(AgentState.isParked(agent.getTasks().get(job.getID())),
				"a task waiting on a late call is parked, not failed");
			assertFalse(job.isFinished());
			gate.release();

			// The outcome arrives in the task's session and runs it again. toolllm
			// replies with text once more, leaving nothing outstanding this time.
			watch.awaitRest(2);
		}
		assertEquals(Status.FAILED, job.getStatus());
		assertEquals(AgentAdapter.TASK_YIELDED_IDLE, job.getErrorMessage());
		assertEquals(2, agent.getTimeline().count());
		ACell shown = RT.getIn(agent.getTimeline().get(1), Fields.MESSAGES);
		assertNotNull(shown, "the second cycle was shown the late outcome");
		assertTrue(shown.toString().contains("late") && shown.toString().contains("COMPLETE"),
			"the late outcome reached the task's session: " + shown);
	}
}
