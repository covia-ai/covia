package covia.adapter.agent;

import java.util.ArrayDeque;

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
import covia.venue.AgentState;

/**
 * Provider-neutral rendering policy for persisted agent conversations.
 *
 * <p>Both flat LLM agents and goal-tree agents persist the active conversation
 * in the same frame shape. This class owns the shared provider-facing view:
 * compacted segments become assistant memory messages, completed tool exchanges are
 * elided by default, and the current in-flight tool cycle remains structurally
 * intact. The durable frame is never mutated.</p>
 */
public final class ConversationRenderer {

	private static final AString K_TRUNCATED = Strings.intern("truncated");

	private ConversationRenderer() {}

	/**
	 * Safe, bounded projection of a stored conversation for retrospective use.
	 * Only completed conversational cycles are exposed: user turns followed by
	 * a final assistant text turn. Tool calls, tool results, diagnostics and an
	 * unanswered tail are omitted. Compacted segments are already summaries and
	 * remain visible as assistant messages; an agent-authored summary never gains
	 * system authority merely because the venue persisted it.
	 */
	public record HistoricalView(AVector<ACell> messages, AString firstUserContent,
			long updated, long turnCount, boolean truncated) {}

	/**
	 * Builds the historical projection while retaining at most {@code maxTurns}
	 * turns and {@code maxChars} content characters, always favouring the newest
	 * content. A zero turn limit is useful for metadata-only scans.
	 */
	@SuppressWarnings("unchecked")
	public static HistoricalView historical(AMap<AString, ACell> frame,
			int maxTurns, int maxChars) {
		return historical(frame, maxTurns, maxChars, 0);
	}

	/** Historical projection with bounded recursive expansion of compacted
	 * archives. Depth zero shows summaries; each higher level opens one nested
	 * {@code items} vector before applying the same safe turn projection. */
	@SuppressWarnings("unchecked")
	public static HistoricalView historical(AMap<AString, ACell> frame,
			int maxTurns, int maxChars, int archiveDepth) {
		if (maxTurns < 0 || maxChars < 0) {
			throw new IllegalArgumentException("Historical view limits must be non-negative");
		}
		if (archiveDepth < 0) {
			throw new IllegalArgumentException("Archive depth must be non-negative");
		}
		AVector<ACell> conversation = (frame != null)
			? RT.ensureVector(frame.get(GoalTreeContext.K_CONVERSATION)) : null;
		if (conversation == null || conversation.isEmpty()) {
			return new HistoricalView(Vectors.empty(), null, 0, 0, false);
		}

		conversation = expandArchives(conversation, archiveDepth);
		ArrayDeque<AMap<AString, ACell>> tail = new ArrayDeque<>();
		ArrayDeque<AMap<AString, ACell>> pending = new ArrayDeque<>();
		long pendingCount = 0;
		long pendingUpdated = 0;
		AString pendingFirst = null;
		AString firstCompletedUser = null;
		long updated = 0;
		long turnCount = 0;

		for (long i = 0; i < conversation.count(); i++) {
			ACell entry = conversation.get(i);
			if (GoalTreeContext.isSegment(entry)) {
				ACell archivedTurns = RT.getIn(entry, GoalTreeContext.K_TURNS);
				turnCount += (archivedTurns instanceof CVMLong n) ? n.longValue() : 1;
				if (firstCompletedUser == null) firstCompletedUser = firstUser(entry);
				updated = Math.max(updated, latestTimestamp(entry));
				retain(tail, renderSegment(entry, Labels.BRACKET), maxTurns);
				continue;
			}
			if (!GoalTreeContext.isLiveTurn(entry)) continue;
			AMap<AString, ACell> turn = (AMap<AString, ACell>) entry;
			AString role = RT.ensureString(turn.get(GoalTreeContext.K_ROLE));
			if (GoalTreeContext.ROLE_USER.equals(role)) {
				AMap<AString, ACell> message = toMessage(turn, null);
				if (message == null) continue;
				AString content = RT.ensureString(message.get(GoalTreeContext.K_CONTENT));
				if (pendingFirst == null) pendingFirst = content;
				pendingCount++;
				pendingUpdated = Math.max(pendingUpdated, timestamp(turn));
				retain(pending, message, maxTurns);
				continue;
			}
			if (!GoalTreeContext.ROLE_ASSISTANT.equals(role) || hasToolCalls(turn)
					|| pendingCount == 0) continue;

			if (firstCompletedUser == null) firstCompletedUser = pendingFirst;
			turnCount += pendingCount + 1;
			for (AMap<AString, ACell> message : pending) {
				retain(tail, message, maxTurns);
			}
			retain(tail, toMessage(turn, null), maxTurns);
			updated = Math.max(updated, Math.max(pendingUpdated, timestamp(turn)));
			pending.clear();
			pendingCount = 0;
			pendingUpdated = 0;
			pendingFirst = null;
		}

		boolean truncated = turnCount > tail.size();
		ArrayDeque<AMap<AString, ACell>> bounded = new ArrayDeque<>();
		int remaining = maxChars;
		Object[] selected = tail.toArray();
		for (int i = selected.length - 1; i >= 0; i--) {
			AMap<AString, ACell> message = (AMap<AString, ACell>) selected[i];
			AString content = RT.ensureString(message.get(GoalTreeContext.K_CONTENT));
			String text = (content != null) ? content.toString() : "";
			if (text.length() <= remaining) {
				bounded.addFirst(message);
				remaining -= text.length();
				continue;
			}
			truncated = true;
			if (remaining > 0) {
				bounded.addFirst(message
					.assoc(GoalTreeContext.K_CONTENT,
						Strings.create(text.substring(text.length() - remaining)))
					.assoc(K_TRUNCATED, CVMBool.TRUE));
			}
			break;
		}
		if (bounded.size() < tail.size()) truncated = true;

		AVector<ACell> messages = Vectors.empty();
		for (AMap<AString, ACell> message : bounded) messages = messages.conj(message);
		return new HistoricalView(messages, firstCompletedUser, updated, turnCount, truncated);
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> expandArchives(AVector<ACell> entries, int depth) {
		if (depth <= 0 || entries == null || entries.isEmpty()) return entries;
		AVector<ACell> expanded = Vectors.empty();
		for (long i = 0; i < entries.count(); i++) {
			ACell entry = entries.get(i);
			if (GoalTreeContext.isSegment(entry)) {
				AVector<ACell> items = RT.ensureVector(RT.getIn(entry, GoalTreeContext.K_ITEMS));
				if (items != null) {
					expanded = expanded.concat(expandArchives(items, depth - 1));
					continue;
				}
			}
			expanded = expanded.conj(entry);
		}
		return expanded;
	}

	private static AString firstUser(ACell entry) {
		if (GoalTreeContext.isSegment(entry)) {
			AVector<ACell> items = RT.ensureVector(RT.getIn(entry, GoalTreeContext.K_ITEMS));
			for (long i = 0; items != null && i < items.count(); i++) {
				AString found = firstUser(items.get(i));
				if (found != null) return found;
			}
			return null;
		}
		if (!GoalTreeContext.ROLE_USER.equals(RT.getIn(entry, GoalTreeContext.K_ROLE))) return null;
		return RT.ensureString(RT.getIn(entry, GoalTreeContext.K_CONTENT));
	}

	private static long latestTimestamp(ACell entry) {
		if (GoalTreeContext.isSegment(entry)) {
			long latest = 0;
			AVector<ACell> items = RT.ensureVector(RT.getIn(entry, GoalTreeContext.K_ITEMS));
			for (long i = 0; items != null && i < items.count(); i++) {
				latest = Math.max(latest, latestTimestamp(items.get(i)));
			}
			return latest;
		}
		return (entry instanceof AMap<?, ?> raw)
			? timestamp((AMap<AString, ACell>) raw) : 0;
	}

	private static void retain(ArrayDeque<AMap<AString, ACell>> values,
			AMap<AString, ACell> value, int limit) {
		if (value == null || limit == 0) return;
		values.addLast(value);
		while (values.size() > limit) values.removeFirst();
	}

	private static long timestamp(AMap<AString, ACell> turn) {
		ACell value = turn.get(Fields.TS);
		return (value instanceof CVMLong ts) ? ts.longValue() : 0;
	}

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
	 * Converts one stored turn or live inbox envelope into the provider-facing
	 * message shape {@code {role, content?, toolCalls?, id?, name?, structuredContent?, isError?}}:
	 * content stringified (JSON, never EDN), framework metadata such as
	 * {@code ts}, {@code source} and {@code caller} dropped. A structured-only
	 * tool result keeps content absent so the provider edge can render the
	 * structured value rather than mistake an invented empty string for it.
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
		boolean structuredOnlyTool = content == null
			&& GoalTreeContext.ROLE_TOOL.equals(role)
			&& source != null
			&& source.get(covia.api.Fields.STRUCTURED_CONTENT) != null;
		AMap<AString, ACell> message = Maps.of(GoalTreeContext.K_ROLE, role);
		if (!structuredOnlyTool) {
			AString text = (content instanceof AString s) ? s
				: (content == null) ? Strings.EMPTY : convex.core.util.JSON.print(content);
			message = message.assoc(GoalTreeContext.K_CONTENT, text);
		}
		if (source == null) return message;
		for (AString key : java.util.List.of(
			GoalTreeContext.K_TOOL_CALLS, Strings.intern("id"), Strings.intern("name"),
				covia.api.Fields.STRUCTURED_CONTENT, Strings.intern("isError"),
				HarnessTools.K_TOOL_ADDITION, HarnessTools.K_TOOL_REMOVAL)) {
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
		return Labels.message(GoalTreeContext.ROLE_ASSISTANT, dialect, Labels.Kind.COMPACTED,
			(summary != null) ? summary.toString() : "",
			(turns != null) ? turns.toString() : "?");
	}
}
