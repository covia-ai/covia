package covia.adapter;

import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

/** Provider-independent inference options. Model definitions supply defaults;
* a call (including an agent's config.modelOptions) can override each key. */
record ModelCallOptions(boolean nativeSystemMessages, boolean automaticCaching,
		String thinkingPrefixMismatch, String cacheTtl) {
	static final AString KEY = Strings.intern("modelOptions");
	static final String INVALID = "Invalid model option: ";
	static final String UPSTREAM_REQUIRED = " requires lossless message-block support in LangChain4j; this feature is not enabled";
	static final String BINDING_BETA = "thinking-binding-controls-2026-08-01";
	static final String INVALID_SYSTEM_POSITION = "Native system messages require a preceding user or tool-result turn";
	static final Set<String> KEYS = Set.of("preserveThinking", "nativeSystemMessages",
		"automaticCaching", "inlineToolChanges", "thinkingPrefixMismatch", "cacheTtl", "compaction");

	static final ModelCallOptions DEFAULT = resolve(Maps.empty(), Maps.empty());

	static ModelCallOptions resolve(AMap<AString, ACell> profile, ACell input) {
		AMap<AString, ACell> defaults = RT.ensureMap(RT.getIn(profile, "options"));
		AMap<AString, ACell> merged = defaults == null ? Maps.empty() : defaults;
		ACell overrides = RT.getIn(input, KEY);
		if (overrides != null) {
			AMap<AString, ACell> map = RT.ensureMap(overrides);
			if (map == null) throw new IllegalArgumentException(INVALID + "modelOptions must be an object");
			for (var e : map.entrySet()) {
				if (!KEYS.contains(e.getKey().toString())) throw new IllegalArgumentException(INVALID + e.getKey());
				merged = merged.assoc(e.getKey(), e.getValue());
			}
		}
		Map<String, Object> values = JSON.jsonMap(merged);
		String mismatch = choice(values, "thinkingPrefixMismatch", "provider", Set.of("provider", "error", "drop"));
		String ttl = choice(values, "cacheTtl", "5m", Set.of("5m", "1h"));
		boolean nativeSystem = flag(values, "nativeSystemMessages", false);
		for (String feature : Set.of("preserveThinking", "inlineToolChanges", "compaction")) {
			if (flag(values, feature, false)) throw new IllegalArgumentException(feature + UPSTREAM_REQUIRED);
		}
		return new ModelCallOptions(nativeSystem, flag(values, "automaticCaching", false), mismatch, ttl);
	}

	private static boolean flag(Map<String, Object> values, String key, boolean fallback) {
		if (!values.containsKey(key)) return fallback;
		Object v = values.get(key);
		if (!(v instanceof Boolean b)) throw new IllegalArgumentException(INVALID + key + " must be boolean");
		return b;
	}

	private static String choice(Map<String, Object> values, String key, String fallback, Set<String> choices) {
		Object v = values.getOrDefault(key, fallback);
		if (!(v instanceof String s) || !choices.contains(s)) {
			throw new IllegalArgumentException(INVALID + key + " must be one of " + choices);
		}
		return s;
	}
}
