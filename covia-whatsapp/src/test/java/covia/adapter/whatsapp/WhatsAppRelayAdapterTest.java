package covia.adapter.whatsapp;

import static covia.adapter.whatsapp.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.Config;
import covia.venue.Engine;

/**
 * The WhatsApp relay end to end against a stand-in for Meta: a principal links a number
 * with a code sent from the phone; a text from that number reaches that principal's poll
 * and no other's; the answer goes back as WhatsApp replies, once; a closed principal's
 * sender is told so; an unlinked number is told how to link; STOP unlinks; a forged
 * webhook reaches nobody; the admission hook can refuse; only the link is stored.
 */
class WhatsAppRelayAdapterTest {

	static final AString ALICE = Strings.create("did:test:relay:alice");
	static final AString BOB = Strings.create("did:test:relay:bob");
	static final String PHONE = "447700900123";

	/** A venue with the relay configured as {@code main}, its credentials in OWNER's secrets. */
	static final class Relay implements AutoCloseable {
		final FakeAPI api = new FakeAPI();
		final Engine engine = Engine.createTemp(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		final WhatsAppRelayAdapter adapter = new WhatsAppRelayAdapter();
		int ids;

		Relay() throws Exception { this(Maps.empty()); }

		Relay(AMap<AString, ACell> extra) throws Exception {
			Engine.addDemoAssets(engine);
			secret(engine, "TOKEN", TOKEN); secret(engine, "SIGNING", SECRET); secret(engine, "VERIFY", "test-verify");
			engine.registerAdapter(adapter);
			AMap<AString, ACell> spec = Maps.of("user", OWNER, "token", "s/TOKEN", "appSecret", "s/SIGNING",
				"verifyToken", "s/VERIFY", "businessAccountId", "123", "phoneNumberId", "456", "apiVersion", "v25.0",
				"number", "447700900000");
			for (var e : extra.entrySet()) spec = spec.assoc(e.getKey(), e.getValue());
			engine.configureAdapter(WhatsAppRelayAdapter.NAME, Maps.of("apiUrl", api.url(), "relays", Maps.of("main", spec)));
		}

		AMap<AString, ACell> call(AString caller, String action, String body) throws Exception {
			WebhookResponse r = adapter.handleRelay("c-main", action, caller,
				new WebhookRequest("POST", Map.of(), Map.of(), body.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
			AMap<AString, ACell> answer = RT.castMap(JSON.parse(new String(r.body(), StandardCharsets.UTF_8)));
			return answer.assoc(Strings.create("status"), convex.core.data.prim.CVMLong.create(r.status()));
		}

		CompletableFuture<WebhookResponse> pollAsync(AString caller, long waitMs) {
			return adapter.handleRelay("c-main", "poll", caller, new WebhookRequest("POST", Map.of(), Map.of(),
				("{\"wait_ms\":" + waitMs + "}").getBytes(StandardCharsets.UTF_8)));
		}

		WebhookResponse text(String from, String id, String body) {
			ACell message = Maps.of("from", from, "id", id, "timestamp", Long.toString(Instant.now().getEpochSecond()),
				"type", "text", "text", Maps.of("body", body));
			return adapter.handleWebhook("c-main", signed(Maps.of("object", "whatsapp_business_account", "entry",
				Vectors.of(Maps.of("id", "123", "changes", Vectors.of(Maps.of("field", "messages", "value",
					Maps.of("messaging_product", "whatsapp", "metadata", Maps.of("phone_number_id", "456"),
						"messages", Vectors.of(message)))))))));
		}

		/** Link PHONE to {@code principal} as a person does: the code, sent from the phone. */
		void link(AString principal) throws Exception {
			AMap<AString, ACell> issued = call(principal, "link", "{}");
			assertEquals(200, status(issued), issued.toString());
			String code = str(issued, "code");
			assertTrue(str(issued, "link").contains(code));
			assertEquals(200, text(PHONE, "wamid.link" + (++ids), "LINK " + code).status());
			Sent linked = api.take();
			assertEquals(PHONE, str(linked.body(), "to"), "the phone hears it is linked");
			assertEquals(PHONE, str(call(principal, "status", "{}"), "linked"));
		}

		@Override public void close() { adapter.close(); engine.close(); api.close(); }
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

			assertEquals(404, status(r.call(BOB, "reply", "{\"message_id\":\"wamid.in1\",\"text\":\"not yours\"}")),
				"another principal cannot answer it");
			String answer = "a ".repeat(3_000);   // over 4,096 characters: two parts
			AMap<AString, ACell> replied = r.call(ALICE, "reply", "{\"message_id\":\"wamid.in1\",\"text\":\"" + answer + "\"}");
			assertEquals(200, status(replied), replied.toString());
			Sent first = r.api.take();
			Sent second = r.api.take();
			assertEquals(PHONE, str(first.body(), "to"));
			assertEquals("wamid.in1", str(RT.getIn(first.body(), "context"), "message_id"), "the first part answers the message");
			assertNull(RT.getIn(second.body(), "context"));
			assertEquals(409, status(r.call(ALICE, "reply", "{\"message_id\":\"wamid.in1\",\"text\":\"again\"}")));
			assertEquals("Bearer " + TOKEN, first.authorization());
		}
	}

	@Test void aClosedPrincipalsSenderIsTold_andNothingIsKept() throws Exception {
		try (Relay r = new Relay()) {
			r.link(ALICE);
			r.text(PHONE, "wamid.closed", "Hello?");
			assertEquals(PHONE, str(r.api.take().body(), "to"), "told the service is not running");
			AMap<AString, ACell> polled = r.call(ALICE, "poll", "{\"wait_ms\":0}");
			assertEquals(0, RT.ensureVector(polled.get(Strings.create("messages"))).count());

			ACell stored = r.engine.resolvePath(Strings.create("w/adapters/" + WhatsAppRelayAdapter.NAME), r.engine.venueContext());
			assertNotNull(RT.getIn(stored, "config", "main", "links"), "the link is stored");
			assertFalse(String.valueOf(stored).contains("Hello?"), "no message is");
		}
	}

	@Test void anUnlinkedNumberIsToldHowToLink_andStopUnlinks() throws Exception {
		try (Relay r = new Relay()) {
			r.text("447700900999", "wamid.stranger", "Hi");
			assertEquals("447700900999", str(r.api.take().body(), "to"));

			r.link(ALICE);
			r.text(PHONE, "wamid.stop", "STOP");
			assertEquals(PHONE, str(r.api.take().body(), "to"));
			assertNull(RT.getIn(r.call(ALICE, "status", "{}"), "linked"));
			assertEquals(200, status(r.call(ALICE, "unlink", "{}")), "unlinking again is harmless");
		}
	}

	@Test void aForgedWebhookReachesNobody_andMetaVerifiesWithTheToken() throws Exception {
		try (Relay r = new Relay()) {
			r.link(ALICE);
			CompletableFuture<WebhookResponse> polling = r.pollAsync(ALICE, 1_000);
			byte[] forged = JSON.print(Maps.of("object", "whatsapp_business_account")).toString().getBytes(StandardCharsets.UTF_8);
			WebhookResponse refused = r.adapter.handleWebhook("c-main", new WebhookRequest("POST",
				Map.of("x-hub-signature-256", "sha256=" + "00".repeat(32)), Map.of(), forged));
			assertEquals(401, refused.status());
			AMap<AString, ACell> got = RT.ensureMap(JSON.parse(new String(polling.get(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8)));
			assertEquals(0, RT.ensureVector(got.get(Strings.create("messages"))).count());

			WebhookResponse ok = r.adapter.handleWebhook("c-main", new WebhookRequest("GET", Map.of(),
				Map.of("hub.mode", java.util.List.of("subscribe"), "hub.verify_token", java.util.List.of("test-verify"),
					"hub.challenge", java.util.List.of("42")), new byte[0]));
			assertEquals(200, ok.status());
			assertEquals("42", new String(ok.body(), StandardCharsets.UTF_8));
			WebhookResponse wrong = r.adapter.handleWebhook("c-main", new WebhookRequest("GET", Map.of(),
				Map.of("hub.mode", java.util.List.of("subscribe"), "hub.verify_token", java.util.List.of("wrong"),
					"hub.challenge", java.util.List.of("42")), new byte[0]));
			assertEquals(403, wrong.status());
		}
	}

	@Test void theAdmissionHookCanRefuse() throws Exception {
		// Echo returns {principal} without allowed:true, so it refuses everyone.
		try (Relay r = new Relay(Maps.of("admit", "v/test/ops/echo"))) {
			AMap<AString, ACell> refused = r.call(ALICE, "link", "{}");
			assertEquals(403, status(refused));
			assertEquals("not_admitted", str(refused, "error"));
		}
	}

	@Test void theRelaysOperationShowsOwnersTheirRelays_withoutSecrets() throws Exception {
		try (Relay r = new Relay()) {
			ACell mine = run(r.engine, OWNER, "v/ops/" + WhatsAppRelayAdapter.NAME + "/relays", Maps.empty());
			AVector<ACell> relays = RT.ensureVector(RT.getIn(mine, "relays"));
			assertEquals(1, relays.count());
			assertEquals("/relay/whatsapp-relay/c-main", str(relays.get(0), "relayPath"));
			assertFalse(String.valueOf(mine).contains(TOKEN) || String.valueOf(mine).contains(SECRET));
			ACell theirs = run(r.engine, OTHER, "v/ops/" + WhatsAppRelayAdapter.NAME + "/relays", Maps.empty());
			assertEquals(0, RT.ensureVector(RT.getIn(theirs, "relays")).count());
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
			AMap<AString, ACell> refused = r.call(ALICE, "reply", "{\"message_id\":\"wamid.long\",\"text\":\"" + answer + "\"}");
			assertEquals(400, status(refused));
			assertEquals("too_long", str(refused, "error"));
			assertNull(r.api.sends.poll(300, TimeUnit.MILLISECONDS), "nothing is sent");
			assertEquals(200, status(r.call(ALICE, "reply", "{\"message_id\":\"wamid.long\",\"text\":\"short\"}")),
				"and the message can still be answered");
		}
	}

	@Test void rateLimitWindowsStayBoundedWhateverSendersArrive() {
		WhatsAppRelayAdapter.Windows windows = new WhatsAppRelayAdapter.Windows();
		for (int i = 0; i < WhatsAppRelayAdapter.Windows.MAX_KEYS + 500; i++) {
			assertTrue(windows.allow("notice:main:" + i, 1, 60_000L));
		}
		assertTrue(windows.size() <= WhatsAppRelayAdapter.Windows.MAX_KEYS);
		assertFalse(windows.allow("notice:main:" + (WhatsAppRelayAdapter.Windows.MAX_KEYS + 499), 1, 60_000L),
			"a recent key keeps its count");
	}

	@Test void malformedRelaySettingsAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> RelaySpec.parse("main", Maps.of("user", OWNER, "token", "plain-token",
			"appSecret", "s/SIGNING", "verifyToken", "s/VERIFY", "businessAccountId", "123", "phoneNumberId", "456",
			"apiVersion", "v25.0", "number", "447700900000"), true), "credentials must be secret references");
		assertThrows(IllegalArgumentException.class, () -> RelaySpec.parse("main", Maps.of("user", OWNER, "token", "s/TOKEN",
			"appSecret", "s/SIGNING", "verifyToken", "s/VERIFY", "businessAccountId", "123", "phoneNumberId", "456",
			"apiVersion", "v25.0", "number", "447700900000", "mystery", "x"), true), "unknown settings, in strict mode");
	}

	private static int status(AMap<AString, ACell> m) {
		return (int) RT.ensureLong(m.get(Strings.create("status"))).longValue();
	}

	private static String str(ACell m, String key) {
		ACell v = RT.getIn(m, key);
		return v == null ? null : v.toString();
	}
}
