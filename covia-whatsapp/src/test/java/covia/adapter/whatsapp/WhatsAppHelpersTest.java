package covia.adapter.whatsapp;

import static covia.adapter.whatsapp.TestSupport.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.RelaySettings;

/**
 * The WhatsApp module's helpers, called directly. Bindings running against a
 * venue are in the module's other tests.
 */
class WhatsAppHelpersTest {

	@Test void malformedRelaySettingsAreRefused() {
		AMap<AString, ACell> base = RT.castMap(Maps.of("user", OWNER, "token", "s/TOKEN", "appSecret", "s/SIGNING",
			"verifyToken", "s/VERIFY", "businessAccountId", "123", "phoneNumberId", "456", "apiVersion", "v25.0"));
		assertEquals(RelaySettings.HOLD, assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main",
			base.assoc(MessagingSettings.key("relay"), Maps.of("holdSeconds", -1)), true)).getMessage());
		assertEquals(MessagingSettings.HANDLER, assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main",
			base.assoc(MessagingSettings.key("relay"), CVMBool.TRUE).assoc(MessagingSettings.key("agent"), Strings.create("g/a")), true)).getMessage());
		assertEquals(RelaySettings.PUBLIC_USER, assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main",
			base.assoc(MessagingSettings.key("relay"), CVMBool.TRUE).assoc(MessagingSettings.key("user"), Strings.create("public")), true)).getMessage());
		assertEquals(MessagingSettings.INVALID_ID.formatted("number"), assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main",
			base.assoc(MessagingSettings.key("relay"), CVMBool.TRUE).assoc(MessagingSettings.key("number"), Strings.create("+44 7700")), true)).getMessage());
	}
}
