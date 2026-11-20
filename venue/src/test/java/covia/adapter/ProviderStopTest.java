package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.TestEngine;

/**
 * Why a provider's response ended, read at the provider edge: a reply carries
 * OpenTelemetry's finish reason and the provider's own value, and a response
 * that holds no answer fails the call as no answer, not as the provider
 * failing (MODELS.md §5.3).
 */
class ProviderStopTest {

	private static final String SONNET = "claude-sonnet-5-5";
	private static final String GPT = "gpt-test";
	private static final String FREE_TEXT = "PROVIDER_FREE_TEXT";

	private HttpServer server;
	private final AtomicReference<String> response = new AtomicReference<>();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		for (String path : new String[] {"/v1/messages", "/v1/chat/completions"}) {
			server.createContext(path, exchange -> {
				exchange.getRequestBody().readAllBytes();
				byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("content-type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (var out = exchange.getResponseBody()) { out.write(body); }
			});
		}
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private static String anthropic(String content, String stopReason, String extra) {
		return """
			{"id":"msg","type":"message","role":"assistant","model":"%s","content":%s,
			 "stop_reason":"%s"%s,"usage":{"input_tokens":10,"output_tokens":2}}
			""".formatted(SONNET, content, stopReason, extra);
	}

	private static String openAi(String content, String finishReason) {
		return """
			{"id":"c","object":"chat.completion","created":1,"model":"%s",
			 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"%s"}],
			 "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
			""".formatted(GPT, content, finishReason);
	}

	private Job call(String provider, String model, AMap<AString, ACell> modelOptions) {
		AMap<AString, ACell> input = Maps.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
			"apiKey", "scripted", "model", model, "maxTokens", 256,
			"messages", Vectors.of(Maps.of("role", "user", "content", "question")));
		if (modelOptions != null) input = input.assoc(Strings.intern("modelOptions"), modelOptions);
		Job job = TestEngine.ENGINE.jobs().invokeOperation("v/ops/langchain/" + provider, input,
			TestEngine.ENGINE.venueContext());
		try {
			job.awaitResult(10_000);
		} catch (JobFailedException failed) {
			// The caller reads the outcome from the job's status.
		}
		return job;
	}

	private ACell reply(String provider, String model, String body) {
		response.set(body);
		Job job = call(provider, model, null);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		return job.getOutput();
	}

	private String failure(String provider, String model, String body, AMap<AString, ACell> modelOptions) {
		response.set(body);
		Job job = call(provider, model, modelOptions);
		assertEquals(Status.FAILED, job.getStatus(), "a response with no answer is not a reply");
		return job.getErrorMessage();
	}

	@Test
	void aReplyCarriesTheFinishReasonAndTheProvidersOwnValue() {
		ACell text = reply("anthropic", SONNET, anthropic("[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn", ""));
		assertEquals(ProviderStop.STOP, RT.getIn(text, Fields.FINISH_REASON));
		assertEquals(Strings.create("end_turn"), RT.getIn(text, Fields.PROVIDER_FINISH_REASON));

		ACell tool = reply("anthropic", SONNET, anthropic(
			"[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"lookup\",\"input\":{}}]", "tool_use", ""));
		assertEquals(ProviderStop.TOOL_CALL, RT.getIn(tool, Fields.FINISH_REASON));

		ACell cut = reply("anthropic", SONNET, anthropic("[{\"type\":\"text\",\"text\":\"partial\"}]", "max_tokens", ""));
		assertEquals(ProviderStop.LENGTH, RT.getIn(cut, Fields.FINISH_REASON));

		ACell openAi = reply("openai", GPT, openAi("\"ok\"", "stop"));
		assertEquals(ProviderStop.STOP, RT.getIn(openAi, Fields.FINISH_REASON));
		assertEquals(Strings.create("stop"), RT.getIn(openAi, Fields.PROVIDER_FINISH_REASON));

		ACell unknown = reply("openai", GPT, openAi("\"ok\"", "SAFETY_REVIEWED"));
		assertNull(RT.getIn(unknown, Fields.FINISH_REASON), "a value with no counterpart has no finish reason");
		assertEquals(Strings.create("SAFETY_REVIEWED"), RT.getIn(unknown, Fields.PROVIDER_FINISH_REASON));
	}

	@Test
	void aRefusalFailsTheCallWithItsReasonNotItsText() {
		String details = ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"" + FREE_TEXT + "\"}";
		String declined = ProviderStop.DECLINED.formatted("anthropic", SONNET, " (refusal, category cyber)");
		assertEquals(declined, failure("anthropic", SONNET, anthropic("[]", "refusal", details), null));
		assertEquals(declined, failure("anthropic", SONNET,
			anthropic("[{\"type\":\"text\",\"text\":\"partial\"}]", "refusal", details), null),
			"partial output before a refusal is not an answer");

		assertEquals(ProviderStop.DECLINED.formatted("openai", GPT, " (content_filter)"),
			failure("openai", GPT, openAi("\"partial\"", "content_filter"), null));
		assertEquals(ProviderStop.NO_ANSWER.formatted("openai", GPT, " (error)"),
			failure("openai", GPT, openAi("null", "error"), null));
	}

	@Test
	void aResponseWithNoChoicesHoldsNoAnswer() {
		String noAnswer = ProviderStop.NO_ANSWER.formatted("openai", GPT, "");
		assertEquals(noAnswer, failure("openai", GPT,
			"{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + GPT + "\",\"choices\":[]}", null),
			"not the provider failing, which would stop an agent");
		assertEquals(noAnswer, failure("openai", GPT, "{\"error\":{\"message\":\"" + FREE_TEXT + "\",\"code\":502}}", null),
			"a successful response holding only an error has no answer either");
	}

	@Test
	void anEmptyResponseIsReadAsItsModelSays() {
		String empty = anthropic("[]", "end_turn", "");
		ACell silent = reply("anthropic", SONNET, empty);
		assertTrue(LangChainAdapter.saysNothing(silent), "by default an empty response is a silent reply: " + silent);
		assertEquals(ProviderStop.STOP, RT.getIn(silent, Fields.FINISH_REASON));

		assertEquals(ProviderStop.DECLINED.formatted("anthropic", SONNET, " (end_turn, no content)"),
			failure("anthropic", SONNET, empty, Maps.of("emptyResponse", "content_filter")));
		assertEquals(ProviderStop.NO_ANSWER.formatted("anthropic", SONNET, " (end_turn, no content)"),
			failure("anthropic", SONNET, empty, Maps.of("emptyResponse", "error")));
		assertEquals(ProviderStop.NO_ANSWER.formatted("openai", GPT, " (stop, no content)"),
			failure("openai", GPT, openAi("\"\"", "stop"), Maps.of("emptyResponse", "error")));
	}
}
