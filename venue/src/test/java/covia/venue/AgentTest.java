package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;

/** What an agent answers about itself: its status, why it stopped, and how it runs. */
public class AgentTest {

	private static final AString OPERATION = Strings.create("v/ops/llmagent/chat");

	private AString owner;
	private Agent agent;

	@BeforeEach
	public void setup(TestInfo info) {
		owner = TestEngine.uniqueDID(info);
		agent = TestAgents.ensure(TestEngine.ENGINE.getVenueState().users().ensure(owner),
			Strings.create("readers-agent"), Maps.of(Fields.OPERATION, OPERATION), null);
	}

	@Test
	public void testAStoppedAgentSaysWhoStoppedItAndWhy() {
		assertFalse(agent.isStopped());
		assertNull(agent.suspension());

		agent.suspend(owner, Strings.create("review"));
		assertTrue(agent.isStopped());
		assertEquals(owner, agent.suspension().get(Agent.SUSPENSION_BY));
		assertEquals(Strings.create("review"), agent.suspension().get(Fields.REASON));
		assertEquals(Agent.SUSPENDED, Agent.status(agent.getRecord()), "a record in hand answers as the agent does");

		assertTrue(agent.tryResume());
		assertFalse(agent.isStopped());
		agent.suspend(Strings.create("model unavailable"));
		assertEquals(Strings.create("model unavailable"), Agent.error(agent.getRecord()));
		assertNull(agent.suspension(), "a fault is not a deliberate stop");

		agent.terminate();
		assertTrue(Agent.isStopped(agent.getRecord()));
	}

	@Test
	public void testAnAgentKnowsHowItRuns() {
		assertEquals(OPERATION, agent.transitionOperation());
		assertNull(agent.definition(), "the venue runs this agent itself");
	}

	@Test
	public void testNoRecordAnswersNothing() {
		assertNull(Agent.status(null));
		assertFalse(Agent.isStopped(null));
		assertNull(Agent.suspension(null));
		assertNull(Agent.transitionOperation(null));
	}
}
