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
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Tests for GoalTreeContext — the pure rendering functions for the goal tree.
 * No engine, no lattice, no LLM — just CVM data structures and rendering.
 */
public class GoalTreeContextTest {
	private static AMap<AString, ACell> legacyFrame(String description) {
		return Maps.of(GoalTreeContext.K_DESCRIPTION, description, GoalTreeContext.K_CONVERSATION, Vectors.empty());
	}
	private static AMap<AString, ACell> legacyFrame(String description, AMap<AString, ACell> loads) {
		return GoalTreeContext.withLoads(legacyFrame(description), loads);
	}


	// ========== Frame creation ==========
	@Test
	public void migrationPreservesNestedArchivesAndRenderedPrefix() {
		ACell a = Maps.of("role", "user", "content", "a");
		ACell b = Maps.of("role", "assistant", "content", "b");
		AMap<AString, ACell> inner = GoalTreeContext.createSegment("inner", Vectors.of(a));
		AMap<AString, ACell> outer = GoalTreeContext.createSegment("outer", Vectors.of(inner, b));
		AMap<AString, ACell> sibling = GoalTreeContext.createSegment("sibling", Vectors.of(b));
		AVector<ACell> original = Vectors.of(outer, sibling, a);
		ACell cached = Maps.of("messages", Vectors.of(a), "custom", "unchanged");
		AMap<AString, ACell> legacy = legacyFrame("goal")
			.assoc(GoalTreeContext.K_CONVERSATION, original)
			.assoc(GoalTreeContext.K_RENDERED_CONTEXT, cached);
		AMap<AString, ACell> migrated = GoalTreeContext.migrateFrame(legacy);
		assertEquals(original, GoalTreeContext.archivedConversation(migrated));
		assertSame(cached, migrated.get(GoalTreeContext.K_RENDERED_CONTEXT));
		assertSame(migrated, GoalTreeContext.migrateFrame(migrated));
		assertSame(original, legacy.get(GoalTreeContext.K_CONVERSATION));
		assertFalse(migrated.containsKey(GoalTreeContext.K_CONVERSATION));
		assertEquals(CVMLong.ONE, migrated.get(GoalTreeContext.K_HISTORY_VERSION));
		for (ACell entry : GoalTreeContext.history(migrated)) {
			assertNull(RT.getIn(entry, GoalTreeContext.K_ITEMS));
		}
	}

	@Test
	public void repeatedCompactionRetainsPrefixAndLegacyReadParity() {
		AMap<AString, ACell> legacy = legacyFrame("goal");
		AMap<AString, ACell> current = GoalTreeContext.createFrame("goal");
		for (int i = 0; i < 3; i++) {
			ACell turn = Maps.of("role", "user", "content", "turn " + i);
			legacy = GoalTreeContext.appendTurn(legacy, turn);
			current = GoalTreeContext.appendTurn(current, turn);
			AVector<ACell> before = GoalTreeContext.history(current);
			legacy = GoalTreeContext.compactFrame(legacy, "summary " + i);
			current = GoalTreeContext.compactFrame(current, "summary " + i);
			assertEquals(before, GoalTreeContext.history(current).slice(0, before.count()));
			assertEquals(GoalTreeContext.activeConversation(legacy), GoalTreeContext.archivedConversation(current));
			assertEquals(1, GoalTreeContext.activeConversation(current).count());
			assertEquals(i + 1, GoalTreeContext.countTurns(current));
			assertEquals(0, GoalTreeContext.countLiveTurns(current));
		}
	}

	@Test
	public void embedderArchiveWalkersKeepTheirResultsAcrossStorageFormats() {
		AMap<AString, ACell> legacy = legacyFrame("root");
		AMap<AString, ACell> current = GoalTreeContext.createFrame("root");
		AVector<ACell> expected = Vectors.empty();
		// Beyond sessionRead's depth limit: raw embedder access has no such cap.
		for (int i = 0; i < 12; i++) {
			AVector<ACell> turns = Vectors.of(
				Maps.of("role", "user", "content", Maps.of("text", "question " + i, "via", "phone"),
					"ts", (long) i, "caller", "owner", "source", "chat"),
				Maps.of("role", "assistant", "content", "internal", "toolCalls", Vectors.of(Maps.of("id", "call-" + i)),
					Fields.PROVIDER_STATE, Maps.of("opaque", "keep exactly")),
				Maps.of("role", "tool", "id", "call-" + i, "structuredContent", Maps.of("value", i)),
				Maps.of("role", "assistant", "content", "answer " + i, "jobId", "job-" + i));
			expected = expected.concat(turns);
			legacy = GoalTreeContext.compactFrame(GoalTreeContext.appendTurns(legacy, turns), "notes " + i);
			current = GoalTreeContext.compactFrame(GoalTreeContext.appendTurns(current, turns), "notes " + i);
		}
		ACell unanswered = Maps.of("role", "user", "content", "unfinished", "ts", 99L);
		legacy = GoalTreeContext.appendTurn(legacy, unanswered);
		current = GoalTreeContext.appendTurn(current, unanswered);
		AVector<ACell> oldShape = GoalTreeContext.activeConversation(legacy);
		ACell cached = Maps.of("messages", Vectors.of(Maps.of("role", "system", "content", "fixed")));
		for (AMap<AString, ACell> frame : java.util.List.of(legacy, GoalTreeContext.migrateFrame(legacy), current)) {
			AMap<AString, ACell> withCache = frame.assoc(GoalTreeContext.K_RENDERED_CONTEXT, cached);
			AVector<ACell> compatible = GoalTreeContext.archivedConversation(withCache);
			assertEquals(oldShape, compatible, "existing summary inspectors see the identical nested shape");
			assertEquals(expected.conj(unanswered), flattenAsLegacyEmbedder(compatible),
				"existing exports retain all original fields, internal turns and the unanswered tail");
			AVector<ACell> earlier = Vectors.empty();
			for (ACell entry : compatible) {
				if (GoalTreeContext.isSegment(entry)) {
					earlier = earlier.concat(flattenAsLegacyEmbedder(RT.ensureVector(RT.getIn(entry, "items"))));
				}
			}
			assertEquals(expected, earlier, "existing archived-only callers exclude the live tail");
			assertSame(unanswered, compatible.get(compatible.count() - 1));
			assertSame(cached, withCache.get(GoalTreeContext.K_RENDERED_CONTEXT));
			assertEquals(ConversationRenderer.renderFull(legacy), ConversationRenderer.renderFull(withCache));
			for (int depth : new int[] {0, 1, 8}) {
				assertEquals(ConversationRenderer.historical(legacy, 20, 1000, depth),
					ConversationRenderer.historical(withCache, 20, 1000, depth),
					"existing filtered callers retain their bounds and projection");
			}
		}
	}

	/** Representative pre-migration embedder logic; deliberately knows only items. */
	private static AVector<ACell> flattenAsLegacyEmbedder(AVector<ACell> entries) {
		AVector<ACell> result = Vectors.empty();
		for (ACell entry : entries) {
			result = GoalTreeContext.isSegment(entry)
				? result.concat(flattenAsLegacyEmbedder(RT.ensureVector(RT.getIn(entry, "items"))))
				: result.conj(entry);
		}
		return result;
	}

	@Test
	public void historyRejectsDowngradesRewritesAndForwardReferences() {
		AMap<AString, ACell> first = GoalTreeContext.appendTurn(GoalTreeContext.createFrame(""), Maps.of("content", "first"));
		AMap<AString, ACell> later = GoalTreeContext.appendTurn(first, Maps.of("content", "later"));
		assertEquals(GoalTreeContext.HISTORY_REWRITE, assertThrows(IllegalArgumentException.class,
			() -> GoalTreeContext.validateFrameUpdate(later, first)).getMessage());
		assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.validateFrameUpdate(first, legacyFrame("")));
		AMap<AString, ACell> invalid = GoalTreeContext.appendTurn(first,
			Maps.of("summary", "invalid", "turns", 1L, "historyRange", Vectors.of(0L, 2L)));
		assertEquals(GoalTreeContext.INVALID_HISTORY_RANGE, assertThrows(IllegalArgumentException.class,
			() -> GoalTreeContext.migrateFrame(invalid)).getMessage());
		assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.validateFrameUpdate(first, invalid));
		assertEquals(GoalTreeContext.INVALID_HISTORY_VERSION, assertThrows(IllegalArgumentException.class,
			() -> GoalTreeContext.migrateFrame(first.assoc(GoalTreeContext.K_HISTORY_VERSION, CVMLong.create(2)))).getMessage());
	}

	@Test
	public void testCreateFrame() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("Analyse vendors");
		assertEquals("Analyse vendors",
			RT.ensureString(frame.get(GoalTreeContext.K_DESCRIPTION)).toString());
		AVector<?> conv = GoalTreeContext.activeConversation(frame);
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
		AMap<AString, ACell> frame = legacyFrame("Research", null);
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
	public void testActiveConversationPreservesArchivesAndAbsentSelection() {
		AMap<AString, ACell> first = GoalTreeContext.createSegment("first summary",
			Vectors.of(Maps.of("role", "user", "content", "archived original")));
		AMap<AString, ACell> second = GoalTreeContext.createSegment("second summary",
			Vectors.of(first, Maps.of("role", "assistant", "content", "later")));
		AVector<ACell> selected = Vectors.of(second,
			Maps.of("role", "user", "content", "current"));
		AMap<AString, ACell> frame = legacyFrame("test")
			.assoc(GoalTreeContext.K_CONVERSATION, selected);

		assertSame(selected, GoalTreeContext.activeConversation(frame),
			"selection preserves the exact vector, including nested archive cells");
		assertSame(second, GoalTreeContext.activeConversation(frame).get(0));
		assertEquals(2, ConversationRenderer.renderFull(frame).count(),
			"archived originals must not be expanded into the active prompt");

		AMap<AString, ACell> absent = frame.dissoc(GoalTreeContext.K_CONVERSATION);
		AMap<AString, ACell> empty = frame.assoc(GoalTreeContext.K_CONVERSATION, Vectors.empty());
		assertNull(GoalTreeContext.activeConversation(null));
		assertNull(GoalTreeContext.activeConversation(absent));
		assertEquals(Vectors.empty(), GoalTreeContext.activeConversation(empty));
		assertEquals(Vectors.empty(), ConversationRenderer.renderFull(absent));
		AMap<AString, ACell> child = legacyFrame("child");
		assertNotEquals(GoalTreeContext.renderAncestors(Vectors.of(absent, child)),
			GoalTreeContext.renderAncestors(Vectors.of(empty, child)),
			"ancestor rendering must keep the existing distinction between absent and empty");
	}

	@Test
	public void testRenderConversationEmpty() {
		AMap<AString, ACell> frame = legacyFrame("test");
		AVector<ACell> messages = ConversationRenderer.renderFull(frame);
		assertEquals(0, messages.count());
	}

	@Test
	public void testActiveConversationSelectsHistorySuffixAtItsBounds() {
		ACell old = Maps.of("role", "user", "content", "outside current context");
		ACell current = Maps.of("role", "user", "content", "selected question");
		AVector<ACell> history = Vectors.of(old, current);
		AMap<AString, ACell> frame = Maps.of(GoalTreeContext.K_HISTORY, history,
			GoalTreeContext.K_CONTEXT_START, CVMLong.ZERO);
		assertEquals(history, GoalTreeContext.activeConversation(frame));

		frame = frame.assoc(GoalTreeContext.K_CONTEXT_START, CVMLong.ONE);
		assertEquals(Vectors.of(current), GoalTreeContext.activeConversation(frame));
		assertSame(current, GoalTreeContext.activeConversation(frame).get(0));
		assertEquals(Vectors.of(current), ConversationRenderer.renderFull(frame));
		assertSame(history, frame.get(GoalTreeContext.K_HISTORY), "selection must not rewrite history");

		AMap<AString, ACell> atEnd = frame.assoc(GoalTreeContext.K_CONTEXT_START, CVMLong.create(2));
		assertEquals(Vectors.empty(), GoalTreeContext.activeConversation(atEnd));
		assertEquals(Vectors.empty(), ConversationRenderer.renderFull(atEnd));
		assertEquals(Vectors.empty(), GoalTreeContext.activeConversation(Maps.of(
			GoalTreeContext.K_HISTORY, Vectors.empty(), GoalTreeContext.K_CONTEXT_START, CVMLong.ZERO)));
	}

	@Test
	public void testInvalidHistorySelectionFailsInsteadOfExposingAnotherRange() {
		AMap<AString, ACell> frame = Maps.of(GoalTreeContext.K_HISTORY,
			Vectors.of(Maps.of("role", "user", "content", "private history")));
		for (ACell start : new ACell[] {null, CVMLong.create(-1), CVMLong.create(2),
				CVMLong.create(Long.MAX_VALUE), Strings.create("0")}) {
			assertEquals(GoalTreeContext.INVALID_CONTEXT_START,
				assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.activeConversation(
					frame.assoc(GoalTreeContext.K_CONTEXT_START, start))).getMessage());
		}
		assertEquals(GoalTreeContext.INVALID_CONTEXT_START,
			assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.activeConversation(frame)).getMessage());
		for (ACell history : new ACell[] {null, Strings.create("not a sequence")}) {
			assertEquals(GoalTreeContext.INVALID_HISTORY,
				assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.activeConversation(
					Maps.of(GoalTreeContext.K_HISTORY, history, GoalTreeContext.K_CONTEXT_START, CVMLong.ZERO)))
					.getMessage());
		}
		assertEquals(GoalTreeContext.INVALID_HISTORY,
			assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.activeConversation(
				frame.assoc(GoalTreeContext.K_CONTEXT_START, CVMLong.ZERO)
					.assoc(GoalTreeContext.K_CONVERSATION, Vectors.empty()))).getMessage());
		assertEquals(GoalTreeContext.CONTEXT_START_WITHOUT_HISTORY,
			assertThrows(IllegalArgumentException.class, () -> GoalTreeContext.activeConversation(
				legacyFrame("legacy").assoc(GoalTreeContext.K_CONTEXT_START, CVMLong.ZERO)))
				.getMessage());
	}

	@Test
	public void testRenderConversationLiveTurnsPassThrough() {
		AMap<AString, ACell> frame = legacyFrame("test");
		ACell turn1 = Maps.of("role", "assistant", "content", "I'll check the data.");
		ACell turn2 = Maps.of("role", "user", "content", "Thanks.");
		frame = GoalTreeContext.appendTurn(frame, turn1);
		frame = GoalTreeContext.appendTurn(frame, turn2);

		AVector<ACell> messages = ConversationRenderer.renderFull(frame);
		assertEquals(2, messages.count());
		assertEquals("assistant", RT.ensureString(RT.getIn(messages.get(0), "role")).toString());
		assertEquals("user", RT.ensureString(RT.getIn(messages.get(1), "role")).toString());
	}

	@Test
	public void testRenderConversationSegmentsBecomeAssistantMemory() {
		AVector<ACell> items = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "step 1"),
			(ACell) Maps.of("role", "assistant", "content", "step 2"));
		AMap<AString, ACell> segment = GoalTreeContext.createSegment("Did steps 1 and 2", items);

		AMap<AString, ACell> frame = legacyFrame("test");
		// Manually build conversation with segment + live turn
		AVector<ACell> conv = Vectors.of((ACell) segment,
			(ACell) Maps.of("role", "assistant", "content", "step 3"));
		frame = frame.assoc(GoalTreeContext.K_CONVERSATION, conv);

		AVector<ACell> messages = ConversationRenderer.renderFull(frame);
		assertEquals(2, messages.count());
		// Agent-written summaries remain assistant memory, never system instruction.
		assertEquals("assistant", RT.ensureString(RT.getIn(messages.get(0), "role")).toString());
		String content = RT.ensureString(RT.getIn(messages.get(0), "content")).toString();
		assertTrue(content.contains("Compacted"));
		assertTrue(content.contains("turns 0–1"), "a summary names the turns it stands for: " + content);
		assertTrue(content.contains("Did steps 1 and 2"));
		// Second is the live turn
		assertEquals("assistant", RT.ensureString(RT.getIn(messages.get(1), "role")).toString());
	}

	// ========== Append-only conversation rendering ==========

	/** Helper: build a conversation vector and put it on a frame. */
	private static AMap<AString, ACell> withConversation(ACell... turns) {
		AMap<AString, ACell> frame = legacyFrame("test");
		AVector<ACell> conv = Vectors.empty();
		for (ACell t : turns) conv = conv.conj(t);
		return frame.assoc(GoalTreeContext.K_CONVERSATION, conv);
	}

	private static ACell userTurn(String content) {
		return Maps.of("role", "user", "content", content);
	}

	private static ACell assistantText(String content) {
		return Maps.of("role", "assistant", "content", content);
	}

	private static ACell assistantToolCall(String toolName) {
		return Maps.of("role", "assistant", "toolCalls",
			Vectors.of((ACell) Maps.of("id", "tc-1", "name", toolName)));
	}

	private static ACell toolResult(String content) {
		return Maps.of("role", "tool", "id", "tc-1", "content", content);
	}

	@Test
	public void testRenderConversationKeepsEveryToolExchange() {
		AMap<AString, ACell> frame = withConversation(
			userTurn("do X"),
			assistantToolCall("a"), toolResult("r"),
			assistantText("Done."),
			userTurn("now Y"));
		AVector<ACell> stored = RT.ensureVector(frame.get(GoalTreeContext.K_CONVERSATION));
		AVector<ACell> rendered = ConversationRenderer.renderFull(frame);
		assertEquals(stored, rendered);
		assertEquals("assistant", String.valueOf(RT.getIn(rendered.get(1), "role")));
		assertEquals("tool", String.valueOf(RT.getIn(rendered.get(2), "role")));
		assertEquals("tc-1", String.valueOf(RT.getIn(rendered.get(2), "id")));
	}

	@Test
	public void testHistoricalTitleIsTheWordsOfTheFirstMessage() {
		AMap<AString, ACell> voiced = withConversation(
			Maps.of("role", "user", "content", Maps.of("text", "are you hearing me?",
				"via", Maps.of("channel", "voice")), "ts", 1L),
			Maps.of("role", "assistant", "content", "yes", "ts", 2L));
		assertEquals("are you hearing me?",
			ConversationRenderer.historical(voiced, 0, 0).firstUserContent().toString());
		AMap<AString, ACell> requested = withConversation(
			Maps.of("role", "user", "content", Maps.of("task", "Daily check-in", "format", "bullets"), "ts", 1L),
			Maps.of("role", "assistant", "content", "done", "ts", 2L));
		assertEquals("Daily check-in",
			ConversationRenderer.historical(requested, 0, 0).firstUserContent().toString());
		assertNull(GoalTreeContext.turnText(Maps.of("data", "no words")));
	}

	@Test
	public void testHistoricalProjectionKeepsOnlyCompletedConversation() {
		AMap<AString, ACell> frame = withConversation(
			Maps.of("role", "user", "content", "first question", "ts", 1L),
			assistantToolCall("lookup"),
			toolResult("private scratch"),
			Maps.of("role", "system", "content", "framework diagnostic", "ts", 2L),
			Maps.of("role", "assistant", "content", "first answer", "ts", 3L),
			Maps.of("role", "user", "content", "unfinished", "ts", 4L),
			assistantToolCall("still-working"));

		ConversationRenderer.HistoricalView view =
			ConversationRenderer.historical(frame, 20, 1000);
		assertEquals(2, view.turnCount());
		assertEquals(2, view.messages().count());
		assertEquals("first question", view.firstUserContent().toString());
		assertEquals(3, view.updated());
		assertFalse(view.truncated());
		assertEquals("first question",
			RT.ensureString(RT.getIn(view.messages().get(0), "content")).toString());
		assertEquals("first answer",
			RT.ensureString(RT.getIn(view.messages().get(1), "content")).toString());
		for (ACell message : view.messages()) {
			assertNull(RT.getIn(message, "ts"));
			assertNull(RT.getIn(message, "toolCalls"));
		}
	}

	@Test
	public void testHistoricalProjectionBoundsNewestContentIncludingOversizedTurn() {
		AMap<AString, ACell> frame = withConversation(
			Maps.of("role", "user", "content", "question-one", "ts", 1L),
			Maps.of("role", "assistant", "content", "answer-one", "ts", 2L),
			Maps.of("role", "user", "content", "question-two", "ts", 3L),
			Maps.of("role", "assistant", "content", "answer-two", "ts", 4L));

		ConversationRenderer.HistoricalView byTurns =
			ConversationRenderer.historical(frame, 2, 1000);
		assertEquals(4, byTurns.turnCount());
		assertEquals(2, byTurns.messages().count());
		assertTrue(byTurns.truncated());
		assertEquals("question-two",
			RT.ensureString(RT.getIn(byTurns.messages().get(0), "content")).toString());

		ConversationRenderer.HistoricalView byChars =
			ConversationRenderer.historical(frame, 20, 4);
		assertEquals(1, byChars.messages().count());
		assertEquals("-two",
			RT.ensureString(RT.getIn(byChars.messages().get(0), "content")).toString());
		assertEquals(Boolean.TRUE, RT.jvm(RT.getIn(byChars.messages().get(0), "truncated")));
		assertTrue(byChars.truncated());
	}

	@Test
	public void testHistoricalProjectionOpensCompactedArchivesSafely() {
		AMap<AString, ACell> frame = withConversation(
			Maps.of("role", "user", "content", "archived question", "ts", 1L),
			assistantToolCall("lookup"),
			toolResult("private scratch"),
			Maps.of("role", "assistant", "content", "archived answer", "ts", 3L));
		frame = GoalTreeContext.compactFrame(frame, "Question answered");

		ConversationRenderer.HistoricalView summary =
			ConversationRenderer.historical(frame, 20, 1000);
		assertEquals(1, summary.messages().count());
		assertEquals("assistant", RT.getIn(summary.messages().get(0), "role").toString());
		assertTrue(RT.getIn(summary.messages().get(0), "content").toString()
			.contains("Question answered"));

		ConversationRenderer.HistoricalView expanded =
			ConversationRenderer.historical(frame, 20, 1000, 1);
		assertEquals(2, expanded.messages().count());
		assertEquals("archived question",
			RT.getIn(expanded.messages().get(0), "content").toString());
		assertEquals("archived answer",
			RT.getIn(expanded.messages().get(1), "content").toString());
		assertEquals("archived question", expanded.firstUserContent().toString());
		assertEquals(3, expanded.updated());
		assertFalse(expanded.messages().toString().contains("private scratch"));
	}

	// ========== Ancestor rendering ==========

	@Test
	public void testRenderAncestorsNullForRootFrame() {
		AVector<ACell> frames = Vectors.of(
			(ACell) legacyFrame("root goal"));
		assertNull(GoalTreeContext.renderAncestors(frames));
	}

	@Test
	public void testRenderAncestorsOneParent() {
		AMap<AString, ACell> root = legacyFrame("Competitive analysis");
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "I'll research three vendors."));
		AMap<AString, ACell> child = legacyFrame("Analyse Acme Corp");

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
		AMap<AString, ACell> gp = legacyFrame("Top-level goal");
		gp = GoalTreeContext.appendTurn(gp,
			Maps.of("role", "assistant", "content", "Starting the grand plan with lots of detail."));

		AMap<AString, ACell> parent = legacyFrame("Mid-level task");
		parent = GoalTreeContext.appendTurn(parent,
			Maps.of("role", "assistant", "content", "Working on the middle layer."));

		AMap<AString, ACell> active = legacyFrame("Leaf task");

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
		AMap<AString, ACell> frame = legacyFrame("Analyse Beta Inc");
		AMap<AString, ACell> goal = GoalTreeContext.renderGoal(frame);
		assertNotNull(goal);
		assertEquals("user", RT.ensureString(goal.get(GoalTreeContext.K_ROLE)).toString());
		String content = RT.ensureString(goal.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(content.contains("Analyse Beta Inc"));
	}

	// ========== Compaction ==========

	@Test
	public void testCompactFrameArchivesLiveTurns() {
		AMap<AString, ACell> frame = legacyFrame("research");
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
	public void testRepeatedCompactionNestsExistingArchive() {
		// Start with a segment already in conversation
		AVector<ACell> items = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "old work"));
		AMap<AString, ACell> oldSegment = GoalTreeContext.createSegment("Phase 1", items);

		AMap<AString, ACell> frame = legacyFrame("research");
		AVector<ACell> conv = Vectors.of((ACell) oldSegment);
		frame = frame.assoc(GoalTreeContext.K_CONVERSATION, conv);

		// Add live turns
		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "new work"));

		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(frame, "Phase 2");

		@SuppressWarnings("unchecked")
		AVector<ACell> newConv = (AVector<ACell>) compacted.get(GoalTreeContext.K_CONVERSATION);
		assertEquals(1, newConv.count());
		assertTrue(GoalTreeContext.isSegment(newConv.get(0)));
		AMap<AString, ACell> outer = RT.ensureMap(newConv.get(0));
		AVector<ACell> archived = RT.ensureVector(outer.get(GoalTreeContext.K_ITEMS));
		assertEquals(conv.conj(Maps.of("role", "assistant", "content", "new work")), archived);
		assertSame(oldSegment, archived.get(0));
		assertEquals(2L, ((CVMLong) outer.get(GoalTreeContext.K_TURNS)).longValue(),
			"turn count is recursive across nested compactions");
	}

	@Test
	public void testCompactNoOpWhenNoLiveTurns() {
		AMap<AString, ACell> frame = legacyFrame("empty");
		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(frame, "nothing");
		assertSame(frame, compacted);
	}

	@Test
	public void testObservationsReloadCurrentValueAfterCompaction() {
		AString id = Strings.create("loads:status");
		AVector<ACell> firstValue = Vectors.of((ACell) Maps.of(
			"role", "system", "content", "status one"));
		AVector<ACell> first = Vectors.of((ACell)
			ContextAssembler.observation(id, 0, firstValue));

		AMap<AString, ACell> frame = GoalTreeContext.applyObservations(
			legacyFrame("watch"), first, 10L);
		AVector<ACell> conversation = RT.ensureVector(
			frame.get(GoalTreeContext.K_CONVERSATION));
		assertEquals(1, conversation.count());
		assertEquals("status one", RT.getIn(conversation.get(0), "content").toString());

		AMap<AString, ACell> unchanged = GoalTreeContext.applyObservations(frame, first, 20L);
		assertSame(frame, unchanged, "the clock is not part of canonical comparison");

		AVector<ACell> second = Vectors.of((ACell) ContextAssembler.observation(id, 0,
			Vectors.of((ACell) Maps.of("role", "system", "content", "status two"))));
		AMap<AString, ACell> changed = GoalTreeContext.applyObservations(frame, second, 30L);
		AVector<ACell> changedConversation = RT.ensureVector(
			changed.get(GoalTreeContext.K_CONVERSATION));
		assertEquals(2, changedConversation.count());
		assertEquals("status two", RT.getIn(changedConversation.get(1), "content").toString());

		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(changed, "latest status is two");
		AVector<ACell> compactedConversation = RT.ensureVector(
			compacted.get(GoalTreeContext.K_CONVERSATION));
		assertEquals(1, compactedConversation.count(), "only the archive remains visible");
		assertNull(compacted.get(GoalTreeContext.K_OBSERVATIONS),
			"the next ordinary observation pass establishes a current baseline");

		AMap<AString, ACell> reloaded = GoalTreeContext.applyObservations(compacted,
			second, 40L);
		AVector<ACell> reloadedConversation = RT.ensureVector(
			reloaded.get(GoalTreeContext.K_CONVERSATION));
		assertEquals(2, reloadedConversation.count(), "archive plus freshly loaded current value");
		assertEquals("status two", RT.getIn(reloadedConversation.get(1), "content").toString());

		AMap<AString, ACell> removed = GoalTreeContext.applyObservations(reloaded,
			Vectors.empty(), 50L);
		assertNull(removed.get(GoalTreeContext.K_OBSERVATIONS));
		String removal = RT.getIn(removed, GoalTreeContext.K_CONVERSATION,
			2L, "content").toString();
		assertTrue(removal.contains("no longer active"), removal);
	}

	@Test
	public void testCompactionReturnsAgentLoadsToInitialDeclarationState() {
		AString pinnedKey = Strings.create("pinned");
		AMap<AString, ACell> pinned = Maps.of(
			Loads.K_AGENT_MANAGED, CVMBool.FALSE,
			Loads.K_APPENDED, CVMBool.TRUE,
			Loads.K_TOOL_BINDINGS, Vectors.of(Strings.create("keep")));
		AString contextKey = Strings.create("working-note");
		AMap<AString, ACell> context = Maps.of(
			Loads.K_AGENT_MANAGED, CVMBool.TRUE,
			Loads.K_TRUSTED, CVMBool.FALSE,
			Loads.K_TEXT, Strings.create("current note"),
			Loads.K_APPENDED, CVMBool.TRUE);
		AString skillKey = Strings.create("w/skills/current");
		AMap<AString, ACell> skill = Maps.of(
			Loads.K_AGENT_MANAGED, CVMBool.TRUE,
			Loads.K_TRUSTED, CVMBool.FALSE,
			Skills.K_SKILL, CVMBool.TRUE,
			AbstractLLMAdapter.K_LABEL, Strings.create("old-name"),
			Fields.TOOLS, Vectors.of(Strings.create("v/ops/old")),
			Skills.K_SKILLS, Vectors.of(Strings.create("w/old-child")),
			Skills.K_SKILLSETS, Vectors.empty(),
			Loads.K_TOOL_BINDINGS, Vectors.of(Strings.create("old-binding")),
			Loads.K_APPENDED, CVMBool.TRUE);

		AMap<AString, ACell> frame = legacyFrame("reload",
			Maps.of(pinnedKey, pinned, contextKey, context, skillKey, skill));
		frame = GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "loaded state was used"));
		AMap<AString, ACell> compacted = GoalTreeContext.compactFrame(frame, "summary");
		AMap<AString, ACell> loads = GoalTreeContext.getLoads(compacted);

		assertSame(pinned, loads.get(pinnedKey), "pinned declarations are not rewritten");
		assertNull(RT.getIn(loads.get(contextKey), Loads.K_APPENDED));
		assertEquals("current note", RT.getIn(loads.get(contextKey), Loads.K_TEXT).toString(),
			"the source declaration remains intact");
		assertNull(RT.getIn(loads.get(skillKey), Loads.K_APPENDED));
		assertNull(RT.getIn(loads.get(skillKey), Loads.K_TOOL_BINDINGS));
		assertNull(RT.getIn(loads.get(skillKey), Fields.TOOLS));
		assertNull(RT.getIn(loads.get(skillKey), Skills.K_SKILLS));
		assertNull(RT.getIn(loads.get(skillKey), Skills.K_SKILLSETS));
		assertNull(RT.getIn(loads.get(skillKey), AbstractLLMAdapter.K_LABEL));
	}

	// ========== Turn counting and size ==========

	@Test
	public void testCountLiveTurns() {
		AMap<AString, ACell> frame = legacyFrame("test");
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

		AMap<AString, ACell> frame = legacyFrame("test");
		AVector<ACell> conv = Vectors.of(
			(ACell) segment,
			(ACell) Maps.of("role", "assistant", "content", "new"));
		frame = frame.assoc(GoalTreeContext.K_CONVERSATION, conv);

		assertEquals(1, GoalTreeContext.countLiveTurns(frame));
	}

	// ========== Scoped loads ==========

	@Test
	public void testGetLoadsEmpty() {
		AMap<AString, ACell> frame = legacyFrame("test");
		AMap<AString, ACell> loads = GoalTreeContext.getLoads(frame);
		assertNotNull(loads);
		assertEquals(0, loads.count());
	}

	@Test
	public void testAddLoad() {
		AMap<AString, ACell> frame = legacyFrame("test");
		AMap<AString, ACell> meta = Maps.of(
			Strings.create("budget"), CVMLong.create(500));
		frame = GoalTreeContext.addLoad(frame, Strings.create("w/docs/rules"), meta);

		AMap<AString, ACell> loads = GoalTreeContext.getLoads(frame);
		assertEquals(1, loads.count());
		assertNotNull(loads.get(Strings.create("w/docs/rules")));
	}

	@Test
	public void testRemoveLoad() {
		AMap<AString, ACell> frame = legacyFrame("test");
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

		AMap<AString, ACell> child = legacyFrame("child task", parentLoads);

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
		AMap<AString, ACell> child = legacyFrame("detail work", parentLoads);
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
		AMap<AString, ACell> child = legacyFrame("focused", parentLoads);
		child = GoalTreeContext.removeLoad(child, Strings.create("w/rules"));

		assertEquals(0, GoalTreeContext.getLoads(child).count());
		// Parent loads unchanged (immutable)
		assertEquals(1, parentLoads.count());
	}

	// ========== Full assembly (integration) ==========

	@Test
	public void testFullContextAssemblyRootFrame() {
		AMap<AString, ACell> root = legacyFrame("Write a report on vendors");
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "I'll research each vendor."));

		AVector<ACell> frames = Vectors.of((ACell) root);

		// No ancestors at root
		assertNull(GoalTreeContext.renderAncestors(frames));

		// Conversation renders the live turn
		AVector<ACell> conv = ConversationRenderer.renderFull(root);
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
		AMap<AString, ACell> root = legacyFrame("Competitive analysis for A, B, C");
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "Explored w/vendors, found 12 entries."));
		root = GoalTreeContext.appendTurn(root,
			Maps.of("role", "assistant", "content", "Starting sequential research."));

		// Child: "Analyse Acme Corp"
		AMap<AString, ACell> child = legacyFrame("Analyse Acme Corp: products, financials, market position");
		child = GoalTreeContext.appendTurn(child,
			Maps.of("role", "assistant", "content", "Checking products first."));

		AVector<ACell> frames = Vectors.of((ACell) root, (ACell) child);

		// Ancestors should render root's conversation at budget
		AMap<AString, ACell> ancestors = GoalTreeContext.renderAncestors(frames);
		assertNotNull(ancestors);
		String ancestorContent = RT.ensureString(ancestors.get(GoalTreeContext.K_CONTENT)).toString();
		assertTrue(ancestorContent.contains("Competitive analysis"));

		// Conversation should render child's live turns
		AVector<ACell> conv = ConversationRenderer.renderFull(child);
		assertEquals(1, conv.count());
		assertTrue(RT.ensureString(RT.getIn(conv.get(0), "content")).toString()
			.contains("products"));

		// Goal should be the child's description
		AMap<AString, ACell> goal = GoalTreeContext.renderGoal(child);
		assertTrue(RT.ensureString(goal.get(GoalTreeContext.K_CONTENT)).toString()
			.contains("Analyse Acme"));
	}
}
