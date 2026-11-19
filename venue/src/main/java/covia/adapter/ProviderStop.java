package covia.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.exception.ResponseException;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;

/**
 * Why a provider's response ended, in the finish-reason vocabulary of
 * OpenTelemetry's GenAI conventions, and whether it holds an answer
 * (MODELS.md §5.3). The provider's own value is read from its response where
 * the provider library keeps it; otherwise the library's own classification
 * is used. A response the provider declined to give ({@code content_filter})
 * or ended without one ({@code error}) is not a reply: the call fails with a
 * reason that names the provider, its own value and, for Anthropic, the
 * category, never the provider's free text.
 */
final class ProviderStop {

	static final AString STOP = Strings.intern("stop");
	static final AString LENGTH = Strings.intern("length");
	static final AString TOOL_CALL = Strings.intern("tool_call");
	static final AString CONTENT_FILTER = Strings.intern("content_filter");
	static final AString ERROR = Strings.intern("error");

	/** A provider that declined to answer; the note names its reason when known. */
	public static final String DECLINED = "Model provider %s declined to answer for model %s%s";
	/** A provider that ended its response without an answer; the note names its reason when known. */
	public static final String NO_ANSWER = "Model provider %s gave no answer for model %s%s";

	/**
	 * The providers' own values that have a counterpart: Anthropic's stop
	 * reasons, then the finish reasons of OpenAI's chat format and the
	 * providers reached through it. A value not listed has none, and the reply
	 * carries only the provider's value.
	 */
	private static final Map<String, AString> PROVIDER_VALUES = Map.ofEntries(
		Map.entry("end_turn", STOP),
		Map.entry("stop_sequence", STOP),
		Map.entry("max_tokens", LENGTH),
		Map.entry("model_context_window_exceeded", LENGTH),
		Map.entry("tool_use", TOOL_CALL),
		Map.entry("refusal", CONTENT_FILTER),
		Map.entry("stop", STOP),
		Map.entry("length", LENGTH),
		Map.entry("model_length", LENGTH),
		Map.entry("tool_calls", TOOL_CALL),
		Map.entry("function_call", TOOL_CALL),
		Map.entry("content_filter", CONTENT_FILTER),
		Map.entry("error", ERROR),
		Map.entry("insufficient_system_resource", ERROR),
		Map.entry("aborted", ERROR));

	/** The finish reason, or null when the provider's value has no counterpart. */
	final AString finishReason;
	/** The provider's own value, or null when the library does not keep it. */
	final AString providerValue;
	/** Anthropic's refusal category, or null. */
	private final AString category;

	private ProviderStop(AString finishReason, AString providerValue, AString category) {
		this.finishReason = finishReason;
		this.providerValue = providerValue;
		this.category = category;
	}

	static ProviderStop of(ChatResponse response) {
		AString value = null;
		AString category = null;
		if (response.metadata() instanceof AnthropicChatResponseMetadata anthropic) {
			ACell raw = body(anthropic.rawHttpResponse());
			value = RT.ensureString(RT.getIn(raw, "stop_reason"));
			category = RT.ensureString(RT.getIn(raw, "stop_details", "category"));
		} else if (response.metadata() instanceof OpenAiChatResponseMetadata openAi) {
			value = RT.ensureString(RT.getIn(body(openAi.rawHttpResponse()), "choices", 0L, "finish_reason"));
		}
		AString finish = (value != null) ? PROVIDER_VALUES.get(value.toString()) : fromLibrary(response.finishReason());
		return new ProviderStop(finish, value, category);
	}

	/**
	 * The failure for a response that holds no answer, or null when it is a
	 * reply. One that says nothing after a normal stop is read as the model's
	 * {@code emptyResponse} option says.
	 *
	 * @param emptyResponse {@code stop}, {@code content_filter} or {@code error}
	 */
	ResponseException noAnswer(String provider, String model, ACell reply, String emptyResponse) {
		AString reason = finishReason;
		boolean empty = LangChainAdapter.saysNothing(reply) && (reason == null || STOP.equals(reason));
		if (empty) reason = Strings.create(emptyResponse);
		String note = note(empty);
		if (CONTENT_FILTER.equals(reason)) return new ResponseException(DECLINED.formatted(provider, model, note));
		if (ERROR.equals(reason)) return new ResponseException(NO_ANSWER.formatted(provider, model, note));
		return null;
	}

	/** " (refusal, category cyber)": the provider's value, its category, and whether there was no content. */
	private String note(boolean empty) {
		List<String> parts = new ArrayList<>();
		if (providerValue != null) parts.add(providerValue.toString());
		if (category != null) parts.add("category " + category);
		if (empty) parts.add("no content");
		return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
	}

	private static AString fromLibrary(FinishReason reason) {
		if (reason == null) return null;
		return switch (reason) {
			case STOP -> STOP;
			case LENGTH -> LENGTH;
			case TOOL_EXECUTION -> TOOL_CALL;
			case CONTENT_FILTER -> CONTENT_FILTER;
			default -> null;
		};
	}

	private static ACell body(SuccessfulHttpResponse raw) {
		return (raw != null && raw.body() != null) ? JSON.parse(raw.body()) : null;
	}
}
