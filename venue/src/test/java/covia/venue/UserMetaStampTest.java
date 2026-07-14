package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * The user record's framework-owned {@code meta} slot (see
 * GRID_LATTICE_DESIGN.md §"User meta record"): {@code meta.created} minted on
 * the record's first write, {@code meta.updated} auto-bumped by the
 * {@code StampingLattice} boundary on EVERY deep write anywhere in the user's
 * subtree — the activity signal for future identity-lifecycle (TTL) policy.
 * No application code writes these fields; the lattice layer does.
 */
public class UserMetaStampTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;
	private User user;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		user = engine.getVenueState().users().ensure(ALICE_DID);
	}

	private long metaField(String field) {
		ACell v = RT.getIn(user.cursor().get(), "meta", field);
		return (v instanceof CVMLong l) ? l.longValue() : -1;
	}

	/** Advances the harness write clock past the current stamp. This test
	 *  mutates the lattice directly (no JobManager dispatch), so it drives
	 *  the clock policy explicitly — real traffic gets this per dispatch. */
	private void tick() {
		try { Thread.sleep(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		engine.refreshWriteClock();
	}

	@Test
	public void testCreatedMintedOnFirstActivity() {
		// ensure() alone is not activity — only writes INTO the record pass
		// the stamping boundary. The first real write mints created == updated.
		assertEquals(-1, metaField("created"), "an ensured-but-unused identity has no meta");

		user.ensureAgent("first-activity-agent", Maps.empty(), null);
		long created = metaField("created");
		long updated = metaField("updated");
		assertTrue(created > 0, "meta.created minted on first activity");
		assertEquals(created, updated, "first activity: created == updated");
	}

	@Test
	public void testDeepAgentWriteBumpsUpdatedNotCreated() {
		user.ensureAgent("meta-agent", Maps.empty(), null);
		long created = metaField("created");
		long before = metaField("updated");
		assertTrue(created > 0);
		tick();

		// Wrappers capture the write clock when derived — mint one AFTER the
		// clock advance, exactly as real traffic does (per-access derivation
		// after the dispatch refresh).
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("meta-agent");
		agent.setStatus(AgentState.SUSPENDED);

		assertTrue(metaField("updated") > before,
			"a deep agent write must bump meta.updated");
		assertEquals(created, metaField("created"),
			"meta.created is minted once and never changes");
	}

	@Test
	public void testDeepSessionFrameWriteBumps() {
		user.ensureAgent("frame-agent", Maps.empty(), null).ensureSession(
			Blob.fromHex("dd112233445566778899aabbccddee01"), ALICE_DID);
		Blob sid = Blob.fromHex("dd112233445566778899aabbccddee01");
		long before = metaField("updated");
		tick();

		// A frame write four levels down the subtree still refreshes the
		// user-level stamp (StampedCursor deep-write re-stamp). Fresh wrapper
		// after the clock advance, as real per-access derivation gives.
		AgentState agent = engine.getVenueState().users().get(ALICE_DID).agent("frame-agent");
		assertTrue(agent.updateSessionFrames(sid, null,
			frames -> frames.conj(Maps.of(Strings.create("description"), Strings.create("x")))));
		assertTrue(metaField("updated") > before,
			"a deep frame write must bump meta.updated");
	}

	@Test
	public void testReadDoesNotBump() {
		AgentState agent = user.ensureAgent("read-agent", Maps.empty(), null);
		long before = metaField("updated");
		tick();

		agent.getRecord();
		agent.getTasks();
		user.getAgents();
		engine.getVenueState().users().get(ALICE_DID).cursor().get();

		assertEquals(before, metaField("updated"), "reads must not bump meta.updated");
	}

	@Test
	public void testUnchangedWriteDoesNotBump() {
		AgentState agent = user.ensureAgent("noop-agent", Maps.empty(), null);
		agent.setStatus(AgentState.SLEEPING);   // already SLEEPING at creation
		long before = metaField("updated");
		tick();

		agent.setStatus(AgentState.SLEEPING);   // no-change write
		assertEquals(before, metaField("updated"),
			"an unchanged write must not bump meta.updated");
	}

	@Test
	public void testMetaReadableViaOrdinaryReadPath() {
		// The doc promises meta is readable (covia:read <did>/meta) though
		// framework-owned (not a writable namespace).
		user.ensureAgent("readable-agent", Maps.empty(), null);
		ACell result = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("meta")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		assertTrue(RT.getIn(result, "value", "updated") instanceof CVMLong,
			"meta must be readable via covia:read: " + result);
	}

	@Test
	public void testJobPersistenceBumps() {
		// A plain invoke persists a job record under the caller's j/ — even
		// "pure compute" activity refreshes the user's stamp.
		long before = metaField("updated");
		tick();

		engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("data"), Strings.create("hi")),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		assertTrue(metaField("updated") > before,
			"job persistence must bump meta.updated");
	}
}
