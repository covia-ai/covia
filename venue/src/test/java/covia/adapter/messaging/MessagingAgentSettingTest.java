package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.venue.AgentAddress;

/** A binding's agent setting names one agent, in any form an agent reference takes. */
class MessagingAgentSettingTest {

	private static final String OWNER = "did:key:z6MkBindingOwner";
	private static final AMap<AString, ACell> BINDING = RT.castMap(Maps.of("user", OWNER, "token", "s/TOKEN"));

	private static void validate(String agent) {
		MessagingSettings.validate("main", BINDING.assoc(MessagingSettings.key("agent"), Strings.create(agent)),
			MessagingSettings.COMMON, Set.of("token"), true);
	}

	@Test void everyFormOfAgentReferenceIsAccepted() {
		for (String agent : new String[] {"helper", "g/helper", "/g/helper", OWNER + "/g/helper", OWNER + ":g:helper"}) {
			assertDoesNotThrow(() -> validate(agent), agent);
		}
	}

	@Test void aReferenceToAnythingButOneAgentIsRefusedWhenTheBindingIsMade() {
		for (String agent : new String[] {"w/notes", "g/helper/n", OWNER, "a:b"}) {
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> validate(agent), agent);
			assertEquals(MessagingSettings.INVALID_AGENT.formatted("agent", AgentAddress.NOT_AN_AGENT + agent),
				refused.getMessage(), agent);
		}
	}

	@Test void aPublicBindingsRelativeAgentIsAccepted() {
		MessagingSettings.validate("main", RT.castMap(Maps.of("user", "public", "agent", "helper", "token", "s/TOKEN")),
			MessagingSettings.COMMON, Set.of("token"), true);
	}
}
