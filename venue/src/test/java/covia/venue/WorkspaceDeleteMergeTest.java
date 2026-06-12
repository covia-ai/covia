package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.ALatticeCursor;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.lattice.LWWWrapperLattice;

/**
 * Regression for GetMine-ai/demo#134: {@code covia:delete} must be durable.
 *
 * <h2>Assumptions under test</h2>
 * <ul>
 *   <li>A deleted workspace entry stays deleted after the live cursor is
 *       merged with a pre-delete snapshot. The NodeServer propagator performs
 *       exactly such a merge on every announce round-trip
 *       ({@code cursor.updateAndGet(current -> lattice.merge(current, persisted))}),
 *       which with the old per-entry union merge re-introduced deleted keys —
 *       observed live as a 43→41→43 count oscillation and deletes that
 *       "came back" in a fresh process. These tests replay that exact merge
 *       vector at the user lattice level.</li>
 *   <li>The {@code {updated, data}} wrapper is storage shape only: readers
 *       see the namespace's data, paths address inside it, and user keys
 *       named {@code "data"} or {@code "updated"} never collide with it.</li>
 *   <li>Workspace values persisted before the wrapper existed (legacy raw
 *       maps) remain readable and are migrated in place by the first
 *       stamped write.</li>
 * </ul>
 */
public class WorkspaceDeleteMergeTest {

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

	private ALatticeCursor<ACell> userCursor() {
		return engine.getVenueState().users().ensure(DID).cursor();
	}

	/**
	 * Replays the propagator's merge-back with a stale snapshot — the same
	 * call shape as {@code NodeServer.setMergeCallback}:
	 * {@code cursor.updateAndGet(current -> lattice.merge(current, persisted))}.
	 * The snapshot plays the "persisted" role.
	 */
	@SuppressWarnings("unchecked")
	private void mergeBack(ACell snapshot) {
		userCursor().updateAndGet(current -> (ACell) Covia.USER.merge(
			(AHashMap<AString, ACell>) current, (AHashMap<AString, ACell>) snapshot));
	}

	// ========== Deletion durability across the merge-back ==========

	/** The #134 shape: nested context entries, one deleted, snapshot merged back. */
	@Test
	public void testNestedDeleteSurvivesMergeBack() {
		write("w/health-context/keep", CVMLong.create(1));
		write("w/health-context/remove", CVMLong.create(2));

		ACell preDelete = userCursor().get();
		delete("w/health-context/remove");
		mergeBack(preDelete);

		assertEquals(CVMBool.FALSE, RT.getIn(read("w/health-context/remove"), "exists"),
			"deleted entry must not re-materialise after merge with a pre-delete snapshot");
		assertEquals(CVMLong.create(1), RT.getIn(read("w/health-context/keep"), "value"));
		assertEquals(CVMLong.create(1), RT.getIn(list("w/health-context"), "count"));
	}

	@Test
	public void testTopLevelDeleteSurvivesMergeBack() {
		write("w/k1", CVMLong.create(1));
		write("w/k2", CVMLong.create(2));

		ACell preDelete = userCursor().get();
		delete("w/k2");
		mergeBack(preDelete);

		ACell keys = RT.getIn(list("w"), "keys");
		assertTrue(((AVector<?>) keys).contains(Strings.create("k1")));
		assertFalse(((AVector<?>) keys).contains(Strings.create("k2")),
			"top-level delete must survive the merge-back");
	}

	/** Deleting the last key leaves a stamped empty namespace that still dominates. */
	@Test
	public void testDeleteOfLastKeySurvivesMergeBack() {
		write("w/only", CVMLong.create(1));

		ACell preDelete = userCursor().get();
		delete("w/only");
		mergeBack(preDelete);

		assertEquals(CVMBool.FALSE, RT.getIn(read("w/only"), "exists"));
		assertEquals(CVMLong.ZERO, RT.getIn(list("w"), "count"),
			"the namespace must converge to empty, not oscillate back");
	}

	/** Repeated delete→merge cycles converge (the live bug never converged). */
	@Test
	public void testBulkDeleteConverges() {
		for (int i = 0; i < 5; i++) {
			write("w/bulk/e" + i, CVMLong.create(i));
		}
		for (int i = 0; i < 5; i++) {
			ACell snapshot = userCursor().get();
			delete("w/bulk/e" + i);
			mergeBack(snapshot);
		}
		assertEquals(CVMLong.ZERO, RT.getIn(list("w/bulk"), "count"));
	}

	// ========== Wrapper transparency ==========

	@Test
	public void testWrapperIsInvisibleToReaders() {
		write("w/visible", CVMLong.create(1));

		ACell ws = RT.getIn(read("w"), "value");
		assertNotNull(ws);
		assertEquals(CVMLong.create(1), RT.getIn(ws, "visible"));
		assertNull(RT.getIn(ws, "data"), "wrapper internals must not leak into reads");
		assertNull(RT.getIn(ws, "updated"), "wrapper internals must not leak into reads");

		// The stored value IS the wrapper — storage shape, not API shape
		assertTrue(LWWWrapperLattice.isWrapper(RT.getIn(userCursor().get(), "w")));
	}

	/** User keys named like the wrapper fields live inside data — no collision. */
	@Test
	public void testUserKeysNamedLikeWrapperFields() {
		write("w/data/inner", CVMLong.create(2));
		write("w/updated", CVMLong.create(7));

		assertEquals(CVMLong.create(2), RT.getIn(read("w/data/inner"), "value"));
		assertEquals(CVMLong.create(7), RT.getIn(read("w/updated"), "value"));

		delete("w/data/inner");
		assertEquals(CVMBool.FALSE, RT.getIn(read("w/data/inner"), "exists"));
		assertEquals(CVMLong.create(7), RT.getIn(read("w/updated"), "value"));
	}

	/** The o/ namespace shares the same lattice and semantics. */
	@Test
	public void testOperationsNamespaceDeleteSurvivesMergeBack() {
		write("o/myop", Maps.of("name", "My Op"));

		ACell preDelete = userCursor().get();
		delete("o/myop");
		mergeBack(preDelete);

		assertEquals(CVMBool.FALSE, RT.getIn(read("o/myop"), "exists"));
	}

	// ========== Strictly-increasing stamps (same-millisecond writes) ==========

	/** Reads the namespace wrapper's LWW stamp directly from storage. */
	private long wStamp() {
		return LWWWrapperLattice.timestamp(RT.getIn(userCursor().get(), "w"));
	}

	/**
	 * Fast sequential writes must each strictly dominate the value they
	 * replace ({@code max(now, current+1)}). With a plain wall-clock stamp,
	 * two ops in the same millisecond tie — LWW resolves ties in favour of
	 * "own" (the side applying a local edit), which is correct, but a
	 * sequential lineage should be totally ordered and never depend on
	 * tie-breaks at all. A burst of ops lands well inside one millisecond,
	 * so strict monotonicity is what makes "the last write applied wins"
	 * unconditional.
	 */
	@Test
	public void testFastSequentialWritesStampStrictlyMonotonic() {
		long previous = Long.MIN_VALUE;
		for (int i = 0; i < 50; i++) {
			write("w/seq", CVMLong.create(i));
			long stamp = wStamp();
			assertTrue(stamp > previous,
				"stamp must strictly increase on every write (iteration " + i + ")");
			previous = stamp;
		}
	}

	/**
	 * The deterministic same-millisecond case: force the stored stamp ahead
	 * of the wall clock (as a same-ms predecessor or clock skew would), then
	 * delete. The delete must stamp {@code current+1} — not {@code now} —
	 * so it strictly dominates the pre-delete snapshot and the merge outcome
	 * is independent of argument position (no reliance on tie-breaks).
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testSameMillisecondDeleteDominatesInBothMergeOrders() {
		write("w/k", CVMLong.create(1));

		// Push the namespace stamp into the future, keeping the data
		long future = System.currentTimeMillis() + 3_600_000;
		userCursor().updateAndGet(current -> {
			AHashMap<AString, ACell> u = (AHashMap<AString, ACell>) current;
			ACell data = LWWWrapperLattice.unwrap(u.get(Strings.create("w")));
			return (ACell) u.assoc(Strings.create("w"), LWWWrapperLattice.wrap(future, data));
		});

		ACell preDelete = RT.getIn(userCursor().get(), "w");
		delete("w/k");
		ACell postDelete = RT.getIn(userCursor().get(), "w");

		assertEquals(future + 1, LWWWrapperLattice.timestamp(postDelete),
			"a write behind the stored stamp must take current+1, not wall-clock");

		var wLattice = Covia.USER.path(Strings.create("w"));
		assertEquals(postDelete, wLattice.merge(preDelete, postDelete),
			"delete must win the merge as 'other'");
		assertEquals(postDelete, wLattice.merge(postDelete, preDelete),
			"delete must win the merge as 'own'");
	}

	// ========== Legacy (pre-wrapper) data ==========

	/**
	 * Simulates a workspace persisted before the wrapper existed: a raw map
	 * stored directly at {@code w}. It must stay readable, and the first
	 * stamped write must migrate it in place without losing entries.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testLegacyRawNamespaceReadableAndMigratedOnWrite() {
		ACell legacy = Maps.of("old", CVMLong.create(42));
		userCursor().updateAndGet(current ->
			(ACell) ((AHashMap<AString, ACell>) current).assoc(Strings.create("w"), legacy));

		assertEquals(CVMLong.create(42), RT.getIn(read("w/old"), "value"),
			"legacy unwrapped data must remain readable");

		write("w/new", CVMLong.create(1));

		assertTrue(LWWWrapperLattice.isWrapper(RT.getIn(userCursor().get(), "w")),
			"first write migrates the namespace to the wrapper");
		assertEquals(CVMLong.create(42), RT.getIn(read("w/old"), "value"),
			"migration must preserve legacy entries");
		assertEquals(CVMLong.create(1), RT.getIn(read("w/new"), "value"));
	}
}
