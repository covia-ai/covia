package covia.adapter.agent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.CellExplorer;
import convex.core.data.Cells;
import convex.core.lang.RT;

/**
 * Renders a goal tree frame stack into LLM context messages.
 *
 * <p>This is a pure function with no side effects — takes a frame stack and
 * configuration, returns assembled messages. All rendering is testable without
 * an LLM, engine, or lattice.</p>
 *
 * <h3>Context layout (follows attention research: reference at top, recency at bottom)</h3>
 * <ol>
 *   <li>Ancestor context — parent/grandparent conversations at decreasing budgets</li>
 *   <li>Loaded data — inherited + own scoped loads, refreshed each turn</li>
 *   <li>Conversation — compacted segments + live turns, current frame only</li>
 *   <li>Goal — the subgoal description for the current frame</li>
 * </ol>
 *
 * <p>System prompt and tool schemas are handled separately by ContextBuilder.
 * This class focuses on the goal-tree-specific sections.</p>
 */
public class GoalTreeContext {

	// ========== CVM keys for frame structure ==========

	/** Frame description (the subgoal text that created this frame) */
	static final AString K_DESCRIPTION = Strings.intern("description");

	/** Frame conversation (vector of segments + live turns) */
	static final AString K_CONVERSATION = Strings.intern("conversation");

	/** Frame-scoped loads (map of path → load metadata) */
	static final AString K_LOADS = Strings.intern("loads");

	// ========== CVM keys for compacted segments ==========

	/** Segment summary (LLM-provided) */
	static final AString K_SUMMARY = Strings.intern("summary");

	/** Segment turn count */
	static final AString K_TURNS = Strings.intern("turns");

	/** Segment items (full conversation turns) */
	static final AString K_ITEMS = Strings.intern("items");

	// ========== Message keys ==========

	static final AString K_ROLE = Strings.intern("role");
	static final AString K_CONTENT = Strings.intern("content");
	static final AString ROLE_SYSTEM = Strings.intern("system");
	static final AString ROLE_USER = Strings.intern("user");

	// ========== Budget defaults ==========

	/** Budget for parent frame's ancestor rendering */
	static final int PARENT_BUDGET = 300;

	/** Budget decay factor per ancestor level */
	static final double ANCESTOR_DECAY = 0.5;

	/** Minimum budget for any ancestor level */
	static final int MIN_ANCESTOR_BUDGET = 50;

	// ========== Frame construction ==========

	/**
	 * Creates a new frame with the given description and empty conversation.
	 */
	public static AMap<AString, ACell> createFrame(String description) {
		return Maps.of(
			K_DESCRIPTION, Strings.create(description),
			K_CONVERSATION, Vectors.empty());
	}

	/**
	 * Creates a new frame with description and initial loads (inherited from parent).
	 */
	public static AMap<AString, ACell> createFrame(String description, AMap<AString, ACell> loads) {
		AMap<AString, ACell> frame = createFrame(description);
		if (loads != null && loads.count() > 0) {
			frame = frame.assoc(K_LOADS, loads);
		}
		return frame;
	}

	// ========== Root goal description generators ==========

	/**
	 * Generates a root goal description from an inbox message.
	 * Inbox messages have {content, caller?} structure.
	 */
	public static String describeMessage(ACell message) {
		AString content = RT.ensureString(RT.getIn(message, K_CONTENT));
		AString caller = RT.ensureString(RT.getIn(message, Strings.intern("caller")));
		String text = (content != null) ? truncate(content.toString(), 200) : "(empty message)";
		if (caller != null) {
			return "Message from " + caller + ": " + text;
		}
		return text;
	}

	/**
	 * Generates a root goal description from a task.
	 * Tasks have {input, caller?} structure from agent:request.
	 */
	public static String describeTask(ACell task) {
		AString input = RT.ensureString(RT.getIn(task, Strings.intern("input")));
		AString caller = RT.ensureString(RT.getIn(task, Strings.intern("caller")));
		String text = (input != null) ? truncate(input.toString(), 200) : "(no input)";
		StringBuilder sb = new StringBuilder("Task");
		if (caller != null) sb.append(" from ").append(caller);
		sb.append(": ").append(text);
		return sb.toString();
	}

	/**
	 * Generates a root goal description from a combination of pending work.
	 * Summarises multiple messages and tasks into a single description.
	 */
	@SuppressWarnings("unchecked")
	public static String describeTransitionInput(
			AVector<ACell> messages, AVector<ACell> tasks, AVector<ACell> pending) {
		int msgCount = (messages != null) ? (int) messages.count() : 0;
		int taskCount = (tasks != null) ? (int) tasks.count() : 0;
		int pendingCount = (pending != null) ? (int) pending.count() : 0;

		// Single message — use its content directly
		if (msgCount == 1 && taskCount == 0 && pendingCount == 0) {
			return describeMessage(messages.get(0));
		}

		// Single task — use its input directly
		if (taskCount == 1 && msgCount == 0 && pendingCount == 0) {
			return describeTask(tasks.get(0));
		}

		// Mixed or multiple — summarise counts
		StringBuilder sb = new StringBuilder("Process");
		boolean first = true;
		if (msgCount > 0) {
			sb.append(" ").append(msgCount).append(" message").append(msgCount > 1 ? "s" : "");
			first = false;
		}
		if (taskCount > 0) {
			if (!first) sb.append(",");
			sb.append(" ").append(taskCount).append(" task").append(taskCount > 1 ? "s" : "");
			first = false;
		}
		if (pendingCount > 0) {
			if (!first) sb.append(",");
			sb.append(" ").append(pendingCount).append(" pending result").append(pendingCount > 1 ? "s" : "");
			first = false;
		}
		if (first) {
			sb.append(" incoming work");
		}
		return sb.toString();
	}

	private static String truncate(String s, int maxLen) {
		if (s.length() <= maxLen) return s;
		return s.substring(0, maxLen - 3) + "...";
	}

	// ========== Segment construction ==========

	/**
	 * Creates a compacted segment from live turns and an LLM-provided summary.
	 */
	public static AMap<AString, ACell> createSegment(String summary, AVector<ACell> items) {
		return Maps.of(
			K_SUMMARY, Strings.create(summary),
			K_TURNS, CVMLong.create(items.count()),
			K_ITEMS, items);
	}

	/**
	 * Returns true if a conversation entry is a compacted segment (has summary + items).
	 */
	public static boolean isSegment(ACell entry) {
		if (!(entry instanceof AMap)) return false;
		AMap<?, ?> map = (AMap<?, ?>) entry;
		return map.get(K_SUMMARY) != null && map.get(K_ITEMS) != null;
	}

	/**
	 * Returns true if a conversation entry is a live turn (has role field).
	 */
	public static boolean isLiveTurn(ACell entry) {
		if (!(entry instanceof AMap)) return false;
		return ((AMap<?, ?>) entry).get(K_ROLE) != null;
	}

	// ========== Context rendering ==========

	/**
	 * Renders ancestor frames as a system message. Each ancestor's conversation
	 * is rendered by CellExplorer at a decreasing budget (parent ~300B,
	 * grandparent ~150B, etc.).
	 *
	 * @param frames the full frame stack (last element is active frame)
	 * @return system message with rendered ancestor context, or null if root frame
	 */
	public static AMap<AString, ACell> renderAncestors(AVector<ACell> frames) {
		if (frames == null || frames.count() <= 1) return null;

		StringBuilder sb = new StringBuilder("[Ancestor Context]\n");
		long activeIndex = frames.count() - 1;

		// Render from outermost to innermost (excluding active frame)
		for (long i = 0; i < activeIndex; i++) {
			AMap<AString, ACell> frame = ensureMap(frames.get(i));
			if (frame == null) continue;

			int budget = budgetForDepth(activeIndex - 1 - i);
			AString desc = RT.ensureString(frame.get(K_DESCRIPTION));
			ACell conversation = frame.get(K_CONVERSATION);

			sb.append("\n");
			if (desc != null) sb.append("Goal: ").append(desc).append("\n");

			if (conversation != null) {
				String rendered = new CellExplorer(budget).explore(conversation).toString();
				sb.append(rendered).append("\n");
			}
		}

		return Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, Strings.create(sb.toString()));
	}

	/**
	 * Renders the active frame's conversation as a vector of messages.
	 * Compacted segments become system messages with their summary.
	 * Live turns pass through as-is.
	 *
	 * @param frame the active frame
	 * @return vector of message maps ready for the LLM
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> renderConversation(AMap<AString, ACell> frame) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(K_CONVERSATION);
		if (conversation == null || conversation.count() == 0) return Vectors.empty();

		AVector<ACell> messages = Vectors.empty();
		for (long i = 0; i < conversation.count(); i++) {
			ACell entry = conversation.get(i);
			if (isSegment(entry)) {
				// Render segment as a system message with summary
				AString summary = RT.ensureString(((AMap<AString, ACell>) entry).get(K_SUMMARY));
				ACell turns = ((AMap<AString, ACell>) entry).get(K_TURNS);
				String text = "[Compacted: " + (turns != null ? turns : "?") + " turns] " +
					(summary != null ? summary.toString() : "");
				messages = messages.conj(Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, Strings.create(text)));
			} else if (isLiveTurn(entry)) {
				messages = messages.conj(entry);
			}
		}
		return messages;
	}

	/**
	 * Renders the active frame's goal description as a user message.
	 *
	 * @param frame the active frame
	 * @return user message with the goal, or null if no description
	 */
	public static AMap<AString, ACell> renderGoal(AMap<AString, ACell> frame) {
		AString desc = RT.ensureString(frame.get(K_DESCRIPTION));
		if (desc == null) return null;
		return Maps.of(K_ROLE, ROLE_USER, K_CONTENT,
			Strings.create("[Goal] " + desc));
	}

	/**
	 * Renders a context map showing budget status and frame depth.
	 *
	 * @param frames the full frame stack
	 * @param bytesConsumed bytes consumed by other context sections
	 * @param totalBudget total context budget
	 * @return system message with context map
	 */
	public static AMap<AString, ACell> renderContextMap(
			AVector<ACell> frames, long bytesConsumed, long totalBudget) {
		StringBuilder sb = new StringBuilder("[Context Map]\n");
		sb.append("budget: ").append(bytesConsumed).append("/").append(totalBudget)
		  .append(" bytes (").append(totalBudget - bytesConsumed).append(" remaining)\n");
		sb.append("depth: ").append(frames.count()).append(" frame")
		  .append(frames.count() > 1 ? "s" : "").append("\n");

		int pct = (int) (100 * bytesConsumed / Math.max(1, totalBudget));
		if (pct >= 70) {
			sb.append("WARNING: ").append(pct).append("% budget used. Consider calling compact(summary).\n");
		}

		return Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, Strings.create(sb.toString()));
	}

	/**
	 * Compacts live turns in a frame's conversation into a new segment.
	 * Returns an updated frame with the segment appended and live turns cleared.
	 *
	 * @param frame the frame to compact
	 * @param summary LLM-provided summary of the compacted turns
	 * @return new frame with compacted conversation
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> compactFrame(AMap<AString, ACell> frame, String summary) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(K_CONVERSATION);
		if (conversation == null || conversation.count() == 0) return frame;

		// Split into segments (keep) and live turns (compact)
		AVector<ACell> segments = Vectors.empty();
		AVector<ACell> liveTurns = Vectors.empty();
		for (long i = 0; i < conversation.count(); i++) {
			ACell entry = conversation.get(i);
			if (isSegment(entry)) {
				segments = segments.conj(entry);
			} else {
				liveTurns = liveTurns.conj(entry);
			}
		}

		if (liveTurns.count() == 0) return frame;

		// Create new segment from live turns
		AMap<AString, ACell> segment = createSegment(summary, liveTurns);

		// New conversation = existing segments + new segment (no live turns)
		AVector<ACell> newConversation = segments.conj(segment);
		return frame.assoc(K_CONVERSATION, newConversation);
	}

	/**
	 * Appends a turn to the active frame's conversation.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> appendTurn(AMap<AString, ACell> frame, ACell turn) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(K_CONVERSATION);
		if (conversation == null) conversation = Vectors.empty();
		return frame.assoc(K_CONVERSATION, conversation.conj(turn));
	}

	/**
	 * Counts live (non-segment) turns in a frame's conversation.
	 */
	public static long countLiveTurns(AMap<AString, ACell> frame) {
		@SuppressWarnings("unchecked")
		AVector<ACell> conversation = (AVector<ACell>) frame.get(K_CONVERSATION);
		if (conversation == null) return 0;
		long count = 0;
		for (long i = 0; i < conversation.count(); i++) {
			if (!isSegment(conversation.get(i))) count++;
		}
		return count;
	}

	/**
	 * Estimates the byte size of live turns in a frame's conversation.
	 */
	public static long estimateLiveTurnBytes(AMap<AString, ACell> frame) {
		@SuppressWarnings("unchecked")
		AVector<ACell> conversation = (AVector<ACell>) frame.get(K_CONVERSATION);
		if (conversation == null) return 0;
		long bytes = 0;
		for (long i = 0; i < conversation.count(); i++) {
			ACell entry = conversation.get(i);
			if (!isSegment(entry)) {
				bytes += Cells.storageSize(entry);
			}
		}
		return bytes;
	}

	// ========== Internal helpers ==========

	/**
	 * Computes the CellExplorer budget for an ancestor at a given depth
	 * (0 = immediate parent, 1 = grandparent, etc.).
	 */
	static int budgetForDepth(long depth) {
		int budget = (int) (PARENT_BUDGET * Math.pow(ANCESTOR_DECAY, depth));
		return Math.max(MIN_ANCESTOR_BUDGET, budget);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> ensureMap(ACell cell) {
		return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
	}
}
