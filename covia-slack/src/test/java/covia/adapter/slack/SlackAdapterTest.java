package covia.adapter.slack;

import static org.junit.jupiter.api.Assertions.*;
import static covia.adapter.slack.TestSupport.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
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
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.messaging.*;
import covia.adapter.webhook.*;
import covia.api.Fields;
import covia.venue.*;
import covia.venue.server.VenueServer;

class SlackAdapterTest {
	@TempDir Path temp;
	static AMap<AString, ACell> spec() {
		return Maps.of("token", "s/TOKEN", "signingSecret", "s/SIGNING", "user", OWNER, "appId", "A123", "teamId", "T123",
			"botUserId", "UBOT", "allow", Vectors.of("UALICE"), "allowChannels", Vectors.of("C123", "D123"),
			"operation", "v/test/ops/echo", "reply", "ack");
	}
	static AMap<AString, ACell> event(String ts) {
		return Maps.of("type", "app_mention", "user", "UALICE", "channel", "C123", "ts", ts, "text", "<@UBOT> hello");
	}
	static AMap<AString, ACell> payload(String id, ACell event) {
		return Maps.of("type", "event_callback", "event_id", id, "api_app_id", "A123", "team_id", "T123", "token", "legacy-do-not-store",
			"authorizations", Vectors.of(Maps.of("team_id", "T123", "user_id", "UBOT", "is_bot", true, "is_enterprise_install", false)), "event", event);
	}
	@Test void rejectsMalformedConfigAndProtectsPrivateFields() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("../bad", spec(), false));
		assertEquals(MessagingSettings.CREDENTIAL.formatted("signingSecret"), assertThrows(IllegalArgumentException.class,
			() -> BotSpec.parse("main", spec().assoc(Strings.intern("signingSecret"), Strings.create(SECRET)), false)).getMessage());
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main", spec().assoc(Strings.intern("allowChannels"), Vectors.of("C/escape")), true));
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main", spec().assoc(Strings.intern("open"), Strings.create("true")), false));
		assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main", spec().assoc(Strings.intern("unknown"), Strings.create("x")), true));
		try (Fixture f = new Fixture(spec())) {
			assertEquals(Maps.empty(), f.adapter.publicConfig());
			String status = f.run("bots", Maps.empty()).toString();
			assertFalse(status.contains(TOKEN)); assertFalse(status.contains("SIGNING")); assertFalse(status.contains("UALICE"));
			assertTrue(status.contains("/webhooks/slack/c-main"));
			assertThrows(IllegalArgumentException.class, () -> f.engine.configureAdapter(NAME, Maps.of("statePath", "w/other")));
			assertThrows(IllegalArgumentException.class, () -> f.engine.configureAdapter(NAME, Maps.of("apiUrl", "http://remote.example")));
		}
	}
	@Test void signedChallengeAndReplayWindowUseExactRawBytes() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var challenge = f.webhook(Maps.of("type", "url_verification", "challenge", "challenge"));
			assertEquals(200, challenge.status()); assertEquals("challenge", new String(challenge.body(), StandardCharsets.UTF_8));
			byte[] body = "{ \"type\": \"url_verification\", \"challenge\": \"unicode ✓\" }".getBytes(StandardCharsets.UTF_8);
			assertEquals(200, f.adapter.handleWebhook("c-main", signed(body, Instant.now().getEpochSecond())).status());
			assertEquals(401, f.adapter.handleWebhook("c-main", signed(body, Instant.now().getEpochSecond() - 301)).status());
			assertEquals(401, f.adapter.handleWebhook("c-main", signed(body, Instant.now().getEpochSecond() + 301)).status());
			var request = signed(body, Instant.now().getEpochSecond());
			assertEquals(401, f.adapter.handleWebhook("c-main", new WebhookRequest("POST", request.headers(), Map.of(), "{}".getBytes(StandardCharsets.UTF_8))).status());
			assertEquals(400, f.adapter.handleWebhook("c-main", signed("{".getBytes(StandardCharsets.UTF_8), Instant.now().getEpochSecond())).status());
			assertEquals(405, f.adapter.handleWebhook("c-main", new WebhookRequest("GET", Map.of(), Map.of(), new byte[0])).status());
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void bindsTheAppWorkspaceBotAndEnterpriseBeforeAcceptingEvents() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var event = payload("wrong", event("1700000000.000001"));
			assertEquals(403, f.webhook(event.assoc(Strings.intern("team_id"), Strings.create("TOTHER"))).status());
			assertEquals(403, f.webhook(event.assoc(Strings.intern("api_app_id"), Strings.create("AOTHER"))).status());
			assertEquals(403, f.webhook(event.assoc(Strings.intern("authorizations"), Vectors.of(Maps.of("team_id", "TOTHER", "user_id", "UBOT", "is_bot", true)))).status());
			f.configure(spec().assoc(Strings.intern("enterpriseId"), Strings.create("E123")));
			assertEquals(403, f.webhook(event).status());
			assertTrue(f.bot().inbox().records(WebhookInbox.PENDING).isEmpty());
			assertTrue(f.api.sends.isEmpty());
		}
	}
	@Test void acceptsTheUserAuthorizationShapeDocumentedBySlack() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var request = payload("user-authorization", event("1.1")).assoc(Strings.intern("authorizations"),
				Vectors.of(Maps.of("team_id", "T123", "user_id", "UINSTALLER", "is_bot", false, "is_enterprise_install", false)));
			assertEquals(200, f.webhook(request).status()); f.api.take();
			awaitStatus(f.bot(), "user-authorization", WebhookInbox.COMPLETE);
		}
	}
	@Test void admitsDmsAndChannelMentionsWithoutEchoLoopsAndPreservesThreads() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var blocked = List.of(event("1.1").assoc(Strings.intern("user"), Strings.create("UMALLORY")),
				event("1.2").assoc(Strings.intern("channel"), Strings.create("COTHER")),
				event("1.3").assoc(Strings.intern("user"), Strings.create("UBOT")),
				event("1.4").assoc(Strings.intern("bot_id"), Strings.create("B123")),
				event("1.5").assoc(Strings.intern("subtype"), Strings.create("message_changed")),
				event("1.6").assoc(Strings.intern("type"), Strings.create("message")));
			for (int i = 0; i < blocked.size(); i++) assertEquals(200, f.webhook(payload("blocked" + i, blocked.get(i))).status());
			assertTrue(f.api.sends.isEmpty());
			var request = signed(payload("mention", event("1700000000.000001").assoc(Strings.intern("thread_ts"), Strings.create("1699999999.000001"))));
			var deliveries = new java.util.ArrayList<CompletableFuture<Void>>();
			for (int i = 0; i < 10; i++) deliveries.add(CompletableFuture.runAsync(() -> assertEquals(200, f.adapter.handleWebhook("c-main", request).status())));
			CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
			var sent = f.api.take(); assertEquals("/chat.postMessage", sent.path()); assertEquals("Bearer " + TOKEN, sent.authorization());
			assertEquals(Strings.create("1699999999.000001"), sent.body().get(Strings.intern("thread_ts")));
			awaitStatus(f.bot(), "mention", WebhookInbox.COMPLETE);
			assertFalse(f.bot().inbox().get("mention").toString().contains("legacy-do-not-store"));
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
			var dm = Maps.of("type", "message", "channel_type", "im", "channel", "DOTHER", "user", "UALICE", "ts", "2.1", "text", "private hello");
			assertEquals(200, f.webhook(payload("dm", dm)).status());
			var dmReply = f.api.take(); assertEquals(Strings.create("DOTHER"), dmReply.body().get(Strings.intern("channel")));
			assertNull(dmReply.body().get(Strings.intern("thread_ts")));
		}
	}
	@Test void explicitSendsNeedOwnerAndChannelAuthorityAndCannotInjectMentions() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			assertThrows(Exception.class, () -> run(f.engine, OTHER, "v/ops/slack/send", Maps.of("bot", "main", "channel", "C123", "text", "x")));
			assertThrows(Exception.class, () -> f.run("send", Maps.of("channel", "COTHER", "text", "x")));
			assertThrows(Exception.class, () -> f.run("send", Maps.of("channel", "C123", "text", "x", "thread_ts", 1)));
			assertTrue(f.api.sends.isEmpty());
			f.run("send", Maps.of("channel", "C123", "text", "hello <@UALICE> <!channel> &", "thread_ts", "1.000001"));
			var sent = f.api.take(); assertEquals(Strings.create("hello &lt;@UALICE&gt; &lt;!channel&gt; &amp;"), sent.body().get(Fields.TEXT));
			assertEquals(convex.core.data.prim.CVMBool.FALSE, sent.body().get(Strings.intern("mrkdwn")));
			f.api.response = "{\"ok\":false,\"error\":\"invalid_auth\"}";
			assertThrows(Exception.class, () -> f.run("send", Maps.of("channel", "C123", "text", "fail")));
			f.api.take(); assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}
	@Test void persistsRuntimeBindingsAndDeletesTheirPrivateState() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			var create = spec().dissoc(Strings.intern("user")).assoc(Fields.NAME, Strings.create("owned"));
			var status = f.run("create", create);
			String callback = RT.ensureString(RT.getIn(status, Strings.intern("webhookPath"))).toString();
			String binding = callback.substring(callback.lastIndexOf('/') + 1);
			assertEquals(200, f.adapter.handleWebhook(binding, signed(payload("runtime", event("1.1")))).status());
			f.api.take(); awaitStatus(f.adapter.runner(OWNER, "owned"), "runtime", WebhookInbox.COMPLETE);
			SlackAdapter restored = new SlackAdapter(); restored.retryMillis = 25;
			f.adapter.state().write(f.adapter.userStatePath(OWNER, "bots/malformed"), Maps.of("token", "literal"));
			f.engine.registerAdapter(restored);
			assertNotNull(restored.runner(OWNER, "owned"));
			assertNull(restored.runner(OWNER, "malformed"));
			assertEquals(200, restored.handleWebhook(binding, signed(payload("runtime", event("1.1")))).status());
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
			assertThrows(Exception.class, () -> run(f.engine, OTHER, "v/ops/slack/delete", Maps.of("name", "owned")));
			run(f.engine, OWNER, "v/ops/slack/delete", Maps.of("name", "owned"));
			assertNull(restored.state().read(restored.userStatePath(OWNER, "bots/owned")));
			assertNull(restored.state().read(restored.userStatePath(OWNER, "inbox/owned")));
			assertNull(restored.state().read(restored.userStatePath(OWNER, "sessions/owned")));
			assertEquals(404, restored.handleWebhook(binding, signed(payload("deleted", event("2.1")))).status());
		}
	}
	@Test void agentSessionsAreSeparatedByThreadAndSurviveRunnerReplacement() throws Exception {
		var agentSpec = spec().dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply")).assoc(Strings.intern("agent"), Strings.create("test-agent"));
		try (Fixture f = new Fixture(agentSpec)) {
			FakeAgent agent = new FakeAgent(); f.engine.registerAdapter(agent);
			f.webhook(payload("first", event("1.1"))); f.api.take(); awaitStatus(f.bot(), "first", WebhookInbox.COMPLETE);
			var first = agent.inputs.poll(5, TimeUnit.SECONDS); assertNotNull(first); assertNull(first.get(Fields.SESSION_ID));
			assertEquals(Strings.create("hello"), RT.getIn(first, Fields.MESSAGE, Fields.TEXT));
			assertEquals(Strings.create("slack"), RT.getIn(first, Fields.MESSAGE, Strings.intern("via"), Strings.intern("channel")));
			assertEquals(Strings.create("first"), RT.getIn(first, Fields.MESSAGE, Strings.intern("via"), Strings.intern("event_id")));
			assertEquals(OWNER, agent.owner);
			var parsed = BotSpec.parse("main", agentSpec, true);
			String identity = Strings.create(ConversationRouter.key(OWNER.toString(), parsed.installationKey())).getHash().toHexString();
			String version = parsed.settings().getHash().toHexString();
			String root = "config/main/sessions/" + identity + "/" + version;
			String sessionPath = root + "/" + ConversationSessions.segment(ConversationRouter.key(parsed.installationKey(), "C123", "1.1"));
			assertEquals(Strings.create("session-1"), f.adapter.state().read(sessionPath));
			assertEquals("w/adapters/slack/" + sessionPath, f.adapter.state().path(sessionPath));
			assertEquals(Strings.create("session-1"), f.engine.resolvePath(Strings.create(f.adapter.state().path(sessionPath)), f.engine.venueContext()));
			assertNull(f.engine.resolvePath(Strings.create(f.adapter.state().path(sessionPath)), RequestContext.of(OWNER)));
			assertNotNull(f.adapter.state().read("config/main/inbox/" + identity + "/" + Strings.create("first").getHash().toHexString()));
			SlackAdapter restored = new SlackAdapter(); restored.retryMillis = 25; f.engine.registerAdapter(restored);
			restored.handleWebhook("c-main", signed(payload("same-thread", event("1.2").assoc(Strings.intern("thread_ts"), Strings.create("1.1")))));
			f.api.take(); awaitStatus(restored.runner("main"), "same-thread", WebhookInbox.COMPLETE);
			assertEquals(Strings.create("session-1"), agent.inputs.poll(5, TimeUnit.SECONDS).get(Fields.SESSION_ID));
			restored.handleWebhook("c-main", signed(payload("other-thread", event("2.1")))); f.api.take();
			assertNull(agent.inputs.poll(5, TimeUnit.SECONDS).get(Fields.SESSION_ID));
		}
	}
	@Test void restartRecoversPendingAndDoesNotReplayUncertainSends() throws Exception {
		try (FakeAPI api = new FakeAPI()) {
			AMap<AString, ACell> config = Maps.of(Config.PORT, 0, Config.BIND_ADDRESS, "127.0.0.1", Config.STORE, temp.resolve("slack.etch").toString(),
				Config.SEED, AKeyPair.createSeeded(9132).getSeed().toHexString(), Config.USERS, Maps.of(Config.AUTO_CREATE, true),
				Config.ADAPTERS, Maps.of(NAME, Maps.of("apiUrl", api.url(), "bots", Maps.of("main", spec()))));
			SlackAdapter firstAdapter = new SlackAdapter(); firstAdapter.retryMillis = 25;
			VenueServer first = VenueServer.launch(config, List.of(), engine -> { engine.registerAdapter(firstAdapter); return CompletableFuture.completedFuture(null); });
			try {
				secret(first.getEngine(), "SIGNING", SECRET);
				firstAdapter.handleWebhook("c-main", signed(payload("pending", event("1.1"))));
				firstAdapter.handleWebhook("c-main", signed(payload("uncertain", event("2.1"))));
				assertTrue(firstAdapter.runner("main").inbox().claim("uncertain"));
				awaitStatus(firstAdapter.runner("main"), "pending", WebhookInbox.PENDING);
			} finally { first.close(); }
			SlackAdapter secondAdapter = new SlackAdapter(); secondAdapter.retryMillis = 25;
			VenueServer second = VenueServer.launch(config, List.of(), engine -> { engine.registerAdapter(secondAdapter); return CompletableFuture.completedFuture(null); });
			try {
				secret(second.getEngine(), "TOKEN", TOKEN);
				api.take(); awaitStatus(secondAdapter.runner("main"), "pending", WebhookInbox.COMPLETE);
				assertEquals(WebhookInbox.STARTED, secondAdapter.runner("main").inbox().get("uncertain").get(Fields.STATUS));
				assertEquals(200, secondAdapter.handleWebhook("c-main", signed(payload("uncertain", event("2.1")))).status());
				assertNull(api.sends.poll(150, TimeUnit.MILLISECONDS));
			} finally { second.close(); }
		}
	}
	@Test void reconfigurationDoesNotExecutePendingWorkUnderANewPolicy() throws Exception {
		var waiting = spec().assoc(Strings.intern("token"), Strings.create("s/LATE"));
		try (Fixture f = new Fixture(waiting)) {
			assertEquals(200, f.webhook(payload("old-policy", event("1.1"))).status());
			awaitStatus(f.bot(), "old-policy", WebhookInbox.PENDING);
			f.configure(waiting.assoc(Strings.intern("reply"), Strings.create("new reply")));
			secret(f.engine, "LATE", TOKEN);
			assertEquals(200, f.webhook(payload("old-policy", event("1.1"))).status());
			assertEquals(200, f.webhook(payload("new-policy", event("1.2"))).status());
			assertEquals(Strings.create("new reply"), f.api.take().body().get(Fields.TEXT));
			awaitStatus(f.bot(), "new-policy", WebhookInbox.COMPLETE);
			awaitStatus(f.bot(), "old-policy", WebhookInbox.PENDING);
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}
	@Test void deletingAnInFlightBindingDoesNotResurrectSessionsOrSendItsReply() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			FakeAgent agent = new FakeAgent(); agent.blocked = new CompletableFuture<>(); f.engine.registerAdapter(agent);
			var create = spec().dissoc(Strings.intern("user")).dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply"))
				.assoc(Fields.NAME, Strings.create("owned")).assoc(Strings.intern("agent"), Strings.create("test-agent"));
			f.run("create", create);
			var bot = f.adapter.runner(OWNER, "owned");
			assertEquals(200, f.adapter.handleWebhook(bot.binding(), signed(payload("in-flight", event("1.1")))).status());
			assertNotNull(agent.inputs.poll(5, TimeUnit.SECONDS));
			f.run("delete", Maps.of("name", "owned"));
			agent.blocked.complete(Maps.of(Fields.SESSION_ID, "late-session", Fields.RESPONSE, "must not send"));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!convex.core.data.prim.CVMLong.ZERO.equals(bot.status().get(Strings.intern("queued"))) && System.nanoTime() < deadline) Thread.sleep(10);
			assertEquals(convex.core.data.prim.CVMLong.ZERO, bot.status().get(Strings.intern("queued")));
			assertNull(f.adapter.state().read(f.adapter.userStatePath(OWNER, "sessions/owned")));
			assertNull(f.adapter.state().read(f.adapter.userStatePath(OWNER, "inbox/owned")));
			assertTrue(f.api.sends.isEmpty());
		}
	}
	private static final class FakeAgent extends AAdapter {
		final LinkedBlockingQueue<AMap<AString, ACell>> inputs = new LinkedBlockingQueue<>();
		final AtomicInteger sessions = new AtomicInteger();
		volatile AString owner;
		volatile CompletableFuture<ACell> blocked;
		@Override public String getName() { return "agent"; }
		@Override public String getDescription() { return "Deterministic agent chat fixture"; }
		@Override protected void installAssets() { installAsset("agent/chat", Maps.of("name", "Fixture chat", "operation", Maps.of("adapter", "agent:chat"))); }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			var in = MessagingSettings.object(input); inputs.add(in); owner = ctx.getUserDID();
			if (blocked != null) return blocked;
			ACell sid = in.get(Fields.SESSION_ID);
			if (sid == null) sid = Strings.create("session-" + sessions.incrementAndGet());
			return CompletableFuture.completedFuture(Maps.of(Fields.SESSION_ID, sid, Fields.RESPONSE, "agent reply"));
		}
	}
}
