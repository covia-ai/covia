package covia.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Functional tests for the Telegram adapter against a REAL venue engine and a
 * fake Telegram Bot API served in-process ({@link FakeTelegramServer}): the
 * real pengrad client, real long polling, a real agent ({@code llmagent:chat}
 * over the echoing {@code v/test/ops/llm}), and real lattice-persisted
 * sessions — no network, no mocks of our own code.
 */
@Execution(ExecutionMode.SAME_THREAD)
public class TelegramAdapterTest {

	private static final AString OWNER = Strings.create("did:test:telegram:owner");
	private static final AString OTHER = Strings.create("did:test:telegram:other");
	private static final long ALLOWED_ID = 7001L;
	private static final long STRANGER_ID = 7002L;
	private static final String AGENT = "tg-agent";

	private static FakeTelegramServer telegram;
	private static Engine engine;
	private static TelegramAdapter adapter;

	@BeforeAll
	static void boot() throws Exception {
		telegram = new FakeTelegramServer();
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of(
				Strings.create("telegram"), Maps.of(
					TelegramAdapter.K_API_URL, Strings.create(telegram.apiUrl()),
					TelegramAdapter.K_BOTS, Maps.of(
						Strings.create("echo"), botConfig(FakeTelegramServer.TOKEN, OWNER.toString(),
							"agent", AGENT, Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice")))))));
		engine = Engine.createTemp(config);
		adapter = new TelegramAdapter();
		adapter.retryMillis = 200;
		engine.registerAdapter(adapter);
		Engine.addDemoAssets(engine);

		// The agent the 'echo' bot talks to: llmagent over the echoing test LLM.
		run(RequestContext.of(OWNER), "v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, AGENT,
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/ops/llmagent/chat",
				"llmOperation", "v/test/ops/llm",
				"systemPrompt", "Echo the user.")));

		awaitState("echo", BotRunner.State.RUNNING, 10_000);
	}

	@AfterAll
	static void shutdown() {
		engine.close();
		telegram.close();
	}

	// ------------------------------------------------------------ config parsing

	@Test
	public void testSpecValidation() {
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
	}

	@Test
	public void testAdapterConfigValidation() {
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

	// ----------------------------------------------------------------- inbound

	@Test
	public void testBotRoutesChatToAgentWithPersistentSession() throws Exception {
		long chat = 1001L;
		telegram.push(chat, ALLOWED_ID, "alice", "hello from telegram");
		FakeTelegramServer.Sent reply = telegram.awaitSent(15_000);
		assertNotNull(reply, "the bot must answer an allowed user");
		assertEquals(chat, reply.chatId());
		assertTrue(reply.text().contains("hello from telegram"), "echo agent reply: " + reply.text());
		// The echo LLM returns the message it received: the agent sees who is on
		// the other end as structure (Telegram's from/chat), not just the text.
		assertTrue(reply.text().contains("\"username\":\"alice\"") || reply.text().contains("\"username\": \"alice\""),
			"agent message carries the Telegram sender: " + reply.text());
		assertTrue(reply.text().contains("\"channel\":\"telegram\"") || reply.text().contains("\"channel\": \"telegram\""), reply.text());
		assertTrue(reply.text().contains("\"access\":\"allow\"") || reply.text().contains("\"access\": \"allow\""), reply.text());
		assertNull(reply.parseMode(), "plain text by default");
		assertNotNull(reply.replyTo(), "answers reply to the inbound message");

		String sid = readSession("echo", chat);
		assertNotNull(sid, "the chat's agent session is persisted in the owner's workspace");

		telegram.push(chat, ALLOWED_ID, "alice", "second");
		FakeTelegramServer.Sent second = telegram.awaitSent(15_000);
		assertNotNull(second);
		assertTrue(second.text().contains("second"), second.text());
		assertEquals(sid, readSession("echo", chat), "follow-ups continue the same session");
	}

	@Test
	public void testUnauthorisedUserIsRefusedInPrivateChat() throws Exception {
		long chat = 1002L;
		telegram.push(chat, STRANGER_ID, "mallory", "let me in");
		FakeTelegramServer.Sent reply = telegram.awaitSent(10_000);
		assertNotNull(reply);
		assertTrue(reply.text().startsWith("Not authorised"), reply.text());
		assertTrue(reply.text().contains(String.valueOf(STRANGER_ID)), "tells the user their id: " + reply.text());
		assertNull(readSession("echo", chat), "no agent session is created for a refused message");
	}

	@Test
	public void testUsernameAllowListAndCommands() throws Exception {
		long chat = 1003L;
		// 'alice' is allowed by @username even with an unlisted id
		telegram.push(chat, 9999L, "Alice", "/start");
		FakeTelegramServer.Sent greeting = telegram.awaitSent(10_000);
		assertNotNull(greeting);
		assertTrue(greeting.text().contains(AGENT), "default greeting names the agent: " + greeting.text());

		telegram.push(chat, 9999L, "Alice", "/id");
		FakeTelegramServer.Sent id = telegram.awaitSent(10_000);
		assertTrue(id.text().contains("Chat id: " + chat) && id.text().contains("9999"), id.text());

		telegram.push(chat, 9999L, "Alice", "first turn");
		assertNotNull(telegram.awaitSent(15_000));
		String sid1 = readSession("echo", chat);
		assertNotNull(sid1);

		telegram.push(chat, 9999L, "Alice", "/new@" + FakeTelegramServer.BOT_USERNAME);
		FakeTelegramServer.Sent reset = telegram.awaitSent(10_000);
		assertTrue(reset.text().startsWith("Started a new conversation"), reset.text());
		assertNull(readSession("echo", chat), "/new forgets the persisted session");

		telegram.push(chat, 9999L, "Alice", "after reset");
		assertNotNull(telegram.awaitSent(15_000));
		String sid2 = readSession("echo", chat);
		assertNotNull(sid2);
		assertNotEquals(sid1, sid2, "a fresh session after /new");
	}

	@Test
	public void testOperationRouteRepliesWithResult() throws Exception {
		String token = "222:OP-BOT";
		telegram.registerBot(token, "op_bot");
		configureBots(Maps.of(
			Strings.create("echo"), botConfig(FakeTelegramServer.TOKEN, OWNER.toString(), "agent", AGENT,
				Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice"))),
			Strings.create("op"), botConfig(token, OWNER.toString(), "operation", "v/test/ops/echo", null)
				.assoc(BotSpec.K_OPEN, convex.core.data.prim.CVMBool.TRUE)));
		awaitState("op", BotRunner.State.RUNNING, 10_000);
		try {
			// open bot: a stranger is answered; echo returns its input — the Telegram
			// Update as sent, plus bot — which is rendered as JSON for the reply
			telegram.push(token, 2001L, "group", STRANGER_ID, null, "ping the op");
			FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
			assertNotNull(reply);
			assertTrue(reply.text().contains("\"update_id\"") && reply.text().contains("\"message\"")
				&& reply.text().contains("ping the op") && reply.text().contains("\"bot\""), reply.text());
			assertTrue(reply.text().contains("\"message_id\"") && reply.text().contains("\"first_name\""),
				"Telegram snake_case shape, not a translation: " + reply.text());
			assertEquals(BotRunner.State.RUNNING, adapter.runner("echo").state(),
				"unchanged bots keep running across a reconfigure");
		} finally {
			configureBots(Maps.of(
				Strings.create("echo"), botConfig(FakeTelegramServer.TOKEN, OWNER.toString(), "agent", AGENT,
					Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice")))));
			assertNull(adapter.runner("op"), "removed bots are stopped and forgotten");
		}
	}

	@Test
	public void testOperationReplyModes() throws Exception {
		String token = "555:MODES-BOT";
		telegram.registerBot(token, "modes_bot");
		AMap<AString, ACell> echoCfg = echoBotConfig();
		AMap<AString, ACell> base = botConfig(token, OWNER.toString(), "operation", "v/test/ops/echo", null)
			.assoc(BotSpec.K_OPEN, convex.core.data.prim.CVMBool.TRUE);
		try {
			// reply:false — the operation runs (as a Job), nothing is sent
			configureBots(Maps.of(Strings.create("echo"), echoCfg,
				Strings.create("modes"), base.assoc(BotSpec.K_REPLY, convex.core.data.prim.CVMBool.FALSE)));
			awaitState("modes", BotRunner.State.RUNNING, 10_000);
			RequestContext owner = RequestContext.of(OWNER);
			Set<Blob> jobsBefore = new HashSet<>(engine.jobs().getJobs(owner).keySet());
			telegram.push(token, 6001L, "private", STRANGER_ID, null, "quiet");
			Job quiet = awaitNewJob(owner, jobsBefore, 10_000);
			quiet.awaitResult(10_000);
			await(() -> adapter.runner("modes").isChatIdle(6001L), 10_000,
				() -> "operation handler did not finish");
			assertNull(telegram.awaitSent(token, 0), "reply:false must send nothing");

			// reply:"…" — a fixed acknowledgement instead of the result
			configureBots(Maps.of(Strings.create("echo"), echoCfg,
				Strings.create("modes"), base.assoc(BotSpec.K_REPLY, Strings.create("Done."))));
			awaitState("modes", BotRunner.State.RUNNING, 10_000);
			telegram.push(token, 6001L, "private", STRANGER_ID, null, "acked");
			FakeTelegramServer.Sent ack = telegram.awaitSent(token, 10_000);
			assertNotNull(ack);
			assertEquals("Done.", ack.text());
		} finally {
			configureBots(Maps.of(Strings.create("echo"), echoCfg));
		}
	}

	@Test
	public void testAgentChatIsAJobAndRecoversFromAStaleSession() throws Exception {
		long chat = 1005L;
		// A persisted session id the agent has never heard of (e.g. the agent was recreated).
		adapter.state().write("config/echo/sessions/" + chat,
			Strings.create("deadbeefdeadbeefdeadbeefdeadbeef"));
		long jobsBefore = engine.jobs().getJobs(RequestContext.of(OWNER)).count();
		telegram.push(chat, ALLOWED_ID, "alice", "are you there");
		FakeTelegramServer.Sent reply = telegram.awaitSent(15_000);
		assertNotNull(reply, "the bot must recover by starting a fresh session");
		assertTrue(reply.text().contains("are you there"), reply.text());
		String sid = readSession("echo", chat);
		assertNotNull(sid);
		assertNotEquals("deadbeefdeadbeefdeadbeefdeadbeef", sid, "the stale session is replaced");
		await(() -> engine.jobs().getJobs(RequestContext.of(OWNER)).count() >= jobsBefore + 1, 10_000,
			() -> "the chat turn is recorded as a Job in the bot user's job index");
	}

	@Test
	public void testCallOperationSendsMediaAndRefusesManagedMethods() throws Exception {
		ACell out = run(RequestContext.of(OWNER), "v/ops/telegram/call", Maps.of(
			"method", "sendPhoto",
			"params", Maps.of("chat_id", CVMLong.create(3005L), "photo", "https://example.org/cat.jpg",
				"caption", "a cat", "disable_notification", convex.core.data.prim.CVMBool.TRUE)));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
		assertNotNull(sent);
		assertEquals("sendPhoto", sent.method());
		assertEquals("https://example.org/cat.jpg", sent.form().get("photo"));
		assertEquals("a cat", sent.text());
		assertTrue(sent.silent());
		assertEquals(Strings.create("a cat"), RT.getIn(out, "caption"), "Telegram's Message result: " + out);

		// A method answering `true` comes back as true
		ACell ack = run(RequestContext.of(OWNER), "v/ops/telegram/call", Maps.of(
			"method", "answerCallbackQuery", "params", Maps.of("callback_query_id", "cq1", "text", "Done")));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, ack);

		// The venue's own update loop owns getUpdates & co.
		Job managed = engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "getUpdates", "params", Maps.empty()), RequestContext.of(OWNER));
		try { managed.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, managed.getStatus());
		assertTrue(String.valueOf(managed.getErrorMessage()).contains("managed"), managed.getErrorMessage());

		// Other users are denied
		Job denied = engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"bot", "echo", "method", "sendPhoto", "params", Maps.of("chat_id", 1, "photo", "x")), RequestContext.of(OTHER));
		try { denied.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(String.valueOf(denied.getErrorMessage()).contains("denied"), denied.getErrorMessage());
	}

	@Test
	public void testOperationBotReceivesPhotosAndCallbackQueriesVerbatim() throws Exception {
		String token = "666:UPDATES-BOT";
		telegram.registerBot(token, "updates_bot");
		AMap<AString, ACell> echoCfg = echoBotConfig();
		configureBots(Maps.of(Strings.create("echo"), echoCfg,
			Strings.create("upd"), botConfig(token, OWNER.toString(), "operation", "v/test/ops/echo", null)
				.assoc(BotSpec.K_OPEN, convex.core.data.prim.CVMBool.TRUE)));
		awaitState("upd", BotRunner.State.RUNNING, 10_000);
		try {
			// A photo message: no text, a caption, photo sizes with file_ids — all passed through
			telegram.pushPhoto(token, 7001L, STRANGER_ID, "pat", "look at this");
			FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
			assertNotNull(reply, "operation bots receive non-text messages");
			assertTrue(reply.text().contains("\"photo\"") && reply.text().contains("AgACbig")
				&& reply.text().contains("look at this"), reply.text());

			// A button tap: callback_query, sender = the tapper, chat = the message's chat
			telegram.pushCallback(token, 7001L, STRANGER_ID, "pat", "approve:42");
			FakeTelegramServer.Sent cb = telegram.awaitSent(token, 15_000);
			assertNotNull(cb, "operation bots receive callback queries");
			assertTrue(cb.text().contains("\"callback_query\"") && cb.text().contains("approve:42"), cb.text());
			assertEquals(7001L, cb.chatId(), "replies go to the chat the tapped message is in");
		} finally {
			configureBots(Maps.of(Strings.create("echo"), echoCfg));
		}
	}

	@Test
	public void testAgentBotIgnoresNonTextInPrivateWithNotice() throws Exception {
		telegram.pushPhoto(FakeTelegramServer.TOKEN, 1006L, ALLOWED_ID, "alice", null);
		FakeTelegramServer.Sent notice = telegram.awaitSent(10_000);
		assertNotNull(notice);
		assertTrue(notice.text().startsWith("I can only read text"), notice.text());
		assertNull(readSession("echo", 1006L), "no conversation turn for a photo without caption");
	}

	@Test
	public void testNonAsciiSurvivesBothDirections() throws Exception {
		String text = "em dash — ✅ ❌ £ é 日本語 🚀";
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of("chat_id", CVMLong.create(3006L), "text", text));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
		assertNotNull(sent);
		assertEquals(text, sent.text(), "outbound text must reach Telegram byte-for-byte as UTF-8");

		telegram.push(1007L, ALLOWED_ID, "alice", "inbound " + text);
		FakeTelegramServer.Sent reply = telegram.awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains(text), "inbound text must survive the round trip: " + reply.text());
	}

	@Test
	public void testCreateAndDeleteRuntimeBots() throws Exception {
		String token = "777:CREATED-BOT";
		telegram.registerBot(token, "created_bot");
		run(RequestContext.of(OWNER), "v/ops/secret/set", Maps.of("name", "TG_CREATED", "value", token));

		// Validation first: literal token, explicit user, no handler
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", token, "agent", AGENT), RequestContext.of(OWNER)), "secret reference");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT, "user", OTHER), RequestContext.of(OWNER)), "implicit");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED"), RequestContext.of(OWNER)), "exactly one of agent");

		ACell created = run(RequestContext.of(OWNER), "v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT,
			"allow", Vectors.of(CVMLong.create(ALLOWED_ID))));
		assertEquals(Strings.create("runtime"), RT.getIn(created, BotRunner.K_MANAGED));
		assertEquals(OWNER, RT.getIn(created, BotSpec.K_USER), "a created bot acts as its creator");
		BotRunner mine = adapter.runner(OWNER, "mine");
		assertNotNull(mine);
		await(() -> mine.state() == BotRunner.State.RUNNING, 10_000, () -> "created bot did not start: " + mine.error());
		assertEquals("created_bot", mine.username());

		// Recorded in venue-private adapter state (settings only — no user, no literal token).
		String recordPath = adapter.runtimeBotPath(OWNER, "mine");
		ACell record = engine.resolvePath(Strings.create(recordPath), engine.venueContext());
		assertNotNull(record, "registry record");
		assertEquals(Strings.create("s/TG_CREATED"), RT.getIn(record, BotSpec.K_TOKEN));
		assertNull(RT.getIn(record, BotSpec.K_USER));
		assertNull(engine.resolvePath(Strings.create(recordPath), RequestContext.of(OWNER)),
			"user association does not make adapter-owned state part of that user's workspace");

		// Same name again is refused; another user may use the name; bots lists it as runtime
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT), RequestContext.of(OWNER)), "already have");
		ACell listing = run(RequestContext.of(OWNER), "v/ops/telegram/bots", Maps.empty());
		assertTrue(listing.toString().contains("runtime") && listing.toString().contains("\"mine\""), listing.toString());

		// It works: an inbound message reaches the agent through it, and it can send
		telegram.push(token, 8001L, "private", ALLOWED_ID, "alice", "via created bot");
		FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("via created bot"), reply.text());
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of("bot", "mine", "chat_id", CVMLong.create(8001L), "text", "out"));
		assertEquals("out", telegram.awaitSent(token, 10_000).text());

		// Simulated restart: forget the live runner, re-arm from the lattice registry
		adapter.forgetForTest(OWNER, "mine");
		assertNull(adapter.runner(OWNER, "mine"));
		adapter.rearmForTest();
		BotRunner rearmed = adapter.runner(OWNER, "mine");
		assertNotNull(rearmed, "a created bot is re-armed from the venue adapter workspace at install");
		await(() -> rearmed.state() == BotRunner.State.RUNNING, 10_000, () -> "re-armed bot did not start: " + rearmed.error());

		// Delete: stops it, removes the record and sessions; config bots are refused
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/delete", Maps.of("name", "echo"),
			RequestContext.of(OWNER)), "venue config");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/delete", Maps.of("name", "nope"),
			RequestContext.of(OWNER)), "no Telegram bot named");
		ACell deleted = run(RequestContext.of(OWNER), "v/ops/telegram/delete", Maps.of("name", "mine"));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(deleted, TelegramAdapter.K_DELETED));
		assertNull(adapter.runner(OWNER, "mine"));
		assertEquals(BotRunner.State.STOPPED, rearmed.state());
		assertNull(engine.resolvePath(Strings.create(recordPath), engine.venueContext()), "record removed");
		assertNull(engine.resolvePath(Strings.create(adapter.state().path(
			adapter.userStatePath(OWNER, "sessions/mine/8001"))), engine.venueContext()), "sessions removed");
	}

	@Test
	public void testLegacyRuntimeBotRegistryMigratesAndDeleteCleansBothRoots() throws Exception {
		String token="779:LEGACY-BOT";
		telegram.registerBot(token,"legacy_bot");
		run(RequestContext.of(OTHER),"v/ops/secret/set",Maps.of("name","TG_LEGACY","value",token));
		AMap<AString,ACell> settings=Maps.of("token","s/TG_LEGACY","operation","v/test/ops/echo","open",true);
		run(RequestContext.of(OTHER),"v/ops/covia/write",Maps.of(
			Fields.PATH,TelegramAdapter.LEGACY_REGISTRY_PATH+"/legacy",Fields.VALUE,settings));

		adapter.rearmForTest();
		BotRunner legacy=adapter.runner(OTHER,"legacy");
		assertNotNull(legacy);
		await(()->legacy.state()==BotRunner.State.RUNNING,10_000,()->"legacy bot did not start: "+legacy.error());
		assertEquals(settings,engine.resolvePath(Strings.create(adapter.runtimeBotPath(OTHER,"legacy")),engine.venueContext()),
			"legacy record is copied into the canonical venue-private adapter workspace");

		run(RequestContext.of(OTHER),"v/ops/telegram/delete",Maps.of("name","legacy"));
		assertNull(engine.resolvePath(Strings.create(adapter.runtimeBotPath(OTHER,"legacy")),engine.venueContext()));
		assertNull(RT.getIn(run(RequestContext.of(OTHER),"v/ops/covia/read",Maps.of(
			Fields.PATH,TelegramAdapter.LEGACY_REGISTRY_PATH+"/legacy")),Fields.VALUE));
	}

	private static void assertFailsWith(Job job, String fragment) {
		try { job.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, job.getStatus(), "expected failure mentioning '" + fragment + "'");
		assertTrue(String.valueOf(job.getErrorMessage()).contains(fragment),
			"expected '" + fragment + "' in: " + job.getErrorMessage());
	}

	@Test
	public void testTelegramAgentTemplate() throws Exception {
		ACell template = engine.resolvePath(Strings.create("v/agents/templates/telegram"), RequestContext.of(OWNER));
		assertNotNull(template, "the module ships an agent template");
		assertEquals(template, engine.resolvePath(Strings.create("v/adapters/telegram/templates/telegram"), RequestContext.of(OWNER)),
			"mirrored in the adapter's own subtree");
		ACell cfg = RT.getIn(template, "agent", "config");
		assertTrue(RT.getIn(cfg, "systemPrompt").toString().contains("via.from"), "the prompt teaches via");
		assertEquals(Vectors.of((ACell) Strings.create("w/skills"), Strings.create("v/skills/root")),
			RT.getIn(cfg, "skillsets"), "the template discovers personal and venue skillsets");
		assertNull(RT.getIn(cfg, "skills"), "directories must not be declared as individual skills");
		assertTrue(RT.getIn(cfg, "tools").toString().contains("v/ops/memory"), "memory is a base tool");
		assertNotNull(RT.getIn(cfg, "context"), "memory is pinned into context");

		// An assistant from the template, provider composed at create time (the echoing test LLM)
		run(RequestContext.of(OWNER), "v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "tg-templated",
			Fields.CONFIG, Vectors.of(Strings.create("v/agents/templates/telegram"),
				Maps.of("llmOperation", "v/test/ops/llm"))));
		ACell agentContext = run(RequestContext.of(OWNER), "v/ops/agent/context", Maps.of(
			Fields.AGENT_ID, "tg-templated", Fields.MESSAGE, "hello"));
		String messages = RT.getIn(agentContext, Fields.MESSAGES).toString();
		assertTrue(messages.contains("[Skills]"), messages);
		assertTrue(messages.contains("adapters"),
			"the venue adapter skill router must be discoverable: " + messages);
		String token = "888:TEMPLATE-BOT";
		telegram.registerBot(token, "templated_bot");
		run(RequestContext.of(OWNER), "v/ops/secret/set", Maps.of("name", "TG_TEMPLATE", "value", token));
		run(RequestContext.of(OWNER), "v/ops/telegram/create", Maps.of(
			"name", "templated", "token", "s/TG_TEMPLATE", "agent", "tg-templated",
			"allow", Vectors.of(CVMLong.create(ALLOWED_ID))));
		try {
			BotRunner r = adapter.runner(OWNER, "templated");
			await(() -> r != null && r.state() == BotRunner.State.RUNNING, 10_000, () -> "templated bot did not start");
			telegram.push(token, 9001L, "private", ALLOWED_ID, "alice", "hello template");
			FakeTelegramServer.Sent reply = telegram.awaitSent(token, 20_000);
			assertNotNull(reply, "an agent built from the template answers through its bot");
			assertTrue(reply.text().contains("hello template"), reply.text());
		} finally {
			run(RequestContext.of(OWNER), "v/ops/telegram/delete", Maps.of("name", "templated"));
		}
	}

	@Test
	public void testDisabledAdapterIsOfflineUntilReenabled() throws Exception {
		long chat = 1004L;
		BotRunner runner = adapter.runner("echo");
		engine.disableAdapter("telegram");
		try {
			await(runner::isOffline, 2_000,
				() -> "Telegram poll loop did not park after adapter disablement");
			telegram.push(chat, ALLOWED_ID, "alice", "while disabled");
			assertNull(telegram.awaitSent(0), "a disabled adapter must not act on Telegram traffic");
		} finally {
			engine.enableAdapter("telegram");
		}
		// Nothing was confirmed while offline, so Telegram redelivers the backlog first.
		FakeTelegramServer.Sent backlog = telegram.awaitSent(15_000);
		assertNotNull(backlog, "the message received while disabled is answered once re-enabled");
		assertTrue(backlog.text().contains("while disabled"), backlog.text());
		telegram.push(chat, ALLOWED_ID, "alice", "after enable");
		FakeTelegramServer.Sent reply = telegram.awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("after enable"), reply.text());
	}

	// ---------------------------------------------------------------- outbound

	@Test
	public void testSendOperationByOwner() throws Exception {
		ACell out = run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(3001L),
			"text", "hello there",
			"disable_notification", convex.core.data.prim.CVMBool.TRUE,
			"reply_parameters", Maps.of("message_id", 77),
			"reply_markup", Maps.of("inline_keyboard", Vectors.of(Vectors.of(
				Maps.of("text", "Yes", "callback_data", "yes"), Maps.of("text", "No", "callback_data", "no"))))));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
		assertNotNull(sent);
		assertEquals("hello there", sent.text());
		assertEquals(3001L, sent.chatId());
		assertTrue(sent.silent());
		assertEquals(77, sent.replyTo(), "reply_parameters passed through as JSON");
		assertTrue(sent.form().get("reply_markup").contains("callback_data"), "reply_markup passed through as JSON: " + sent.form());
		// The result is Telegram's Message, not a translation (single owned bot was the default)
		assertEquals(CVMLong.create(sent.messageId()), RT.getIn(out, "message_id"));
		assertEquals(CVMLong.create(3001L), RT.getIn(out, "chat", "id"));
	}

	@Test
	public void testSendIsDeniedToOtherUsers() {
		Job job = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_BOT, "echo",
			"chat_id", CVMLong.create(3002L),
			"text", "sneaky"), RequestContext.of(OTHER));
		try {
			job.awaitResult(10_000);
		} catch (Exception ignored) {
			// failure surfaces on the job
		}
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(String.valueOf(job.getErrorMessage()).contains("denied"), job.getErrorMessage());
		assertTrue(telegram.drainSent().isEmpty(), "nothing must reach Telegram");
	}

	@Test
	public void testSendUnknownBotAndNoDefaultBot() {
		Job unknown = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_BOT, "nope", "chat_id", CVMLong.create(1), "text", "x"),
			RequestContext.of(OWNER));
		try { unknown.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, unknown.getStatus());
		assertTrue(String.valueOf(unknown.getErrorMessage()).contains("Unknown Telegram bot"), unknown.getErrorMessage());

		Job none = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(1), "text", "x"), RequestContext.of(OTHER));
		try { none.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, none.getStatus());
		assertTrue(String.valueOf(none.getErrorMessage()).contains("No Telegram bot is configured"), none.getErrorMessage());
	}

	@Test
	public void testSendFallsBackToPlainTextWhenMarkupRejected() throws Exception {
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(3003L),
			"text", "*unbalanced " + FakeTelegramServer.BAD_MARKUP,
			"parse_mode", "Markdown"));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
		assertNotNull(sent);
		assertNull(sent.parseMode(), "resent as plain text after Telegram rejected the markup");
		assertTrue(sent.text().contains(FakeTelegramServer.BAD_MARKUP));
	}

	@Test
	public void testSendSplitsLongText() throws Exception {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 700; i++) sb.append("line ").append(i).append(" of a rather long message\n");
		String text = sb.toString();
		assertTrue(text.length() > 2 * BotRunner.MAX_MESSAGE_LENGTH);
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(3004L), "text", text));
		List<String> expected = BotRunner.split(text, BotRunner.MAX_MESSAGE_LENGTH);
		List<String> actual = new java.util.ArrayList<>(expected.size());
		for (int i = 0; i < expected.size(); i++) {
			FakeTelegramServer.Sent sent = telegram.awaitSent(i == 0 ? 10_000 : 0);
			assertNotNull(sent, "missing chunk " + i);
			actual.add(sent.text());
		}
		assertEquals(expected, actual, "send every split chunk exactly once and in order");
		assertNull(telegram.awaitSent(0), "send no extra chunks");
	}

	@Test
	public void testSplitPrefersLineBreaks() {
		String text = "aaaa\nbbbb\ncccc";
		assertEquals(java.util.List.of("aaaa\nbbbb", "cccc"), BotRunner.split(text, 10));
		assertEquals(java.util.List.of("abcdefghij", "klm"), BotRunner.split("abcdefghijklm", 10));
		assertEquals(java.util.List.of("short"), BotRunner.split("short", 10));
	}

	@Test
	public void testRenderText() {
		assertEquals("plain", TelegramAdapter.renderText(Strings.create("plain")));
		assertEquals("from text", TelegramAdapter.renderText(Maps.of("text", "from text", "other", 1)));
		assertEquals("from response", TelegramAdapter.renderText(Maps.of("response", "from response")));
		String json = TelegramAdapter.renderText(Maps.of("count", 3));
		assertTrue(json.contains("\"count\"") && json.contains("3"), json);
		assertNull(TelegramAdapter.renderText(null));
	}

	// ------------------------------------------------------------------ status

	@Test
	public void testBotsListsOnlyTheCallersBots() throws Exception {
		ACell mine = run(RequestContext.of(OWNER), "v/ops/telegram/bots", Maps.empty());
		AVector<ACell> bots = RT.ensureVector(RT.getIn(mine, TelegramAdapter.K_BOTS));
		assertNotNull(bots);
		boolean found = false;
		for (long i = 0; i < bots.count(); i++) {
			ACell b = bots.get(i);
			if (Strings.create("echo").equals(RT.getIn(b, Fields.NAME))) {
				found = true;
				assertEquals(Strings.create("RUNNING"), RT.getIn(b, BotRunner.K_STATE));
				assertEquals(Strings.create(FakeTelegramServer.BOT_USERNAME), RT.getIn(b, BotRunner.K_USERNAME));
				assertEquals(Strings.create("agent " + AGENT), RT.getIn(b, BotRunner.K_TARGET));
				assertNull(RT.getIn(b, BotSpec.K_TOKEN), "tokens are never listed");
			}
		}
		assertTrue(found, "owner sees their bot: " + mine);

		ACell theirs = run(RequestContext.of(OTHER), "v/ops/telegram/bots", Maps.empty());
		assertEquals(0L, RT.ensureVector(RT.getIn(theirs, TelegramAdapter.K_BOTS)).count(),
			"another user sees no bots they don't own");

		ACell all = run(engine.venueContext(), "v/ops/telegram/bots", Maps.empty());
		assertTrue(RT.ensureVector(RT.getIn(all, TelegramAdapter.K_BOTS)).count() >= 1, "the venue sees every bot");
	}

	@Test
	public void testPendingBotStartsWhenSecretIsProvisioned() throws Exception {
		String token = "333:LATE-SECRET";
		telegram.registerBot(token, "late_bot");
		AMap<AString, ACell> echoCfg = botConfig(FakeTelegramServer.TOKEN, OWNER.toString(), "agent", AGENT,
			Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice")));
		configureBots(Maps.of(
			Strings.create("echo"), echoCfg,
			Strings.create("late"), botConfig("s/TG_LATE_TOKEN", OWNER.toString(), "agent", AGENT, null)));
		try {
			awaitState("late", BotRunner.State.PENDING, 10_000);
			assertTrue(adapter.runner("late").error().contains("not found"), adapter.runner("late").error());
			assertEquals(0, telegram.getMeCalls(token), "no token, no Telegram call");

			ACell status = run(RequestContext.of(OWNER), "v/ops/telegram/bots", Maps.empty());
			assertTrue(status.toString().contains("PENDING"), status.toString());

			// Provisioning the secret in the bot user's store brings it up on the next retry.
			run(RequestContext.of(OWNER), "v/ops/secret/set", Maps.of("name", "TG_LATE_TOKEN", "value", token));
			awaitState("late", BotRunner.State.RUNNING, 10_000);
			assertEquals("late_bot", adapter.runner("late").username());
			assertNull(adapter.runner("late").error());
		} finally {
			configureBots(Maps.of(Strings.create("echo"), echoCfg));
		}
	}

	@Test
	public void testBadTokenIsPendingWithTelegramError() throws Exception {
		AMap<AString, ACell> echoCfg = botConfig(FakeTelegramServer.TOKEN, OWNER.toString(), "agent", AGENT,
			Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice")));
		configureBots(Maps.of(
			Strings.create("echo"), echoCfg,
			Strings.create("bad"), botConfig("999:NOT-REGISTERED", OWNER.toString(), "agent", AGENT, null)));
		try {
			awaitState("bad", BotRunner.State.PENDING, 10_000);
			assertTrue(adapter.runner("bad").error().contains("401"), adapter.runner("bad").error());
		} finally {
			configureBots(Maps.of(Strings.create("echo"), echoCfg));
		}
	}

	// ----------------------------------------------------------------- helpers

	private static AMap<AString, ACell> botConfig(String token, String user, String targetKey, String target,
			AVector<ACell> allow) {
		AMap<AString, ACell> m = Maps.of(
			BotSpec.K_TOKEN, Strings.create(token),
			BotSpec.K_USER, Strings.create(user),
			Strings.create(targetKey), Strings.create(target));
		if (allow != null) m = m.assoc(BotSpec.K_ALLOW, allow);
		return m;
	}

	private static AMap<AString, ACell> echoBotConfig() {
		return botConfig(FakeTelegramServer.TOKEN, OWNER.toString(), "agent", AGENT,
			Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice")));
	}

	private static void configureBots(AMap<AString, ACell> bots) {
		engine.configureAdapter("telegram", Maps.of(
			TelegramAdapter.K_API_URL, Strings.create(telegram.apiUrl()),
			TelegramAdapter.K_BOTS, bots));
	}

	private static ACell run(RequestContext ctx, String op, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		ACell result = job.awaitResult(30_000);
		assertEquals(Status.COMPLETE, job.getStatus(), op + ": " + job.getErrorMessage());
		return result;
	}

	private static String readSession(String bot, long chatId) {
		BotRunner runner=adapter.runner(OWNER,bot);
		if(runner==null)runner=adapter.runner(bot);
		if(runner==null)return null;
		ACell v=engine.resolvePath(Strings.create(runner.sessionsPath()+"/"+chatId),engine.venueContext());
		return (v == null) ? null : v.toString();
	}

	private static Job awaitNewJob(RequestContext ctx, Set<Blob> known, long timeoutMs)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() <= deadline) {
			for (Blob id : engine.jobs().getJobs(ctx).keySet()) {
				if (!known.contains(id)) return engine.jobs().getJob(id, ctx);
			}
			Thread.sleep(10);
		}
		throw new AssertionError("inbound message did not create a Job");
	}

	private static void awaitState(String bot, BotRunner.State state, long timeoutMs) throws InterruptedException {
		await(() -> adapter.runner(bot) != null && adapter.runner(bot).state() == state, timeoutMs,
			() -> "bot '" + bot + "' did not reach " + state + " (now "
				+ (adapter.runner(bot) == null ? "absent" : adapter.runner(bot).state() + " / " + adapter.runner(bot).error()) + ")");
	}

	private static void await(BooleanSupplier condition, long timeoutMs, java.util.function.Supplier<String> failure)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) throw new AssertionError(failure.get());
			Thread.sleep(50);
		}
	}
}
