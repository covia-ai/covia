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
	 * True when the child frame is parked in the never-tool with its dangling
	 * assistant toolCall turn DURABLE. Awaiting only {@code frames.count()==2}
	 * races the child's assistant turn: the crash then lands on a child with
	 * just the goal turn, there is nothing to repair, and the resumed child
	 * legitimately re-runs the tool from scratch (at-least-once) — parking for
	 * the full tool timeout, which is not the scenario these tests pin.
	 */
	private static boolean childParkedDurably(Engine engine, String agentId, Blob sid) {
		AVector<ACell> fs = frames(engine, agentId, sid);
		if (fs == null || fs.count() != 2) return false;
		ACell conv = RT.getIn(fs.get(1), "conversation");
		return conv instanceof AVector<?> v && v.count() >= 2
			&& String.valueOf(conv).contains("toolCalls");
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

			engine.jobs().recoverJobs();   // nothing in flight (message completed at delivery)
			AgentAdapter aa = (AgentAdapter) engine.getAdapter("agent");
			assertEquals(1, aa.wakeAgentsWithWork(),
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
	 * Crash inside a running subgoal child. Recovery never re-executes intake
	 * (#214): at boot the caller's chat job FAILS honestly (session intact,
	 * sessionId on the record so the caller can re-engage), and the boot scan
	 * resumes the agent's interrupted cycle from durable state — the repaired
	 * child completes, pops atomically into the parent, and the root's answer
	 * lands in the conversation.
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

			// Child frame live on the lattice, parked in its never-tool — with
			// the dangling toolCall turn durable (the state the resume repairs).
			await(() -> childParkedDurably(engine, "sub-agent", sid),
				10_000, "child parked with its dangling toolCall durable");

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

			engine.jobs().recoverJobs();   // stabilises: fails the in-flight chat job

			// The caller's chat job fails honestly — never re-executed — with the
			// sessionId on the record so the caller knows which session to re-engage.
			RequestContext aliceCtx = RequestContext.of(ALICE);
			Blob chatId = Blob.fromHex(chatJobId);
			AMap<AString, ACell> chatData = engine.jobs().getJobData(chatId, aliceCtx);
			assertEquals(Status.FAILED, RT.getIn(chatData, Fields.STATUS),
				"in-flight chat fails at boot: " + chatData);
			String err = String.valueOf(RT.getIn(chatData, Fields.ERROR));
			assertTrue(err.contains("re-send"), "error tells the caller to re-send: " + err);
			assertTrue(err.contains(sid.toHexString()), "error names the session: " + err);

			// The agent's own interrupted work resumes from durable state alone.
			AgentAdapter aa = (AgentAdapter) engine.getAdapter("agent");
			assertEquals(1, aa.wakeAgentsWithWork(), "boot scan wakes the interrupted agent");

			AgentState ag = agent(engine, "sub-agent");
			try {
				await(() -> ag.getSessionCycleEpoch(sid) == null
						&& countTurnsContaining(rootConversation(engine, "sub-agent", sid), "root done") > 0,
					30_000, "resumed cycle completes the tree and releases the claim");
			} catch (AssertionError e) {
				// Self-diagnosing: dump the durable state AND all thread stacks
				// (incl. virtual threads) so a stall names its cause.
				String dumpPath = System.getProperty("java.io.tmpdir")
					+ "/goaltree-stall-" + System.currentTimeMillis() + ".txt";
				try {
					long pid = ProcessHandle.current().pid();
					new ProcessBuilder("jcmd", Long.toString(pid),
						"Thread.dump_to_file", "-format=plain", dumpPath)
						.inheritIO().start().waitFor();
				} catch (Exception ignored) {}
				fail("resume stalled — epoch=" + ag.getSessionCycleEpoch(sid)
					+ " status=" + ag.getStatus()
					+ " threadDump=" + dumpPath
					+ " frames=" + frames(engine, "sub-agent", sid), e);
			}

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
	 * one), crashed mid-cycle. Recovery must leave exactly one session — the
	 * minted one — and fail the caller's job with that sessionId on the record
	 * (the stamp is how a caller re-engages the conversation their failed chat
	 * started). The boot scan alone resumes the interrupted cycle; nothing
	 * re-executes intake, so no duplicate session can ever be minted (#214).
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

			// The caller's job fails honestly, carrying the MINTED sessionId —
			// the caller's route back into the conversation their chat started.
			Blob sid = Blob.fromHex(sidHex);
			Blob chatId = Blob.fromHex(chatJobId);
			RequestContext aliceCtx = RequestContext.of(ALICE);
			AMap<AString, ACell> chatData = engine.jobs().getJobData(chatId, aliceCtx);
			assertEquals(Status.FAILED, RT.getIn(chatData, Fields.STATUS),
				"in-flight chat fails at boot: " + chatData);
			assertTrue(String.valueOf(RT.getIn(chatData, Fields.ERROR)).contains(sidHex),
				"the failed job names the minted session: " + chatData);

			// Boot scan resumes the interrupted cycle from durable state.
			AgentAdapter aa = (AgentAdapter) engine.getAdapter("agent");
			assertEquals(1, aa.wakeAgentsWithWork(), "boot scan wakes the interrupted agent");
			await(() -> agent(engine, "mint-agent").getSessionCycleEpoch(sid) == null,
				30_000, "resumed cycle completed");

			// The one session the chat minted is the only one — no spurious dup.
			assertEquals(1, agent(engine, "mint-agent").getSessions().count(),
				"recovery must not mint a second session");

			// The resumed cycle finished the conversation in that session.
			AVector<ACell> conv = rootConversation(engine, "mint-agent", sid);
			assertEquals(1, countTurnsContaining(conv, "venue restarted"),
				"exactly one synthetic restart turn");

			engine.close();
			ns.close();
		}
	}

	/**
	 * An in-flight {@code agent:request} whose task is still queued at the
	 * crash is RESTORED at boot, not failed and not re-executed (#214): the
	 * task index is the durable work marker (taskId == jobID), so the job
	 * legitimately outlives the restart — callers keep polling by ID — and the
	 * boot scan wakes the agent to run it.
	 */
	@Test
	public void testCrashRequestJobRestoredWhileTaskQueued() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kp = AKeyPair.generate();
		String did = "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
		AMap<AString, ACell> config = Maps.of(Config.DID, did);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee03");
		String requestJobId;

		// ===== Stage 1: request parked mid-cycle (task not yet merged out) =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			createGoalAgent(engine, "task-agent", "v/test/ops/nevertoolllm");
			agent(engine, "task-agent").ensureSession(sid, ALICE);

			Job requestJob = engine.jobs().invokeOperation(
				"v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "task-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.INPUT, Strings.create("please use the slow tool")),
				RequestContext.of(ALICE));
			requestJobId = requestJob.getID().toHexString();

			// nevertoolllm parks at the ROOT (no subgoal child): await the
			// dangling assistant toolCall turn itself, not just any frame state.
			await(() -> {
				AVector<ACell> conv = rootConversation(engine, "task-agent", sid);
				return conv != null && conv.count() >= 2
					&& countTurnsContaining(conv, "toolCalls") > 0;
			}, 10_000, "root parked with its dangling toolCall durable");
			assertNotNull(agent(engine, "task-agent").getTasks().get(requestJob.getID()),
				"task still queued while the cycle runs");

			engine.flush();
			engine.close();
			ns.close();
		}

		// ===== Stage 2: restored live, still STARTED, task queued, agent woken =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			engine.jobs().recoverJobs();

			Blob reqId = Blob.fromHex(requestJobId);
			Job restored = engine.jobs().getJob(reqId);
			assertNotNull(restored, "in-flight task job is restored live, never failed");
			assertEquals(Status.STARTED, restored.getStatus(),
				"restored task job stays STARTED — caller keeps awaiting by ID");
			assertNotNull(agent(engine, "task-agent").getTasks().get(reqId),
				"the durable task marker survived");

			AgentAdapter aa = (AgentAdapter) engine.getAdapter("agent");
			assertEquals(1, aa.wakeAgentsWithWork(),
				"boot scan wakes the agent to run its queued task");

			engine.close();
			ns.close();
		}
	}

	/**
	 * The converse: an in-flight {@code agent:request} whose task is GONE at
	 * boot (venue crashed in the window between the merge removing the task
	 * and the job completion write) fails honestly, pointing the caller at the
	 * agent's timeline — there is no durable work left to drive completion,
	 * and recovery never re-executes.
	 */
	@Test
	public void testCrashRequestJobTaskGoneFailsAtBoot() throws Exception {
		EtchStore store = EtchStore.createTemp();
		AKeyPair kp = AKeyPair.generate();
		String did = "did:key:" + Multikey.encodePublicKey(kp.getAccountKey());
		AMap<AString, ACell> config = Maps.of(Config.DID, did);
		Blob sid = Blob.fromHex("cc112233445566778899aabbccddee04");
		String requestJobId;

		// ===== Stage 1: as above — parked mid-cycle, then crash =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			createGoalAgent(engine, "gone-agent", "v/test/ops/nevertoolllm");
			agent(engine, "gone-agent").ensureSession(sid, ALICE);

			Job requestJob = engine.jobs().invokeOperation(
				"v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, "gone-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.INPUT, Strings.create("please use the slow tool")),
				RequestContext.of(ALICE));
			requestJobId = requestJob.getID().toHexString();

			await(() -> {
				AVector<ACell> conv = rootConversation(engine, "gone-agent", sid);
				return conv != null && conv.count() >= 2
					&& countTurnsContaining(conv, "toolCalls") > 0;
			}, 10_000, "root parked with its dangling toolCall durable");

			engine.flush();
			engine.close();
			ns.close();
		}

		// ===== Stage 2: simulate the merge→completion crash window, recover =====
		{
			NodeServer<Index<Keyword, ACell>> ns = new NodeServer<>(Covia.ROOT, store, NodeConfig.port(-1));
			ns.launch();
			Engine engine = new Engine(config, ns.getCursor(), kp);
			Engine.addDemoAssets(engine);

			// The merge removed the task but the job completion never landed.
			Blob reqId = Blob.fromHex(requestJobId);
			agent(engine, "gone-agent").removeTask(reqId);

			engine.jobs().recoverJobs();

			AMap<AString, ACell> data = engine.jobs().getJobData(reqId, RequestContext.of(ALICE));
			assertEquals(Status.FAILED, RT.getIn(data, Fields.STATUS),
				"task-gone request job fails at boot: " + data);
			assertTrue(String.valueOf(RT.getIn(data, Fields.ERROR)).contains("task concluded"),
				"error points the caller at the agent's result: " + data);

			engine.close();
			ns.close();
		}
	}
}
