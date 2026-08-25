package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRole;
import dev.langchain4j.model.anthropic.internal.api.AnthropicToolResultContent;
import dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import covia.adapter.agent.AbstractLLMAdapter;
import covia.adapter.agent.ConversationRenderer;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Unit tests for LangChainAdapter conversion methods.
 */
public class LangChainAdapterTest {

	@Test
	public void testProviderKeyPrefersSecretStoreThenProcessEnvironment() {
		assertEquals("caller-key",
			LangChainAdapter.preferStoredSecret("caller-key", "operator-key"));
		assertEquals("operator-key",
			LangChainAdapter.preferStoredSecret(null, "operator-key"));
		assertEquals("operator-key",
			LangChainAdapter.preferStoredSecret("  ", "operator-key"));
		assertNull(LangChainAdapter.preferStoredSecret(null, "  "));
	}

	// ========== #91 regression: silent fallback when secret resolution fails ==========

	/**
	 * #91: When the configured secret cannot be resolved (no secret stored,
	 * or the concurrent-resolution race), the adapter must return a clear
	 * Status.failure inside its CompletableFuture — not throw synchronously,
	 * not silently call the provider with junk auth.
	 */
	@Test
	public void testAnthropicMissingSecretReturnsClearError() throws Exception {
		assertMissingSecretReportsClearly("langchain:anthropic", "ANTHROPIC_API_KEY");
	}

	@Test
	public void testOpenAiMissingSecretReturnsClearError() throws Exception {
		assertMissingSecretReportsClearly("langchain:openai", "OPENAI_API_KEY");
	}

	/**
	 * #91: When the caller passes an apiKey of the form "/s/<name>" but the
	 * named secret can't be resolved, the literal "/s/<name>" must NOT be
	 * passed through to the provider as the API key. (Doing so would cause
	 * a 401 with a confusing message — exactly what the bug report saw.)
	 */
	@Test
	public void testAnthropicUnresolvedSecretRefReturnsClearError() throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			Engine.addDemoAssets(engine);
			RequestContext ctx = RequestContext.of(Strings.create("did:key:zTestNoSecret"));

			AMap<AString, ACell> meta = Maps.of(
				Strings.create("operation"), Maps.of(
					Strings.create("adapter"), Strings.create("langchain:anthropic")
				)
			);
			ACell input = Maps.of(
				Strings.create("prompt"), Strings.create("hi"),
				Strings.create("apiKey"), Strings.create("/s/anthropic"),
				Strings.create("url"), Strings.create("http://127.0.0.1:1/")
			);

			LangChainAdapter adapter = (LangChainAdapter) engine.getAdapter("langchain");
			ACell result = adapter.invokeFuture(ctx, meta, input)
				.get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertFailureMentioning(result, "/s/anthropic");
		} finally {
			engine.close();
		}
	}

	private static void assertMissingSecretReportsClearly(String adapterOp, String secretName) throws Exception {
		Engine engine = Engine.createTemp(null);
		try {
			Engine.addDemoAssets(engine);
			RequestContext ctx = RequestContext.of(Strings.create("did:key:zTestNoSecret"));

			AMap<AString, ACell> meta = Maps.of(
				Strings.create("operation"), Maps.of(
					Strings.create("adapter"), Strings.create(adapterOp),
					Strings.create("secretKey"), Strings.create(secretName)
				)
			);
			ACell input = Maps.of(
				Strings.create("prompt"), Strings.create("hi"),
				// Closed port so an accidental HTTP call would fail fast — but the
				// fix should make the adapter fail BEFORE attempting any dial.
				Strings.create("url"), Strings.create("http://127.0.0.1:1/")
			);

			LangChainAdapter adapter = (LangChainAdapter) engine.getAdapter("langchain");
			ACell result = adapter.invokeFuture(ctx, meta, input)
				.get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertFailureMentioning(result, secretName);
		} finally {
			engine.close();
		}
	}

	private static void assertFailureMentioning(ACell result, String hint) {
		AString status = RT.ensureString(RT.getIn(result, Strings.create("status")));
		assertEquals(Status.FAILED, status,
			"Expected FAILED status, got: " + result);
		AString message = RT.ensureString(RT.getIn(result, Strings.create("message")));
		assertNotNull(message, "Failure must carry a message");
		String lower = message.toString().toLowerCase();
		assertTrue(lower.contains("api key") || lower.contains("secret"),
			"Failure message should mention API key / secret, got: " + message);
		assertTrue(message.toString().contains(hint),
			"Failure message should reference the secret name/ref '" + hint + "', got: " + message);
	}

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
	public void testToAssistantMessageCanonicalisesOpenAiStyleArguments() {
		AiMessage ai = new AiMessage(null, List.of(ToolExecutionRequest.builder()
			.id("toolu_1")
			.name("agent_create")
			.arguments("{\"agentId\":\"Worker1\",\"config\":\"v/agents/templates/worker\"}")
			.build()));

		ACell msg = LangChainAdapter.toAssistantMessage(ai);
		ACell arguments = RT.getIn(msg, "toolCalls", 0L, "arguments");
		assertInstanceOf(AMap.class, arguments,
			"provider JSON text must not leak into the canonical conversation");
		assertEquals("Worker1", RT.getIn(arguments, "agentId").toString());
		assertEquals("v/agents/templates/worker", RT.getIn(arguments, "config").toString());
	}

	@Test
	public void testToAssistantMessageRetainsMalformedArgumentsForAudit() {
		AiMessage ai = new AiMessage(null, List.of(ToolExecutionRequest.builder()
			.id("toolu_bad").name("agent_create").arguments("{broken").build()));

		ACell msg = LangChainAdapter.toAssistantMessage(ai);
		assertEquals(Strings.create("{broken"), RT.getIn(msg, "toolCalls", 0L, "arguments"),
			"the tool loop needs the exact provider output to report a useful error");
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
	public void testToChatMessagesRendersStructuredAgentRequestAsJson() {
		ACell request = Maps.of("task", "echo marker", "marker", "COVIA-349");
		var messages = Vectors.of(Maps.of(
			"role", "user", "content", request));

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		UserMessage user = assertInstanceOf(UserMessage.class, result.get(0));
		assertEquals(JSON.print(request).toString(), user.singleText());
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
	public void testToChatMessagesSerialisesStructuredArgumentsAtProviderBoundary() {
		ACell arguments = Maps.of(
			"agentId", "Worker1",
			"config", "v/agents/templates/worker");
		var messages = Vectors.of(Maps.of(
			"role", "assistant",
			"toolCalls", Vectors.of(Maps.of(
				"id", "toolu_1", "name", "agent_create", "arguments", arguments))));

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		AiMessage assistant = assertInstanceOf(AiMessage.class, result.get(0));
		assertEquals(JSON.print(arguments).toString(),
			assistant.toolExecutionRequests().get(0).arguments());
	}

	@Test
	public void testToChatMessagesAcceptsLegacyStringArguments() {
		String legacy = "{\"path\":\"w/report\"}";
		var messages = Vectors.of(Maps.of(
			"role", "assistant",
			"toolCalls", Vectors.of(Maps.of(
				"id", "call_old", "name", "covia_read", "arguments", legacy))));

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		AiMessage assistant = assertInstanceOf(AiMessage.class, result.get(0));
		assertEquals(legacy, assistant.toolExecutionRequests().get(0).arguments());
	}

	@Test
	public void testToChatMessagesPreservesAnthropicToolErrorFlag() {
		var messages = Vectors.of(Maps.of(
			"role", "tool", "id", "call_bad", "name", "search",
			"content", "Error: unavailable", "isError", CVMBool.TRUE));

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		ToolExecutionResultMessage tool = assertInstanceOf(
			ToolExecutionResultMessage.class, result.get(0));
		assertEquals(Boolean.TRUE, tool.isError());
	}

	@Test
	public void testAnthropicWireMapperGroupsParallelToolResultsAfterToolUse() {
		var canonical = Vectors.of(
			Maps.of("role", "assistant", "content", "", "toolCalls", Vectors.of(
				Maps.of("id", "call_ok", "name", "complete", "arguments", Maps.of("answer", "42")),
				Maps.of("id", "call_skip", "name", "search", "arguments", Maps.empty()))),
			Maps.of("role", "tool", "id", "call_ok", "name", "complete",
				"content", "{\"status\":\"complete\"}"),
			Maps.of("role", "tool", "id", "call_skip", "name", "search",
				"content", "Error: skipped", "isError", CVMBool.TRUE));

		List<AnthropicMessage> wire = AnthropicMapper.toAnthropicMessages(
			LangChainAdapter.toChatMessages(canonical));
		assertEquals(2, wire.size());
		assertEquals(AnthropicRole.ASSISTANT, wire.get(0).role);
		assertEquals(AnthropicRole.USER, wire.get(1).role);
		assertEquals(2, wire.get(1).content.size(),
			"parallel results must share the immediately following Anthropic user turn");
		AnthropicToolResultContent ok = assertInstanceOf(
			AnthropicToolResultContent.class, wire.get(1).content.get(0));
		AnthropicToolResultContent skipped = assertInstanceOf(
			AnthropicToolResultContent.class, wire.get(1).content.get(1));
		assertEquals("call_ok", ok.toolUseId);
		assertEquals("call_skip", skipped.toolUseId);
		assertEquals(Boolean.TRUE, skipped.isError);
	}

	@Test
	public void testProviderCopyMakesStructuredToolResultConsumable() {
		ACell nested = Maps.of("results", Vectors.of(
			Maps.of("source", Maps.of("name", "kyc")),
			Maps.of("source", Maps.of("name", "sanctions"))));
		AMap<AString, ACell> rendered = ConversationRenderer.toMessage(
			Maps.of("role", "tool", "id", "call_old", "name", "covia_read",
				"structuredContent", nested), null);
		assertNull(rendered.get(Strings.intern("content")),
			"conversation rendering must not replace a structured result with empty text");
		var canonical = Vectors.of(rendered);
		var messages = LangChainAdapter.serialiseToolResultsForProvider(canonical);

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		ToolExecutionResultMessage tool = assertInstanceOf(
			ToolExecutionResultMessage.class, result.get(0));
		assertEquals(JSON.toString(nested), tool.text());
	}

	@Test
	public void testProviderToolResultCopyIsTextWithoutChangingCanonicalTurn() {
		ACell nested = Maps.of("results", Vectors.of(
			Maps.of("source", Maps.of("name", "kyc")),
			Maps.of("source", Maps.of("name", "sanctions"))));
		AMap<AString, ACell> canonicalTool = Maps.of(
			"role", "tool", "id", "call_334", "name", "covia_read",
			"structuredContent", nested);
		AVector<ACell> canonical = Vectors.of(
			Maps.of("role", "user", "content", "inspect sources"), canonicalTool);

		AVector<ACell> provider = LangChainAdapter.serialiseToolResultsForProvider(canonical);
		ACell providerTool = provider.get(1);

		assertSame(canonicalTool, canonical.get(1),
			"provider preparation must not replace the durable message");
		assertSame(nested, RT.getIn(canonical.get(1), "structuredContent"),
			"canonical structured value must retain its exact Convex type and identity");
		assertNull(RT.getIn(canonical.get(1), "content"));
		assertNull(RT.getIn(providerTool, "structuredContent"));
		AString providerText = RT.ensureString(RT.getIn(providerTool, "content"));
		assertNotNull(providerText);
		assertEquals(nested, JSON.parse(providerText));
	}

	// ========== Asset-referenced images (covia#198) ==========

	@Test
	public void testImageAssetRefResolved() {
		// Store a tiny "PNG" as a content asset, reference it from a message,
		// and check the adapter inlines it (base64 + contentType) at call time.
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(convex.core.data.Strings.create("did:key:zImgTest"));
		byte[] bytes = new byte[] {(byte)0x89, 0x50, 0x4E, 0x47, 1, 2, 3};
		var meta = Maps.of("name", "scan", "contentType", "image/png");
		var stored = engine.jobs().invokeOperation("v/ops/asset/store",
			Maps.of("metadata", meta, "content",
				convex.core.data.Strings.create("0x" + convex.core.data.Blob.wrap(bytes).toHexString())),
			ctx).awaitResult(5000);
		String id = convex.core.lang.RT.ensureString(
			convex.core.lang.RT.getIn(stored, "id")).toString();

		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of("type", "asset", "ref", id)),
				Maps.of("type", "text", "text", "what is this?"))));

		var adapter = (LangChainAdapter) engine.getAdapter("langchain");
		var resolved = adapter.resolveImageRefs(messages, ctx);
		var block = convex.core.lang.RT.getIn(resolved.get(0), "content");
		assertEquals(convex.core.data.Strings.create("base64"),
			convex.core.lang.RT.getIn(((convex.core.data.AVector<?>) block).get(0), "source", "type"));
		assertEquals(convex.core.data.Strings.create("image/png"),
			convex.core.lang.RT.getIn(((convex.core.data.AVector<?>) block).get(0), "source", "mediaType"));
		assertEquals(convex.core.data.Strings.create(java.util.Base64.getEncoder().encodeToString(bytes)),
			convex.core.lang.RT.getIn(((convex.core.data.AVector<?>) block).get(0), "source", "data"));
		// The text block is untouched; the original messages vector is unchanged.
		assertEquals(convex.core.data.Strings.create("what is this?"),
			convex.core.lang.RT.getIn(((convex.core.data.AVector<?>) block).get(1), "text"));
		// And the resolved form converts to a multimodal UserMessage end-to-end.
		List<ChatMessage> chat = LangChainAdapter.toChatMessages(resolved);
		UserMessage um = assertInstanceOf(UserMessage.class, chat.get(0));
		assertEquals(2, um.contents().size());
	}

	@Test
	public void testImageRefViaWorkspacePath() {
		// A workspace slot holding an asset reference string resolves via one hop.
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(convex.core.data.Strings.create("did:key:zImgWsTest"));
		byte[] bytes = new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 9};
		var stored = engine.jobs().invokeOperation("v/ops/asset/store",
			Maps.of("metadata", Maps.of("name", "snap", "contentType", "image/jpeg"),
				"content", convex.core.data.Strings.create("0x" + convex.core.data.Blob.wrap(bytes).toHexString())),
			ctx).awaitResult(5000);
		String id = convex.core.lang.RT.ensureString(
			convex.core.lang.RT.getIn(stored, "id")).toString();
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "w/pics/one", "value", id), ctx).awaitResult(5000);

		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of("type", "asset", "ref", "w/pics/one")))));
		var adapter = (LangChainAdapter) engine.getAdapter("langchain");
		var resolved = adapter.resolveImageRefs(messages, ctx);
		var blocks = (convex.core.data.AVector<?>) convex.core.lang.RT.getIn(resolved.get(0), "content");
		assertEquals(convex.core.data.Strings.create("image/jpeg"),
			convex.core.lang.RT.getIn(blocks.get(0), "source", "mediaType"));
		assertEquals(convex.core.data.Strings.create(java.util.Base64.getEncoder()
				.encodeToString(new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 9})),
			convex.core.lang.RT.getIn(blocks.get(0), "source", "data"));
	}

	@Test
	public void testImageRefFromOwnDlfsDrive() {
		// A vault/drive file referenced by its DID-scoped DLFS path feeds a
		// vision call directly — no asset:store hop.
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(convex.core.data.Strings.create("did:key:zDlfsImg"));
		byte[] bytes = new byte[] {(byte)0x89, 0x50, 0x4E, 0x47, 7, 7};
		String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
		engine.jobs().invokeOperation("v/ops/dlfs/create-drive",
			Maps.of("name", "vault"), ctx).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", "vault", "path", "scan.png", "bytes", b64), ctx).awaitResult(5000);

		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of("type", "asset", "ref", "dlfs/vault/scan.png")))));
		var adapter = (LangChainAdapter) engine.getAdapter("langchain");
		var resolved = adapter.resolveImageRefs(messages, ctx);
		var blocks = (convex.core.data.AVector<?>) convex.core.lang.RT.getIn(resolved.get(0), "content");
		assertEquals(convex.core.data.Strings.create("image/png"),
			convex.core.lang.RT.getIn(blocks.get(0), "source", "mediaType"), "sniffed from filename/bytes");
		assertEquals(convex.core.data.Strings.create(b64),
			convex.core.lang.RT.getIn(blocks.get(0), "source", "data"));
	}

	@Test
	public void testImageRefFromCrossUserDlfsRequiresProof() {
		// Alice's drive file referenced cross-user: authorised by a UCAN grant,
		// denied without one — the same gate dlfs:read enforces.
		var engine = covia.venue.TestEngine.ENGINE;
		var aliceKP = convex.core.crypto.AKeyPair.generate();
		var bobKP = convex.core.crypto.AKeyPair.generate();
		var aliceDID = convex.auth.ucan.UCAN.toDIDKey(aliceKP.getAccountKey());
		var bobDID = convex.auth.ucan.UCAN.toDIDKey(bobKP.getAccountKey());
		var alice = covia.venue.RequestContext.of(aliceDID);
		byte[] bytes = new byte[] {(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 5};
		engine.jobs().invokeOperation("v/ops/dlfs/create-drive",
			Maps.of("name", "docs"), alice).awaitResult(5000);
		engine.jobs().invokeOperation("v/ops/dlfs/write",
			Maps.of("drive", "docs", "path", "letter.jpg",
				"bytes", java.util.Base64.getEncoder().encodeToString(bytes)), alice).awaitResult(5000);

		String ref = aliceDID + "/dlfs/docs/letter.jpg";
		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of("type", "asset", "ref", ref)))));
		var adapter = (LangChainAdapter) engine.getAdapter("langchain");

		// Without a proof: denied.
		var bobNoProof = covia.venue.RequestContext.of(bobDID);
		assertThrows(Exception.class, () -> adapter.resolveImageRefs(messages, bobNoProof));

		// Alice signs Bob a self-sovereign read grant over the drive.
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		var grant = convex.auth.ucan.UCAN.create(aliceKP,
			convex.auth.ucan.UCAN.fromDIDKey(bobDID), exp,
			Vectors.of(convex.auth.ucan.Capability.create(
				convex.core.data.Strings.create(aliceDID + "/dlfs/docs/"),
				convex.auth.ucan.Capability.CRUD_READ)),
			Vectors.empty());
		var bob = covia.venue.RequestContext.of(bobDID)
			.withProofs(Vectors.of(grant.toMap()));
		var resolved = adapter.resolveImageRefs(messages, bob);
		var blocks = (convex.core.data.AVector<?>) convex.core.lang.RT.getIn(resolved.get(0), "content");
		assertEquals(convex.core.data.Strings.create("image/jpeg"),
			convex.core.lang.RT.getIn(blocks.get(0), "source", "mediaType"));
	}

	@Test
	public void testUnresolvableImageRefFailsLoudly() {
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(convex.core.data.Strings.create("did:key:zImgMissing"));
		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of("type", "asset", "ref", "w/nothing/here")))));
		var adapter = (LangChainAdapter) engine.getAdapter("langchain");
		Exception e = assertThrows(IllegalArgumentException.class,
			() -> adapter.resolveImageRefs(messages, ctx));
		assertTrue(e.getMessage().contains("w/nothing/here"), e.getMessage());
	}

	@Test
	public void testUserContentBlocksVision() {
		// covia#198: a user message's content may be an array of blocks —
		// image (base64) + text — mapped to langchain4j multimodal contents.
		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of(
					"type", "base64", "mediaType", "image/jpeg", "data", "aGVsbG8=")),
				Maps.of("type", "text", "text", "Extract structured health information")
			))
		);

		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		assertEquals(1, result.size());
		UserMessage um = assertInstanceOf(UserMessage.class, result.get(0));
		assertEquals(2, um.contents().size());
		ImageContent img = assertInstanceOf(ImageContent.class, um.contents().get(0));
		assertEquals("aGVsbG8=", img.image().base64Data());
		assertEquals("image/jpeg", img.image().mimeType());
		TextContent txt = assertInstanceOf(TextContent.class, um.contents().get(1));
		assertEquals("Extract structured health information", txt.text());
	}

	@Test
	public void testUserStringContentStillWorks() {
		// Back-compat: plain string content is unchanged by the vision support.
		var messages = Vectors.of(Maps.of("role", "user", "content", "plain text"));
		List<ChatMessage> result = LangChainAdapter.toChatMessages(messages);
		UserMessage um = assertInstanceOf(UserMessage.class, result.get(0));
		assertEquals("plain text", um.singleText());
	}

	@Test
	public void testUnknownContentBlockFailsLoudly() {
		// A silently-dropped block would be a wrong answer — unknown types throw
		// with a diagnosable message.
		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "video", "source", Maps.of("data", "x")))));
		Exception e = assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.toChatMessages(messages));
		assertTrue(e.getMessage().contains("video"), e.getMessage());
	}

	@Test
	public void testImageBlockRequiresBase64Source() {
		var messages = Vectors.of(
			Maps.of("role", "user", "content", Vectors.of(
				Maps.of("type", "image", "source", Maps.of(
					"type", "url", "url", "https://example.com/x.jpg")))));
		Exception e = assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.toChatMessages(messages));
		assertTrue(e.getMessage().contains("base64"), e.getMessage());
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

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Test
	public void testToJsonObjectSchemaTypelessValueStaysStructured() {
		// Mirrors covia/write's input schema: a typed `path` and a typeless `value`.
		// Regression: the typeless `value` used to be advertised to the LLM as a
		// string, so a structured object could not be passed and arrived null. It
		// must now be an open object (additionalProperties allowed), while `path`
		// stays a string.
		AMap<AString, ACell> schema = (AMap<AString, ACell>)(AMap) Maps.of(
			"type", "object",
			"properties", Maps.of(
				"path", Maps.of("type", "string"),
				"value", Maps.of("description", "The value to store (any JSON value)")
			),
			"required", Vectors.of("path", "value")
		);

		JsonObjectSchema result = LangChainAdapter.toJsonObjectSchema(schema);
		assertNotNull(result);
		assertEquals(2, result.properties().size(), "Both path and value must be present");
		assertInstanceOf(JsonStringSchema.class, result.properties().get("path"));

		JsonSchemaElement value = result.properties().get("value");
		assertInstanceOf(JsonObjectSchema.class, value,
			"Typeless `value` must be an open object, not a string");
		assertEquals(Boolean.TRUE, ((JsonObjectSchema) value).additionalProperties());
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
	public void testSchemaElementNoTypeIsOpenObject() {
		// No declared type = "any JSON value". It must be an open object so the LLM
		// can pass a structured value through. Regression: it used to coerce to a
		// string, which dropped structured tool-call args (e.g. covia/write's value
		// arrived null).
		JsonSchemaElement el = LangChainAdapter.toSchemaElement(schemaMap("description", "no type"));
		assertInstanceOf(JsonObjectSchema.class, el);
		JsonObjectSchema obj = (JsonObjectSchema) el;
		assertEquals(Boolean.TRUE, obj.additionalProperties(),
			"Typeless param must allow additional properties so structured values pass through");
		assertEquals("no type", obj.description());
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

	// ========== #81 — provider-aware structured output ==========

	private static final ACell RF_SCHEMA = Maps.of(
		"name", Strings.create("agent_output"),
		"schema", Maps.of(
			"type", "object",
			"properties", Maps.of("answer", Maps.of("type", "integer")),
			"required", Vectors.of("answer")));

	@Test
	public void testProviderCapabilityMap() {
		assertTrue(LangChainAdapter.lacksSchemaResponseFormat("anthropic"));
		for (String p : new String[] {"openai", "ollama", "gemini", "xai", "deepseek", "mistral", "openrouter"}) {
			assertFalse(LangChainAdapter.lacksSchemaResponseFormat(p),
				p + " keeps the native response_format path");
		}
	}

	@Test
	public void testIsOpenAiReasoningModel() {
		for (String m : new String[] {"gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-luna", "gpt-5.4-mini", "gpt-5.4-nano", "GPT-5.6-Terra"}) {
			assertTrue(LangChainAdapter.isOpenAiReasoningModel(m), m + " is a gpt-5.x reasoning model");
		}
		for (String m : new String[] {"gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo", "o1", "o3-mini", null}) {
			assertFalse(LangChainAdapter.isOpenAiReasoningModel(m), m + " is not gpt-5.x");
		}
	}

	/**
	 * Regression for the actual root cause behind the reasoning_effort fix:
	 * a caller that omits "model" (e.g. the frontend's "Venue default" option)
	 * relies on the provider operation's data default. That resolved name, not
	 * the raw null input, must reach the reasoning-model check.
	 */
	@Test
	public void testDefaultOpenAiModelIsARecognisedReasoningModel() {
		Engine engine = covia.venue.TestEngine.ENGINE;
		Asset openai = engine.resolveAsset(Strings.create("v/ops/langchain/openai"), engine.venueContext());
		String resolved = RT.ensureString(RT.getIn(openai.meta(), Fields.OPERATION,
			Fields.DEFAULT, AbstractLLMAdapter.K_MODEL)).toString();
		assertTrue(LangChainAdapter.isOpenAiReasoningModel(resolved),
			"openai's default model (" + resolved + ") must be recognised as a reasoning model, "
			+ "since every catalogued openai model is gpt-5.x");
	}

	@Test
	public void testSyntheticOutputToolCarriesSchema() {
		AMap<AString, ACell> tool = LangChainAdapter.syntheticOutputTool(
			"agent_output", RF_SCHEMA);
		// Round-trip through the real converter — the schema must land as
		// the tool's parameters, exactly like any configured tool.
		List<ToolSpecification> specs = LangChainAdapter.toToolSpecifications(
			Vectors.of((ACell) tool));
		assertEquals(1, specs.size());
		assertEquals("agent_output", specs.get(0).name());
		assertNotNull(specs.get(0).parameters());
		assertTrue(specs.get(0).parameters().properties().containsKey("answer"));
	}

	@Test
	public void testConvertOutputToolCall() {
		// The reported Anthropic shape: text preamble + forced tool_use block.
		ACell msg = Maps.of(
			"role", Strings.create("assistant"),
			"content", Strings.create("Sure, here is the answer:"),
			"toolCalls", Vectors.of(Maps.of(
				"id", Strings.create("tu_1"),
				"name", Strings.create("agent_output"),
				"arguments", Strings.create("{\"answer\": 42}"))),
			"tokens", Maps.of("input", 10L, "output", 5L, "total", 15L));

		ACell converted = LangChainAdapter.convertOutputToolCall(msg, "agent_output");
		assertEquals("{\"answer\": 42}", RT.getIn(converted, "content").toString(),
			"content must be the arguments JSON, preamble discarded");
		assertNull(RT.getIn(converted, "toolCalls"), "the synthetic call must not leak upstream");
		assertNotNull(RT.getIn(converted, "tokens"), "usage accounting must survive the rewrite");

		// Ordinary tool calls pass through untouched — agent loops unaffected
		ACell workCall = Maps.of(
			"role", Strings.create("assistant"),
			"toolCalls", Vectors.of(Maps.of(
				"name", Strings.create("covia_read"),
				"arguments", Strings.create("{\"path\": \"w/x\"}"))));
		assertSame(workCall, LangChainAdapter.convertOutputToolCall(workCall, "agent_output"));

		// Text-only responses pass through untouched
		ACell text = Maps.of("role", Strings.create("assistant"),
			"content", Strings.create("plain reply"));
		assertSame(text, LangChainAdapter.convertOutputToolCall(text, "agent_output"));
	}

	@Test
	public void testForcedToolStructuredCallEndToEnd() {
		// Stub provider standing in for Anthropic: captures the request the
		// adapter built, replies with the multi-block shape (text preamble +
		// forced tool_use). Verifies the whole branch: schema tool added,
		// tool choice forced, work tools preserved, response converted to
		// schema-conformant text with usage intact.
		var captured = new java.util.concurrent.atomic.AtomicReference<dev.langchain4j.model.chat.request.ChatRequest>();
		dev.langchain4j.model.chat.ChatModel stub = new dev.langchain4j.model.chat.ChatModel() {
			@Override
			public ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
				captured.set(request);
				AiMessage ai = new AiMessage("Let me deliver the answer.",
					List.of(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
						.id("tu_1").name("agent_output")
						.arguments("{\"answer\": 42}").build()));
				return ChatResponse.builder()
					.aiMessage(ai)
					.tokenUsage(new TokenUsage(10, 5))
					.finishReason(FinishReason.TOOL_EXECUTION)
					.build();
			}
		};

		convex.core.data.AVector<ACell> messages = Vectors.of(
			(ACell) Maps.of("role", Strings.create("user"), "content", Strings.create("what is 6*7?")));
		convex.core.data.AVector<ACell> workTools = Vectors.of(
			(ACell) Maps.of("name", Strings.create("covia_read"),
				"description", Strings.create("read a value")));

		ACell result = LangChainAdapter.callModelForcedTool(stub, messages, workTools, RF_SCHEMA, java.util.Set.of());

		// Request shape: both tools present, choice forced
		var request = captured.get();
		assertEquals(2, request.toolSpecifications().size(), "work tool + synthetic output tool");
		assertTrue(request.toolSpecifications().stream().anyMatch(t -> t.name().equals("agent_output")));
		assertTrue(request.toolSpecifications().stream().anyMatch(t -> t.name().equals("covia_read")));
		assertEquals(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED, request.toolChoice());
		assertNull(request.responseFormat(), "no response_format on the forced-tool path");

		// Response: exactly what native response_format would have produced
		assertEquals("{\"answer\":42}", RT.getIn(result, "content").toString(),
			"structured arguments are canonically encoded for the synthetic output");
		assertNull(RT.getIn(result, "toolCalls"));
		assertEquals(15L, RT.ensureLong(RT.getIn(result, "tokens", "total")).longValue(),
			"usage must be measured on the forced-tool path too");
	}

	// ========== #218 — temperature / topP pass-through ==========

	@Test
	public void testTuningReachesProviderModels() {
		LangChainAdapter.ModelTuning tuning = new LangChainAdapter.ModelTuning(2048, 0.0, 0.9, null);

		var ollama = LangChainAdapter.buildOllamaModel("http://localhost:11434", "qwen",
			java.time.Duration.ofSeconds(5), tuning);
		assertEquals(0.0, ollama.defaultRequestParameters().temperature());
		assertEquals(0.9, ollama.defaultRequestParameters().topP());

		var openai = LangChainAdapter.buildOpenAiModel("key", "https://api.openai.com/v1", "gpt-5.4-mini",
			java.time.Duration.ofSeconds(5), tuning);
		assertEquals(0.0, openai.defaultRequestParameters().temperature());
		assertEquals(0.9, openai.defaultRequestParameters().topP());

		var anthropic = LangChainAdapter.buildAnthropicModel("key", "https://api.anthropic.com/v1/",
			"claude-sonnet-5", java.time.Duration.ofSeconds(5), tuning);
		assertEquals(0.0, anthropic.defaultRequestParameters().temperature());
		assertEquals(0.9, anthropic.defaultRequestParameters().topP());
		assertEquals(2048, anthropic.defaultRequestParameters().maxOutputTokens());

		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.buildAnthropicModel("key", "https://api.anthropic.com/v1/",
				"claude-sonnet-5", java.time.Duration.ofSeconds(5), LangChainAdapter.ModelTuning.NONE));
		assertTrue(missing.getMessage().contains("effective maxTokens"));

		// Absent tuning leaves provider defaults alone (no crash, no forced values)
		var plain = LangChainAdapter.buildOllamaModel("http://localhost:11434", "qwen",
			java.time.Duration.ofSeconds(5), LangChainAdapter.ModelTuning.NONE);
		assertNull(plain.defaultRequestParameters().temperature());
	}

	@Test
	public void testHostedProviderIoTimeoutBoundsStalledResponse() throws Exception {
		HttpServer stalled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		CountDownLatch requestEntered = new CountDownLatch(1);
		CountDownLatch releaseResponse = new CountDownLatch(1);
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		stalled.setExecutor(executor);
		stalled.createContext("/", exchange -> {
			requestEntered.countDown();
			try {
				releaseResponse.await(10, TimeUnit.SECONDS);
				byte[] body = "{\"choices\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (IOException ignored) {
				// The timed-out client normally closes before the fixture responds.
			} finally {
				exchange.close();
			}
		});
		stalled.start();

		try {
			String baseUrl = "http://127.0.0.1:" + stalled.getAddress().getPort() + "/v1";
			var model = LangChainAdapter.buildOpenAiModel("test-key", baseUrl, "test-model",
				Duration.ofMillis(150), LangChainAdapter.ModelTuning.NONE);
			ChatRequest request = ChatRequest.builder()
				.messages(UserMessage.from("timeout contract"))
				.build();

			long started = System.nanoTime();
			assertThrows(RuntimeException.class, () -> model.chat(request));
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			assertTrue(requestEntered.await(1, TimeUnit.SECONDS),
				"the timeout must cover an actual in-flight provider request");
			// The provider client retries transport failures, so the wall-clock
			// bound is the per-attempt timeout plus its bounded retry/backoff policy.
			// The fixture itself remains stalled for ten seconds: returning well
			// before that proves the configured IO timeout is actually effective.
			assertTrue(elapsedMs < 8_000,
				"configured IO timeout should bound retries, elapsed=" + elapsedMs + "ms");
		} finally {
			releaseResponse.countDown();
			stalled.stop(0);
			executor.close();
		}
	}

	@Test
	public void testExtractTuningAcceptsIntegerNumbers() {
		// temperature: 0 arrives as a LONG from JSON — the deterministic-
		// extraction case that motivated #218 must not be dropped
		ACell input = JSON.parse("{\"temperature\": 0, \"topP\": 1, \"maxTokens\": 512}");
		LangChainAdapter.ModelTuning t = LangChainAdapter.extractTuning(input);
		assertEquals(0.0, t.temperature());
		assertEquals(1.0, t.topP());
		assertEquals(512, t.maxTokens());

		LangChainAdapter.ModelTuning none = LangChainAdapter.extractTuning(JSON.parse("{}"));
		assertNull(none.temperature());
		assertNull(none.topP());
		assertNull(none.maxTokens());

		assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.extractTuning(JSON.parse("{\"temperature\": \"hot\"}")));
		assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.extractTuning(JSON.parse("{\"maxTokens\": 0}")));
		assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.extractTuning(JSON.parse("{\"maxTokens\": 2147483648}")));
		assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.extractTuning(JSON.parse("{\"maxTokens\": \"1024\"}")));
	}

	// ========== #224 — Ollama base URL resolution + connect hint ==========

	@Test
	public void testResolveOllamaUrlPrecedence() {
		AString explicit = Strings.create("http://explicit:11434");
		AMap<AString, ACell> cfg = Maps.of(Strings.create("ollamaUrl"),
			Strings.create("http://from-config:11434"));

		assertEquals("http://explicit:11434",
			LangChainAdapter.resolveOllamaUrl(explicit, cfg, "http://from-env:11434"));
		assertEquals("http://from-config:11434",
			LangChainAdapter.resolveOllamaUrl(null, cfg, "http://from-env:11434"));
		assertEquals("http://from-env:11434",
			LangChainAdapter.resolveOllamaUrl(null, null, "http://from-env:11434"));
		assertEquals("http://localhost:11434",
			LangChainAdapter.resolveOllamaUrl(null, null, null));
		assertEquals("http://localhost:11434",
			LangChainAdapter.resolveOllamaUrl(null, Maps.empty(), "  "));
	}

	@Test
	public void testOllamaConnectHint() {
		RuntimeException connect = new RuntimeException("request failed",
			new java.net.ConnectException("Connection refused"));
		String hint = LangChainAdapter.ollamaConnectHint("http://localhost:11434", connect);
		assertNotNull(hint);
		assertTrue(hint.contains("http://localhost:11434"), hint);
		assertTrue(hint.contains("adapters.langchain.ollamaUrl"), hint);
		assertTrue(hint.contains("host.docker.internal"), hint);

		// Non-connectivity failures pass through untouched
		assertNull(LangChainAdapter.ollamaConnectHint("http://localhost:11434",
			new RuntimeException("model not found: qwen")));
	}

	// ========== langchain:models discovery ==========

	@Test
	public void testModelCatalogPublishesCompleteInvocableDefinitions() {
		Engine engine = covia.venue.TestEngine.ENGINE;
		RequestContext ctx = engine.venueContext();
		Asset provider = engine.resolveAsset(
			Strings.create("v/ops/langchain/anthropic"), ctx);
		assertEquals(8192L, RT.ensureLong(RT.getIn(provider.meta(),
			Fields.OPERATION, Fields.DEFAULT, "maxTokens")).longValue());
		Asset anthropic = engine.resolveAsset(
			Strings.create("v/models/anthropic/claude-opus-5"), ctx);
		assertNotNull(anthropic);
		assertEquals("langchain:anthropic",
			RT.getIn(anthropic.meta(), Fields.OPERATION, "adapter").toString());
		assertEquals("claude-opus-5",
			RT.getIn(anthropic.meta(), Fields.OPERATION, Fields.DEFAULT, "model").toString());
		assertEquals(8192L, RT.ensureLong(RT.getIn(anthropic.meta(),
			Fields.OPERATION, Fields.DEFAULT, "maxTokens")).longValue());
		Asset fable = engine.resolveAsset(
			Strings.create("v/models/anthropic/claude-fable-5"), ctx);
		assertEquals(16000L, RT.ensureLong(RT.getIn(fable.meta(),
			Fields.OPERATION, Fields.DEFAULT, "maxTokens")).longValue(),
			"model metadata may override the provider default");
		assertEquals("ANTHROPIC_API_KEY",
			RT.getIn(anthropic.meta(), Fields.OPERATION, "secretKey").toString());
		assertNotNull(RT.getIn(anthropic.meta(), Fields.OPERATION, "input"));
		assertNotNull(RT.getIn(anthropic.meta(), Fields.OPERATION, "output"));
		assertNotNull(engine.resolveAsset(
			Strings.create("v/models/openrouter/anthropic/claude-sonnet-5"), ctx));
		assertTrue(LangChainAdapter.providerNeedsApiKey("mistral") && LangChainAdapter.providerNeedsApiKey("openrouter"));
	}

	@Test
	public void testModelOverrideSelectsEffectiveProviderProfile() {
		Engine engine = covia.venue.TestEngine.ENGINE;
		RequestContext ctx = engine.venueContext();
		Asset preset = engine.resolveAsset(Strings.create(
			"v/models/openrouter/anthropic/claude-sonnet-5"), ctx);
		var selected = AbstractLLMAdapter.resolveModel(engine, preset, null, null, ctx);
		assertEquals("anthropic/claude-sonnet-5", selected.modelId().toString());
		assertEquals(400000L, AbstractLLMAdapter.budgetBytes(selected.executionProfile(), 0));

		var overridden = AbstractLLMAdapter.resolveModel(engine, preset,
			Strings.create("google/gemini-3.5-flash"), null, ctx);
		assertEquals("google/gemini-3.5-flash", overridden.modelId().toString());
		assertEquals(1000000L, AbstractLLMAdapter.budgetBytes(overridden.executionProfile(), 0),
			"caller model override must select the effective model profile");
	}

	@Test
	public void testModelsDiscoveryCallerRelative() throws Exception {
		// Readiness must reflect the CALLER's secret store — same venue,
		// different callers, different answers.
		covia.venue.Engine engine = covia.venue.TestEngine.ENGINE;
		AString did = Strings.create("did:key:z6Mk-test-models-discovery");
		covia.venue.RequestContext ctx = covia.venue.RequestContext.of(did);

		ACell before = engine.jobs().invokeInternal("v/ops/langchain/models",
			Maps.of(Strings.create("provider"), Strings.create("anthropic")), ctx)
			.get(10, java.util.concurrent.TimeUnit.SECONDS);
		AMap<AString, ACell> entry = RT.castMap(
			RT.ensureVector(RT.getIn(before, "providers")).get(0));
		assertEquals("anthropic", RT.getIn(entry, "provider").toString());
		assertEquals("ANTHROPIC_API_KEY", RT.getIn(entry, "keySecret").toString());
		assertEquals("claude-sonnet-5", RT.getIn(entry, "defaultModel").toString());
		assertEquals("claude-haiku-4-5-20251001",
			RT.getIn(entry, "recommendations", "economical").toString());
		assertEquals(convex.core.data.prim.CVMBool.FALSE, RT.getIn(entry, "ready"));

		// Store the key → ready flips, for this caller only. The VALUE never
		// appears anywhere in the discovery output.
		engine.jobs().invokeInternal("v/ops/secret/set",
			Maps.of(Strings.create("name"), Strings.create("ANTHROPIC_API_KEY"),
				Strings.create("value"), Strings.create("sk-test-dummy")), ctx)
			.get(5, java.util.concurrent.TimeUnit.SECONDS);
		ACell after = engine.jobs().invokeInternal("v/ops/langchain/models",
			Maps.of(Strings.create("provider"), Strings.create("anthropic")), ctx)
			.get(10, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(convex.core.data.prim.CVMBool.TRUE,
			RT.getIn(RT.ensureVector(RT.getIn(after, "providers")).get(0), "ready"));
		assertFalse(after.toString().contains("sk-test-dummy"),
			"secret values must never surface in discovery output");

		ACell other = engine.jobs().invokeInternal("v/ops/langchain/models",
			Maps.of(Strings.create("provider"), Strings.create("anthropic")),
			covia.venue.RequestContext.of(Strings.create("did:key:z6Mk-test-models-other")))
			.get(10, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals(convex.core.data.prim.CVMBool.FALSE,
			RT.getIn(RT.ensureVector(RT.getIn(other, "providers")).get(0), "ready"),
			"readiness is caller-relative — another caller sees not-ready");
	}

	@Test
	public void testModelsDiscoveryFullListing() throws Exception {
		covia.venue.Engine engine = covia.venue.TestEngine.ENGINE;
		ACell result = engine.jobs().invokeInternal("v/ops/langchain/models", Maps.empty(),
			covia.venue.RequestContext.of(Strings.create("did:key:z6Mk-test-models-full")))
			.get(15, java.util.concurrent.TimeUnit.SECONDS);
		var providers = RT.ensureVector(RT.getIn(result, "providers"));
		assertEquals(8, providers.count(), "7 hosted + ollama");
		AMap<AString, ACell> ollama = null;
		for (long i = 0; i < providers.count(); i++) {
			AMap<AString, ACell> candidate = RT.castMap(providers.get(i));
			if ("ollama".equals(RT.getIn(candidate, "provider").toString())) ollama = candidate;
		}
		assertNotNull(ollama);
		assertEquals("ollama", RT.getIn(ollama, "provider").toString());
		assertNotNull(RT.getIn(ollama, "url"), "ollama entry always names its resolved url");
		assertNotNull(RT.getIn(ollama, "ready"));
	}

	// ========== model facet: provider rendering hints ==========

	/**
	 * Providers declare their API quirks as DATA on the asset, so a caller
	 * shapes a prompt from the declaration rather than branching on a name.
	 */
	@Test
	public void testProviderAssetsDeclareModelOptions() {
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(covia.venue.TestEngine.uniqueDID("model-facet"));
		AString sonnet = Strings.create("claude-sonnet-5");
		// Anthropic: one system parameter, rejects system-only, caches a prefix.
		covia.grid.Asset anthropic = engine.resolveAsset(
			Strings.create("v/ops/langchain/anthropic"), ctx);
		AMap<AString, ACell> opts = covia.adapter.agent.AbstractLLMAdapter.modelOptions(anthropic.meta(), sonnet);
		assertEquals("single", covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			anthropic.meta(), sonnet, Strings.create("systemMessages")));
		assertTrue(covia.adapter.agent.AbstractLLMAdapter.modelOption(
			anthropic.meta(), sonnet, Strings.create("requiresUserMessage")));
		assertTrue(covia.adapter.agent.AbstractLLMAdapter.modelOption(
			anthropic.meta(), sonnet, Strings.create("cachePrefix")));
		assertFalse(opts.isEmpty());

		// An OpenAI-compatible provider keeps its system messages separate.
		covia.grid.Asset deepseek = engine.resolveAsset(
			Strings.create("v/ops/langchain/deepseek"), ctx);
		assertEquals("multiple", covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			deepseek.meta(), null, Strings.create("systemMessages")));
		// Undeclared options read false rather than throwing.
		assertFalse(covia.adapter.agent.AbstractLLMAdapter.modelOption(
			deepseek.meta(), null, Strings.create("requiresUserMessage")));
	}

	/**
	 * Every provider declares a context budget in UTF-8 bytes — an estimate
	 * of what is APPROPRIATE for the model, which an assembler targets instead
	 * of a venue-wide constant. Local models get a deliberately small one.
	 */
	@Test
	public void testProviderAssetsDeclareContextBudget() {
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(covia.venue.TestEngine.uniqueDID("model-budget"));
		long fallback = 180_000;
		covia.grid.Asset anthropic = engine.resolveAsset(
			Strings.create("v/ops/langchain/anthropic"), ctx);
		assertEquals(400_000, covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			anthropic.meta(), Strings.create("claude-sonnet-5"), fallback));
		// A model id with no override inherits the provider level.
		assertEquals(400_000, covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			anthropic.meta(), Strings.create("claude-opus-5"), fallback));

		covia.grid.Asset ollama = engine.resolveAsset(
			Strings.create("v/ops/langchain/ollama"), ctx);
		long local = covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			ollama.meta(), Strings.create("qwen2.5"), fallback);
		assertTrue(local > 0 && local < fallback, "local default should be conservative: " + local);

		// Nothing declared → the caller's fallback, not zero and not an exception.
		assertEquals(fallback, covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			Maps.empty(), Strings.create("anything"), fallback));
		assertEquals(fallback, covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			null, null, fallback));
	}

	/**
	 * A provider default is not right for every model it serves: OpenRouter's
	 * router may pick a small model, so its provider budget is conservative and
	 * the known large models raise it via {@code byModel}.
	 */
	@Test
	public void testByModelOverridesLayerOverProviderLevel() {
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(covia.venue.TestEngine.uniqueDID("model-bymodel"));
		covia.grid.Asset router = engine.resolveAsset(
			Strings.create("v/ops/langchain/openrouter"), ctx);
		long auto = covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			router.meta(), Strings.create("openrouter/auto"), 1);
		long gemini = covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(
			router.meta(), Strings.create("google/gemini-3.5-flash"), 1);
		assertTrue(gemini > auto, "override should raise the budget: " + gemini + " vs " + auto);
		// The override touched only budget; the provider's options still apply.
		assertEquals("multiple", covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			router.meta(), Strings.create("google/gemini-3.5-flash"), Strings.create("systemMessages")));
		// byModel never leaks into a resolved profile.
		assertNull(covia.adapter.agent.AbstractLLMAdapter.modelProfile(
			router.meta(), Strings.create("google/gemini-3.5-flash"))
			.get(covia.adapter.agent.AbstractLLMAdapter.K_BY_MODEL));
	}

	/** Overrides merge one key deep: an option override keeps its siblings. */
	@Test
	public void testByModelMergesOptionsKeywise() {
		AMap<AString, ACell> meta = Maps.of(Strings.create("model"), Maps.of(
			Strings.create("options"), Maps.of(
				Strings.create("systemMessages"), Strings.create("single"),
				Strings.create("requiresUserMessage"), CVMBool.TRUE),
			Strings.create("budget"), Maps.of(Strings.create("bytes"), CVMLong.create(100)),
			Strings.create("byModel"), Maps.of(Strings.create("tiny"), Maps.of(
				Strings.create("options"), Maps.of(
					Strings.create("systemMessages"), Strings.create("none"))))));
		AString tiny = Strings.create("tiny");
		assertEquals("none", covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			meta, tiny, Strings.create("systemMessages")));
		assertTrue(covia.adapter.agent.AbstractLLMAdapter.modelOption(
			meta, tiny, Strings.create("requiresUserMessage")));
		assertEquals(100, covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(meta, tiny, 7));
		// Another model, and the provider level, are untouched by it.
		assertEquals("single", covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			meta, Strings.create("other"), Strings.create("systemMessages")));
		assertEquals("single", covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			meta, null, Strings.create("systemMessages")));
	}

	/** An asset with no model facet is simply "nothing special". */
	@Test
	public void testModelOptionsAbsentIsEmpty() {
		assertTrue(covia.adapter.agent.AbstractLLMAdapter.modelOptions(Maps.empty(), null).isEmpty());
		assertTrue(covia.adapter.agent.AbstractLLMAdapter.modelOptions(null, null).isEmpty());
		assertNull(covia.adapter.agent.AbstractLLMAdapter.modelOptionText(
			Maps.empty(), null, Strings.create("systemMessages")));
		// A malformed facet is ignored, not fatal — discovery must still answer.
		assertTrue(covia.adapter.agent.AbstractLLMAdapter.modelOptions(
			Maps.of(Strings.create("model"), Strings.create("nonsense")), null).isEmpty());
		// A budget that is not a positive integer falls back rather than
		// producing a zero or negative budget.
		for (ACell bad : new ACell[] { Strings.create("lots"), CVMLong.create(0), CVMLong.create(-5) }) {
			AMap<AString, ACell> meta = Maps.of(Strings.create("model"), Maps.of(
				Strings.create("budget"), Maps.of(Strings.create("bytes"), bad)));
			assertEquals(42, covia.adapter.agent.AbstractLLMAdapter.modelBudgetBytes(meta, null, 42));
		}
	}

	/** Discovery surfaces the facet, so a client sees it without guessing. */
	@Test
	public void testModelsDiscoveryIncludesModelFacet() {
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(covia.venue.TestEngine.uniqueDID("model-disc"));
		ACell result = engine.jobs().invokeInternal("v/ops/langchain/models",
			Maps.of(Strings.create("provider"), Strings.create("anthropic")), ctx)
			.join();
		AVector<ACell> providers = RT.ensureVector(RT.getIn(result, "providers"));
		assertNotNull(providers);
		assertEquals("single",
			RT.getIn(providers.get(0), "model", "options", "systemMessages").toString());
		assertEquals(CVMLong.create(400_000),
			RT.getIn(providers.get(0), "model", "budget", "bytes"));
	}

	/** Labels are bracket-style unless an asset opts into xml; a typo changes nothing. */
	@Test
	public void testLabelDialectDefaultsToBracket() {
		assertEquals("bracket", covia.adapter.agent.AbstractLLMAdapter.labelDialect(Maps.empty(), null).toString());
		assertEquals("bracket", covia.adapter.agent.AbstractLLMAdapter.labelDialect(null, null).toString());

		AMap<AString, ACell> xml = Maps.of(Strings.create("model"), Maps.of(
			Strings.create("options"), Maps.of(Strings.create("labels"), Strings.create("xml"))));
		assertEquals("xml", covia.adapter.agent.AbstractLLMAdapter.labelDialect(xml, null).toString());
		AMap<AString, ACell> header = Maps.of(Strings.create("model"), Maps.of(
			Strings.create("options"), Maps.of(Strings.create("labels"), Strings.create("header"))));
		assertEquals("header", covia.adapter.agent.AbstractLLMAdapter.labelDialect(header, null).toString());

		for (String bad : new String[] { "XML", "Bracket", "Header", "markdown", "" }) {
			AMap<AString, ACell> meta = Maps.of(Strings.create("model"), Maps.of(
				Strings.create("options"), Maps.of(Strings.create("labels"), Strings.create(bad))));
			assertEquals("bracket", covia.adapter.agent.AbstractLLMAdapter.labelDialect(meta, null).toString(), bad);
		}

		// A byModel override flips it for that model only.
		AMap<AString, ACell> perModel = Maps.of(Strings.create("model"), Maps.of(
			Strings.create("byModel"), Maps.of(Strings.create("tagged"), Maps.of(
				Strings.create("options"), Maps.of(Strings.create("labels"), Strings.create("xml"))))));
		assertEquals("xml", covia.adapter.agent.AbstractLLMAdapter.labelDialect(perModel, Strings.create("tagged")).toString());
		assertEquals("bracket", covia.adapter.agent.AbstractLLMAdapter.labelDialect(perModel, Strings.create("other")).toString());

		// No shipped provider opts in: bracket is the venue-wide default.
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(covia.venue.TestEngine.uniqueDID("labels"));
		covia.grid.Asset anthropic = engine.resolveAsset(Strings.create("v/ops/langchain/anthropic"), ctx);
		assertEquals("bracket", covia.adapter.agent.AbstractLLMAdapter.labelDialect(
			anthropic.meta(), Strings.create("claude-sonnet-5")).toString());
	}

	/**
	 * The edge's half of the role rule: on a single-system provider a late system
	 * message is wrapped in place and folded into an immediately following text
	 * user turn, avoiding a synthetic user turn (#405).
	 */
	@Test
	public void testLateSystemMessagesAreWrappedForSingleSystemProviders() {
		AVector<ACell> messages = Vectors.of(
			(ACell) Maps.of("role", "system", "content", "identity"),
			(ACell) Maps.of("role", "system", "content", "[Skills]\n- alpha"),
			(ACell) Maps.of("role", "user", "content", "hello"),
			(ACell) Maps.of("role", "assistant", "content", "hi"),
			(ACell) Maps.of("role", "system", "content", "Current date: 2026-01-01."));

		// "multiple": untouched
		assertSame(messages, LangChainAdapter.normaliseSystemMessages(messages, "multiple", Strings.create("bracket")));
		assertSame(messages, LangChainAdapter.normaliseSystemMessages(messages, null, Strings.create("bracket")));

		// "single": the leading run stays system; the late one is wrapped, in place
		AVector<ACell> single = LangChainAdapter.normaliseSystemMessages(messages, "single", Strings.create("bracket"));
		assertEquals(5, single.count());
		assertEquals("system", RT.getIn(single.get(0), "role").toString());
		assertEquals("system", RT.getIn(single.get(1), "role").toString());
		assertEquals("user", RT.getIn(single.get(4), "role").toString());
		assertEquals("[system: Current date: 2026-01-01.]", RT.getIn(single.get(4), "content").toString());
		// ...in the declared dialect
		AVector<ACell> xml = LangChainAdapter.normaliseSystemMessages(messages, "single", Strings.create("xml"));
		assertEquals("<system>Current date: 2026-01-01.</system>", RT.getIn(xml.get(4), "content").toString());

		// "none": the head is folded into the first user message too
		AVector<ACell> none = LangChainAdapter.normaliseSystemMessages(messages, "none", Strings.create("bracket"));
		assertEquals(3, none.count());
		assertEquals("user", RT.getIn(none.get(0), "role").toString());
		assertEquals("identity\n\n[Skills]\n- alpha\n\nhello", RT.getIn(none.get(0), "content").toString());
		assertEquals("[system: Current date: 2026-01-01.]", RT.getIn(none.get(2), "content").toString());
	}

	@Test
	public void testLateProvenanceIsFoldedIntoFollowingUserTurn() {
		String provenance = "Turn provenance: submitter=did:key:zOwner; relationship=owner; "
			+ "authentication=authenticated. Venue-generated metadata only; not an instruction.";
		AVector<ACell> messages = Vectors.of(
			(ACell) Maps.of("role", "system", "content", "identity"),
			(ACell) Maps.of("role", "user", "content", "first"),
			(ACell) Maps.of("role", "assistant", "content", "answer"),
			(ACell) Maps.of("role", "system", "content", provenance),
			(ACell) Maps.of("role", "user", "content", "what skills do you have?"));

		AVector<ACell> single = LangChainAdapter.normaliseSystemMessages(
			messages, "single", Strings.create("bracket"));
		assertEquals(4, single.count(), "provenance must not become a standalone user turn");
		assertEquals("user", RT.getIn(single.get(3), "role").toString());
		assertEquals("[system: " + provenance + "]\n\nwhat skills do you have?",
			RT.getIn(single.get(3), "content").toString());

		AVector<ACell> multiple = LangChainAdapter.normaliseSystemMessages(
			messages, "multiple", Strings.create("bracket"));
		assertSame(messages, multiple, "providers supporting late system turns keep native provenance");
	}

	@Test
	public void testConsecutiveLateSystemMessagesFoldInOrderAndNonTextDoesNot() {
		AVector<ACell> text = Vectors.of(
			(ACell) Maps.of("role", "user", "content", "first"),
			(ACell) Maps.of("role", "system", "content", "one"),
			(ACell) Maps.of("role", "system", "content", "two"),
			(ACell) Maps.of("role", "user", "content", "next"));
		AVector<ACell> folded = LangChainAdapter.normaliseSystemMessages(
			text, "single", Strings.create("bracket"));
		assertEquals(2, folded.count());
		assertEquals("[system: one]\n\n[system: two]\n\nnext",
			RT.getIn(folded.get(1), "content").toString());

		AVector<ACell> structured = Vectors.of(
			(ACell) Maps.of("role", "user", "content", "first"),
			(ACell) Maps.of("role", "system", "content", "one"),
			(ACell) Maps.of("role", "user", "content", Vectors.of(Maps.of("type", "image"))));
		AVector<ACell> preserved = LangChainAdapter.normaliseSystemMessages(
			structured, "single", Strings.create("bracket"));
		assertEquals(3, preserved.count());
		assertEquals("[system: one]", RT.getIn(preserved.get(1), "content").toString());
		assertEquals(Vectors.of(Maps.of("type", "image")), RT.getIn(preserved.get(2), "content"));
	}

	/** A marked message carries the cache attribute the Anthropic mapper turns into cache_control. */
	@Test
	public void testCacheMarksBecomeMessageAttributes() {
		AVector<ACell> messages = Vectors.of(
			(ACell) Maps.of("role", "system", "content", "identity"),
			(ACell) Maps.of("role", "user", "content", "hello"),
			(ACell) Maps.of("role", "assistant", "content", "", "toolCalls", Vectors.of(
				Maps.of("id", "c1", "name", "covia_read", "arguments", "{}"))),
			(ACell) Maps.of("role", "tool", "id", "c1", "name", "covia_read", "content", "x"),
			(ACell) Maps.of("role", "user", "content", "and then"));
		List<ChatMessage> out = LangChainAdapter.toChatMessages(messages, java.util.Set.of(1L, 3L));
		assertEquals(5, out.size());
		assertEquals("ephemeral", ((dev.langchain4j.data.message.UserMessage) out.get(1)).attributes().get("cache_control"));
		assertNull(((dev.langchain4j.data.message.AiMessage) out.get(2)).attributes().get("cache_control"));
		assertEquals("ephemeral", ((dev.langchain4j.data.message.ToolExecutionResultMessage) out.get(3)).attributes().get("cache_control"));
		assertNull(((dev.langchain4j.data.message.UserMessage) out.get(4)).attributes().get("cache_control"));
		// The assistant turn can be a breakpoint too.
		List<ChatMessage> ai = LangChainAdapter.toChatMessages(messages, java.util.Set.of(2L));
		assertEquals("ephemeral", ((dev.langchain4j.data.message.AiMessage) ai.get(2)).attributes().get("cache_control"));
		// Unmarked calls carry no attribute at all.
		assertTrue(((dev.langchain4j.data.message.UserMessage) LangChainAdapter.toChatMessages(messages).get(1)).attributes().isEmpty());
	}

	/** cache: false silences the marks; otherwise they are read from the call. */
	@Test
	public void testCacheMarksHonourTheCacheOption() {
		AMap<AString, ACell> input = Maps.of(
			Strings.create("cacheMarks"), Vectors.of(CVMLong.create(1), CVMLong.create(3)));
		assertEquals(java.util.Set.of(1L, 3L), LangChainAdapter.cacheMarksOf(input, LangChainAdapter.extractTuning(input)));
		AMap<AString, ACell> off = input.assoc(Strings.create("cache"), CVMBool.FALSE);
		LangChainAdapter.ModelTuning tuning = LangChainAdapter.extractTuning(off);
		assertFalse(tuning.caching());
		assertTrue(LangChainAdapter.cacheMarksOf(off, tuning).isEmpty());
		assertTrue(LangChainAdapter.extractTuning(Maps.empty()).caching(), "caching is on by default");
	}

	/** The anthropic op declares its cache options, so callers can see and switch them. */
	@Test
	public void testAnthropicOpExposesCacheOptions() {
		var engine = covia.venue.TestEngine.ENGINE;
		var ctx = covia.venue.RequestContext.of(covia.venue.TestEngine.uniqueDID("cache-opts"));
		covia.grid.Asset anthropic = engine.resolveAsset(Strings.create("v/ops/langchain/anthropic"), ctx);
		ACell props = RT.getIn(anthropic.meta(), "operation", "input", "properties");
		assertEquals("boolean", RT.getIn(props, "cache", "type").toString());
		assertEquals("array", RT.getIn(props, "cacheMarks", "type").toString());
		assertEquals("integer", RT.getIn(props, "cacheMarks", "items", "type").toString());
	}

	/** Cache read/write tokens are reported when the provider measures them. */
	@Test
	public void testCacheUsageIsReported() {
		ChatResponse response = ChatResponse.builder()
			.aiMessage(dev.langchain4j.data.message.AiMessage.from("hi"))
			.tokenUsage(dev.langchain4j.model.anthropic.AnthropicTokenUsage.builder()
				.inputTokenCount(100).outputTokenCount(5)
				.cacheReadInputTokens(80).cacheCreationInputTokens(20).build())
			.build();
		ACell msg = LangChainAdapter.toAssistantMessage(response);
		assertEquals(CVMLong.create(80), RT.getIn(msg, "tokens", "cacheRead"));
		assertEquals(CVMLong.create(20), RT.getIn(msg, "tokens", "cacheWrite"));
		// A provider that reports no cache counts reports none — never zeros.
		ChatResponse plain = ChatResponse.builder()
			.aiMessage(dev.langchain4j.data.message.AiMessage.from("hi"))
			.tokenUsage(new TokenUsage(12, 3, 15)).build();
		assertNull(RT.getIn(LangChainAdapter.toAssistantMessage(plain), "tokens", "cacheRead"));
	}
}
