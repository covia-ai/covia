package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Conversion helpers for provider-neutral tool-call arguments.
 *
 * <p>Covia keeps arguments as structured {@link ACell} values internally.
 * Providers and older persisted conversations may instead supply a JSON
 * string; that representation is parsed on ingress and produced again only
 * when a provider adapter requires it. Malformed provider output remains an
 * {@link AString} so the exact value can be audited and returned to the model
 * alongside the resulting tool error.</p>
 */
public final class ToolCallArguments {

	private static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	private static final AString K_ARGUMENTS = Strings.intern("arguments");

	private ToolCallArguments() {
	}

	/**
	 * Parses a provider or legacy argument value into its canonical ACell form.
	 * Structured values pass through unchanged; absent or blank arguments mean
	 * an empty object. One extra parse is allowed for historically
	 * double-encoded object/array arguments.
	 *
	 * @throws IllegalArgumentException when a string is not valid JSON
	 */
	public static ACell parse(ACell rawArguments) {
		if (rawArguments == null) return Maps.empty();
		if (!(rawArguments instanceof AString)) return rawArguments;
		String s = rawArguments.toString().trim();
		if (s.isEmpty()) return Maps.empty();
		ACell parsed;
		try {
			parsed = JSON.parse(s);
		} catch (Exception e) {
			throw new IllegalArgumentException("Tool arguments are not valid JSON: " + snippet(s));
		}
		if (parsed instanceof AString inner) {
			String nested = inner.toString().trim();
			if (!nested.isEmpty() && (nested.charAt(0) == '{' || nested.charAt(0) == '[')) {
				try {
					return JSON.parse(nested);
				} catch (Exception ignored) {
					// Keep the once-parsed value. Operation schema validation will
					// report a more useful shape/type error downstream.
				}
			}
		}
		return parsed;
	}

	/**
	 * Canonicalises valid arguments while retaining malformed strings exactly.
	 */
	public static ACell canonicalOrRaw(ACell rawArguments) {
		try {
			return parse(rawArguments);
		} catch (IllegalArgumentException e) {
			return rawArguments;
		}
	}

	/**
	 * Canonicalises every tool call in an assistant message. This also protects
	 * the agent state contract when a custom Level 3 operation does not use the
	 * built-in LangChain adapter.
	 */
	@SuppressWarnings("unchecked")
	public static ACell canonicaliseAssistantMessage(ACell assistantMessage) {
		if (!(assistantMessage instanceof AMap<?, ?> rawMessage)) return assistantMessage;
		ACell callsCell = RT.getIn(assistantMessage, K_TOOL_CALLS);
		if (!(callsCell instanceof AVector<?> rawCalls)) return assistantMessage;

		AVector<ACell> calls = (AVector<ACell>) rawCalls;
		AVector<ACell> canonicalCalls = calls;
		boolean changed = false;
		for (long i = 0; i < calls.count(); i++) {
			ACell callCell = calls.get(i);
			if (!(callCell instanceof AMap<?, ?> rawCall)) continue;
			ACell arguments = RT.getIn(callCell, K_ARGUMENTS);
			ACell canonical = canonicalOrRaw(arguments);
			if (canonical == arguments) continue;
			AMap<AString, ACell> call = (AMap<AString, ACell>) rawCall;
			canonicalCalls = canonicalCalls.assoc(i, call.assoc(K_ARGUMENTS, canonical));
			changed = true;
		}
		if (!changed) return assistantMessage;
		return ((AMap<AString, ACell>) rawMessage).assoc(K_TOOL_CALLS, canonicalCalls);
	}

	/**
	 * Serialises canonical arguments for a provider API that represents them as
	 * JSON text. AString is retained verbatim for legacy history and malformed
	 * provider output; all structured cells are encoded at this boundary.
	 */
	public static String toProviderJson(ACell arguments) {
		if (arguments == null) return "{}";
		if (arguments instanceof AString string) return string.toString();
		return JSON.print(arguments).toString();
	}

	private static String snippet(String s) {
		return (s.length() <= 80) ? s : s.substring(0, 77) + "...";
	}
}
