package covia.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import dev.langchain4j.data.message.AiMessage;

/**
 * Pure conversion at the provider boundary for optional assistant-message
 * continuation state. The durable conversation treats {@code providerState}
 * as opaque; this class is the only code that knows how a supported provider
 * represents it in LangChain4j.
 */
final class ProviderState {

	private static final String ANTHROPIC = "anthropic";
	// LangChain4j documents these AiMessage attribute names as stable for
	// backward compatibility. Keep the internal mapper out of production code.
	private static final String THINKING_SIGNATURE_ATTRIBUTE = "thinking_signature";
	private static final String REDACTED_THINKING_ATTRIBUTE = "redacted_thinking";
	private static final AString K_BLOCKS = Strings.intern("blocks");
	private static final AString K_THINKING = Strings.intern("thinking");
	private static final AString K_SIGNATURE = Strings.intern("signature");
	private static final AString K_DATA = Strings.intern("data");
	private static final AString V_THINKING = Strings.intern("thinking");
	private static final AString V_REDACTED_THINKING = Strings.intern("redacted_thinking");

	private ProviderState() {}

	/**
	 * Captures only state needed to continue an actual provider tool-use turn.
	 * Text and tool calls stay in their canonical fields and are not duplicated.
	 */
	static AMap<AString, ACell> capture(String provider, String model, AiMessage message) {
		if (!ANTHROPIC.equals(provider) || model == null || !message.hasToolExecutionRequests()) return null;

		AVector<ACell> blocks = Vectors.empty();
		String thinking = message.thinking();
		Object rawSignature = message.attributes().get(THINKING_SIGNATURE_ATTRIBUTE);
		String signature = (rawSignature instanceof String s) ? s : null;
		if (thinking != null || signature != null) {
			AMap<AString, ACell> block = Maps.of(Fields.TYPE, V_THINKING);
			if (thinking != null) block = block.assoc(K_THINKING, Strings.create(thinking));
			if (signature != null) block = block.assoc(K_SIGNATURE, Strings.create(signature));
			blocks = blocks.conj(block);
		}

		Object rawRedacted = message.attributes().get(REDACTED_THINKING_ATTRIBUTE);
		if (rawRedacted instanceof List<?> redacted) {
			for (Object value : redacted) {
				if (value instanceof String data) {
					blocks = blocks.conj(Maps.of(
						Fields.TYPE, V_REDACTED_THINKING,
						K_DATA, Strings.create(data)));
				}
			}
		}

		if (blocks.isEmpty()) return null;
		return Maps.of(
			Fields.PROVIDER, Strings.create(provider),
			Fields.MODEL, Strings.create(model),
			K_BLOCKS, blocks);
	}

	/**
	 * Restores state only for the exact provider/model that produced it.
	 * Missing, legacy, malformed, future, or non-matching state is ignored.
	 */
	static AiMessage restore(String provider, String model, ACell state, AiMessage message) {
		if (!ANTHROPIC.equals(provider) || model == null || !message.hasToolExecutionRequests()
				|| !(state instanceof AMap<?, ?>)) return message;
		AString storedProvider = RT.ensureString(RT.getIn(state, Fields.PROVIDER));
		AString storedModel = RT.ensureString(RT.getIn(state, Fields.MODEL));
		AVector<ACell> blocks = RT.ensureVector(RT.getIn(state, K_BLOCKS));
		if (storedProvider == null || storedModel == null || blocks == null
				|| !provider.equals(storedProvider.toString()) || !model.equals(storedModel.toString())) {
			return message;
		}

		String thinking = null;
		String signature = null;
		List<String> redacted = new ArrayList<>();
		boolean seenRedacted = false;
		for (long i = 0; i < blocks.count(); i++) {
			ACell block = blocks.get(i);
			AString type = RT.ensureString(RT.getIn(block, Fields.TYPE));
			if (V_THINKING.equals(type)) {
				// LangChain4j 1.x can faithfully send one non-empty signed thinking
				// block followed by redacted blocks. Refuse to reinterpret any wider
				// provider shape; a later codec can add support without migrating state.
				if (thinking != null || seenRedacted) return message;
				AString text = RT.ensureString(RT.getIn(block, K_THINKING));
				AString sig = RT.ensureString(RT.getIn(block, K_SIGNATURE));
				if (text == null || text.toString().isBlank()) return message;
				thinking = text.toString();
				signature = (sig != null) ? sig.toString() : null;
			} else if (V_REDACTED_THINKING.equals(type)) {
				seenRedacted = true;
				AString data = RT.ensureString(RT.getIn(block, K_DATA));
				if (data == null) return message;
				redacted.add(data.toString());
			} else {
				return message;
			}
		}
		if (thinking == null && redacted.isEmpty()) return message;

		Map<String, Object> attributes = new LinkedHashMap<>(message.attributes());
		if (signature != null) attributes.put(THINKING_SIGNATURE_ATTRIBUTE, signature);
		if (!redacted.isEmpty()) attributes.put(REDACTED_THINKING_ATTRIBUTE, List.copyOf(redacted));
		return message.toBuilder()
			.thinking(thinking)
			.attributes(attributes)
			.build();
	}
}
