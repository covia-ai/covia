package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.Task;
import covia.venue.AgentWatch;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

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
		TestEngine.Restartable venue = TestEngine.restartable(config());
		RequestContext alice = RequestContext.of(ALICE);
		String agentId = "survivor";
		String gateName = "agent-restart-" + System.nanoTime();
		Job request;

		// Phase 1: hold the transition at a deterministic gate. Waiting only for
		// RUNNING races the executor entering a fixed sleep under full-suite load.
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Engine first = venue.start();
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
				assertEquals(Agent.RUNNING, status(first, agentId));
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
			Engine second = venue.start();
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
	 * A task its agent resolved, in a cycle a restart stopped before its merge,
	 * is handed back at boot: its request completes, with its output written
	 * at its {@code outputPath}, as the merge would have done.
	 */
	@Test
	public void resolvedTaskIsHandedBackAfterRestart() throws Exception {
		TestEngine.Restartable venue = TestEngine.restartable(config());
		RequestContext alice = RequestContext.of(ALICE);
		String agentId = "resolver";
		String gateName = "agent-handback-" + System.nanoTime();
		AString path = Strings.create("w/handback/after-restart");
		Job request;

		// Phase 1: the cycle completes its task, then holds before its merge.
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Engine first = venue.start();
			try {
				Engine.addDemoAssets(first);
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/step")), alice).awaitResult(5000);
				request = first.jobs().invokeOperation("v/ops/agent/request",
					Maps.of(Fields.AGENT_ID, agentId,
						Fields.INPUT, Strings.create("complete hold:" + gateName),
						Fields.OUTPUT_PATH, path), alice);
				assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the cycle never reached its test gate");
				Agent worker = first.getVenueState().users().user(ALICE).agent(agentId);
				assertNotNull(worker.getResolved().get(request.getID()), "the resolution is stored before the merge");
				assertNull(worker.getTasks().get(request.getID()));
				first.syncState();
			} finally {
				first.close();
			}
			assertFalse(request.isFinished(), "a resolved task's request is kept, not cancelled, at shutdown");
		}

		// Phase 2: boot on the same lattice; reconciliation hands the task back.
		Engine second = venue.start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			assertFalse(Job.isFinished(second.jobs().getJobData(request.getID(), alice)),
				"the request is restored live, not failed as interrupted");
			((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();

			Job live = second.jobs().getJob(request.getID(), alice);
			ACell receipt = live.awaitResult(10_000);
			assertEquals(path, RT.getIn(receipt, Fields.OUTPUT_PATH));
			ACell written = RT.getIn(second.jobs().invokeOperation("v/ops/covia/read",
				Maps.of(Fields.PATH, path), alice).awaitResult(5000), Fields.VALUE);
			assertEquals(Strings.create("complete hold:" + gateName), RT.getIn(written, Fields.INPUT),
				"the output the agent resolved its task with is written");
			assertTrue(second.getVenueState().users().user(ALICE).agent(agentId).getResolved().isEmpty());
		} finally {
			second.close();
		}
	}

	/**
	 * A parked task survives a restart only while what it waits on does. A
	 * wake time is durable, so that task stays parked. A task left with
	 * nothing durable to wait on fails at boot rather than waiting forever.
	 */
	@Test
	public void parkedTaskSurvivesRestartOnlyWithSomethingDurableToWaitOn() throws Exception {
		TestEngine.Restartable venue = TestEngine.restartable(config());
		RequestContext alice = RequestContext.of(ALICE);
		AMap<AString, ACell> later = Maps.of(Fields.WAKE_TIME,
			convex.core.data.prim.CVMLong.create(System.currentTimeMillis() + 3_600_000));
		Job waking;
		Job orphaned;

		// Phase 1: both tasks yield with a wake time and park. One then loses
		// its wake time, standing in for a task whose only wait was in memory.
		Engine first = venue.start();
		try {
			Engine.addDemoAssets(first);
			for (String agentId : new String[] {"parked-waking", "parked-orphan"}) {
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/wakeresponse"),
					Agent.KEY_STATE, later), alice).awaitResult(5000);
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
			Agent orphan = first.getVenueState().users().user(ALICE).agent("parked-orphan");
			assertTrue(Task.isParked(orphan.getTasks().get(orphaned.getID())));
			orphan.setThreadWakeTime(Agent.ThreadKind.TASK, orphaned.getID(), 0);
			first.syncState();
		} finally {
			first.close();
		}

		// Phase 2: boot on the same lattice and run the boot reconciliation.
		Engine second = venue.start();
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
			assertTrue(Task.isParked(second.getVenueState().users().user(ALICE)
				.agent("parked-waking").getTasks().get(waking.getID())));

			// A recovered request still leaves the queue when its caller cancels it.
			kept.cancel();
			assertNull(second.getVenueState().users().user(ALICE).agent("parked-waking")
				.getTasks().get(waking.getID()), "a cancelled request is not left for the agent to run");
		} finally {
			second.close();
		}
	}

	/** {@link #config()} with the public principal's access on or off, unrestricted while on. */
	private static AMap<AString, ACell> withPublicAccess(boolean on) {
		return withPublicAccess(on, Config.UNRESTRICTED);
	}

	/** {@link #config()} with the public principal's access on or off, and its {@code caps}. */
	private static AMap<AString, ACell> withPublicAccess(boolean on, ACell caps) {
		return config().assoc(Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, on, Config.CAPS, caps)));
	}

	/**
	 * An agent the venue's public principal owns acts with that principal's
	 * authority, as any owner's agent acts with its owner's: its harness and
	 * its tool calls alike, even unrestricted, reach what the public caps
	 * cover, and nothing while public access is off.
	 */
	@Test
	public void aPublicAgentActsWithThePublicPrincipalsAuthority() throws Exception {
		ACell caps = Vectors.of(
			Capability.create(Strings.create("g/"), Abilities.AGENT_CREATE),
			Maps.of(Capability.CAN, Abilities.INVOKE),
			Capability.create(Strings.create("w/open/"), Capability.CRUD_READ));
		TestEngine.Restartable venue = TestEngine.restartable(withPublicAccess(true, caps));
		AString lobby = Strings.create("lobby");
		Engine first = venue.mount(withPublicAccess(true, caps)).launch();
		AString owner = first.publicPrincipal();
		AString agent = Principals.agentDID(owner, lobby);
		try {
			Engine.addDemoAssets(first);
			first.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, lobby,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never", Config.CAPS, Config.UNRESTRICTED)),
				first.context(owner)).awaitResult(5000);
			for (RequestContext acting : new RequestContext[] {first.harnessContext(owner, lobby), first.context(owner, agent)}) {
				assertTrue(first.authorityCovers(acting, Strings.create("w/open/doc"), Capability.CRUD_READ));
				assertFalse(first.authorityCovers(acting, Strings.create("w/closed/doc"), Capability.CRUD_READ),
					"no more than the public caps give");
			}
		} finally {
			first.close();
		}

		Engine second = venue.mount(withPublicAccess(false, caps)).launch();
		try {
			AuthException harness = assertThrows(AuthException.class, () -> second.harnessContext(owner, lobby));
			assertEquals(Engine.PUBLIC_ACCESS_OFF, harness.getMessage());
			AuthException tools = assertThrows(AuthException.class, () -> second.context(owner, agent));
			assertEquals(Engine.PUBLIC_ACCESS_OFF, tools.getMessage());
		} finally {
			second.close();
		}
	}

	/**
	 * Each agent is recovered at boot on its own. One that cannot run then,
	 * here because its record names a controller that cannot act, is left
	 * asleep with its work queued, and the venue still starts and wakes the
	 * others. Its info still reads, saying why its tools could not be checked.
	 */
	@Test
	public void anAgentThatCannotRunDoesNotStopTheVenueStarting() throws Exception {
		TestEngine.Restartable venue = TestEngine.restartable(withPublicAccess(true));
		RequestContext alice = RequestContext.of(ALICE);
		String gateName = "agent-boot-" + System.nanoTime();
		Job request, stranded;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Engine first = venue.mount(withPublicAccess(true)).launch();
			try {
				Engine.addDemoAssets(first);
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "worker",
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete",
						"testGate", gateName, Fields.DELAY, 0L)), alice).awaitResult(5000);
				first.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "stray",
					Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/never",
						"tools", Vectors.of("v/ops/json/merge"))), alice).awaitResult(5000);
				// A record no request could write: the public principal controls Alice's agent
				Agent stray = first.getVenueState().users().user(ALICE).agent("stray");
				stray.putRecord(stray.getRecord().assoc(Agent.KEY_CONTROLLER, first.publicPrincipal()));
				stranded = first.jobs().invokeOperation("v/ops/agent/request",
					Maps.of(Fields.AGENT_ID, "stray", Fields.INPUT, Maps.of("ask", "wait")), alice);
				request = first.jobs().invokeOperation("v/ops/agent/request",
					Maps.of(Fields.AGENT_ID, "worker", Fields.INPUT, Maps.of("ask", "outlive the venue")), alice);
				assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the task transition never reached its test gate");
				first.syncState();
			} finally {
				first.close();
			}
		}

		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			gate.release();
			// With public access off, the stray agent's controller cannot act
			Engine second = venue.mount(withPublicAccess(false)).launch();
			try {
				Job live = second.jobs().getJob(request.getID(), alice);
				live.awaitResult(10_000);
				assertEquals(Status.COMPLETE, live.getStatus(), "the venue started and woke the agent that can run");
				assertFalse(second.jobs().getJob(stranded.getID(), alice).isFinished(), "its work stays queued");

				// Its info still reads, saying its tools could not be checked
				AMap<AString, ACell> info = ((AgentAdapter) second.getAdapter("agent"))
					.agentInfo(alice, Strings.create("stray"));
				assertEquals(Agent.SLEEPING, info.get(Fields.STATUS));
				assertEquals(second.publicPrincipal(), info.get(Fields.CONTROLLER));
				AVector<ACell> unavailable = RT.ensureVector(info.get(Fields.UNAVAILABLE_TOOLS));
				assertEquals(1, unavailable.count(), info.toString());
				assertEquals(Strings.create("v/ops/json/merge"), RT.getIn(unavailable.get(0), Fields.OPERATION));
				assertEquals(Strings.create(AgentAdapter.TOOLS_UNCHECKED.formatted(Engine.PUBLIC_ACCESS_OFF)),
					RT.getIn(unavailable.get(0), Fields.REASON));
			} finally {
				second.close();
			}
		}
	}
}
