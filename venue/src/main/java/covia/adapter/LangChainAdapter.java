package covia.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.util.JSON;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.grid.Status;
import covia.venue.RequestContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * LLM adapter providing level 3 (single LLM call) operations.
 *
 * <h3>Messages-based input (used by agent loop)</h3>
 * <p>When input contains a {@code messages} array, each entry is a message map:</p>
 * <ul>
 *   <li>{@code {role: "system"|"user", content: "..."}}</li>
 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments}]}}</li>
 *   <li>{@code {role: "tool", id: "...", name: "...", content: "..."}}</li>
 * </ul>
 *
 * <p>Optional {@code tools} array defines available tools:</p>
 * <pre>{@code [{name: "search", description: "...", parameters: {type: "object", properties: {...}}}]}</pre>
 *
 * <p>Output is an assistant message map:</p>
 * <pre>{@code {role: "assistant", content: "Hello!", toolCalls?: [{id, name, arguments}]}}</pre>
 *
 * <h3>Legacy prompt-based input</h3>
 * <p>When input contains {@code prompt} (string), returns {@code {response: "...", think?: "..."}}.</p>
 */
public class LangChainAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(LangChainAdapter.class);

	/** IO timeout for LLM API calls */
	private static final Duration IO_TIMEOUT = Duration.ofSeconds(120);

	private static final AString DEFAULT_PROMPT = Strings.create("Say hello in an entertaining way and remind the user that then need to provide a 'prompt' string input");
	private static final AString DEFAULT_SYSTEM_PROMPT = Strings.create("You are an AI agent for the Covia platform. Give concise, clear and accurate responses to any user message you receive.");

	// Message field keys
	static final AString K_MESSAGES   = Strings.intern("messages");
	static final AString K_TOOLS      = Strings.intern("tools");
	static final AString K_ROLE       = Strings.intern("role");
	static final AString K_CONTENT    = Strings.intern("content");
	static final AString K_STRUCTURED_CONTENT = Strings.intern("structuredContent");
	static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	static final AString K_ID         = Strings.intern("id");
	static final AString K_NAME       = Strings.intern("name");
	static final AString K_ARGUMENTS  = Strings.intern("arguments");
	static final AString K_PARAMETERS = Strings.intern("parameters");
	static final AString K_RESPONSE_FORMAT = Strings.intern("responseFormat");
	static final AString K_SCHEMA      = Strings.intern("schema");
	static final AString K_TYPE        = Strings.intern("type");
	static final AString K_DESCRIPTION = Strings.intern("description");
	static final AString K_ENUM        = Strings.intern("enum");
	static final AString K_ITEMS       = Strings.intern("items");

	// Role constants
	static final AString ROLE_SYSTEM    = Strings.intern("system");
	static final AString ROLE_USER      = Strings.intern("user");
	static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	static final AString ROLE_TOOL      = Strings.intern("tool");

	@Override
	public String getName() {
		return "langchain";
	}

	@Override
	public String getDescription() {
		return "Connects to LangChain for advanced language model interactions. " +
			   "Provides seamless access to local and remote AI models with configurable parameters and system prompts. " +
			   "Ideal for natural language processing, AI-powered conversations, and intelligent content generation workflows.";
	}

	@Override
	public void installAssets() {
		// Canonical operations — one per adapter route
		installAsset("/adapters/langchain/openai.json");      // langchain:openai (OpenAI-compatible)
		installAsset("/adapters/langchain/ollama.json");     // langchain:ollama (local Ollama)
		installAsset("/adapters/langchain/anthropic.json");  // langchain:anthropic (native Anthropic API)

		// Example configurations — distinct adapter references for the operation registry
		installAsset("/asset-examples/qwen.json");          // langchain:ollama:qwen3
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.completedFuture(
				Status.failure("Method not specified. Use 'langchain:ollama:modelName' or 'langchain:openai'")
			);
		}

		// subOp may be "ollama:modelName" or "openai" etc.
		String[] subParts = subOp.split(":", 2);
		String provider = subParts[0];

		// Get URL parameter
		AString urlParam = RT.ensureString(RT.getIn(input, "url"));

		// Get model parameter from subParts[1] if provided, otherwise from input
		String modelName = (subParts.length > 1) ? subParts[1] : null;
		if (modelName == null) {
			AString modelParam = RT.ensureString(RT.getIn(input, "model"));
			modelName = (modelParam != null) ? modelParam.toString() : null;
		}
		final String finalModelName = modelName;

		// Resolve API key
		final String apiKey = resolveApiKey(meta, input, ctx);

		// Build the ChatModel
		final ChatModel chatModel = buildProviderModel(provider, finalModelName, apiKey, urlParam);
		if (chatModel == null) {
			return CompletableFuture.completedFuture(
				Status.failure("Unknown provider: '" + provider + "'. Supported: 'ollama', 'openai'")
			);
		}

		// Build messages: either from explicit messages array or from prompt string
		final AVector<ACell> messages;
		ACell messagesCell = RT.getIn(input, K_MESSAGES);
		if (messagesCell instanceof AVector) {
			@SuppressWarnings("unchecked")
			AVector<ACell> m = (AVector<ACell>) messagesCell;
			messages = m;
		} else {
			// Convert prompt/systemPrompt to messages
			AString prompt = RT.ensureString(RT.getIn(input, "prompt"));
			if (prompt == null) prompt = DEFAULT_PROMPT;
			AString systemPromptParam = RT.ensureString(RT.getIn(input, "systemPrompt"));
			AString sysContent = (systemPromptParam != null) ? systemPromptParam : DEFAULT_SYSTEM_PROMPT;
			messages = Vectors.of(
				(ACell) Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, sysContent),
				(ACell) Maps.of(K_ROLE, ROLE_USER, K_CONTENT, prompt)
			);
		}

		@SuppressWarnings("unchecked")
		AVector<ACell> tools = (AVector<ACell>) ((RT.getIn(input, K_TOOLS) instanceof AVector) ? RT.getIn(input, K_TOOLS) : null);

		// Response format: "json", "text", or {name, schema} map
		ACell responseFormatCell = RT.getIn(input, K_RESPONSE_FORMAT);

		// Prompt-based callers expect {response: "..."} output
		final boolean legacyOutput = !(messagesCell instanceof AVector);

		return CompletableFuture.supplyAsync(() -> {
			ACell result = callModel(chatModel, messages, tools, responseFormatCell);
			if (legacyOutput) {
				// Wrap assistant message as {response: content}
				AString content = RT.ensureString(RT.getIn(result, K_CONTENT));
				return (ACell) Maps.of(Strings.intern("response"), (content != null) ? content : Strings.create(""));
			}
			return result;
		}, VIRTUAL_EXECUTOR);
	}

	// ========== Model construction ==========

	private ChatModel buildProviderModel(String provider, String modelName, String apiKey, AString urlParam) {
		if ("ollama".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "http://localhost:11434";
			String model = (modelName != null) ? modelName : "qwen";
			return buildOllamaModel(baseUrl, model, IO_TIMEOUT);
		} else if ("openai".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.openai.com/v1";
			String model = (modelName != null) ? modelName : "gpt-3.5-turbo";
			return buildOpenAiModel(apiKey, baseUrl, model, IO_TIMEOUT);
		} else if ("anthropic".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.anthropic.com/v1/";
			String model = (modelName != null) ? modelName : "claude-sonnet-4-5-20250514";
			return buildAnthropicModel(apiKey, baseUrl, model, IO_TIMEOUT);
		}
		return null;
	}

	static ChatModel buildOllamaModel(String baseUrl, String model, Duration timeout) {
		return OllamaChatModel.builder()
			.baseUrl(baseUrl)
			.logRequests(true)
			.logResponses(true)
			.modelName(model)
			.timeout(timeout)
			.build();
	}

	static ChatModel buildOpenAiModel(String apiKey, String baseUrl, String model, Duration timeout) {
		return OpenAiChatModel.builder()
			.apiKey(apiKey)
			.baseUrl(baseUrl)
			.logRequests(true)
			.logResponses(true)
			.modelName(model)
			.timeout(timeout)
			.build();
	}

	static ChatModel buildAnthropicModel(String apiKey, String baseUrl, String model, Duration timeout) {
		return AnthropicChatModel.builder()
			.apiKey(apiKey)
			.baseUrl(baseUrl)
			.logRequests(true)
			.logResponses(true)
			.modelName(model)
			.timeout(timeout)
			.build();
	}

	// ========== API key resolution ==========

	private String resolveApiKey(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		AString apiKeyParam = RT.ensureString(RT.getIn(input, "apiKey"));
		if (apiKeyParam != null) {
			String resolved = engine.resolveSecret(apiKeyParam.toString(), ctx);
			if (resolved != null) return resolved;
			return apiKeyParam.toString();
		}
		AString secretName = RT.ensureString(RT.getIn(meta, "operation", "secretKey"));
		if (secretName != null) {
			return engine.resolveSecret(secretName.toString(), ctx);
		}
		return null;
	}

	// ========== LLM invocation ==========

	/**
	 * Calls the LLM with messages, optional tool definitions, and optional response format.
	 * Returns an assistant message map: {role, content?, toolCalls?}.
	 *
	 * @param responseFormatCell Response format: null (default text), "json" or "text" string,
	 *        or a map {@code {name: "...", schema: {type: "object", ...}}} for strict schema mode
	 */
	private static ACell callModel(ChatModel model, AVector<ACell> messages,
			AVector<ACell> tools, ACell responseFormatCell) {
		List<ChatMessage> chatMessages = toChatMessages(messages);
		ResponseFormat responseFormat = toResponseFormat(responseFormatCell);

		log.debug("LLM call: {} messages, {} tools", chatMessages.size(),
			(tools != null) ? tools.count() : 0);
		for (int i = 0; i < chatMessages.size(); i++) {
			log.debug("  msg[{}]: {}", i, chatMessages.get(i));
		}

		boolean needsRequest = (tools != null && tools.count() > 0) || responseFormat != null;
		ChatResponse response;
		if (needsRequest) {
			ChatRequest.Builder builder = ChatRequest.builder().messages(chatMessages);
			if (tools != null && tools.count() > 0) {
				builder.toolSpecifications(toToolSpecifications(tools));
			}
			if (responseFormat != null) {
				builder.responseFormat(responseFormat);
			}
			response = model.chat(builder.build());
		} else {
			response = model.chat(chatMessages);
		}

		log.debug("LLM response: text='{}', toolCalls={}",
			response.aiMessage().text(),
			response.aiMessage().hasToolExecutionRequests()
				? response.aiMessage().toolExecutionRequests() : "none");

		return toAssistantMessage(response.aiMessage());
	}

	// ========== Response conversion ==========

	/**
	 * Converts a LangChain4j AiMessage to a Convex assistant message map.
	 */
	static ACell toAssistantMessage(AiMessage ai) {
		AMap<AString, ACell> msg = Maps.of(K_ROLE, ROLE_ASSISTANT);

		// Text content (strip <think> tags)
		String text = ai.text();
		if (text != null) {
			text = stripThinkTags(text);
			msg = msg.assoc(K_CONTENT, Strings.create(text));
		}

		// Tool calls
		if (ai.hasToolExecutionRequests()) {
			AVector<ACell> toolCalls = Vectors.empty();
			for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
				AMap<AString, ACell> tc = Maps.of(
					K_NAME, Strings.create(req.name()),
					K_ARGUMENTS, Strings.create(req.arguments())
				);
				if (req.id() != null) {
					tc = tc.assoc(K_ID, Strings.create(req.id()));
				}
				toolCalls = toolCalls.conj(tc);
			}
			msg = msg.assoc(K_TOOL_CALLS, toolCalls);
		}

		return msg;
	}

	/**
	 * Strips {@code <think>...</think>} tags from model output.
	 */
	static String stripThinkTags(String text) {
		if (text == null) return null;
		if (text.contains("</think>")) {
			int end = text.lastIndexOf("</think>");
			text = text.substring(end + 8).trim();
		}
		return text;
	}

	// ========== Message conversion ==========

	/**
	 * Converts Convex message maps to LangChain4j ChatMessage list.
	 * Supports all message types: system, user, assistant (with toolCalls), tool.
	 */
	@SuppressWarnings("unchecked")
	static List<ChatMessage> toChatMessages(AVector<ACell> messages) {
		List<ChatMessage> result = new ArrayList<>();
		for (long i = 0; i < messages.count(); i++) {
			ACell entry = messages.get(i);
			AString role = RT.ensureString(RT.getIn(entry, K_ROLE));
			if (role == null) continue;

			String roleStr = role.toString();
			switch (roleStr) {
				case "system": {
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					if (content != null) result.add(SystemMessage.from(content.toString()));
					break;
				}
				case "user": {
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					if (content != null) result.add(UserMessage.from(content.toString()));
					break;
				}
				case "assistant": {
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					ACell tcCell = RT.getIn(entry, K_TOOL_CALLS);
					if (tcCell instanceof AVector && ((AVector<ACell>) tcCell).count() > 0) {
						List<ToolExecutionRequest> reqs = new ArrayList<>();
						AVector<ACell> toolCalls = (AVector<ACell>) tcCell;
						for (long j = 0; j < toolCalls.count(); j++) {
							ACell tc = toolCalls.get(j);
							AString name = RT.ensureString(RT.getIn(tc, K_NAME));
							AString args = RT.ensureString(RT.getIn(tc, K_ARGUMENTS));
							AString id = RT.ensureString(RT.getIn(tc, K_ID));
							if (name != null) {
								// Synthetic ID if LLM didn't provide one (e.g. Ollama)
								String idStr = (id != null) ? id.toString() : name.toString();
								reqs.add(ToolExecutionRequest.builder()
									.id(idStr)
									.name(name.toString())
									.arguments(args != null ? args.toString() : "{}")
									.build());
							}
						}
						String text = (content != null) ? content.toString() : null;
						result.add(new AiMessage(text, reqs));
					} else if (content != null) {
						result.add(AiMessage.from(content.toString()));
					}
					break;
				}
				case "tool": {
					AString id = RT.ensureString(RT.getIn(entry, K_ID));
					AString name = RT.ensureString(RT.getIn(entry, K_NAME));
					// Text content or structured content (serialised to JSON)
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					if (content == null) {
						ACell structured = RT.getIn(entry, K_STRUCTURED_CONTENT);
						if (structured != null) {
							content = Strings.create(JSON.toString(structured));
						}
					}
					if (name != null && content != null) {
						String idStr = (id != null) ? id.toString() : name.toString();
						result.add(new ToolExecutionResultMessage(
							idStr, name.toString(), content.toString()));
					}
					break;
				}
			}
		}
		return result;
	}

	/**
	 * Converts Convex tool definition maps to LangChain4j ToolSpecification list.
	 *
	 * <p>Tool format: {@code {name, description, parameters?}}. Parameters follow
	 * JSON Schema: {@code {type: "object", properties: {p: {type, description}}, required: [...]}}.</p>
	 */
	static List<ToolSpecification> toToolSpecifications(AVector<ACell> tools) {
		List<ToolSpecification> specs = new ArrayList<>();
		for (long i = 0; i < tools.count(); i++) {
			ACell tool = tools.get(i);
			AString name = RT.ensureString(RT.getIn(tool, K_NAME));
			if (name == null) continue;
			AString desc = RT.ensureString(RT.getIn(tool, Strings.intern("description")));

			ToolSpecification.Builder builder = ToolSpecification.builder()
				.name(name.toString());
			if (desc != null) builder.description(desc.toString());

			ACell params = RT.getIn(tool, K_PARAMETERS);
			if (params instanceof AMap) {
				@SuppressWarnings("unchecked")
				JsonObjectSchema schema = toJsonObjectSchema((AMap<AString, ACell>) params);
				if (schema != null) builder.parameters(schema);
			}

			specs.add(builder.build());
		}
		return specs;
	}

	// ========== Response format conversion ==========

	/**
	 * Converts a Convex response format specification to a LangChain4j ResponseFormat.
	 *
	 * <p>Accepts three forms:</p>
	 * <ul>
	 *   <li>{@code null} → returns null (no format constraint, default text)</li>
	 *   <li>{@code "text"} → returns null (explicit text, same as default)</li>
	 *   <li>{@code "json"} → {@link ResponseFormat#JSON} (JSON mode, no schema)</li>
	 *   <li>Map {@code {name: "...", schema: {type: "object", ...}}} →
	 *       strict JSON schema mode with {@link JsonSchema}</li>
	 * </ul>
	 */
	@SuppressWarnings("unchecked")
	static ResponseFormat toResponseFormat(ACell cell) {
		if (cell == null) return null;

		// String shorthand: "json" or "text"
		AString str = RT.ensureString(cell);
		if (str != null) {
			if ("json".equals(str.toString())) return ResponseFormat.JSON;
			// "text" or anything else → default (no constraint)
			return null;
		}

		// Map form: {name: "...", schema: {type: "object", properties: {...}}}
		if (cell instanceof AMap) {
			AMap<AString, ACell> map = (AMap<AString, ACell>) cell;
			AString name = RT.ensureString(map.get(K_NAME));
			ACell schemaCell = map.get(K_SCHEMA);

			if (schemaCell instanceof AMap) {
				AMap<AString, ACell> schemaMap = (AMap<AString, ACell>) schemaCell;
				JsonObjectSchema rootElement = toJsonObjectSchema(schemaMap);
				if (rootElement != null) {
					JsonSchema jsonSchema = JsonSchema.builder()
						.name((name != null) ? name.toString() : "response")
						.rootElement(rootElement)
						.build();
					return ResponseFormat.builder()
						.type(ResponseFormatType.JSON)
						.jsonSchema(jsonSchema)
						.build();
				}
			}

			// Map without valid schema → JSON mode (no strict schema)
			return ResponseFormat.JSON;
		}

		return null;
	}

	// ========== JSON Schema conversion ==========

	/**
	 * Converts a Convex map representing a JSON Schema object to a JsonObjectSchema.
	 */
	@SuppressWarnings("unchecked")
	static JsonObjectSchema toJsonObjectSchema(AMap<AString, ACell> schema) {
		ACell propsCell = schema.get(Strings.intern("properties"));
		if (!(propsCell instanceof AMap)) return null;

		AMap<AString, ACell> properties = (AMap<AString, ACell>) propsCell;
		JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

		properties.forEach((key, value) -> {
			if (key != null && value instanceof AMap) {
				JsonSchemaElement element = toSchemaElement((AMap<AString, ACell>) value);
				if (element != null) {
					builder.addProperty(key.toString(), element);
				}
			}
		});

		ACell reqCell = schema.get(Strings.intern("required"));
		if (reqCell instanceof AVector) {
			AVector<ACell> required = (AVector<ACell>) reqCell;
			List<String> reqList = new ArrayList<>();
			for (long i = 0; i < required.count(); i++) {
				AString r = RT.ensureString(required.get(i));
				if (r != null) reqList.add(r.toString());
			}
			if (!reqList.isEmpty()) builder.required(reqList);
		}

		AString desc = RT.ensureString(schema.get(K_DESCRIPTION));
		if (desc != null) builder.description(desc.toString());

		return builder.build();
	}

	/**
	 * Converts a single JSON Schema property map to a JsonSchemaElement.
	 *
	 * <p>Supports: string, number, integer, boolean, array (with items), object (nested),
	 * and enum (string type with {@code enum} array).</p>
	 */
	@SuppressWarnings("unchecked")
	static JsonSchemaElement toSchemaElement(AMap<AString, ACell> prop) {
		AString type = RT.ensureString(prop.get(K_TYPE));
		AString desc = RT.ensureString(prop.get(K_DESCRIPTION));
		String typeStr = (type != null) ? type.toString() : "string";
		String descStr = (desc != null) ? desc.toString() : null;

		// Check for enum values — applies to string type
		ACell enumCell = prop.get(K_ENUM);
		if (enumCell instanceof AVector) {
			AVector<ACell> enumVec = (AVector<ACell>) enumCell;
			List<String> enumValues = new ArrayList<>();
			for (long i = 0; i < enumVec.count(); i++) {
				AString val = RT.ensureString(enumVec.get(i));
				if (val != null) enumValues.add(val.toString());
			}
			if (!enumValues.isEmpty()) {
				return JsonEnumSchema.builder()
					.description(descStr)
					.enumValues(enumValues)
					.build();
			}
		}

		switch (typeStr) {
			case "string":
				return JsonStringSchema.builder().description(descStr).build();
			case "number":
				return JsonNumberSchema.builder().description(descStr).build();
			case "integer":
				return JsonIntegerSchema.builder().description(descStr).build();
			case "boolean":
				return JsonBooleanSchema.builder().description(descStr).build();
			case "array": {
				JsonArraySchema.Builder builder = JsonArraySchema.builder().description(descStr);
				ACell itemsCell = prop.get(K_ITEMS);
				if (itemsCell instanceof AMap) {
					JsonSchemaElement itemSchema = toSchemaElement((AMap<AString, ACell>) itemsCell);
					if (itemSchema != null) builder.items(itemSchema);
				}
				return builder.build();
			}
			case "object": {
				JsonObjectSchema obj = toJsonObjectSchema(prop);
				if (obj != null) return obj;
				// Object with no sub-properties — return empty schema (required for Gemini compat)
				JsonObjectSchema.Builder b = JsonObjectSchema.builder();
				if (descStr != null) b.description(descStr);
				return b.build();
			}
			default:
				return JsonStringSchema.builder().description(descStr).build();
		}
	}
}
