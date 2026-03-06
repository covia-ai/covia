package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;

import java.util.List;

/**
 * Unit tests for LangChainAdapter conversion methods.
 */
public class LangChainAdapterTest {

	// ========== toResponseFormat ==========

	@Test
	public void testResponseFormatNull() {
		assertNull(LangChainAdapter.toResponseFormat(null));
	}

	@Test
	public void testResponseFormatTextString() {
		assertNull(LangChainAdapter.toResponseFormat(Strings.create("text")));
	}

	@Test
	public void testResponseFormatJsonString() {
		ResponseFormat rf = LangChainAdapter.toResponseFormat(Strings.create("json"));
		assertNotNull(rf);
		assertEquals(ResponseFormatType.JSON, rf.type());
		assertNull(rf.jsonSchema(), "Simple JSON mode should have no schema");
	}

	@Test
	public void testResponseFormatUnknownString() {
		assertNull(LangChainAdapter.toResponseFormat(Strings.create("xml")));
	}

	@Test
	public void testResponseFormatSchemaMap() {
		ACell responseFormat = Maps.of(
			"name", "Person",
			"schema", Maps.of(
				"type", "object",
				"properties", Maps.of(
					"name", Maps.of("type", "string", "description", "Person's name"),
					"age", Maps.of("type", "integer", "description", "Age in years")
				),
				"required", Vectors.of("name")
			)
		);

		ResponseFormat rf = LangChainAdapter.toResponseFormat(responseFormat);
		assertNotNull(rf);
		assertEquals(ResponseFormatType.JSON, rf.type());
		assertNotNull(rf.jsonSchema(), "Schema map should produce a JsonSchema");
		assertEquals("Person", rf.jsonSchema().name());
		assertNotNull(rf.jsonSchema().rootElement(), "Should have a root element");
	}

	@Test
	public void testResponseFormatMapNoSchema() {
		ACell responseFormat = Maps.of("name", "Anything");

		ResponseFormat rf = LangChainAdapter.toResponseFormat(responseFormat);
		assertNotNull(rf);
		assertEquals(ResponseFormatType.JSON, rf.type());
		assertNull(rf.jsonSchema(), "Map without schema should fall back to simple JSON mode");
	}

	@Test
	public void testResponseFormatDefaultName() {
		ACell responseFormat = Maps.of(
			"schema", Maps.of(
				"type", "object",
				"properties", Maps.of(
					"result", Maps.of("type", "string")
				)
			)
		);

		ResponseFormat rf = LangChainAdapter.toResponseFormat(responseFormat);
		assertNotNull(rf);
		assertEquals("response", rf.jsonSchema().name(), "Should default to 'response' name");
	}

	// ========== toAssistantMessage ==========

	@Test
	public void testToAssistantMessageText() {
		AiMessage ai = AiMessage.from("Hello world");
		ACell msg = LangChainAdapter.toAssistantMessage(ai);

		assertEquals(Strings.create("assistant"), RT.getIn(msg, "role"));
		assertEquals(Strings.create("Hello world"), RT.getIn(msg, "content"));
		assertNull(RT.getIn(msg, "toolCalls"));
	}

	@Test
	public void testToAssistantMessageStripThink() {
		AiMessage ai = AiMessage.from("<think>reasoning here</think>The answer is 42");
		ACell msg = LangChainAdapter.toAssistantMessage(ai);

		assertEquals(Strings.create("The answer is 42"), RT.getIn(msg, "content"));
	}

	// ========== toChatMessages ==========

	@Test
	public void testToChatMessagesBasic() {
		var messages = Vectors.of(
			Maps.of("role", "system", "content", "Be helpful"),
			Maps.of("role", "user", "content", "Hello")
		);

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		assertEquals(2, result.size());
		assertInstanceOf(SystemMessage.class, result.get(0));
		assertInstanceOf(UserMessage.class, result.get(1));
	}

	@Test
	public void testToChatMessagesToolResult() {
		var messages = Vectors.of(
			Maps.of("role", "tool", "id", "call_1", "name", "search", "content", "{\"results\": []}")
		);

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		assertEquals(1, result.size());
		assertInstanceOf(ToolExecutionResultMessage.class, result.get(0));
	}

	@Test
	public void testToChatMessagesSkipsNullRole() {
		var messages = Vectors.of(
			Maps.of("content", "no role"),
			Maps.of("role", "user", "content", "valid")
		);

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		assertEquals(1, result.size(), "Should skip message without role");
	}

	// ========== toToolSpecifications ==========

	@Test
	public void testToToolSpecifications() {
		var tools = Vectors.of(
			Maps.of(
				"name", "search",
				"description", "Search the web",
				"parameters", Maps.of(
					"type", "object",
					"properties", Maps.of(
						"query", Maps.of("type", "string", "description", "Search query")
					),
					"required", Vectors.of("query")
				)
			)
		);

		List<ToolSpecification> specs = LangChainAdapter.toToolSpecifications(tools);
		assertEquals(1, specs.size());
		assertEquals("search", specs.get(0).name());
		assertEquals("Search the web", specs.get(0).description());
		assertNotNull(specs.get(0).parameters());
	}

	@Test
	public void testToToolSpecificationsSkipsNoName() {
		var tools = Vectors.of(Maps.of("description", "no name tool"));

		List<ToolSpecification> specs = LangChainAdapter.toToolSpecifications(tools);
		assertEquals(0, specs.size(), "Should skip tool without name");
	}

	// ========== toJsonObjectSchema ==========

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaAllTypes() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"name", Maps.of("type", "string"),
				"age", Maps.of("type", "integer"),
				"score", Maps.of("type", "number"),
				"active", Maps.of("type", "boolean"),
				"tags", Maps.of("type", "array")
			),
			"required", Vectors.of("name", "age")
		);

		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertNotNull(result.properties());
		assertEquals(5, result.properties().size());
		assertNotNull(result.required());
		assertEquals(2, result.required().size());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaNoProperties() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of("type", "object");
		assertNull(LangChainAdapter.toJsonObjectSchema(schema), "Should return null when no properties");
	}

	// ========== stripThinkTags ==========

	@Test
	public void testStripThinkTagsNull() {
		assertNull(LangChainAdapter.stripThinkTags(null));
	}

	@Test
	public void testStripThinkTagsNoTags() {
		assertEquals("Hello", LangChainAdapter.stripThinkTags("Hello"));
	}

	@Test
	public void testStripThinkTagsRemoves() {
		assertEquals("Result", LangChainAdapter.stripThinkTags("<think>some reasoning</think>Result"));
	}
}
