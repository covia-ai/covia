package covia.adapter.agent;

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
 * The context scope chain's lexical semantics (#142): union outer→inner,
 * inner shadows outer, nil tombstones mask, mutation targets the innermost
 * tier only. Pure functions — no engine needed.
 */
public class ContextChainTest {

	private static final AString A = Strings.create("w/a");
	private static final AString B = Strings.create("w/b");
	private static final AString C = Strings.create("w/c");

	private static AMap<AString, ACell> spec(long budget) {
		return Maps.of(Strings.create("budget"), CVMLong.create(budget));
	}

	@Test
	public void testUnionAndShadowing() {
		AMap<AString, ACell> outer = Maps.of(A, spec(100), B, spec(200));
		AMap<AString, ACell> inner = Maps.of(B, spec(999), C, spec(300));

		AMap<AString, ACell> eff = ContextChain.effective(outer, inner);
		assertEquals(3, eff.count());
		assertEquals(spec(100), eff.get(A));
		assertEquals(spec(999), eff.get(B), "inner shadows outer");
		assertEquals(spec(300), eff.get(C));

		// Null tiers are skipped; order matters.
		assertEquals(spec(200), ContextChain.effective(null, outer).get(B));
	}

	@Test
	public void testTombstoneMasks() {
		AMap<AString, ACell> outer = Maps.of(A, spec(100), B, spec(200));
		AMap<AString, ACell> inner = Maps.of(A, (ACell) null); // tombstone

		AMap<AString, ACell> eff = ContextChain.effective(outer, inner);
		assertEquals(1, eff.count(), "masked path excluded: " + eff);
		assertNull(eff.get(A));
		assertEquals(spec(200), eff.get(B));

		// A third tier re-loading the masked path un-masks from there inward.
		AMap<AString, ACell> innermost = Maps.of(A, spec(50));
		assertEquals(spec(50), ContextChain.effective(outer, inner, innermost).get(A));
	}

	@Test
	public void testUnloadLocalEntry() {
		AMap<AString, ACell> inner = Maps.of(A, spec(100));
		AMap<AString, ACell> updated = ContextChain.unload(inner, Maps.empty(), A);
		assertNotNull(updated);
		assertEquals(0, updated.count(), "locally-owned entry plainly removed, no tombstone litter");
	}

	@Test
	public void testUnloadMasksOuterEntry() {
		AMap<AString, ACell> outer = Maps.of(A, spec(100));
		AMap<AString, ACell> updated = ContextChain.unload(Maps.empty(), outer, A);
		assertNotNull(updated);
		assertTrue(updated.containsKey(A), "tombstone written");
		assertNull(updated.get(A));
		assertEquals(0, ContextChain.effective(outer, updated).count(), "outer entry masked");
	}

	@Test
	public void testUnloadLocalEntryShadowingOuterLeavesTombstone() {
		// Loaded locally AND supplied outside: unload must mask, not reveal outer.
		AMap<AString, ACell> outer = Maps.of(A, spec(100));
		AMap<AString, ACell> inner = Maps.of(A, spec(999));
		AMap<AString, ACell> updated = ContextChain.unload(inner, outer, A);
		assertNotNull(updated);
		assertNull(updated.get(A));
		assertTrue(updated.containsKey(A), "intent holds: still masked from outer");
	}

	@Test
	public void testUnloadNotInContextIsError() {
		assertNull(ContextChain.unload(Maps.empty(), Maps.empty(), A));
		// Already masked → not in effective context → error again (idempotent).
		AMap<AString, ACell> outer = Maps.of(A, spec(100));
		AMap<AString, ACell> masked = ContextChain.unload(Maps.empty(), outer, A);
		assertNull(ContextChain.unload(masked, outer, A));
	}

	@Test
	public void testLoadOverwritesTombstone() {
		AMap<AString, ACell> outer = Maps.of(A, spec(100));
		AMap<AString, ACell> masked = ContextChain.unload(Maps.empty(), outer, A);
		// context_load writes to the innermost tier — un-masks locally.
		AMap<AString, ACell> reloaded = masked.assoc(A, spec(300));
		assertEquals(spec(300), ContextChain.effective(outer, reloaded).get(A));
	}

	@Test
	public void testDeclaredLoadsValidation() {
		assertEquals(0, ContextChain.declaredLoads(null, "config.loads").count());
		AMap<AString, ACell> ok = ContextChain.declaredLoads(Maps.of(A, spec(100)), "config.loads");
		assertEquals(1, ok.count());
		assertEquals(256L, ((CVMLong) RT.getIn(ok.get(A), "budget")).longValue(),
			"declared and dynamic loads use the same advisory normalisation");
		assertNull(RT.getIn(ok.get(A), "ts"),
			"rendering a stable config declaration must not invent a fresh timestamp");
		AMap<AString, ACell> minted = ContextChain.declaredLoads(
			Maps.of(A, Maps.empty()), "loads", true);
		assertNotNull(RT.getIn(minted.get(A), "ts"),
			"persistence boundaries stamp missing timestamps once");

		// Non-map declaration or non-map spec: loud, never silently dropped.
		assertThrows(IllegalArgumentException.class,
			() -> ContextChain.declaredLoads(Strings.create("w/a"), "config.loads"));
		assertThrows(IllegalArgumentException.class,
			() -> ContextChain.declaredLoads(Maps.of(A, CVMLong.create(5)), "config.loads"));
	}
}
