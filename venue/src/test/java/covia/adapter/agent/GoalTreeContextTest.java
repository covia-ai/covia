package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Tests for GoalTreeContext — the pure rendering functions for the goal tree.
 * No engine, no lattice, no LLM — just CVM data structures and rendering.
 */
public class GoalTreeContextTest {

	// ========== Frame creation ==========

	@Test
	public void testCreateFrame() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("Analyse vendors");
		assertEquals("Analyse vendors",
			RT.ensureString(frame.get(GoalTreeContext.K_DESCRIPTION)).toString());
		AVector<?> conv = (AVector<?>) frame.get(GoalTreeContext.K_CONVERSATION);
		assertNotNull(conv);
		assertEquals(0, conv.count());
	}

	@Test
	public void testCreateFrameWithLoads() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/data"), Maps.of(Strings.create("budget"), CVMLong.create(500)));
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("Research", loads);
		assertNotNull(frame.get(GoalTreeContext.K_LOADS));
	}

	// ========== Root goal description generators ==========

	@Test
	public void testDescribeMessageWithCaller() {
		ACell msg = Maps.of("content", "Analyse Q4 results", "caller", "did:key:zAlice");
		String desc = GoalTreeContext.describeMessage(msg);
		assertTrue(desc.contains("did:key:zAlice"));
		assertTrue(desc.contains("Analyse Q4 results"));
	}

	@Test
	public void testDescribeMessageWithoutCaller() {
		ACell msg = Maps.of("content", "Check vendor status");
		String desc = GoalTreeContext.describeMessage(msg);
		assertEquals("Check vendor status", desc);
	}

	@Test
	public void testDescribeMessageTruncatesLongContent() {
		ACell msg = Maps.of("content", "x".repeat(500));
		String desc = GoalTreeContext.describeMessage(msg);
		assertTrue(desc.length() <= 210); // some room for prefix
		assertTrue(desc.contains("..."));
	}

	@Test
	public void testDescribeTask() {
		ACell task = Maps.of("input", "Generate report for March", "caller", "did:key:zBob");
		String desc = GoalTreeContext.describeTask(task);
		assertTrue(desc.startsWith("Task from did:key:zBob"));
		assertTrue(desc.contains("Generate report"));
	}

	@Test
	public void testDescribeTaskNoCaller() {
		ACell task = Maps.of("input", "Run diagnostics");
		String desc = GoalTreeContext.describeTask(task);
		assertEquals("Task: Run diagnostics", desc);
	}

	@Test
	public void testDescribeTransitionSingleMessage() {
		AVector<ACell> msgs = Vectors.of((ACell) Maps.of("content", "Hello agent"));
		String desc = GoalTreeContext.describeTransitionInput(msgs, null, null);
		assertEquals("Hello agent", desc);
	}

	@Test
	public void testDescribeTransitionSingleTaskString() {
		AVector<ACell> tasks = Vectors.of((ACell) Maps.of("input", "Process invoice"));
		String desc = GoalTreeContext.describeTransitionInput(null, tasks, null);
		assertEquals("Process invoice", desc);
	}

	@Test
	public void testDescribeTransitionSingleTaskWithMessageField() {
		AVector<ACell> tasks = Vectors.of((ACell) Maps.of("input",
			Maps.of("message", "What is 2+2?")));
		String desc = GoalTreeContext.describeTransitionInput(null, tasks, null);
		assertEquals("What is 2+2?", desc);
	}

	@Test
	public void testDescribeTransitionMultipleMessages() {
		AVector<ACell> msgs = Vectors.of(
			(ACell) Maps.of("content", "msg1"),
			(ACell) Maps.of("content", "msg2"),
			(ACell) Maps.of("content", "msg3"));
		String desc = GoalTreeContext.describeTransitionInput(msgs, null, null);
		assertEquals("Process 3 messages", desc);
	}

	@Test
	public void testDescribeTransitionMixed() {
		AVector<ACell> msgs = Vectors.of((ACell) Maps.of("content", "hello"));
		AVector<ACell> tasks = Vectors.of(
			(ACell) Maps.of("input", "task1"),
			(ACell) Maps.of("input", "task2"));
		String desc = GoalTreeContext.describeTransitionInput(msgs, tasks, null);
		assertTrue(desc.contains("1 message"));
		assertTrue(desc.contains("2 tasks"));
	}

	@Test
	public void testDescribeTransitionPendingOnly() {
		AVector<ACell> pending = Vectors.of((ACell) Maps.of("id", "job1"));
		String desc = GoalTreeContext.describeTransitionInput(null, null, pending);
		assertEquals("Process 1 pending result", desc);
	}

	@Test
	public void testDescribeTransitionEmpty() {
		String desc = GoalTreeContext.describeTransitionInput(null, null, null);
		assertEquals("Process incoming work", desc);
	}

	// ========== Frame creation (continued) ==========

	@Test
	public void testCreateFrameNullLoadsOmitted() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("Research", null);
		assertNull(frame.get(GoalTreeContext.K_LOADS));
	}

	// ========== Segment creation ==========

	@Test
	public void testCreateSegment() {
		AVector<ACell> items = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "Hello"),
			(ACell) Maps.of("role", "user", "content", "Hi"));
		AMap<AString, ACell> segment = GoalTreeContext.createSegment("Greeted user", items);

		assertEquals("Greeted user",
			RT.ensureString(segment.get(GoalTreeContext.K_SUMMARY)).toString());
		assertEquals(2L,
			((CVMLong) segment.get(GoalTreeContext.K_TURNS)).longValue());
		assertEquals(2, ((AVector<?>) segment.get(GoalTreeContext.K_ITEMS)).count());
	}

	@Test
	public void testIsSegment() {
		AMap<AString, ACell> segment = GoalTreeContext.createSegment("summary", Vectors.empty());
		assertTrue(GoalTreeContext.isSegment(segment));

		AMap<AString, ACell> turn = Maps.of("role", "assistant", "content", "hello");
		assertFalse(GoalTreeContext.isSegment(turn));
	}

	@Test
	public void testIsLiveTurn() {
		AMap<AString, ACell> turn = Maps.of("role", "assistant", "content", "hello");
		assertTrue(GoalTreeContext.isLiveTurn(turn));

		AMap<AString, ACell> segment = GoalTreeContext.createSegment("summary", Vectors.empty());
		// Segments also have role? No — segments have summary+items, not role
		assertFalse(GoalTreeContext.isLiveTurn(segment));
	}

	// ========== Conversation rendering ==========

	@Test
	public void testRenderConversationEmpty() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		AVector<ACell> messages = GoalTreeContext.renderConversation(frame);
		assertEquals(0, messages.count());
	}

	@Test
	public void testRenderConversationLiveTurnsPassThrough() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		ACell turn1 = Maps.of("role", "assistant", "content", "I'll check the data.");
		ACell turn2 = Maps.of("role", "user", "content", "Thanks.");
		frame = GoalTreeContext.appendTurn(frame, turn1);
		frame = GoalTreeContext.appendTurn(frame, turn2);

		AVector<ACell> messages = GoalTreeContext.renderConversation(frame);
		assertEquals(2, messages.count());
		assertEquals("assistant", RT.ensureString(RT.getIn(messages.get(0), "role")).toString());
		assertEquals("user", RT.ensureString(RT.getIn(messages.get(1), "role")).toString());
	}

	@Test
	public void testRenderConversationSegmentsBecomeSystemMessages() {
		AVector<ACell> items = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "step 1"),
			(ACell) Maps.of("role", "assistant", "content", "step 2"));
		AMap<AString, ACell> segment = GoalTreeContext.createSegment("Did steps 1 and 2", items);

		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		// Manually build conversation with segment + live turn
		AVector<ACell> conv = Vectors.of((ACell) segment,
			(ACell) Maps.of("role", "assistant", "content", "step 3"));
		frame = frame.assoc(GoalTreeContext.K_CONVERSATION, conv);

		AVector<ACell> messages = GoalTreeContext.renderConversation(frame);
		assertEquals(2, messages.count());
		// First message is the segment rendered as system
		assertEquals("system", RT.ensureString(RT.getIn(messages.get(0), "role")).toString());
		String content = RT.ensureString(RT.getIn(messages.get(0), "content")).toString();
		assertTrue(content.contains("Compacted"));
		assertTrue(content.contains("2 turns"));
		assertTrue(content.contains("Did steps 1 and 2"));
		// Second is the live turn
		assertEquals("assistant", RT.ensureString(RT.getIn(messages.get(1), "role")).toString());
	}

	// ========== Ancestor rendering ==========

	@Test
	public void testRenderAncestorsNullForRootFrame() {
		AVector<ACell> frames = Vectors.of(
			(ACell) GoalTreeContext.createFrame("root goal"));
		assertNull(GoalTreeContext.renderAncestors(frames));
	}

	@Test
	public void testRenderAncestorsOneParent() {
		AMap<AString, ACell> root = GoalTreeContext.createFrame("Competitive analysis");
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "I'll research three vendors."));
		AMap<AString, ACell> child = GoalTreeContext.createFrame("Analyse Acme Corp");

		AVector<ACell> frames = Vectors.of((ACell) root, (ACell) child);
		AMap<AString, ACell> ancestors = GoalTreeContext.renderAncestors(frames);
		assertNotNull(ancestors);

		String content = RT.ensureString(ancestors.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(content.contains("Ancestor Context"));
		assertTrue(content.contains("Competitive analysis"));
		// Parent's conversation should be rendered (at budget ~300B)
		assertTrue(content.contains("research") || content.contains("vendors"));
	}

	@Test
	public void testRenderAncestorsDecreasingBudget() {
		// Three frames: grandparent → parent → active
		AMap<AString, ACell> gp = GoalTreeContext.createFrame("Top-level goal");
		gp = GoalTreeContext.appendTurn(gp,
			Maps.of("role", "assistant", "content", "Starting the grand plan with lots of detail."));

		AMap<AString, ACell> parent = GoalTreeContext.createFrame("Mid-level task");
		parent = GoalTreeContext.appendTurn(parent,
			Maps.of("role", "assistant", "content", "Working on the middle layer."));

		AMap<AString, ACell> active = GoalTreeContext.createFrame("Leaf task");

		AVector<ACell> frames = Vectors.of((ACell) gp, (ACell) parent, (ACell) active);
		AMap<AString, ACell> ancestors = GoalTreeContext.renderAncestors(frames);
		assertNotNull(ancestors);
		String content = RT.ensureString(ancestors.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(content.contains("Top-level goal"));
		assertTrue(content.contains("Mid-level task"));
	}

	@Test
	public void testBudgetForDepth() {
		// Parent (depth 0) = 300
		assertEquals(300, GoalTreeContext.budgetForDepth(0));
		// Grandparent (depth 1) = 150
		assertEquals(150, GoalTreeContext.budgetForDepth(1));
		// Great-grandparent (depth 2) = 75
		assertEquals(75, GoalTreeContext.budgetForDepth(2));
		// Floors at MIN_ANCESTOR_BUDGET
		assertTrue(GoalTreeContext.budgetForDepth(20) >= GoalTreeContext.MIN_ANCESTOR_BUDGET);
	}

	// ========== Goal rendering ==========

	@Test
	public void testRenderGoal() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("Analyse Beta Inc");
		AMap<AString, ACell> goal = GoalTreeContext.renderGoal(frame);
		assertNotNull(goal);
		assertEquals("user", RT.ensureString(goal.get(GoalTreeContext.K_ROLE)).toString());
		String content = RT.ensureString(goal.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(content.contains("Analyse Beta Inc"));
	}

	// ========== Context map ==========

	@Test
	public void testRenderContextMap() {
		AVector<ACell> frames = Vectors.of(
			(ACell) GoalTreeContext.createFrame("root"),
			(ACell) GoalTreeContext.createFrame("child"));
		AMap<AString, ACell> map = GoalTreeContext.renderContextMap(frames, 5000, 180000);
		String content = RT.ensureString(map.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(content.contains("5000/180000"));
		assertTrue(content.contains("2 frames"));
		assertFalse(content.contains("WARNING")); // only ~3%
	}

	@Test
	public void testRenderContextMapWarningAt70Pct() {
		AVector<ACell> frames = Vectors.of((ACell) GoalTreeContext.createFrame("root"));
		AMap<AString, ACell> map = GoalTreeContext.renderContextMap(frames, 140000, 180000);
		String content = RT.ensureString(map.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(content.contains("WARNING"));
		assertTrue(content.contains("compact"));
	}

	// ========== Compaction ==========

	@Test
	public void testCompactFrameArchivesLiveTurns() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("research");
		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "Explored vendors."));
		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "Analysed financials."));

		assertEquals(2, GoalTreeContext.countLiveTurns(frame));

		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(frame,
			"Explored vendors and analysed financials.");

		// Should have 1 segment, 0 live turns
		@SuppressWarnings("unchecked")
		AVector<ACell> conv = (AVector<ACell>) compacted.get(GoalTreeContext.K_CONVERSATION);
		assertEquals(1, conv.count());
		assertTrue(GoalTreeContext.isSegment(conv.get(0)));
		assertEquals(0, GoalTreeContext.countLiveTurns(compacted));

		// Segment should contain the original turns
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> seg = (AMap<AString, ACell>) conv.get(0);
		assertEquals(2L, ((CVMLong) seg.get(GoalTreeContext.K_TURNS)).longValue());
		assertEquals("Explored vendors and analysed financials.",
			RT.ensureString(seg.get(GoalTreeContext.K_SUMMARY)).toString());
	}

	@Test
	public void testCompactPreservesExistingSegments() {
		// Start with a segment already in conversation
		AVector<ACell> items = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "old work"));
		AMap<AString, ACell> oldSegment = GoalTreeContext.createSegment("Phase 1", items);

		AMap<AString, ACell> frame = GoalTreeContext.createFrame("research");
		AVector<ACell> conv = Vectors.of((ACell) oldSegment);
		frame = frame.assoc(GoalTreeContext.K_CONVERSATION, conv);

		// Add live turns
		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "new work"));

		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(frame, "Phase 2");

		@SuppressWarnings("unchecked")
		AVector<ACell> newConv = (AVector<ACell>) compacted.get(GoalTreeContext.K_CONVERSATION);
		assertEquals(2, newConv.count()); // old segment + new segment
		assertTrue(GoalTreeContext.isSegment(newConv.get(0)));
		assertTrue(GoalTreeContext.isSegment(newConv.get(1)));
	}

	@Test
	public void testCompactNoOpWhenNoLiveTurns() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("empty");
		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(frame, "nothing");
		assertSame(frame, compacted);
	}

	// ========== Turn counting and size ==========

	@Test
	public void testCountLiveTurns() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		assertEquals(0, GoalTreeContext.countLiveTurns(frame));

		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "one"));
		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "two"));
		assertEquals(2, GoalTreeContext.countLiveTurns(frame));
	}

	@Test
	public void testCountLiveTurnsExcludesSegments() {
		AVector<ACell> items = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "old"));
		AMap<AString, ACell> segment = GoalTreeContext.createSegment("old work", items);

		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		AVector<ACell> conv = Vectors.of(
			(ACell) segment,
			(ACell) Maps.of("role", "assistant", "content", "new"));
		frame = frame.assoc(GoalTreeContext.K_CONVERSATION, conv);

		assertEquals(1, GoalTreeContext.countLiveTurns(frame));
	}

	@Test
	public void testEstimateLiveTurnBytes() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		assertEquals(0, GoalTreeContext.estimateLiveTurnBytes(frame));

		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "some content"));
		assertTrue(GoalTreeContext.estimateLiveTurnBytes(frame) > 0);
	}

	// ========== Scoped loads ==========

	@Test
	public void testGetLoadsEmpty() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		AMap<AString, ACell> loads = GoalTreeContext.getLoads(frame);
		assertNotNull(loads);
		assertEquals(0, loads.count());
	}

	@Test
	public void testAddLoad() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		AMap<AString, ACell> meta = Maps.of(
			Strings.create("budget"), CVMLong.create(500));
		frame = GoalTreeContext.addLoad(frame, Strings.create("w/docs/rules"), meta);

		AMap<AString, ACell> loads = GoalTreeContext.getLoads(frame);
		assertEquals(1, loads.count());
		assertNotNull(loads.get(Strings.create("w/docs/rules")));
	}

	@Test
	public void testRemoveLoad() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("test");
		AMap<AString, ACell> meta = Maps.of(
			Strings.create("budget"), CVMLong.create(500));
		frame = GoalTreeContext.addLoad(frame, Strings.create("w/docs/rules"), meta);
		frame = GoalTreeContext.removeLoad(frame, Strings.create("w/docs/rules"));

		AMap<AString, ACell> loads = GoalTreeContext.getLoads(frame);
		assertEquals(0, loads.count());
		// K_LOADS key should be removed entirely when empty
		assertNull(frame.get(GoalTreeContext.K_LOADS));
	}

	@Test
	public void testCreateFrameWithInheritedLoads() {
		AMap<AString, ACell> meta = Maps.of(
			Strings.create("budget"), CVMLong.create(300));
		AMap<AString, ACell> parentLoads = Maps.of(
			Strings.create("w/docs/rules"), (ACell) meta);

		AMap<AString, ACell> child = GoalTreeContext.createFrame("child task", parentLoads);

		// Child should have inherited loads
		AMap<AString, ACell> childLoads = GoalTreeContext.getLoads(child);
		assertEquals(1, childLoads.count());
		assertNotNull(childLoads.get(Strings.create("w/docs/rules")));
	}

	@Test
	public void testChildLoadShadowsParent() {
		// Parent has w/data at budget 200
		AMap<AString, ACell> parentLoads = Maps.of(
			Strings.create("w/data"), (ACell) Maps.of(
				Strings.create("budget"), CVMLong.create(200)));

		// Child inherits, then overrides with budget 2000
		AMap<AString, ACell> child = GoalTreeContext.createFrame("detail work", parentLoads);
		child = GoalTreeContext.addLoad(child, Strings.create("w/data"),
			Maps.of(Strings.create("budget"), CVMLong.create(2000)));

		CVMLong childBudget = (CVMLong) RT.getIn(
			GoalTreeContext.getLoads(child).get(Strings.create("w/data")), "budget");
		assertEquals(2000, childBudget.longValue());

		// Parent loads unaffected (immutable data)
		CVMLong parentBudget = (CVMLong) RT.getIn(
			parentLoads.get(Strings.create("w/data")), "budget");
		assertEquals(200, parentBudget.longValue());
	}

	@Test
	public void testChildUnloadDoesNotAffectParent() {
		AMap<AString, ACell> parentLoads = Maps.of(
			Strings.create("w/rules"), (ACell) Maps.of(
				Strings.create("budget"), CVMLong.create(500)));

		// Child inherits, then unloads
		AMap<AString, ACell> child = GoalTreeContext.createFrame("focused", parentLoads);
		child = GoalTreeContext.removeLoad(child, Strings.create("w/rules"));

		assertEquals(0, GoalTreeContext.getLoads(child).count());
		// Parent loads unchanged (immutable)
		assertEquals(1, parentLoads.count());
	}

	// ========== Full assembly (integration) ==========

	@Test
	public void testFullContextAssemblyRootFrame() {
		AMap<AString, ACell> root = GoalTreeContext.createFrame("Write a report on vendors");
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "I'll research each vendor."));

		AVector<ACell> frames = Vectors.of((ACell) root);

		// No ancestors at root
		assertNull(GoalTreeContext.renderAncestors(frames));

		// Conversation renders the live turn
		AVector<ACell> conv = GoalTreeContext.renderConversation(root);
		assertEquals(1, conv.count());

		// Goal renders the description
		AMap<AString, ACell> goal = GoalTreeContext.renderGoal(root);
		assertNotNull(goal);
		assertTrue(RT.ensureString(goal.get(GoalTreeContext.K_CONTENT)).toString()
			.contains("Write a report"));
	}

	@Test
	public void testFullContextAssemblyNestedFrames() {
		// Root: "Competitive analysis" with some turns
		AMap<AString, ACell> root = GoalTreeContext.createFrame("Competitive analysis for A, B, C");
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "Explored w/vendors, found 12 entries."));
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "Starting sequential research."));

		// Child: "Analyse Acme Corp"
		AMap<AString, ACell> child = GoalTreeContext.createFrame("Analyse Acme Corp: products, financials, market position");
		child = GoalTreeContext.appendTurn(child,
			Maps.of("role", "assistant", "content", "Checking products first."));

		AVector<ACell> frames = Vectors.of((ACell) root, (ACell) child);

		// Ancestors should render root's conversation at budget
		AMap<AString, ACell> ancestors = GoalTreeContext.renderAncestors(frames);
		assertNotNull(ancestors);
		String ancestorContent = RT.ensureString(ancestors.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(ancestorContent.contains("Competitive analysis"));

		// Conversation should render child's live turns
		AVector<ACell> conv = GoalTreeContext.renderConversation(child);
		assertEquals(1, conv.count());
		assertTrue(RT.ensureString(RT.getIn(conv.get(0), "content")).toString()
			.contains("products"));

		// Goal should be the child's description
		AMap<AString, ACell> goal = GoalTreeContext.renderGoal(child);
		assertTrue(RT.ensureString(goal.get(GoalTreeContext.K_CONTENT)).toString()
			.contains("Analyse Acme"));
	}
}
