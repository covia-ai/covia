package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;
import covia.exception.InvalidInputException;

/** Reading an agent reference: which agent each form names, and what names none. */
public class AgentAddressTest {

	private static final AString CALLER = Strings.create("did:key:z6MkCaller");
	private static final AString OWNER = Strings.create("did:key:z6MkOwner");
	private static final AString ID = Strings.create("helper");

	private static AgentAddress parse(String ref) {
		return AgentAddress.parse(CALLER, Strings.create(ref));
	}

	@Test
	public void aRelativeReferenceNamesAnAgentOfTheDefaultOwner() {
		AgentAddress expected = new AgentAddress(CALLER, ID);
		assertEquals(expected, parse("helper"));
		assertEquals(expected, parse("g/helper"));
		assertEquals(expected, parse("/g/helper"));
	}

	@Test
	public void anAbsoluteReferenceNamesItsOwnersAgent() {
		AgentAddress expected = new AgentAddress(OWNER, ID);
		assertEquals(expected, parse(OWNER + "/g/helper"));
		assertEquals(expected, parse(OWNER + ":g:helper"), "an agent's DID names it");
	}

	@Test
	public void anAbsoluteReferenceNeedsNoDefaultOwner() {
		assertEquals(new AgentAddress(OWNER, ID), AgentAddress.parse(null, Strings.create(OWNER + ":g:helper")));
		assertEquals(new AgentAddress(OWNER, ID), AgentAddress.parse(null, Strings.create(OWNER + "/g/helper")));
	}

	@Test
	public void aRelativeReferenceWithNoDefaultOwnerNamesNothing() {
		InvalidInputException refused = assertThrows(InvalidInputException.class,
			() -> AgentAddress.parse(null, Strings.create("helper")));
		assertTrue(refused.getMessage().startsWith(AgentAddress.NO_OWNER));
	}

	@Test
	public void anAgentsAddressAndDidReadBackAsTheSameAgent() {
		AgentAddress agent = new AgentAddress(OWNER, ID);
		assertEquals(Strings.create(OWNER + "/g/helper"), agent.address());
		assertEquals(Strings.create(OWNER + ":g:helper"), agent.did());
		assertEquals(agent, AgentAddress.parse(null, agent.address()));
		assertEquals(agent, AgentAddress.parse(null, agent.did()));
	}

	@Test
	public void aReferenceToAnythingButOneAgentNamesNothing() {
		for (String ref : new String[] {
				"", "g/", "w/notes", "g/helper/n", "a:b", "g/a:b",
				OWNER.toString(), OWNER + "/w/notes", OWNER + "/g/", OWNER + "/g/helper/n", "did:/g/helper"}) {
			InvalidInputException refused = assertThrows(InvalidInputException.class, () -> parse(ref), ref);
			assertTrue(refused.getMessage().startsWith(AgentAddress.NOT_AN_AGENT), ref);
		}
		InvalidInputException refused = assertThrows(InvalidInputException.class, () -> AgentAddress.parse(CALLER, null));
		assertTrue(refused.getMessage().startsWith(AgentAddress.NOT_AN_AGENT));
	}
}
