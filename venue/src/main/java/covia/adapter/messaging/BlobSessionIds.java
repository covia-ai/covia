package covia.adapter.messaging;

import static covia.venue.migration.Records.convertAt;
import static covia.venue.migration.Records.mapValues;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.HexId;
import covia.venue.migration.MigrationStep;

/**
 * Messaging adapter state version 1 to 2: the agent session each conversation
 * continues, and the session and chat job a delivery receipt names, are
 * {@link Blob}s, where earlier versions wrote them as hex text.
 *
 * <p>A user's bots keep their state under {@code users/<owner>}, a bot from
 * configuration under {@code config/<bot>}. Below either, {@code sessions}
 * maps each conversation to its session at a depth that differs by provider,
 * so every value in it is a session id; an empty one named no session and
 * goes. {@code inbox} holds delivery receipts by bot, binding and event:
 * {@code inbox/<bot>/<binding>/<event>} for a user's bot,
 * {@code inbox/<binding>/<event>} for one from configuration. Nothing else
 * changes. An id that is neither a Blob nor hex text is refused.</p>
 */
public final class BlobSessionIds implements MigrationStep {

	/** Refusal: a stored session or job id is not an id. */
	public static final String NOT_AN_ID = "A messaging bot's stored session or job id is neither a blob nor hex text: ";

	private static final AString USERS = Strings.intern("users");
	private static final AString CONFIG = Strings.intern("config");
	private static final AString SESSIONS = Strings.intern("sessions");
	private static final AString INBOX = Strings.intern("inbox");

	@Override
	public ACell apply(ACell state) {
		ACell result = convertAt(state, USERS, eachValue(owner -> place(owner, 3)));
		return convertAt(result, CONFIG, eachValue(bot -> place(bot, 2)));
	}

	/** An owner's or configured bot's state: its sessions, and its receipts {@code depth} levels below its inbox. */
	private static ACell place(ACell state, int depth) {
		ACell result = convertAt(state, SESSIONS, BlobSessionIds::sessions);
		return convertAt(result, INBOX, inbox -> receipts(inbox, depth));
	}

	/** Conversation mappings: each value a session, or a further level of them; null for one that names none. */
	@SuppressWarnings("unchecked")
	static ACell sessions(ACell mappings) {
		if (!(mappings instanceof AMap<?, ?> map)) {
			return (mappings == null || (mappings instanceof AString text && text.isEmpty())) ? null : id(mappings);
		}
		AMap<ACell, ACell> result = (AMap<ACell, ACell>) map;
		for (var entry : ((AMap<ACell, ACell>) map).entrySet()) {
			ACell converted = sessions(entry.getValue());
			if (converted == null) result = result.dissoc(entry.getKey());
			else if (converted != entry.getValue()) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	/** The receipts {@code depth} levels below {@code level}. */
	private static ACell receipts(ACell level, int depth) {
		if (depth == 0) return convertAt(convertAt(level, Fields.SESSION_ID, BlobSessionIds::id), Fields.JOB_ID, BlobSessionIds::id);
		return eachValue(child -> receipts(child, depth - 1)).apply(level);
	}

	private static UnaryOperator<ACell> eachValue(UnaryOperator<ACell> convert) {
		return value -> (value instanceof AMap<?, ?> map) ? mapValues(map, convert) : value;
	}

	/** An id as a Blob: the same cell when it is one. */
	static ACell id(ACell cell) {
		Blob id = HexId.parse(cell);
		if (id == null) throw new IllegalStateException(NOT_AN_ID + cell);
		return id;
	}
}
