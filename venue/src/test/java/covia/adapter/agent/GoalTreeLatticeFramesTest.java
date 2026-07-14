package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * Lattice-resident goal-tree frames (Stage B): every frame mutation writes
 * through to {@code sessions/<sid>/frames} live, epoch-fenced. Covers the
 * plan's invariants: I1 (zombie writes fenced after suspend), I2 (drained ⇔
 * turn landed; mid-cycle arrivals survive every exit path), I3 (lattice ==
 * emitted frames after a cycle), I4 (atomic subgoal pop), plus mid-run
 * observability and the deleteSession abort.
 *
 * <p>Deterministic blocking window: the {@code nevertoolllm}/{@code
 * subgoalllm} mocks call {@code v/test/ops/never} as a tool, so the frame
 * loop parks inside the tool dispatch for exactly {@code toolCallTimeoutMs}
 * — mid-run lattice state is pollable during that window.</p>
 */
public class GoalTreeLatticeFramesTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	// ========== helpers ==========

	private void createAgent(String agentId, String llmOp, long toolTimeoutMs) {
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, Strings.create("v/ops/goaltree/chat"),
			Strings.create("llmOperation"), Strings.create(llmOp),
			Strings.create("systemPrompt"), Strings.create("You are a test agent."));
		if (toolTimeoutMs > 0) {
			config = config.assoc(Strings.create("toolCallTimeoutMs"), CVMLong.create(toolTimeoutMs));
		}
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	private AgentState agent(String agentId) {
		User user = engine.getVenueState().users().get(ALICE_DID);
		return user.agent(agentId);
	}

	/** Pre-mints a session so tests know the sid before the chat starts. */
	private Blob mintSession(String agentId, String hex) {
		Blob sid = Blob.fromHex(hex);
		agent(agentId).ensureSession(sid, ALICE_DID);
		return sid;
	}

	private Job chat(String agentId, Blob sid, String message) {
		return engine.jobs().invokeOperation(
			"v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, agentId,
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE, Strings.create(message)),
			RequestContext.of(ALICE_DID));
	}

	@SuppressWarnings("unchecked")
	private AVector<ACell> frames(String agentId, Blob sid) {
		AMap<AString, ACell> session = agent(agentId).getSession(sid);
		if (session == null) return null;
		ACell fv = session.get(AgentState.KEY_FRAMES);
		return (fv instanceof AVector) ? (AVector<ACell>) fv : Vectors.empty();
	}

	private static AVector<ACell> conversation(ACell frame) {
		AVector<ACell> conv = RT.ensureVector(RT.getIn(frame, "conversation"));
		return (conv != null) ? conv : Vectors.empty();
	}

	/** Last conversation turn of frames[index], or null. */
	private ACell lastTurn(String agentId, Blob sid, int index) {
		AVector<ACell> fs = frames(agentId, sid);
		if (fs == null || index >= fs.count()) return null;
		AVector<ACell> conv = conversation(fs.get(index));
		return (conv.count() > 0) ? conv.get(conv.count() - 1) : null;
	}

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

	// ========== I3: lattice frames == emitted frames ==========

	/**
	 * The load-bearing cutover invariant: after a sessioned cycle, the frames
	 * on the lattice equal the frames the transition emitted — any mutation
	 * site missed by the live-write cutover diverges here (the merge no
	 * longer rewrites frames for FramesOwning adapters, so nothing papers
	 * over a miss).
	 */
	@Test
	public void testLatticeFramesMatchEmittedFrames() {
		createAgent("inv-agent", "v/test/ops/llm", 0);
		Blob sid = mintSession("inv-agent", "aa112233445566778899aabbccddee01");

		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		RequestContext ctx = RequestContext.of(ALICE_DID).withSessionId(sid);
		ACell input = Maps.of(
			Fields.AGENT_ID, "inv-agent",
			AgentState.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Echo agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("message"), Strings.create("hello lattice"))));

		ACell output = adapter.processGoal(null, ctx, input);

		AVector<ACell> emitted = RT.ensureVector(RT.getIn(output, Fields.FRAMES));
		assertNotNull(emitted, "transition must still emit frames in Stage B");
		assertEquals(emitted, frames("inv-agent", sid),
			"lattice frames must equal emitted frames — a mismatch means a "
			+ "mutation site missed the live-write cutover");
		assertTrue(conversation(emitted.get(0)).count() > 0, "cycle turns recorded");
	}

	// ========== Mid-run observability + I2 on the success path ==========

	@Test
	public void testMidRunFramesVisibleAndMidCycleMessageSurvives() {
		createAgent("live-agent", "v/test/ops/nevertoolllm", 2000);
		Blob sid = mintSession("live-agent", "aa112233445566778899aabbccddee02");
		AgentState ag = agent("live-agent");

		Job chatJob = chat("live-agent", sid, "use your slow tool");

		// Mid-run: the assistant turn with its dangling toolCall is on the
		// lattice BEFORE the tool completes, and the cycle claim is visible.
		await(() -> {
			ACell turn = lastTurn("live-agent", sid, 0);
			return turn != null && RT.getIn(turn, "toolCalls") != null;
		}, 5000, "dangling toolCall visible on the lattice mid-run");
		assertNotNull(ag.getSessionCycleEpoch(sid), "inCycle claim visible mid-run");

		// A message arriving MID-cycle (after the cycle-start drain) must
		// survive the cycle (I2) — the framework no longer drains for
		// FramesOwning transitions.
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "live-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE, Strings.create("mid-cycle message")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Tool times out (~2s), mock returns text, chat completes.
		ACell result = chatJob.awaitResult(15000);
		assertTrue(String.valueOf(RT.getIn(result, Fields.RESPONSE)).contains("gave up"));

		// Post-cycle: claim released; the presented envelope was drained (its
		// turn landed at cycle start) but the mid-cycle arrival survived.
		await(() -> ag.getSessionCycleEpoch(sid) == null, 5000, "inCycle cleared at merge");
		AVector<ACell> pendingAfter = ag.getSessionPending(sid);
		assertEquals(1, pendingAfter.count(), "mid-cycle message must survive the merge");
		assertEquals(Strings.create("mid-cycle message"),
			RT.getIn(pendingAfter.get(0), Fields.MESSAGE.toString()));
	}

	// ========== I2 on the failure path ==========

	@Test
	public void testMidCycleMessageSurvivesTransitionFailure() {
		createAgent("fail-agent", "v/test/ops/neverfailllm", 2000);
		Blob sid = mintSession("fail-agent", "aa112233445566778899aabbccddee03");
		AgentState ag = agent("fail-agent");

		Job chatJob = chat("fail-agent", sid, "m1");
		await(() -> {
			ACell turn = lastTurn("fail-agent", sid, 0);
			return turn != null && RT.getIn(turn, "toolCalls") != null;
		}, 5000, "cycle blocked in the slow tool");

		// Arrives mid-cycle; the transition will FAIL after the tool timeout
		// (scripted L3 failure) — the error path must not double-drain.
		engine.jobs().invokeOperation(
			"v/ops/agent/message",
			Maps.of(Fields.AGENT_ID, "fail-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Fields.MESSAGE, Strings.create("m2")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertThrows(Exception.class, () -> chatJob.awaitResult(15000),
			"chat must fail when the transition fails");

		await(() -> AgentState.SUSPENDED.equals(ag.getStatus()), 10000,
			"transition failure suspends the agent");
		AVector<ACell> pendingAfter = ag.getSessionPending(sid);
		assertEquals(1, pendingAfter.count(),
			"the mid-cycle message must survive a FAILED cycle — the framework "
			+ "must not re-drain what the adapter drained at cycle start");
		assertEquals(Strings.create("m2"),
			RT.getIn(pendingAfter.get(0), Fields.MESSAGE.toString()));

		// The presented m1 was drained in the same CAS that landed its turn —
		// the turn is on the lattice even though the cycle failed.
		AVector<ACell> conv = conversation(frames("fail-agent", sid).get(0));
		boolean sawM1 = false;
		for (long i = 0; i < conv.count(); i++) {
			if (String.valueOf(RT.getIn(conv.get(i), "content")).contains("m1")) sawM1 = true;
		}
		assertTrue(sawM1, "drained envelope's turn must be on the lattice (I2)");
	}

	// ========== I4 + live child frames ==========

	@Test
	public void testSubgoalChildLiveOnLatticeAndAtomicPop() {
		createAgent("sub-agent", "v/test/ops/subgoalllm", 2000);
		Blob sid = mintSession("sub-agent", "aa112233445566778899aabbccddee04");

		Job chatJob = chat("sub-agent", sid, "decompose the work");

		// Mid-run: the pushed child frame is live on the lattice, stamped
		// with the spawning toolCall id.
		await(() -> {
			AVector<ACell> fs = frames("sub-agent", sid);
			return fs != null && fs.count() == 2;
		}, 5000, "child frame live on the lattice during the subgoal");
		AVector<ACell> mid = frames("sub-agent", sid);
		assertEquals(Strings.create("call_subgoal"), RT.getIn(mid.get(1), "callId"),
			"child frame must carry the spawning toolCall id");
		assertEquals(Strings.create("run the sub-task"), RT.getIn(mid.get(1), "description"));

		ACell result = chatJob.awaitResult(15000);
		assertTrue(String.valueOf(RT.getIn(result, Fields.RESPONSE)).contains("root done"));

		// Post-pop: child truncated AND its result recorded in the parent —
		// the pop was one CAS, and the end state is root-only as before.
		AVector<ACell> after = frames("sub-agent", sid);
		assertEquals(1, after.count(), "clean cycle ends root-only (child popped)");
		AVector<ACell> conv = conversation(after.get(0));
		boolean sawSubResult = false;
		for (long i = 0; i < conv.count(); i++) {
			ACell turn = conv.get(i);
			if (Strings.create("call_subgoal").equals(RT.getIn(turn, "id"))
					&& String.valueOf(turn).contains("sub done")) {
				sawSubResult = true;
			}
		}
		assertTrue(sawSubResult, "the child's result must be the parent's tool result: " + conv);
	}

	// ========== I1: suspend fences the zombie ==========

	@Test
	public void testSuspendMidRunFencesZombieWrites() throws Exception {
		createAgent("zombie-agent", "v/test/ops/nevertoolllm", 1500);
		Blob sid = mintSession("zombie-agent", "aa112233445566778899aabbccddee05");
		AgentState ag = agent("zombie-agent");

		chat("zombie-agent", sid, "block please");
		await(() -> ag.getSessionCycleEpoch(sid) != null, 5000, "cycle claimed");

		// Suspend while the transition is parked in the tool: the settle
		// clears the cycle claim, which fences every later write from the
		// still-running transition thread (cancel does not stop it).
		engine.jobs().invokeOperation(
			"v/ops/agent/suspend",
			Maps.of(Fields.AGENT_ID, "zombie-agent"),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		await(() -> AgentState.SUSPENDED.equals(ag.getStatus()), 5000, "agent suspended");
		await(() -> ag.getSessionCycleEpoch(sid) == null, 5000,
			"suspend settle releases the cycle claim");

		AVector<ACell> atSuspend = frames("zombie-agent", sid);

		// Let the zombie's tool timeout fire and its post-tool writes get
		// fenced: frames must not change after the settle.
		Thread.sleep(2500);
		assertEquals(atSuspend, frames("zombie-agent", sid),
			"zombie transition writes after the suspend settle must be fenced (I1)");
		assertNull(ag.getSessionCycleEpoch(sid), "claim stays released");
	}

	// ========== deleteSession mid-run aborts cleanly ==========

	@Test
	public void testDeleteSessionMidRunAborts() {
		createAgent("delsess-agent", "v/test/ops/nevertoolllm", 1500);
		Blob sid = mintSession("delsess-agent", "aa112233445566778899aabbccddee06");
		AgentState ag = agent("delsess-agent");

		Job chatJob = chat("delsess-agent", sid, "block please");
		await(() -> ag.getSessionCycleEpoch(sid) != null, 5000, "cycle claimed");

		engine.jobs().invokeOperation(
			"v/ops/agent/delete-session",
			Maps.of(Fields.AGENT_ID, "delsess-agent",
				Fields.SESSION_ID, Strings.create(sid.toHexString())),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// The chat caller is failed by deleteSession; the blocked transition
		// aborts at its next write (session gone) instead of grinding on —
		// surfacing as a transition failure, not a hang.
		assertThrows(Exception.class, () -> chatJob.awaitResult(15000));
		assertNull(agent("delsess-agent").getSession(sid), "session stays deleted");
		await(() -> AgentState.SUSPENDED.equals(ag.getStatus()), 10000,
			"aborted cycle surfaces as a transition failure (agent suspended, diagnosable)");
		assertTrue(String.valueOf(ag.getError()).contains("superseded")
				|| String.valueOf(ag.getError()).contains("vanished"),
			"suspension error must be the abort diagnostic: " + ag.getError());
	}
}
