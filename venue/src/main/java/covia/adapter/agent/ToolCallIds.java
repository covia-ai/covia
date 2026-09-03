package covia.adapter.agent;

import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/** Shared rules for tool-call ids sent to model providers. */
public final class ToolCallIds {

	/** Conservative grammar accepted by the supported model providers. */
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]+");
	private static final int HASH_CHARS = 40;

	private ToolCallIds() {}

	/** True when an id can be sent unchanged to every supported provider. */
	public static boolean valid(AString id) {
		return id != null && VALID.matcher(id.toString()).matches();
	}

	/**
	 * Preserves an already-valid provider id. A legacy invalid id is mapped
	 * deterministically, so its assistant call and tool result still pair.
	 */
	public static AString normalise(AString id) {
		if (id == null || valid(id)) return id;
		return synthetic("call", id);
	}

	/** Creates a bounded, provider-valid id from a stable Convex value. */
	public static AString synthetic(String prefix, ACell identity) {
		if (prefix == null || !VALID.matcher(prefix).matches()) {
			throw new IllegalArgumentException("tool-call id prefix must match " + VALID.pattern());
		}
		if (identity == null) throw new IllegalArgumentException("tool-call id identity is required");
		String hash = identity.getHash().toHexString();
		return Strings.create(prefix + "-" + hash.substring(0, Math.min(HASH_CHARS, hash.length())));
	}

	/**
	 * Normalises the ids in one canonical message without changing its other
	 * fields. This also makes old persisted frames safe when they are rendered.
	 */
	@SuppressWarnings("unchecked")
	public static AMap<AString, ACell> normaliseMessage(AMap<AString, ACell> message) {
		if (message == null) return null;
		AString role = RT.ensureString(message.get(AbstractLLMAdapter.K_ROLE));
		if (AbstractLLMAdapter.ROLE_TOOL.equals(role)) {
			AString id = RT.ensureString(message.get(AbstractLLMAdapter.K_ID));
			AString normalised = normalise(id);
			return (normalised != null && !normalised.equals(id))
				? message.assoc(AbstractLLMAdapter.K_ID, normalised) : message;
		}
		if (!AbstractLLMAdapter.ROLE_ASSISTANT.equals(role)) return message;

		AVector<ACell> calls = RT.ensureVector(message.get(AbstractLLMAdapter.K_TOOL_CALLS));
		if (calls == null || calls.isEmpty()) return message;
		AVector<ACell> out = Vectors.empty();
		boolean changed = false;
		for (long i = 0; i < calls.count(); i++) {
			ACell value = calls.get(i);
			AMap<AString, ACell> call = RT.ensureMap(value);
			if (call == null) {
				out = out.conj(value);
				continue;
			}
			AString id = RT.ensureString(call.get(AbstractLLMAdapter.K_ID));
			AString normalised = normalise(id);
			if (normalised != null && !normalised.equals(id)) {
				call = call.assoc(AbstractLLMAdapter.K_ID, normalised);
				changed = true;
			}
			out = out.conj(call);
		}
		return changed ? message.assoc(AbstractLLMAdapter.K_TOOL_CALLS, out) : message;
	}
}
