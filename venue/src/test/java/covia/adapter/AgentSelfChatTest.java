package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Regression test for nested self-chat during a transition.
 *
 * <p>An agent whose transition calls {@code agent:message} on itself writes
 * to its own {@code session.pending} while the run loop is mid-transition.
 * In the virtual-thread run loop the in-flight transition finishes, the
 * merge lands, and the loop's next iteration picks the fresh session up
 * — no yield state, no race, no re-wake handoff. This test proves that
 * invariant: one trigger, two cycles, both timeline entries present.</p>
 */
public class AgentSelfChatTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	@Test
	public void testAgentMessagesItselfDuringTransition() {
		// Agent uses the test:selfchat level-3 op — on first cycle it
		// invokes agent:message against itself with a new session carrying
		// "FOLLOWUP"; on the next cycle (driven by that pending message)
		// it returns "handled followup".
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "selfchat-agent",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat"),
				AgentState.KEY_STATE, Maps.of("config", Maps.of(
					"llmOperation", "v/test/ops/selfchat"))),
			ctx).awaitResult(5000);

		AgentState agent = engine.getVenueState().users().get(did)
			.agent("selfchat-agent");
		Blob sid = Blob.fromHex("abababababababababababababababab");
		agent.ensureSession(sid, did);
		agent.appendSessionPending(sid, Maps.of(
			Strings.intern("content"), Strings.create("START")));

		Job run = engine.jobs().invokeOperation("v/ops/agent/trigger",
			Maps.of(Fields.AGENT_ID, "selfchat-agent"), ctx);
		run.awaitResult(30000);

		// Two cycles must complete: the initial START cycle (which self-
		// messaged) and the FOLLOWUP cycle kicked by that message.
		TestEngine.awaitTimelineCount(agent, 2, 15000);

		assertEquals(2, agent.getTimeline().count(),
			"Timeline should record both cycles (initial + self-kicked FOLLOWUP)");
		assertEquals(AgentState.SLEEPING, agent.getStatus(),
			"Agent should quiesce once both cycles are done");
	}
}
