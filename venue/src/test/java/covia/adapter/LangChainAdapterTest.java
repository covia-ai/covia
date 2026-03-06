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
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

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

	// ========== toSchemaElement ==========

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static AMap<AString, ACell> schemaMap(Object... kv) {
		return (AMap<AString, ACell>)(AMap) Maps.of(kv);
	}

	@Test
	public void testSchemaElementString() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("type", "string", "description", "A name"));
		assertInstanceOf(JsonStringSchema.class, el);
		assertEquals("A name", ((JsonStringSchema) el).description());
	}

	@Test
	public void testSchemaElementInteger() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("type", "integer"));
		assertInstanceOf(JsonIntegerSchema.class, el);
	}

	@Test
	public void testSchemaElementNumber() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("type", "number"));
		assertInstanceOf(JsonNumberSchema.class, el);
	}

	@Test
	public void testSchemaElementBoolean() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("type", "boolean"));
		assertInstanceOf(JsonBooleanSchema.class, el);
	}

	@Test
	public void testSchemaElementDefaultsToString() {
		// No type specified → defaults to string
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("description", "no type"));
		assertInstanceOf(JsonStringSchema.class, el);
	}

	@Test
	public void testSchemaElementUnknownTypeDefaultsToString() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("type", "xml"));
		assertInstanceOf(JsonStringSchema.class, el);
	}

	// --- Enum ---

	@Test
	public void testSchemaElementEnum() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "string", "enum", Vectors.of("red", "green", "blue")));
		assertInstanceOf(JsonEnumSchema.class, el);
		JsonEnumSchema enumSchema = (JsonEnumSchema) el;
		assertEquals(3, enumSchema.enumValues().size());
		assertTrue(enumSchema.enumValues().contains("red"));
		assertTrue(enumSchema.enumValues().contains("green"));
		assertTrue(enumSchema.enumValues().contains("blue"));
	}

	@Test
	public void testSchemaElementEnumWithDescription() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "string", "enum", Vectors.of("low", "high"), "description", "Priority level"));
		assertInstanceOf(JsonEnumSchema.class, el);
		assertEquals("Priority level", ((JsonEnumSchema) el).description());
	}

	@Test
	public void testSchemaElementEmptyEnumFallsBackToString() {
		// Empty enum array → no enum values → falls back to string
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "string", "enum", Vectors.empty()));
		assertInstanceOf(JsonStringSchema.class, el);
	}

	// --- Array with items ---

	@Test
	public void testSchemaElementArrayNoItems() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("type", "array"));
		assertInstanceOf(JsonArraySchema.class, el);
		// LangChain4j may set items to null or a default
	}

	@Test
	public void testSchemaElementArrayWithStringItems() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "array", "items", Maps.of("type", "string")));
		assertInstanceOf(JsonArraySchema.class, el);
		JsonArraySchema arr = (JsonArraySchema) el;
		assertNotNull(arr.items(), "Array should have items schema");
		assertInstanceOf(JsonStringSchema.class, arr.items());
	}

	@Test
	public void testSchemaElementArrayWithIntegerItems() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "array", "items", Maps.of("type", "integer")));
		assertInstanceOf(JsonArraySchema.class, el);
		assertInstanceOf(JsonIntegerSchema.class, ((JsonArraySchema) el).items());
	}

	@Test
	public void testSchemaElementArrayWithObjectItems() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "array", "items", Maps.of(
				"type", "object",
				"properties", Maps.of("x", Maps.of("type", "number"))
			)));
		assertInstanceOf(JsonArraySchema.class, el);
		assertInstanceOf(JsonObjectSchema.class, ((JsonArraySchema) el).items());
	}

	@Test
	public void testSchemaElementArrayWithEnumItems() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "array", "items", Maps.of(
				"type", "string", "enum", Vectors.of("a", "b")
			)));
		assertInstanceOf(JsonArraySchema.class, el);
		assertInstanceOf(JsonEnumSchema.class, ((JsonArraySchema) el).items());
	}

	@Test
	public void testSchemaElementArrayWithDescription() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "array", "items", Maps.of("type", "string"), "description", "List of tags"));
		assertInstanceOf(JsonArraySchema.class, el);
		assertEquals("List of tags", ((JsonArraySchema) el).description());
	}

	// --- Nested objects ---

	@Test
	public void testSchemaElementNestedObject() {
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "object", "properties", Maps.of(
				"street", Maps.of("type", "string"),
				"city", Maps.of("type", "string")
			)));
		assertInstanceOf(JsonObjectSchema.class, el);
		JsonObjectSchema obj = (JsonObjectSchema) el;
		assertEquals(2, obj.properties().size());
	}

	@Test
	public void testSchemaElementDeeplyNested() {
		// object → object → string
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(
			schemaMap("type", "object", "properties", Maps.of(
				"address", Maps.of("type", "object", "properties", Maps.of(
					"city", Maps.of("type", "string")
				))
			)));
		assertInstanceOf(JsonObjectSchema.class, el);
		JsonObjectSchema outer = (JsonObjectSchema) el;
		assertInstanceOf(JsonObjectSchema.class, outer.properties().get("address"));
		JsonObjectSchema inner = (JsonObjectSchema) outer.properties().get("address");
		assertInstanceOf(JsonStringSchema.class, inner.properties().get("city"));
	}

	// ========== toJsonObjectSchema: required + description ==========

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaDescription() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of("x", Maps.of("type", "string")),
			"description", "A point"
		);
		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertEquals("A point", result.description());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaNoRequired() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of("x", Maps.of("type", "string"))
		);
		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		// required may be null or empty
		assertTrue(result.required() == null || result.required().isEmpty());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaWithEnumProperty() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"status", Maps.of("type", "string", "enum", Vectors.of("active", "inactive"))
			),
			"required", Vectors.of("status")
		);
		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertInstanceOf(JsonEnumSchema.class, result.properties().get("status"));
		assertEquals(1, result.required().size());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaWithArrayItems() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"tags", Maps.of("type", "array", "items", Maps.of("type", "string")),
				"scores", Maps.of("type", "array", "items", Maps.of("type", "number"))
			)
		);
		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertEquals(2, result.properties().size());

		JsonArraySchema tags = (JsonArraySchema) result.properties().get("tags");
		assertInstanceOf(JsonStringSchema.class, tags.items());

		JsonArraySchema scores = (JsonArraySchema) result.properties().get("scores");
		assertInstanceOf(JsonNumberSchema.class, scores.items());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaNestedObject() {
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"name", Maps.of("type", "string"),
				"address", Maps.of("type", "object", "properties", Maps.of(
					"street", Maps.of("type", "string"),
					"city", Maps.of("type", "string"),
					"postcode", Maps.of("type", "string")
				), "required", Vectors.of("city"))
			),
			"required", Vectors.of("name")
		);
		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertEquals(2, result.properties().size());
		assertEquals(List.of("name"), result.required());

		JsonObjectSchema address = (JsonObjectSchema) result.properties().get("address");
		assertNotNull(address);
		assertEquals(3, address.properties().size());
		assertEquals(List.of("city"), address.required());
	}

	// ========== toToolSpecifications with complex parameters ==========

	@Test
	public void testToolSpecWithEnumAndArrayParams() {
		var tools = Vectors.of(
			Maps.of(
				"name", "create_item",
				"description", "Create an item",
				"parameters", Maps.of(
					"type", "object",
					"properties", Maps.of(
						"title", Maps.of("type", "string"),
						"priority", Maps.of("type", "string", "enum", Vectors.of("low", "medium", "high")),
						"tags", Maps.of("type", "array", "items", Maps.of("type", "string"))
					),
					"required", Vectors.of("title", "priority")
				)
			)
		);

		List<ToolSpecification> specs = LangChainAdapter.toToolSpecifications(tools);
		assertEquals(1, specs.size());
		assertNotNull(specs.get(0).parameters());

		JsonObjectSchema params = specs.get(0).parameters();
		assertEquals(3, params.properties().size());
		assertInstanceOf(JsonStringSchema.class, params.properties().get("title"));
		assertInstanceOf(JsonEnumSchema.class, params.properties().get("priority"));
		assertInstanceOf(JsonArraySchema.class, params.properties().get("tags"));

		JsonArraySchema tags = (JsonArraySchema) params.properties().get("tags");
		assertInstanceOf(JsonStringSchema.class, tags.items());
	}

	@Test
	public void testToolSpecWithNestedObjectParam() {
		var tools = Vectors.of(
			Maps.of(
				"name", "send_email",
				"description", "Send an email",
				"parameters", Maps.of(
					"type", "object",
					"properties", Maps.of(
						"to", Maps.of("type", "string"),
						"body", Maps.of("type", "object", "properties", Maps.of(
							"text", Maps.of("type", "string"),
							"html", Maps.of("type", "string")
						))
					)
				)
			)
		);

		List<ToolSpecification> specs = LangChainAdapter.toToolSpecifications(tools);
		assertEquals(1, specs.size());

		JsonObjectSchema params = specs.get(0).parameters();
		assertInstanceOf(JsonObjectSchema.class, params.properties().get("body"));
		JsonObjectSchema body = (JsonObjectSchema) params.properties().get("body");
		assertEquals(2, body.properties().size());
	}

	// ========== responseFormat with enum and array schemas ==========

	@Test
	public void testResponseFormatWithEnumProperty() {
		ACell responseFormat = Maps.of(
			"name", "Sentiment",
			"schema", Maps.of(
				"type", "object",
				"properties", Maps.of(
					"sentiment", Maps.of("type", "string", "enum", Vectors.of("positive", "negative", "neutral")),
					"confidence", Maps.of("type", "number")
				),
				"required", Vectors.of("sentiment")
			)
		);

		ResponseFormat rf = LangChainAdapter.toResponseFormat(responseFormat);
		assertNotNull(rf);
		assertEquals(ResponseFormatType.JSON, rf.type());
		assertEquals("Sentiment", rf.jsonSchema().name());
	}

	@Test
	public void testResponseFormatWithArrayOfObjects() {
		ACell responseFormat = Maps.of(
			"name", "SearchResults",
			"schema", Maps.of(
				"type", "object",
				"properties", Maps.of(
					"results", Maps.of("type", "array", "items", Maps.of(
						"type", "object",
						"properties", Maps.of(
							"title", Maps.of("type", "string"),
							"url", Maps.of("type", "string")
						)
					)),
					"total", Maps.of("type", "integer")
				),
				"required", Vectors.of("results", "total")
			)
		);

		ResponseFormat rf = LangChainAdapter.toResponseFormat(responseFormat);
		assertNotNull(rf);
		assertEquals("SearchResults", rf.jsonSchema().name());
		assertNotNull(rf.jsonSchema().rootElement());
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
