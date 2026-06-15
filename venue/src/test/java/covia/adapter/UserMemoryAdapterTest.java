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
 * Tests for the UserMemoryAdapter — a simple, always-in-context numbered memory
 * list. Covers the op surface (remember / recall / update / forget by number)
 * and, importantly, the <b>context format</b>: recall resolved through
 * {@link ContextLoader} as a {@code config.context} assemble-op yields a clean
 * {@code system} message.
 *
 * <p>Uses the shared {@link TestEngine#ENGINE}; each test gets its own user DID
 * so the default {@code w/memory} list is fresh per test.</p>
 */
public class UserMemoryAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ALICE;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private ACell run(String op, AMap<AString, ACell> input, RequestContext ctx) {
		return engine.jobs().invokeOperation(op, input, ctx).awaitResult(5000);
	}

	private ACell remember(String text) {
		return run("v/ops/memory/remember", Maps.of(Strings.create("text"), Strings.create(text)), ALICE);
	}

	private ACell recall() {
		return run("v/ops/memory/recall", Maps.of(Strings.create("path"), Strings.create("w/memory")), ALICE);
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

	// ========== update by number ==========

	@Test
	public void testUpdateByNumber() {
		remember("a");
		remember("b");
		remember("c");

		ACell res = run("v/ops/memory/update",
			Maps.of(Strings.create("n"), CVMLong.create(2), Strings.create("text"), Strings.create("B-updated")),
			ALICE);
		assertEquals(CVMLong.create(2), RT.getIn(res, "n"));

		// position preserved, only item 2 changed
		assertEquals("1. a\n2. B-updated\n3. c", recall().toString());
	}

	// ========== forget by number (renumbers) ==========

	@Test
	public void testForgetRenumbers() {
		remember("a");
		remember("b");
		remember("c");

		ACell res = run("v/ops/memory/forget", Maps.of(Strings.create("n"), CVMLong.create(2)), ALICE);
		assertEquals(CVMLong.create(2), RT.getIn(res, "count"), "two items remain");

		assertEquals("1. a\n2. c", recall().toString());
	}

	// ========== forget is durable — entry does not re-materialise ==========

	@Test
	public void testForgetDurableNoReMaterialise() {
		remember("a");
		remember("b");
		run("v/ops/memory/forget", Maps.of(Strings.create("n"), CVMLong.create(1)), ALICE);

		// whole-vector LWW replace — re-reads consistently show 'a' gone, 'b' renumbered to 1.
		assertEquals("1. b", recall().toString());
		assertEquals("1. b", recall().toString(), "forgotten entry must not come back on a fresh read");
	}

	// ========== out-of-range and validation ==========

	@Test
	public void testForgetOutOfRangeThrows() {
		remember("only");
		assertThrows(JobFailedException.class, () ->
			run("v/ops/memory/forget", Maps.of(Strings.create("n"), CVMLong.create(5)), ALICE));
	}

	@Test
	public void testRememberRequiresText() {
		assertThrows(JobFailedException.class, () ->
			run("v/ops/memory/remember", Maps.of(Strings.create("path"), Strings.create("w/memory")), ALICE));
	}

	// ========== configurable path ==========

	@Test
	public void testConfigurablePath() {
		String customPath = "w/health/mina/memory";
		run("v/ops/memory/remember",
			Maps.of(Strings.create("path"), Strings.create(customPath), Strings.create("text"), Strings.create("custom-list item")),
			ALICE);

		// custom path has the item
		ACell custom = run("v/ops/memory/recall", Maps.of(Strings.create("path"), Strings.create(customPath)), ALICE);
		assertEquals("1. custom-list item", custom.toString());

		// default path is a different, empty list
		assertNull(recall(), "default w/memory list is independent of the custom path");
	}

	// ========== context format (the interface that matters) ==========

	/**
	 * recall referenced as a config.context assemble-op must yield a clean
	 * {@code {role:"system", content:"[Context: <label>]\n<numbered list>"}}
	 * message — the heading comes from the entry's label, the body is recall's
	 * bare numbered list.
	 */
	@Test
	public void testRecallAsContextEntry() {
		remember("Prefers metric units");
		remember("Anxious about heart");

		ContextLoader loader = new ContextLoader(engine);
		ACell entry = Maps.of(
			Strings.create("op"), Strings.create("v/ops/memory/recall"),
			Strings.create("input"), Maps.of(Strings.create("path"), Strings.create("w/memory")),
			Strings.create("label"), Strings.create("User memory"));

		ACell msg = loader.resolveEntry(entry, ALICE);
		assertNotNull(msg, "recall op entry should resolve to a system message");
		assertEquals(Strings.create("system"), RT.getIn(msg, "role"));

		String content = RT.ensureString(RT.getIn(msg, "content")).toString();
		assertEquals("[Context: User memory]\n1. Prefers metric units\n2. Anxious about heart", content);
	}

	/** Empty memory → the context entry resolves to null (no empty heading injected). */
	@Test
	public void testEmptyRecallContextEntrySkipped() {
		ContextLoader loader = new ContextLoader(engine);
		ACell entry = Maps.of(
			Strings.create("op"), Strings.create("v/ops/memory/recall"),
			Strings.create("input"), Maps.of(Strings.create("path"), Strings.create("w/memory")),
			Strings.create("label"), Strings.create("User memory"));

		assertNull(loader.resolveEntry(entry, ALICE), "empty memory should inject no system message");
	}
}
