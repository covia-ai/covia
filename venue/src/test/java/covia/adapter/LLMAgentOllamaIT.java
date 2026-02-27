package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.User;

/**
 * Integration test: LLMAgentAdapter against local Ollama.
 * Run manually — requires Ollama running on localhost:11434 with qwen3 model.
 */
public class LLMAgentOllamaIT {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	@Test
	public void testOllamaChat() {
		// Create agent with Ollama config
		AMap<AString, ACell> config = Maps.of(
			"provider", Strings.create("ollama"),
			"model", Strings.create("qwen3"),
			"systemPrompt", Strings.create("You are a concise assistant. Reply in one sentence.")
		);
		engine.jobs().invokeOperation(
			Strings.create("agent:create"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("ollama-agent"),
				Fields.CONFIG, config
			),
			ALICE_DID).awaitResult();

		// Send a message
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("ollama-agent"),
				Fields.MESSAGE, Maps.of("content", Strings.create("What is the capital of France?"))
			),
			ALICE_DID).awaitResult();

		// Run with llmagent:chat
		Job runJob = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("ollama-agent"),
				Fields.OPERATION, Strings.create("llmagent:chat")
			),
			ALICE_DID);
		ACell result = runJob.awaitResult();

		assertNotNull(result);
		assertEquals(AgentState.SLEEPING, RT.getIn(result, Fields.STATUS));

		// Check agent state
		User user = engine.getVenueState().users().get(ALICE_DID);
		AgentState agent = user.agent(Strings.create("ollama-agent"));

		// History should have system + user + assistant
		AVector<ACell> history = LLMAgentAdapter.extractHistory(agent.getState());
		assertEquals(3, history.count());

		// Print the response
		AString response = RT.ensureString(RT.getIn(history.get(2), "content"));
		assertNotNull(response);
		System.out.println("=== Ollama response ===");
		System.out.println(response);

		// Timeline should have the result
		AVector<ACell> timeline = agent.getTimeline();
		assertEquals(1, timeline.count());
		AString timelineResponse = RT.ensureString(RT.getIn(timeline.get(0), Fields.RESULT, "response"));
		assertNotNull(timelineResponse);
		System.out.println("=== Timeline result ===");
		System.out.println(timelineResponse);

		// Second turn — multi-turn conversation
		engine.jobs().invokeOperation(
			Strings.create("agent:message"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("ollama-agent"),
				Fields.MESSAGE, Maps.of("content", Strings.create("And what is its population?"))
			),
			ALICE_DID).awaitResult();

		Job run2 = engine.jobs().invokeOperation(
			Strings.create("agent:run"),
			Maps.of(
				Fields.AGENT_ID, Strings.create("ollama-agent"),
				Fields.OPERATION, Strings.create("llmagent:chat")
			),
			ALICE_DID);
		run2.awaitResult();

		// History should now be system + user1 + assistant1 + user2 + assistant2 = 5
		AVector<ACell> history2 = LLMAgentAdapter.extractHistory(agent.getState());
		assertEquals(5, history2.count());

		AString response2 = RT.ensureString(RT.getIn(history2.get(4), "content"));
		assertNotNull(response2);
		System.out.println("=== Turn 2 response ===");
		System.out.println(response2);

		// Should have 2 timeline entries
		assertEquals(2, agent.getTimeline().count());
	}
}
