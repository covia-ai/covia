package covia.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.InvalidDefinitionException;

/**
 * The Telegram module's helpers, called directly: bot and adapter configuration,
 * reply and media-processor results, and the pending-retry warning. Bots
 * running against a venue are in {@link TelegramAdapterTest}.
 */
class TelegramHelpersTest {

	@Test
	void testSpecValidation() {
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x", Maps.of("user", "did:test:a", "agent", "a"), false),
			"token required");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x", Maps.of("token", "t", "agent", "a"), false),
			"user required");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x", Maps.of("token", "t", "user", "did:test:a"), false),
			"agent or operation required — the module has no default handler");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "did:test:a", "agent", "a", "reply", "ok"), false),
			"reply is not for agent conversations");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "did:test:a", "operation", "o", "reply", 7), false),
			"reply must be boolean or string");
		BotSpec op = BotSpec.parse("x", Maps.of("token", "t", "user", "did:test:a", "operation", "o/x",
			"reply", true), false);
		assertEquals("operation o/x", op.target());
		assertNull(op.reply(), "reply:true is the default, normalised away");
		assertTrue(BotSpec.parse("x", Maps.of("token", "t", "user", "did:test:a", "operation", "o/x",
			"reply", false), false).silent());
		assertEquals("Thanks", BotSpec.parse("x", Maps.of("token", "t", "user", "did:test:a", "operation", "o/x",
			"reply", "Thanks"), false).fixedReply());
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "did:test:a", "agent", "a", "operation", "o"), false),
			"agent and operation are exclusive");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "alice", "agent", "a"), false),
			"user must be a DID or public");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "did:test:a", "agent", "a", "parseMode", "BBCode"), false),
			"parseMode enum");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "did:test:a", "agent", "a", "allow", Vectors.of(Maps.empty())), false),
			"allow entries");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("bad name",
			Maps.of("token", "t", "user", "did:test:a", "agent", "a"), false),
			"bot name charset");
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("x",
			Maps.of("token", "t", "user", "did:test:a", "agent", "a", "colour", "blue"), true),
			"strict rejects unknown keys");
		// Lenient mode tolerates unknown keys
		BotSpec spec = BotSpec.parse("x",
			Maps.of("token", "s/TG", "user", "did:test:a", "agent", "a", "colour", "blue",
				"allow", Vectors.of(CVMLong.create(5), Strings.create("@Bob"), Strings.create("77"))), false);
		assertTrue(spec.allows(5L, null));
		assertTrue(spec.allows(77L, null));
		assertTrue(spec.allows(1L, "bob"), "usernames match case-insensitively");
		assertFalse(spec.allows(1L, "carol"));
		assertFalse(spec.toString().contains("s/TG") || spec.toString().contains("token"),
			"toString must not leak the token: " + spec);

		// Outbound: an allow-listed bot addresses only its listed ids, however written
		assertTrue(spec.allowsTarget(5L) && spec.allowsTarget("77"),
			"listed ids as numbers or numeric strings");
		assertFalse(spec.allowsTarget(" 5 "), "what is checked is what is sent, so padding is no listed id");
		assertFalse(spec.allowsTarget(1L), "an unlisted id");
		assertFalse(spec.allowsTarget("@bob"), "a username is never a target, even a listed one");
		assertFalse(spec.allowsTarget("@channel") || spec.allowsTarget(null) || spec.allowsTarget("5x"));
		BotSpec open = BotSpec.parse("x", Maps.of("token", "t", "user", "did:test:a", "agent", "a", "open", true), false);
		assertTrue(open.allowsTarget(1L) && open.allowsTarget("@channel"), "an open bot may address anyone");
	}

	@Test
	void testATargetIdIsExactlyAnId() {
		assertEquals(Long.valueOf(42), BotSpec.targetId("42"));
		assertEquals(Long.valueOf(-42), BotSpec.targetId(CVMLong.create(-42)));
		assertEquals(null, BotSpec.targetId(" 42"));
		assertEquals(null, BotSpec.targetId("42 "));
	}

	@Test
	void testAdapterConfigValidation() {
		TelegramAdapter fresh = new TelegramAdapter();
		assertThrows(IllegalArgumentException.class, () -> fresh.configure(Maps.of("apiUrl", "ftp://x"), false));
		assertThrows(IllegalArgumentException.class, () -> fresh.configure(Maps.of("bots", "nope"), false));
		assertThrows(IllegalArgumentException.class, () -> fresh.configure(Maps.of("unknown", true), true));
		assertTrue(fresh.configure(Maps.of("unknown", true), false), "lenient mode tolerates unknown keys");
		assertThrows(IllegalArgumentException.class,
			() -> fresh.configure(Maps.of("statePath", "w/elsewhere"), false),
			"adapter-global state has one well-known venue-private root even in lenient mode");
		assertTrue(fresh.configure(Maps.empty(), true));
		fresh.close();
	}

	@Test
	void processorResultsKeepTransportMetadataAndRejectMalformedText() {
		AMap<AString, ACell> attachment = Maps.of("ref", "dlfs/telegram/original.ogg", "kind", "voice");
		assertEquals(attachment, TelegramMedia.processed(attachment, Maps.empty()));
		var result = TelegramMedia.processed(attachment, Maps.of("ref", "application-ref", "recordId", "r1"));
		assertEquals(attachment.get(Fields.REF), result.get(Fields.REF));
		assertEquals(Strings.create("r1"), RT.getIn(result, "result", "recordId"));
		for (ACell invalid : new ACell[] {null, Strings.create("bare transcript"), Maps.of("text", null), Maps.of("text", 12), Maps.of("text", " ")}) {
			assertEquals(TelegramMedia.PROCESSOR_RESULT,
				assertThrows(InvalidDefinitionException.class, () -> TelegramMedia.processed(attachment, invalid)).getMessage());
		}
		assertEquals("Rendered", BotRunner.formattedReply(Strings.create("Rendered")));
		assertEquals("Rendered", BotRunner.formattedReply(Maps.of("text", "Rendered")));
		for (ACell invalid : new ACell[] {null, Maps.empty(), Maps.of("internalCard", "do not leak"), Strings.EMPTY}) {
			assertEquals(BotRunner.INVALID_REPLY,
				assertThrows(InvalidDefinitionException.class, () -> BotRunner.formattedReply(invalid)).getMessage());
		}
		for (AString option : List.of(BotSpec.K_MEDIA_OPERATION, BotSpec.K_REPLY_OPERATION)) {
			assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("bad", Maps.of("token", "t", "user", "did:test:a",
				"operation", "v/test/ops/echo").assoc(option, Strings.create("v/test/ops/echo")), true));
		}
	}

	@Test
	void testRenderText() {
		assertEquals("plain", TelegramAdapter.renderText(Strings.create("plain")));
		assertEquals("from text", TelegramAdapter.renderText(Maps.of("text", "from text", "other", 1)));
		assertEquals("from response", TelegramAdapter.renderText(Maps.of("response", "from response")));
		String json = TelegramAdapter.renderText(Maps.of("count", 3));
		assertTrue(json.contains("\"count\"") && json.contains("3"), json);
		assertNull(TelegramAdapter.renderText(null));
	}

	@Test
	void testPendingRetryWarningIsDeduplicated() {
		assertTrue(BotRunner.shouldWarnPending(BotRunner.State.PENDING,
			"same reason", 2, "same reason"), "the first slow retry warns once");
		assertFalse(BotRunner.shouldWarnPending(BotRunner.State.PENDING,
			"same reason", 3, "same reason"), "unchanged retries are debug-only");
		assertTrue(BotRunner.shouldWarnPending(BotRunner.State.PENDING,
			"old reason", 3, "new reason"), "a changed failure remains visible");
		assertTrue(BotRunner.shouldWarnPending(BotRunner.State.RUNNING,
			null, 4, "outage"), "a new outage after recovery warns");
	}
}
