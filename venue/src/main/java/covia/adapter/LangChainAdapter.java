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

	ChatModel model = OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .temperature(0.7)
            .logRequests(true)
            .logResponses(true)
            .modelName("qwen3")
            .build();
	
	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
        String[] parts = operation.split(":");
        if (!getName().equals(parts[0])) {
    		return CompletableFuture.completedFuture(
    			Status.failure("Bad operation specifier")
    		);	
        }
        
        if ("ollama".equals(parts[1])) {
        	return CompletableFuture.supplyAsync(()->{
        		AString prompt=RT.ensureString(RT.getIn(input, "prompt"));
        		if (prompt==null) {
        			prompt=DEFAULT_PROMPT;
        		}
	        	
	        	UserMessage userMessage = UserMessage.from(
	        		TextContent.from(prompt.toString())
	        	);
	        	ChatResponse response = model.chat(SYSTEM_MESSAGE,userMessage);
	        	
	        	AiMessage reply = response.aiMessage();
	        	String output=reply.text();
	        	String think=null;
	        	if (output.contains("</think>")) {
	        		int split=output.lastIndexOf("</think>");
	        		think=output.substring(7, split).trim();
	        		output=output.substring(split+8).trim();
	        	}
	        	AMap<AString, ACell> result = Maps.of(
	    			"reply", Strings.create(output)
	        	);
	        	if (think!=null) {
	        		result=RT.assocIn(result, Strings.create(think), "think");
	        	}
	        	return result;
        	});
        } else {
    		return CompletableFuture.completedFuture(
    			Status.failure("LangChain implementation not found: "+parts[1])
    		);	
        }

	}
}
