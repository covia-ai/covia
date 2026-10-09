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
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.agent.AbstractLLMAdapter;
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
			var error = assertThrows(IllegalArgumentException.class, () -> ModelCallOptions.resolve(
				Maps.empty(), Maps.of("modelOptions", Maps.of(feature, true))));
			assertEquals(feature + ModelCallOptions.UPSTREAM_REQUIRED, error.getMessage());
		}
		for (String invalid : List.of("{\"cacheTtl\":\"2h\"}", "{\"automaticCaching\":\"true\"}",
				"{\"unknown\":true}", "{\"thinkingPrefixMismatch\":\"ignore\"}")) {
			assertThrows(IllegalArgumentException.class, () -> ModelCallOptions.resolve(
				Maps.empty(), Maps.of("modelOptions", JSON.parse(invalid))));
		}
		for (String raw : List.of("{\"compaction\":{\"type\":\"summarize\"}}",
				"{\"context_management\":{\"edits\":[{\"type\":\"compact_20260112\"}]}}")) {
			var tuning = LangChainAdapter.extractTuning(Maps.of("maxTokens", 100,
				"providerOptions", JSON.parse(raw)));
			var error = assertThrows(IllegalArgumentException.class, () -> LangChainAdapter.buildAnthropicModel(
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
		var nativeMessages = LangChainAdapter.normaliseNativeSystemMessages(messages, Set.of(1L, 4L));
		assertEquals("second", RT.getIn(nativeMessages.messages(), 3L, "content").toString());
		assertEquals("system", RT.getIn(nativeMessages.messages(), 4L, "role").toString());
		assertEquals(Set.of(1L, 3L), nativeMessages.cacheMarks());
		assertEquals("update", RT.getIn(messages, 3L, "content").toString());
		var error = assertThrows(IllegalArgumentException.class,
			() -> LangChainAdapter.normaliseNativeSystemMessages(messages.subVector(0, 4), Set.of()));
		assertEquals(ModelCallOptions.INVALID_SYSTEM_POSITION, error.getMessage());
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
				 "stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":2},
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
}
