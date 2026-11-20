package covia.adapter.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.exception.ConfigurationException;
import covia.venue.Engine;
import covia.venue.TestEngine;

/**
 * The Discord module's helpers, called directly. Bots running against a venue
 * are in {@link DiscordAdapterTest}.
 */
class DiscordHelpersTest {
	private static final AString OWNER = Strings.create("did:test:discord:owner");

	@Test void validatesSpecsAndDoesNotLeakToken() {
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x", Maps.of("user", OWNER, "agent", "a"), false));
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x", Maps.of("token", "t", "user", OWNER, "agent", "a", "operation", "o"), false));
		BotSpec s = BotSpec.parse("x", Maps.of("token", "s/DISCORD", "user", OWNER, "agent", "a", "allow", Vectors.of("123", "@Alice")), true);
		assertTrue(s.mentionOnly());
		assertTrue(s.allows("123", null));
		assertTrue(s.allows("9", "ALICE"));
		assertFalse(s.allows("9", "bob"));
		assertFalse(s.toString().contains("DISCORD"));
		DiscordAdapter fresh = new DiscordAdapter();
		assertEquals(DiscordAdapter.STATE_PATH_FIXED, assertThrows(ConfigurationException.class,
			() -> fresh.configure(Maps.of("statePath", "w/elsewhere"), false)).getMessage());
		assertEquals(DiscordAdapter.API_URL_NOT_HTTP, assertThrows(ConfigurationException.class,
			() -> fresh.configure(Maps.of("apiUrl", "ftp://x"), false)).getMessage());
		assertEquals(DiscordAdapter.UNKNOWN_SETTING.formatted("unknown"), assertThrows(ConfigurationException.class,
			() -> fresh.configure(Maps.of("unknown", true), true)).getMessage());
		// The bot parser also reads create input, so its refusal is rethrown here as configuration
		ConfigurationException badBot = assertThrows(ConfigurationException.class,
			() -> fresh.configure(Maps.of("bots", Maps.of("main", Maps.empty())), false));
		assertInstanceOf(IllegalArgumentException.class, badBot.getCause());
		fresh.close();
	}

	@Test void aBotsUserMayBeAUserOfTheVenueInShortForm() {
		BotSpec spec = BotSpec.parse("x", Maps.of("token", "t", "user", "u:bob", "agent", "a"), false);
		assertEquals(TestEngine.ENGINE.managedUserDID(Strings.create("bob")), spec.userDID(TestEngine.ENGINE));
		BotSpec malformed = BotSpec.parse("x", Maps.of("token", "t", "user", "u:b@d", "agent", "a"), false);
		ConfigurationException refused = assertThrows(ConfigurationException.class, () -> malformed.userDID(TestEngine.ENGINE));
		assertEquals(Engine.CONFIGURED_PRINCIPAL.formatted("bot 'x' user", Engine.NOT_A_SHORT_FORM + "u:b@d"), refused.getMessage());
	}
}
