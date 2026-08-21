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
	public static AVector<ACell> renderFull(AMap<AString, ACell> frame) {
		return renderFull(frame, Labels.BRACKET);
	}

	@SuppressWarnings("unchecked")
	public static AVector<ACell> renderFull(AMap<AString, ACell> frame, AString dialect) {
		AVector<ACell> conversation = (AVector<ACell>) frame.get(GoalTreeContext.K_CONVERSATION);
		if (conversation == null || conversation.count() == 0) return Vectors.empty();

		AVector<ACell> messages = Vectors.empty();
		for (long i = 0; i < conversation.count(); i++) {
			ACell entry = conversation.get(i);
			if (GoalTreeContext.isSegment(entry)) {
				messages = messages.conj(renderSegment(entry, dialect));
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
	public static AVector<ACell> renderElidingPriorScratch(AMap<AString, ACell> frame) {
		return renderElidingPriorScratch(frame, Labels.BRACKET);
	}

	@SuppressWarnings("unchecked")
	public static AVector<ACell> renderElidingPriorScratch(AMap<AString, ACell> frame, AString dialect) {
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
				messages = messages.conj(renderSegment(entry, dialect));
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
		return renderFor(frame, config, Labels.BRACKET);
	}

	public static AVector<ACell> renderFor(AMap<AString, ACell> frame,
			AMap<AString, ACell> config, AString dialect) {
		AString mode = (config != null) ? RT.ensureString(config.get(K_RENDER_HISTORY)) : null;
		return RENDER_HISTORY_FULL.equals(mode)
			? renderFull(frame, dialect) : renderElidingPriorScratch(frame, dialect);
	}

	/**
	 * Converts one stored turn or live inbox envelope into the provider-facing
	 * message shape {@code {role, content, toolCalls?, id?, name?, structuredContent?, isError?}}:
	 * content stringified (JSON, never EDN), framework metadata such as
	 * {@code ts}, {@code source} and {@code caller} dropped.
	 *
	 * @param value stored turn, inbox envelope, or raw message string
	 * @param defaultRole role used when {@code value} has none; null requires one
	 * @return the message, or null when no role can be established
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> toMessage(ACell value, AString defaultRole) {
		AString role = defaultRole;
		ACell content = value;
		AMap<AString, ACell> source = null;
		if (value instanceof AMap<?, ?> raw) {
			source = (AMap<AString, ACell>) raw;
			AString sourceRole = RT.ensureString(source.get(GoalTreeContext.K_ROLE));
			if (sourceRole != null) role = sourceRole;
			content = source.get(covia.api.Fields.MESSAGE);
			if (content == null) content = source.get(GoalTreeContext.K_CONTENT);
			// A malformed envelope with a default role still renders, as itself.
			if (content == null && defaultRole != null) content = source;
		}
		if (role == null) return null;
		AString text = (content instanceof AString s) ? s
			: (content == null) ? Strings.EMPTY : convex.core.util.JSON.print(content);
		AMap<AString, ACell> message = Maps.of(GoalTreeContext.K_ROLE, role, GoalTreeContext.K_CONTENT, text);
		if (source == null) return message;
		for (AString key : java.util.List.of(
				GoalTreeContext.K_TOOL_CALLS, Strings.intern("id"), Strings.intern("name"),
				covia.api.Fields.STRUCTURED_CONTENT, Strings.intern("isError"))) {
			ACell fieldValue = source.get(key);
			if (fieldValue != null) message = message.assoc(key, fieldValue);
		}
		return message;
	}

	@SuppressWarnings("unchecked")
	private static boolean hasToolCalls(AMap<AString, ACell> turn) {
		ACell calls = turn.get(GoalTreeContext.K_TOOL_CALLS);
		return calls instanceof AVector && ((AVector<ACell>) calls).count() > 0;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> renderSegment(ACell entry, AString dialect) {
		AMap<AString, ACell> segment = (AMap<AString, ACell>) entry;
		AString summary = RT.ensureString(segment.get(GoalTreeContext.K_SUMMARY));
		ACell turns = segment.get(GoalTreeContext.K_TURNS);
		return Labels.message(GoalTreeContext.ROLE_SYSTEM, dialect, Labels.Kind.COMPACTED,
			(summary != null) ? summary.toString() : "",
			(turns != null) ? turns.toString() : "?");
	}
}
