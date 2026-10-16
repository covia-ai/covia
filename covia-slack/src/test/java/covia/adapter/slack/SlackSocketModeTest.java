package covia.adapter.slack;

import static covia.adapter.slack.SlackAdapterTest.event;
import static covia.adapter.slack.SlackAdapterTest.payload;
import static covia.adapter.slack.SlackAdapterTest.spec;
import static covia.adapter.slack.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
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
import covia.adapter.messaging.MessagingHttp;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBot;
import covia.adapter.webhook.WebhookInbox;
import covia.api.Fields;
import covia.venue.RequestContext;
import io.javalin.websocket.WsContext;

class SlackSocketModeTest {
	static final AString APP = Strings.intern("appToken"), SIGNING = Strings.intern("signingSecret");
	static AMap<AString, ACell> socketSpec() { return spec().dissoc(SIGNING).assoc(APP, Strings.create("s/APP")); }
	static String envelope(String id, ACell payload) {
		return JSON.print(Maps.of("type", "events_api", "envelope_id", id, "accepts_response_payload", false, "payload", payload)).toString();
	}
	static String ack(String id) { return JSON.print(Maps.of("envelope_id", id)).toString(); }
	static void awaitConnection(WebhookBot<?> bot, String state) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
		while (System.nanoTime() < deadline) {
			if (Strings.create(state).equals(bot.status().get(Strings.intern("connection")))) return;
			Thread.sleep(10);
		}
		fail("Connection did not reach " + state + ": " + bot.status());
	}

	@Test void acknowledgesOnlyAfterDurableAcceptanceAndRepliesInThread() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			assertEquals("Bearer " + APP_TOKEN, f.api.opened().authorization());
			WsContext ctx = socket.connected();
			awaitConnection(f.bot(), WebhookBot.CONNECTED);
			ctx.send(envelope("env-1", payload("socket", event("1700000000.000001"))));
			assertEquals(ack("env-1"), socket.ack());
			assertNotNull(f.bot().inbox().get("socket"));
			var reply = f.api.take();
			assertEquals("/chat.postMessage", reply.path()); assertEquals("Bearer " + TOKEN, reply.authorization());
			assertEquals(Strings.create("ack"), reply.body().get(Fields.TEXT));
			assertEquals(Strings.create("1700000000.000001"), reply.body().get(Strings.intern("thread_ts")));
			awaitStatus(f.bot(), "socket", WebhookInbox.COMPLETE);
			var status = f.bot().status();
			assertEquals(Strings.create("RUNNING"), status.get(Strings.intern("state")));
			assertNull(status.get(Strings.intern("webhookPath")));
			String listed = f.run("bots", Maps.empty()).toString();
			assertFalse(listed.contains(APP_TOKEN)); assertFalse(listed.contains("s/APP"));
			// Without a signing secret there is no HTTP intake to forge.
			assertEquals(404, f.webhook(payload("http", event("2.1"))).status());
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}

	@Test void ignoredEnvelopesAreAcknowledgedWithoutEffect() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			WsContext ctx = socket.connected();
			ctx.send(JSON.print(Maps.of("type", "slash_commands", "envelope_id", "command", "payload", Maps.of("command", "/x"))).toString());
			assertEquals(ack("command"), socket.ack());
			ctx.send(envelope("other-team", payload("other-team", event("1.1")).assoc(Strings.intern("team_id"), Strings.create("TOTHER"))));
			assertEquals(ack("other-team"), socket.ack());
			ctx.send(envelope("stranger", payload("stranger", event("1.2").assoc(Strings.intern("user"), Strings.create("UMALLORY")))));
			assertEquals(ack("stranger"), socket.ack());
			ctx.send(envelope("malformed", Strings.create("not an object")));
			assertEquals(ack("malformed"), socket.ack());
			assertNull(f.bot().inbox().get("other-team"));
			assertNull(f.bot().inbox().get("stranger"));
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}

	@Test void refreshReconnectsAndRedeliveryIsDeduplicated() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			f.api.opened();
			WsContext first = socket.connected();
			first.send(envelope("env-1", payload("once", event("1.1"))));
			assertEquals(ack("env-1"), socket.ack());
			f.api.take(); awaitStatus(f.bot(), "once", WebhookInbox.COMPLETE);
			first.send("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}");
			f.api.opened();
			WsContext second = socket.connected();
			second.send(envelope("env-2", payload("once", event("1.1"))));
			assertEquals(ack("env-2"), socket.ack());
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}

	@Test void waitsForTheAppTokenAndStopsCleanly() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec().assoc(APP, Strings.create("s/LATE")), socket.url())) {
			assertNull(f.api.opens.poll(150, TimeUnit.MILLISECONDS));
			assertEquals(Strings.create("PENDING"), f.bot().status().get(Strings.intern("state")));
			assertEquals(Strings.create(WebhookBot.CONNECTING), f.bot().status().get(Strings.intern("connection")));
			secret(f.engine, "LATE", APP_TOKEN);
			f.api.opened(); socket.connected();
			awaitConnection(f.bot(), WebhookBot.CONNECTED);
			f.engine.configureAdapter(NAME, Maps.of("apiUrl", f.api.url(), "bots", Maps.empty()));
			assertNotNull(socket.closes.poll(8, TimeUnit.SECONDS));
			assertNull(f.api.opens.poll(150, TimeUnit.MILLISECONDS));
		}
	}

	@Test void aSilentConnectionIsReplaced() throws Exception {
		try (SilentSocket silent = new SilentSocket(); Fixture f = new Fixture(socketSpec())) {
			f.adapter.keepaliveMillis = 50;
			f.api.socketUrl = silent.url();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			while (silent.accepted.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
			assertTrue(silent.accepted.size() >= 2, "silent connection was not replaced");
		}
	}

	@Test void bothTransportsShareOneConversation() throws Exception {
		var both = spec().assoc(APP, Strings.create("s/APP")).dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply"))
			.assoc(Strings.intern("agent"), Strings.create("test-agent"));
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(both, socket.url())) {
			SlackAdapterTest.FakeAgent agent = new SlackAdapterTest.FakeAgent(); f.engine.registerAdapter(agent);
			WsContext ctx = socket.connected();
			var dm = Maps.of("type", "message", "channel_type", "im", "channel", "D123", "user", "UALICE", "ts", "3.1", "text", "over http");
			assertEquals(200, f.webhook(payload("http-turn", dm)).status());
			f.api.take(); awaitStatus(f.bot(), "http-turn", WebhookInbox.COMPLETE);
			assertNull(agent.inputs.poll(5, TimeUnit.SECONDS).get(Fields.SESSION_ID));
			ctx.send(envelope("env-dm", payload("socket-turn", dm.assoc(Strings.intern("ts"), Strings.create("3.2")))));
			assertEquals(ack("env-dm"), socket.ack());
			f.api.take();
			assertEquals(Strings.create("session-1"), agent.inputs.poll(5, TimeUnit.SECONDS).get(Fields.SESSION_ID));
			var status = f.bot().status();
			assertNotNull(status.get(Strings.intern("webhookPath")));
			assertEquals(Strings.create(WebhookBot.CONNECTED), status.get(Strings.intern("connection")));
		}
	}

	/** A Socket Mode binding for another workspace of the same app. */
	static AMap<AString, ACell> teamSpec(String team, String channel) {
		return socketSpec().assoc(Strings.intern("teamId"), Strings.create(team)).assoc(Strings.intern("allowChannels"), Vectors.of(channel));
	}
	static AMap<AString, ACell> teamPayload(String team, String id, String channel) {
		return payload(id, event("1.1").assoc(Strings.intern("channel"), Strings.create(channel))).assoc(Strings.intern("team_id"), Strings.create(team))
			.assoc(Strings.intern("authorizations"), Vectors.of(Maps.of("team_id", team, "user_id", "UBOT", "is_bot", true, "is_enterprise_install", false)));
	}
	static AMap<AString, ACell> twoWorkspaces() { return Maps.of("main", socketSpec(), "second", teamSpec("T456", "C456")); }

	@Test void bindingsOfOneAppTokenShareAConnectionAndEachGetsItsWorkspace() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			f.bots(twoWorkspaces());
			awaitConnection(f.bot(), WebhookBot.CONNECTED); awaitConnection(f.adapter.runner("second"), WebhookBot.CONNECTED);
			f.api.opened(); WsContext ctx = socket.connected();
			assertNull(f.api.opens.poll(150, TimeUnit.MILLISECONDS)); assertNull(socket.connections.poll(150, TimeUnit.MILLISECONDS));
			ctx.send(envelope("e-second", teamPayload("T456", "second-event", "C456")));
			assertEquals(ack("e-second"), socket.ack());
			assertEquals(Strings.create("C456"), f.api.take().body().get(Strings.intern("channel")));
			awaitStatus(f.adapter.runner("second"), "second-event", WebhookInbox.COMPLETE);
			ctx.send(envelope("e-main", teamPayload("T123", "main-event", "C123")));
			assertEquals(ack("e-main"), socket.ack());
			assertEquals(Strings.create("C123"), f.api.take().body().get(Strings.intern("channel")));
			awaitStatus(f.bot(), "main-event", WebhookInbox.COMPLETE);
			assertNull(f.bot().inbox().get("second-event")); assertNull(f.adapter.runner("second").inbox().get("main-event"));
			// A workspace with no binding is acknowledged and dropped.
			ctx.send(envelope("e-none", teamPayload("T789", "unbound", "C789")));
			assertEquals(ack("e-none"), socket.ack());
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}

	@Test void ownersShareAConnectionOnlyByHoldingTheSameToken() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			f.api.opened(); WsContext ctx = socket.connected();
			secret(f.engine, OTHER, "TOKEN", TOKEN); secret(f.engine, OTHER, "SAME", APP_TOKEN); secret(f.engine, OTHER, "IMPOSTOR", "another-app-token");
			var create = teamSpec("T456", "C456").dissoc(Strings.intern("user")).assoc(Fields.NAME, Strings.create("shared")).assoc(APP, Strings.create("s/SAME"));
			TestSupport.run(f.engine, OTHER, "v/ops/slack/create", create);
			awaitConnection(f.adapter.runner(OTHER, "shared"), WebhookBot.CONNECTED);
			assertNull(f.api.opens.poll(150, TimeUnit.MILLISECONDS));
			// Claiming main's installation with another token opens a separate connection and receives nothing of main's.
			var claim = socketSpec().dissoc(Strings.intern("user")).assoc(Fields.NAME, Strings.create("claim")).assoc(APP, Strings.create("s/IMPOSTOR"));
			TestSupport.run(f.engine, OTHER, "v/ops/slack/create", claim);
			assertEquals("Bearer another-app-token", f.api.opened().authorization());
			socket.connected();
			ctx.send(envelope("e-main", payload("main-event", event("1.1"))));
			assertEquals(ack("e-main"), socket.ack());
			f.api.take(); awaitStatus(f.bot(), "main-event", WebhookInbox.COMPLETE);
			assertNull(f.adapter.runner(OTHER, "claim").inbox().get("main-event"));
			assertNull(f.adapter.runner(OTHER, "shared").inbox().get("main-event"));
		}
	}

	@Test void allMembersRejoinOneConnectionAfterARefresh() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			f.bots(twoWorkspaces());
			awaitConnection(f.adapter.runner("second"), WebhookBot.CONNECTED);
			f.api.opened();
			socket.connected().send("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}");
			f.api.opened(); WsContext refreshed = socket.connected();
			awaitConnection(f.bot(), WebhookBot.CONNECTED); awaitConnection(f.adapter.runner("second"), WebhookBot.CONNECTED);
			assertNull(f.api.opens.poll(150, TimeUnit.MILLISECONDS)); assertNull(socket.connections.poll(150, TimeUnit.MILLISECONDS));
			refreshed.send(envelope("e-second", teamPayload("T456", "after-refresh", "C456")));
			assertEquals(ack("e-second"), socket.ack());
			f.api.take(); awaitStatus(f.adapter.runner("second"), "after-refresh", WebhookInbox.COMPLETE);
		}
	}

	@Test void removingOneBindingKeepsTheConnectionForTheRest() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			f.bots(twoWorkspaces());
			awaitConnection(f.adapter.runner("second"), WebhookBot.CONNECTED);
			f.api.opened(); WsContext ctx = socket.connected();
			f.configure(socketSpec());
			assertNull(f.adapter.runner("second"));
			assertNull(socket.closes.poll(150, TimeUnit.MILLISECONDS)); assertNull(f.api.opens.poll(150, TimeUnit.MILLISECONDS));
			ctx.send(envelope("e-gone", teamPayload("T456", "gone", "C456")));
			assertEquals(ack("e-gone"), socket.ack());
			ctx.send(envelope("e-main", payload("still-here", event("1.1"))));
			assertEquals(ack("e-main"), socket.ack());
			assertEquals(Strings.create("C123"), f.api.take().body().get(Strings.intern("channel")));
			assertNull(f.api.sends.poll(150, TimeUnit.MILLISECONDS));
		}
	}

	@Test void everyBindingOfTheInstallationReceivesTheEvent() throws Exception {
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(socketSpec(), socket.url())) {
			f.bots(Maps.of("main", socketSpec(), "echo", socketSpec().assoc(Strings.intern("reply"), Strings.create("echo"))));
			awaitConnection(f.adapter.runner("echo"), WebhookBot.CONNECTED);
			socket.connected().send(envelope("e-both", payload("both", event("1.1"))));
			assertEquals(ack("e-both"), socket.ack());
			assertNotNull(f.bot().inbox().get("both")); assertNotNull(f.adapter.runner("echo").inbox().get("both"));
			var replies = Set.of(f.api.take().body().get(Fields.TEXT), f.api.take().body().get(Fields.TEXT));
			assertEquals(Set.of(Strings.create("ack"), Strings.create("echo")), replies);
		}
	}

	@Test void anAgentFromTheModuleTemplateAnswersOverSocketMode() throws Exception {
		var agentSpec = socketSpec().dissoc(Strings.intern("operation")).dissoc(Strings.intern("reply")).assoc(Strings.intern("agent"), Strings.create("slack-templated"));
		try (FakeSocket socket = new FakeSocket(); Fixture f = new Fixture(agentSpec, socket.url())) {
			RequestContext owner = RequestContext.of(OWNER);
			ACell cfg = RT.getIn(f.engine.resolvePath(Strings.create("v/agents/templates/slack"), owner), "agent", "config");
			assertTrue(RT.getIn(cfg, "systemPrompt").toString().contains("via.from"), "the prompt teaches via");
			assertTrue(RT.getIn(cfg, "systemPrompt").toString().contains("plain text"), "the prompt asks for plain text");
			assertEquals(Vectors.of(Strings.create("w/skills"), Strings.create("v/skills/root")), RT.getIn(cfg, "skillsets"));
			// Every tool and revealed skill in the template and the skill hierarchy resolves.
			ACell skill = f.engine.resolvePath(Strings.create("v/skills/adapters/slack"), owner);
			ACell management = f.engine.resolvePath(Strings.create("v/skills/adapters/slack-bot-management"), owner);
			assertEquals(Vectors.of(Strings.create("v/skills/adapters/slack-bot-management")), RT.getIn(skill, "skill", "skills"));
			List<AVector<ACell>> refs = List.of(RT.ensureVector(RT.getIn(cfg, "tools")), RT.ensureVector(RT.getIn(skill, "skill", "tools")),
				RT.ensureVector(RT.getIn(management, "skill", "tools")), RT.ensureVector(RT.getIn(management, "skill", "skills")));
			for (AVector<ACell> paths : refs) for (ACell path : paths) assertNotNull(f.engine.resolvePath((AString) path, owner), "unresolved " + path);
			// An assistant from the template, with the echoing test LLM, answers a DM through the binding.
			TestSupport.run(f.engine, OWNER, "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "slack-templated",
				Fields.CONFIG, Vectors.of(Strings.create("v/agents/templates/slack"), Maps.of("llmOperation", "v/test/ops/llm"))));
			var dm = Maps.of("type", "message", "channel_type", "im", "channel", "D123", "user", "UALICE", "ts", "4.1", "text", "hello template");
			socket.connected().send(envelope("e-agent", payload("agent-turn", dm)));
			assertEquals(ack("e-agent"), socket.ack());
			var reply = f.api.sends.poll(20, TimeUnit.SECONDS);
			assertNotNull(reply, "the templated agent answers through the binding");
			assertEquals(Strings.create("D123"), reply.body().get(Strings.intern("channel")));
			assertTrue(reply.body().get(Fields.TEXT).toString().contains("hello template"), reply.body().toString());
		}
	}

	@Test void appTokensAreSecretReferencesAndSocketUrlsAreChecked() throws Exception {
		assertEquals(BotSpec.INGRESS_REQUIRED, assertThrows(IllegalArgumentException.class,
			() -> BotSpec.parse("main", spec().dissoc(SIGNING), true)).getMessage());
		assertEquals(MessagingSettings.CREDENTIAL.formatted("appToken"), assertThrows(IllegalArgumentException.class,
			() -> BotSpec.parse("main", socketSpec().assoc(APP, Strings.create(APP_TOKEN)), true)).getMessage());
		assertEquals(Set.of("token", "signingSecret"), BotSpec.parse("main", spec(), true).credentialFields());
		assertEquals(Set.of("token", "appToken"), BotSpec.parse("main", socketSpec(), true).credentialFields());
		try (Fixture f = new Fixture(socketSpec(), "ws://example.com/link")) {
			f.api.opened(); f.api.opened();
			awaitConnection(f.bot(), WebhookBot.RETRYING);
			var create = socketSpec().dissoc(Strings.intern("user")).assoc(Fields.NAME, Strings.create("owned"));
			assertThrows(Exception.class, () -> f.run("create", create.assoc(APP, Strings.create(APP_TOKEN))));
			var status = f.run("create", create);
			assertNull(((AMap<?, ?>) status).get(Strings.intern("webhookPath")));
			assertNotNull(((AMap<?, ?>) status).get(Strings.intern("connection")));
		}
		assertEquals(MessagingHttp.INVALID_SOCKET_URL, assertThrows(IllegalArgumentException.class,
			() -> MessagingHttp.socket("ws://example.com/link")).getMessage());
	}
}
