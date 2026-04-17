package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;

/**
 * Integration test: LLMAgentAdapter against local Ollama.
 * Skipped automatically when Ollama is not running on localhost:11434.
 */
@Tag("integration")
@EnabledIf("isOllamaAvailable")
public class LLMAgentOllamaIT {

	static boolean isOllamaAvailable() {
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
				.connectTimeout(java.time.Duration.ofSeconds(2)).build();
			java.net.http.HttpResponse<Void> resp = client.send(
				java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create("http://localhost:11434/api/tags"))
					.timeout(java.time.Duration.ofSeconds(2))
					.GET().build(),
				java.net.http.HttpResponse.BodyHandlers.discarding());
			return resp.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	@Test
	public void testOllamaChat() {
		ACell initialState = Maps.of(
			"config", Maps.of(
				"llmOperation", "v/ops/langchain/ollama",
				"model", "qwen3",
				"systemPrompt", "You are a concise assistant. Reply in one sentence."
			)
		);
		engine.jobs().invokeOperation(
			"v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "ollama-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, initialState),
			RequestContext.of(ALICE_DID)).awaitResult(5000);

		// Deliver directly to avoid auto-wake race
		User ollamaUser = engine.getVenueState().users().get(ALICE_DID);
		ollamaUser.agent("ollama-agent").deliverMessage(Maps.of("content", "What is the capital of France?"));

		Job runJob = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "ollama-agent"),
			RequestContext.of(ALICE_DID));
		ACell result = runJob.awaitResult(5000);

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent("ollama-agent");

		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());
		AString timelineResponse = RT.ensureString(RT.getIn(timeline.get(0), Fields.RESULT, "response"));
		assertNotNull(timelineResponse);
		System.out.println("=== Timeline result ===");
		System.out.println(timelineResponse);

		// Second turn
		ollamaUser.agent("ollama-agent").deliverMessage(Maps.of("content", "And what is its population?"));

		Job run2 = engine.jobs().invokeOperation(
			"v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "ollama-agent"),
			RequestContext.of(ALICE_DID));
		run2.awaitResult(5000);

		assertEquals(2, agent.getTimeline().count());
	}
}
