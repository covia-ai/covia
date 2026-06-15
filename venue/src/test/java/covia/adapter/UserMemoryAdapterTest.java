package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.agent.ContextLoader;
import covia.exception.JobFailedException;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for the UserMemoryAdapter — one tool ({@code v/ops/memory}) dispatched by
 * a {@code command} (recall | remember | update | forget) over a numbered memory
 * list. Covers the command surface, recall over both a flat list and a gated map
 * collection, and the context-format path (recall as a config.context assemble-op).
 *
 * <p>Uses the shared {@link TestEngine#ENGINE}; each test gets its own user DID so
 * the default {@code w/memory} list is fresh per test.</p>
 */
public class UserMemoryAdapterTest {

	private static final String OP = "v/ops/memory";
	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ALICE;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private ACell run(AMap<AString, ACell> input) {
		return engine.jobs().invokeOperation(OP, input, ALICE).awaitResult(5000);
	}

	private ACell remember(String text) {
		return run(Maps.of(Strings.create("command"), Strings.create("remember"),
			Strings.create("text"), Strings.create(text)));
	}

	private ACell recall() {
		return run(Maps.of(Strings.create("command"), Strings.create("recall"),
			Strings.create("path"), Strings.create("w/memory")));
	}

	// ========== remember + recall (numbered render) ==========

	@Test
	public void testRememberAndRecallNumbered() {
		assertEquals(CVMLong.create(1), RT.getIn(remember("Prefers metric units"), "n"));
		assertEquals(CVMLong.create(2), RT.getIn(remember("Anxious about heart"), "count"));
		remember("Lactose intolerant");

		ACell block = recall();
		assertNotNull(block);
		assertEquals("1. Prefers metric units\n2. Anxious about heart\n3. Lactose intolerant",
			block.toString());
	}

	// ========== update / forget by number ==========

	@Test
	public void testUpdateByNumber() {
		remember("a");
		remember("b");
		remember("c");

		ACell res = run(Maps.of(Strings.create("command"), Strings.create("update"),
			Strings.create("n"), CVMLong.create(2), Strings.create("text"), Strings.create("B-updated")));
		assertEquals(CVMLong.create(2), RT.getIn(res, "n"));

		assertEquals("1. a\n2. B-updated\n3. c", recall().toString());
	}

	@Test
	public void testForgetRenumbers() {
		remember("a");
		remember("b");
		remember("c");

		ACell res = run(Maps.of(Strings.create("command"), Strings.create("forget"),
			Strings.create("n"), CVMLong.create(2)));
		assertEquals(CVMLong.create(2), RT.getIn(res, "count"), "two items remain");

		assertEquals("1. a\n2. c", recall().toString());
	}

	@Test
	public void testForgetDurableNoReMaterialise() {
		remember("a");
		remember("b");
		run(Maps.of(Strings.create("command"), Strings.create("forget"), Strings.create("n"), CVMLong.create(1)));

		assertEquals("1. b", recall().toString());
		assertEquals("1. b", recall().toString(), "forgotten entry must not come back on a fresh read");
	}

	// ========== validation ==========

	@Test
	public void testForgetOutOfRangeThrows() {
		remember("only");
		assertThrows(JobFailedException.class, () ->
			run(Maps.of(Strings.create("command"), Strings.create("forget"), Strings.create("n"), CVMLong.create(5))));
	}

	@Test
	public void testRememberRequiresText() {
		assertThrows(JobFailedException.class, () ->
			run(Maps.of(Strings.create("command"), Strings.create("remember"))));
	}

	@Test
	public void testUnknownCommandThrows() {
		assertThrows(JobFailedException.class, () ->
			run(Maps.of(Strings.create("command"), Strings.create("frobnicate"))));
	}

	// ========== configurable path ==========

	@Test
	public void testConfigurablePath() {
		run(Maps.of(Strings.create("command"), Strings.create("remember"),
			Strings.create("path"), Strings.create("w/notes"), Strings.create("text"), Strings.create("custom item")));

		ACell custom = run(Maps.of(Strings.create("command"), Strings.create("recall"),
			Strings.create("path"), Strings.create("w/notes")));
		assertEquals("1. custom item", custom.toString());

		assertNull(recall(), "default w/memory list is independent of the custom path");
	}

	// ========== recall over a map collection (e.g. a clinical problem list) ==========

	@Test
	public void testRecallOverMapCollection() {
		// A rich slug-keyed map (the shape getmine's health-context uses), written
		// directly. recall must render active/surfaceable values as a numbered list
		// via displayField, skipping resolved / surfacing:hold / mergedInto.
		AMap<AString, ACell> collection = Maps.of(
			Strings.create("asthma"), Maps.of(Strings.create("concept"), Strings.create("Asthma"),
				Strings.create("status"), Strings.create("active"), Strings.create("userNote"), Strings.create("inhaler PRN")),
			Strings.create("old-fx"), Maps.of(Strings.create("concept"), Strings.create("Old fracture"),
				Strings.create("status"), Strings.create("resolved")),
			Strings.create("held"), Maps.of(Strings.create("concept"), Strings.create("Held item"),
				Strings.create("status"), Strings.create("active"), Strings.create("surfacing"), Strings.create("hold")),
			Strings.create("merged"), Maps.of(Strings.create("concept"), Strings.create("Merged item"),
				Strings.create("status"), Strings.create("active"), Strings.create("mergedInto"), Strings.create("thread-x")),
			Strings.create("eczema"), Maps.of(Strings.create("concept"), Strings.create("Eczema"),
				Strings.create("status"), Strings.create("active")));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/health/ctx"), Strings.create("value"), collection),
			ALICE).awaitResult(5000);

		ACell block = run(Maps.of(
			Strings.create("command"), Strings.create("recall"),
			Strings.create("path"), Strings.create("w/health/ctx"),
			Strings.create("displayField"), Strings.create("concept"),
			Strings.create("noteField"), Strings.create("userNote")));
		assertNotNull(block);
		String s = block.toString();

		// active entries surfaced (sorted), with the note; gated entries absent.
		assertTrue(s.contains("Asthma"), "active entry surfaces");
		assertTrue(s.contains("inhaler PRN"), "noteField appended");
		assertTrue(s.contains("Eczema"), "active entry surfaces");
		assertFalse(s.contains("Old fracture"), "resolved entry gated out");
		assertFalse(s.contains("Held item"), "surfacing:hold gated out");
		assertFalse(s.contains("Merged item"), "mergedInto gated out");
		assertTrue(s.startsWith("1. "), "rendered as a numbered list");
		assertTrue(s.contains("\n2. "), "two active entries → two numbered lines");
	}

	// ========== write ops refuse a map path (no clobber) ==========

	@Test
	public void testRememberRefusesMapPath() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/health/ctx"),
				Strings.create("value"), Maps.of(Strings.create("x"), Maps.of(Strings.create("concept"), Strings.create("X")))),
			ALICE).awaitResult(5000);

		assertThrows(JobFailedException.class, () -> run(Maps.of(
			Strings.create("command"), Strings.create("remember"),
			Strings.create("path"), Strings.create("w/health/ctx"),
			Strings.create("text"), Strings.create("oops"))),
			"remember must not clobber a map store");
	}

	// ========== context format (recall as a config.context assemble-op) ==========

	@Test
	public void testRecallAsContextEntry() {
		remember("Prefers metric units");
		remember("Anxious about heart");

		ContextLoader loader = new ContextLoader(engine);
		ACell entry = Maps.of(
			Strings.create("op"), Strings.create(OP),
			Strings.create("input"), Maps.of(Strings.create("command"), Strings.create("recall"),
				Strings.create("path"), Strings.create("w/memory")),
			Strings.create("label"), Strings.create("User memory"));

		ACell msg = loader.resolveEntry(entry, ALICE);
		assertNotNull(msg, "recall op entry should resolve to a system message");
		assertEquals(Strings.create("system"), RT.getIn(msg, "role"));

		String content = RT.ensureString(RT.getIn(msg, "content")).toString();
		assertEquals("[Context: User memory]\n1. Prefers metric units\n2. Anxious about heart", content);
	}

	@Test
	public void testEmptyRecallContextEntrySkipped() {
		ContextLoader loader = new ContextLoader(engine);
		ACell entry = Maps.of(
			Strings.create("op"), Strings.create(OP),
			Strings.create("input"), Maps.of(Strings.create("command"), Strings.create("recall"),
				Strings.create("path"), Strings.create("w/memory")),
			Strings.create("label"), Strings.create("User memory"));

		assertNull(loader.resolveEntry(entry, ALICE), "empty memory should inject no system message");
	}
}
