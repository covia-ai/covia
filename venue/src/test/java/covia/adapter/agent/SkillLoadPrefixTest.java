package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.test.IntegrationTest;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.Ids;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Loading a skill adds to what an agent's provider is sent; it never rewrites the
 * prefix (AGENT_CONTEXT.md §3.1, §3.2.3). Across the load and the turns after it,
 * the tools and the system parameter Anthropic is sent stay equal and earlier
 * messages stay a prefix of later ones, and a new session shares the same tools
 * and system parameter.
 *
 * <p>The agent's model calls go to a local stand-in for Anthropic, which the
 * outbound guard allows only on a venue that lists the loopback host, so the class
 * has a venue of its own.</p>
 */
@IntegrationTest
public class SkillLoadPrefixTest {
	private static final AString CACHE_CONTROL = Strings.intern("cache_control");
	private static Engine engine;

	@BeforeAll
	static void start() {
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("http", Maps.of("allowedHosts", Vectors.of("127.0.0.1")))));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void stop() {
		engine.close();
	}

	private static String reply(String content, String stopReason) {
		return "{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
			+ "\"content\":[" + content + "],\"stop_reason\":\"" + stopReason + "\","
			+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
	}

	private static final String LOAD_SKILL = reply(
		"{\"type\":\"tool_use\",\"id\":\"toolu_load\",\"name\":\"skill_load\",\"input\":{\"name\":\"alpha\"}}", "tool_use");
	private static final String TEXT = reply("{\"type\":\"text\",\"text\":\"ok\"}", "end_turn");

	@Test
	public void loadingASkillAppendsToTheProviderPrefix() throws Exception {
		List<ACell> requests = new ArrayList<>();
		HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		provider.createContext("/v1/messages", exchange -> {
			ACell body = JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes;
			synchronized (requests) {
				requests.add(body);
				bytes = (requests.size() == 1 ? LOAD_SKILL : TEXT).getBytes(StandardCharsets.UTF_8);
			}
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) { out.write(bytes); }
		});
		provider.start();
		try {
			AString owner = TestEngine.uniqueDID("skill-prefix");
			RequestContext ctx = RequestContext.of(owner);
			engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(Fields.PATH, "w/skills/alpha",
				Fields.VALUE, Maps.of(
					"description", "Alpha skill",
					"content", Maps.of("inline", "Use the covia tools on w/probe."),
					"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/read"),
						Strings.create("v/ops/covia/list"))))), ctx).awaitResult(5000);
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "helper",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/goaltree/chat",
					"llmOperation", "v/ops/langchain/anthropic",
					"url", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1/",
					"apiKey", "scripted",
					"model", "claude-sonnet-5-5",
					"systemPrompt", "You help with the probe.",
					"skillsets", Vectors.of(Strings.create("w/skills")),
					"defaultTools", false,
					"tools", Vectors.of(Strings.create("v/ops/covia/inspect")),
					"caps", JSON.parse("""
						[{"with":"w/skills","can":"crud/read"},{"with":"w/skills","can":"asset/read"},
						 {"with":"v/ops/covia","can":"invoke"}]"""))), ctx).awaitResult(5000);

			ACell first = chat(ctx, null, "load the alpha skill");
			Blob session = Ids.stored(RT.getIn(first, Fields.SESSION_ID));
			chat(ctx, session, "and now?");
			chat(ctx, null, "a new conversation");
			assertEquals(4, requests.size(), "load, reply, next turn, new session");

			AMap<AString, ACell> loads = RT.ensureMap(RT.getIn(engine.getVenueState().users().user(owner)
				.agent("helper").session(session).getRecord(), Fields.FRAMES, CVMLong.ZERO, Fields.LOADS));
			assertTrue(Skills.isSkillEntry(loads.get(Strings.create("w/skills/alpha"))), "the skill loaded: " + loads);

			ACell tools = RT.getIn(requests.get(0), "tools");
			ACell system = RT.getIn(requests.get(0), "system");
			for (ACell request : requests) {
				assertEquals(tools, RT.getIn(request, "tools"), "tools are a fixed prefix");
				assertEquals(withoutCacheControl(system), withoutCacheControl(RT.getIn(request, "system")),
					"the system parameter is a fixed prefix");
			}
			for (int i = 0; i < 2; i++) {
				AVector<ACell> earlier = sentBeforeTail(requests.get(i));
				AVector<ACell> later = RT.ensureVector(withoutCacheControl(RT.getIn(requests.get(i + 1), "messages")));
				assertEquals(earlier, later.slice(0, earlier.count()),
					"request " + (i + 1) + " extends request " + i + " rather than rewriting it");
			}
		} finally {
			provider.stop(0);
		}
	}

	private static ACell chat(RequestContext ctx, Blob session, String message) {
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, "helper", Fields.MESSAGE, message);
		if (session != null) input = input.assoc(Fields.SESSION_ID, session);
		return engine.jobs().invokeOperation("v/ops/agent/chat", input, ctx).awaitResult(10_000);
	}

	/** A request's messages without the inference tail, its last message, which
	 *  describes that inference only and is never part of the prefix. */
	private static AVector<ACell> sentBeforeTail(ACell request) {
		AVector<ACell> messages = RT.ensureVector(withoutCacheControl(RT.getIn(request, "messages")));
		return messages.slice(0, messages.count() - 1);
	}

	/** The value as sent without its cache breakpoints, which mark the prefix but are not part of it. */
	private static ACell withoutCacheControl(ACell value) {
		if (value instanceof AMap<?, ?> map) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> out = ((AMap<AString, ACell>) map).dissoc(CACHE_CONTROL);
			for (var e : out.entrySet()) out = out.assoc(e.getKey(), withoutCacheControl(e.getValue()));
			return out;
		}
		if (value instanceof AVector<?> vector) {
			AVector<ACell> out = Vectors.empty();
			for (long i = 0; i < vector.count(); i++) out = out.conj(withoutCacheControl(vector.get(i)));
			return out;
		}
		return value;
	}
}
