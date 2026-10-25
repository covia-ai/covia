package covia.adapter.agent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Preview API: immutable, format-independent snapshot of one frame's history.
 * The API may change before stabilisation; existing callers should continue to
 * use {@link GoalTreeContext#archivedConversation} for nested archive compatibility.
 *
 * <p>This is a raw storage reader, not an authorised tool response. Callers must
 * authorise access and apply audience/provenance projection and output budgets
 * before exposing entries. Raw entries include internal activity, unfinished
 * turns and opaque provider continuation data.</p>
 *
 * <p>Positions belong to this snapshot. Reading neither expands summaries nor
 * changes the frame, active selection or cached model input. Legacy archives
 * are normalised in memory using the same transform as storage migration.</p>
 */
public final class ConversationHistory {

	static final String INVALID_RANGE = "History range must satisfy 0 <= start <= end <= snapshot size";
	static final String INVALID_LIMIT = "History page limit must be positive";
	static final String INVALID_POSITION = "History position must identify an entry in the snapshot";

	private final AVector<ACell> entries;
	private final long contextStart;

	private ConversationHistory(AVector<ACell> entries, long contextStart) {
		this.entries = entries;
		this.contextStart = contextStart;
	}

	/** Captures and validates a frame without publishing migration or resolving
	 * sources. An absent frame/conversation has an empty readable history.
	 * Opening may inspect the entire history; subsequent reads use vector slices. */
	public static ConversationHistory open(AMap<AString, ACell> frame) {
		AMap<AString, ACell> normalised = GoalTreeContext.migrateFrame(frame);
		AVector<ACell> entries = GoalTreeContext.history(normalised);
		if (entries == null) return new ConversationHistory(Vectors.empty(), 0);
		long start = ((CVMLong) normalised.get(GoalTreeContext.K_CONTEXT_START)).longValue();
		return new ConversationHistory(entries, start);
	}

	public long size() { return entries.count(); }

	/** The active selection's start when this snapshot was opened. */
	public long contextStart() { return contextStart; }

	/** A half-open range in this snapshot, including summary coverage. */
	public record Range(long start, long end) {}

	/** Entry at vector offset i has absolute position start + i. End is the
	 * requested range end; next is the exclusive page end and continuation offset.
	 * Entries retain their original values and are never truncated. */
	public record Page(long start, long end, long next, AVector<ACell> entries) {
		public boolean hasMore() { return next < end; }
	}

	/** Read toward the fixed snapshot end. Limits count entries, not bytes. */
	public Page read(long start, int limit) {
		return read(start, size(), limit);
	}

	/** Read a page within [start, end), for example a summary's coverage.
	 * Continue with read(page.next(), page.end(), limit) on this same snapshot. */
	public Page read(long start, long end, int limit) {
		if (start < 0 || end < start || end > size()) throw new IllegalArgumentException(INVALID_RANGE);
		if (limit <= 0) throw new IllegalArgumentException(INVALID_LIMIT);
		long next = start + Math.min(end - start, (long) limit);
		return new Page(start, end, next, entries.slice(start, next));
	}

	/** Returns the range replaced by a summary, or null for an ordinary entry.
	 * Coverage can contain earlier summaries; following them is an explicit read,
	 * never an automatic recursive expansion. All ranges were validated on open. */
	public Range coverage(long position) {
		if (position < 0 || position >= size()) throw new IllegalArgumentException(INVALID_POSITION);
		AVector<ACell> range = RT.ensureVector(RT.getIn(entries.get(position), GoalTreeContext.K_HISTORY_RANGE));
		if (range == null) return null;
		return new Range(((CVMLong) range.get(0)).longValue(), ((CVMLong) range.get(1)).longValue());
	}
}
