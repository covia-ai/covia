package covia.adapter.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static covia.adapter.whatsapp.TestSupport.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.lang.RT;
import covia.adapter.messaging.*;
import covia.adapter.webhook.*;
import covia.api.Fields;
import covia.venue.*;
import covia.venue.server.VenueServer;

class WhatsAppAdapterTest {
	@TempDir Path temp;
	static AMap<AString, ACell> spec() {
		return Maps.of("token", "s/TOKEN", "appSecret", "s/SIGNING", "verifyToken", "s/VERIFY", "user", OWNER,
			"phoneNumberId", "12345", "businessAccountId", "67890", "apiVersion", "v25.0", "operation", "v/test/ops/echo",
			"reply", "ack", "allow", Vectors.of("441234567890"));
	}
	static AMap<AString, ACell> message(String id) {
		return Maps.of("id", id, "type", "text", "from", "441234567890", "timestamp", Long.toString(Instant.now().getEpochSecond()), "text", Maps.of("body", "hello"));
	}
	static AMap<AString, ACell> payload(ACell... messages) {
		return Maps.of("object", "whatsapp_business_account", "entry", Vectors.of(Maps.of("id", "67890", "changes",
			Vectors.of(Maps.of("field", "messages", "value", Maps.of("messaging_product", "whatsapp", "metadata", Maps.of("phone_number_id", "12345"), "messages", Vectors.of(messages)))))));
	}
	@Test void validatesConfigurationAndKeepsSecretsPrivate() throws Exception {
		assertEquals(MessagingSettings.CREDENTIAL.formatted("token"), assertThrows(IllegalArgumentException.class,
			() -> BotSpec.parse("main", spec().assoc(Strings.intern("token"), Strings.create(TOKEN)), false)).getMessage());
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("../bad", spec(), false));
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main", spec().assoc(Strings.intern("agent"), Strings.create("a")), true));
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main", spec().assoc(Strings.intern("allow"), Vectors.of(123)), false));
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main", spec().assoc(Strings.intern("oops"), Strings.create("x")), true));
		assertFalse(BotSpec.parse("main", spec(), true).toString().contains("SIGNING"));
		try (Fixture f = new Fixture(spec())) {
			assertEquals(Maps.empty(), f.adapter.publicConfig());
			assertEquals(Maps.empty(), f.engine.resolvePath(Strings.create("v/adapters/whatsapp/config"), f.engine.venueContext()));
			String status = f.run("bots", Maps.empty()).toString();
			assertFalse(status.contains(TOKEN)); assertFalse(status.contains("s/SIGNING"));
			assertTrue(status.contains("/webhooks/whatsapp/c-main"));
			assertThrows(IllegalArgumentException.class, () -> f.engine.configureAdapter(NAME, Maps.of("statePath", "w/other")));
			assertThrows(IllegalArgumentException.class, () -> f.engine.configureAdapter(NAME, Maps.of("apiUrl", "https://token@example.com")));
			assertEquals(200, f.webhook(payload(message("still-live"))).status());
			assertEquals("Bearer " + TOKEN, f.api.take().authorization());
		}
	}
	@Test void verifiesChallengesAndExactRawBodyBeforeParsing() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var query = Map.of("hub.mode", List.of("subscribe"), "hub.verify_token", List.of("test-verify"), "hub.challenge", List.of("challenge"));
			var challenge = f.adapter.handleWebhook("c-main", new WebhookRequest("GET", Map.of(), query, new byte[0]));
			assertEquals(200, challenge.status()); assertEquals("challenge", new String(challenge.body(), StandardCharsets.UTF_8));
			assertEquals(403, f.adapter.handleWebhook("c-main", new WebhookRequest("GET", Map.of(), Map.of(), new byte[0])).status());
			var signed = signed(payload(message("sig")));
			assertEquals(401, f.adapter.handleWebhook("c-main", new WebhookRequest("POST", signed.headers(), Map.of(), "{} ".getBytes(StandardCharsets.UTF_8))).status());
			assertEquals(401, f.adapter.handleWebhook("c-main", new WebhookRequest("POST", Map.of(), Map.of(), new byte[] {1})).status());
			assertEquals(400, f.adapter.handleWebhook("c-main", signed("{".getBytes(StandardCharsets.UTF_8), Instant.now().getEpochSecond())).status());
			assertTrue(f.bot().inbox().records(WebhookInbox.PENDING).isEmpty());
			assertNull(f.api.sends.poll(100, TimeUnit.MILLISECONDS));
		}
	}
	@Test void splitsBatchesAndDeduplicatesConcurrentDelivery() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var request = signed(payload(message("batch-1"), message("batch-2")));
			var deliveries = new java.util.ArrayList<CompletableFuture<Void>>();
			for (int i = 0; i < 12; i++) deliveries.add(CompletableFuture.runAsync(() -> assertEquals(200, f.adapter.handleWebhook("c-main", request).status())));
			CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
			for (int i = 0; i < 2; i++) {
				var sent = f.api.take(); assertEquals("/v25.0/12345/messages", sent.path());
				assertEquals(Strings.create("441234567890"), sent.body().get(Strings.intern("to")));
				assertEquals(Strings.create("ack"), RT.getIn(sent.body(), Strings.intern("text"), Strings.intern("body")));
			}
			awaitStatus(f.bot(), "batch-1", WebhookInbox.COMPLETE); awaitStatus(f.bot(), "batch-2", WebhookInbox.COMPLETE);
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
			assertEquals(Strings.create("batch-1"), RT.getIn(f.bot().inbox().get("batch-1"), Fields.INPUT, Fields.INPUT, Strings.intern("message"), Fields.ID));
		}
	}
	@Test void ignoresUnadmittedSendersOtherBindingsStatusesAndStaleMessages() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			assertEquals(200, f.webhook(payload(message("denied").assoc(Strings.intern("from"), Strings.create("449999999999")))).status());
			assertEquals(200, f.webhook(payload(message("media").assoc(Strings.intern("type"), Strings.create("image")))).status());
			assertEquals(200, f.webhook(payload(message("old").assoc(Strings.intern("timestamp"), Strings.create("1")))).status());
			assertEquals(200, f.webhook(Maps.of("object", "whatsapp_business_account", "entry", Vectors.of(Maps.of("id", "other")))).status());
			assertEquals(200, f.webhook(payload()).status());
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
			assertTrue(f.bot().inbox().records(WebhookInbox.COMPLETE).isEmpty());
		}
	}
	@Test void gatesSendsAndRuntimeBindingsByOwnerAndAdmission() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			assertThrows(Exception.class, () -> run(f.engine, OTHER, "v/ops/whatsapp/send", Maps.of("bot", "main", "to", "441234567890", "text", "x")));
			assertThrows(Exception.class, () -> f.run("send", Maps.of("bot", "main", "to", "449999999999", "text", "x")));
			assertThrows(Exception.class, () -> f.run("send", Maps.of("to", "441234567890", "text", "x".repeat(4097))));
			assertTrue(f.api.sends.isEmpty());
			f.run("send", Maps.of("to", "441234567890", "text", "outbound", "reply_to", "wamid.parent"));
			assertEquals(Strings.create("wamid.parent"), RT.getIn(f.api.take().body(), Strings.intern("context"), Strings.intern("message_id")));
			var create = spec().dissoc(Strings.intern("user")).assoc(Fields.NAME, Strings.create("owned"));
			ACell status = f.run("create", create);
			String path = RT.ensureString(RT.getIn(status, Strings.intern("webhookPath"))).toString();
			assertTrue(path.startsWith("/webhooks/whatsapp/u-"));
			assertNotNull(f.adapter.state().read(f.adapter.userStatePath(OWNER, "bots/owned")));
			assertThrows(Exception.class, () -> f.run("create", create));
			assertThrows(Exception.class, () -> f.run("create", create.assoc(Strings.intern("user"), OTHER)));
			assertThrows(Exception.class, () -> f.run("create", create.assoc(Fields.NAME, Strings.create("bad")).assoc(Strings.intern("appSecret"), Strings.create(SECRET))));
			assertThrows(Exception.class, () -> run(f.engine, OTHER, "v/ops/whatsapp/delete", Maps.of("name", "owned")));
			f.run("delete", Maps.of("name", "owned"));
			assertNull(f.adapter.state().read(f.adapter.userStatePath(OWNER, "bots/owned")));
			assertEquals(404, f.adapter.handleWebhook(path.substring(path.lastIndexOf('/') + 1), signed(payload(message("deleted")))).status());
			assertThrows(Exception.class, () -> f.run("delete", Maps.of("name", "main")));
		}
	}
	@Test void failuresRemainUncertainAndDisableStopsIngress() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			f.api.status = 429;
			assertEquals(200, f.webhook(payload(message("failed"))).status());
			f.api.take(); awaitStatus(f.bot(), "failed", WebhookInbox.STARTED);
			assertEquals(200, f.webhook(payload(message("failed"))).status());
			assertNull(f.api.sends.poll(200, TimeUnit.MILLISECONDS));
			f.engine.disableAdapter(NAME);
			assertEquals(404, f.webhook(payload(message("disabled"))).status());
			f.engine.enableAdapter(NAME);
			f.api.status = 200;
			assertEquals(200, f.webhook(payload(message("enabled"))).status());
			f.api.take(); awaitStatus(f.bot(), "enabled", WebhookInbox.COMPLETE);
		}
	}
	@Test void durablePendingRecoversAfterRestartButStartedDoesNotReplay() throws Exception {
		try (FakeAPI api = new FakeAPI()) {
			AMap<AString, ACell> config = Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1", Config.STORE, temp.resolve("whatsapp.etch").toString(),
				Config.SEED, AKeyPair.createSeeded(9131).getSeed().toHexString(), Config.USERS, Maps.of(Config.AUTO_CREATE, true),
				Config.ADAPTERS, Maps.of(NAME, Maps.of("apiUrl", api.url(), "bots", Maps.of("main", spec()))));
			WhatsAppAdapter firstAdapter = new WhatsAppAdapter(); firstAdapter.retryMillis = 25;
			VenueServer first = VenueServer.launch(config, List.of(), engine -> { engine.registerAdapter(firstAdapter); return CompletableFuture.completedFuture(null); });
			try {
				secret(first.getEngine(), "SIGNING", SECRET); secret(first.getEngine(), "VERIFY", "test-verify");
				assertEquals(200, firstAdapter.handleWebhook("c-main", signed(payload(message("pending"), message("uncertain")))).status());
				assertTrue(firstAdapter.runner("main").inbox().claim("uncertain"));
				awaitStatus(firstAdapter.runner("main"), "pending", WebhookInbox.PENDING);
			} finally { first.close(); }
			WhatsAppAdapter secondAdapter = new WhatsAppAdapter(); secondAdapter.retryMillis = 25;
			VenueServer second = VenueServer.launch(config, List.of(), engine -> { engine.registerAdapter(secondAdapter); return CompletableFuture.completedFuture(null); });
			try {
				secret(second.getEngine(), "TOKEN", TOKEN);
				api.take(); awaitStatus(secondAdapter.runner("main"), "pending", WebhookInbox.COMPLETE);
				assertEquals(WebhookInbox.STARTED, secondAdapter.runner("main").inbox().get("uncertain").get(Fields.STATUS));
				assertEquals(200, secondAdapter.handleWebhook("c-main", signed(payload(message("pending"), message("uncertain")))).status());
				assertNull(api.sends.poll(200, TimeUnit.MILLISECONDS));
			} finally { second.close(); }
		}
	}
}
