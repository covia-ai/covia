package covia.adapter.whatsapp;

import static covia.adapter.whatsapp.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.AWebhookMessagingAdapter;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.RelayBinding;
import covia.adapter.messaging.RelaySettings;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;

/**
 * A WhatsApp binding whose handler is {@code relay}, end to end against a stand-in for
 * Meta: a principal links a number with a code sent from the phone; a text from that
 * number reaches that principal's poll and no other's; the answer goes back as WhatsApp
 * replies, once; a closed principal's sender is told so; an unlinked number is told how
 * to link; STOP unlinks; a forged webhook reaches nobody; the admission hook can refuse;
 * only the link is stored; the send operation stays as closed as written.
 */
class WhatsAppRelayTest {

	static final AString ALICE = Strings.create("did:test:relay:alice");
	static final AString BOB = Strings.create("did:test:relay:bob");
	static final String PHONE = "447700900123";
	static final String NUMBER = "447700900000";

	/** A venue with the binding {@code main} relaying, its credentials in OWNER's secrets. */
	static final class Relay implements AutoCloseable {
		final Fixture f;
		int ids;

		Relay() throws Exception { this(CVMBool.TRUE); }

		Relay(ACell relay) throws Exception {
			f = new Fixture(Maps.of("user", OWNER, "token", "s/TOKEN", "appSecret", "s/SIGNING", "verifyToken", "s/VERIFY",
				"businessAccountId", "123", "phoneNumberId", "456", "apiVersion", "v25.0", "number", NUMBER, "relay", relay));
		}

		AMap<AString, ACell> call(AString caller, String action, String body) throws Exception {
			WebhookResponse r = f.adapter.handleRelay("c-main", action, caller,
				new WebhookRequest("POST", Map.of(), Map.of(), body.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
			AMap<AString, ACell> answer = RT.castMap(JSON.parse(new String(r.body(), StandardCharsets.UTF_8)));
			return answer.assoc(Strings.create("status"), CVMLong.create(r.status()));
		}

		CompletableFuture<WebhookResponse> pollAsync(AString caller, long waitMs) {
			return f.adapter.handleRelay("c-main", RelayBinding.POLL, caller, new WebhookRequest("POST", Map.of(), Map.of(),
				("{\"wait_ms\":" + waitMs + "}").getBytes(StandardCharsets.UTF_8)));
		}

		WebhookResponse message(ACell message) {
			return f.webhook(Maps.of("object", "whatsapp_business_account", "entry",
				Vectors.of(Maps.of("id", "123", "changes", Vectors.of(Maps.of("field", "messages", "value",
					Maps.of("messaging_product", "whatsapp", "metadata", Maps.of("phone_number_id", "456"),
						"messages", Vectors.of(message))))))));
		}

		WebhookResponse text(String from, String id, String body) {
			return message(Maps.of("from", from, "id", id, "timestamp", Long.toString(Instant.now().getEpochSecond()),
				"type", "text", "text", Maps.of("body", body)));
		}

		/** Link PHONE to {@code principal} as a person does: the code, sent from the phone. */
		void link(AString principal) throws Exception {
			AMap<AString, ACell> issued = call(principal, RelayBinding.LINK, "{}");
			assertEquals(200, status(issued), issued.toString());
			String code = str(issued, "code");
			assertEquals(NUMBER, str(issued, "number"));
			assertEquals("https://wa.me/" + NUMBER + "?text=LINK%20" + code, str(issued, "link"));
			assertEquals(200, text(PHONE, "wamid.link" + (++ids), "LINK " + code).status());
			Sent linked = f.api.take();
			assertEquals(PHONE, str(linked.body(), "to"), "the phone hears it is linked");
			assertEquals(PHONE, str(call(principal, RelayBinding.STATUS, "{}"), "linked"));
		}

		@Override public void close() { f.close(); }
	}

	@Test void aLinkedPrincipalReceivesItsMessage_andAnswersItOnce() throws Exception {
		try (Relay r = new Relay()) {
			r.link(ALICE);
			CompletableFuture<WebhookResponse> polling = r.pollAsync(ALICE, 8_000);
			Thread.sleep(100);
			r.text(PHONE, "wamid.in1", "When was my last blood test?");
			AMap<AString, ACell> got = RT.ensureMap(JSON.parse(new String(polling.get(8, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8)));
			AVector<ACell> messages = RT.ensureVector(got.get(Strings.create("messages")));
			assertEquals(1, messages.count());
			assertEquals("wamid.in1", str(messages.get(0), "id"));
			assertEquals("When was my last blood test?", str(messages.get(0), "text"));
			assertEquals(PHONE, str(RT.getIn(messages.get(0), "via"), "from"));
			assertEquals(WhatsAppAdapter.NAME, str(RT.getIn(messages.get(0), "via"), "channel"));

			assertEquals(404, status(r.call(BOB, RelayBinding.REPLY, "{\"message_id\":\"wamid.in1\",\"text\":\"not yours\"}")),
				"another principal cannot answer it");
			String answer = "a ".repeat(3_000);   // over 4,096 characters: two parts
			AMap<AString, ACell> replied = r.call(ALICE, RelayBinding.REPLY, "{\"message_id\":\"wamid.in1\",\"text\":\"" + answer + "\"}");
			assertEquals(200, status(replied), replied.toString());
			Sent first = r.f.api.take();
			Sent second = r.f.api.take();
			assertEquals(PHONE, str(first.body(), "to"));
			assertEquals("wamid.in1", str(RT.getIn(first.body(), "context"), "message_id"), "the first part answers the message");
			assertNull(RT.getIn(second.body(), "context"));
			AMap<AString, ACell> again = r.call(ALICE, RelayBinding.REPLY, "{\"message_id\":\"wamid.in1\",\"text\":\"again\"}");
			assertEquals(409, status(again));
			assertEquals(RelayBinding.E_ALREADY_REPLIED, str(again, "error"));
			assertEquals("Bearer " + TOKEN, first.authorization());
		}
	}

	@Test void aClosedPrincipalsSenderIsTold_andNothingIsKept() throws Exception {
		try (Relay r = new Relay()) {
			r.link(ALICE);
			r.text(PHONE, "wamid.closed", "Hello?");
			assertEquals(PHONE, str(r.f.api.take().body(), "to"), "told the service is not running");
			AMap<AString, ACell> polled = r.call(ALICE, RelayBinding.POLL, "{\"wait_ms\":0}");
			assertEquals(0, RT.ensureVector(polled.get(Strings.create("messages"))).count());

			ACell stored = r.f.engine.resolvePath(Strings.create("w/adapters/" + WhatsAppAdapter.NAME), r.f.engine.venueContext());
			assertNotNull(RT.getIn(stored, "config", "main", "relay"), "the link is stored");
			assertNull(RT.getIn(stored, "config", "main", "inbox"), "a relay keeps no inbox");
			assertFalse(String.valueOf(stored).contains("Hello?"), "no message is stored");
		}
	}

	@Test void anUnlinkedNumberIsToldHowToLink_andStopUnlinks() throws Exception {
		try (Relay r = new Relay()) {
			r.text("447700900999", "wamid.stranger", "Hi");
			Sent told = r.f.api.take();
			assertEquals("447700900999", str(told.body(), "to"));
			assertEquals(RelaySettings.DEFAULT_NOTICES.get(RelayBinding.N_NOT_LINKED), str(RT.getIn(told.body(), "text"), "body"));

			r.link(ALICE);
			r.text(PHONE, "wamid.stop", "STOP");
			assertEquals(PHONE, str(r.f.api.take().body(), "to"));
			assertNull(RT.getIn(r.call(ALICE, RelayBinding.STATUS, "{}"), "linked"));
			assertEquals(200, status(r.call(ALICE, RelayBinding.UNLINK, "{}")), "unlinking again is harmless");
		}
	}

	@Test void aLinkedSenderIsToldWhatCannotBeRelayed_butAReactionPassesQuietly() throws Exception {
		try (Relay r = new Relay()) {
			r.link(ALICE);
			r.message(Maps.of("from", PHONE, "id", "wamid.reaction", "timestamp", Long.toString(Instant.now().getEpochSecond()),
				"type", "reaction", "reaction", Maps.of("message_id", "wamid.earlier", "emoji", "👍")));
			r.message(Maps.of("from", PHONE, "id", "wamid.location", "timestamp", Long.toString(Instant.now().getEpochSecond()),
				"type", "location", "location", Maps.of("latitude", 51.5, "longitude", -0.1)));
			Sent told = r.f.api.take();
			assertEquals(RelaySettings.DEFAULT_NOTICES.get(RelayBinding.N_TEXT_ONLY), str(RT.getIn(told.body(), "text"), "body"));
			assertNull(r.f.api.sends.poll(300, TimeUnit.MILLISECONDS), "the reaction drew no notice");
			AMap<AString, ACell> polled = r.call(ALICE, RelayBinding.POLL, "{\"wait_ms\":0}");
			assertEquals(0, RT.ensureVector(polled.get(Strings.create("messages"))).count());
		}
	}

	@Test void aVoiceNoteIsRelayed_andItsBytesAreFetchedOnRequestButNeverKept() throws Exception {
		byte[] voice = "OggS-relayed-voice".getBytes(StandardCharsets.UTF_8);
		try (Relay r = new Relay()) {
			r.link(ALICE);
			r.f.api.media.put("70001", new Media(voice, "audio/ogg"));
			CompletableFuture<WebhookResponse> polling = r.pollAsync(ALICE, 8_000);
			Thread.sleep(100);
			r.message(Maps.of("from", PHONE, "id", "wamid.voice", "timestamp", Long.toString(Instant.now().getEpochSecond()),
				"type", "audio", "audio", Maps.of("id", "70001", "mime_type", "audio/ogg; codecs=opus", "voice", CVMBool.TRUE)));
			AMap<AString, ACell> got = RT.ensureMap(JSON.parse(new String(polling.get(8, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8)));
			AVector<ACell> messages = RT.ensureVector(got.get(Strings.create("messages")));
			assertEquals(1, messages.count(), got.toString());
			assertEquals("", str(messages.get(0), "text"));
			AMap<AString, ACell> item = RT.ensureMap(RT.ensureVector(RT.getIn(messages.get(0), "media")).get(0));
			assertEquals("audio", str(item, "kind"));
			assertEquals("audio/ogg; codecs=opus", str(item, "mediaType"));
			assertEquals(CVMBool.TRUE, item.get(Strings.create("voice")));
			assertNull(item.get(Strings.create("id")), "the provider's media id stays with the hub");

			WebhookResponse fetched = media(r, ALICE, "wamid.voice");
			assertEquals(200, fetched.status());
			assertArrayEquals(voice, fetched.body());
			assertEquals("audio/ogg; codecs=opus", fetched.contentType());
			assertEquals("Bearer " + TOKEN, r.f.api.gets.poll(1, TimeUnit.SECONDS).authorization());
			assertEquals(404, media(r, BOB, "wamid.voice").status(), "only its principal may fetch it");
			assertEquals(404, media(r, ALICE, "wamid.unknown").status());

			ACell stored = r.f.engine.resolvePath(Strings.create("w/adapters/" + WhatsAppAdapter.NAME), r.f.engine.venueContext());
			assertFalse(String.valueOf(stored).contains("70001"), "no media reference is stored");
			ACell drives = run(r.f.engine, OWNER, "v/ops/dlfs/list-drives", Maps.empty());
			assertFalse(String.valueOf(drives).contains(WhatsAppAdapter.NAME), "and no copy in a drive of the owner's: " + drives);
		}
	}

	private static WebhookResponse media(Relay r, AString caller, String messageId) throws Exception {
		return r.f.adapter.handleRelay("c-main", RelayBinding.MEDIA, caller, new WebhookRequest("GET", Map.of(),
			Map.of("message_id", List.of(messageId)), new byte[0])).get(10, TimeUnit.SECONDS);
	}

	@Test void aForgedWebhookReachesNobody_andMetaVerifiesWithTheToken() throws Exception {
		try (Relay r = new Relay()) {
			r.link(ALICE);
			CompletableFuture<WebhookResponse> polling = r.pollAsync(ALICE, 1_000);
			byte[] forged = JSON.print(Maps.of("object", "whatsapp_business_account")).toString().getBytes(StandardCharsets.UTF_8);
			WebhookResponse refused = r.f.adapter.handleWebhook("c-main", new WebhookRequest("POST",
				Map.of("x-hub-signature-256", "sha256=" + "00".repeat(32)), Map.of(), forged));
			assertEquals(401, refused.status());
			AMap<AString, ACell> got = RT.ensureMap(JSON.parse(new String(polling.get(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8)));
			assertEquals(0, RT.ensureVector(got.get(Strings.create("messages"))).count());

			WebhookResponse ok = r.f.adapter.handleWebhook("c-main", new WebhookRequest("GET", Map.of(),
				Map.of("hub.mode", List.of("subscribe"), "hub.verify_token", List.of("test-verify"), "hub.challenge", List.of("42")), new byte[0]));
			assertEquals(200, ok.status());
			assertEquals("42", new String(ok.body(), StandardCharsets.UTF_8));
		}
	}

	@Test void theAdmissionHookCanRefuse() throws Exception {
		// Echo returns {principal} without allowed:true, so it refuses everyone.
		try (Relay r = new Relay(Maps.of("admit", "v/test/ops/echo"))) {
			AMap<AString, ACell> refused = r.call(ALICE, RelayBinding.LINK, "{}");
			assertEquals(403, status(refused));
			assertEquals(RelayBinding.E_NOT_ADMITTED, str(refused, "error"));
		}
	}

	@Test void aReplyLongerThanTheCapSendsNothing() throws Exception {
		try (Relay r = new Relay(Maps.of("maxParts", 1))) {
			r.link(ALICE);
			CompletableFuture<WebhookResponse> polling = r.pollAsync(ALICE, 8_000);
			Thread.sleep(100);
			r.text(PHONE, "wamid.long", "Tell me everything");
			polling.get(8, TimeUnit.SECONDS);
			String answer = "a ".repeat(3_000);   // two parts, over the cap of one
			AMap<AString, ACell> refused = r.call(ALICE, RelayBinding.REPLY, "{\"message_id\":\"wamid.long\",\"text\":\"" + answer + "\"}");
			assertEquals(400, status(refused));
			assertEquals(RelayBinding.E_TOO_LONG, str(refused, "error"));
			assertNull(r.f.api.sends.poll(300, TimeUnit.MILLISECONDS), "nothing is sent");
			assertEquals(200, status(r.call(ALICE, RelayBinding.REPLY, "{\"message_id\":\"wamid.long\",\"text\":\"short\"}")),
				"and the message can still be answered");
		}
	}

	@Test void theBotsListingShowsTheRelayPath_withoutSecrets() throws Exception {
		try (Relay r = new Relay()) {
			ACell mine = r.f.run("bots", Maps.empty());
			AVector<ACell> bots = RT.ensureVector(RT.getIn(mine, "bots"));
			assertEquals(1, bots.count());
			assertEquals("/relay/whatsapp/c-main", str(bots.get(0), "relayPath"));
			assertEquals("/webhooks/whatsapp/c-main", str(bots.get(0), "webhookPath"));
			assertFalse(String.valueOf(mine).contains(TOKEN) || String.valueOf(mine).contains(SECRET));
			ACell theirs = run(r.f.engine, OTHER, "v/ops/whatsapp/bots", Maps.empty());
			assertEquals(0, RT.ensureVector(RT.getIn(theirs, "bots")).count());
		}
	}

	@Test void aRelayBindingAdmitsAnySender_butItsSendOperationStaysClosed() throws Exception {
		try (Relay r = new Relay()) {
			Exception refused = assertThrows(Exception.class,
				() -> r.f.run("send", Maps.of("to", "447700900777", "text", "unsolicited")));
			assertTrue(String.valueOf(refused.getMessage()).contains(AWebhookMessagingAdapter.DESTINATION_DENIED), refused.toString());
			assertNull(r.f.api.sends.poll(300, TimeUnit.MILLISECONDS), "nothing is sent");
		}
	}

	@Test void anOwnerMayCreateARelayBindingAtRuntime() throws Exception {
		try (Relay r = new Relay()) {
			ACell created = r.f.run("create", Maps.of("name", "mine", "token", "s/TOKEN", "appSecret", "s/SIGNING",
				"verifyToken", "s/VERIFY", "businessAccountId", "124", "phoneNumberId", "457", "apiVersion", "v25.0", "relay", CVMBool.TRUE));
			String path = str(created, "relayPath");
			assertNotNull(path, String.valueOf(created));
			assertTrue(path.startsWith("/relay/whatsapp/u-"), path);
		}
	}

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

	private static int status(AMap<AString, ACell> m) {
		return (int) RT.ensureLong(m.get(Strings.create("status"))).longValue();
	}

	private static String str(ACell m, String key) {
		ACell v = RT.getIn(m, key);
		return v == null ? null : v.toString();
	}
}
