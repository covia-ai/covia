package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.agent.ContextAssembler;
import covia.api.Fields;
import covia.grid.Authority;
import covia.test.IntegrationTest;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/** Real Anthropic encoding against a local scripted provider: no key or paid call. */
@IntegrationTest
public class ReusablePromptPrefixTest {
	@Test
	public void equivalentOwnersAndSessionsShareTheWirePrefix() throws Exception {
		AtomicReference<ACell> captured = new AtomicReference<>();
		HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		provider.createContext("/", exchange -> {
			captured.set(JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			byte[] reply = ("{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test\","
				+ "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
				+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, reply.length);
			try (var out = exchange.getResponseBody()) { out.write(reply); }
		});
		provider.start();
		try {
			var tuning = LangChainAdapter.extractTuning(Maps.of("maxTokens", 256));
			var model = LangChainAdapter.buildAnthropicModel("scripted", "http://127.0.0.1:"
				+ provider.getAddress().getPort() + "/v1/", "test", Duration.ofSeconds(5), tuning);
			AString firstOwner = TestEngine.uniqueDID("prefix-owner-a");
			AString otherOwner = TestEngine.uniqueDID("prefix-owner-b");
			for (String dialect : List.of("bracket", "xml", "header")) {
				List<ACell> requests = new ArrayList<>();
				for (int i = 0; i < 3; i++) {
					AString owner = i < 2 ? firstOwner : otherOwner;
					RequestContext ctx = RequestContext.ofAuthority(Authority.ofAgent(owner, Strings.create("helper")));
					String session = "session-" + i;
					String personal = "personal-message-" + i;
					AVector<ACell> input = Vectors.of(Maps.of(Fields.CALLER, owner, Fields.MESSAGE, personal));
					AVector<ACell> tools = Vectors.of(Maps.of("name", "read_record", "description", "Read a record",
						"parameters", Maps.of("type", "object", "properties", Maps.empty())));
					var spec = new ContextAssembler.Spec(TestEngine.ENGINE, ctx, null,
						Maps.of("systemPrompt", "Shared instructions. ".repeat(300)), session, null, 0,
						Strings.create(dialect), tools, null, null, null, null, input, true, null, null, null, null, null);
					var prompt = ContextAssembler.assemble(spec);
					var normalised = LangChainAdapter.normaliseSystemMessages(prompt.messages(),
						LangChainAdapter.cacheMarksOf(prompt.toL3Input(null), tuning), "single", Strings.create(dialect));
					LangChainAdapter.callModel("anthropic", "test", model, normalised.messages(), prompt.tools(), null,
						normalised.cacheMarks());
					ACell request = captured.get();
					assertNotNull(request);
					String wire = JSON.toString(request);
					assertFalse(wire.contains(owner.toString()), "the model does not need its owner's internal principal");
					String system = JSON.toString(RT.getIn(request, "system"));
					assertFalse(system.contains(session));
					assertFalse(system.contains(personal));
					assertTrue(system.contains("cache_control"), "the identical prefix must have a real wire breakpoint");
					assertTrue(wire.contains(session), "deferred work still has the session handle in the tail");
					assertTrue(wire.contains(personal));
					for (int other = 0; other < 3; other++) {
						if (other != i) assertFalse(wire.contains("personal-message-" + other));
					}
					requests.add(request);
				}
				for (ACell request : requests) {
					assertEquals(RT.getIn(requests.get(0), "tools"), RT.getIn(request, "tools"), dialect);
					assertEquals(RT.getIn(requests.get(0), "system"), RT.getIn(request, "system"), dialect);
				}
			}
		} finally {
			provider.stop(0);
		}
	}
}
