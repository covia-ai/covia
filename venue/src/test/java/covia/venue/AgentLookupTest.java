package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.grid.Principals;

/**
 * The engine's lookups of an agent that can act, by owner and id, by the
 * agent's DID and by a reference read against a request.
 */
public class AgentLookupTest {

	private static final AString AGENT = Strings.intern("lookup-agent");
	private static final Engine ENGINE = TestEngine.ENGINE;

	private AString owner;
	private AString agentDID;

	@BeforeEach
	public void setup(TestInfo info) {
		owner = TestEngine.uniqueDID(info);
		ENGINE.getVenueState().users().ensure(owner).agents().create(AGENT, Maps.empty(), null, null, null);
		agentDID = Principals.agentDID(owner, AGENT);
	}

	@Test
	public void everyFormFindsTheSameAgent() {
		Agent byId = ENGINE.getAgent(owner, AGENT);
		assertNotNull(byId);
		assertEquals(AGENT, ENGINE.getAgent(agentDID).getAgentId());
		RequestContext ctx = RequestContext.of(owner);
		assertEquals(AGENT, ENGINE.getAgent(ctx, AGENT).getAgentId(), "a bare id names the caller's agent");
		assertEquals(AGENT, ENGINE.getAgent(ctx, agentDID).getAgentId());
		assertEquals(AGENT, ENGINE.getAgent(ctx, Strings.create(owner + "/g/" + AGENT)).getAgentId());
	}

	@Test
	public void anotherUsersAgentResolvesWithoutAnyAccessCheck(TestInfo info) {
		RequestContext stranger = RequestContext.of(TestEngine.uniqueDID(info.getDisplayName() + "-stranger"));
		assertNotNull(ENGINE.getAgent(stranger, agentDID), "resolving names the agent; using it is checked where it is used");
		assertNull(ENGINE.getAgent(stranger, AGENT), "a bare id names the stranger's own agent, which does not exist");
	}

	@Test
	public void aDeletedAgentCannotAct() {
		ENGINE.getVenueState().users().user(owner).agents().agent(AGENT).terminate();
		assertNull(ENGINE.getAgent(owner, AGENT));
		assertNull(ENGINE.getAgent(agentDID));
		assertNull(ENGINE.getAgent(RequestContext.of(owner), AGENT));
		assertNotNull(ENGINE.getVenueState().users().user(owner).agents().agent(AGENT), "though its record is still there");
	}

	@Test
	public void aDidThatNamesNoAgentFindsNone() {
		assertNull(ENGINE.getAgent(owner), "a user's DID names no agent");
		assertNull(ENGINE.getAgent(Principals.agentDID(owner, Strings.create("missing"))));
		assertNull(ENGINE.getAgent((AString) null, AGENT));
	}
}
