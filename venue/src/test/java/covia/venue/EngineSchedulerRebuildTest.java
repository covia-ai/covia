package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.AgentState.ThreadKind;

/**
 * Validates {@link Engine#rebuildSchedulerFromLattice} — on boot, each agent's
 * single {@code agent:wake} event is re-derived from the authoritative
 * per-thread {@code wakeTime} fields in the lattice, healing any drift from a
 * prior run. The lattice is the source of truth; a stale stored handle never
 * loses or duplicates a scheduled wake. See GRID_SCHEDULER.md §8.
 */
public class EngineSchedulerRebuildTest {

	@SuppressWarnings("unchecked")
	private static convex.core.data.AMap<AString, ACell> event(AVector<ACell> events, long i) {
		return (convex.core.data.AMap<AString, ACell>) events.get(i);
	}

	private static long timeOf(AVector<ACell> events, long i) {
		return ((convex.core.data.prim.CVMLong) event(events, i).get(Scheduler.K_TIME)).longValue();
	}

	@Test
	public void testRebuildArmsOneEventAtEarliestAcrossThreads() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			AString userDid = Strings.intern("did:key:z6Mk-rebuild-test");
			RequestContext ctx = RequestContext.of(userDid);
			User user = engine.getVenueState().users().ensure(userDid);
			AgentState agent = user.ensureAgent("rebuild-agent", Maps.empty(), null);

			// One session + one task, each with a far-future wakeTime so
			// nothing fires during the test. Session is the earlier of the two.
			Blob sid = Blob.fromHex("aa");
			agent.ensureSession(sid, userDid);
			Blob taskId = Blob.fromHex("bb");
			agent.addTask(taskId, Maps.empty());

			long now = System.currentTimeMillis();
			long sessionWake = now + 600_000;
			long taskWake    = now + 1_200_000;
			agent.setThreadWakeTime(engine.gridScheduler(), userDid,
				ThreadKind.SESSION, sid, sessionWake);
			agent.setThreadWakeTime(engine.gridScheduler(), userDid,
				ThreadKind.TASK, taskId, taskWake);

			// One armed event at the earliest of the two threads.
			AVector<ACell> before = engine.gridScheduler().list(ctx);
			assertEquals(1, before.count());
			assertEquals(sessionWake, timeOf(before, 0));

			// Re-deriving from the lattice is idempotent: still exactly one
			// event at the same earliest time (a fresh handle replaces the old).
			engine.rebuildSchedulerFromLattice();
			AVector<ACell> after = engine.gridScheduler().list(ctx);
			assertEquals(1, after.count(), "rebuild must not duplicate the wake");
			assertEquals(AgentState.TRIGGER_OP, event(after, 0).get(Scheduler.K_OP));
			assertEquals(sessionWake, timeOf(after, 0));
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRebuildArmsNothingWithoutWakeTime() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			AString userDid = Strings.intern("did:key:z6Mk-rebuild-test-empty");
			RequestContext ctx = RequestContext.of(userDid);
			User user = engine.getVenueState().users().ensure(userDid);
			AgentState agent = user.ensureAgent("no-wakes-agent", Maps.empty(), null);

			// Session + task exist but neither carries a wakeTime.
			agent.ensureSession(Blob.fromHex("cc"), userDid);
			agent.addTask(Blob.fromHex("dd"), Maps.empty());

			engine.rebuildSchedulerFromLattice();
			assertEquals(0, engine.gridScheduler().list(ctx).count(),
				"rebuild should arm nothing when no wakeTime fields exist");
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRebuildOnEmptyLatticeIsNoop() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			// The constructor already ran rebuild against an empty lattice;
			// calling again must remain safe.
			engine.rebuildSchedulerFromLattice();
			AString anyone = Strings.intern("did:key:z6Mk-nobody");
			assertEquals(0, engine.gridScheduler().list(RequestContext.of(anyone)).count());
		} finally {
			engine.close();
		}
	}
}
