package covia.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;

/**
 * Merge semantics of the user-writable namespaces ({@code w/}, {@code o/},
 * {@code h/}) — regression for GetMine-ai/demo#134.
 *
 * <p>The previous {@code MapLattice(JSONValueLattice)} merged per-entry: a
 * union, which re-introduced a deleted key whenever the live cursor was
 * merged with any pre-delete snapshot (the NodeServer propagator does this
 * on every announce round-trip — observed as a 43→41→43 count oscillation
 * and deletes that "came back" after restart). A join semilattice cannot
 * express removal per-entry; these namespaces are now whole
 * {@code {updated, data}} values replaced as a unit under LWW, the same
 * trade the {@code :schedule} slot made (see {@code ScheduleSlotMergeTest}).</p>
 *
 * <p>This drives the actual namespace lattice ({@code Covia.USER.path("w")})
 * with whole wrapper values and asserts the higher-stamp value wins
 * wholesale — deletion preserved — regardless of argument order.</p>
 */
public class LWWWrapperLatticeTest {

	/** The workspace namespace's merge lattice, straight from the schema. */
	private static final ALattice<ACell> W = Covia.USER.path(Strings.create("w"));

	private static ACell data(Object... kv) {
		return Maps.of(kv);
	}

	@Test
	public void testHigherStampDeletionSurvivesMerge() {
		ACell hasKey  = LWWWrapperLattice.wrap(1, data("ctx", data("a", 1, "b", 2)));
		ACell deleted = LWWWrapperLattice.wrap(2, data("ctx", data("a", 1)));

		assertSame(deleted, W.merge(hasKey, deleted),
			"deletion (higher stamp) must survive merge when deleted is 'other'");
		assertSame(deleted, W.merge(deleted, hasKey),
			"deletion (higher stamp) must survive merge when deleted is 'own'");
	}

	/** Guards against regressing to a per-entry union (the original bug shape). */
	@Test
	public void testMergeIsNotAUnion() {
		ACell left  = LWWWrapperLattice.wrap(1, data("k1", 1, "k2", 2));
		ACell right = LWWWrapperLattice.wrap(2, data("k1", 1));

		ACell merged = LWWWrapperLattice.unwrap(W.merge(left, right));
		assertNull(convex.core.lang.RT.getIn(merged, "k2"),
			"a union merge would re-introduce the removed key — it must not");
	}

	/** Equal stamps prefer the own (local) value — same policy as LWWLattice. */
	@Test
	public void testTiePrefersOwn() {
		ACell x = LWWWrapperLattice.wrap(5, data("k", 1));
		ACell y = LWWWrapperLattice.wrap(5, data("k", 2));

		assertSame(x, W.merge(x, y));
		assertSame(y, W.merge(y, x));
	}

	/**
	 * Legacy values (raw maps persisted before the wrapper existed) stamp
	 * as 0, so any stamped write dominates them — in-place migration.
	 */
	@Test
	public void testLegacyRawMapLosesToStampedWrapper() {
		ACell legacy  = data("old", 42);
		ACell wrapped = LWWWrapperLattice.wrap(1, data("new", 1));

		assertSame(wrapped, W.merge(legacy, wrapped));
		assertSame(wrapped, W.merge(wrapped, legacy));
	}

	@Test
	public void testNullMerge() {
		ACell v = LWWWrapperLattice.wrap(1, data("k", 1));
		assertSame(v, W.merge(null, v));
		assertSame(v, W.merge(v, null));
	}

	// ========== Wrapper shape helpers ==========

	@Test
	public void testWrapperShapeIsStrict() {
		assertTrue(LWWWrapperLattice.isWrapper(LWWWrapperLattice.wrap(1, data("k", 1))));

		// Legacy raw map — not a wrapper, passes through unwrap unchanged
		ACell legacy = data("updated", "not-a-long", "data", 1);
		assertFalse(LWWWrapperLattice.isWrapper(legacy), "updated must be an integer");
		assertSame(legacy, LWWWrapperLattice.unwrap(legacy));

		ACell threeKeys = data("updated", 1, "data", 2, "extra", 3);
		assertFalse(LWWWrapperLattice.isWrapper(threeKeys), "wrapper has exactly two entries");

		assertFalse(LWWWrapperLattice.isWrapper(data("a", 1)));
		assertFalse(LWWWrapperLattice.isWrapper(CVMLong.ONE));
		assertFalse(LWWWrapperLattice.isWrapper(null));
	}

	@Test
	public void testWrapUnwrapRoundTrip() {
		ACell d = data("k", 1);
		assertSame(d, LWWWrapperLattice.unwrap(LWWWrapperLattice.wrap(7, d)));
		assertEquals(7, LWWWrapperLattice.timestamp(LWWWrapperLattice.wrap(7, d)));
		assertEquals(0, LWWWrapperLattice.timestamp(d), "legacy values stamp as 0");
		assertEquals(Maps.empty(), LWWWrapperLattice.unwrap(LWWWrapperLattice.wrap(1, null)),
			"null data normalises to an empty map so the stamp still dominates");
	}
}
