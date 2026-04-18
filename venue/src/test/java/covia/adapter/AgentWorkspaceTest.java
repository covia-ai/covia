package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import org.junit.jupiter.api.TestInfo;

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

	private final Engine engine = TestEngine.ENGINE;
	private AString ALICE_DID;
	private RequestContext ALICE;

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		ALICE = RequestContext.of(ALICE_DID);
	}

	@Test
	public void testAgentWorkspaceAcrossRuns() {
		// Create an LLM agent that uses workspace tools
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "workspace-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of("config", Maps.of(
					"llmOperation", "v/test/ops/workspacellm"))),
			ALICE).awaitResult(5000);

		// Run 1: agent writes knowledge, appends to log, reads back
		AgentState wsAgent = engine.getVenueState().users().get(ALICE_DID)
			.agent("workspace-agent");
		Blob wsSid = Blob.fromHex("55550001555500015555000155550001");
		wsAgent.ensureSession(wsSid, ALICE_DID);
		AString wsSidHex = Strings.create(wsSid.toHexString());
		wsAgent.appendSessionPending(wsSid, Maps.of(
			Strings.intern("content"), Strings.create("Learn about lattice technology")));

		Job run1 = engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "workspace-agent"), ALICE);
		run1.awaitResult(30000);
		TestEngine.awaitTimelineCount(wsAgent, 1, 10000);

		// Verify: agent is sleeping, workspace has data
		AgentState agent = engine.getVenueState().users().get(ALICE_DID)
			.agent("workspace-agent");
		assertEquals(AgentState.SLEEPING, agent.getStatus());
		assertEquals(1, agent.getTimeline().count());

		// Knowledge persisted
		Job readK = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "w/knowledge/topic"), ALICE);
		assertEquals(Strings.create("lattice technology"),
			RT.getIn(readK.awaitResult(5000), "value"));

		// Log has one entry
		Job readLog = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "w/log"), ALICE);
		AVector<ACell> log1 = RT.getIn(readLog.awaitResult(5000), "value");
		assertEquals(1, log1.count());

		// Run 2: same agent, workspace accumulates
		wsAgent.appendSessionPending(wsSid, Maps.of(
			Strings.intern("content"), Strings.create("Continue learning")));

		Job run2 = engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "workspace-agent"), ALICE);
		run2.awaitResult(30000);
		TestEngine.awaitTimelineCount(wsAgent, 2, 10000);

		// Log now has two entries
		Job readLog2 = engine.jobs().invokeOperation("v/ops/covia/read",
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
