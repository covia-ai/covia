package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.agent.AbstractLLMAdapter;
import covia.adapter.agent.Labels;
import covia.exception.InvalidInputException;
import covia.exception.UnsupportedException;
import covia.venue.TestEngine;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

class ModelCallOptionsTest {

	@Test
	void overridesMergeWithModelDefaultsAndUnsupportedFeaturesFail() {
		AMap<AString, ACell> profile = RT.ensureMap(JSON.parse("""
			{"options":{"systemMessages":"head","nativeSystemMessages":true,"cacheTtl":"1h"}}
			"""));
		var options = ModelCallOptions.resolve(profile, JSON.parse("""
			{"modelOptions":{"nativeSystemMessages":false,"automaticCaching":true}}
			"""));
		assertFalse(options.nativeSystemMessages());
		assertTrue(options.automaticCaching());
		assertEquals("1h", options.cacheTtl());
		for (String feature : List.of("compaction", "preserveThinking", "inlineToolChanges")) {
			var error = assertThrows(UnsupportedException.class, () -> ModelCallOptions.resolve(
				Maps.empty(), Maps.of("modelOptions", Maps.of(feature, true))));
			assertEquals(feature + ModelCallOptions.UPSTREAM_REQUIRED, error.getMessage());
		}
		assertEquals("stop", options.emptyResponse(), "an empty response is a silent reply unless the model says otherwise");
		AMap<AString, ACell> declining = RT.ensureMap(JSON.parse("{\"options\":{\"emptyResponse\":\"content_filter\"}}"));
		assertEquals("content_filter", ModelCallOptions.resolve(declining, Maps.empty()).emptyResponse(),
			"a model's own default applies");
		assertEquals("error", ModelCallOptions.resolve(declining,
			Maps.of("modelOptions", Maps.of("emptyResponse", "error"))).emptyResponse(), "a call overrides it");
		for (String invalid : List.of("{\"cacheTtl\":\"2h\"}", "{\"automaticCaching\":\"true\"}",
				"{\"thinkingPrefixMismatch\":\"ignore\"}", "{\"emptyResponse\":\"refused\"}")) {
			assertThrows(InvalidInputException.class, () -> ModelCallOptions.resolve(
				Maps.empty(), Maps.of("modelOptions", JSON.parse(invalid))));
		}
		assertThrows(InvalidInputException.class, () -> ModelCallOptions.resolve(
			Maps.empty(), Maps.of("modelOptions", JSON.parse("{\"unknown\":true}"))));
		for (String raw : List.of("{\"compaction\":{\"type\":\"summarize\"}}",
				"{\"context_management\":{\"edits\":[{\"type\":\"compact_20260112\"}]}}")) {
			var tuning = LangChainAdapter.extractTuning(Maps.of("maxTokens", 100,
				"providerOptions", JSON.parse(raw)));
			var error = assertThrows(UnsupportedException.class, () -> LangChainAdapter.buildAnthropicModel(
				"key", "https://api.anthropic.com/v1/", "model", Duration.ofSeconds(5), tuning));
			assertEquals("compaction" + ModelCallOptions.UPSTREAM_REQUIRED, error.getMessage());
		}
	}

	@Test
	void standardOptionsFollowTheEffectiveModel() {
		var engine = TestEngine.ENGINE;
		var context = engine.venueContext();
		var preset = engine.resolveAsset(Strings.create("v/models/anthropic/claude-sonnet-5-5"), context);
		var provider = engine.resolveAsset(Strings.create("v/ops/langchain/anthropic"), context);
		for (var asset : List.of(preset, provider)) {
			var selected = AbstractLLMAdapter.resolveModel(engine, asset,
				Strings.create("claude-haiku-5-5"), null, context);
			var options = ModelCallOptions.resolve(selected.executionProfile(), Maps.empty());
			assertTrue(options.nativeSystemMessages());
			assertEquals("drop", options.thinkingPrefixMismatch());
			assertFalse(options.automaticCaching());
		}
		var old = AbstractLLMAdapter.resolveModel(engine, preset,
			Strings.create("claude-haiku-4-5"), null, context);
		assertFalse(ModelCallOptions.resolve(old.executionProfile(), Maps.empty()).nativeSystemMessages());
	}

	@Test
	void lateSystemsKeepTheirRoleAfterTheIncomingTurn() {
		AVector<ACell> messages = RT.ensureVector(JSON.parse("""
			[{"role":"system","content":"head"},{"role":"user","content":"first"},
			 {"role":"assistant","content":"answer"},{"role":"system","content":"update"},
			 {"role":"user","content":"second"}]
			"""));
		var nativeMessages = LangChainAdapter.normaliseNativeSystemMessages(messages, Set.of(1L, 4L), Labels.BRACKET);
		assertEquals("second", RT.getIn(nativeMessages.messages(), 3L, "content").toString());
		assertEquals("system", RT.getIn(nativeMessages.messages(), 4L, "role").toString());
		assertEquals(Set.of(1L, 3L), nativeMessages.cacheMarks());
		assertEquals("update", RT.getIn(messages, 3L, "content").toString());

		// With no turn of the caller's after it, it takes the portable form in place
		var lastAfterAnswer = LangChainAdapter.normaliseNativeSystemMessages(messages.subVector(0, 4), Set.of(), Labels.BRACKET)
			.messages();
		assertEquals(4, lastAfterAnswer.count());
		assertEquals("user", RT.getIn(lastAfterAnswer, 3L, "role").toString());
		assertEquals(Labels.wrapSystem(Labels.BRACKET, "update"), RT.getIn(lastAfterAnswer, 3L, "content").toString());
	}

	/** A tool loop whose last step loaded a skill: the body and the tool notice end it. */
	private static final String SKILL_LOAD_LOOP = """
		[{"role":"system","content":"head"},{"role":"user","content":"question"},
		 {"role":"assistant","content":"","toolCalls":[{"id":"c1","name":"skill_load","arguments":{}}]},
		 {"role":"tool","id":"c1","name":"skill_load","content":"loaded"},
		 {"role":"system","content":"skill body"},{"role":"system","content":"tool notice"}]
		""";

	/**
	 * A mark on a late system message stays on it when it ends the request
	 * where it was placed, for the provider call to carry (#726). A turn of
	 * the caller's after it moves it later, so the mark falls back to the
	 * message before it, never forward onto that turn.
	 */
	@Test
	void aMarkOnALateSystemMessageStaysWhenItEndsTheRequestInPlace() {
		AVector<ACell> loop = RT.ensureVector(JSON.parse(SKILL_LOAD_LOOP));
		assertEquals(Set.of(1L, 5L),
			LangChainAdapter.normaliseNativeSystemMessages(loop, Set.of(1L, 5L), Labels.BRACKET).cacheMarks());

		AVector<ACell> withTurn = loop.conj(JSON.parse("{\"role\":\"user\",\"content\":\"task\"}"));
		var placed = LangChainAdapter.normaliseNativeSystemMessages(withTurn, Set.of(5L), Labels.BRACKET);
		assertEquals("task", RT.getIn(placed.messages(), 4L, "content").toString());
		assertEquals("tool notice", RT.getIn(placed.messages(), 6L, "content").toString());
		assertEquals(Set.of(3L), placed.cacheMarks());

		// Not at the end: the mark goes to the message before the system messages
		AVector<ACell> answered = loop.conj(JSON.parse("{\"role\":\"assistant\",\"content\":\"answer\"}"));
		assertEquals(Set.of(3L),
			LangChainAdapter.normaliseNativeSystemMessages(answered, Set.of(5L), Labels.BRACKET).cacheMarks());
	}

	/**
	 * Anthropic takes no cache marker on an inline system message through
	 * LangChain4j, so a request ending in a marked one uses the automatic
	 * breakpoint, which lands on its last block, in place of that explicit
	 * mark: the request still holds at most four breakpoints (#726).
	 */
	@Test
	void aRequestEndingInAMarkedSystemMessageCachesItAutomatically() throws Exception {
		AtomicReference<ACell> request = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			request.set(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			byte[] response = """
				{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-5-5",
				 "content":[{"type":"text","text":"done"}],"stop_reason":"end_turn",
				 "usage":{"input_tokens":10,"output_tokens":2}}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
			ACell input = JSON.parse("""
				{"maxTokens":1024,"modelOptions":{"cacheTtl":"5m","nativeSystemMessages":true}}
				""");
			var tuning = LangChainAdapter.extractTuning(input);
			var model = LangChainAdapter.buildAnthropicModel("key", url, "claude-sonnet-5-5", Duration.ofSeconds(5), tuning);
			AVector<ACell> tools = RT.ensureVector(JSON.parse("[{\"name\":\"lookup\",\"description\":\"Look up data\"}]"));
			AVector<ACell> loop = RT.ensureVector(JSON.parse(SKILL_LOAD_LOOP));

			var ending = LangChainAdapter.normaliseNativeSystemMessages(loop, Set.of(1L, 5L), Labels.BRACKET);
			LangChainAdapter.callModel("anthropic", "claude-sonnet-5-5", model, ending.messages(), tools, null,
				ending.cacheMarks());
			ACell wire = request.get();
			assertEquals("ephemeral", RT.getIn(wire, "cache_control", "type").toString(), wire.toString());
			assertNotNull(RT.getIn(wire, "messages", 0L, "content", 0L, "cache_control"), "the conversation start keeps its mark");
			assertNull(RT.getIn(wire, "messages", 2L, "content", 0L, "cache_control"),
				"the tool result is not marked in place of the skill body");
			assertEquals("system", RT.getIn(wire, "messages", 4L, "role").toString());
			assertEquals(4, breakpoints(wire), wire.toString());

			// A request ending in a marked turn of the conversation keeps explicit marks only
			AVector<ACell> toResult = loop.subVector(0, 4);
			LangChainAdapter.callModel("anthropic", "claude-sonnet-5-5", model, toResult, tools, null, Set.of(1L, 3L));
			assertNull(RT.getIn(request.get(), "cache_control"), request.get().toString());
			assertNotNull(RT.getIn(request.get(), "messages", 2L, "content", 0L, "cache_control"));
		} finally {
			server.stop(0);
		}
	}

	/** Cache breakpoints in a request as sent: every {@code cache_control} field. */
	private static int breakpoints(ACell wire) {
		String json = JSON.print(wire).toString();
		int count = 0;
		for (int i = json.indexOf("\"cache_control\""); i >= 0; i = json.indexOf("\"cache_control\"", i + 1)) count++;
		return count;
	}

	/** A turn after a reply that said nothing: that reply is never sent, so the
	 *  instructions placed before it must land where the API takes a system message. */
	@Test
	void lateSystemsBeforeASilentReplyLandWhereTheProviderTakesThem() throws Exception {
		// As an agent's session holds it: a skill loaded, its instructions and the
		// tool change as system events, then a reply with no text and no tool call.
		AVector<ACell> wire = sentNatively("""
			[{"role":"system","content":"head"},
			 {"role":"user","content":"first"},
			 {"role":"assistant","toolCalls":[{"id":"toolu_load","name":"skill_load","arguments":{"name":"alpha"}}]},
			 {"role":"tool","id":"toolu_load","name":"skill_load","content":"loaded"},
			 {"role":"system","content":"skill instructions"},
			 {"role":"system","content":"tool change"},
			 {"role":"assistant","finishReason":"stop"},
			 {"role":"user","content":"second"}]
			""");
		assertTrue(texts(wire).containsAll(List.of("skill instructions", "tool change", "second")),
			"nothing said is dropped: " + wire);
	}

	/**
	 * After an agent compacts, its summary is its own turn and the context it
	 * pins comes back as system events after it, before its next turn: with no
	 * turn of the caller's for them to follow, they reach Anthropic in the
	 * portable form, in place, and the request is one it takes.
	 */
	@Test
	void lateSystemsBetweenTwoAgentTurnsTakeThePortableForm() throws Exception {
		AVector<ACell> wire = sentNatively("""
			[{"role":"system","content":"head"},
			 {"role":"user","content":"first"},
			 {"role":"assistant","content":"[compacted turns 0-16] summary"},
			 {"role":"system","content":"pinned one"},
			 {"role":"system","content":"pinned two"},
			 {"role":"assistant","toolCalls":[{"id":"toolu_read","name":"read","arguments":{}}]},
			 {"role":"tool","id":"toolu_read","name":"read","content":"read result"}]
			""");
		String pinned = Labels.wrapSystem(Labels.BRACKET, "pinned one") + "\n\n" + Labels.wrapSystem(Labels.BRACKET, "pinned two");
		assertTrue(texts(wire).contains(pinned), "the pinned context is sent in place, in the portable form: " + wire);
	}

	/**
	 * Sends {@code json} messages to a stand-in Anthropic with native system
	 * messages on, and returns the messages it received, checked against
	 * Anthropic's rules: no empty message, and each run of system messages
	 * after a user message and before an assistant message or the end.
	 */
	private static AVector<ACell> sentNatively(String json) throws Exception {
		AtomicReference<ACell> request = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			request.set(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			byte[] response = """
				{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-5-5",
				 "content":[{"type":"text","text":"ok"}],
				 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":2}}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
		});
		server.start();
		try {
			ACell input = Maps.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
				"apiKey", "scripted", "model", "claude-sonnet-5-5", "maxTokens", 256,
				"modelOptions", Maps.of("nativeSystemMessages", true), "messages", RT.ensureVector(JSON.parse(json)));
			TestEngine.ENGINE.jobs().invokeOperation("v/ops/langchain/anthropic", input,
				TestEngine.ENGINE.venueContext()).awaitResult(10_000);
		} finally {
			server.stop(0);
		}
		AVector<ACell> wire = RT.ensureVector(RT.getIn(request.get(), "messages"));
		for (long i = 0; i < wire.count(); i++) {
			assertNotEquals(0L, RT.count(RT.getIn(wire.get(i), "content")), "an empty message was sent: " + wire);
			if (!"system".equals(role(wire, i))) continue;
			assertTrue(i > 0 && ("user".equals(role(wire, i - 1)) || "system".equals(role(wire, i - 1))),
				"a system message must follow a user message: " + wire);
			long next = i + 1;
			while (next < wire.count() && "system".equals(role(wire, next))) next++;
			assertTrue(next == wire.count() || "assistant".equals(role(wire, next)),
				"a system message must precede an assistant message or end the array: " + wire);
		}
		return wire;
	}

	private static String role(AVector<ACell> wire, long i) {
		return RT.getIn(wire.get(i), "role").toString();
	}

	/** The first text block of each message sent. */
	private static List<String> texts(AVector<ACell> wire) {
		List<String> texts = new java.util.ArrayList<>();
		for (ACell message : wire) texts.add(String.valueOf((Object) RT.getIn(message, "content", 0L, "text")));
		return texts;
	}

	/** A cache mark on a turn that is not sent ends the same prefix one message earlier. */
	@Test
	void aMarkOnATurnNotSentMovesToTheMessageBefore() {
		AVector<ACell> messages = RT.ensureVector(JSON.parse("""
			[{"role":"user","content":"first"},{"role":"assistant","content":""},{"role":"user","content":"second"}]
			"""));
		var sent = LangChainAdapter.withoutSilentTurns(messages, Set.of(1L, 2L));
		assertEquals(Vectors.of(messages.get(0), messages.get(2)), sent.messages());
		assertEquals(Set.of(0L, 1L), sent.cacheMarks());
	}

	@Test
	void sdkMapsCachingSystemsBindingAndSignedToolReplay() throws Exception {
		AtomicReference<ACell> request = new AtomicReference<>();
		AtomicReference<String> beta = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			request.set(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
			byte[] response = """
				{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-5-5",
				 "content":[{"type":"thinking","thinking":"","signature":"opaque-signed-state"},
				            {"type":"tool_use","id":"call_1","name":"lookup","input":{}}],
				 "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":2,
				 "cache_creation_input_tokens":12,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":12}},
				 "input_transformations":[{"type":"thinking_dropped"}]}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
			ACell input = JSON.parse("""
				{"maxTokens":8192,"cacheMarks":[1],"modelOptions":{
				 "cacheTtl":"1h","nativeSystemMessages":true,"thinkingPrefixMismatch":"drop"},
				 "providerOptions":{"output_config":{"effort":"low"}}}
				""");
			var tuning = LangChainAdapter.extractTuning(input);
			var model = LangChainAdapter.buildAnthropicModel("key", url, "claude-sonnet-5-5", Duration.ofSeconds(5), tuning);
			AVector<ACell> canonical = RT.ensureVector(JSON.parse("""
				[{"role":"system","content":"head"},{"role":"user","content":"question"},
				 {"role":"system","content":"late instruction"}]
				"""));
			var messages = LangChainAdapter.toChatMessages(canonical, LangChainAdapter.cacheMarksOf(input, tuning));
			var tool = ToolSpecification.builder().name("lookup").description("Look up data").build();
			var response = model.chat(ChatRequest.builder().messages(messages).toolSpecifications(tool).build());
			ACell wire = request.get();
			assertEquals("1h", RT.getIn(wire, "system", 0L, "cache_control", "ttl").toString());
			assertEquals("1h", RT.getIn(wire, "tools", 0L, "cache_control", "ttl").toString());
			assertEquals("1h", RT.getIn(wire, "messages", 0L, "content", 0L, "cache_control", "ttl").toString());
			assertEquals("system", RT.getIn(wire, "messages", 1L, "role").toString());
			assertEquals("drop_block", RT.getIn(wire, "thinking", "block_binding", "prefix_mismatch_behavior").toString());
			assertEquals("low", RT.getIn(wire, "output_config", "effort").toString());
			assertTrue(beta.get().contains(ModelCallOptions.BINDING_BETA));

			ACell stored = LangChainAdapter.toAssistantMessage(response, "anthropic", "claude-sonnet-5-5");
			assertNotNull(RT.getIn(stored, "inputTransformations"));
			assertEquals(0L, RT.ensureLong(RT.getIn(stored, "tokens", "cacheWrite5m")).longValue());
			assertEquals(12L, RT.ensureLong(RT.getIn(stored, "tokens", "cacheWrite1h")).longValue());
			assertEquals(12L, RT.ensureLong(RT.getIn(stored, "tokens", "cacheWrite")).longValue());
			var replay = LangChainAdapter.toChatMessages(canonical.conj(stored), Set.of(), "anthropic", "claude-sonnet-5-5");
			replay.add(ToolExecutionResultMessage.from("call_1", "lookup", "found"));
			model.chat(ChatRequest.builder().messages(replay).toolSpecifications(tool).build());
			assertEquals("", RT.getIn(request.get(), "messages", 2L, "content", 0L, "thinking").toString());
			assertEquals("opaque-signed-state", RT.getIn(request.get(), "messages", 2L, "content", 0L, "signature").toString());

			for (boolean cache : List.of(true, false)) {
				ACell automaticInput = Maps.of("maxTokens", 100, "cache", cache, "cacheMarks", JSON.parse("[0]"),
					"modelOptions", Maps.of("automaticCaching", true, "cacheTtl", "1h"));
				var automatic = LangChainAdapter.extractTuning(automaticInput);
				assertTrue(LangChainAdapter.cacheMarksOf(automaticInput, automatic).isEmpty());
				LangChainAdapter.buildAnthropicModel("key", url, "claude-sonnet-5-5", Duration.ofSeconds(5), automatic)
					.chat(ChatRequest.builder().messages(LangChainAdapter.toChatMessages(canonical,
						LangChainAdapter.cacheMarksOf(automaticInput, automatic))).toolSpecifications(tool).build());
				if (cache) assertEquals("1h", RT.getIn(request.get(), "cache_control", "ttl").toString());
				else assertFalse(request.get().toString().contains("cache_control"));
			}
		} finally {
			server.stop(0);
		}
	}

	@Test
	void thinkingIsBoundOnlyWhenAdaptive() throws Exception {
		// Anthropic refuses block_binding beside a non-adaptive thinking type
		// (Sonnet 5.5's between_tools, Haiku 5.5's disabled): such a setting goes
		// out as the caller wrote it, without the binding or its beta.
		AtomicReference<ACell> request = new AtomicReference<>();
		AtomicReference<String> beta = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", exchange -> {
			request.set(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
			byte[] response = """
				{"id":"msg","type":"message","role":"assistant","model":"claude-sonnet-5-5",
				 "content":[{"type":"text","text":"ok"}],
				 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":2}}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
			var messages = LangChainAdapter.toChatMessages(RT.ensureVector(JSON.parse(
				"[{\"role\":\"user\",\"content\":\"question\"}]")), Set.of());
			AMap<AString, ACell> profile = Maps.of("options", Maps.of("thinkingPrefixMismatch", "drop"));
			for (String policy : List.of("drop", "error", "provider")) {
				for (String type : List.of("between_tools", "disabled", "adaptive", "enabled")) {
					beta.set(null);
					AMap<AString, ACell> configuredThinking = Maps.of("type", type);
					if ("enabled".equals(type)) configuredThinking = configuredThinking.assoc(Strings.intern("budget_tokens"), RT.cvm(1024));
					AMap<AString, ACell> config = Maps.of("maxTokens", 2048, "cache", false,
						"modelOptions", Maps.of("thinkingPrefixMismatch", policy),
						"providerOptions", Maps.of("thinking", configuredThinking, "output_config", Maps.of("effort", "low")));
					ACell input = AbstractLLMAdapter.buildL3Input(config,
						convex.core.data.Vectors.of(Maps.of("role", "user", "content", "question")), null);
					String model = switch (type) {
						case "disabled" -> "claude-haiku-5-5";
						case "enabled" -> "claude-haiku-4-5";
						default -> "claude-sonnet-5-5";
					};
					LangChainAdapter.buildAnthropicModel("key", url, model, Duration.ofSeconds(5),
						LangChainAdapter.extractTuning(input, profile)).chat(ChatRequest.builder().messages(messages).build());
					ACell thinking = RT.getIn(request.get(), "thinking");
					assertEquals(type, RT.getIn(thinking, "type").toString());
					boolean bound = "adaptive".equals(type) && !"provider".equals(policy);
					assertEquals(bound, RT.getIn(thinking, "block_binding") != null, type);
					assertEquals(bound, beta.get() != null && beta.get().contains(ModelCallOptions.BINDING_BETA), type);
					if (bound) assertEquals("drop".equals(policy) ? "drop_block" : "error",
						RT.getIn(thinking, "block_binding", "prefix_mismatch_behavior").toString());
					else assertEquals(configuredThinking, thinking);
					assertEquals(RT.cvm(2048), RT.getIn(request.get(), "max_tokens"));
					assertEquals(Strings.create("low"), RT.getIn(request.get(), "output_config", "effort"));
					assertFalse(request.get().toString().contains("cache_control"));
					assertEquals(config.get(Strings.intern("providerOptions")), RT.getIn(input, "providerOptions"));
				}
			}
			// An existing agent may have set a native binding policy before model
			// defaults gained a drop policy. Preserve its explicit error setting.
			AMap<AString, ACell> explicitThinking = Maps.of("type", "adaptive", "display", "omitted",
				"block_binding", Maps.of("prefix_mismatch_behavior", "error"));
			AMap<AString, ACell> configured = Maps.of("maxTokens", 2048,
				"providerOptions", Maps.of("thinking", explicitThinking));
			ACell input = AbstractLLMAdapter.buildL3Input(configured,
				convex.core.data.Vectors.of(Maps.of("role", "user", "content", "question")), null);
			LangChainAdapter.buildAnthropicModel("key", url, "claude-sonnet-5-5", Duration.ofSeconds(5),
				LangChainAdapter.extractTuning(input, profile)).chat(ChatRequest.builder().messages(messages).build());
			assertEquals(explicitThinking, RT.getIn(request.get(), "thinking"));
			assertTrue(beta.get().contains(ModelCallOptions.BINDING_BETA));
		} finally {
			server.stop(0);
		}
	}
}
