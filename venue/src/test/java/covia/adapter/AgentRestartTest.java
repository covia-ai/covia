package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.AgentWatch;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * An agent request is a task, not an ephemeral call: the request Job is the
 * task's system of record and the agent's intake is durable, so a venue
 * restart must carry it across — kept STARTED at shutdown, restored at boot,
 * completed by the boot wake (AGENT_LOOP.md § 6.1, JOBS.md § Recovery).
 * Two in-process engines on one lattice; no venue is launched.
 */
public class AgentRestartTest {
	private static final AString ALICE = Strings.create("did:key:zAliceAgentRestart");

	private static AMap<AString, ACell> config() {
		return Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0L));
	}

	private static AString status(Engine e, String agentId) {
		AMap<AString, ACell> info = ((AgentAdapter) e.getAdapter("agent"))
			.agentInfo(RequestContext.of(ALICE), Strings.create(agentId));
		return RT.ensureString(info.get(Fields.STATUS));
	}

	@Test
	public void requestSurvivesRestartAndCompletesAfterBoot() throws Exception {
		Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
		AKeyPair keyPair = AKeyPair.generate();
		RequestContext alice = RequestContext.of(ALICE);
		String agentId = "survivor";
		String gateName = "agent-restart-" + System.nanoTime();
		Job request;

		// Phase 1: hold the transition at a deterministic gate. Waiting only for
		// RUNNING races the executor entering a fixed sleep under full-suite load.
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Engine first = new Engine(config(), covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)), keyPair).start();
			try {
				Engine.addDemoAssets(first);
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.CONFIG, Maps.of(
						Fields.OPERATION, "v/test/ops/taskcomplete",
						"testGate", gateName)), alice).awaitResult(5000);
				request = first.jobs().invokeOperation("v/ops/agent/request",
					Maps.of(Fields.AGENT_ID, agentId,
						Fields.INPUT, Maps.of("ask", "outlive the venue")), alice);
				assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS),
					"the task transition never reached its test gate");
				// The loop holds its running slot before its transition starts.
				assertEquals(AgentState.RUNNING, status(first, agentId));
				assertFalse(request.isFinished(),
					"a queued task Job is PENDING/STARTED while the agent holds it");
				first.syncState();
			} finally {
				first.close();
			}
			assertFalse(request.isFinished(), "a queued task is kept, not cancelled, at shutdown");
		}

		// Phase 2: boot on the same lattice; recovery keeps the task, the wake completes it.
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			// The persisted config still names the gate; let the recovered cycle
			// cross it immediately rather than adding timing to phase 2.
			gate.release();
			Engine second = new Engine(config(), covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)), keyPair).start();
			try {
				Engine.addDemoAssets(second);
				second.jobs().recoverJobs();
				AMap<AString, ACell> restored = second.jobs().getJobData(request.getID(), alice);
				assertFalse(Job.isFinished(restored),
					"the task Job is restored live, not failed: " + restored.get(Fields.STATUS));

				// Let the transition complete promptly this time, then run the boot wake.
				second.jobs().invokeOperation("v/ops/agent/update", Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.CONFIG, Maps.of(
						Fields.OPERATION, "v/test/ops/taskcomplete", Fields.DELAY, 0L)),
					alice).awaitResult(5000);
				((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();

				Job live = second.jobs().getJob(request.getID(), alice);
				assertNotNull(live, "the restored Job is live in the second engine");
				live.awaitResult(10_000);
				assertEquals(Status.COMPLETE, live.getStatus(),
					"the boot wake completed the surviving task");
			} finally {
				second.close();
			}
		}
	}

	/**
	 * A parked task survives a restart only while what it waits on does. A
	 * wake time is durable, so that task stays parked. A call past its time
	 * limit ran only in memory, so a task left with nothing durable to wait
	 * on fails at boot rather than waiting forever.
	 */
	@Test
	public void parkedTaskSurvivesRestartOnlyWithSomethingDurableToWaitOn() throws Exception {
		Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
		AKeyPair keyPair = AKeyPair.generate();
		RequestContext alice = RequestContext.of(ALICE);
		AMap<AString, ACell> later = Maps.of(Fields.WAKE_TIME,
			convex.core.data.prim.CVMLong.create(System.currentTimeMillis() + 3_600_000));
		Job waking;
		Job orphaned;

		// Phase 1: both tasks yield with a wake time and park. One then loses
		// its wake time, standing in for a task whose only wait was in memory.
		Engine first = new Engine(config(), covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)), keyPair).start();
		try {
			Engine.addDemoAssets(first);
			for (String agentId : new String[] {"parked-waking", "parked-orphan"}) {
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/wakeresponse"),
					AgentState.KEY_STATE, later), alice).awaitResult(5000);
			}
			try (AgentWatch wakingWatch = AgentWatch.open(first, ALICE, "parked-waking");
					AgentWatch orphanWatch = AgentWatch.open(first, ALICE, "parked-orphan")) {
				waking = first.jobs().invokeOperation("v/ops/agent/request",
					Maps.of(Fields.AGENT_ID, "parked-waking", Fields.INPUT, Maps.of("ask", "wait")), alice);
				orphaned = first.jobs().invokeOperation("v/ops/agent/request",
					Maps.of(Fields.AGENT_ID, "parked-orphan", Fields.INPUT, Maps.of("ask", "wait")), alice);
				wakingWatch.awaitRest(1);
				orphanWatch.awaitRest(1);
			}
			AgentState orphan = first.getVenueState().users().get(ALICE).agent("parked-orphan");
			assertTrue(AgentState.isParked(orphan.getTasks().get(orphaned.getID())));
			orphan.setThreadWakeTime(AgentState.ThreadKind.TASK, orphaned.getID(), 0);
			first.syncState();
		} finally {
			first.close();
		}

		// Phase 2: boot on the same lattice and run the boot reconciliation.
		Engine second = new Engine(config(), covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)), keyPair).start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();

			Job lost = second.jobs().getJob(orphaned.getID(), alice);
			assertNotNull(lost);
			assertEquals(Status.FAILED, lost.getStatus(), "a task with nothing durable to wait on fails at boot");
			assertEquals(AgentAdapter.TASK_WAIT_LOST, lost.getErrorMessage());

			Job kept = second.jobs().getJob(waking.getID(), alice);
			assertNotNull(kept);
			assertFalse(kept.isFinished(), "a task waiting on its wake time stays queued");
			assertTrue(AgentState.isParked(second.getVenueState().users().get(ALICE)
				.agent("parked-waking").getTasks().get(waking.getID())));

			// A recovered request still leaves the queue when its caller cancels it.
			kept.cancel();
			assertNull(second.getVenueState().users().get(ALICE).agent("parked-waking")
				.getTasks().get(waking.getID()), "a cancelled request is not left for the agent to run");
		} finally {
			second.close();
		}
	}
}
