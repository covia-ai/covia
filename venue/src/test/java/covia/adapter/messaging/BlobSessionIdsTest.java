package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.migration.MigrationStep;

/** Messaging adapter state step 1 → 2 ({@link BlobSessionIds}) as a function of the state. */
class BlobSessionIdsTest {
	private static final MigrationStep STEP = new BlobSessionIds();
	private static final AString OWNER = Strings.create("did:test:owner");
	private static final Blob SESSION = Blob.fromHex("a1".repeat(16));
	private static final Blob OTHER = Blob.fromHex("b2".repeat(16));
	private static final Blob CHAT = Blob.fromHex("c3".repeat(16));

	private static AString hex(Blob id) {
		return Strings.create(id.toHexString());
	}

	private static AMap<AString, ACell> receipt(ACell session, ACell job) {
		return Maps.of(Fields.STATUS, "COMPLETE", Fields.SESSION_ID, session, Fields.JOB_ID, job, "receivedAt", 1L);
	}

	/**
	 * A user's Telegram bot and a configured webhook bot as version 1 kept them:
	 * conversations at both providers' depths, and a delivered and a pending
	 * receipt. Two of the Telegram bot's mappings name no session: one is empty,
	 * and one is text its owner wrote at {@code w/telegram/sessions}, which
	 * version 1 copied as it found it.
	 */
	private static ACell legacy() {
		return Maps.of(
			"users", Maps.of(OWNER, Maps.of(
				"bots", Maps.of("support", Maps.of("operation", "v/test/ops/echo")),
				"sessions", Maps.of("support", Maps.of("1234", hex(SESSION), "5678", "", "9012", "the owner's note")),
				"inbox", Maps.of("support", Maps.of("binding", Maps.of("update", receipt(hex(SESSION), hex(CHAT))))),
				"relay", Maps.of("support", Maps.of("identity", Maps.of("note", "unchanged"))))),
			"config", Maps.of("operator", Maps.of(
				"sessions", Maps.of("identity", Maps.of("version", Maps.of("C0456", hex(OTHER)))),
				"inbox", Maps.of("identity", Maps.of("event", receipt(hex(OTHER), hex(CHAT)),
					"waiting", Maps.of(Fields.STATUS, "PENDING", "input", Maps.of(Fields.SESSION_ID, "a caller's text")))))));
	}

	@Test void aConversationContinuesItsSessionAsABlob() {
		ACell state = STEP.apply(legacy());
		assertEquals(SESSION, RT.getIn(state, "users", OWNER, "sessions", "support", "1234"));
		assertEquals(OTHER, RT.getIn(state, "config", "operator", "sessions", "identity", "version", "C0456"),
			"a webhook bot's conversations lie deeper, under its identity and settings");
	}

	@Test void aMappingThatNamesNoSessionGoes() {
		ACell state = STEP.apply(legacy());
		AMap<?, ?> conversations = RT.ensureMap(RT.getIn(state, "users", OWNER, "sessions", "support"));
		assertFalse(conversations.containsKey(Strings.create("5678")), "an empty mapping");
		assertFalse(conversations.containsKey(Strings.create("9012")), "text an owner wrote cannot stop the venue starting");
		assertTrue(conversations.containsKey(Strings.create("1234")));
	}

	@Test void aReceiptNamesItsSessionAndChatJobAsBlobs() {
		ACell state = STEP.apply(legacy());
		assertEquals(receipt(SESSION, CHAT), RT.getIn(state, "users", OWNER, "inbox", "support", "binding", "update"));
		assertEquals(receipt(OTHER, CHAT), RT.getIn(state, "config", "operator", "inbox", "identity", "event"));
		assertEquals(Maps.of(Fields.STATUS, "PENDING", "input", Maps.of(Fields.SESSION_ID, "a caller's text")),
			RT.getIn(state, "config", "operator", "inbox", "identity", "waiting"), "what an event carried is left as it came");
	}

	@Test void nothingElseChanges() {
		ACell state = STEP.apply(legacy());
		assertEquals(RT.getIn(legacy(), "users", OWNER, "bots"), RT.getIn(state, "users", OWNER, "bots"));
		assertEquals(RT.getIn(legacy(), "users", OWNER, "relay"), RT.getIn(state, "users", OWNER, "relay"));
	}

	@Test void currentStateComesBackAsTheSameCell() {
		ACell migrated = STEP.apply(legacy());
		assertSame(migrated, STEP.apply(migrated));
		assertNull(STEP.apply(null), "no state yet");
	}

	@Test void aReceiptIdThatIsNotOneIsRefused() {
		ACell state = Maps.of("config", Maps.of("operator", Maps.of(
			"inbox", Maps.of("identity", Maps.of("event", receipt(Strings.create("session-b"), hex(CHAT)))))));
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> STEP.apply(state));
		assertTrue(refused.getMessage().startsWith(BlobSessionIds.NOT_AN_ID));
	}
}
