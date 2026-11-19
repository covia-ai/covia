package covia.adapter;

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
import covia.adapter.agent.GoalTreeContext;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.test.IntegrationTest;
import covia.venue.Agent;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * A provider's refusal fails the chat the agent was answering, with a reason
 * naming the provider, and the agent carries on: the refusal is never stored,
 * and the next turn in the same conversation is answered with a request the
 * provider takes (AGENT_LOOP.md §3.4, MODELS.md §5.3).
 *
 * <p>The agent's model calls go to a local stand-in for Anthropic, which the
 * outbound guard allows only on a venue that lists the loopback host, so the
 * class has a venue of its own.</p>
 */
@IntegrationTest
public class ProviderRefusalAgentTest {
	private static final String SONNET = "claude-sonnet-5-5";
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

	private static String reply(String content, String stopReason, String extra) {
		return "{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + SONNET + "\","
			+ "\"content\":[" + content + "],\"stop_reason\":\"" + stopReason + "\"" + extra + ","
			+ "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
	}

	private static final List<String> REPLIES = List.of(
		reply("{\"type\":\"tool_use\",\"id\":\"toolu_load\",\"name\":\"skill_load\",\"input\":{\"name\":\"alpha\"}}", "tool_use", ""),
		reply("", "refusal", ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"withheld\"}"),
		reply("{\"type\":\"text\",\"text\":\"ok\"}", "end_turn", ""));

	@Test
	public void aRefusalFailsTheChatAndTheAgentCarriesOn() throws Exception {
		List<ACell> requests = new ArrayList<>();
		List<String> broken = new ArrayList<>();
		HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		provider.createContext("/v1/messages", exchange -> {
			ACell body = JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes;
			synchronized (requests) {
				String fault = providerRule(body);
				if (fault != null) broken.add(fault);
				bytes = REPLIES.get(Math.min(requests.size(), REPLIES.size() - 1)).getBytes(StandardCharsets.UTF_8);
				requests.add(body);
			}
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) { out.write(bytes); }
		});
		provider.start();
		try {
			AString owner = TestEngine.uniqueDID("provider-refusal");
			RequestContext ctx = RequestContext.of(owner);
			engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(Fields.PATH, "w/skills/alpha",
				Fields.VALUE, Maps.of(
					"description", "Alpha skill",
					"content", Maps.of("inline", "Use the covia tools on w/probe."),
					"skill", Maps.of("tools", Vectors.of(Strings.create("v/ops/covia/read"))))), ctx).awaitResult(5000);
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "helper",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/goaltree/chat",
					"llmOperation", "v/ops/langchain/anthropic",
					"url", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1/",
					"apiKey", "scripted",
					"model", SONNET,
					"skillsets", Vectors.of(Strings.create("w/skills")),
					"defaultTools", false,
					"caps", JSON.parse("""
						[{"with":"w/skills","can":"crud/read"},{"with":"w/skills","can":"asset/read"},
						 {"with":"v/ops/covia","can":"invoke"}]"""))), ctx).awaitResult(5000);
			Agent agent = engine.getVenueState().users().user(owner).agent("helper");
			Blob session = Blob.fromHex("55550001555500015555000155550001");
			agent.sessions().create(session, owner, null);

			Job refused = chat(ctx, session, "load the alpha skill");
			assertEquals(Status.FAILED, refused.getStatus(), "a refusal is not an answer");
			assertTrue(refused.getErrorMessage().contains(
				ProviderStop.DECLINED.formatted("anthropic", SONNET, " (refusal, category cyber)")), refused.getErrorMessage());
			assertNotEquals(Agent.SUSPENDED, engine.getVenueState().users().user(owner).agent("helper").getStatus(),
				"a refusal fails the chat, not the agent");

			Job next = chat(ctx, session, "and now?");
			assertEquals(Status.COMPLETE, next.getStatus(), next.getErrorMessage());
			assertEquals(3, requests.size(), "load, refusal, next turn");
			assertEquals(List.of(), broken, "every request is one the provider takes");

			AVector<ACell> turns = GoalTreeContext.activeConversation(RT.ensureMap(RT.getIn(
				engine.getVenueState().users().user(owner).agent("helper").session(session).getRecord(),
				Fields.FRAMES, CVMLong.ZERO)));
			for (ACell turn : turns) {
				assertFalse(LangChainAdapter.saysNothing(turn), "the refusal was stored as a turn: " + turns);
			}
		} finally {
			provider.stop(0);
		}
	}

	private static Job chat(RequestContext ctx, Blob session, String message) {
		Job job = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "helper", Fields.SESSION_ID, session, Fields.MESSAGE, message), ctx);
		try {
			job.awaitResult(10_000);
		} catch (JobFailedException failed) {
			// The caller reads the outcome from the job's status.
		}
		return job;
	}

	/** Why Anthropic would reject this request, or null: an empty message, or a
	 *  system message that neither precedes an assistant message nor ends the array. */
	private static String providerRule(ACell request) {
		AVector<ACell> messages = RT.ensureVector(RT.getIn(request, "messages"));
		for (long i = 0; i < messages.count(); i++) {
			AMap<AString, ACell> message = RT.ensureMap(messages.get(i));
			if (RT.count(message.get(Strings.intern("content"))) == 0) return "empty message " + i + ": " + messages;
			if (!"system".equals(RT.getIn(message, "role").toString())) continue;
			long next = i + 1;
			while (next < messages.count() && "system".equals(RT.getIn(messages.get(next), "role").toString())) next++;
			if (next < messages.count() && !"assistant".equals(RT.getIn(messages.get(next), "role").toString())) {
				return "system message " + i + " is followed by " + RT.getIn(messages.get(next), "role") + ": " + messages;
			}
		}
		return null;
	}
}
