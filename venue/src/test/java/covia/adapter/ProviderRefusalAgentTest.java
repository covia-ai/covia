package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
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
 * A model call that gets no answer, a provider's refusal or a response with
 * nothing in it, fails the chat the agent was answering, with a reason naming
 * the provider, and the agent carries on: the empty response is never stored,
 * and the next turn in the same conversation is answered with a request the
 * provider takes (AGENT_LOOP.md §3.4, MODELS.md §5.3).
 *
 * <p>The agent's model calls go to a local stand-in for the provider, which
 * the outbound guard allows only on a venue that lists the loopback host, so
 * the class has a venue of its own.</p>
 */
@IntegrationTest
public class ProviderRefusalAgentTest {
	private static final String SONNET = "claude-sonnet-5-5";
	private static final String GPT = "gpt-test";
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
		HttpServer provider = provider("/v1/messages", REPLIES, requests, broken);
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
					"url", url(provider),
					"apiKey", "scripted",
					"model", SONNET,
					"skillsets", Vectors.of(Strings.create("w/skills")),
					"defaultTools", false,
					"caps", JSON.parse("""
						[{"with":"w/skills","can":"crud/read"},{"with":"w/skills","can":"asset/read"},
						 {"with":"v/ops/covia","can":"invoke"}]"""))), ctx).awaitResult(5000);
			Blob session = session(owner, "55550001555500015555000155550001");

			Job refused = chat(ctx, session, "load the alpha skill");
			assertEquals(Status.FAILED, refused.getStatus(), "a refusal is not an answer");
			assertTrue(refused.getErrorMessage().contains(
				ProviderStop.DECLINED.formatted("anthropic", SONNET, " (refusal, category cyber)")), refused.getErrorMessage());
			assertNotEquals(Agent.SUSPENDED, agent(owner).getStatus(), "a refusal fails the chat, not the agent");

			Job next = chat(ctx, session, "and now?");
			assertEquals(Status.COMPLETE, next.getStatus(), next.getErrorMessage());
			assertEquals(3, requests.size(), "load, refusal, next turn");
			assertEquals(List.of(), broken, "every request is one the provider takes");

			AVector<ACell> turns = GoalTreeContext.activeConversation(RT.ensureMap(RT.getIn(
				agent(owner).session(session).getRecord(), Fields.FRAMES, CVMLong.ZERO)));
			for (ACell turn : turns) {
				assertFalse(LangChainAdapter.saysNothing(turn), "the refusal was stored as a turn: " + turns);
			}
		} finally {
			provider.stop(0);
		}
	}

	@Test
	public void aResponseWithNoChoicesFailsTheChatAndTheAgentCarriesOn() throws Exception {
		List<ACell> requests = new ArrayList<>();
		HttpServer provider = provider("/v1/chat/completions", List.of(
			"{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + GPT + "\",\"choices\":[]}",
			"{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + GPT + "\","
				+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"), requests, null);
		try {
			AString owner = TestEngine.uniqueDID("provider-no-choices");
			RequestContext ctx = RequestContext.of(owner);
			engine.jobs().invokeOperation("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "helper",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/ops/langchain/openai",
					"url", url(provider),
					"apiKey", "scripted",
					"model", GPT,
					"defaultTools", false)), ctx).awaitResult(5000);
			Blob session = session(owner, "55550002555500025555000255550002");

			Job empty = chat(ctx, session, "hello");
			assertEquals(Status.FAILED, empty.getStatus(), "a response with no choices is not an answer");
			assertTrue(empty.getErrorMessage().contains(ProviderStop.NO_ANSWER.formatted("openai", GPT, "")),
				empty.getErrorMessage());
			assertNotEquals(Agent.SUSPENDED, agent(owner).getStatus(), "no answer fails the chat, not the agent");

			Job next = chat(ctx, session, "and now?");
			assertEquals(Status.COMPLETE, next.getStatus(), next.getErrorMessage());
			assertEquals(2, requests.size(), "the empty response, then the next turn");
		} finally {
			provider.stop(0);
		}
	}

	/** A stand-in provider answering each request at {@code path} with the next of
	 *  {@code replies}, repeating the last; with {@code broken}, it records any
	 *  request Anthropic would reject. */
	private static HttpServer provider(String path, List<String> replies, List<ACell> requests, List<String> broken)
			throws IOException {
		HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		provider.createContext(path, exchange -> {
			ACell body = JSON.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes;
			synchronized (requests) {
				String fault = (broken != null) ? anthropicRule(body) : null;
				if (fault != null) broken.add(fault);
				bytes = replies.get(Math.min(requests.size(), replies.size() - 1)).getBytes(StandardCharsets.UTF_8);
				requests.add(body);
			}
			exchange.getResponseHeaders().set("content-type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) { out.write(bytes); }
		});
		provider.start();
		return provider;
	}

	private static String url(HttpServer provider) {
		return "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1/";
	}

	private static Agent agent(AString owner) {
		return engine.getVenueState().users().user(owner).agent("helper");
	}

	private static Blob session(AString owner, String hex) {
		Blob session = Blob.fromHex(hex);
		agent(owner).sessions().create(session, owner, null);
		return session;
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
	private static String anthropicRule(ACell request) {
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
