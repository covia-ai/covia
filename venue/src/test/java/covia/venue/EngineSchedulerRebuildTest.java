package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.AgentScheduler.ThreadKind;
import covia.venue.AgentScheduler.ThreadRef;

/**
 * Validates {@link Engine#rebuildSchedulerFromLattice} — on boot, the
 * scheduler's in-memory index is rebuilt from session/task {@code wakeTime}
 * fields in the lattice. The lattice is authoritative; a crash that drops
 * scheduler state never loses a scheduled wake.
 */
public class EngineSchedulerRebuildTest {

	@Test
	public void testRebuildRegistersExistingSessionAndTaskWakes() throws Exception {
		// Fresh, isolated engine — not the shared TestEngine, so that
		// scheduler/lattice state is guaranteed clean for this test.
		Engine engine = Engine.createTemp(null);
		try {
			AString userDid = Strings.create("did:key:z6Mk-rebuild-test");
			User user = engine.getVenueState().users().ensure(userDid);
			AgentState agent = user.ensureAgent("rebuild-agent", Maps.empty(), null);

			// Seed lattice with one session + one task, each carrying a
			// far-future wakeTime so nothing fires during the test.
			Blob sid = Blob.fromHex("aa");
			agent.ensureSession(sid, userDid);

			Blob taskId = Blob.fromHex("bb");
			agent.addTask(taskId, Maps.empty());

			long farFuture = System.currentTimeMillis() + 60_000;
			agent.setThreadWakeTime(engine.scheduler(), userDid,
				ThreadKind.SESSION, sid, farFuture);
			agent.setThreadWakeTime(engine.scheduler(), userDid,
				ThreadKind.TASK, taskId, farFuture);

			ThreadRef sessionRef =
				new ThreadRef(userDid, agent.getAgentId(), ThreadKind.SESSION, sid);
			ThreadRef taskRef =
				new ThreadRef(userDid, agent.getAgentId(), ThreadKind.TASK, taskId);
			assertTrue(engine.scheduler().contains(sessionRef));
			assertTrue(engine.scheduler().contains(taskRef));
			assertEquals(2, engine.scheduler().size());

			// Simulate a crash: drop the in-memory scheduler index without
			// touching the lattice. Lattice still carries wakeTime fields.
			engine.scheduler().cancel(sessionRef);
			engine.scheduler().cancel(taskRef);
			assertFalse(engine.scheduler().contains(sessionRef));
			assertFalse(engine.scheduler().contains(taskRef));
			assertEquals(0, engine.scheduler().size());

			// Rebuild from lattice — every pre-existing wakeTime should be
			// re-registered as a ThreadRef.
			engine.rebuildSchedulerFromLattice();
			assertTrue(engine.scheduler().contains(sessionRef),
				"session wake should be rebuilt from lattice");
			assertTrue(engine.scheduler().contains(taskRef),
				"task wake should be rebuilt from lattice");
			assertEquals(2, engine.scheduler().size());
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRebuildSkipsEntriesWithoutWakeTime() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			AString userDid = Strings.create("did:key:z6Mk-rebuild-test-empty");
			User user = engine.getVenueState().users().ensure(userDid);
			AgentState agent = user.ensureAgent("no-wakes-agent", Maps.empty(), null);

			// Session + task exist but neither has wakeTime.
			Blob sid = Blob.fromHex("cc");
			agent.ensureSession(sid, userDid);
			Blob taskId = Blob.fromHex("dd");
			agent.addTask(taskId, Maps.empty());

			// Start clean — no wakes in scheduler.
			assertEquals(0, engine.scheduler().size());

			engine.rebuildSchedulerFromLattice();
			assertEquals(0, engine.scheduler().size(),
				"rebuild should register nothing when no wakeTime fields exist");
		} finally {
			engine.close();
		}
	}

	@Test
	public void testRebuildOnEmptyLatticeIsNoop() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			// createTemp() already ran rebuild inside the constructor against
			// an empty lattice; this should still be safe to call again.
			engine.rebuildSchedulerFromLattice();
			assertEquals(0, engine.scheduler().size());
		} finally {
			engine.close();
		}
	}
}
