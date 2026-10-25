package covia.adapter.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static covia.adapter.whatsapp.TestSupport.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.messaging.*;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
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
		return payloadValue(Maps.of("messaging_product", "whatsapp", "metadata", Maps.of("phone_number_id", "12345"), "messages", Vectors.create(messages)));
	}
	static AMap<AString, ACell> payloadValue(ACell value) {
		return Maps.of("object", "whatsapp_business_account", "entry", Vectors.of(Maps.of("id", "67890", "changes",
			Vectors.of(Maps.of("field", "messages", "value", value)))));
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
			assertEquals(200, f.webhook(payload(message("reaction").assoc(Strings.intern("type"), Strings.create("reaction")))).status());
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
	@Test void ignoresOtherPhoneNumbersAndDeliveryStatusCallbacks() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			assertEquals(200, f.webhook(payloadValue(Maps.of("messaging_product", "whatsapp",
				"metadata", Maps.of("phone_number_id", "99999"), "messages", Vectors.of(message("wrong-phone"))))).status());
			assertEquals(200, f.webhook(payloadValue(Maps.of("messaging_product", "whatsapp",
				"metadata", Maps.of("phone_number_id", "12345"), "statuses", Vectors.of(Maps.of("id", "delivery-only",
					"status", "delivered", "recipient_id", "441234567890", "timestamp", "1700000000"))))).status());
			assertNull(f.bot().inbox().get("wrong-phone"));
			assertNull(f.bot().inbox().get("delivery-only"));
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void localDeliveryDoesNotWaitForResponsesOrReplayFailedReplies() throws Exception {
		var agentSpec = spec().dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply"))
			.assoc(Strings.intern("agent"), Strings.create("local-agent"));
		var response = new CompletableFuture<ACell>();
		var entered = new java.util.concurrent.CountDownLatch(1);
		try (Fixture f = new Fixture(agentSpec)) {
			f.engine.registerAdapter(new AAdapter() {
				@Override public String getName() { return "held"; }
				@Override public String getDescription() { return "Held model"; }
				@Override protected void installAssets() { installAsset("held/llm", Maps.of("operation", Maps.of("adapter", "held:llm"))); }
				@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
					entered.countDown(); return response;
				}
			});
			TestSupport.run(f.engine, OWNER, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "local-agent", Fields.CONFIG,
				Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/ops/held/llm")));
			assertEquals(200, f.webhook(payload(message("local-first"))).status());
			assertTrue(entered.await(8, TimeUnit.SECONDS));
			awaitStatus(f.bot(), "local-first", WebhookInbox.COMPLETE);
			assertEquals(200, f.webhook(payload(message("local-second"))).status());
			awaitStatus(f.bot(), "local-second", WebhookInbox.COMPLETE);
			var sid = f.bot().inbox().get("local-first").get(Fields.SESSION_ID);
			assertEquals(sid, f.bot().inbox().get("local-second").get(Fields.SESSION_ID));
			assertFalse(response.isDone());
			assertTrue(f.api.sends.isEmpty());
			var agent = f.engine.getVenueState().users().get(OWNER).agent("local-agent");
			var before = agent.getRecord();
			assertEquals(200, f.webhook(payload(message("local-second"))).status());
			assertEquals(before, agent.getRecord(), "duplicate callbacks must not append input");
			f.api.status = 503;
			response.complete(Maps.of("role", "assistant", "content", "reply"));
			f.api.take(); f.api.take();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			while (!Strings.create(WebhookBot.REPLY_FAILED).equals(f.bot().status().get(Fields.ERROR)) && System.nanoTime() < deadline) Thread.sleep(10);
			assertEquals(Strings.create(WebhookBot.REPLY_FAILED), f.bot().status().get(Fields.ERROR));
			awaitStatus(f.bot(), "local-first", WebhookInbox.COMPLETE);
			awaitStatus(f.bot(), "local-second", WebhookInbox.COMPLETE);
			f.configure(agentSpec);
			assertEquals(200, f.webhook(payload(message("local-first"), message("local-second"))).status());
			assertNull(f.api.sends.poll(200, TimeUnit.MILLISECONDS), "failed replies must not replay admission");
		} finally { response.complete(Maps.of("role", "assistant", "content", "cleanup")); }
	}

	static AMap<AString, ACell> agentSpec(String agent) {
		return spec().dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply")).assoc(Strings.intern("agent"), Strings.create(agent));
	}
	static void createAgent(Engine engine, String agent) throws Exception {
		TestSupport.run(engine, OWNER, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, agent, Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm")));
	}

	@Test void failingSessionDeliveryRunsOnce() throws Exception {
		try (Fixture f = new Fixture(agentSpec("flaky-agent"))) {
			var agents = new FlakyAgents(Integer.MAX_VALUE);
			f.engine.registerAdapter(agents);
			createAgent(f.engine, "flaky-agent");
			assertEquals(200, f.webhook(payload(message("doomed"))).status());
			awaitStatus(f.bot(), "doomed", WebhookInbox.FAILED);
			var receipt = f.bot().inbox().get("doomed");
			assertEquals(Strings.create(WebhookBot.PROCESSING_FAILED), receipt.get(Fields.REASON));
			assertEquals(Strings.create(FlakyAgents.FAILURE), receipt.get(WebhookInbox.LAST_ERROR));
			assertEquals(1, agents.calls.get());
			long jobs = jobCount(f.engine);
			// Recovery sweeps every 25 ms and providers redeliver: neither revives a FAILED receipt.
			assertEquals(200, f.webhook(payload(message("doomed"))).status());
			Thread.sleep(300);
			assertEquals(1, agents.calls.get());
			assertEquals(receipt, f.bot().inbox().get("doomed"));
			assertEquals(jobs, jobCount(f.engine));
			assertEquals(CVMLong.ONE, f.bot().status().get(Strings.intern("failed")));
			assertEquals(Strings.create(WebhookBot.PROCESSING_FAILED), f.bot().status().get(Fields.ERROR));
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void suspendedAgentFailsTheReceiptOnceWithoutJobs() throws Exception {
		try (Fixture f = new Fixture(agentSpec("resting-agent"))) {
			createAgent(f.engine, "resting-agent");
			TestSupport.run(f.engine, OWNER, "v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "resting-agent"));
			long jobs = jobCount(f.engine);
			assertEquals(200, f.webhook(payload(message("resting"))).status());
			awaitStatus(f.bot(), "resting", WebhookInbox.FAILED);
			var receipt = f.bot().inbox().get("resting");
			assertEquals(Strings.create(WebhookBot.PROCESSING_FAILED), receipt.get(Fields.REASON));
			assertEquals(Strings.create(AgentAdapter.DELIVERY_AGENT_SUSPENDED.formatted("resting-agent")), receipt.get(WebhookInbox.LAST_ERROR));
			Thread.sleep(300);
			assertEquals(receipt, f.bot().inbox().get("resting"));
			assertEquals(jobs, jobCount(f.engine), "a suspended agent gets no failed chat Job per attempt");
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void restartDoesNotReplayFailedDelivery() throws Exception {
		try (FakeAPI api = new FakeAPI()) {
			AMap<AString, ACell> config = Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1", Config.STORE, temp.resolve("failed.etch").toString(),
				Config.SEED, AKeyPair.createSeeded(9133).getSeed().toHexString(), Config.USERS, Maps.of(Config.AUTO_CREATE, true),
				Config.ADAPTERS, Maps.of(NAME, Maps.of("apiUrl", api.url(), "bots", Maps.of("main", agentSpec("durable-agent")))));
			var firstAgents = new FlakyAgents(Integer.MAX_VALUE);
			WhatsAppAdapter firstAdapter = quickScan(new WhatsAppAdapter());
			VenueServer first = VenueServer.launch(config, List.of(), engine -> {
				engine.registerAdapter(firstAgents); engine.registerAdapter(firstAdapter); return CompletableFuture.completedFuture(null); });
			AMap<AString, ACell> attempted;
			try {
				secret(first.getEngine(), "SIGNING", SECRET); secret(first.getEngine(), "VERIFY", "test-verify"); secret(first.getEngine(), "TOKEN", TOKEN);
				createAgent(first.getEngine(), "durable-agent");
				assertEquals(200, firstAdapter.handleWebhook("c-main", signed(payload(message("durable")))).status());
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
				while (firstAdapter.runner("main").inbox().get("durable").get(WebhookInbox.LAST_ERROR) == null && System.nanoTime() < deadline) Thread.sleep(5);
				attempted = firstAdapter.runner("main").inbox().get("durable");
				assertNotNull(attempted.get(WebhookInbox.LAST_ERROR));
			} finally { first.close(); }
			var secondAgents = new FlakyAgents(0);
			WhatsAppAdapter secondAdapter = quickScan(new WhatsAppAdapter());
			VenueServer second = VenueServer.launch(config, List.of(), engine -> {
				engine.registerAdapter(secondAgents); engine.registerAdapter(secondAdapter); return CompletableFuture.completedFuture(null); });
			try {
				awaitStatus(secondAdapter.runner("main"), "durable", WebhookInbox.FAILED);
				var receipt = secondAdapter.runner("main").inbox().get("durable");
				assertEquals(Strings.create(WebhookBot.PROCESSING_FAILED), receipt.get(Fields.REASON));
				assertEquals(attempted, receipt);
				assertEquals(Strings.create(FlakyAgents.FAILURE), receipt.get(WebhookInbox.LAST_ERROR));
				assertEquals(0, secondAgents.calls.get(), "a restart must not replay failed work");
				assertTrue(api.sends.isEmpty());
			} finally { second.close(); }
		}
	}
	/** Exercise repeated pending scans without replaying failed work. */
	private static WhatsAppAdapter quickScan(WhatsAppAdapter adapter) {
		adapter.retryMillis = 25;
		return adapter;
	}

	@Test void agentSessionsFollowSenderAndPhoneAndSurviveReplacement() throws Exception {
		var agentSpec = spec().dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply"))
			.assoc(Strings.intern("agent"), Strings.create("test-agent"))
			.assoc(Strings.intern("allow"), Vectors.of("441234567890", "441234567891"));
		try (Fixture f = new Fixture(agentSpec)) {
			FakeAgent agent = new FakeAgent(); f.engine.registerAdapter(agent);
			f.engine.configureAdapter(NAME, Maps.of("apiUrl", f.api.url(), "bots", Maps.of("main", agentSpec,
				"second", agentSpec.assoc(Strings.intern("phoneNumberId"), Strings.create("54321")))));
			var first = agentTurn(f, f.adapter, agent, "main", "12345", "441234567890", "first");
			assertNull(first.get(Fields.SESSION_ID));
			assertEquals(Strings.create("hello"), RT.getIn(first, Fields.MESSAGE, Fields.TEXT));
			assertEquals(Maps.of("channel", "whatsapp", "bot", "main", "from", "441234567890", "chat", "441234567890",
				"message_id", "first", "phoneNumberId", "12345", "access", "allow"), RT.getIn(first, Fields.MESSAGE, Strings.intern("via")));
			assertEquals(OWNER, agent.owner);
			assertNull(agentTurn(f, f.adapter, agent, "main", "12345", "441234567891", "other-sender").get(Fields.SESSION_ID));
			assertNull(agentTurn(f, f.adapter, agent, "second", "54321", "441234567890", "other-phone").get(Fields.SESSION_ID));
			var parsed = BotSpec.parse("main", agentSpec, true);
			String identity = Strings.create(ConversationRouter.key(OWNER.toString(), parsed.installationKey())).getHash().toHexString();
			String path = "config/main/sessions/" + identity + "/" + agentSpec.getHash().toHexString() + "/"
				+ ConversationSessions.segment(ConversationRouter.key(parsed.installationKey(), "441234567890"));
			assertEquals(Strings.create("session-1"), f.adapter.state().read(path));
			assertNull(f.engine.resolvePath(Strings.create("w/adapters/whatsapp/" + path), RequestContext.of(OWNER)));
			WhatsAppAdapter restored = new WhatsAppAdapter(); f.engine.registerAdapter(restored);
			assertEquals(Strings.create("session-1"), agentTurn(f, restored, agent, "main", "12345", "441234567890", "resumed").get(Fields.SESSION_ID));
			assertEquals(Strings.create("session-3"), agentTurn(f, restored, agent, "second", "54321", "441234567890", "resumed-other").get(Fields.SESSION_ID));
		}
	}
	private static AMap<AString, ACell> agentTurn(Fixture f, WhatsAppAdapter adapter, FakeAgent agent,
			String bot, String phone, String from, String id) throws Exception {
		assertEquals(200, adapter.handleWebhook("c-" + bot, signed(payloadValue(Maps.of("messaging_product", "whatsapp",
			"metadata", Maps.of("phone_number_id", phone), "messages", Vectors.of(message(id).assoc(Strings.intern("from"), Strings.create(from))))))).status());
		assertEquals(Strings.create(from), f.api.take().body().get(Strings.intern("to")));
		awaitStatus(adapter.runner(bot), id, WebhookInbox.COMPLETE);
		var input = agent.inputs.poll(5, TimeUnit.SECONDS); assertNotNull(input); return input;
	}
	@Test void appliesInboundAgeAndFutureSkewLimits() throws Exception {
		var clock = new MutableClock(); long now = clock.instant().getEpochSecond();
		try (Fixture f = new Fixture(spec().assoc(Strings.intern("token"), Strings.create("s/LATE")), new WhatsAppAdapter(clock))) {
			for (long offset : new long[] {-86401, -86399, 300, 301}) {
				String id = "offset-" + offset;
				assertEquals(200, f.webhook(payload(message(id).assoc(Strings.intern("timestamp"), Strings.create(Long.toString(now + offset))))).status());
				if (offset == -86401 || offset == 301) assertNull(f.bot().inbox().get(id));
				else assertEquals(WebhookInbox.PENDING, f.bot().inbox().get(id).get(Fields.STATUS));
			}
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void repliesUntilButNotAtThe24HourBoundary() throws Exception {
		var clock = new MutableClock(); long timestamp = clock.instant().getEpochSecond();
		try (Fixture f = new Fixture(spec(), new WhatsAppAdapter(clock))) {
			AMap<AString, ACell> event = Maps.of("to", "441234567890", "messageId", "boundary", "timestamp", Long.toString(timestamp));
			clock.now = Instant.ofEpochSecond(timestamp + 86399);
			f.adapter.sendReply(f.bot(), event, "still in window"); f.api.take();
			for (long age : new long[] {86400, 86401}) {
				clock.now = Instant.ofEpochSecond(timestamp + age);
				assertEquals(WhatsAppAdapter.WINDOW_EXPIRED, assertThrows(IllegalStateException.class,
					() -> f.adapter.sendReply(f.bot(), event, "expired")).getMessage());
			}
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void rechecksTheReplyWindowAfterTheAgentCompletes() throws Exception {
		var clock = new MutableClock();
		var agentSpec = spec().dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply")).assoc(Strings.intern("agent"), Strings.create("slow-agent"));
		try (Fixture f = new Fixture(agentSpec, new WhatsAppAdapter(clock))) {
			FakeAgent agent = new FakeAgent(); agent.blocked = new CompletableFuture<>(); f.engine.registerAdapter(agent);
			try {
				var message = message("slow").assoc(Strings.intern("timestamp"), Strings.create(Long.toString(clock.instant().getEpochSecond())));
				assertEquals(200, f.webhook(payload(message)).status());
				assertNotNull(agent.inputs.poll(5, TimeUnit.SECONDS));
				clock.now = clock.now.plusSeconds(86400);
				agent.blocked.complete(Maps.of(Fields.SESSION_ID, "late-session", Fields.RESPONSE, "too late"));
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (f.bot().status().get(Fields.ERROR) == null && System.nanoTime() < deadline) Thread.sleep(10);
				assertEquals(Strings.create(WebhookBot.PROCESSING_FAILED), f.bot().status().get(Fields.ERROR));
				assertEquals(WebhookInbox.STARTED, f.bot().inbox().get("slow").get(Fields.STATUS));
				assertTrue(f.api.sends.isEmpty());
			} finally { agent.blocked.complete(Maps.of(Fields.RESPONSE, "cleanup")); }
		}
	}
	@Test void truncatesAutomaticRepliesWithoutSplittingUnicode() throws Exception {
		try (Fixture f = new Fixture(spec().assoc(Strings.intern("reply"), Strings.create("🐦".repeat(4097))))) {
			assertEquals(200, f.webhook(payload(message("long-reply"))).status());
			assertEquals(Strings.create("🐦".repeat(4095) + "…"), RT.getIn(f.api.take().body(), Strings.intern("text"), Strings.intern("body")));
			awaitStatus(f.bot(), "long-reply", WebhookInbox.COMPLETE);
		}
	}
	@Test void rejectsSuccessfulHttpResponsesWithoutAMessageId() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			for (String response : new String[] {"{}", "{\"messages\":[]}", "{\"messages\":[{\"id\":42}]}"}) {
				f.api.response = response;
				assertEquals(WhatsAppAdapter.INVALID_RESPONSE, assertThrows(IllegalStateException.class,
					() -> f.adapter.send(f.bot(), Maps.of("to", "441234567890", "text", "test receipt"))).getMessage());
				f.api.take();
			}
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
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
	private static final class MutableClock extends Clock {
		volatile Instant now = Instant.parse("2026-10-06T12:00:00Z");
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
		@Override public Instant instant() { return now; }
	}
	private static final class FakeAgent extends AAdapter {
		final LinkedBlockingQueue<AMap<AString, ACell>> inputs = new LinkedBlockingQueue<>();
		final AtomicInteger sessions = new AtomicInteger();
		volatile AString owner;
		volatile CompletableFuture<ACell> blocked;
		@Override public String getName() { return "agent"; }
		@Override public String getDescription() { return "Deterministic WhatsApp chat fixture"; }
		@Override protected void installAssets() { installAsset("agent/chat", Maps.of("name", "Fixture chat", "operation", Maps.of("adapter", "agent:chat"))); }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			var in = MessagingSettings.object(input); owner = ctx.getUserDID(); inputs.add(in);
			if (blocked != null) return blocked;
			ACell sid = in.get(Fields.SESSION_ID);
			if (sid == null) sid = Strings.create("session-" + sessions.incrementAndGet());
			return CompletableFuture.completedFuture(Maps.of(Fields.SESSION_ID, sid, Fields.RESPONSE, "agent reply"));
		}
	}
}
