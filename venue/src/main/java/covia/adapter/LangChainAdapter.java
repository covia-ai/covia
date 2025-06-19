package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
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

	ChatModel model = OllamaChatModel.builder()
            .baseUrl("http://localhost:11434")
            .temperature(0.7)
            .logRequests(true)
            .logResponses(true)
            .modelName("qwen3")
            .build();
	
	@Override
	public CompletableFuture<ACell> invoke(String operation, ACell meta, ACell input) {
        String[] parts = operation.split(":");
        if (!getName().equals(parts[0])) {
    		return CompletableFuture.completedFuture(
    			Maps.of(
    				"status", Status.FAILED,
    				"message", Strings.create("Bad operation specifier")
    			)
    		);	
        }
        
        if ("ollama".equals(parts[1])) {
        	return CompletableFuture.supplyAsync(()->{
        		AString prompt=RT.ensureString(RT.getIn(input, "prompt"));
        		if (prompt==null) {
        			prompt=DEFAULT_PROMPT;
        		}
	        	
	        	UserMessage userMessage = UserMessage.from(
	        		TextContent.from("Say hello in an entertaining way")
	        	);
	        	ChatResponse response = model.chat(SYSTEM_MESSAGE,userMessage);
	        	
	        	AiMessage reply = response.aiMessage();
	        	return Maps.of(
	    			"reply", Strings.create(reply.text())
	        	);
        	});
        } else {
    		return CompletableFuture.completedFuture(
    			Maps.of(
    				"status", Status.FAILED,
    				"message", Strings.create("LangChain implementation not found: "+parts[1])
    			)
    		);	
        }

	}
}
