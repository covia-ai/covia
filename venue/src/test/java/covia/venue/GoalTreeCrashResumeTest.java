package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

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
import convex.core.data.prim.CVMLong;
import convex.core.crypto.util.Multikey;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import convex.node.NodeConfig;
import convex.node.NodeServer;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;

/**
 * Crash resume for lattice-resident goal-tree frames (Stage C): a venue that
 * dies mid-cycle restarts, detects the stale {@code inCycle} claim, repairs
 * dangling toolCalls with synthetic results, settles child frames
 * deepest-first, and completes the interrupted work. Two-engine pattern over
 * one Etch store (see {@link VenueRestartTest}); the pre-crash transition is
 * parked deterministically in a {@code v/test/ops/never} tool call with a
 * long timeout, so the "crash" (engine close) always lands mid-cycle.
 */
public class GoalTreeCrashResumeTest {

	private static final AString ALICE = Strings.create("did:key:z6MkCrashResumeAlice");

	private static void await(BooleanSupplier cond, long ms, String desc) {
		long deadline = System.currentTimeMillis() + ms;
		while (!cond.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) fail("timeout waiting for: " + desc);
			try { Thread.sleep(20); } catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("interrupted");
			}
		}
	}

	private static void createGoalAgent(Engine engine, String agentId, String llmOp) {
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, Strings.create("v/ops/goaltree/chat"),
					Strings.create("llmOperation"), Strings.create(llmOp),
					Strings.create("systemPrompt"), Strings.create("Test agent."),
					// Long tool timeout: the pre-crash cycle stays parked in the
					// never-tool for the whole test — the crash always lands
					// mid-cycle, deterministically.
					Strings.create("toolCallTimeoutMs"), CVMLong.create(60_000))),
			RequestContext.of(ALICE)).awaitResult(5000);
	}

	private static AgentState agent(Engine engine, String agentId) {
		User user = engine.getVenueState().users().get(ALICE);
		return (user != null) ? user.agent(agentId) : null;
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> frames(Engine engine, String agentId, Blob sid) {
		AMap<AString, ACell> session = agent(engine, agentId).getSession(sid);
		if (session == null) return null;
		ACell fv = session.get(AgentState.KEY_FRAMES);
		return (fv instanceof AVector) ? (AVector<ACell>) fv : null;
	}

	private static AVector<ACell> rootConversation(Engine engine, String agentId, Blob sid) {
		AVector<ACell> fs = frames(engine, agentId, sid);
		return RT.ensureVector(RT.getIn(fs.get(0), "conversation"));
	}

	private static long countTurnsContaining(AVector<ACell> conv, String needle) {
		long n = 0;
		for (long i = 0; i < conv.count(); i++) {
			if (String.valueOf(conv.get(i)).contains(needle)) n++;
		}
		return n;
	}

	/**
	 * Message-driven interruption, recovered by the boot scan: the intake job
	 * completed at delivery, so recoverJobs has nothing to re-fire —
	 * {@code wakeInterruptedCycles} is the only wake path. The resumed cycle
	 * repairs the dangling toolCall and finishes.
	 */
	@Test
	public void testCrashResumeViaBootScan() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kp = AKeyPair.generate();
		String did = "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
		AMap<AString, ACell> config = Maps.of(Config.DID, did);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee01");

		// ===== Stage 1: run until parked mid-cycle, then "crash" =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			createGoalAgent(engine, "boot-agent", "v/test/ops/nevertoolllm");
			agent(engine, "boot-agent").ensureSession(sid, ALICE);

			engine.jobs().invokeOperation(
				"v/ops/agent/message",
				Maps.of(Fields.AGENT_ID, "boot-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.MESSAGE, Strings.create("please use the slow tool")),
				RequestContext.of(ALICE)).awaitResult(5000);

			// Parked in the never-tool: the assistant turn with its dangling
			// toolCall is live on the lattice and the cycle claim is set.
			await(() -> {
				AVector<ACell> conv = rootConversation(engine, "boot-agent", sid);
				return conv != null && countTurnsContaining(conv, "toolCalls") > 0;
			}, 10_000, "cycle parked in the never-tool");
			assertNotNull(agent(engine, "boot-agent").getSessionCycleEpoch(sid));

			engine.flush();      // make the mid-cycle state durable
			engine.close();      // "crash": sweep stops, zombie writes never persist
			ns.close();
		}

		// ===== Stage 2: restart, boot scan wakes, cycle resumes =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			// Mid-cycle state survived the crash
			assertNotNull(agent(engine, "boot-agent").getSessionCycleEpoch(sid),
				"stale inCycle claim must survive the restart");

			engine.jobs().recoverJobs();   // nothing to re-fire (message completed at delivery)
			AgentAdapter aa = (AgentAdapter) engine.getAdapter("agent");
			assertEquals(1, aa.wakeInterruptedCycles(),
				"the boot scan must find and wake the interrupted agent");

			AgentState ag = agent(engine, "boot-agent");
			await(() -> AgentState.SLEEPING.equals(ag.getStatus())
					&& ag.getSessionCycleEpoch(sid) == null,
				15_000, "resumed cycle completes and releases the claim");

			AVector<ACell> conv = rootConversation(engine, "boot-agent", sid);
			assertEquals(1, countTurnsContaining(conv, "venue restarted"),
				"exactly one synthetic restart result for the dangling call: " + conv);
			assertTrue(countTurnsContaining(conv, "gave up") > 0,
				"the model continued from the synthetic result: " + conv);
			assertEquals(1, countTurnsContaining(conv, "please use the slow tool"),
				"no duplicated user turn on resume");

			engine.close();
			ns.close();
		}
	}

	/**
	 * Crash inside a running subgoal child, recovered via recoverJobs (the
	 * chat job re-fires — deduped, no duplicate envelope): the repaired child
	 * resumes, completes, pops atomically into the parent, and the root
	 * answers the original chat.
	 */
	@Test
	public void testCrashResumeInsideSubgoalChild() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kp = AKeyPair.generate();
		String did = "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
		AMap<AString, ACell> config = Maps.of(Config.DID, did);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee02");
		String chatJobId;

		// ===== Stage 1: crash while the child frame is mid-flight =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			createGoalAgent(engine, "sub-agent", "v/test/ops/subgoalllm");
			agent(engine, "sub-agent").ensureSession(sid, ALICE);

			Job chatJob = engine.jobs().invokeOperation(
				"v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, "sub-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.MESSAGE, Strings.create("decompose the work")),
				RequestContext.of(ALICE));
			chatJobId = chatJob.getID().toHexString();

			// Child frame live on the lattice, parked in its never-tool
			await(() -> {
				AVector<ACell> fs = frames(engine, "sub-agent", sid);
				return fs != null && fs.count() == 2;
			}, 10_000, "child frame live before the crash");

			engine.flush();
			engine.close();
			ns.close();
		}

		// ===== Stage 2: restart; refired chat + resume completes the tree =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			AVector<ACell> preResume = frames(engine, "sub-agent", sid);
			assertEquals(2, preResume.count(), "child frame survived the crash");
			assertEquals(Strings.create("call_subgoal"), RT.getIn(preResume.get(1), "callId"));

			engine.jobs().recoverJobs();   // re-fires the STARTED chat job (deduped)

			Job recovered = engine.jobs().getJob(Blob.fromHex(chatJobId));
			assertNotNull(recovered, "the chat job must be recovered as live");
			// Generous budget: two engine spin-ups + refired chat + multi-round
			// resume is heavyweight and shares the box with the parallel suite.
			ACell result = recovered.awaitResult(60_000);
			assertTrue(String.valueOf(RT.getIn(result, Fields.RESPONSE)).contains("root done"),
				"the refired chat gets the root's answer: " + result);

			AgentState ag = agent(engine, "sub-agent");
			await(() -> ag.getSessionCycleEpoch(sid) == null, 10_000, "claim released");

			AVector<ACell> fs = frames(engine, "sub-agent", sid);
			assertEquals(1, fs.count(), "child popped — end state is root-only");
			AVector<ACell> conv = rootConversation(engine, "sub-agent", sid);
			assertEquals(1, countTurnsContaining(conv, "sub done"),
				"exactly one popped subgoal result (deduped by callId): " + conv);
			assertEquals(1, countTurnsContaining(conv, "decompose the work"),
				"chat-refire dedupe: no duplicate user turn: " + conv);

			engine.close();
			ns.close();
		}
	}

	/**
	 * A chat sent WITHOUT a sessionId (the first-contact case: the venue mints
	 * one) must, on crash recovery, continue the session it minted — not spawn
	 * a fresh one. The minted sessionId is stamped on the job so the re-fire
	 * routes back; otherwise recovery produces a spurious duplicate session and
	 * answers the caller from it (a stale/hallucinated reply) while the boot
	 * scan separately resumes the real one. (Regression for the bug the earlier
	 * tests missed by always pre-supplying an explicit sessionId.)
	 */
	@Test
	public void testCrashResumeMintedSessionNotDuplicated() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kp = AKeyPair.generate();
		String did = "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
		AMap<AString, ACell> config = Maps.of(Config.DID, did);
		String chatJobId;
		String sidHex;

		// ===== Stage 1: chat with NO sessionId, crash mid-tool =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			createGoalAgent(engine, "mint-agent", "v/test/ops/nevertoolllm");

			Job chatJob = engine.jobs().invokeOperation(
				"v/ops/agent/chat",
				Maps.of(Fields.AGENT_ID, "mint-agent",
					Fields.MESSAGE, Strings.create("start the slow job")),   // no sessionId
				RequestContext.of(ALICE));
			chatJobId = chatJob.getID().toHexString();

			// The venue minted a session — discover it and wait until parked.
			await(() -> agent(engine, "mint-agent").getSessions().count() == 1, 5000, "session minted");
			sidHex = agent(engine, "mint-agent").getSessions().entrySet().iterator().next().getKey().toHexString();
			Blob sid = Blob.fromHex(sidHex);
			await(() -> {
				AVector<ACell> conv = rootConversation(engine, "mint-agent", sid);
				ACell turn = (conv != null && conv.count() > 0) ? conv.get(conv.count() - 1) : null;
				return turn != null && RT.getIn(turn, "toolCalls") != null;
			}, 5000, "parked in the tool");

			engine.flush();
			engine.close();
			ns.close();
		}

		// ===== Stage 2: recover; exactly one session, caller gets ITS answer =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			engine.jobs().recoverJobs();
			AgentAdapter aa = (AgentAdapter) engine.getAdapter("agent");
			aa.wakeInterruptedCycles();

			// Wait for the resumed cycle to finish, then read the caller's job
			// from its persisted record — the fast mock resume can complete and
			// evict the live Job from cache before we look, so getJob() would
			// race to null.
			Blob sid = Blob.fromHex(sidHex);
			Blob chatId = Blob.fromHex(chatJobId);
			RequestContext aliceCtx = RequestContext.of(ALICE);
			await(() -> agent(engine, "mint-agent").getSessionCycleEpoch(sid) == null
					&& Status.COMPLETE.equals(
						RT.getIn(engine.jobs().getJobData(chatId, aliceCtx), Fields.STATUS)),
				30_000, "resumed cycle completed and the caller's job finished");

			// The one session the chat minted is the only one — no spurious dup.
			assertEquals(1, agent(engine, "mint-agent").getSessions().count(),
				"recovery must not mint a second session");

			// The caller's answer IS the resumed session's final assistant turn,
			// not a fresh hallucinated reply from a duplicate session.
			AVector<ACell> conv = rootConversation(engine, "mint-agent", sid);
			String finalTurn = String.valueOf(RT.getIn(conv.get(conv.count() - 1), "content"));
			String response = String.valueOf(
				RT.getIn(engine.jobs().getJobData(chatId, aliceCtx), Fields.OUTPUT, Fields.RESPONSE));
			assertEquals(finalTurn, response,
				"the caller's answer must be the resumed session's response, not a duplicate's");
			assertEquals(1, countTurnsContaining(conv, "venue restarted"),
				"exactly one synthetic restart turn");

			engine.close();
			ns.close();
		}
	}
}
