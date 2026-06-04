package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALattice;
import covia.lattice.Covia;

/**
 * Regression for GRID_SCHEDULER.md §8: the {@code :schedule} slot is replaced
 * <i>as a unit</i>, so a removal survives the fork→merge path that
 * {@code syncState} takes when a concurrent persistence sweep advances the
 * parent. The earlier {@code IndexLattice(LWW)} slot merged per-entry (a
 * union), which silently re-introduced a cancelled/fired event — a bug the
 * in-process unit tests missed because they never run the merge, and which only
 * surfaced under live MCP traffic.
 *
 * <p>This drives the actual slot lattice ({@code Covia.VENUE.path(SCHEDULE)})
 * with two whole {@code {updated, events}} values and asserts the higher-stamp
 * value wins wholesale (deletion preserved) regardless of argument order.</p>
 */
public class ScheduleSlotMergeTest {

	private static final AString K_UP = Scheduler.K_UPDATED;
	private static final AString K_EV = Scheduler.K_EVENTS;

	/** The {@code :schedule} slot's merge lattice. */
	private static final ALattice<ACell> SCHED = Covia.VENUE.path(Covia.SCHEDULE);

	/** A 16-byte event handle: 8-byte big-endian time + 8 arbitrary bytes. */
	private static final Blob KEY = Blob.fromHex("00000000000003e80011223344556677");

	private static ACell wrap(long stamp, Index<Blob, ACell> events) {
		return Maps.of(K_UP, CVMLong.create(stamp), K_EV, events);
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> eventsOf(ACell wrapper) {
		return (Index<Blob, ACell>) ((AMap<AString, ACell>) wrapper).get(K_EV);
	}

	private static Index<Blob, ACell> withEvent() {
		AMap<AString, ACell> rec = Maps.of(
			Scheduler.K_OP, Strings.intern("v/test/ops/echo"),
			Scheduler.K_TIME, CVMLong.create(1000));
		return Index.of(KEY, rec);
	}

	/**
	 * Parent still holds the event (stamp 1); the fork removed it (stamp 2).
	 * The higher-stamp fork must win wholesale — the event stays gone — whichever
	 * side is "own" in the merge (the fork-sync slow path and its CAS fallback
	 * call merge with different argument orders).
	 */
	@Test
	public void testHigherStampDeletionSurvivesMerge() {
		ACell hasEvent = wrap(1, withEvent());
		ACell removed  = wrap(2, Index.none());

		assertEquals(0, eventsOf(SCHED.merge(hasEvent, removed)).count(),
			"deletion (higher stamp) must survive merge when removed is 'other'");
		assertEquals(0, eventsOf(SCHED.merge(removed, hasEvent)).count(),
			"deletion (higher stamp) must survive merge when removed is 'own'");
	}

	/** Symmetric sanity: a higher-stamp addition wins wholesale too. */
	@Test
	public void testHigherStampAdditionWinsMerge() {
		ACell empty    = wrap(1, Index.none());
		ACell hasEvent = wrap(2, withEvent());

		assertEquals(1, eventsOf(SCHED.merge(empty, hasEvent)).count());
		assertEquals(1, eventsOf(SCHED.merge(hasEvent, empty)).count());
	}

	/** Guards against regressing to a per-entry union (the original bug shape). */
	@Test
	public void testMergeIsNotAUnion() {
		ACell hasEvent = wrap(1, withEvent());
		ACell removed  = wrap(2, Index.none());
		assertTrue(eventsOf(SCHED.merge(hasEvent, removed)).isEmpty(),
			"a union merge would re-introduce the removed key — it must not");
	}
}
