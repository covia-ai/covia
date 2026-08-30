package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import convex.lattice.cursor.RootLatticeCursor;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.venue.AgentState;
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

	private static void awaitAgentStatus(Engine e, String agentId, AString expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!expected.equals(status(e, agentId)) && System.nanoTime() < deadline) Thread.sleep(10);
		assertEquals(expected, status(e, agentId));
	}

	@Test
	public void requestSurvivesRestartAndCompletesAfterBoot() throws Exception {
		RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(Covia.ROOT);
		AKeyPair keyPair = AKeyPair.generate();
		RequestContext alice = RequestContext.of(ALICE);
		String agentId = "survivor";

		// Phase 1: a transition held open for longer than the venue lives.
		Engine first = new Engine(config(), cursor, keyPair).start();
		Engine.addDemoAssets(first);
		first.jobs().invokeOperation("v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/test/ops/taskcomplete",
				Fields.DELAY, 5000L)), alice).awaitResult(5000);
		Job request = first.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, Maps.of("ask", "outlive the venue")), alice);
		awaitAgentStatus(first, agentId, AgentState.RUNNING); // the delayed transition is in flight
		assertFalse(request.isFinished(), "a queued task Job is PENDING/STARTED while the agent holds it");
		first.syncState();
		first.close();
		assertFalse(request.isFinished(), "a queued task is kept, not cancelled, at shutdown");

		// Phase 2: boot on the same lattice; recovery keeps the task, the wake completes it.
		Engine second = new Engine(config(), cursor, keyPair).start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			AMap<AString, ACell> restored = second.jobs().getJobData(request.getID(), alice);
			assertFalse(Job.isFinished(restored), "the task Job is restored live, not failed: " + restored.get(Fields.STATUS));

			// Let the transition complete promptly this time, then run the boot wake.
			second.jobs().invokeOperation("v/ops/agent/update", Maps.of(
				Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete", Fields.DELAY, 0L)),
				alice).awaitResult(5000);
			((AgentAdapter) second.getAdapter("agent")).wakeAgentsWithWork();

			Job live = second.jobs().getJob(request.getID(), alice);
			assertNotNull(live, "the restored Job is live in the second engine");
			live.awaitResult(10_000);
			assertEquals(Status.COMPLETE, live.getStatus(), "the boot wake completed the surviving task");
		} finally {
			second.close();
		}
	}
}
