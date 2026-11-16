package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;

/** A user's agents: making, finding, deleting and removing them. */
public class AgentsTest {

	private static final AString AGENT = Strings.intern("agents-agent");
	private static final AString FORK = Strings.intern("agents-fork");
	private static final AMap<AString, ACell> CONFIG = Maps.of("note", "configured");

	private User user;
	private Agents agents;

	@BeforeEach
	public void setup(TestInfo info) {
		user = TestEngine.ENGINE.getVenueState().users().ensure(TestEngine.uniqueDID(info));
		agents = user.agents();
	}

	@Test
	public void aUserWithNoAgentsHasNone() {
		assertTrue(agents.all().isEmpty());
		assertNull(agents.agent(AGENT));
		Agent missing = agents.at(AGENT);
		assertNotNull(missing, "an agent a path names is there to address, record or not");
		assertNull(missing.getRecord());
	}

	@Test
	public void anIdIsCreatedOnce() {
		Agent created = agents.create(AGENT, CONFIG, Strings.create("state"), null, null);
		assertNotNull(created);
		assertEquals(CONFIG, created.getConfig());
		assertEquals(Strings.create("state"), agents.agent(AGENT).getState());
		assertTrue(agents.all().containsKey(AGENT));

		assertNull(agents.create(AGENT, Maps.empty(), null, null, null), "the id is taken");
		assertEquals(CONFIG, agents.agent(AGENT).getConfig(), "a refused create changes nothing");
	}

	@Test
	public void aDeletedAgentIsStillFoundAndKeepsItsId() {
		agents.create(AGENT, CONFIG, null, null, null);
		assertFalse(agents.agent(AGENT).isTerminated());

		agents.agent(AGENT).terminate();
		Agent deleted = agents.agent(AGENT);
		assertNotNull(deleted, "a deleted agent keeps its record");
		assertTrue(deleted.isTerminated());
		assertEquals(CONFIG, deleted.getConfig());
		assertNull(agents.create(AGENT, Maps.empty(), null, null, null), "a deleted agent keeps its id taken");
	}

	@Test
	public void removingAnAgentFreesItsId() {
		agents.create(AGENT, CONFIG, null, null, null);
		agents.agent(AGENT).terminate();

		agents.remove(AGENT);
		assertNull(agents.agent(AGENT));
		assertFalse(agents.all().containsKey(AGENT), "the key itself goes, not just its record");
		assertNotNull(agents.create(AGENT, Maps.empty(), null, null, null), "the id can be used again");
	}

	@Test
	public void removingFromAUserWithNoAgentsMakesNone() {
		agents.remove(AGENT);
		assertNull(user.cursor().path(covia.lattice.Namespace.G).getOrDefault(null), "no empty g is written");
	}

	@Test
	public void aForkStartsFromTheConfigStateAndTimelineGiven() {
		agents.create(AGENT, CONFIG, null, null, null);
		var timeline = Vectors.<ACell>of(Maps.of("turn", 1));

		Agent fork = agents.fork(FORK, CONFIG, Strings.create("copied"), timeline, null);
		assertNotNull(fork);
		assertEquals(CONFIG, fork.getConfig());
		assertEquals(Strings.create("copied"), fork.getState());
		assertEquals(timeline, fork.getTimeline());
		assertEquals(Agent.SLEEPING, fork.getStatus());

		assertNull(agents.fork(AGENT, CONFIG, null, null, null), "a fork cannot take an id in use");
	}
}
