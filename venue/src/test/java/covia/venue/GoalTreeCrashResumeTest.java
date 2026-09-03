package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.test.DurabilityTest;

/**
 * Restart boundary for agent execution. Jobs and external interaction records
 * are durable, and a request task survives its venue process: it is restored
 * live and re-attempted fresh at the next boot (ae2c9670). What does not carry
 * across is the interrupted model loop itself — the boot wake starts the
 * request over rather than replaying a transition that was aborted mid-flight,
 * and a merely-completed message never becomes stale boot work.
 */
@DurabilityTest
public class GoalTreeCrashResumeTest {
	private EtchStore activeStore;
	private NodeServer<Index<Keyword, ACell>> activeNode;
	private Engine activeEngine;

	@AfterEach
	void teardown() throws Exception {
		try {
			closeActivePhase();
		} finally {
			if (activeStore != null) activeStore.close();
			activeStore = null;
		}
	}

	private void closeActivePhase() throws Exception {
		try {
			if (activeEngine != null) activeEngine.close();
		} finally {
			activeEngine = null;
			if (activeNode != null) activeNode.close();
			activeNode = null;
		}
	}

	private static final AString ALICE = Strings.create("did:key:z6MkCrashBoundaryAlice");

	private static void await(BooleanSupplier condition, long timeoutMs, String description) {
		TestEngine.awaitCondition(condition, timeoutMs,
			() -> "timeout waiting for: " + description);
	}

	private static AMap<AString, ACell> config(AKeyPair keyPair) {
		String did = "did:key:" + Multikey.encodePublicKey(keyPair.getAccountKey());
		return Maps.of(Config.DID, did, Config.USERS, Maps.of(Config.AUTO_CREATE, true));
	}

	private Engine start(EtchStore store, AKeyPair keyPair,
			AMap<AString, ACell> config)
			throws Exception {
		activeStore = store;
		NodeServer<Index<Keyword, ACell>> node =
			new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
		activeNode = node;
		node.launch();
		Engine engine = new Engine(config, node.getCursor(), keyPair).start();
		activeEngine = engine;
		Engine.addDemoAssets(engine);
		return engine;
	}

	private static void createGoalAgent(Engine engine, String agentId) {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, Strings.create("v/ops/goaltree/chat"),
					Strings.create("llmOperation"), Strings.create("v/test/ops/nevertoolllm"),
					Strings.create("systemPrompt"), Strings.create("Test agent."),
					Strings.create("toolCallTimeoutMs"), CVMLong.create(60_000))),
			RequestContext.of(ALICE)).awaitResult(5000);
	}

	private static AgentState agent(Engine engine, String agentId) {
		User user = engine.getVenueState().users().get(ALICE);
		return (user != null) ? user.agent(agentId) : null;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> rootConversation(Engine engine, String agentId, Blob sid) {
		AMap<AString, ACell> session = agent(engine, agentId).getSession(sid);
		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		return RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
	}

	private static boolean parkedInTool(Engine engine, String agentId, Blob sid) {
		AVector<ACell> conversation = rootConversation(engine, agentId, sid);
		return conversation != null && String.valueOf(conversation).contains("toolCalls");
	}

	@Test
	public void testInterruptedRequestSurvivesRestartAndReattempts() throws Exception {
		// A request is a task, not an ephemeral call (ae2c9670, JOBS.md
		// § Recovery): its Job is durable and its intake stays on the agent,
		// so a restart carries it across — restored live (never failed), kept
		// as the agent's task, and picked up by the boot wake as a FRESH
		// attempt. The safety line the old behaviour drew still holds: the
		// wake does not replay the model loop that was interrupted mid-flight
		// (no "gave up" continuation) — it starts the request over. Whether
		// that fresh attempt then re-parks or completes is timing, not
		// contract (AgentRestartTest covers the deterministic completing
		// case); what matters here is that the restart never fails it.
		EtchStore store = EtchStore.createTemp();
		AKeyPair keyPair = AKeyPair.generate();
		AMap<AString, ACell> config = config(keyPair);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee31");
		Blob requestId;

		Engine first = start(store, keyPair, config);
		createGoalAgent(first, "request-agent");
		agent(first, "request-agent").ensureSession(sid, ALICE);
		Job request = first.jobs().invokeOperation("v/ops/agent/request",
			Maps.of(Fields.AGENT_ID, "request-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.INPUT, Strings.create("use the slow tool")),
			RequestContext.of(ALICE));
		requestId = request.getID();
		await(() -> parkedInTool(first, "request-agent", sid), 10_000, "request parked in tool");
		assertEquals(Status.STARTED, request.getStatus());
		assertEquals(AgentState.RUNNING, agent(first, "request-agent").getStatus());
		assertNotNull(agent(first, "request-agent").getSessionCycleEpoch(sid));
		first.flush();
		closeActivePhase();

		Engine second = start(store, keyPair, config);
		second.jobs().recoverJobs();

		// The request Job is restored live, never failed: recovery keeps it as
		// the agent's durable task rather than taking the STARTED-fails default.
		AMap<AString, ACell> restored = second.jobs().getJobData(requestId, RequestContext.of(ALICE));
		assertEquals(Status.STARTED, restored.get(Fields.STATUS),
			"an interrupted request is restored live, not failed");
		AgentState recovered = agent(second, "request-agent");
		assertNotNull(recovered.getTasks().get(requestId),
			"the surviving request is still the agent's task after recovery");
		assertFalse(String.valueOf(rootConversation(second, "request-agent", sid)).contains("gave up"),
			"startup must not replay the interrupted model loop");

		// The surviving request is boot work: the wake picks it up and starts a
		// fresh attempt (it does not stay dormant like a completed message).
		AgentAdapter adapter = (AgentAdapter) second.getAdapter("agent");
		assertEquals(1, adapter.wakeAgentsWithWork(),
			"the surviving request is fresh boot work for its agent");
		// The fresh attempt runs — live or completed depending on timing — but
		// the restart never turns the surviving request into a failure.
		AString afterWake = RT.ensureString(second.jobs().getJobData(requestId, RequestContext.of(ALICE)).get(Fields.STATUS));
		assertTrue(Status.STARTED.equals(afterWake) || Status.COMPLETE.equals(afterWake),
			"the re-attempted request is live or completed, never failed by the restart: " + afterWake);
		closeActivePhase();
	}

	@Test
	public void testCompletedMessageDoesNotMakeStaleCycleBootWork() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair keyPair = AKeyPair.generate();
		AMap<AString, ACell> config = config(keyPair);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee32");

		Engine first = start(store, keyPair, config);
		createGoalAgent(first, "message-agent");
		agent(first, "message-agent").ensureSession(sid, ALICE);
		Job delivery = first.jobs().invokeOperation("v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "message-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE, Strings.create("use the slow tool")),
			RequestContext.of(ALICE));
		delivery.awaitResult(5000);
		await(() -> parkedInTool(first, "message-agent", sid), 10_000, "message parked in tool");
		first.flush();
		closeActivePhase();

		Engine second = start(store, keyPair, config);
		second.jobs().recoverJobs();
		AgentAdapter adapter = (AgentAdapter) second.getAdapter("agent");
		assertEquals(0, adapter.wakeAgentsWithWork(),
			"inCycle alone must not wake an agent after restart");
		AgentState recovered = agent(second, "message-agent");
		assertEquals(AgentState.SLEEPING, recovered.getStatus());
		assertNull(recovered.getSessionCycleEpoch(sid));
		assertFalse(String.valueOf(rootConversation(second, "message-agent", sid)).contains("gave up"));
		closeActivePhase();
	}

	@Test
	public void testFreshAttemptRepairsAnAbortedToolCall() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair keyPair = AKeyPair.generate();
		AMap<AString, ACell> config = config(keyPair);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee33");

		Engine engine = start(store, keyPair, config);
		try {
			createGoalAgent(engine, "repair-agent");
			agent(engine, "repair-agent").ensureSession(sid, ALICE);
			engine.jobs().invokeOperation("v/ops/agent/message",
				Maps.of(Fields.AGENT_ID, "repair-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.MESSAGE, Strings.create("use the slow tool")),
				RequestContext.of(ALICE)).awaitResult(5000);
			await(() -> parkedInTool(engine, "repair-agent", sid),
				10_000, "message parked in tool");

			engine.jobs().invokeOperation("v/ops/agent/suspend",
				Maps.of(Fields.AGENT_ID, "repair-agent"),
				RequestContext.of(ALICE)).awaitResult(5000);
			await(() -> agent(engine, "repair-agent").getSessionCycleEpoch(sid) == null,
				10_000, "cycle fence cleared after suspension");
			engine.jobs().invokeOperation("v/ops/agent/resume",
				Maps.of(Fields.AGENT_ID, "repair-agent"),
				RequestContext.of(ALICE)).awaitResult(5000);

			engine.jobs().invokeOperation("v/ops/agent/message",
				Maps.of(Fields.AGENT_ID, "repair-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.MESSAGE, Strings.create("start a fresh attempt")),
				RequestContext.of(ALICE)).awaitResult(5000);
			await(() -> String.valueOf(rootConversation(engine, "repair-agent", sid))
				.contains("did not return"), 10_000,
				"fresh attempt settled the dangling call explicitly");
		} finally {
			closeActivePhase();
		}
	}
}
