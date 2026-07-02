package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.lattice.Covia;

/**
 * MECE deletion-durability tests for the whole-{@code :state} LWW venue model.
 *
 * <p>Deletion durability is the reason the venue's mutable state was consolidated
 * under a single navigable whole-value-LWW {@code :state} region. The propagator
 * merges a persisted snapshot back into the live venue on every announce
 * round-trip ({@code cursor.updateAndGet(current -> lattice.merge(current, persisted))},
 * see {@code NodeServer.setMergeCallback}). Under the previous per-entry union
 * merge a deleted key resurrected on that merge-back; under whole-value LWW the
 * newer (live) {@code :state} wins wholesale, so deletions survive.</p>
 *
 * <p>Each test replays that merge-back at the venue level with a <b>stale
 * pre-delete snapshot</b> ({@code own = live current}, {@code other = persisted})
 * and asserts the deletion is not undone. Coverage spans the regions where hard
 * deletion was a live or latent bug — user workspace (GetMine-ai/demo#134) and
 * agent hard-delete; operations/secrets/per-user data share the same
 * {@code :state} merge and are covered transitively. An addition case guards the
 * dual property (a write is not lost when an older snapshot merges back).</p>
 */
public class DeletionDurabilityTest {

	private final Engine engine = TestEngine.ENGINE;

	private AString DID;
	private RequestContext CTX;

	@BeforeEach
	public void setup(TestInfo info) {
		DID = TestEngine.uniqueDID(info);
		CTX = RequestContext.of(DID);
	}

	// ========== covia-op helpers ==========

	private void write(String path, ACell value) {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, path, Fields.VALUE, value), CTX).awaitResult(5000);
	}

	private void delete(String path) {
		engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, path), CTX).awaitResult(5000);
	}

	private ACell read(String path) {
		return engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, path), CTX).awaitResult(5000);
	}

	private ACell list(String path) {
		return engine.jobs().invokeOperation("v/ops/covia/list",
			Maps.of(Fields.PATH, path), CTX).awaitResult(5000);
	}

	/** Snapshot of the whole venue {@code :value}, as the propagator would persist. */
	private ACell venueSnapshot() {
		return engine.getVenueState().cursor().get();
	}

	/**
	 * Replays the propagator's merge-back: merge a (stale) persisted snapshot
	 * into the live venue value via the venue lattice, exactly as
	 * {@code NodeServer.setMergeCallback} does — {@code own = live current},
	 * {@code other = persisted}. Whole-value LWW at {@code :state} keeps the
	 * live (newer) value, so a deletion applied after the snapshot survives.
	 *
	 * <p>Safe on the shared test engine: the live value is always newer than a
	 * prior snapshot, so the merge is effectively a no-op on live state (it
	 * cannot revert another parallel test's writes).</p>
	 */
	private void mergeBack(ACell staleSnapshot) {
		engine.getVenueState().cursor().updateAndGet(current ->
			Covia.VENUE.merge(current, staleSnapshot));
	}

	// ========== Workspace deletion durability (GetMine-ai/demo#134) ==========

	@Test
	public void testNestedWorkspaceDeleteSurvivesMergeBack() {
		write("w/health-context/keep", CVMLong.create(1));
		write("w/health-context/remove", CVMLong.create(2));

		ACell preDelete = venueSnapshot();
		delete("w/health-context/remove");
		mergeBack(preDelete);

		assertEquals(CVMBool.FALSE, RT.getIn(read("w/health-context/remove"), "exists"),
			"a deleted nested key must not re-materialise after a stale merge-back");
		assertEquals(CVMLong.create(1), RT.getIn(read("w/health-context/keep"), "value"),
			"the sibling key must remain intact");
	}

	@Test
	public void testTopLevelWorkspaceDeleteSurvivesMergeBack() {
		write("w/k1", CVMLong.create(1));
		write("w/k2", CVMLong.create(2));

		ACell preDelete = venueSnapshot();
		delete("w/k2");
		mergeBack(preDelete);

		AVector<?> keys = (AVector<?>) RT.getIn(list("w"), "keys");
		assertTrue(keys.contains(Strings.create("k1")), "surviving key present");
		assertFalse(keys.contains(Strings.create("k2")),
			"top-level delete must survive the merge-back");
	}

	@Test
	public void testRepeatedDeleteMergeConverges() {
		for (int i = 0; i < 5; i++) write("w/bulk/e" + i, CVMLong.create(i));
		for (int i = 0; i < 5; i++) {
			ACell snap = venueSnapshot();
			delete("w/bulk/e" + i);
			mergeBack(snap);
		}
		assertEquals(CVMLong.ZERO, RT.getIn(list("w/bulk"), "totalSize"),
			"repeated delete+merge must converge to empty, not oscillate back");
	}

	// ========== Addition durability (the dual property) ==========

	@Test
	public void testAdditionSurvivesStaleMergeBack() {
		ACell preWrite = venueSnapshot();   // snapshot BEFORE the write
		write("w/added", CVMLong.create(42));
		mergeBack(preWrite);                 // merge the pre-write snapshot back

		assertEquals(CVMLong.create(42), RT.getIn(read("w/added"), "value"),
			"a write must survive a merge-back of a snapshot that predates it");
	}

	// ========== Secret deletion durability (#166) ==========

	@Test
	public void testSecretDeleteSurvivesMergeBack() {
		engine.jobs().invokeOperation("v/ops/secret/set",
			Maps.of(Fields.NAME, "revoked-token", Fields.VALUE, Strings.create("sk-old")),
			CTX).awaitResult(5000);

		ACell preDelete = venueSnapshot();
		delete("s/revoked-token");
		mergeBack(preDelete);

		assertFalse(engine.getVenueState().users().get(DID).secrets().exists("revoked-token"),
			"a deleted secret must not resurrect after a stale merge-back");
	}

	// ========== Agent hard-delete durability (the latent bug) ==========

	@Test
	public void testAgentHardDeleteSurvivesMergeBack() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "dur-agent"), CTX).awaitResult(5000);

		ACell preDelete = venueSnapshot();
		ACell result = engine.jobs().invokeOperation("v/ops/agent/delete",
			Maps.of(Fields.AGENT_ID, "dur-agent", Fields.REMOVE, CVMBool.TRUE), CTX).awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, Fields.REMOVED), "hard delete reported");

		mergeBack(preDelete);

		assertNull(engine.getVenueState().users().get(DID).agent("dur-agent"),
			"a hard-deleted agent must not resurrect after a stale merge-back");
	}
}
