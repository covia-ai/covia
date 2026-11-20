package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.test.IntegrationTest;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/** Actual Anthropic HTTP encoding, driven exclusively by a local scripted provider. */
@IntegrationTest
class RuntimeGuidanceWireTest {

	@Test
	void completePrefixesSurviveToolsFinalAnswersAndSessionReload() throws Exception {
		List<ACell> requests = new ArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.add(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			int n = turn.getAndIncrement();
			String content = n % 2 == 0
				? "{\"type\":\"tool_use\",\"id\":\"call_" + n + "\",\"name\":\"search\",\"input\":{}}"
				: "{\"type\":\"text\",\"text\":\"Final answer\"}";
			byte[] reply = ("{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\","
				+ "\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-" + n + "\"}," + content
				+ "],\"stop_reason\":\"" + (n % 2 == 0 ? "tool_use" : "end_turn")
				+ "\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, reply.length);
			try (var out = exchange.getResponseBody()) { out.write(reply); }
		});
		server.start();
		try {
			var ctx = RequestContext.of(TestEngine.uniqueDID("runtime-prefix-wire"));
			AMap<AString, ACell> frame = GoalTreeContext.appendTurn(GoalTreeContext.createFrame(""),
				Maps.of("role", "user", "content", "Load the skill"));
			AVector<ACell> tools = Vectors.of(Maps.of("name", "search", "description", "Search",
				"parameters", Maps.of("type", "object", "properties", Maps.empty())));
			var spec = new ContextAssembler.Spec(TestEngine.ENGINE, ctx, null,
				Maps.of("systemPrompt", "Stable instructions"), "test-session", null, 0, null,
				tools, null, null, Vectors.of(frame), null, null, true, null, null, null,
				"Return the result.", null);
			for (int n = 0; n < 4; n++) {
				spec = spec.withFrames(Vectors.of(frame));
				frame = GoalTreeContext.applyObservations(frame,
					ContextAssembler.observations(spec, Loads.Snapshot.EMPTY), n);
				var prompt = ContextAssembler.assemble(spec.withFrames(Vectors.of(frame)));
				ACell input = prompt.toL3Input(null).merge(Maps.of("model", "test", "apiKey", "test",
					"url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
					"modelOptions", Maps.of("nativeSystemMessages", true)));
				ACell reply = TestEngine.ENGINE.jobs().invokeOperation("v/ops/langchain/anthropic", input,
					TestEngine.ENGINE.venueContext())
					.awaitResult(10000);
				assertNotNull(RT.getIn(reply, Fields.PROVIDER_STATE), String.valueOf(reply));
				frame = GoalTreeContext.appendTurn(frame, reply);
				if (n % 2 == 0) {
					frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "tool", "name", "search",
						"id", "call_" + n, "content", "Loaded"));
					frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "system", "content", "Loaded instructions " + n));
				} else frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "user", "content", "Follow up"));
				frame = RT.ensureMap(CVMEncoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(frame, true)));
			}
			for (int n = 1; n < requests.size(); n++) {
				ACell before = withoutCache(requests.get(n - 1));
				ACell after = withoutCache(requests.get(n));
				assertEquals(RT.getIn(before, "system"), RT.getIn(after, "system"));
				assertEquals(RT.getIn(before, "tools"), RT.getIn(after, "tools"));
				AVector<ACell> prefix = RT.ensureVector(RT.getIn(before, "messages"));
				AVector<ACell> messages = RT.ensureVector(RT.getIn(after, "messages"));
				assertEquals(prefix, messages.slice(0, prefix.count()), "every prior wire message, including guidance");
				assertTrue(after.toString().contains("sig-" + (n - 1)), "thinking survives final answers too");
			}
		} finally { server.stop(0); }
	}

	private static ACell withoutCache(ACell value) {
		if (value instanceof AMap<?, ?> map) {
			AMap<ACell, ACell> result = Maps.empty();
			for (var e : map.entrySet()) if (!"cache_control".equals(e.getKey().toString()))
				result = result.assoc(e.getKey(), withoutCache(e.getValue()));
			return result;
		}
		if (value instanceof AVector<?> values) {
			AVector<ACell> result = Vectors.empty();
			for (ACell v : values) result = result.conj(withoutCache(v));
			return result;
		}
		return value;
	}
}
