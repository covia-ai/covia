package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import covia.api.Fields;

/** Pure tests for the preview reader; no running venue or provider. */
public class ConversationHistoryTest {

	@Test
	public void pagingReachesEveryEntryIncludingInternalAndUnfinishedTurns() {
		AVector<ACell> entries = Vectors.of(
			Maps.of("role", "user", "content", "question"),
			Maps.of("role", "assistant", "content", "working", "toolCalls", Vectors.of(Maps.of("id", "call")),
				Fields.PROVIDER_STATE, Maps.of("opaque", "continuation")),
			Maps.of("role", "tool", "id", "call", "structuredContent", Maps.of("value", 42)),
			Maps.of("role", "assistant", "content", "answer"),
			Maps.of("role", "user", "content", "unfinished"));
		ConversationHistory snapshot = ConversationHistory.open(GoalTreeContext.appendTurns(
			GoalTreeContext.createFrame(""), entries));
		AVector<ACell> collected = Vectors.empty();
		long position = 0;
		do {
			var page = snapshot.read(position, 2);
			assertEquals(position, page.start());
			assertEquals(entries.count(), page.end());
			assertTrue(page.entries().count() <= 2);
			assertTrue(page.next() > position);
			collected = collected.concat(page.entries());
			position = page.next();
			if (!page.hasMore()) break;
		} while (true);
		assertEquals(entries, collected);
		assertEquals(entries.count(), position);
		assertNull(snapshot.coverage(1));
	}

	@Test
	public void legacyAndMigratedArchivesHaveTheSamePositionsAndCoverage() {
		ACell user = Maps.of("role", "user", "content", "question");
		ACell assistant = Maps.of("role", "assistant", "content", "answer");
		AMap<AString, ACell> inner = GoalTreeContext.createSegment("inner", Vectors.of(user));
		AMap<AString, ACell> outer = GoalTreeContext.createSegment("outer", Vectors.of(inner, assistant));
		AVector<ACell> original = Vectors.of(outer, user);
		AMap<AString, ACell> legacy = Maps.of("conversation", original, "renderedContext", Maps.of("fixed", true));
		ConversationHistory before = ConversationHistory.open(legacy);
		ConversationHistory after = ConversationHistory.open(GoalTreeContext.migrateFrame(legacy));
		assertEquals(before.read(0, 100), after.read(0, 100));
		assertEquals(3, before.contextStart());
		assertEquals(5, before.size());
		assertEquals(new ConversationHistory.Range(1, 3), before.coverage(3));
		assertEquals(new ConversationHistory.Range(0, 1), before.coverage(1));
		var range = before.coverage(3);
		var first = before.read(range.start(), range.end(), 1);
		assertTrue(first.hasMore());
		assertEquals(2, first.next());
		var last = before.read(first.next(), first.end(), 1);
		assertFalse(last.hasMore());
		assertEquals(Vectors.of(assistant), last.entries());
		assertSame(original, legacy.get(GoalTreeContext.K_CONVERSATION));
		assertEquals(original, GoalTreeContext.archivedConversation(GoalTreeContext.migrateFrame(legacy)));
	}

	@Test
	public void snapshotEndAndSelectionSurviveLaterAppendsAndCompaction() {
		AMap<AString, ACell> frame = GoalTreeContext.appendTurn(GoalTreeContext.createFrame(""),
			Maps.of("role", "user", "content", "first"));
		ConversationHistory snapshot = ConversationHistory.open(frame);
		var first = snapshot.read(0, 1);
		AMap<AString, ACell> later = GoalTreeContext.compactFrame(GoalTreeContext.appendTurn(frame,
			Maps.of("role", "assistant", "content", "second")), "summary");
		ConversationHistory current = ConversationHistory.open(later);
		assertEquals(3, current.size());
		assertEquals(2, current.contextStart());
		assertEquals(new ConversationHistory.Range(0, 2), current.coverage(2));
		assertEquals(first, snapshot.read(0, 1));
		assertEquals(0, snapshot.contextStart());
		assertEquals(1, snapshot.size());
		assertFalse(snapshot.read(first.next(), 1).hasMore());
	}

	@Test
	public void deepArchivesAndLargeEntriesRemainReachableWithoutTruncation() {
		ACell original = Maps.of("role", "user", "content", "large entry ".repeat(10_000));
		ACell nested = original;
		for (int i = 0; i < 12; i++) {
			nested = GoalTreeContext.createSegment("summary " + i, Vectors.of(nested));
		}
		ConversationHistory snapshot = ConversationHistory.open(Maps.of("conversation", Vectors.of(nested)));
		long position = snapshot.contextStart();
		for (int i = 0; i < 12; i++) {
			var coverage = snapshot.coverage(position);
			assertNotNull(coverage);
			assertEquals(1, coverage.end() - coverage.start());
			position = coverage.start();
		}
		assertNull(snapshot.coverage(position));
		assertSame(original, snapshot.read(position, position + 1, 1).entries().get(0));
	}

	@Test
	public void emptyAndBoundaryReadsHaveExplicitCompletion() {
		for (AMap<AString, ACell> frame : java.util.Arrays.<AMap<AString, ACell>>asList(
				null, Maps.empty(), GoalTreeContext.createFrame(""))) {
			ConversationHistory snapshot = ConversationHistory.open(frame);
			var page = snapshot.read(0, Integer.MAX_VALUE);
			assertEquals(0, snapshot.contextStart());
			assertEquals(new ConversationHistory.Page(0, 0, 0, Vectors.empty()), page);
			assertFalse(page.hasMore());
		}
	}

	@Test
	public void invalidRangesLimitsAndSummaryReferencesFailExplicitly() {
		ConversationHistory snapshot = ConversationHistory.open(GoalTreeContext.createFrame(""));
		for (long[] range : new long[][] {{-1, 0}, {1, 0}, {0, 1}, {0, Long.MAX_VALUE}}) {
			assertEquals(ConversationHistory.INVALID_RANGE, assertThrows(IllegalArgumentException.class,
				() -> snapshot.read(range[0], range[1], 1)).getMessage());
		}
		for (int limit : new int[] {0, -1}) {
			assertEquals(ConversationHistory.INVALID_LIMIT, assertThrows(IllegalArgumentException.class,
				() -> snapshot.read(0, limit)).getMessage());
		}
		for (long position : new long[] {-1, 0, Long.MAX_VALUE}) {
			assertEquals(ConversationHistory.INVALID_POSITION, assertThrows(IllegalArgumentException.class,
				() -> snapshot.coverage(position)).getMessage());
		}
		var malformed = GoalTreeContext.appendTurn(GoalTreeContext.createFrame(""),
			Maps.of("summary", "bad", "turns", 1L, "historyRange", Vectors.of(0L, 1L)));
		assertEquals(GoalTreeContext.INVALID_HISTORY_RANGE, assertThrows(IllegalArgumentException.class,
			() -> ConversationHistory.open(malformed)).getMessage());
	}
}
