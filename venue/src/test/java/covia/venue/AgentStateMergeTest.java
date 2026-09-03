package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * The run loop's state write-back is a merge of the transition's change
 * against the snapshot it fired with, applied inside the record's CAS —
 * never a replacement of a value read minutes earlier. This is what lets
 * {@code agent:update} land during a transition without being clobbered.
 */
public class AgentStateMergeTest {

	private static CVMLong n(long v) {
		return CVMLong.create(v);
	}

	@Test
	public void untouchedKeysKeepTheCurrentValueChangedKeysWin() {
		AMap<AString, ACell> snapshot = Maps.of("a", 1, "b", 1, "c", 1);
		// An external update landed while the transition ran: b changed, d added.
		AMap<AString, ACell> current = Maps.of("a", 1, "b", 2, "c", 1, "d", 4);
		// The transition changed a, dropped c, left b alone.
		AMap<AString, ACell> returned = Maps.of("a", 9, "b", 1);

		ACell merged = AgentState.applyStateChange(current, snapshot, returned);
		assertEquals(n(9), RT.getIn(merged, "a"), "changed by the transition: its value");
		assertEquals(n(2), RT.getIn(merged, "b"), "untouched by the transition: the external update survives");
		assertNull(RT.getIn(merged, "c"), "dropped by the transition: removed");
		assertEquals(n(4), RT.getIn(merged, "d"), "added externally: survives");
	}

	@Test
	public void sameKeyConflictGoesToTheTransition() {
		ACell merged = AgentState.applyStateChange(
			Maps.of("a", 5), Maps.of("a", 1), Maps.of("a", 9));
		assertEquals(n(9), RT.getIn(merged, "a"));
	}

	@Test
	public void noResultIsNoChangeAndNonMapsReplace() {
		AMap<AString, ACell> current = Maps.of("a", 5);
		assertSame(current, AgentState.applyStateChange(current, Maps.of("a", 1), null));
		assertEquals(Strings.create("blob"), AgentState.applyStateChange(current, Maps.of("a", 1), Strings.create("blob")));
		AMap<AString, ACell> returned = Maps.of("a", 9);
		assertEquals(returned, AgentState.applyStateChange(null, Maps.of("a", 1), returned));
		assertEquals(returned, AgentState.applyStateChange(current, null, returned));
	}

	@Test
	public void noConcurrentChangeReproducesTheTransitionResultExactly() {
		AMap<AString, ACell> snapshot = Maps.of("a", 1, "b", 1);
		AMap<AString, ACell> returned = Maps.of("a", 2, "c", 3);
		assertEquals(returned, AgentState.applyStateChange(snapshot, snapshot, returned));
	}

	@Test
	public void mergeRunResultAppliesTheChangeInsideTheRecordCas() {
		AString did = TestEngine.uniqueDID("state-merge-seam");
		AgentState agent = TestEngine.ENGINE.getVenueState().users().ensure(did)
			.ensureAgent(Strings.create("merge-seam"), Maps.empty(), Maps.of("a", 1, "b", 1));
		ACell snapshot = agent.getState();               // what the transition fired with

		// agent:update lands while the transition runs
		agent.updateConfigAndState(null, Maps.of("b", 2, "external", 7));

		// the transition returns its own view: a changed, b as it saw it
		agent.mergeRunResult(snapshot, Maps.of("a", 9, "b", 1), null,
			Maps.of("op", "test-cycle"), null, null, 0, null, null);

		ACell state = agent.getState();
		assertEquals(n(9), RT.getIn(state, "a"));
		assertEquals(n(2), RT.getIn(state, "b"), "the transition left b alone: the update's value stands");
		assertEquals(n(7), RT.getIn(state, "external"));
	}
}
