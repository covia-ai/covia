package covia.adapter.agent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.venue.AgentState;

/**
 * Provider-neutral rendering policy for persisted agent conversations.
 *
 * <p>Both flat LLM agents and goal-tree agents persist the active conversation
 * in the same frame shape. This class owns the shared provider-facing view:
 * compacted segments become system messages, completed tool exchanges are
 * elided by default, and the current in-flight tool cycle remains structurally
 * intact. The durable frame is never mutated.</p>
 */
public final class ConversationRenderer {

	private static final AString K_RENDER_HISTORY = Strings.intern("renderHistory");
	private static final AString RENDER_HISTORY_FULL = Strings.intern("full");

	private ConversationRenderer() {}

	/** Renders every live turn and compacted segment in a frame. */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> renderFull(AMap<AString, ACell> frame) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(GoalTreeContext.K_CONVERSATION);
		if (conversation == null || conversation.count() == 0) return Vectors.empty();

		AVector<ACell> messages = Vectors.empty();
		for (long i = 0; i < conversation.count(); i++) {
			ACell entry = conversation.get(i);
			if (GoalTreeContext.isSegment(entry)) {
				messages = messages.conj(renderSegment(entry));
			} else if (GoalTreeContext.isLiveTurn(entry)) {
				messages = messages.conj(entry);
			}
		}
		return messages;
	}

	/**
	 * Renders prior completed cycles as user/final-assistant pairs while
	 * retaining the active cycle in full. Assistant tool calls and their tool
	 * results are therefore either both present or both absent, as required by
	 * Anthropic and other tool-use protocols.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> renderElidingPriorScratch(AMap<AString, ACell> frame) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(GoalTreeContext.K_CONVERSATION);
		if (conversation == null || conversation.count() == 0) return Vectors.empty();

		long count = conversation.count();
		long activeCycleStart = 0;
		for (long i = count - 1; i >= 0; i--) {
			ACell entry = conversation.get(i);
			if (GoalTreeContext.isSegment(entry)) continue;
			if (GoalTreeContext.isLiveTurn(entry)
					&& GoalTreeContext.ROLE_USER.equals(RT.ensureString(
						((AMap<AString, ACell>) entry).get(GoalTreeContext.K_ROLE)))) {
				activeCycleStart = i;
				break;
			}
		}
		for (long i = activeCycleStart + 1; i < count; i++) {
			ACell entry = conversation.get(i);
			if (!GoalTreeContext.isLiveTurn(entry)) continue;
			AMap<AString, ACell> turn = (AMap<AString, ACell>) entry;
			if (GoalTreeContext.ROLE_ASSISTANT.equals(RT.ensureString(
					turn.get(GoalTreeContext.K_ROLE))) && !hasToolCalls(turn)) {
				activeCycleStart = count;
			}
		}

		AVector<ACell> messages = Vectors.empty();
		for (long i = 0; i < count; i++) {
			ACell entry = conversation.get(i);
			if (GoalTreeContext.isSegment(entry)) {
				messages = messages.conj(renderSegment(entry));
				continue;
			}
			if (!GoalTreeContext.isLiveTurn(entry)) continue;
			AMap<AString, ACell> turn = (AMap<AString, ACell>) entry;

			if (i >= activeCycleStart) {
				messages = messages.conj(turn);
				continue;
			}

			AString role = RT.ensureString(turn.get(GoalTreeContext.K_ROLE));
			if (GoalTreeContext.ROLE_USER.equals(role)) {
				messages = messages.conj(turn);
			} else if (GoalTreeContext.ROLE_ASSISTANT.equals(role) && !hasToolCalls(turn)) {
				messages = messages.conj(turn);
			} else if (GoalTreeContext.ROLE_SYSTEM.equals(role)
					&& AgentState.SOURCE_TOOL.equals(RT.getIn(turn, AgentState.K_SOURCE))) {
				messages = messages.conj(turn);
			}
		}
		return messages;
	}

	/** Uses full history only when explicitly requested; elision is the default. */
	public static AVector<ACell> renderFor(AMap<AString, ACell> frame,
			AMap<AString, ACell> config) {
		AString mode = (config != null) ? RT.ensureString(config.get(K_RENDER_HISTORY)) : null;
		return RENDER_HISTORY_FULL.equals(mode)
			? renderFull(frame) : renderElidingPriorScratch(frame);
	}

	@SuppressWarnings("unchecked")
	private static boolean hasToolCalls(AMap<AString, ACell> turn) {
		ACell calls = turn.get(GoalTreeContext.K_TOOL_CALLS);
		return calls instanceof AVector && ((AVector<ACell>) calls).count() > 0;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> renderSegment(ACell entry) {
		AMap<AString, ACell> segment = (AMap<AString, ACell>) entry;
		AString summary = RT.ensureString(segment.get(GoalTreeContext.K_SUMMARY));
		ACell turns = segment.get(GoalTreeContext.K_TURNS);
		return Labels.message(GoalTreeContext.ROLE_SYSTEM, Labels.BRACKET, Labels.Kind.COMPACTED,
			(summary != null) ? summary.toString() : "",
			(turns != null) ? turns.toString() : "?");
	}
}
