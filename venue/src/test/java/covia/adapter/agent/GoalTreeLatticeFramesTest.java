package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Agent;
import covia.venue.AgentEvents;
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
 * neverfailllm}/{@code subgoalllm} mocks, given a {@code hold:<gate>}
 * model, make a tool call that waits at that {@link TestAdapter.TestGate}.
 * Once the gate is entered the frame loop is parked inside the tool
 * dispatch, with everything before it already on the lattice, until the
 * test releases it. The run loop's {@code run:end} event marks the rest
 * state a test then asserts.</p>
 */
public class GoalTreeLatticeFramesTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
	}

	// ========== helpers ==========

	/**
	 * @param model the mock's model: {@code hold:<gate>} to block at that gate, or null
	 * @param tools the harness tools the agent is offered
	 */
	private void createAgent(String agentId, String llmOp, String model, String... tools) {
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, Strings.create("v/ops/goaltree/chat"),
			Strings.create("llmOperation"), Strings.create(llmOp),
			Strings.create("systemPrompt"), Strings.create("You are a test agent."));
		if (model != null) config = config.assoc(Fields.MODEL, Strings.create(model));
		if (tools.length > 0) {
			AVector<ACell> offered = Vectors.empty();
			for (String tool : tools) offered = offered.conj(Strings.create(tool));
			config = config.assoc(Fields.TOOLS, offered);
		}
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
	}

	private Agent agent(String agentId) {
		User user = engine.getVenueState().users().user(ALICE_DID);
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
		ACell fv = session.get(Agent.KEY_FRAMES);
		return (fv instanceof AVector) ? (AVector<ACell>) fv : Vectors.empty();
	}

	private static AVector<ACell> conversation(ACell frame) {
		AVector<ACell> conv = covia.adapter.agent.GoalTreeContext.activeConversation(RT.ensureMap(frame));
		return (conv != null) ? conv : Vectors.empty();
	}

	/** Last conversation turn of frames[index], or null. */
	private ACell lastTurn(String agentId, Blob sid, int index) {
		AVector<ACell> fs = frames(agentId, sid);
		if (fs == null || index >= fs.count()) return null;
		AVector<ACell> conv = conversation(fs.get(index));
		return (conv.count() > 0) ? conv.get(conv.count() - 1) : null;
	}

	/** A gate for the agent's blocking tool call; its model is {@code "hold:" + name}. */
	private String gateName(String agentId) {
		return agentId + "-" + ALICE_DID;
	}

	private static void awaitEntered(TestAdapter.TestGate gate, String desc) throws InterruptedException {
		assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "never reached: " + desc);
	}

	/**
	 * The rest status the agent's next run loop ends in, from its
	 * {@code run:end} event — emitted once the run's last merge, settle and
	 * sleep are on the lattice. Open it before the work that starts the run.
	 */
	private final class RunEnd implements AutoCloseable {
		private final CompletableFuture<AString> status = new CompletableFuture<>();
		private final AgentEvents.Subscription subscription;

		RunEnd(String agentId) {
			subscription = engine.agentEvents().subscribe(ALICE_DID, Strings.create(agentId), e -> {
				if (AgentEvents.RUN_END.equals(e.type())) {
					status.complete(RT.ensureString(RT.getIn(e.data(), Fields.STATUS)));
				}
			});
		}

		AString await() throws Exception {
			return status.get(10, TimeUnit.SECONDS);
		}

		@Override
		public void close() {
			subscription.close();
		}
	}

	// ========== I3: the lattice is the single authoritative copy ==========

	/**
	 * A cycle on a claimed session writes every frame mutation live and emits
	 * NO frames on its output — the session record is the single copy (the
	 * FramesOwning gate keeps the framework's merge out of frames, so a missed
	 * live-write site would be immediately visible as missing lattice state,
	 * with nothing papering over it). Direct-invoke (local-store) runs still
	 * return the stack in the output for their callers.
	 */
	@Test
	public void testSessionedRunWritesLiveAndEmitsNoFrames() {
		createAgent("inv-agent", "v/test/ops/llm", null);
		Blob sid = mintSession("inv-agent", "aa112233445566778899aabbccddee01");

		// Start the cycle as the run loop does: claim the session and move the
		// input into its conversation, then hand the runtime the claim.
		AVector<ACell> messages = Vectors.of(
			(ACell) Maps.of(Strings.create("message"), Strings.create("hello lattice")));
		ACell claim = Strings.create("test-claim");
		assertEquals(Agent.CycleStart.SESSION, agent("inv-agent").beginCycle(null, null, sid, claim,
			covia.adapter.AgentAdapter.cycleInputTurns(messages, null, null, null, 1), 0));

		GoalTreeAdapter adapter = (GoalTreeAdapter) engine.getAdapter("goaltree");
		RequestContext ctx = RequestContext.of(ALICE_DID).withSessionId(sid);
		ACell input = Maps.of(
			Fields.AGENT_ID, "inv-agent",
			Agent.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Echo agent.")),
			Fields.SESSION, Maps.of(Fields.SESSION_ID, Strings.create(sid.toHexString()),
				Agent.KEY_IN_CYCLE, claim, Agent.KEY_PENDING, messages));

		ACell output = adapter.processGoal(null, ctx, input);

		assertNull(RT.getIn(output, Fields.FRAMES),
			"a sessioned run must not emit a second copy of the frames");
		AVector<ACell> lattice = frames("inv-agent", sid);
		AVector<ACell> conv = conversation(lattice.get(0));
		assertTrue(conv.count() >= 2,
			"the cycle's turns (user input + assistant) must be live on the lattice: " + conv);
		boolean sawInput = false;
		for (long i = 0; i < conv.count(); i++) {
			if (String.valueOf(RT.getIn(conv.get(i), "content")).contains("hello lattice")) sawInput = true;
		}
		assertTrue(sawInput, "the cycle-input turn must be on the lattice");

		// Direct-invoke (no session scope) still returns frames in the output
		ACell directOut = adapter.processGoal(null, RequestContext.of(ALICE_DID), Maps.of(
			Fields.AGENT_ID, "inv-agent",
			Agent.KEY_CONFIG, Maps.of(
				Strings.create("llmOperation"), Strings.create("v/test/ops/llm"),
				Strings.create("systemPrompt"), Strings.create("Echo agent.")),
			Fields.MESSAGES, Vectors.of(
				(ACell) Maps.of(Strings.create("message"), Strings.create("direct")))));
		assertNotNull(RT.getIn(directOut, Fields.FRAMES),
			"direct-invoke runs keep returning frames to their caller");
	}

	// ========== Mid-run observability + I2 on the success path ==========

	@Test
	public void testMidRunFramesVisibleAndMidCycleMessageSurvives() throws Exception {
		String gateName = gateName("live-agent");
		createAgent("live-agent", "v/test/ops/nevertoolllm", "hold:" + gateName);
		Blob sid = mintSession("live-agent", "aa112233445566778899aabbccddee02");
		Agent ag = agent("live-agent");

		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				RunEnd runEnd = new RunEnd("live-agent")) {
			Job chatJob = chat("live-agent", sid, "use your slow tool");

			// Mid-run: the assistant turn with its dangling toolCall is on the
			// lattice BEFORE the tool completes, and the cycle claim is visible.
			awaitEntered(gate, "the slow tool");
			ACell turn = lastTurn("live-agent", sid, 0);
			assertNotNull(turn, "the assistant turn is on the lattice mid-run");
			assertNotNull(RT.getIn(turn, "toolCalls"), "dangling toolCall visible on the lattice mid-run: " + turn);
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

			// The tool returns, the mock answers with text, chat completes.
			gate.release();
			ACell result = chatJob.awaitResult(15000);
			assertTrue(String.valueOf(RT.getIn(result, Fields.RESPONSE)).contains("gave up"));

			// The mid-cycle message must not be LOST (I2): the merge preserves it
			// (drain 0 for FramesOwning), and the run loop then processes it in a
			// follow-up cycle — the deterministic end state is its turn in the
			// conversation, pending empty, agent asleep.
			assertEquals(Agent.SLEEPING, runEnd.await(), "the run ends asleep");
		}
		assertEquals(Agent.SLEEPING, ag.getStatus());
		assertEquals(0, ag.getSessionPending(sid).count(), "the follow-up cycle took the mid-cycle message");
		assertNull(ag.getSessionCycleEpoch(sid), "no cycle holds the session");
		AVector<ACell> conv = conversation(frames("live-agent", sid).get(0));
		long seen = 0;
		for (long i = 0; i < conv.count(); i++) {
			if (String.valueOf(RT.getIn(conv.get(i), "content")).contains("mid-cycle message")) seen++;
		}
		assertEquals(1, seen,
			"the mid-cycle message must land in the conversation exactly once: " + conv);
	}

	// ========== I2 on the failure path ==========

	@Test
	public void testMidCycleMessageSurvivesTransitionFailure() throws Exception {
		String gateName = gateName("fail-agent");
		createAgent("fail-agent", "v/test/ops/neverfailllm", "hold:" + gateName);
		Blob sid = mintSession("fail-agent", "aa112233445566778899aabbccddee03");
		Agent ag = agent("fail-agent");

		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				RunEnd runEnd = new RunEnd("fail-agent")) {
			Job chatJob = chat("fail-agent", sid, "m1");
			awaitEntered(gate, "cycle blocked in the slow tool");

			// Arrives mid-cycle; the transition will FAIL once the tool returns
			// (scripted L3 failure) — the error path must not double-drain.
			engine.jobs().invokeOperation(
				"v/ops/agent/message",
				Maps.of(Fields.AGENT_ID, "fail-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString()),
					Fields.MESSAGE, Strings.create("m2")),
				RequestContext.of(ALICE_DID)).awaitResult(5000);

			gate.release();
			assertThrows(Exception.class, () -> chatJob.awaitResult(15000),
				"chat must fail when the transition fails");
			assertEquals(Agent.SUSPENDED, runEnd.await(), "transition failure suspends the agent");
		}
		assertEquals(Agent.SUSPENDED, ag.getStatus());
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
	public void testSubgoalChildLiveOnLatticeAndAtomicPop() throws Exception {
		String gateName = gateName("sub-agent");
		createAgent("sub-agent", "v/test/ops/subgoalllm", "hold:" + gateName, "subgoal");
		Blob sid = mintSession("sub-agent", "aa112233445566778899aabbccddee04");

		ACell result;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job chatJob = chat("sub-agent", sid, "decompose the work");

			// Mid-run: the pushed child frame is live on the lattice, stamped
			// with the spawning toolCall id.
			awaitEntered(gate, "the child frame's slow tool");
			AVector<ACell> mid = frames("sub-agent", sid);
			assertEquals(2, mid.count(), "child frame live on the lattice during the subgoal: " + mid);
			assertEquals(Strings.create("call_subgoal"), RT.getIn(mid.get(1), "callId"),
				"child frame must carry the spawning toolCall id");
			assertEquals(Strings.create("run the sub-task"), RT.getIn(mid.get(1), "description"));

			gate.release();
			result = chatJob.awaitResult(15000);
		}
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
		String gateName = gateName("zombie-agent");
		createAgent("zombie-agent", "v/test/ops/nevertoolllm", "hold:" + gateName);
		Blob sid = mintSession("zombie-agent", "aa112233445566778899aabbccddee05");
		Agent ag = agent("zombie-agent");

		// The gate stays shut until the end: the transition is still parked in
		// its tool, a zombie, when the claim it holds is released.
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				RunEnd runEnd = new RunEnd("zombie-agent")) {
			chat("zombie-agent", sid, "block please");
			awaitEntered(gate, "the slow tool");

			// The epoch the parked transition thread holds — exactly what its later,
			// post-tool "zombie" writes will carry once cancel fails to stop it.
			ACell zombieEpoch = ag.getSessionCycleEpoch(sid);
			assertNotNull(zombieEpoch, "cycle epoch must be claimed before suspend");

			// Suspend while the transition is parked in the tool: the suspension
			// revokes the cycle's claim in its own write, which fences every later
			// write from the still-running transition thread (cancel does not stop it).
			engine.jobs().invokeOperation(
				"v/ops/agent/suspend",
				Maps.of(Fields.AGENT_ID, "zombie-agent"),
				RequestContext.of(ALICE_DID)).awaitResult(5000);
			assertEquals(Agent.SUSPENDED, ag.getStatus(), "agent suspended");

			assertEquals(Agent.SUSPENDED, runEnd.await(), "the run ends suspended");
			assertNull(ag.getSessionCycleEpoch(sid), "the suspension released the cycle claim");

			AVector<ACell> atSuspend = frames("zombie-agent", sid);

			// Deterministic I1 check: a frame write bearing the pre-suspend epoch —
			// the exact write the zombie transition attempts when its tool finally
			// returns — is rejected by the released claim and cannot touch the
			// frames.
			boolean applied = ag.session(sid).updateFrames(zombieEpoch,
				f -> f.conj(Maps.of(Strings.create("zombie"), Strings.create("write"))));
			assertFalse(applied, "a write with the superseded cycle epoch must be fenced (I1)");
			assertEquals(atSuspend, frames("zombie-agent", sid),
				"fenced zombie write must not change frames (I1)");
			assertNull(ag.getSessionCycleEpoch(sid), "claim stays released");
		}
	}

	// ========== deleteSession mid-run aborts cleanly ==========

	@Test
	public void testDeleteSessionMidRunAborts() throws Exception {
		String gateName = gateName("delsess-agent");
		createAgent("delsess-agent", "v/test/ops/nevertoolllm", "hold:" + gateName);
		Blob sid = mintSession("delsess-agent", "aa112233445566778899aabbccddee06");
		Agent ag = agent("delsess-agent");

		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName);
				RunEnd runEnd = new RunEnd("delsess-agent")) {
			Job chatJob = chat("delsess-agent", sid, "block please");
			awaitEntered(gate, "the slow tool");
			assertNotNull(ag.getSessionCycleEpoch(sid), "cycle claimed");

			engine.jobs().invokeOperation(
				"v/ops/agent/delete-session",
				Maps.of(Fields.AGENT_ID, "delsess-agent",
					Fields.SESSION_ID, Strings.create(sid.toHexString())),
				RequestContext.of(ALICE_DID)).awaitResult(5000);

			// The chat caller is failed by deleteSession; the blocked transition
			// aborts at its next write (session gone) instead of grinding on —
			// surfacing as a failed cycle, not a hang. The work it lost was the
			// deleted session's, so the agent carries on (AGENT_LOOP.md §3.4).
			assertThrows(Exception.class, () -> chatJob.awaitResult(15000));
			assertNull(agent("delsess-agent").getSession(sid), "session stays deleted");
			gate.release();
			assertEquals(Agent.SLEEPING, runEnd.await(), "the run ends asleep");
		}
		assertEquals(Agent.SLEEPING, ag.getStatus());
		assertTrue(ag.getTimeline().count() > 0, "aborted cycle surfaces as a failed cycle in the timeline");
		assertNull(ag.getError(), "losing a deleted session's cycle is not a fault in the agent");
		String result = String.valueOf(RT.getIn(ag.getTimeline().get(ag.getTimeline().count() - 1), Fields.RESULT));
		assertTrue(result.contains("superseded") || result.contains("vanished"),
			"the cycle's result must be the abort diagnostic: " + result);
	}
}
