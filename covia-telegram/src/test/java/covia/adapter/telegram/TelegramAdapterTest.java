package covia.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
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
			// open bot: a stranger is answered; echo returns the inbound record, whose
			// text field becomes the reply
			telegram.push(token, 2001L, "group", STRANGER_ID, null, "ping the op");
			FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
			assertNotNull(reply);
			assertEquals("ping the op", reply.text());
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
			long jobsBefore = engine.jobs().getJobs(RequestContext.of(OWNER)).count();
			telegram.push(token, 6001L, "private", STRANGER_ID, null, "quiet");
			assertNull(telegram.awaitSent(token, 2_000), "reply:false must send nothing");
			await(() -> engine.jobs().getJobs(RequestContext.of(OWNER)).count() == jobsBefore + 1, 10_000,
				() -> "each inbound message is one Job in the bot user's job index");

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
		run(RequestContext.of(OWNER), "v/ops/covia/write", Maps.of(
			Fields.PATH, "w/telegram/echo/sessions/" + chat,
			Fields.VALUE, "deadbeefdeadbeefdeadbeefdeadbeef"));
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
	public void testDisabledAdapterIsOfflineUntilReenabled() throws Exception {
		long chat = 1004L;
		engine.disableAdapter("telegram");
		try {
			telegram.push(chat, ALLOWED_ID, "alice", "while disabled");
			assertNull(telegram.awaitSent(2_500), "a disabled adapter must not act on Telegram traffic");
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
			TelegramAdapter.K_CHAT_ID, CVMLong.create(3001L),
			Fields.TEXT, "hello there",
			TelegramAdapter.K_SILENT, convex.core.data.prim.CVMBool.TRUE));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
		assertNotNull(sent);
		assertEquals("hello there", sent.text());
		assertEquals(3001L, sent.chatId());
		assertTrue(sent.silent());
		assertEquals(Strings.create("echo"), RT.getIn(out, TelegramAdapter.K_BOT), "single owned bot is the default");
		assertEquals(CVMLong.create(sent.messageId()), RT.getIn(out, Fields.MESSAGE_ID));
	}

	@Test
	public void testSendIsDeniedToOtherUsers() {
		Job job = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_BOT, "echo",
			TelegramAdapter.K_CHAT_ID, CVMLong.create(3002L),
			Fields.TEXT, "sneaky"), RequestContext.of(OTHER));
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
			TelegramAdapter.K_BOT, "nope", TelegramAdapter.K_CHAT_ID, CVMLong.create(1), Fields.TEXT, "x"),
			RequestContext.of(OWNER));
		try { unknown.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, unknown.getStatus());
		assertTrue(String.valueOf(unknown.getErrorMessage()).contains("Unknown Telegram bot"), unknown.getErrorMessage());

		Job none = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_CHAT_ID, CVMLong.create(1), Fields.TEXT, "x"), RequestContext.of(OTHER));
		try { none.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, none.getStatus());
		assertTrue(String.valueOf(none.getErrorMessage()).contains("No Telegram bot is configured"), none.getErrorMessage());
	}

	@Test
	public void testSendFallsBackToPlainTextWhenMarkupRejected() throws Exception {
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_CHAT_ID, CVMLong.create(3003L),
			Fields.TEXT, "*unbalanced " + FakeTelegramServer.BAD_MARKUP,
			BotSpec.K_PARSE_MODE, "Markdown"));
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
			TelegramAdapter.K_CHAT_ID, CVMLong.create(3004L), Fields.TEXT, text));
		StringBuilder received = new StringBuilder();
		int chunks = 0;
		FakeTelegramServer.Sent s;
		while ((s = telegram.awaitSent(chunks == 0 ? 10_000 : 2_000)) != null) {
			assertTrue(s.text().length() <= BotRunner.MAX_MESSAGE_LENGTH, "chunk within Telegram's limit");
			received.append(s.text()).append('\n');
			chunks++;
		}
		assertTrue(chunks >= 3, "split into several messages, got " + chunks);
		assertTrue(received.toString().contains("line 699 of a rather long message"), "nothing lost at the tail");
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
		ACell read = run(RequestContext.of(OWNER), "v/ops/covia/read",
			Maps.of(Fields.PATH, "w/telegram/" + bot + "/sessions/" + chatId));
		ACell v = RT.getIn(read, Fields.VALUE);
		return (v == null) ? null : v.toString();
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
