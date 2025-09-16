package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Status;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class LangChainAdapter extends AAdapter {
	
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
	}
	
	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
        String[] parts = operation.split(":");
        if (!getName().equals(parts[0])) {
    		return CompletableFuture.completedFuture(
    			Status.failure("Bad operation specifier")
    		);	
        }
        
        // Check if method is specified
        if (parts.length < 2) {
    		return CompletableFuture.completedFuture(
    			Status.failure("Method not specified. Use 'langchain:ollama:modelName' or 'langchain:openai'")
    		);	
        }
        
        // Extract common parameters
        AString prompt = RT.ensureString(RT.getIn(input, "prompt"));
        if (prompt == null) {
            prompt = DEFAULT_PROMPT;
        }
        final AString finalPrompt = prompt;
        
        // Get URL parameter
        AString urlParam = RT.ensureString(RT.getIn(input, "url"));
        
        // Get model parameter from parts[2] if provided, otherwise from input
        String modelName = (parts.length > 2) ? parts[2] : null;
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
        
        if ("ollama".equals(parts[1])) {
        	return CompletableFuture.supplyAsync(()->{
        		// Use Ollama defaults if not provided
        		String baseUrl = (urlParam != null) ? urlParam.toString() : "http://localhost:11434";
        		String model = (finalModelName != null) ? finalModelName : "qwen";
        		
        		// Create Ollama model dynamically with the specified URL and model
        		ChatModel ollamaModel = OllamaChatModel.builder()
        			.baseUrl(baseUrl)
        			.temperature(0.7)
        			.logRequests(true)
        			.logResponses(true)
        			.modelName(model)
        			.build();
	        	
	        	return processChatRequest(ollamaModel, finalPrompt, systemMessage);
        	});
        } else if ("openai".equals(parts[1])) {
        	return CompletableFuture.supplyAsync(()->{
        		// Use OpenAI defaults if not provided
        		String baseUrl = (urlParam != null) ? urlParam.toString() : "https://api.openai.com/v1";
        		String model = (finalModelName != null) ? finalModelName : "gpt-3.5-turbo";
        		
        		// Create OpenAI model dynamically with the specified URL and model
        		ChatModel openaiModel = OpenAiChatModel.builder()
        			.apiKey(apiKey)
        			.baseUrl(baseUrl)
        			.temperature(0.7)
        			.logRequests(true)
        			.logResponses(true)
        			.modelName(model)
        			.build();
	        	
	        	return processChatRequest(openaiModel, finalPrompt, systemMessage);
        	});
        } else {
    		return CompletableFuture.completedFuture(
    			Status.failure("Unknown method: '"+parts[1]+"'. Supported methods: 'ollama', 'openai'")
    		);	
        }
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
			"reply", Strings.create(output)
		);
		if (think != null) {
			result = RT.assocIn(result, Strings.create(think), "think");
		}
		return result;
	}
}
