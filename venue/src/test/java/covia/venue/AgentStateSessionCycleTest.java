package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Unit tests for the session cycle-epoch primitives (lattice-resident frames,
 * Stage A): {@link AgentState#beginSessionCycle},
 * {@link AgentState#presentSessionCycleInput},
 * {@link AgentState#updateSessionFrames} (epoch-fenced), the shared
 * root-turn-append/pending-drain helpers, the {@code inCycle} clear inside
 * {@link AgentState#mergeRunResult}, and the rule that {@code inCycle} is a
 * write fence rather than durable queued work.
 */
public class AgentStateSessionCycleTest {

	private static final Engine engine = TestEngine.ENGINE;

	private AString userDid;
	private AgentState agent;
	private Blob sid;

	private static final ACell EPOCH_A = Strings.create("epoch-a");
	private static final ACell EPOCH_B = Strings.create("epoch-b");

	@BeforeEach
	public void setup(TestInfo info) {
		userDid = TestEngine.uniqueDID(info);
		User user = engine.getVenueState().users().ensure(userDid);
		agent = user.ensureAgent("cycle-agent", Maps.empty(), null);
		sid = Blob.fromHex("00112233445566778899aabbccddeeff");
		agent.ensureSession(sid, userDid);
	}

	private static AMap<AString, ACell> turn(String content) {
		return Maps.of(
			Strings.create("role"),    Strings.create("user"),
			Strings.create("content"), Strings.create(content));
	}

	private static AMap<AString, ACell> envelope(String content) {
		return Maps.of(Strings.create("message"), Strings.create(content));
	}

	@SuppressWarnings("unchecked")
	private AVector<ACell> rootConversation() {
		AMap<AString, ACell> session = agent.getSession(sid);
		AVector<ACell> frames = (AVector<ACell>) session.get(AgentState.KEY_FRAMES);
		return RT.ensureVector(RT.getIn(frames.get(0), "conversation"));
	}

	// ========== beginSessionCycle ==========

	@Test
	public void testInitialLoadsAreStoredOnRootFrame() {
		Blob loadedSid = Blob.fromHex("11112222333344445555666677778888");
		AMap<AString, ACell> loads = Maps.of("w/rules", Maps.of("budget", 500L));
		AMap<AString, ACell> session = agent.ensureSession(loadedSid, userDid, loads);

		assertNull(session.get(Fields.LOADS), "session has no parallel loads slot");
		assertEquals(loads, RT.getIn(session,
			AgentState.KEY_FRAMES, CVMLong.ZERO, Fields.LOADS));
	}

	@Test
	public void testBeginCycleMigratesLegacyLoadsUnderExistingRootLoads() {
		Blob legacySid = Blob.fromHex("9999aaaabbbbccccddddeeeeffff0000");
		AMap<AString, ACell> legacyLoads = Maps.of(
			"w/outer", "legacy",
			"w/shadow", "old",
			"w/masked", "old");
		AMap<AString, ACell> frameLoads = Maps.of(
			"w/shadow", "new",
			"w/frame", "frame");
		frameLoads = frameLoads.assoc(Strings.create("w/masked"), null);
		AMap<AString, ACell> root = Maps.of(
			AgentState.KEY_DESCRIPTION, Strings.EMPTY,
			AgentState.KEY_CONVERSATION, Vectors.empty(),
			Fields.LOADS, frameLoads);
		AMap<AString, ACell> legacySession = Maps.of(
			"c", Maps.empty(), "pending", Vectors.empty(),
			Fields.FRAMES, Vectors.of(root), Fields.LOADS, legacyLoads);
		agent.putRecord(agent.getRecord().assoc(AgentState.KEY_SESSIONS,
			agent.getSessions().assoc(legacySid, legacySession)));

		assertTrue(agent.beginSessionCycle(legacySid, EPOCH_A, null, 0));
		AMap<AString, ACell> migrated = agent.getSession(legacySid);
		assertNull(migrated.get(Fields.LOADS));
		AMap<AString, ACell> loads = RT.ensureMap(RT.getIn(migrated,
			AgentState.KEY_FRAMES, CVMLong.ZERO, Fields.LOADS));
		assertEquals(Strings.create("legacy"), loads.get(Strings.create("w/outer")));
		assertEquals(Strings.create("new"), loads.get(Strings.create("w/shadow")));
		assertEquals(Strings.create("frame"), loads.get(Strings.create("w/frame")));
		assertFalse(loads.containsKey(Strings.create("w/masked")),
			"the former inner nil mask removes the legacy outer value");
	}

	@Test
	public void testBeginCycleClaimsAppendsAndDrainsAtomically() {
		agent.appendSessionPending(sid, envelope("m1"));
		agent.appendSessionPending(sid, envelope("m2"));
		agent.appendSessionPending(sid, envelope("m3"));

		AVector<ACell> turns = Vectors.of(turn("m1"), turn("m2"));
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, turns, 2));

		// All three effects landed together
		assertEquals(EPOCH_A, agent.getSessionCycleEpoch(sid), "epoch claimed");
		assertEquals(2, rootConversation().count(), "input turns appended to frames[0]");
		AVector<ACell> pending = agent.getSessionPending(sid);
		assertEquals(1, pending.count(), "presented prefix drained, tail preserved");
		assertEquals(Strings.create("m3"), RT.getIn(pending.get(0), "message"));

		// meta.turns bumped by the shared helper (parity with mergeRunResult's path)
		AMap<AString, ACell> session = agent.getSession(sid);
		assertEquals(CVMLong.create(2), RT.getIn(session, "meta", "turns"));
	}

	@Test
	public void testBeginCycleMissingSessionIsNoOp() {
		Blob unknown = Blob.fromHex("ffffffffffffffffffffffffffffffff");
		long tsBefore = agent.getTs();
		assertFalse(agent.beginSessionCycle(unknown, EPOCH_A, Vectors.of(turn("x")), 1));
		assertEquals(tsBefore, agent.getTs(), "no-op must not bump the record ts");
	}

	@Test
	public void testStaleEpochOverwrittenByNewCycle() {
		// A stale claim (crash remnant) is the normal resume case — a fresh
		// cycle overwrites it, after which the old epoch's writes are fenced.
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		assertTrue(agent.beginSessionCycle(sid, EPOCH_B, null, 0));
		assertEquals(EPOCH_B, agent.getSessionCycleEpoch(sid));
	}

	@Test
	public void testInputPresentationCannotDrainAfterCycleIsSuperseded() {
		agent.appendSessionPending(sid, envelope("m1"));
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		assertTrue(agent.beginSessionCycle(sid, EPOCH_B, null, 0));

		assertFalse(agent.presentSessionCycleInput(
			sid, EPOCH_A, Vectors.of(turn("m1")), 1));
		assertEquals(0, rootConversation().count());
		assertEquals(1, agent.getSessionPending(sid).count());
		assertEquals(EPOCH_B, agent.getSessionCycleEpoch(sid));
	}

	// ========== updateSessionFrames (epoch fence) ==========

	@Test
	public void testUpdateSessionFramesAppliesUnderOwnEpoch() {
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		assertTrue(agent.updateSessionFrames(sid, EPOCH_A,
			frames -> frames.conj(Maps.of(Strings.create("description"), Strings.create("child")))));
		AMap<AString, ACell> session = agent.getSession(sid);
		assertEquals(2, RT.ensureVector(session.get(AgentState.KEY_FRAMES)).count(),
			"child frame appended (root + child)");
	}

	@Test
	public void testUpdateSessionFramesFencedByEpoch() {
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		AMap<AString, ACell> before = agent.getSession(sid);

		// A superseded cycle (wrong epoch) must be rejected with no write —
		// this is the zombie-writer fence (I1).
		assertFalse(agent.updateSessionFrames(sid, EPOCH_B,
			frames -> frames.conj(Maps.of(Strings.create("description"), Strings.create("zombie")))));
		assertEquals(before, agent.getSession(sid), "fenced write must leave the session untouched");
	}

	@Test
	public void testUpdateSessionFramesRejectedAfterMergeClearsEpoch() {
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		mergeMinimalCycle();
		assertFalse(agent.updateSessionFrames(sid, EPOCH_A, frames -> frames.conj(turn("late"))),
			"a completed cycle's epoch is cleared — its writes must be fenced");
	}

	@Test
	public void testUpdateSessionFramesMissingSessionReturnsFalse() {
		Blob unknown = Blob.fromHex("ffffffffffffffffffffffffffffffff");
		assertFalse(agent.updateSessionFrames(unknown, null, frames -> frames.conj(turn("x"))));
	}

	@Test
	public void testUpdateSessionFramesNoChangeSkipsTsBump() {
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		long tsBefore = agent.getTs();
		assertTrue(agent.updateSessionFrames(sid, EPOCH_A, frames -> frames),
			"identity update applies (returns true) without writing");
		assertEquals(tsBefore, agent.getTs(), "no-change update must not bump the record ts");
	}

	// ========== mergeRunResult clears inCycle ==========

	@Test
	public void testMergeClearsInCycle() {
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));
		assertEquals(EPOCH_A, agent.getSessionCycleEpoch(sid));
		mergeMinimalCycle();
		assertNull(agent.getSessionCycleEpoch(sid),
			"merge must release the cycle claim in the same CAS");
	}

	// ========== inCycle is not work ==========

	@Test
	public void testInCycleDoesNotCountAsWork() {
		// Drain everything: the epoch remains only as a live write fence. It is
		// not a durable instruction to resume the interrupted execution.
		agent.appendSessionPending(sid, envelope("m1"));
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, Vectors.of(turn("m1")), 1));
		assertEquals(0, agent.getSessionPending(sid).count());

		assertFalse(agent.hasSessionPending(), "inCycle must not count as queued work");
		assertNull(agent.pickSessionWithPending(), "inCycle session must not be pickable");

		mergeMinimalCycle();
		assertFalse(agent.hasSessionPending(), "merged cycle with empty pending is quiescent");
		assertNull(agent.pickSessionWithPending());
	}

	// ========== CAS contention: intake vs frame writes ==========

	@Test
	public void testConcurrentIntakeAndFrameWritesLoseNothing() throws Exception {
		final int MESSAGES = 200;
		final int FRAME_WRITES = 200;
		assertTrue(agent.beginSessionCycle(sid, EPOCH_A, null, 0));

		Thread intake = new Thread(() -> {
			for (int i = 0; i < MESSAGES; i++) {
				agent.appendSessionPending(sid, envelope("m" + i));
			}
		});
		intake.start();
		List<Boolean> results = new ArrayList<>();
		for (int i = 0; i < FRAME_WRITES; i++) {
			final int n = i;
			results.add(agent.updateSessionFrames(sid, EPOCH_A,
				frames -> frames.conj(Maps.of(Strings.create("description"), Strings.create("f" + n)))));
		}
		intake.join(10_000);
		assertFalse(intake.isAlive());

		assertTrue(results.stream().allMatch(Boolean::booleanValue),
			"every fenced frame write under the live epoch must apply");
		assertEquals(MESSAGES, agent.getSessionPending(sid).count(),
			"no intake envelope lost under contention");
		AMap<AString, ACell> session = agent.getSession(sid);
		assertEquals(1 + FRAME_WRITES,
			RT.ensureVector(session.get(AgentState.KEY_FRAMES)).count(),
			"no frame write lost under contention (root + appended)");
	}

	// ========== helper ==========

	/** Minimal end-of-cycle merge for this session: no turns, no drain, no
	 *  frames replace — exercises exactly the inCycle clear. */
	private void mergeMinimalCycle() {
		AMap<AString, ACell> timelineEntry = Maps.of(
			Strings.create("op"), Strings.create("test-cycle"));
		agent.mergeRunResult(null, Maps.empty(), null,
			timelineEntry, sid, null, 0, null, null, null);
	}
}
