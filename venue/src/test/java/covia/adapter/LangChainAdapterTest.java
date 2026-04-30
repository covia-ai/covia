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
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
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

	@Test
	public void testToAssistantMessageWithTokens() {
		ChatResponse response = ChatResponse.builder()
			.aiMessage(AiMessage.from("Hi"))
			.tokenUsage(new TokenUsage(12, 3, 15))
			.finishReason(FinishReason.STOP)
			.build();
		ACell msg = LangChainAdapter.toAssistantMessage(response);

		assertEquals(Strings.create("Hi"), RT.getIn(msg, "content"));
		assertEquals(convex.core.data.prim.CVMLong.create(12), RT.getIn(msg, "tokens", "input"));
		assertEquals(convex.core.data.prim.CVMLong.create(3),  RT.getIn(msg, "tokens", "output"));
		assertEquals(convex.core.data.prim.CVMLong.create(15), RT.getIn(msg, "tokens", "total"));
		assertEquals(Strings.create("stop"), RT.getIn(msg, "finishReason"));
	}

	@Test
	public void testToAssistantMessageNoTokens() {
		// Provider didn't report usage — fields should be omitted, not zero
		ChatResponse response = ChatResponse.builder()
			.aiMessage(AiMessage.from("Hi"))
			.build();
		ACell msg = LangChainAdapter.toAssistantMessage(response);

		assertEquals(Strings.create("Hi"), RT.getIn(msg, "content"));
		assertNull(RT.getIn(msg, "tokens"),
			"Absent token usage must not produce a tokens map");
		assertNull(RT.getIn(msg, "finishReason"),
			"Absent finish reason must not produce a finishReason field");
	}

	@Test
	public void testToAssistantMessagePartialTokens() {
		// Some providers report total only, or input+output without total.
		// Missing sub-counts must be omitted, not written as zero.
		ChatResponse response = ChatResponse.builder()
			.aiMessage(AiMessage.from("Hi"))
			.tokenUsage(new TokenUsage(null, null, 50))
			.build();
		ACell msg = LangChainAdapter.toAssistantMessage(response);

		assertNull(RT.getIn(msg, "tokens", "input"));
		assertNull(RT.getIn(msg, "tokens", "output"));
		assertEquals(convex.core.data.prim.CVMLong.create(50), RT.getIn(msg, "tokens", "total"));
	}

	@Test
	public void testToAssistantMessageZeroTokensPreserved() {
		// Reported zero is real data (e.g. tool-call-only response with no
		// output text). Distinct from "provider didn't report this count" —
		// must be written, not dropped.
		ChatResponse response = ChatResponse.builder()
			.aiMessage(AiMessage.from(""))
			.tokenUsage(new TokenUsage(10, 0, 10))
			.build();
		ACell msg = LangChainAdapter.toAssistantMessage(response);

		assertEquals(convex.core.data.prim.CVMLong.create(10), RT.getIn(msg, "tokens", "input"));
		assertEquals(convex.core.data.prim.CVMLong.create(0),  RT.getIn(msg, "tokens", "output"));
		assertEquals(convex.core.data.prim.CVMLong.create(10), RT.getIn(msg, "tokens", "total"));
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
		assertRequiredPropertiesExist(result, "allTypes");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaNoProperties() {
		// toJsonObjectSchema still returns null for top-level schemas without properties
		// (the fix is in toSchemaElement which wraps the null into an empty object)
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

	// ========== JSON Schema correctness invariants ==========

	/**
	 * Invariant: every entry in "required" must have a corresponding entry in "properties".
	 * Violating this produces invalid JSON Schema that strict validators (e.g. Gemini) reject.
	 */
	private static void assertRequiredPropertiesExist(JsonObjectSchema schema, String context) {
		if (schema.required() == null) return;
		for (String req : schema.required()) {
			assertNotNull(schema.properties().get(req),
				context + ": required property '" + req + "' missing from properties");
		}
	}

	/**
	 * Invariant: all declared properties in the input must appear in the output.
	 * No silent dropping of valid JSON Schema constructs during conversion.
	 */
	private static void assertAllPropertiesPreserved(
			AMap<AString, ACell> inputProperties, JsonObjectSchema output, String context) {
		inputProperties.forEach((key, value) -> {
			assertNotNull(output.properties().get(key.toString()),
				context + ": input property '" + key + "' was silently dropped during conversion");
		});
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testObjectPropertyWithoutSubProperties() {
		// type: "object" without "properties" is valid JSON Schema (means "any object").
		// Conversion must not drop it — that would break the required/properties invariant.
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"metadata", Maps.of("type", "object", "description", "Arbitrary JSON metadata"),
				"name", Maps.of("type", "string", "description", "A name")
			),
			"required", Vectors.of("metadata")
		);

		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);

		AMap<AString, ACell> inputProps = (AMap<AString, ACell>)(AMap) Maps.of(
			"metadata", Maps.of("type", "object", "description", "Arbitrary JSON metadata"),
			"name", Maps.of("type", "string", "description", "A name")
		);
		assertAllPropertiesPreserved(inputProps, result, "object-without-subproperties");
		assertRequiredPropertiesExist(result, "object-without-subproperties");

		// The propertyless object must be a JsonObjectSchema, not downcast to string
		assertInstanceOf(JsonObjectSchema.class, result.properties().get("metadata"));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testRequiredPropertiesInvariantWithAllTypes() {
		// Every type of property — when listed as required — must appear in the output.
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"s", Maps.of("type", "string"),
				"i", Maps.of("type", "integer"),
				"n", Maps.of("type", "number"),
				"b", Maps.of("type", "boolean"),
				"a", Maps.of("type", "array"),
				"o", Maps.of("type", "object", "properties", Maps.of("x", Maps.of("type", "string"))),
				"bare_o", Maps.of("type", "object")
			),
			"required", Vectors.of("s", "i", "n", "b", "a", "o", "bare_o")
		);

		var result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertRequiredPropertiesExist(result, "all-types-required");
		assertEquals(7, result.properties().size());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToolSpecWithPropertylessObjectParam() {
		// End-to-end: tool spec matching asset:store's shape
		var tools = Vectors.of(
			Maps.of(
				"name", "store_asset",
				"description", "Store an asset",
				"parameters", Maps.of(
					"type", "object",
					"properties", Maps.of(
						"metadata", Maps.of("type", "object", "description", "Asset metadata"),
						"content", Maps.of("type", "string")
					),
					"required", Vectors.of("metadata")
				)
			)
		);

		List<ToolSpecification> specs = LangChainAdapter.toToolSpecifications(tools);
		assertEquals(1, specs.size());

		JsonObjectSchema params = specs.get(0).parameters();
		assertNotNull(params);
		assertRequiredPropertiesExist(params, "store_asset tool");
		assertEquals(2, params.properties().size());
		assertInstanceOf(JsonObjectSchema.class, params.properties().get("metadata"));
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
