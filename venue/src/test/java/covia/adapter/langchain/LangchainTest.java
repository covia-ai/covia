package covia.adapter.langchain;

import java.util.ArrayList;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.ollama.OllamaChatModel;

public class LangchainTest {
	
	public static void main(String[] args) {
		String MODEL="qwen3"; // e.g. qwen3, deepseek-r1
		
		ChatModel model = OllamaChatModel.builder()
				.baseUrl("http://localhost:11434")
				.modelName(MODEL).build();
		
		ArrayList<ChatMessage> msgs=new ArrayList<>();
		msgs.add(SystemMessage.from("You are an interactive storyteller. Write responses of one or two paragraph that describes what happens next after any user input"));
		msgs.add(UserMessage.from("I tie up my horse and walk into the saloon with a confident swagger"));
		
		ChatRequest chatRequest = ChatRequest.builder()
				.parameters(model.defaultRequestParameters())
		        .messages(msgs)
		        .build();

		
		// System.out.println(model.doChat(chatRequest));
	}

}
