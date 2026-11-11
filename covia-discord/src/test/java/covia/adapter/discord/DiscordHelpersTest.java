package covia.adapter.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;

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
		assertThrows(IllegalArgumentException.class, () -> fresh.configure(Maps.of("statePath", "w/elsewhere"), false));
		fresh.close();
	}
}
