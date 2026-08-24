package covia.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.agent.AbstractLLMAdapter;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Status;
import covia.venue.RequestContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
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
import dev.langchain4j.model.ollama.OllamaModelCard;
import dev.langchain4j.model.ollama.OllamaModels;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

/**
 * LLM adapter providing level 3 (single LLM call) operations.
 *
 * <h3>Messages-based input (used by agent loop)</h3>
 * <p>When input contains a {@code messages} array, each entry is a message map:</p>
 * <ul>
 *   <li>{@code {role: "system"|"user", content: "..."}}</li>
 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments: {...}}]}}</li>
 *   <li>{@code {role: "tool", id: "...", name: "...", content: "...", isError?: boolean}}</li>
 * </ul>
 *
 * <p>Optional {@code tools} array defines available tools:</p>
 * <pre>{@code [{name: "search", description: "...", parameters: {type: "object", properties: {...}}}]}</pre>
 *
 * <p>Output is an assistant message map:</p>
 * <pre>{@code {role: "assistant", content: "Hello!", toolCalls?: [{id, name, arguments: {...}}]}}</pre>
 * <p>Tool arguments are structured ACell values in Covia messages. JSON-text
 * argument formats used by some providers are converted only at this adapter
 * boundary.</p>
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
	static final AString K_SOURCE     = Strings.intern("source");
	static final AString K_MEDIA_TYPE = Strings.intern("mediaType");
	static final AString K_DATA       = Strings.intern("data");
	static final AString V_IMAGE      = Strings.intern("image");
	static final AString V_BASE64     = Strings.intern("base64");
	static final AString K_MESSAGES   = Strings.intern("messages");
	static final AString K_TOOLS      = Strings.intern("tools");
	static final AString K_ROLE       = Strings.intern("role");
	static final AString K_CONTENT    = Strings.intern("content");
	static final AString K_STRUCTURED_CONTENT = Strings.intern("structuredContent");
	static final AString K_IS_ERROR   = Strings.intern("isError");
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
	static final AString K_TOKENS         = Strings.intern("tokens");
	static final AString K_INPUT          = Strings.intern("input");
	static final AString K_OUTPUT         = Strings.intern("output");
	static final AString K_TOTAL          = Strings.intern("total");
	static final AString K_FINISH_REASON  = Strings.intern("finishReason");
	static final AString K_PROVIDER       = Strings.intern("provider");
	static final AString K_PROVIDERS      = Strings.intern("providers");
	static final AString K_MODELS         = Strings.intern("models");
	static final AString K_RESOURCE       = Strings.intern("resource");
	static final AString K_RECOMMENDED    = Strings.intern("recommended");
	static final AString K_TAGS           = Strings.intern("tags");

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
		// The adapter's own skill: v/skills/models lives and dies with this adapter.
		installSkill("ops-tools/models", "/skills/models.json");
		installModelCatalog();
		installAsset("langchain/models",    "/adapters/langchain/models.json");

		// Example configurations — stored in CAS, not in /v/ops/.
		installExampleAsset("/asset-examples/qwen.json");   // langchain:ollama:qwen3
	}

	/** Publishes provider operations and their model-operation presets from one data file. */
	private void installModelCatalog() {
		AMap<AString, ACell> manifest = readJsonMap("/adapters/langchain/model-catalog.json");
		AVector<ACell> providers = RT.ensureVector(manifest.get(K_PROVIDERS));
		if (providers == null) throw new IllegalStateException("model-catalog.json needs providers[]");
		for (long i = 0; i < providers.count(); i++) {
			AMap<AString, ACell> spec = RT.ensureMap(providers.get(i));
			AString provider = RT.ensureString(spec != null ? spec.get(K_NAME) : null);
			AString resource = RT.ensureString(spec != null ? spec.get(K_RESOURCE) : null);
			AString defaultModel = RT.ensureString(spec != null ? spec.get(Fields.DEFAULT) : null);
			AVector<ACell> models = RT.ensureVector(spec != null ? spec.get(K_MODELS) : null);
			if (provider == null || resource == null || defaultModel == null || models == null) {
				throw new IllegalStateException("Invalid model provider at index " + i);
			}

			AMap<AString, ACell> executable = withModelDefault(readJsonMap(resource.toString()), defaultModel);
			String providerOp = "v/ops/langchain/" + provider;
			AMap<AString, ACell> providerFacet = AbstractLLMAdapter.modelFacet(executable)
				.assoc(Fields.DEFAULT, modelPath(provider, defaultModel))
				.assoc(K_RECOMMENDED, modelPaths(provider, RT.ensureMap(spec.get(K_RECOMMENDED))));
			AMap<AString, ACell> providerMeta = executable.assoc(
				AbstractLLMAdapter.K_MODEL_FACET, providerFacet);
			installAsset("langchain/" + provider, providerMeta);

			for (long j = 0; j < models.count(); j++) {
				AMap<AString, ACell> modelSpec = RT.ensureMap(models.get(j));
				AString id = RT.ensureString(modelSpec != null ? modelSpec.get(K_ID) : null);
				if (id == null) throw new IllegalStateException(
					"Invalid model at providers[" + i + "].models[" + j + "]");
				AMap<AString, ACell> facet = modelSpecificProfile(executable, id);
				AMap<AString, ACell> declaredFacet = Maps.empty();
				ACell options = modelSpec.get(AbstractLLMAdapter.K_OPTIONS);
				ACell budget = modelSpec.get(AbstractLLMAdapter.K_BUDGET);
				if (options != null) declaredFacet = declaredFacet.assoc(AbstractLLMAdapter.K_OPTIONS, options);
				if (budget != null) declaredFacet = declaredFacet.assoc(AbstractLLMAdapter.K_BUDGET, budget);
				facet = layerModelProfile(facet, declaredFacet)
					.assoc(K_ID, id)
					.assoc(K_PROVIDER, Strings.create(providerOp));
				ACell tags = modelSpec.get(K_TAGS);
				if (tags != null) facet = facet.assoc(K_TAGS, tags);

				AMap<AString, ACell> modelMeta = withModelDefaults(executable, id,
					RT.ensureMap(modelSpec.get(Fields.DEFAULT)))
					.assoc(Fields.NAME, valueOr(modelSpec.get(Fields.NAME),
						Strings.create(provider + " / " + id)))
					.assoc(Fields.DESCRIPTION, valueOr(modelSpec.get(Fields.DESCRIPTION), Strings.create(
						"Model preset for " + id + " through the " + provider + " provider operation.")))
					.assoc(AbstractLLMAdapter.K_MODEL_FACET, facet);
				installModel(provider + "/" + id, modelMeta);
			}
		}
	}

	private AMap<AString, ACell> readJsonMap(String resourcePath) {
		try {
			AMap<AString, ACell> map = RT.ensureMap(JSON.parse(readResource(resourcePath)));
			if (map == null) throw new IllegalStateException("Resource is not a JSON object: " + resourcePath);
			return map;
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Cannot read adapter resource " + resourcePath, e);
		}
	}

	private static AMap<AString, ACell> withModelDefault(AMap<AString, ACell> meta, AString model) {
		return withModelDefaults(meta, model, null);
	}

	private static AMap<AString, ACell> withModelDefaults(AMap<AString, ACell> meta, AString model,
			AMap<AString, ACell> modelDefaults) {
		AMap<AString, ACell> operation = RT.ensureMap(meta.get(Fields.OPERATION));
		if (operation == null) throw new IllegalStateException("Model provider asset needs operation metadata");
		AMap<AString, ACell> defaults = RT.ensureMap(operation.get(Fields.DEFAULT));
		if (defaults == null) defaults = Maps.empty();
		if (modelDefaults != null) {
			for (var entry : modelDefaults.entrySet()) defaults = defaults.assoc(entry.getKey(), entry.getValue());
		}
		operation = operation.assoc(Fields.DEFAULT, defaults.assoc(AbstractLLMAdapter.K_MODEL, model));
		return meta.assoc(Fields.OPERATION, operation);
	}

	private static ACell valueOr(ACell value, ACell fallback) {
		return (value != null) ? value : fallback;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> modelSpecificProfile(AMap<AString, ACell> providerMeta,
			AString modelId) {
		AMap<AString, ACell> byModel = RT.ensureMap(
			AbstractLLMAdapter.modelFacet(providerMeta).get(AbstractLLMAdapter.K_BY_MODEL));
		AMap<AString, ACell> profile = (byModel != null) ? RT.ensureMap(byModel.get(modelId)) : null;
		return (profile != null) ? profile : Maps.empty();
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> layerModelProfile(AMap<AString, ACell> base,
			AMap<AString, ACell> override) {
		AMap<AString, ACell> result = base;
		for (var entry : override.entrySet()) {
			ACell under = result.get(entry.getKey());
			ACell value = entry.getValue();
			if (under instanceof AMap && value instanceof AMap) {
				AMap<AString, ACell> merged = (AMap<AString, ACell>) under;
				for (var inner : ((AMap<AString, ACell>) value).entrySet()) {
					merged = merged.assoc(inner.getKey(), inner.getValue());
				}
				value = merged;
			}
			result = result.assoc(entry.getKey(), value);
		}
		return result;
	}

	private static AString modelPath(AString provider, AString id) {
		return Strings.create("v/models/" + provider + "/" + id);
	}

	private static AMap<AString, ACell> modelPaths(AString provider, AMap<AString, ACell> ids) {
		AMap<AString, ACell> paths = Maps.empty();
		if (ids == null) return paths;
		for (var entry : ids.entrySet()) {
			AString id = RT.ensureString(entry.getValue());
			if (id != null) paths = paths.assoc(entry.getKey(), modelPath(provider, id));
		}
		return paths;
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.completedFuture(
				Status.failure("Method not specified. Use 'langchain:ollama:modelName' or 'langchain:openai'")
			);
		}

		// Discovery: providers + models with CALLER-RELATIVE readiness (see
		// the models skill). IO-bound (ollama probe) → virtual executor.
		if ("models".equals(subOp)) {
			final ACell modelsInput = input;
			return CompletableFuture.supplyAsync(() -> handleModels(ctx, modelsInput), VIRTUAL_EXECUTOR);
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
		final AbstractLLMAdapter.ResolvedModel resolvedModel;
		try {
			resolvedModel = AbstractLLMAdapter.resolveModel(engine, Asset.fromMeta(meta),
				(modelName != null) ? Strings.create(modelName) : null, null, ctx);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(Status.failure("Cannot resolve model: " + e.getMessage()));
		}
		final String finalModelName = (resolvedModel.modelId() != null)
			? resolvedModel.modelId().toString() : null;

		// Resolve API key
		final String apiKey = resolveApiKey(meta, input, ctx);

		// Providers that need a key must fail fast with a clear message —
		// without this, langchain4j's Anthropic builder throws synchronously
		// (breaking the CompletableFuture contract) and OpenAI silently sends
		// a request with junk auth, producing the misleading "you didn't provide
		// an API key" error from OpenAI's server. See #91.
		if (providerNeedsApiKey(provider) && (apiKey == null || apiKey.isBlank())) {
			AString apiKeyParam = RT.ensureString(RT.getIn(input, "apiKey"));
			AString secretName = RT.ensureString(RT.getIn(meta, "operation", "secretKey"));
			String hint = apiKeyParam != null ? apiKeyParam.toString()
					: secretName != null ? "/s/" + secretName
					: "<no apiKey or operation.secretKey configured>";
			return CompletableFuture.completedFuture(
				Status.failure("API key not found for provider '" + provider + "' at " + hint)
			);
		}
		if (finalModelName == null || finalModelName.isBlank()) {
			return CompletableFuture.completedFuture(
				Status.failure("Model not specified for provider '" + provider + "'"));
		}

		// Optional sampling/bounds parameters (#218): temperature and topP pass
		// through to every provider; maxTokens (covia#198) is honoured by the
		// anthropic provider (its API requires max_tokens; the client default
		// stands otherwise) and currently ignored by the others.
		final ModelTuning tuning = extractTuning(input);

		// Ollama base URL resolution (#224): explicit url > venue config >
		// OLLAMA_BASE_URL env > localhost default — agents stay
		// topology-agnostic; only the venue deployment knows where Ollama lives.
		final String ollamaUrl = "ollama".equals(provider) ? resolveOllamaUrl(urlParam) : null;
		final AString effectiveUrl = (ollamaUrl != null) ? Strings.create(ollamaUrl) : urlParam;

		// Build the ChatModel. The selected operation's data has already supplied
		// any default, with caller input winning.
		final String resolvedModelName = finalModelName;
		final ChatModel chatModel = buildProviderModel(provider, finalModelName, apiKey, effectiveUrl, tuning);
		if (chatModel == null) {
			return CompletableFuture.completedFuture(
				Status.failure("Unknown provider: '" + provider + "'. Supported: ollama, openai, anthropic, gemini, xai, deepseek, mistral, openrouter")
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

		// LangChain4j's provider messages require tool results as text. Build a
		// provider-only copy here, before handing work to the provider worker.
		// The canonical agent messages retain their exact structuredContent for
		// session persistence; this narrow conversion also avoids #334's hang
		// when nested Convex collections reached the Anthropic worker unchanged.
		// The same copy applies the role rule for the declared provider
		// (AGENT_CONTEXT.md §3.5): a system message after the conversation has
		// begun must not be hoisted into the cached head.
		final AVector<ACell> providerMessages = normaliseSystemMessages(
			serialiseToolResultsForProvider(messages),
			AbstractLLMAdapter.modelOptionText(resolvedModel.executionProfile(), OPT_SYSTEM_MESSAGES),
			AbstractLLMAdapter.labelDialect(resolvedModel.executionProfile()));
		final Set<Long> cacheMarks = cacheMarksOf(input, tuning);

		// Response format: "json", "text", or {name, schema} map
		ACell responseFormatCell = RT.getIn(input, K_RESPONSE_FORMAT);

		// Prompt-based callers expect {response: "..."} output
		final boolean legacyOutput = !(messagesCell instanceof AVector);

		final RequestContext rctx = ctx;
		// Interruptible execution: submit() (not supplyAsync) so that cancelling
		// the returned future interrupts the worker thread, closing the in-flight
		// HTTP call to the provider. CompletableFuture.cancel(true) alone never
		// interrupts supplyAsync work — the whenComplete bridge below forwards
		// cancellation to the submitted task. This covers both paths: the default
		// job-aware invoke wires Job.cancel() to future.cancel(true), and the
		// agent loop's L3 timeout (AbstractLLMAdapter.invokeLevel3) cancels the
		// future directly.
		final CompletableFuture<ACell> pending = new CompletableFuture<>();
		Future<?> worker = VIRTUAL_EXECUTOR.submit(() -> {
			try {
				// Resolve asset-referenced image blocks to inline data (covia#198):
				// the job record keeps the ~tiny reference, not the image bytes.
				AVector<ACell> resolvedMessages = resolveImageRefs(providerMessages, rctx);
				ACell result;
				try {
					result = callModel(provider, resolvedModelName, chatModel, resolvedMessages, tools,
						responseFormatCell, cacheMarks);
				} catch (RuntimeException e) {
					// A connect failure against Ollama is almost always topology
					// (Docker vs host) — turn the bare ConnectException into a
					// 30-second self-serve diagnosis (#224).
					String hint = (ollamaUrl != null) ? ollamaConnectHint(ollamaUrl, e) : null;
					if (hint != null) throw new covia.exception.JobFailedException(hint);
					throw e;
				}
				if (legacyOutput) {
					// Wrap assistant message as {response: content}
					AString content = RT.ensureString(RT.getIn(result, K_CONTENT));
					result = Maps.of(Strings.intern("response"), (content != null) ? content : Strings.create(""));
				}
				pending.complete(result);
			} catch (Throwable t) {
				pending.completeExceptionally(t);
			}
		});
		pending.whenComplete((r, e) -> {
			if (pending.isCancelled()) worker.cancel(true);
		});
		return pending;
	}

	// ========== Model construction ==========

	/** Optional sampling/bounds parameters passed through to the provider
	 *  builders (#218). Anthropic requires an effective {@code maxTokens}; its
	 *  operation metadata supplies the built-in default before this edge. */
	record ModelTuning(Integer maxTokens, Double temperature, Double topP, Boolean cache) {
		static final ModelTuning NONE = new ModelTuning(null, null, null, null);

		/** Prompt caching is on unless the call says {@code cache: false}. */
		boolean caching() {
			return cache == null || cache;
		}
	}

	/** Reads maxTokens/temperature/topP from the op input. Numeric fields
	 *  accept integers and doubles alike — {@code temperature: 0} arrives as
	 *  a long from JSON and must not be dropped (it's the deterministic-
	 *  extraction case that motivated #218). */
	static ModelTuning extractTuning(ACell input) {
		Integer maxTokens = asPositiveInt(RT.getIn(input, "maxTokens"), "maxTokens");
		ACell cacheCell = RT.getIn(input, K_CACHE);
		Boolean cache = (cacheCell instanceof CVMBool b) ? b.booleanValue() : null;
		return new ModelTuning(maxTokens,
			asDouble(RT.getIn(input, "temperature"), "temperature"),
			asDouble(RT.getIn(input, "topP"), "topP"),
			cache);
	}

	private static Integer asPositiveInt(ACell value, String field) {
		if (value == null) return null;
		if (!(value instanceof CVMLong number)) {
			throw new IllegalArgumentException(field + " must be a positive integer, got: " + value);
		}
		long n = number.longValue();
		if (n <= 0 || n > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(field + " must be between 1 and "
				+ Integer.MAX_VALUE + ", got: " + n);
		}
		return (int) n;
	}

	/** The {@code cache} input: prompt caching on or off for this call. */
	static final AString K_CACHE = Strings.intern("cache");

	/**
	 * The message indices this call marks as prompt-cache breakpoints — the
	 * caller's {@code cacheMarks}, honoured only while caching is on. The agent
	 * runtime sends the band boundaries of AGENT_CONTEXT.md §3.1.
	 */
	static Set<Long> cacheMarksOf(ACell input, ModelTuning tuning) {
		if (!tuning.caching()) return Set.of();
		AVector<ACell> marks = RT.ensureVector(RT.getIn(input, AbstractLLMAdapter.K_CACHE_MARKS));
		if (marks == null || marks.isEmpty()) return Set.of();
		Set<Long> out = new java.util.HashSet<>();
		for (long i = 0; i < marks.count(); i++) {
			CVMLong idx = RT.ensureLong(marks.get(i));
			if (idx != null && idx.longValue() >= 0) out.add(idx.longValue());
		}
		return out;
	}

	private static Double asDouble(ACell v, String field) {
		if (v == null) return null;
		convex.core.data.prim.CVMDouble d = RT.castDouble(v);
		if (d == null) throw new IllegalArgumentException(field + " must be a number, got: " + v);
		return d.doubleValue();
	}

	/** Ollama base URL (#224): explicit input {@code url} wins, then venue
	 *  config {@code adapters.langchain.ollamaUrl}, then the
	 *  {@code OLLAMA_BASE_URL} environment variable, then localhost. */
	String resolveOllamaUrl(AString urlParam) {
		AMap<AString, ACell> cfg = (engine != null) ? engine.adapterConfig("langchain") : null;
		return resolveOllamaUrl(urlParam, cfg, System.getenv("OLLAMA_BASE_URL"));
	}

	static String resolveOllamaUrl(AString urlParam, AMap<AString, ACell> adapterConfig, String env) {
		if (urlParam != null) return urlParam.toString();
		AString configured = RT.ensureString(RT.getIn(adapterConfig, "ollamaUrl"));
		if (configured != null) return configured.toString();
		if (env != null && !env.isBlank()) return env;
		return "http://localhost:11434";
	}

	/** A diagnosable message for an Ollama connect failure, or null when the
	 *  failure isn't connectivity. Names the resolved URL and the venue-level
	 *  knob — the usual cause is a Dockerised venue reaching for its own
	 *  localhost (#224). */
	static String ollamaConnectHint(String resolvedUrl, Throwable e) {
		boolean connectivity = false;
		for (Throwable t = e; t != null; t = (t.getCause() != t) ? t.getCause() : null) {
			if (t instanceof java.net.ConnectException
					|| t instanceof java.net.UnknownHostException
					|| t instanceof java.net.NoRouteToHostException
					|| t instanceof java.net.http.HttpConnectTimeoutException) {
				connectivity = true;
				break;
			}
		}
		if (!connectivity) return null;
		return "Ollama not reachable at " + resolvedUrl
			+ " — if the venue runs in Docker, set adapters.langchain.ollamaUrl in venue config"
			+ " (or the OLLAMA_BASE_URL environment variable) to http://host.docker.internal:11434"
			+ " and start Ollama with OLLAMA_HOST=0.0.0.0";
	}

	static boolean providerNeedsApiKey(String provider) {
		return "openai".equals(provider) || "anthropic".equals(provider) || "gemini".equals(provider)
			|| "xai".equals(provider) || "deepseek".equals(provider)
			|| "mistral".equals(provider) || "openrouter".equals(provider);
	}

	/**
	 * Probes an Ollama server for a model's advertised capabilities — the
	 * {@code capabilities} list from {@code /api/show}, e.g.
	 * {@code ["completion", "tools", "vision"]}. This is the only provider whose
	 * tool-calling support is discoverable in advance: langchain4j's own
	 * {@code Capability} enum has no tool capability, and the hosted providers'
	 * {@code /models} endpoints don't report one (#205).
	 *
	 * <p>Best-effort and side-effect-free. Returns {@code null} when capabilities
	 * cannot be determined — the server is unreachable, the model is not
	 * installed, or the Ollama version predates capability reporting. Callers
	 * treat {@code null} as "unknown", never as a negative. A short timeout and
	 * zero retries keep this from stalling its caller when Ollama is absent
	 * (a refused connection returns immediately).</p>
	 *
	 * @param baseUrl Ollama base URL (e.g. {@code http://localhost:11434})
	 * @param model   model name (e.g. {@code qwen2.5})
	 * @return the advertised capabilities, or {@code null} if undeterminable
	 */
	public static java.util.List<String> ollamaModelCapabilities(String baseUrl, String model) {
		try {
			OllamaModelCard card = OllamaModels.builder()
				.baseUrl(baseUrl)
				.timeout(Duration.ofSeconds(2))
				.maxRetries(0)
				.build()
				.modelCard(model)
				.content();
			java.util.List<String> caps = (card != null) ? card.getCapabilities() : null;
			return (caps != null && !caps.isEmpty()) ? caps : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private ChatModel buildProviderModel(String provider, String modelName, String apiKey, AString urlParam, ModelTuning tuning) {
		if ("ollama".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "http://localhost:11434";
			return buildOllamaModel(baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("openai".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.openai.com/v1";
			return buildOpenAiModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("anthropic".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.anthropic.com/v1/";
			return buildAnthropicModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("gemini".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://generativelanguage.googleapis.com/v1beta/openai/";
			return buildOpenAiModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("xai".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.x.ai/v1";
			return buildOpenAiModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("deepseek".equals(provider)) {
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.deepseek.com/v1";
			return buildOpenAiModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("mistral".equals(provider)) {
			// Mistral's API is OpenAI-compatible (chat completions, tools, JSON mode).
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.mistral.ai/v1";
			return buildOpenAiModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		} else if ("openrouter".equals(provider)) {
			// OpenRouter fronts many vendors behind one OpenAI-compatible endpoint;
			// model ids are vendor-prefixed (anthropic/…, openai/…) and
			// openrouter/auto lets the router choose.
			String baseUrl = (urlParam != null) ? urlParam.toString() : "https://openrouter.ai/api/v1";
			return buildOpenAiModel(apiKey, baseUrl, modelName, IO_TIMEOUT, tuning);
		}
		return null;
	}

	// Request/response logging is disabled by default — these flags would
	// leak full prompts and completions (potentially sensitive user data)
	// to stdout if the langchain4j logger were ever bumped to DEBUG.

	static ChatModel buildOllamaModel(String baseUrl, String model, Duration timeout, ModelTuning tuning) {
		var builder = OllamaChatModel.builder()
			.baseUrl(baseUrl)
			.logRequests(false)
			.logResponses(false)
			.modelName(model)
			.timeout(timeout);
		if (tuning.temperature() != null) builder = builder.temperature(tuning.temperature());
		if (tuning.topP() != null) builder = builder.topP(tuning.topP());
		return builder.build();
	}

	static ChatModel buildOpenAiModel(String apiKey, String baseUrl, String model, Duration timeout, ModelTuning tuning) {
		// strictJsonSchema(true) enables OpenAI structured outputs for the
		// response_format path — server-enforced schema conformance on the
		// assistant's text response. This is how typed agent outputs are
		// implemented (see GoalTreeAdapter).
		//
		// strictTools is intentionally NOT enabled. It would force every
		// object in every tool's parameter schema to have additionalProperties:
		// false, which makes polymorphic tool inputs (e.g. agent_request.input
		// or grid_run.input) impossible — strict mode reduces them to {} only.
		// Tool argument validation still happens, just not as strict-mode
		// pre-validation by OpenAI.
		var builder = OpenAiChatModel.builder()
			.apiKey(apiKey)
			.baseUrl(baseUrl)
			.logRequests(false)
			.logResponses(false)
			.modelName(model)
			.timeout(timeout)
			.strictJsonSchema(true);
		if (tuning.temperature() != null) builder = builder.temperature(tuning.temperature());
		if (tuning.topP() != null) builder = builder.topP(tuning.topP());
		return builder.build();
	}

	static ChatModel buildAnthropicModel(String apiKey, String baseUrl, String model, Duration timeout, ModelTuning tuning) {
		AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
			.apiKey(apiKey)
			.baseUrl(baseUrl)
			.logRequests(false)
			.logResponses(false)
			.modelName(model)
			.timeout(timeout)
			// Anthropic prompt caching is explicit opt-in: mark the system
			// prompt and tool definitions with cache_control (the assembler
			// keeps both stable across calls), and the caller's cacheMarks
			// place the conversation breakpoints — see toChatMessages.
			.cacheSystemMessages(tuning.caching())
			.cacheTools(tuning.caching());
		// Anthropic requires max_tokens on every request. Built-in provider and
		// model operations declare an overridable operation.default; requiring the
		// effective value here also keeps authored operations from silently falling
		// through to langchain4j's hidden 1024-token default (#391).
		if (tuning.maxTokens() == null) {
			throw new IllegalArgumentException("Anthropic requires an effective maxTokens; "
				+ "declare operation.default.maxTokens or supply maxTokens in the call");
		}
		builder = builder.maxTokens(tuning.maxTokens());
		if (tuning.temperature() != null) builder = builder.temperature(tuning.temperature());
		if (tuning.topP() != null) builder = builder.topP(tuning.topP());
		return builder.build();
	}

	// ========== API key resolution ==========

	// ========== Provider/model discovery (langchain:models) ==========

	/**
	 * {@code langchain:models} is a view over {@code v/models/}. The catalog is
	 * authoritative; this method only adds caller-relative readiness and the
	 * live set reported by Ollama.
	 */
	private ACell handleModels(RequestContext ctx, ACell input) {
		AString filter = RT.ensureString(RT.getIn(input, "provider"));
		AVector<ACell> providers = Vectors.empty();
		for (Map.Entry<String, List<ModelDefinition>> group : catalogModels(ctx).entrySet()) {
			String providerName = group.getKey();
			if (filter != null && !filter.toString().equals(providerName)) continue;
			providers = providers.conj(providerEntry(providerName, group.getValue(), ctx));
		}
		return Maps.of(K_PROVIDERS, providers);
	}

	private static final class ModelDefinition {
		final AMap<AString, ACell> meta;
		final List<String> paths = new ArrayList<>();
		ModelDefinition(AMap<AString, ACell> meta, String path) {
			this.meta = meta;
			this.paths.add(path);
		}
	}

	private Map<String, List<ModelDefinition>> catalogModels(RequestContext ctx) {
		AMap<AString, ACell> root = RT.ensureMap(engine.resolvePath(Strings.create("v/models"), ctx));
		Map<String, LinkedHashMap<Hash, ModelDefinition>> grouped = new LinkedHashMap<>();
		if (root != null) collectModels(root, "", grouped);
		Map<String, List<ModelDefinition>> result = new LinkedHashMap<>();
		for (var entry : grouped.entrySet()) {
			List<ModelDefinition> definitions = new ArrayList<>(entry.getValue().values());
			for (ModelDefinition definition : definitions) definition.paths.sort(String::compareTo);
			definitions.sort((a, b) -> a.paths.get(0).compareTo(b.paths.get(0)));
			result.put(entry.getKey(), definitions);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static void collectModels(AMap<AString, ACell> node, String relative,
			Map<String, LinkedHashMap<Hash, ModelDefinition>> grouped) {
		if (node.get(Fields.OPERATION) != null) {
			int slash = relative.indexOf('/');
			if (slash <= 0) return;
			String provider = relative.substring(0, slash);
			String path = "v/models/" + relative;
			Hash hash = Asset.fromMeta(node).getID();
			ModelDefinition definition = grouped
				.computeIfAbsent(provider, ignored -> new LinkedHashMap<>())
				.get(hash);
			if (definition == null) {
				definition = new ModelDefinition(node, path);
				grouped.get(provider).put(hash, definition);
			} else {
				definition.paths.add(path);
			}
			return;
		}
		for (var entry : node.entrySet()) {
			if (!(entry.getValue() instanceof AMap)) continue;
			String next = relative.isEmpty() ? entry.getKey().toString()
				: relative + "/" + entry.getKey();
			collectModels((AMap<AString, ACell>) entry.getValue(), next, grouped);
		}
	}

	private AMap<AString, ACell> providerEntry(String providerName,
			List<ModelDefinition> definitions, RequestContext ctx) {
		AMap<AString, ACell> firstFacet = AbstractLLMAdapter.modelFacet(definitions.get(0).meta);
		AString providerRef = RT.ensureString(firstFacet.get(AbstractLLMAdapter.K_MODEL_PROVIDER));
		Asset provider = (providerRef != null) ? engine.resolveAsset(providerRef, ctx) : null;
		AMap<AString, ACell> providerMeta = (provider != null) ? provider.meta() : definitions.get(0).meta;
		AMap<AString, ACell> providerFacet = AbstractLLMAdapter.modelFacet(providerMeta);

		AVector<ACell> ids = Vectors.empty();
		AVector<ACell> entries = Vectors.empty();
		for (ModelDefinition definition : definitions) {
			AMap<AString, ACell> facet = AbstractLLMAdapter.modelFacet(definition.meta);
			AString id = RT.ensureString(facet.get(AbstractLLMAdapter.K_MODEL_ID));
			if (id == null) continue;
			String canonical = "v/models/" + providerName + "/" + id;
			String primary = definition.paths.contains(canonical) ? canonical : definition.paths.get(0);
			ids = ids.conj(id);
			AMap<AString, ACell> item = Maps.of(
				Fields.OP, Strings.create(primary),
				Fields.ID, id,
				Fields.NAME, definition.meta.get(Fields.NAME));
			ACell tags = facet.get(K_TAGS);
			if (tags != null) item = item.assoc(K_TAGS, tags);
			try {
				AMap<AString, ACell> profile = AbstractLLMAdapter.resolveModel(engine,
					Asset.fromMeta(definition.meta), null, null, ctx).executionProfile();
				ACell options = profile.get(AbstractLLMAdapter.K_OPTIONS);
				ACell budget = profile.get(AbstractLLMAdapter.K_BUDGET);
				if (options != null) item = item.assoc(AbstractLLMAdapter.K_OPTIONS, options);
				if (budget != null) item = item.assoc(AbstractLLMAdapter.K_BUDGET, budget);
			} catch (RuntimeException e) {
				// The definition remains discoverable even if its provider is broken.
			}
			if (definition.paths.size() > 1) {
				AVector<ACell> aliases = Vectors.empty();
				for (String path : definition.paths) if (!path.equals(primary)) aliases = aliases.conj(Strings.create(path));
				item = item.assoc(Strings.intern("aliases"), aliases);
			}
			entries = entries.conj(item);
		}

		AString defaultPath = RT.ensureString(providerFacet.get(Fields.DEFAULT));
		AMap<AString, ACell> recommended = RT.ensureMap(providerFacet.get(K_RECOMMENDED));
		AString keySecret = RT.ensureString(RT.getIn(providerMeta, Fields.OPERATION, "secretKey"));
		boolean ready = keySecret == null;
		if (keySecret != null) {
			try {
				ready = preferStoredSecret(engine.resolveSecret(keySecret.toString(), ctx),
					System.getenv(keySecret.toString())) != null;
			} catch (RuntimeException e) {
				ready = false;
			}
		}

		AMap<AString, ACell> entry = Maps.of(
			Fields.OP, (providerRef != null) ? providerRef : Strings.create("v/ops/langchain/" + providerName),
			K_PROVIDER, Strings.create(providerName),
			Strings.intern("ready"), CVMBool.create(ready),
			K_MODELS, ids,
			Strings.intern("entries"), entries,
			AbstractLLMAdapter.K_MODEL_FACET, providerFacet);
		if (keySecret != null) entry = entry.assoc(Strings.intern("keySecret"), keySecret);
		if (defaultPath != null) {
			entry = entry.assoc(Fields.DEFAULT, defaultPath)
				.assoc(Strings.intern("defaultModel"), modelIdFromPath(providerName, defaultPath));
		}
		if (recommended != null) {
			entry = entry.assoc(K_RECOMMENDED, recommended)
				.assoc(Strings.intern("recommendations"), modelIdsFromPaths(providerName, recommended));
		}
		AString adapter = RT.ensureString(RT.getIn(providerMeta, Fields.OPERATION, "adapter"));
		if (adapter != null && "langchain:ollama".equals(adapter.toString())) {
			for (var status : ollamaStatus().entrySet()) entry = entry.assoc(status.getKey(), status.getValue());
		}
		return entry;
	}

	private static AString modelIdFromPath(String provider, AString path) {
		String prefix = "v/models/" + provider + "/";
		String value = path.toString();
		return Strings.create(value.startsWith(prefix) ? value.substring(prefix.length()) : value);
	}

	private static AMap<AString, ACell> modelIdsFromPaths(String provider, AMap<AString, ACell> paths) {
		AMap<AString, ACell> ids = Maps.empty();
		for (var entry : paths.entrySet()) {
			AString path = RT.ensureString(entry.getValue());
			if (path != null) ids = ids.assoc(entry.getKey(), modelIdFromPath(provider, path));
		}
		return ids;
	}

	/** Live Ollama reachability and installed models; catalog definitions stay authoritative. */
	private AMap<AString, ACell> ollamaStatus() {
		AMap<AString, ACell> adapterCfg = engine.adapterConfig("langchain");
		String url = resolveOllamaUrl(null, adapterCfg, System.getenv("OLLAMA_BASE_URL"));
		AVector<ACell> models = Vectors.empty();
		boolean ready = false;
		String note = null;
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
				.connectTimeout(java.time.Duration.ofSeconds(2)).build();
			java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
					java.net.URI.create(url + "/api/tags"))
				.timeout(java.time.Duration.ofSeconds(3)).GET().build();
			java.net.http.HttpResponse<String> resp = client.send(req,
				java.net.http.HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() == 200) {
				ready = true;
				ACell parsed = convex.core.util.JSON.parse(resp.body());
				AVector<ACell> tags = RT.ensureVector(RT.getIn(parsed, "models"));
				if (tags != null) {
					for (long i = 0; i < tags.count(); i++) {
						AString name = RT.ensureString(RT.getIn(tags.get(i), "name"));
						if (name != null) models = models.conj(name);
					}
				}
			} else {
				note = "HTTP " + resp.statusCode() + " from " + url;
			}
		} catch (Exception e) {
			note = "unreachable at " + url + " — venue knob: adapters.langchain.ollamaUrl";
		}
		AMap<AString, ACell> entry = Maps.of(
			Strings.intern("url"), Strings.create(url),
			Strings.intern("ready"), ready ? CVMBool.TRUE : CVMBool.FALSE,
			Strings.intern("installedModels"), models);
		if (note != null) entry = entry.assoc(Strings.intern("note"), Strings.create(note));
		return entry;
	}

	private String resolveApiKey(AMap<AString, ACell> meta, ACell input, RequestContext ctx) {
		AString apiKeyParam = RT.ensureString(RT.getIn(input, "apiKey"));
		if (apiKeyParam != null) {
			String s = apiKeyParam.toString();
			// A "/s/..." or "s/..." prefix means this is a reference into the
			// caller's secret store. If resolution fails we MUST return null
			// rather than the literal reference — passing "/s/foo" through as
			// an API key produces a misleading 401 from the provider (see #91).
			if (s.startsWith("/s/") || s.startsWith("s/")) {
				return engine.resolveSecret(s, ctx);
			}
			return s;
		}
		AString secretName = RT.ensureString(RT.getIn(meta, "operation", "secretKey"));
		if (secretName != null) {
			String name = secretName.toString();
			// Caller/public SecretStore remains authoritative. An operator may
			// alternatively inject the conventional provider variable into the
			// venue process (for example ANTHROPIC_API_KEY in local development or
			// a container secret) without writing it to Covia config or agent state.
			return preferStoredSecret(
				engine.resolveSecret(name, ctx), System.getenv(name));
		}
		return null;
	}

	/** Selects the caller/public store before the venue-wide process fallback. */
	static String preferStoredSecret(String stored, String environment) {
		if (stored != null && !stored.isBlank()) return stored;
		return (environment != null && !environment.isBlank()) ? environment : null;
	}

	// ========== LLM invocation ==========

	/**
	 * Calls the LLM with messages, optional tool definitions, and optional response format.
	 * Returns an assistant message map: {role, content?, toolCalls?}.
	 *
	 * <p>Provider-aware structured output (#81): callers request structured
	 * output uniformly via {@code responseFormat}; THIS is the layer that
	 * picks the mechanism. Providers with native JSON-schema response_format
	 * take the direct path; providers without it (Anthropic) are served via
	 * {@link #callModelForcedTool} — same contract, different plumbing, so
	 * flipping providers is transparent to agents and harnesses.</p>
	 *
	 * @param responseFormatCell Response format: null (default text), "json" or "text" string,
	 *        or a map {@code {name: "...", schema: {type: "object", ...}}} for strict schema mode
	 */
	private static ACell callModel(String provider, String modelName, ChatModel model, AVector<ACell> messages,
			AVector<ACell> tools, ACell responseFormatCell, Set<Long> cacheMarks) {
		List<ChatMessage> chatMessages = toChatMessages(messages, cacheMarks);
		ResponseFormat responseFormat = toResponseFormat(responseFormatCell);

		if (responseFormat != null && lacksSchemaResponseFormat(provider)) {
			if (responseFormat.jsonSchema() != null) {
				return callModelForcedTool(model, messages, tools, responseFormatCell, cacheMarks);
			}
			// Plain JSON mode (no schema) is equally unsupported there —
			// suppress rather than let the provider client reject the
			// request; conformance falls back to prompt guidance.
			responseFormat = null;
		}

		log.debug("LLM call: {} messages, {} tools", chatMessages.size(),
			(tools != null) ? tools.count() : 0);
		for (int i = 0; i < chatMessages.size(); i++) {
			log.debug("  msg[{}]: {}", i, chatMessages.get(i));
		}

		boolean hasTools = tools != null && tools.count() > 0;
		boolean needsRequest = hasTools || responseFormat != null;
		ChatResponse response;
		if (needsRequest) {
			ChatRequest.Builder builder = ChatRequest.builder().messages(chatMessages);
			if (hasTools && "openai".equals(provider) && isOpenAiReasoningModel(modelName)) {
				// OpenAI rejects function tools together with a reasoning
				// effort on /v1/chat/completions for the gpt-5.x family
				// ("Function tools with reasoning_effort are not supported
				// for <model> in /v1/chat/completions. To use function
				// tools, use /v1/responses or set reasoning_effort to
				// 'none'.") langchain4j's OpenAiChatModel never sets
				// reasoning_effort itself, but the API defaults it to a
				// non-'none' value server-side whenever it's omitted — so an
				// explicit 'none' here is required, not just a fallback.
				// Scoped to hasTools: a tool-free turn keeps the provider's
				// default reasoning effort.
				OpenAiChatRequestParameters.Builder paramsBuilder = OpenAiChatRequestParameters.builder()
					.toolSpecifications(toToolSpecifications(tools))
					.reasoningEffort("none");
				if (responseFormat != null) paramsBuilder.responseFormat(responseFormat);
				builder.parameters(paramsBuilder.build());
			} else {
				if (hasTools) {
					builder.toolSpecifications(toToolSpecifications(tools));
				}
				if (responseFormat != null) {
					builder.responseFormat(responseFormat);
				}
			}
			response = model.chat(builder.build());
		} else {
			response = model.chat(chatMessages);
		}

		log.debug("LLM response: text='{}', toolCalls={}",
			response.aiMessage().text(),
			response.aiMessage().hasToolExecutionRequests()
				? response.aiMessage().toolExecutionRequests() : "none");

		return toAssistantMessage(response);
	}

	// ========== Provider-aware structured output (#81) ==========

	/**
	 * Providers WITHOUT native JSON-schema {@code response_format} support —
	 * structured output is realised via forced tool calling instead
	 * (Anthropic's only structured-output mechanism). Kept as a denylist so
	 * every other provider keeps the direct path exactly as before.
	 */
	static boolean lacksSchemaResponseFormat(String provider) {
		return "anthropic".equals(provider);
	}

	/**
	 * OpenAI's gpt-5.x family are reasoning models: {@code /v1/chat/completions}
	 * defaults their reasoning effort to a non-'none' value whenever the
	 * request omits it, which OpenAI rejects together with function tools
	 * (see the {@code reasoningEffort("none")} call site in {@link #callModel}).
	 * Every model this adapter's catalog recommends for {@code provider:
	 * "openai"} is gpt-5.x (openai.json / HostedProvider "openai"); this check
	 * is deliberately scoped to that prefix rather than applied to every
	 * openai-compatible provider, since {@code reasoning_effort} is an
	 * OpenAI-specific field an arbitrary compatible endpoint (or an older,
	 * non-reasoning model a caller names explicitly) may reject outright.
	 */
	static boolean isOpenAiReasoningModel(String modelName) {
		return modelName != null && modelName.toLowerCase(java.util.Locale.ROOT).startsWith("gpt-5");
	}

	/**
	 * Structured output via forced tool calling: a synthetic output tool
	 * whose parameters ARE the requested schema joins the palette, and tool
	 * choice is forced ({@code REQUIRED}) — every turn ends in a tool call:
	 * work tools while working, the output tool to answer. A call to the
	 * output tool is converted back into a plain assistant TEXT message
	 * whose content is the arguments JSON, so upstream consumers see exactly
	 * what native response_format would have produced. Calls to other tools
	 * pass through unchanged — agent tool loops work normally.
	 *
	 * <p>Note for direct API callers: on these providers a schema request
	 * combined with {@code REQUIRED} means the model cannot end a turn in
	 * free text. The venue harnesses always offer completion tools alongside
	 * (typed complete/fail), so agents are unaffected.</p>
	 */
	static ACell callModelForcedTool(ChatModel model, AVector<ACell> messages,
			AVector<ACell> tools, ACell responseFormatCell, Set<Long> cacheMarks) {
		String outName = outputToolName(responseFormatCell);
		AMap<AString, ACell> outputTool = syntheticOutputTool(outName, responseFormatCell);
		AVector<ACell> allTools = (tools != null) ? tools.conj(outputTool) : Vectors.of((ACell) outputTool);

		log.debug("LLM call (forced-tool structured output): {} messages, {} tools, output tool '{}'",
			messages.count(), allTools.count(), outName);

		ChatRequest request = ChatRequest.builder()
			.messages(toChatMessages(messages, cacheMarks))
			.toolSpecifications(toToolSpecifications(allTools))
			.toolChoice(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED)
			.build();
		ChatResponse response = model.chat(request);
		return convertOutputToolCall(toAssistantMessage(response), outName);
	}

	/** The synthetic output tool's name — the responseFormat's own name (the
	 *  harness passes e.g. "agent_output"), or "structured_output". */
	static String outputToolName(ACell responseFormatCell) {
		AString name = RT.ensureString(RT.getIn(responseFormatCell, K_NAME));
		return (name != null) ? name.toString() : "structured_output";
	}

	/** A tool definition carrying the requested schema as its parameters —
	 *  the {@code {name, description, parameters}} shape that
	 *  {@link #toToolSpecifications} converts. */
	static AMap<AString, ACell> syntheticOutputTool(String name, ACell responseFormatCell) {
		AMap<AString, ACell> tool = Maps.of(
			K_NAME, Strings.create(name),
			Strings.intern("description"), Strings.intern(
				"Deliver your final answer by calling this tool with the answer as its arguments. "
				+ "Call it exactly once, when you have the complete answer."));
		ACell schema = RT.getIn(responseFormatCell, K_SCHEMA);
		if (schema instanceof AMap) tool = tool.assoc(K_PARAMETERS, schema);
		return tool;
	}

	/**
	 * If the assistant called the synthetic output tool, rewrites the message
	 * as a plain text response: content = the call's arguments JSON,
	 * toolCalls dropped, tokens / finishReason preserved. An answer call
	 * dominates: any accompanying text preamble (Anthropic sometimes chats
	 * before a forced tool_use block) and any other parallel tool calls are
	 * deliberately discarded — content must stay cleanly parseable. Messages
	 * without an output-tool call pass through unchanged.
	 */
	@SuppressWarnings("unchecked")
	static ACell convertOutputToolCall(ACell assistantMsg, String outputToolName) {
		ACell tcCell = RT.getIn(assistantMsg, K_TOOL_CALLS);
		if (!(tcCell instanceof AVector)) return assistantMsg;
		AVector<ACell> toolCalls = (AVector<ACell>) tcCell;
		for (long i = 0; i < toolCalls.count(); i++) {
			ACell tc = toolCalls.get(i);
			AString name = RT.ensureString(RT.getIn(tc, K_NAME));
			if (name == null || !outputToolName.equals(name.toString())) continue;
			ACell args = RT.getIn(tc, K_ARGUMENTS);
			AString content = (args instanceof AString s) ? s : JSON.print(args);
			return ((AMap<AString, ACell>) assistantMsg)
				.dissoc(K_TOOL_CALLS)
				.assoc(K_CONTENT, content);
		}
		return assistantMsg;
	}

	// ========== Response conversion ==========

	/**
	 * Builds an assistant message map from a full ChatResponse. Includes
	 * {@code tokens: {input, output, total, cacheRead?, cacheWrite?}} and
	 * {@code finishReason} when the provider reports them. The cache counts
	 * are what prompt caching is costing and saving — present only when the
	 * provider reports them (Anthropic).
	 */
	@SuppressWarnings("unchecked")
	static ACell toAssistantMessage(ChatResponse response) {
		AMap<AString, ACell> msg = (AMap<AString, ACell>) toAssistantMessage(response.aiMessage());

		TokenUsage usage = response.tokenUsage();
		if (usage != null) {
			AMap<AString, ACell> tokens = Maps.empty();
			Integer in = usage.inputTokenCount();
			Integer out = usage.outputTokenCount();
			Integer tot = usage.totalTokenCount();
			if (in != null)  tokens = tokens.assoc(K_INPUT,  CVMLong.create(in));
			if (out != null) tokens = tokens.assoc(K_OUTPUT, CVMLong.create(out));
			if (tot != null) tokens = tokens.assoc(K_TOTAL,  CVMLong.create(tot));
			if (usage instanceof AnthropicTokenUsage cached) {
				Integer read = cached.cacheReadInputTokens();
				Integer write = cached.cacheCreationInputTokens();
				if (read != null && read > 0) tokens = tokens.assoc(Fields.CACHE_READ, CVMLong.create(read));
				if (write != null && write > 0) tokens = tokens.assoc(Fields.CACHE_WRITE, CVMLong.create(write));
			}
			if (tokens.count() > 0) msg = msg.assoc(K_TOKENS, tokens);
		}

		FinishReason fr = response.finishReason();
		if (fr != null) {
			msg = msg.assoc(K_FINISH_REASON,
				Strings.create(fr.name().toLowerCase()));
		}

		return msg;
	}

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
					K_ARGUMENTS, ToolCallArguments.canonicalOrRaw(
						req.arguments() == null ? null : Strings.create(req.arguments()))
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
	 * Returns the LangChain/provider view of a message vector.
	 *
	 * <p>LangChain4j models tool results as text, whereas Covia keeps structured
	 * operation results typed in canonical agent turns. Tool messages carrying
	 * structured content are copied; absent text is rendered as JSON, and the
	 * provider copy drops the structured field. The input vector and its maps
	 * are never mutated. Conversion happens before the provider worker is
	 * started because handing the nested Convex value to that worker triggered
	 * the collection-result stall reported in #334.</p>
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> serialiseToolResultsForProvider(AVector<ACell> messages) {
		AVector<ACell> result = messages;
		for (long i = 0; i < messages.count(); i++) {
			ACell entry = messages.get(i);
			if (!(entry instanceof AMap)) continue;
			AString role = RT.ensureString(RT.getIn(entry, K_ROLE));
			if (!ROLE_TOOL.equals(role)) continue;
			ACell structured = RT.getIn(entry, K_STRUCTURED_CONTENT);
			if (structured == null) continue;

			AMap<AString, ACell> providerEntry = (AMap<AString, ACell>) entry;
			if (RT.getIn(entry, K_CONTENT) == null) {
				providerEntry = providerEntry.assoc(K_CONTENT, JSON.print(structured));
			}
			providerEntry = providerEntry.dissoc(K_STRUCTURED_CONTENT);
			result = result.assoc(i, providerEntry);
		}
		return result;
	}

	/** The {@code model.options.systemMessages} key: how the provider takes system content. */
	static final AString OPT_SYSTEM_MESSAGES = Strings.intern("systemMessages");
	static final AString SYSTEM_MESSAGES_SINGLE = Strings.intern("single");
	static final AString SYSTEM_MESSAGES_NONE = Strings.intern("none");

	/**
	 * The edge's half of the role rule (AGENT_CONTEXT.md §3.2.1, §3.5), for the
	 * provider's declared {@code systemMessages}:
	 * <ul>
	 *   <li>{@code "multiple"} (or undeclared): messages pass through — the
	 *       provider keeps a system message wherever it is placed.</li>
	 *   <li>{@code "single"}: the leading run of system messages stays system
	 *       (the client coalesces it into the provider's one system parameter);
	 *       any system message after the conversation has begun becomes a
	 *       {@code user} message wrapped as a system element, in place — never
	 *       hoisted into the cached head, never out of sequence.</li>
	 *   <li>{@code "none"}: as {@code "single"}, with the leading run folded
	 *       into the first user message.</li>
	 * </ul>
	 * Never reorders, never drops, never adds content of its own.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> normaliseSystemMessages(AVector<ACell> messages, String systemMode, AString dialect) {
		boolean single = SYSTEM_MESSAGES_SINGLE.toString().equals(systemMode);
		boolean none = SYSTEM_MESSAGES_NONE.toString().equals(systemMode);
		if (!single && !none) return messages;

		AVector<ACell> out = Vectors.empty();
		StringBuilder leading = new StringBuilder();
		boolean started = false;
		for (long i = 0; i < messages.count(); i++) {
			ACell entry = messages.get(i);
			AString role = RT.ensureString(RT.getIn(entry, K_ROLE));
			if (ROLE_SYSTEM.equals(role)) {
				AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
				if (started) {
					out = out.conj(Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(
						covia.adapter.agent.Labels.wrapSystem(dialect, content != null ? content.toString() : ""))));
				} else if (none) {
					if (leading.length() > 0) leading.append("\n\n");
					if (content != null) leading.append(content);
				} else {
					out = out.conj(entry);
				}
				continue;
			}
			if (!started) {
				started = true;
				if (none && leading.length() > 0) {
					// Fold the head into the first user message; any other first
					// message gets it as its own user turn immediately before.
					ACell content = RT.getIn(entry, K_CONTENT);
					if (ROLE_USER.equals(role) && content instanceof AString text) {
						entry = ((AMap<AString, ACell>) entry).assoc(K_CONTENT,
							Strings.create(leading + "\n\n" + text));
					} else {
						out = out.conj(Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(leading.toString())));
					}
				}
			}
			out = out.conj(entry);
		}
		if (none && !started && leading.length() > 0) {
			out = out.conj(Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(leading.toString())));
		}
		return out;
	}

	/**
	 * Converts a user message's content-block array to LangChain4j contents
	 * (vision support, covia#198). Recognised blocks:
	 * <ul>
	 *   <li>{@code {type: "text", text: "…"}}</li>
	 *   <li>{@code {type: "image", source: {type: "base64", mediaType: "image/jpeg",
	 *       data: "…"}}}</li>
	 * </ul>
	 * Unknown block or source types fail loudly — a silently-dropped image is a
	 * wrong answer, not a degraded one.
	 */
	static List<Content> toUserContents(AVector<ACell> blocks) {
		List<Content> contents = new ArrayList<>();
		for (long i = 0; i < blocks.count(); i++) {
			ACell block = blocks.get(i);
			AString type = RT.ensureString(RT.getIn(block, "type"));
			String t = (type != null) ? type.toString() : null;
			if ("text".equals(t)) {
				AString text = RT.ensureString(RT.getIn(block, "text"));
				if (text == null) throw new IllegalArgumentException(
					"content[" + i + "]: text block requires a 'text' string");
				contents.add(TextContent.from(text.toString()));
			} else if ("image".equals(t)) {
				AString srcType = RT.ensureString(RT.getIn(block, "source", "type"));
				if (!"base64".equals(srcType != null ? srcType.toString() : null)) {
					throw new IllegalArgumentException(
						"content[" + i + "]: image source.type must be \"base64\" (got "
						+ srcType + ")");
				}
				AString mediaType = RT.ensureString(RT.getIn(block, "source", "mediaType"));
				AString data = RT.ensureString(RT.getIn(block, "source", "data"));
				if (mediaType == null || data == null) {
					throw new IllegalArgumentException(
						"content[" + i + "]: image source requires 'mediaType' and 'data' (base64)");
				}
				contents.add(ImageContent.from(data.toString(), mediaType.toString()));
			} else {
				throw new IllegalArgumentException(
					"content[" + i + "]: unknown block type '" + t
					+ "' — supported: text, image");
			}
		}
		if (contents.isEmpty()) throw new IllegalArgumentException(
			"user content array must contain at least one block");
		return contents;
	}

	/**
	 * Replaces {@code {type: "image", source: {type: "asset", ref: "…"}}} blocks
	 * with inline base64 blocks by resolving the reference and reading the
	 * content — under the <b>caller's</b> authority. This is the preferred way
	 * to pass images: the venue persists operation input in the job record, so
	 * an inline base64 image would land (multi-MB, possibly sensitive) in
	 * durable lattice history, while a content reference keeps the record tiny.
	 * Asset references remain content-address deduped. The ref accepts any
	 * resolvable content form — an
	 * asset hash ({@code a/<hash>}), a workspace path ({@code w/…}) holding
	 * either asset metadata or a reference string, a file/DLFS path, or a DID URL.
	 * Messages without asset image blocks pass through unchanged.
	 */
	@SuppressWarnings("unchecked")
	AVector<ACell> resolveImageRefs(AVector<ACell> messages, RequestContext ctx) {
		AVector<ACell> out = messages;
		for (long i = 0; i < messages.count(); i++) {
			ACell entry = messages.get(i);
			AString role = RT.ensureString(RT.getIn(entry, K_ROLE));
			if (role == null || !"user".equals(role.toString())) continue;
			ACell contentCell = RT.getIn(entry, K_CONTENT);
			if (!(contentCell instanceof AVector)) continue;
			AVector<ACell> blocks = (AVector<ACell>) contentCell;
			AVector<ACell> newBlocks = blocks;
			for (long j = 0; j < blocks.count(); j++) {
				ACell block = blocks.get(j);
				AString type = RT.ensureString(RT.getIn(block, "type"));
				AString srcType = RT.ensureString(RT.getIn(block, "source", "type"));
				if (type == null || !"image".equals(type.toString())) continue;
				if (srcType == null || !"asset".equals(srcType.toString())) continue;
				newBlocks = newBlocks.assoc(j, resolveImageAsset(block, ctx));
			}
			if (newBlocks != blocks) {
				AMap<AString, ACell> newEntry = ((AMap<AString, ACell>) entry).assoc(K_CONTENT, newBlocks);
				out = out.assoc(i, newEntry);
			}
		}
		return out;
	}

	/** Resolves one referenced-image block to an inline base64 block. Fail-loud: an
	 *  unresolvable image is a wrong answer, not a degraded one. */
	private ACell resolveImageAsset(ACell block, RequestContext ctx) {
		AString ref = RT.ensureString(RT.getIn(block, "source", "ref"));
		if (ref == null) throw new IllegalArgumentException(
			"image asset source requires a content 'ref' (asset, workspace, file, DLFS, or DID URL)");
		try {
			// Unified reference-addressed content resolution (Engine.resolveContent):
			// CAS assets, lattice values, and DLFS drive paths — every storage
			// mechanism, under the caller's authority.
			covia.venue.storage.ContentProvider.Resolved resolved =
				engine.resolveContent(ref, ctx);
			byte[] bytes = (resolved != null) ? resolved.content().getBlob().getBytes() : null;
			String mime = (resolved != null) ? resolved.contentType() : null;
			if (bytes == null || bytes.length == 0) {
				throw new IllegalArgumentException(
					"image ref '" + ref + "' did not resolve to content");
			}

			// Explicit mediaType on the block wins; else asset contentType; else sniff.
			AString mtOverride = RT.ensureString(RT.getIn(block, "source", "mediaType"));
			if (mtOverride != null) mime = mtOverride.toString();
			if (mime == null) mime = covia.utils.MimeUtils.guess(ref.toString(), bytes);
			if (mime == null || !mime.startsWith("image/")) {
				throw new IllegalArgumentException(
					"image ref '" + ref + "' has no image media type (got " + mime
					+ ") — set source.mediaType explicitly");
			}

			String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
			return Maps.of(
				K_TYPE, V_IMAGE,
				K_SOURCE, Maps.of(
					K_TYPE, V_BASE64,
					K_MEDIA_TYPE, Strings.create(mime),
					K_DATA, Strings.create(b64)));
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException(
				"failed to read image ref '" + ref + "': " + e.getMessage(), e);
		}
	}


	/**
	 * Converts Convex message maps to LangChain4j ChatMessage list.
	 * Supports all message types: system, user, assistant (with toolCalls), tool.
	 */
	@SuppressWarnings("unchecked")
	static List<ChatMessage> toChatMessages(AVector<ACell> messages) {
		return toChatMessages(messages, Set.of());
	}

	/** The langchain4j message attribute the Anthropic mapper turns into {@code cache_control}. */
	static final java.util.Map<String, Object> CACHE_ATTRIBUTE = java.util.Map.of("cache_control", "ephemeral");

	/**
	 * Converts the canonical {@code {role, content, ...}} messages to LangChain4j
	 * messages. A message whose index is in {@code cacheMarks} carries the
	 * cache attribute: the Anthropic mapper places {@code cache_control} on its
	 * last content block, making it a prompt-cache breakpoint (other providers
	 * ignore the attribute).
	 */
	@SuppressWarnings("unchecked")
	static List<ChatMessage> toChatMessages(AVector<ACell> messages, Set<Long> cacheMarks) {
		List<ChatMessage> result = new ArrayList<>();
		for (long i = 0; i < messages.count(); i++) {
			ACell entry = messages.get(i);
			AString role = RT.ensureString(RT.getIn(entry, K_ROLE));
			if (role == null) continue;
			boolean marked = cacheMarks.contains(i);

			String roleStr = role.toString();
			switch (roleStr) {
				case "system": {
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					if (content != null) result.add(SystemMessage.from(content.toString()));
					break;
				}
				case "user": {
					ACell contentCell = RT.getIn(entry, K_CONTENT);
					UserMessage user;
					if (contentCell instanceof AVector) {
						// Multimodal content blocks (vision, covia#198):
						// [{type:"text", text:"…"}, {type:"image",
						//   source:{type:"base64", mediaType:"image/jpeg", data:"…"}}]
						user = UserMessage.from(toUserContents((AVector<ACell>) contentCell));
					} else if (contentCell != null) {
						// Agent requests are commonly structured objects. Preserve that
						// information as readable JSON just like the assembler's persisted
						// history renderer; dropping the turn leaves Anthropic with a
						// system-only request, which its Messages API rejects.
						AString content = RT.ensureString(contentCell);
						String text = (content != null)
							? content.toString() : JSON.print(contentCell).toString();
						user = UserMessage.from(text);
					} else {
						break;
					}
					result.add(marked ? user.toBuilder().attributes(CACHE_ATTRIBUTE).build() : user);
					break;
				}
				case "assistant": {
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					ACell tcCell = RT.getIn(entry, K_TOOL_CALLS);
					AiMessage ai;
					if (tcCell instanceof AVector && ((AVector<ACell>) tcCell).count() > 0) {
						List<ToolExecutionRequest> reqs = new ArrayList<>();
						AVector<ACell> toolCalls = (AVector<ACell>) tcCell;
						for (long j = 0; j < toolCalls.count(); j++) {
							ACell tc = toolCalls.get(j);
							AString name = RT.ensureString(RT.getIn(tc, K_NAME));
							ACell args = RT.getIn(tc, K_ARGUMENTS);
							AString id = RT.ensureString(RT.getIn(tc, K_ID));
							if (name != null) {
								// Synthetic ID if LLM didn't provide one (e.g. Ollama)
								String idStr = (id != null) ? id.toString() : name.toString();
								reqs.add(ToolExecutionRequest.builder()
									.id(idStr)
									.name(name.toString())
									.arguments(ToolCallArguments.toProviderJson(args))
									.build());
							}
						}
						String text = (content != null) ? content.toString() : null;
						ai = new AiMessage(text, reqs);
					} else if (content != null) {
						ai = AiMessage.from(content.toString());
					} else {
						break;
					}
					result.add(marked ? ai.toBuilder().attributes(CACHE_ATTRIBUTE).build() : ai);
					break;
				}
				case "tool": {
					AString id = RT.ensureString(RT.getIn(entry, K_ID));
					AString name = RT.ensureString(RT.getIn(entry, K_NAME));
					AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
					if (name != null && content != null) {
						String idStr = (id != null) ? id.toString() : name.toString();
						var builder = ToolExecutionResultMessage.builder()
							.id(idStr)
							.toolName(name.toString())
							.text(content.toString());
						ACell rawIsError = RT.getIn(entry, K_IS_ERROR);
						if (rawIsError instanceof CVMBool) {
							builder.isError(CVMBool.TRUE.equals(rawIsError));
						}
						if (marked) builder.attributes(CACHE_ATTRIBUTE);
						result.add(builder.build());
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

		// A property with no declared "type" is an "any JSON value" parameter
		// (e.g. covia:write's `value`, agent_request's `input`, grid_run's
		// `input`). LangChain4j's typed schema model has no "any" element, so the
		// old fallback silently coerced such params to a string — which made the
		// LLM unable to pass a structured value through (it arrived null). Emit an
		// open object (additionalProperties allowed) so providers accept it and
		// the model can supply a structured object. Scalar/array values for a
		// typeless param remain a known limitation; objects are the common case.
		if (type == null) {
			return JsonObjectSchema.builder()
				.description(descStr)
				.additionalProperties(true)
				.build();
		}

		String typeStr = type.toString();
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
