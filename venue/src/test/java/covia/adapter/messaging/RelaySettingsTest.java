package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

/**
 * A binding's relay settings are validated whole, and a relay is one of exactly one
 * handler, owned by a DID its hooks can run as.
 */
class RelaySettingsTest {

	@Test void relaySettingsAreValidatedWhole() {
		RelaySettings defaults = RelaySettings.parse(CVMBool.TRUE, true);
		assertEquals(60, defaults.holdSeconds());
		assertEquals(10, defaults.maxParts());
		assertNotNull(defaults.notice(RelayBinding.N_NOT_LINKED));
		RelaySettings custom = RelaySettings.parse(Maps.of("holdSeconds", 0, "maxParts", 2,
			"notices", Maps.of(RelayBinding.N_LINKED, "Done.")), true);
		assertEquals("Done.", custom.notice(RelayBinding.N_LINKED));
		assertEquals(RelaySettings.HOLD, assertThrows(IllegalArgumentException.class,
			() -> RelaySettings.parse(Maps.of("holdSeconds", -1), true)).getMessage());
		assertEquals(RelaySettings.MAX_PARTS, assertThrows(IllegalArgumentException.class,
			() -> RelaySettings.parse(Maps.of("maxParts", 0), true)).getMessage());
		assertEquals(RelaySettings.NOTICES, assertThrows(IllegalArgumentException.class,
			() -> RelaySettings.parse(Maps.of("notices", Maps.of("mystery", "x")), true)).getMessage());
		assertEquals(RelaySettings.INVALID, assertThrows(IllegalArgumentException.class,
			() -> RelaySettings.parse(CVMBool.FALSE, true)).getMessage());
		assertEquals(MessagingSettings.UNKNOWN.formatted("mystery"), assertThrows(IllegalArgumentException.class,
			() -> RelaySettings.parse(Maps.of("mystery", 1), true)).getMessage());
	}

	@Test void aRelayIsTheOneHandler_ownedByADid() {
		AMap<AString, ACell> relay = RT.castMap(Maps.of("user", "did:test:relay", "relay", CVMBool.TRUE, "token", "s/TOKEN"));
		MessagingSettings.validate("main", relay, MessagingSettings.COMMON, Set.of("token"), true);
		assertEquals(MessagingSettings.HANDLER, assertThrows(IllegalArgumentException.class, () -> MessagingSettings.validate("main",
			relay.assoc(MessagingSettings.key("agent"), Strings.create("g/a")), MessagingSettings.COMMON, Set.of("token"), true)).getMessage(),
			"one handler only");
		assertEquals(RelaySettings.PUBLIC_USER, assertThrows(IllegalArgumentException.class, () -> MessagingSettings.validate("main",
			relay.assoc(MessagingSettings.key("user"), Strings.create("public")), MessagingSettings.COMMON, Set.of("token"), true)).getMessage(),
			"hooks need an owner");
		assertEquals(MessagingSettings.REPLY, assertThrows(IllegalArgumentException.class, () -> MessagingSettings.validate("main",
			relay.assoc(MessagingSettings.key("reply"), CVMBool.FALSE), MessagingSettings.COMMON, Set.of("token"), true)).getMessage(),
			"reply belongs to operation handlers");
	}
}
