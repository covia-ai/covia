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
 *   <li>Loaded data — inherited + own scoped load events</li>
 *   <li>Conversation — compacted segments + live turns, current frame only</li>
 *   <li>Goal — the subgoal description for the current frame</li>
 * </ol>
 *
 * <p>System prompt and tool schemas are handled separately by ContextAssembler.
 * This class focuses on the goal-tree-specific sections.</p>
 *
 * <h3>Why the layout matters</h3>
 *
 * <p>The progressive-budget ancestor rendering in {@link #renderAncestors}
 * is the central value of the goal-tree design — it's what lets a deep
 * decomposition stay within a fixed context budget while the active frame
 * still gets full fidelity. Removing or flattening it collapses goal-tree
 * to a flat agent. See class-level docs on
 * {@link covia.adapter.agent.GoalTreeAdapter} and
 * {@code venue/docs/GOAL_TREE.md} §"Context Assembly".</p>
 */
public class GoalTreeContext {

	// ========== CVM keys for frame structure ==========

	/** Frame description (the subgoal text that created this frame) */
	static final AString K_DESCRIPTION = Strings.intern("description");

	/** Frame conversation (vector of segments + live turns) */
	static final AString K_CONVERSATION = Strings.intern("conversation");

	/** Frame-scoped loads (map of path → load metadata) */
	static final AString K_LOADS = Strings.intern("loads");

	/** Optional initial provider-facing cache projection for this frame.
	 * Semantic session state never depends on its presence. */
	static final AString K_RENDERED_CONTEXT = Strings.intern("renderedContext");

	/** Latest canonical value and exact appended messages for each watched
	 * context declaration. This is durable frame state, not a runtime cache. */
	static final AString K_OBSERVATIONS = Strings.intern("observations");

	/** Frame lifecycle status: absent while live; {@link #STATUS_COMPLETE} /
	 *  {@link #STATUS_FAILED} written in the same CAS as the terminal turn,
	 *  {@link #STATUS_INTERRUPTED} by an operator-suspend settle. Explicit
	 *  markers, not structural inference — interruption cleanup must distinguish
	 *  a committed terminal child from abandoned execution. */
	static final AString K_STATUS = Strings.intern("status");

	/** The toolCall id of the subgoal call that spawned this (child) frame —
	 *  stamped at push so interruption cleanup can match a child back to its
	 *  parent's dangling toolCall unambiguously (identical descriptions are
	 *  legal in one batch). */
	static final AString K_CALL_ID = Strings.intern("callId");

	static final AString STATUS_COMPLETE    = Strings.intern("complete");
	static final AString STATUS_FAILED      = Strings.intern("failed");
	static final AString STATUS_INTERRUPTED = Strings.intern("interrupted");

	/** Returns the frame with its lifecycle status set. */
	public static AMap<AString, ACell> withStatus(AMap<AString, ACell> frame, AString status) {
		return frame.assoc(K_STATUS, status);
	}

	/** The frame's lifecycle status, or null while live. */
	public static AString getStatus(AMap<AString, ACell> frame) {
		return (frame.get(K_STATUS) instanceof AString s) ? s : null;
	}

	/** The frame's spawning toolCall id (child frames), or null. */
	public static AString getCallId(AMap<AString, ACell> frame) {
		return (frame.get(K_CALL_ID) instanceof AString s) ? s : null;
	}

	// ========== Interrupted-conversation repair (pure) ==========

	/**
	 * Repairs an interrupted frame stack: every dangling toolCall — an
	 * entry of an assistant turn's {@code toolCalls} with no later tool-result
	 * turn for its id — gets a synthetic tool-result turn appended, so the
	 * conversation is provider-valid again (tool results must follow their
	 * assistant message) and the model learns what happened. Never
	 * re-executes anything: the note tells the agent the call's effects may
	 * or may not have applied, and the agent decides (autonomy principle).
	 *
	 * <p>A dangling call whose id matches a deeper frame's {@code callId} is
	 * an unfinished subgoal — skipped here; the interruption cleanup pops it with the
	 * child's own outcome. Null-id calls are matched positionally within
	 * their batch. At most one synthetic result per call, ever.</p>
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> repairDanglingToolCalls(AVector<ACell> frames, AString note) {
		// ids handled by live child frames (any depth)
		java.util.Set<String> childCallIds = new java.util.HashSet<>();
		for (long i = 0; i < frames.count(); i++) {
			AString cid = getCallId((AMap<AString, ACell>) frames.get(i));
			if (cid != null) childCallIds.add(cid.toString());
		}

		AVector<ACell> repaired = frames;
		for (long fi = 0; fi < frames.count(); fi++) {
			AMap<AString, ACell> frame = (AMap<AString, ACell>) repaired.get(fi);
			AVector<ACell> conv = (frame.get(K_CONVERSATION) instanceof AVector cv)
				? (AVector<ACell>) cv : Vectors.empty();
			AVector<ACell> fixed = conv;
			for (long t = 0; t < conv.count(); t++) {
				ACell turn = conv.get(t);
				if (!ROLE_ASSISTANT.equals(RT.getIn(turn, K_ROLE))) continue;
				ACell tcs = RT.getIn(turn, K_TOOL_CALLS);
				if (!(tcs instanceof AVector<?> calls) || calls.count() == 0) continue;
				for (long c = 0; c < calls.count(); c++) {
					ACell tc = calls.get(c);
					AString id = RT.ensureString(RT.getIn(tc, Strings.intern("id")));
					AString name = RT.ensureString(RT.getIn(tc, Strings.intern("name")));
					if (id != null && childCallIds.contains(id.toString())) continue;
					if (hasToolResultAfter(fixed, t, id, c)) continue;
					AMap<AString, ACell> synthetic = Maps.of(
						K_ROLE, ROLE_TOOL,
						Strings.intern("id"), (id != null) ? id : Strings.intern("unknown"),
						Strings.intern("name"), (name != null) ? name : Strings.intern("unknown"),
						K_CONTENT, note);
					fixed = fixed.conj(synthetic);
				}
			}
			if (fixed != conv) {
				repaired = repaired.assoc(fi, frame.assoc(K_CONVERSATION, fixed));
			}
		}
		return repaired;
	}

	/**
	 * True if the frame's conversation contains a tool-result turn with the
	 * given id — the frame-settling pop-dedupe: never synthesise a second
	 * result for a call the parent already has one for.
	 */
	@SuppressWarnings("unchecked")
	public static boolean hasToolResultFor(AMap<AString, ACell> frame, AString id) {
		if (id == null) return false;
		AVector<ACell> conv = (frame.get(K_CONVERSATION) instanceof AVector cv)
			? (AVector<ACell>) cv : Vectors.empty();
		for (long i = 0; i < conv.count(); i++) {
			ACell turn = conv.get(i);
			if (ROLE_TOOL.equals(RT.getIn(turn, K_ROLE))
					&& id.equals(RT.getIn(turn, Strings.intern("id")))) return true;
		}
		return false;
	}

	/**
	 * True if a tool-result turn answering the given call exists after
	 * position {@code assistantIdx}. Id-matched when the call has an id;
	 * positional fallback (the {@code callIdx}-th tool turn following the
	 * assistant turn) for providers that omit ids.
	 */
	private static boolean hasToolResultAfter(AVector<ACell> conv, long assistantIdx,
			AString id, long callIdx) {
		long toolTurnsSeen = 0;
		for (long i = assistantIdx + 1; i < conv.count(); i++) {
			ACell turn = conv.get(i);
			if (!ROLE_TOOL.equals(RT.getIn(turn, K_ROLE))) continue;
			if (id != null) {
				if (id.equals(RT.getIn(turn, Strings.intern("id")))) return true;
			} else {
				if (toolTurnsSeen == callIdx) return true;
			}
			toolTurnsSeen++;
		}
		return false;
	}

	/**
	 * Best-effort result value of a terminal frame, for a pop that must be
	 * synthesised while settling an interrupted stack: a text-completed frame's value is its final
	 * assistant turn's content. Returns null when unrecoverable (e.g. the
	 * complete() tool's input was the value and is not stored) — the caller
	 * substitutes an honest "result may be lost" note.
	 */
	@SuppressWarnings("unchecked")
	public static ACell terminalValue(AMap<AString, ACell> frame) {
		AVector<ACell> conv = (frame.get(K_CONVERSATION) instanceof AVector cv)
			? (AVector<ACell>) cv : Vectors.empty();
		if (conv.count() == 0) return null;
		ACell last = conv.get(conv.count() - 1);
		if (ROLE_ASSISTANT.equals(RT.getIn(last, K_ROLE))
				&& RT.getIn(last, K_TOOL_CALLS) == null) {
			return RT.getIn(last, K_CONTENT);
		}
		return null;
	}

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
	static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	static final AString ROLE_SYSTEM = Strings.intern("system");
	static final AString ROLE_USER = Strings.intern("user");
	static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	static final AString ROLE_TOOL = Strings.intern("tool");

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
		return withLoads(createFrame(description), loads);
	}

	// ========== Root goal description generators ==========

	/**
	 * Generates a root goal description from an inbox message.
	 * Inbox messages have {content, caller?} structure.
	 */
	public static String describeMessage(ACell message) {
		AString content = RT.ensureString(RT.getIn(message, K_CONTENT));
		if (content == null) {
			// Chat/message envelopes use "message" not "content"
			content = RT.ensureString(RT.getIn(message, Strings.intern("message")));
		}
		AString caller = RT.ensureString(RT.getIn(message, Strings.intern("caller")));
		String text = (content != null) ? truncate(content.toString(), 200) : "(empty message)";
		if (caller != null) {
			return "Message from " + caller + ": " + text;
		}
		return text;
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

		// Single task — use its input directly (the task IS the user's request)
		if (taskCount == 1 && msgCount == 0 && pendingCount == 0) {
			ACell inputCell = RT.getIn(tasks.get(0), Strings.intern("input"));
			if (inputCell == null) return "Task: (no input)";
			if (inputCell instanceof AString s) return s.toString();
			// Structured input — check for message field, else render as JSON
			AString msgField = RT.ensureString(RT.getIn(inputCell, Strings.intern("message")));
			if (msgField != null) return msgField.toString();
			return convex.core.util.JSON.toString(inputCell);
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
	 * Creates a compacted segment from the exact conversation prefix it replaces.
	 * Items may themselves be compacted segments; {@code turns} is therefore the
	 * recursive leaf-turn count, not merely {@code items.count()}. Keeping the
	 * original vector under the segment gives the lattice an auditable,
	 * structurally-shared archive without a second transcript store.
	 */
	public static AMap<AString, ACell> createSegment(String summary, AVector<ACell> items) {
		return Maps.of(
			K_SUMMARY, Strings.create(summary),
			K_TURNS, CVMLong.create(turnCount(items)),
			K_ITEMS, items);
	}

	/** Recursive number of ordinary conversation turns archived by entries. */
	static long turnCount(AVector<ACell> entries) {
		long count = 0;
		for (long i = 0; entries != null && i < entries.count(); i++) {
			ACell entry = entries.get(i);
			if (isSegment(entry)) {
				ACell turns = RT.getIn(entry, K_TURNS);
				if (turns instanceof CVMLong n) count += n.longValue();
			} else if (isLiveTurn(entry)) {
				count++;
			}
		}
		return count;
	}

	/** Total ordinary turns represented by a frame, including nested archives. */
	public static long countTurns(AMap<AString, ACell> frame) {
		return turnCount(RT.ensureVector(frame != null ? frame.get(K_CONVERSATION) : null));
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
	 * Renders ancestor frames as a single system message. Each ancestor's
	 * conversation is rendered by CellExplorer at a decreasing byte budget
	 * (parent ~300B, grandparent ~150B, great-grandparent ~80B, floor at
	 * {@link #MIN_ANCESTOR_BUDGET}).
	 *
	 * <p><b>This is the core of the goal-tree value proposition.</b> Without
	 * progressive ancestor compaction, every level of decomposition would
	 * push the conversation transcript verbatim into descendants — a 50-turn
	 * child of a 50-turn parent of a 50-turn grandparent would inherit ~150
	 * turns of context, defeating the purpose of decomposition. With it, the
	 * cost of being deep in the stack is bounded regardless of ancestor
	 * conversation length.</p>
	 *
	 * <p><b>Don't be tempted to drop this</b> as a "redundant" pass — the
	 * subgoal description is an explicit handoff but does <i>not</i> capture
	 * the parent's tool calls, intermediate findings, or reasoning. The
	 * ancestor summary surfaces the live state of the parent's work to the
	 * child, lossily but bounded. See class-level javadoc and
	 * {@code venue/docs/GOAL_TREE.md} §"Context Assembly" (lines 147–187).</p>
	 *
	 * @param frames the full frame stack (last element is active frame)
	 * @return system message with rendered ancestor context, or null if root frame
	 */
	public static AMap<AString, ACell> renderAncestors(AVector<ACell> frames) {
		return renderAncestors(frames, Labels.BRACKET);
	}

	public static AMap<AString, ACell> renderAncestors(AVector<ACell> frames, AString dialect) {
		if (frames == null || frames.count() <= 1) return null;

		StringBuilder sb = new StringBuilder();
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

		return Labels.message(ROLE_SYSTEM, dialect, Labels.Kind.ANCESTORS, sb.toString());
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
			Strings.create(desc));
	}

	/**
	 * Compacts the frame's complete visible conversation into one new segment.
	 * Existing segments are nested in {@code items}, rather than retained as
	 * siblings, so repeated compaction keeps provider-visible history bounded
	 * while preserving every replaced cell for audit and later inspection.
	 *
	 * @param frame the frame to compact
	 * @param summary LLM-provided summary of the compacted turns
	 * @return new frame with compacted conversation
	 */
	public static AMap<AString, ACell> compactFrame(AMap<AString, ACell> frame, String summary) {
		return compactFrame(frame, summary, null);
	}

	/** Compacts with optional non-rendered provenance attached to the archive. */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> compactFrame(AMap<AString, ACell> frame,
			String summary, AMap<AString, ACell> provenance) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(K_CONVERSATION);
		if (conversation == null || conversation.count() == 0) return frame;

		if (countLiveTurns(frame) == 0) return frame;

		// The old vector itself is the archive. Convex cells are immutable, so
		// this retains exact history through structural sharing rather than copy.
		AMap<AString, ACell> segment = createSegment(summary, conversation);
		if (provenance != null) {
			for (var entry : provenance.entrySet()) {
				if (segment.get(entry.getKey()) == null) {
					segment = segment.assoc(entry.getKey(), entry.getValue());
				}
			}
		}
		// A compacted summary replaces history, not current watched state. Reuse
		// the exact latest observation messages after the archive so no source is
		// re-read and the model still has the active snapshot.
		AVector<ACell> newConversation = Vectors.of((ACell) segment);
		newConversation = (AVector<ACell>) newConversation.concat(latestObservationMessages(frame));
		// Compaction is an explicit prefix rewrite boundary. The next inference
		// materialises a new initial representation around the compacted history.
		return frame.assoc(K_CONVERSATION, newConversation).dissoc(K_RENDERED_CONTEXT);
	}

	/** Persists the exact initial vectors on a frame. */
	static AMap<AString, ACell> withRenderedContext(AMap<AString, ACell> frame,
			ContextAssembler.Rendered rendered) {
		return frame.assoc(K_RENDERED_CONTEXT, rendered.toCell());
	}

	/** Clears only the materialised provider prefix. Conversation, loads and
	 * frame state remain byte-identical; the next inference rebuilds the prefix
	 * from current agent configuration. */
	public static AMap<AString, ACell> reloadContext(AMap<AString, ACell> frame) {
		return frame.dissoc(K_RENDERED_CONTEXT);
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

	// ========== Watched context observations ==========

	/**
	 * Atomically-applicable pure frame transform for watched context. Candidate
	 * values were materialised before this call. Equal canonical values retain
	 * the exact prior messages and append nothing; changes append the new
	 * provider messages and removals append one venue-authored state event.
	 */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> applyObservations(AMap<AString, ACell> frame,
			AVector<ACell> candidates, long timestamp) {
		if (frame == null) return null;
		AMap<AString, ACell> previous = (frame.get(K_OBSERVATIONS) instanceof AMap<?, ?> raw)
			? (AMap<AString, ACell>) raw : Maps.empty();
		AMap<AString, ACell> next = previous;
		AVector<ACell> conversation = (frame.get(K_CONVERSATION) instanceof AVector<?> raw)
			? (AVector<ACell>) raw : Vectors.empty();
		java.util.Set<AString> active = new java.util.HashSet<>();

		for (long i = 0; candidates != null && i < candidates.count(); i++) {
			AMap<AString, ACell> candidate = RT.ensureMap(candidates.get(i));
			if (candidate == null) continue;
			AString id = RT.ensureString(candidate.get(ContextAssembler.K_OBSERVATION_ID));
			AVector<ACell> value = RT.ensureVector(
				candidate.get(ContextAssembler.K_OBSERVATION_VALUE));
			if (id == null || value == null || !active.add(id)) continue;
			ACell order = candidate.get(ContextAssembler.K_OBSERVATION_ORDER);
			AMap<AString, ACell> old = RT.ensureMap(previous.get(id));
			if (old != null && value.equals(old.get(ContextAssembler.K_OBSERVATION_VALUE))) {
				AMap<AString, ACell> retained = old;
				if (order != null && !order.equals(old.get(ContextAssembler.K_OBSERVATION_ORDER))) {
					retained = retained.assoc(ContextAssembler.K_OBSERVATION_ORDER, order);
				}
				if (retained != old) next = next.assoc(id, retained);
				continue;
			}

			AVector<ACell> messages = stampObservationMessages(value, timestamp);
			conversation = (AVector<ACell>) conversation.concat(messages);
			AMap<AString, ACell> observation = Maps.of(
				ContextAssembler.K_OBSERVATION_VALUE, value,
				ContextAssembler.K_OBSERVATION_MESSAGES, messages,
				ContextAssembler.K_OBSERVATION_ORDER,
				(order != null) ? order : CVMLong.create(i));
			next = next.assoc(id, observation);
		}

		java.util.List<java.util.Map.Entry<AString, ACell>> removed = new java.util.ArrayList<>();
		for (var entry : previous.entrySet()) {
			if (!active.contains(entry.getKey())) removed.add(entry);
		}
		removed.sort(java.util.Comparator
			.comparingLong((java.util.Map.Entry<AString, ACell> e) -> observationOrder(e.getValue()))
			.thenComparing(e -> e.getKey().toString()));
		for (var entry : removed) {
			AVector<ACell> event = stampObservationMessages(Vectors.of(
				ContextAssembler.observationRemoved(entry.getKey())), timestamp);
			conversation = (AVector<ACell>) conversation.concat(event);
			next = next.dissoc(entry.getKey());
		}

		AMap<AString, ACell> updated = frame;
		AVector<ACell> oldConversation = RT.ensureVector(frame.get(K_CONVERSATION));
		if (oldConversation == null || !conversation.equals(oldConversation)) {
			updated = updated.assoc(K_CONVERSATION, conversation);
		}
		if (next.isEmpty()) {
			if (updated.get(K_OBSERVATIONS) != null) updated = updated.dissoc(K_OBSERVATIONS);
		} else if (!next.equals(previous)) {
			updated = updated.assoc(K_OBSERVATIONS, next);
		}
		return updated;
	}

	private static long observationOrder(ACell value) {
		ACell order = RT.getIn(value, ContextAssembler.K_OBSERVATION_ORDER);
		return (order instanceof CVMLong n) ? n.longValue() : Long.MAX_VALUE;
	}

	/** Adds audit metadata after canonical comparison; it never participates in
	 * change detection and therefore cannot create clock-driven observations. */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> stampObservationMessages(
			AVector<ACell> messages, long timestamp) {
		AVector<ACell> stamped = Vectors.empty();
		for (long i = 0; messages != null && i < messages.count(); i++) {
			ACell value = messages.get(i);
			if (!(value instanceof AMap<?, ?> raw)) {
				stamped = stamped.conj(value);
				continue;
			}
			AMap<AString, ACell> message = (AMap<AString, ACell>) raw;
			AString role = RT.ensureString(message.get(K_ROLE));
			AString source = ROLE_TOOL.equals(role)
				? covia.venue.AgentState.SOURCE_TOOL
				: covia.venue.AgentState.SOURCE_TRANSITION;
			stamped = stamped.conj(message
				.assoc(covia.venue.AgentState.K_TURN_TS, CVMLong.create(timestamp))
				.assoc(covia.venue.AgentState.K_SOURCE, source));
		}
		return stamped;
	}

	/** Exact latest observation messages in their current declaration order. */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> latestObservationMessages(AMap<AString, ACell> frame) {
		if (!(frame.get(K_OBSERVATIONS) instanceof AMap<?, ?> raw)) return Vectors.empty();
		AMap<AString, ACell> observations = (AMap<AString, ACell>) raw;
		java.util.List<java.util.Map.Entry<AString, ACell>> ordered = new java.util.ArrayList<>();
		for (var entry : observations.entrySet()) ordered.add(entry);
		ordered.sort(java.util.Comparator
			.comparingLong((java.util.Map.Entry<AString, ACell> e) -> observationOrder(e.getValue()))
			.thenComparing(e -> e.getKey().toString()));
		AVector<ACell> messages = Vectors.empty();
		for (var entry : ordered) {
			AVector<ACell> value = RT.ensureVector(RT.getIn(
				entry.getValue(), ContextAssembler.K_OBSERVATION_MESSAGES));
			if (value != null) messages = (AVector<ACell>) messages.concat(value);
		}
		return messages;
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

	// ========== Scoped loads ==========

	/**
	 * Returns the loads map for a frame, or empty if none.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> getLoads(AMap<AString, ACell> frame) {
		ACell loads = frame.get(K_LOADS);
		return (loads instanceof AMap) ? (AMap<AString, ACell>) loads : Maps.empty();
	}

	/** Returns the root frame's loads, or empty for an absent/malformed root. */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> rootLoads(AVector<ACell> frames) {
		if (frames == null || frames.isEmpty() || !(frames.get(0) instanceof AMap root)) {
			return Maps.empty();
		}
		return getLoads((AMap<AString, ACell>) root);
	}

	/** Replaces the root frame's loads, preserving every other frame field. */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> withRootLoads(AVector<ACell> frames,
			AMap<AString, ACell> loads) {
		if (frames == null || frames.isEmpty() || !(frames.get(0) instanceof AMap root)) {
			return frames;
		}
		return frames.assoc(0, withLoads((AMap<AString, ACell>) root, loads));
	}

	/** Replaces a frame's loads, omitting the optional field when empty. */
	public static AMap<AString, ACell> withLoads(AMap<AString, ACell> frame,
			AMap<AString, ACell> loads) {
		return (loads == null || loads.isEmpty())
			? frame.dissoc(K_LOADS) : frame.assoc(K_LOADS, loads);
	}

	/**
	 * Adds or replaces a load entry in a frame's loads map.
	 *
	 * @param frame the frame to modify
	 * @param path the lattice path being loaded
	 * @param meta load metadata (budget, ts, label)
	 * @return updated frame
	 */
	public static AMap<AString, ACell> addLoad(AMap<AString, ACell> frame,
			AString path, AMap<AString, ACell> meta) {
		AMap<AString, ACell> loads = getLoads(frame);
		return withLoads(frame, loads.assoc(path, meta));
	}

	/**
	 * Removes a load entry from a frame's loads map.
	 *
	 * @param frame the frame to modify
	 * @param path the lattice path to unload
	 * @return updated frame
	 */
	public static AMap<AString, ACell> removeLoad(AMap<AString, ACell> frame, AString path) {
		AMap<AString, ACell> loads = getLoads(frame);
		AMap<AString, ACell> updated = loads.dissoc(path);
		return withLoads(frame, updated);
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
