package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * End-to-end test for agent workspace interaction.
 *
 * <p>Verifies the integrated story: an LLM agent uses workspace tools
 * (covia:write, covia:read, covia:append) during its tool call loop,
 * and workspace state persists across multiple agent runs. Uses
 * {@code test:workspacellm} which simulates an LLM that calls workspace
 * tools in sequence.</p>
 *
 * <p>Individual workspace operations (deep paths, vector indexing, isolation,
 * slice, maxSize, etc.) are tested in {@link CoviaAdapterTest}.</p>
 */
public class AgentWorkspaceTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final RequestContext ALICE = RequestContext.of(ALICE_DID);

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	@Test
	public void testAgentWorkspaceAcrossRuns() {
		// Create an LLM agent that uses workspace tools
		engine.jobs().invokeOperation("agent:create",
			Maps.of(Fields.AGENT_ID, "workspace-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "llmagent:chat"),
				AgentState.KEY_STATE, Maps.of("config", Maps.of(
					"llmOperation", "test:workspacellm"))),
			ALICE).awaitResult(5000);

		// Run 1: agent writes knowledge, appends to log, reads back
		engine.getVenueState().users().get(ALICE_DID)
			.agent("workspace-agent")
			.deliverMessage(Maps.of("content", "Learn about lattice technology"));

		Job run1 = engine.jobs().invokeOperation("agent:trigger",
			Maps.of(Fields.AGENT_ID, "workspace-agent"), ALICE);
		run1.awaitResult(30000);

		// Verify: agent is sleeping, workspace has data
		AgentState agent = engine.getVenueState().users().get(ALICE_DID)
			.agent("workspace-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(1, agent.getTimeline().count());

		// Knowledge persisted
		Job readK = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/knowledge/topic"), ALICE);
		assertEquals(Strings.create("lattice technology"),
			RT.getIn(readK.awaitResult(5000), "value"));

		// Log has one entry
		Job readLog = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/log"), ALICE);
		AVector<ACell> log1 = RT.getIn(readLog.awaitResult(5000), "value");
		assertEquals(1, log1.count());

		// Run 2: same agent, workspace accumulates
		engine.getVenueState().users().get(ALICE_DID)
			.agent("workspace-agent")
			.deliverMessage(Maps.of("content", "Continue learning"));

		Job run2 = engine.jobs().invokeOperation("agent:trigger",
			Maps.of(Fields.AGENT_ID, "workspace-agent"), ALICE);
		run2.awaitResult(30000);

		// Log now has two entries
		Job readLog2 = engine.jobs().invokeOperation("covia:read",
			Maps.of(Fields.PATH, "w/log"), ALICE);
		AVector<ACell> log2 = RT.getIn(readLog2.awaitResult(5000), "value");
		assertEquals(2, log2.count(), "Log should accumulate across agent runs");

		// Timeline has two entries
		agent = engine.getVenueState().users().get(ALICE_DID)
			.agent("workspace-agent");
		assertEquals(2, agent.getTimeline().count());
		assertEquals(AgentState.SLEEPING, agent.getStatus());
	}
}
