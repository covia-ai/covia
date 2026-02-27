package covia.adapter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Status;
import covia.venue.RequestContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class LangChainAdapter extends AAdapter {
	
	/** IO timeout for LLM API calls */
	private static final Duration IO_TIMEOUT = Duration.ofSeconds(120);

	private static final AString DEFAULT_PROMPT = Strings.create("Say hello in an entertaining way and remind the user that then need to provide a 'prompt' string input");
	private static final SystemMessage SYSTEM_MESSAGE = SystemMessage.from("You are an AI agent for the Covia platform. Give concise, clear and accurate responses to any user message you receive.");

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
		installAsset("/asset-examples/qwen.json");

		installAsset("/adapters/langchain/ollama.json");
		installAsset("/adapters/langchain/openai.json");
		installAsset("/adapters/langchain/openai2.json");
		installAsset("/adapters/langchain/gemini.json");
		installAsset("/adapters/langchain/deepseek.json");
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

        // Extract common parameters
        AString prompt = RT.ensureString(RT.getIn(input, "prompt"));
        if (prompt == null) {
            prompt = DEFAULT_PROMPT;
        }
        final AString finalPrompt = prompt;

        // Get URL parameter
        AString urlParam = RT.ensureString(RT.getIn(input, "url"));

        // Get model parameter from subParts[1] if provided, otherwise from input
        String modelName = (subParts.length > 1) ? subParts[1] : null;
        if (modelName == null) {
            AString modelParam = RT.ensureString(RT.getIn(input, "model"));
            modelName = (modelParam != null) ? modelParam.toString() : null;
        }
        final String finalModelName = modelName;

        // Get system prompt parameter
        AString systemPromptParam = RT.ensureString(RT.getIn(input, "systemPrompt"));
        SystemMessage systemMessage = (systemPromptParam != null) ?
            SystemMessage.from(systemPromptParam.toString()) : SYSTEM_MESSAGE;

        // Get API key parameter
        AString apiKeyParam = RT.ensureString(RT.getIn(input, "apiKey"));
        final String apiKey = (apiKeyParam != null) ? apiKeyParam.toString() : System.getenv("OPENAI_API_KEY");

        if ("ollama".equals(provider)) {
        	return CompletableFuture.supplyAsync(()->{
        		String baseUrl = (urlParam != null) ? urlParam.toString() : "http://localhost:11434";
        		String model = (finalModelName != null) ? finalModelName : "qwen";
        		ChatModel ollamaModel = buildOllamaModel(baseUrl, model, IO_TIMEOUT);
	        	return processChatRequest(ollamaModel, finalPrompt, systemMessage);
        	}, VIRTUAL_EXECUTOR);
        } else if ("openai".equals(provider)) {
        	return CompletableFuture.supplyAsync(()->{
        		String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.openai.com/v1";
        		String model = (finalModelName != null) ? finalModelName : "gpt-3.5-turbo";
        		ChatModel openaiModel = buildOpenAiModel(apiKey, baseUrl, model, IO_TIMEOUT);
	        	return processChatRequest(openaiModel, finalPrompt, systemMessage);
        	}, VIRTUAL_EXECUTOR);
        } else {
    		return CompletableFuture.completedFuture(
    			Status.failure("Unknown method: '"+provider+"'. Supported methods: 'ollama', 'openai'")
    		);
        }
	}

	// ========== Reusable model builders ==========

	/**
	 * Builds an Ollama ChatModel.
	 */
	static ChatModel buildOllamaModel(String baseUrl, String model, Duration timeout) {
		return OllamaChatModel.builder()
			.baseUrl(baseUrl)
			.logRequests(true)
			.logResponses(true)
			.modelName(model)
			.timeout(timeout)
			.build();
	}

	/**
	 * Builds an OpenAI-compatible ChatModel.
	 */
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

	private ACell processChatRequest(ChatModel model, AString prompt, SystemMessage systemMessage) {
		UserMessage userMessage = UserMessage.from(
			TextContent.from(prompt.toString())
		);
		ChatResponse response = model.chat(systemMessage, userMessage);
		
		AiMessage reply = response.aiMessage();
		String output = reply.text();
		String think = null;
		if (output.contains("</think>")) {
			int split = output.lastIndexOf("</think>");
			think = output.substring(7, split).trim();
			output = output.substring(split + 8).trim();
		}
		AMap<AString, ACell> result = Maps.of(
			"response", Strings.create(output)
		);
		if (think != null) {
			result = RT.assocIn(result, Strings.create(think), "think");
		}
		return result;
	}
}
