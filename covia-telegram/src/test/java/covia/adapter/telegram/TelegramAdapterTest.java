package covia.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.ResourceLockTarget;

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
import covia.adapter.messaging.AMessagingAdapter;
import covia.adapter.messaging.MessagingText;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Agent;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Functional tests for the Telegram adapter against a REAL venue engine and a
 * fake Telegram Bot API served in-process ({@link FakeTelegramServer}): the
 * real pengrad client, real long polling, a real agent ({@code llmagent:chat}
 * over the echoing {@code v/test/ops/llm}), and real lattice-persisted
 * sessions — no network, no mocks of our own code.
 *
 * <p>The tests run at once on one venue and one fake. Each has its own users,
 * agents and bots, so it shares no sessions, jobs, files or Telegram traffic
 * with another. Operator-declared bots are added to and removed from the
 * adapter's configuration one at a time ({@link #declare}), which leaves every
 * other test's bots running.</p>
 */
@ResourceLock(value = TelegramAdapterTest.ADAPTER, mode = ResourceAccessMode.READ, target = ResourceLockTarget.CHILDREN)
public class TelegramAdapterTest {

	/** Disabling the adapter stops every test's bots, so that test runs alone. */
	static final String ADAPTER = "covia.telegram.adapter";
	/** Re-arming restarts every user's forgotten runtime bots, so tests that forget one take turns. */
	static final String REARM = "covia.telegram.rearm";

	private static final long ALLOWED_ID = 7001L;
	private static final long STRANGER_ID = 7002L;
	private static final String AGENT = "tg-agent";

	private static final AtomicInteger IDS = new AtomicInteger();
	/** Every test's operator-declared bots: the adapter's configuration holds them all at once. */
	private static AMap<AString, ACell> declaredBots = Maps.empty();
	private static final Object DECLARE = new Object();

	private static FakeTelegramServer telegram;
	private static Engine engine;
	private static TelegramAdapter adapter;

	private final int id = IDS.incrementAndGet();
	/** This test's own user, so its bots, sessions, jobs and files are its own. */
	private final AString owner = Strings.create("did:test:telegram:owner-" + id);
	/** A user with no bot unless this test makes one. */
	private final AString other = Strings.create("did:test:telegram:other-" + id);
	/** This test's echo bot: operator-declared, with a token of its own on the fake. */
	private final String echo = "echo-" + id;
	private final String echoToken = id + ":ECHO-TOKEN";
	private final String echoUsername = "echo_" + id + "_bot";
	/** The bots this test declared, removed after it. */
	private final Set<String> declared = new HashSet<>();
	private boolean agentCreated;

	@BeforeAll
	static void boot() throws Exception {
		telegram = new FakeTelegramServer();
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			// Strangers on an open bot are anonymous: the operator lets them invoke the echo operation.
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.CAPS, Vectors.of(convex.auth.ucan.Capability.create(
				Strings.create("v/test/ops/echo"), Strings.create("invoke"))))),
			Config.ADAPTERS, Maps.of(
				Strings.create("telegram"), Maps.of(TelegramAdapter.K_API_URL, Strings.create(telegram.apiUrl()))));
		engine = Engine.createTemp(config);
		adapter = new TelegramAdapter();
		adapter.retryMillis = 200;
		engine.registerAdapter(adapter);
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void shutdown() {
		engine.close();
		telegram.close();
	}

	@BeforeEach
	void registerEchoBot() {
		telegram.registerBot(echoToken, echoUsername);
	}

	@AfterEach
	void removeDeclaredBots() {
		synchronized (DECLARE) {
			AMap<AString, ACell> bots = declaredBots;
			for (String name : declared) bots = bots.dissoc(Strings.create(name));
			if (bots.count() != declaredBots.count()) configureBots(bots);
		}
	}

	// ------------------------------------------------------------ config parsing

	@Test
	public void aBotThatIsNotRunningSaysWhetherItIsStillStarting() {
		// A runner not yet started is STARTING: callers may simply retry shortly.
		BotRunner idle = new BotRunner(adapter, BotSpec.parse("idle", botConfig(echoToken,
			owner.toString(), "operation", "v/test/ops/echo", Vectors.of(CVMLong.create(ALLOWED_ID))), false),
			telegram.apiUrl(), covia.adapter.messaging.MessagingBot.Managed.CONFIG);
		assertThrows(covia.exception.StillStartingException.class, () -> idle.call("getMe", java.util.Map.of()));

		// A stopped bot is unavailable, and not about to become available.
		idle.stop();
		covia.exception.UnavailableException stopped = assertThrows(covia.exception.UnavailableException.class,
			() -> idle.call("getMe", java.util.Map.of()));
		assertFalse(stopped instanceof covia.exception.StillStartingException, stopped.getMessage());
	}

	@Test
	public void preparedEngineDefersBotWorkersUntilActivation() throws Exception {
		try (FakeTelegramServer fake = new FakeTelegramServer()) {
			var key = convex.core.crypto.AKeyPair.generate();
			AMap<AString, ACell> botSettings = Maps.of("apiUrl", fake.apiUrl(), "bots",
				Maps.of("startup", botConfig(FakeTelegramServer.TOKEN, owner.toString(),
					"operation", "v/test/ops/echo", Vectors.of(CVMLong.create(ALLOWED_ID)))));
			Engine prepared = covia.venue.TestEngine.application().mount(key,
				Maps.of(Config.ADAPTERS, Maps.of("telegram", botSettings))).prepare();
			try {
				TelegramAdapter deferred = new TelegramAdapter();
				prepared.registerAdapter(deferred);
				prepared.configureAdapter("telegram", botSettings);
				Engine.addDemoAssets(prepared);
				assertNull(deferred.runner("startup"));
				assertEquals(0, fake.getMeCalls(FakeTelegramServer.TOKEN));
				prepared.start();
				await(() -> deferred.runner("startup").state() == BotRunner.State.RUNNING,
					10_000, () -> "prepared bot did not activate");
				assertTrue(fake.getMeCalls(FakeTelegramServer.TOKEN) > 0);
			} finally {
				prepared.close();
			}
		}
	}

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
		startEcho();
		long chat = 1001L;
		push(chat, ALLOWED_ID, "alice", "hello from telegram");
		FakeTelegramServer.Sent reply = awaitSent(15_000);
		assertNotNull(reply, "the bot must answer an allowed user");
		assertEquals(chat, reply.chatId());
		assertTrue(reply.text().contains("hello from telegram"), "echo agent reply: " + reply.text());
		// The echo LLM returns the message it received: the sender's own words.
		assertEquals("hello from telegram", reply.text());
		assertNull(reply.parseMode(), "plain text by default");
		assertNotNull(reply.replyTo(), "answers reply to the inbound message");

		String sid = readSession(echo, chat);
		assertNotNull(sid, "the chat's agent session is persisted in the owner's workspace");
		// Who is on the other end travels beside the turn: the principal the bot
		// identified and how, with Telegram's sender and chat.
		AMap<AString, ACell> turn = firstTurn(sid);
		assertEquals(owner, turn.get(Fields.CALLER), "an allow-listed sender comes from the bot's principal");
		assertEquals(Strings.create("telegram:" + ALLOWED_ID), RT.getIn(turn, "authentication", "authenticatedIdentity"));
		assertEquals(Strings.create("alice"), RT.getIn(turn, "authentication", "authentication", "details", "sender", "handle"));
		assertEquals(Strings.create(Long.toString(chat)), RT.getIn(turn, "authentication", "authentication", "details", "chat", "id"));

		push(chat, ALLOWED_ID, "alice", "second");
		FakeTelegramServer.Sent second = awaitSent(15_000);
		assertNotNull(second);
		assertTrue(second.text().contains("second"), second.text());
		assertEquals(sid, readSession(echo, chat), "follow-ups continue the same session");
	}

	@Test
	public void testUnauthorisedUserIsRefusedInPrivateChat() throws Exception {
		startEcho();
		long chat = 1002L;
		push(chat, STRANGER_ID, "mallory", "let me in");
		FakeTelegramServer.Sent reply = awaitSent(10_000);
		assertNotNull(reply);
		assertTrue(reply.text().startsWith("Not authorised"), reply.text());
		assertTrue(reply.text().contains(String.valueOf(STRANGER_ID)), "tells the user their id: " + reply.text());
		assertNull(readSession(echo, chat), "no agent session is created for a refused message");
	}

	@Test
	public void testUsernameAllowListAndCommands() throws Exception {
		startEcho();
		long chat = 1003L;
		// 'alice' is allowed by @username even with an unlisted id
		push(chat, 9999L, "Alice", "/start");
		FakeTelegramServer.Sent greeting = awaitSent(10_000);
		assertNotNull(greeting);
		assertTrue(greeting.text().contains(AGENT), "default greeting names the agent: " + greeting.text());

		push(chat, 9999L, "Alice", "/id");
		FakeTelegramServer.Sent id = awaitSent(10_000);
		assertTrue(id.text().contains("Chat id: " + chat) && id.text().contains("9999"), id.text());

		push(chat, 9999L, "Alice", "first turn");
		assertNotNull(awaitSent(15_000));
		String sid1 = readSession(echo, chat);
		assertNotNull(sid1);

		push(chat, 9999L, "Alice", "/new@" + echoUsername);
		FakeTelegramServer.Sent reset = awaitSent(10_000);
		assertTrue(reset.text().startsWith("Started a new conversation"), reset.text());
		assertNull(readSession(echo, chat), "/new forgets the persisted session");

		push(chat, 9999L, "Alice", "after reset");
		assertNotNull(awaitSent(15_000));
		String sid2 = readSession(echo, chat);
		assertNotNull(sid2);
		assertNotEquals(sid1, sid2, "a fresh session after /new");
	}

	@Test
	public void testOperationRouteRepliesWithResult() throws Exception {
		BotRunner echoRunner = startEcho();
		String token = "222:OP-BOT";
		telegram.registerBot(token, "op_bot");
		declare("op", botConfig(token, owner.toString(), "operation", "v/test/ops/echo", null)
			.assoc(BotSpec.K_OPEN, convex.core.data.prim.CVMBool.TRUE));
		awaitState("op", BotRunner.State.RUNNING, 10_000);
		await(() -> "[]".equals(telegram.lastFilters.get(token)), 10_000, () -> "operation bot did not reset allowed_updates");
		// open bot: a stranger is answered; echo returns its input — the Telegram
		// Update as sent, plus bot — which is rendered as JSON for the reply
		telegram.push(token, 2001L, "group", STRANGER_ID, null, "ping the op");
		FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("\"update_id\"") && reply.text().contains("\"message\"")
			&& reply.text().contains("ping the op") && reply.text().contains("\"bot\""), reply.text());
		assertTrue(reply.text().contains("\"message_id\"") && reply.text().contains("\"first_name\""),
			"Telegram snake_case shape, not a translation: " + reply.text());
		assertSame(echoRunner, adapter.runner(echo), "unchanged bots keep running across a reconfigure");
		assertEquals(BotRunner.State.RUNNING, echoRunner.state());

		declare("op", null);
		assertNull(adapter.runner("op"), "removed bots are stopped and forgotten");
	}

	@Test
	public void testOperationReplyModes() throws Exception {
		String token = "555:MODES-BOT";
		telegram.registerBot(token, "modes_bot");
		AMap<AString, ACell> base = botConfig(token, owner.toString(), "operation", "v/test/ops/echo", null)
			.assoc(BotSpec.K_OPEN, convex.core.data.prim.CVMBool.TRUE);
		// reply:false — the operation runs (as a Job), nothing is sent
		declare("modes", base.assoc(BotSpec.K_REPLY, convex.core.data.prim.CVMBool.FALSE));
		awaitState("modes", BotRunner.State.RUNNING, 10_000);
		// The stranger is anonymous, so the Job is the public principal's, not the bot user's.
		RequestContext anonymous = RequestContext.of(engine.publicPrincipal());
		Set<Blob> jobsBefore = new HashSet<>(engine.jobs().getJobs(anonymous).keySet());
		telegram.push(token, 6001L, "private", STRANGER_ID, null, "quiet reply modes turn");
		Job quiet = awaitNewJob(anonymous, jobsBefore, "quiet reply modes turn", 10_000);
		quiet.awaitResult(10_000);
		await(() -> adapter.runner("modes").isChatIdle(6001L), 10_000,
			() -> "operation handler did not finish");
		assertNull(telegram.awaitSent(token, 0), "reply:false must send nothing");

		// reply:"…" — a fixed acknowledgement instead of the result
		declare("modes", base.assoc(BotSpec.K_REPLY, Strings.create("Done.")));
		awaitState("modes", BotRunner.State.RUNNING, 10_000);
		telegram.push(token, 6001L, "private", STRANGER_ID, null, "acked");
		FakeTelegramServer.Sent ack = telegram.awaitSent(token, 10_000);
		assertNotNull(ack);
		assertEquals("Done.", ack.text());
	}

	@Test
	public void testAgentChatIsAJobAndRecoversFromAStaleSession() throws Exception {
		startEcho();
		long chat = 1005L;
		// A persisted session id the agent has never heard of (e.g. the agent was recreated).
		adapter.state().write("config/" + echo + "/sessions/" + chat,
			Strings.create("deadbeefdeadbeefdeadbeefdeadbeef"));
		long jobsBefore = engine.jobs().getJobs(RequestContext.of(owner)).count();
		push(chat, ALLOWED_ID, "alice", "are you there");
		FakeTelegramServer.Sent reply = awaitSent(15_000);
		assertNotNull(reply, "the bot must recover by starting a fresh session");
		assertTrue(reply.text().contains("are you there"), reply.text());
		String sid = readSession(echo, chat);
		assertNotNull(sid);
		assertNotEquals("deadbeefdeadbeefdeadbeefdeadbeef", sid, "the stale session is replaced");
		await(() -> engine.jobs().getJobs(RequestContext.of(owner)).count() >= jobsBefore + 1, 10_000,
			() -> "the chat turn is recorded as a Job in the bot user's job index");
	}

	@Test
	public void testCallOperationSendsMediaAndRefusesManagedMethods() throws Exception {
		startEcho();
		ACell out = run(RequestContext.of(owner), "v/ops/telegram/call", Maps.of(
			"method", "sendPhoto",
			"params", Maps.of("chat_id", CVMLong.create(ALLOWED_ID), "photo", "https://example.org/cat.jpg",
				"caption", "a cat", "disable_notification", convex.core.data.prim.CVMBool.TRUE)));
		FakeTelegramServer.Sent sent = awaitSent(10_000);
		assertNotNull(sent);
		assertEquals("sendPhoto", sent.method());
		assertEquals("https://example.org/cat.jpg", sent.form().get("photo"));
		assertEquals("a cat", sent.text());
		assertTrue(sent.silent());
		assertEquals(Strings.create("a cat"), RT.getIn(out, "caption"), "Telegram's Message result: " + out);

		// A method answering `true` comes back as true
		ACell ack = run(RequestContext.of(owner), "v/ops/telegram/call", Maps.of(
			"method", "answerCallbackQuery", "params", Maps.of("callback_query_id", "cq1", "text", "Done")));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, ack);

		// The venue's own update loop owns getUpdates & co.
		Job managed = engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "getUpdates", "params", Maps.empty()), RequestContext.of(owner));
		try { managed.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, managed.getStatus());
		assertFalse(String.valueOf(managed.getErrorMessage()).isBlank());

		// A padded name is no method
		Job padded = engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", " sendPhoto", "params", Maps.of("chat_id", CVMLong.create(ALLOWED_ID), "photo", "x")), RequestContext.of(owner));
		try { padded.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, padded.getStatus());

		// Other users are denied
		Job denied = engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"bot", echo, "method", "sendPhoto", "params", Maps.of("chat_id", 1, "photo", "x")), RequestContext.of(other));
		try { denied.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, denied.getStatus());
		assertFalse(String.valueOf(denied.getErrorMessage()).isBlank());
	}

	@Test
	public void testATargetIdIsExactlyAnId() {
		assertEquals(Long.valueOf(42), BotSpec.targetId("42"));
		assertEquals(Long.valueOf(-42), BotSpec.targetId(CVMLong.create(-42)));
		assertEquals(null, BotSpec.targetId(" 42"));
		assertEquals(null, BotSpec.targetId("42 "));
	}

	@Test
	public void testOperationBotReceivesPhotosAndCallbackQueriesVerbatim() throws Exception {
		String token = "666:UPDATES-BOT";
		telegram.registerBot(token, "updates_bot");
		declare("upd", botConfig(token, owner.toString(), "operation", "v/test/ops/echo", null)
			.assoc(BotSpec.K_OPEN, convex.core.data.prim.CVMBool.TRUE));
		awaitState("upd", BotRunner.State.RUNNING, 10_000);
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
	}

	@Test
	public void testAgentBotReceivesPhotoWithoutCaption() throws Exception {
		var seen = new java.util.concurrent.atomic.AtomicReference<ACell>();
		engine.registerAdapter(new covia.adapter.AAdapter() {
			@Override public String getName() { return "telegram-vision"; }
			@Override public String getDescription() { return "Captures a multimodal LLM request"; }
			@Override protected void installAssets() {
				installAsset("telegram-vision/llm", Maps.of("name", "Vision fixture", "operation", Maps.of("adapter", "telegram-vision:llm")));
			}
			@Override public java.util.concurrent.CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
				seen.set(input);
				return java.util.concurrent.CompletableFuture.completedFuture(Maps.of("role", "assistant", "content", "Photo received"));
			}
		});
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "tg-vision", Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/ops/telegram-vision/llm", "caps", "unrestricted")));
		startEcho(echoBotConfig().assoc(BotSpec.K_AGENT, Strings.create("tg-vision")));
		telegram.pushPhoto(echoToken, 1006L, ALLOWED_ID, "alice", null);
		FakeTelegramServer.Sent reply = awaitSent(15_000);
		assertNotNull(reply);
		assertEquals("Photo received", reply.text());
		assertNotNull(readSession(echo, 1006L));
		AVector<ACell> messages = RT.ensureVector(RT.getIn(seen.get(), "messages"));
		boolean imageAtModel = false;
		for (ACell message : messages) {
			AVector<ACell> blocks = RT.ensureVector(RT.getIn(message, "content"));
			if (blocks == null) continue;
			for (ACell block : blocks) {
				if (!Strings.create("image").equals(RT.getIn(block, "type"))) continue;
				assertEquals(Strings.create("image/jpeg"), RT.getIn(block, "source", "mediaType"));
				assertTrue(RT.getIn(block, "source", "ref").toString().contains("/dlfs/telegram/"));
				imageAtModel = true;
			}
		}
		assertTrue(imageAtModel, "photo reference reaches the LLM as an image block: " + seen.get());
	}

	@Test
	public void mediaProcessorReadsBytesAsOwnerAndTranscriptionReachesAgent() throws Exception {
		String fileId = "transcription_test";
		telegram.files.put(fileId, "recorded voice".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		engine.registerAdapter(new covia.adapter.AAdapter() {
			@Override public String getName() { return "telegram-test"; }
			@Override public String getDescription() { return "Test transcription fixture"; }
			@Override protected void installAssets() {
				installAsset("telegram-test/transcribe", Maps.of("name", "Transcribe fixture", "operation", Maps.of("adapter", "telegram-test:transcribe")));
			}
			@Override public java.util.concurrent.CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
				assertEquals(owner, ctx.getUserDID());
				assertEquals(Strings.create("15001"), RT.getIn(input, "authentication", "authentication", "details", "chat", "id"));
				assertEquals(Strings.create(owner + "/telegram/" + echo), RT.getIn(input, "authentication", "authentication", "binding"));
				if (Strings.create("document").equals(RT.getIn(input, "kind"))) {
					return java.util.concurrent.CompletableFuture.completedFuture(Maps.empty());
				}
				assertEquals(Strings.create("voice"), RT.getIn(input, "kind"));
				assertEquals(Strings.create("Transcribe this note"), RT.getIn(input, "caption"));
				try {
					var content = engine.resolveContent(RT.ensureString(RT.getIn(input, "ref")), ctx);
					assertEquals("recorded voice", new String(content.content().getBlob().getBytes(), java.nio.charset.StandardCharsets.UTF_8));
				} catch (java.io.IOException e) { throw new RuntimeException(e); }
				return java.util.concurrent.CompletableFuture.completedFuture(Maps.of("text", "Please remind me about my appointment", "language", "en", "recordId", "application-record-17"));
			}
		});
		startEcho(echoBotConfig().assoc(BotSpec.K_MEDIA_OPERATION, Strings.create("v/ops/telegram-test/transcribe")));
		var message = telegram.message(15001, "private", ALLOWED_ID, "alice");
		message.put("voice", java.util.Map.of("file_id", fileId, "file_unique_id", "voiceu", "duration", 3));
		message.put("caption", "Transcribe this note");
		telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message)));
		var reply = awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("Please remind me about my appointment"), reply.text());
		assertTrue(reply.text().contains("application-record-17"), "structured application result is retained");
		// A speech-only operation need not invent text for every other media type.
		message = telegram.message(15001, "private", ALLOWED_ID, "alice");
		message.put("document", java.util.Map.of("file_id", fileId, "file_unique_id", "docu", "file_name", "note.ogg"));
		message.put("caption", "Keep this original file");
		telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message)));
		reply = awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("Keep this original file") && reply.text().contains("/dlfs/telegram/"), reply.text());
	}

	@Test
	public void processorResultsKeepTransportMetadataAndRejectMalformedText() {
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
			assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("bad", Maps.of("token", "t", "user", owner,
				"operation", "v/test/ops/echo").assoc(option, Strings.create("v/test/ops/echo")), true));
		}
	}

	@Test
	@ResourceLock(REARM)
	public void runtimeProcessingAndReplyHooksSurviveRearming() throws Exception {
		createAgent();
		String token = "778:HOOKS-BOT";
		telegram.registerBot(token, "hooks_bot");
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_HOOKS", "value", token));
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var malformed = new java.util.concurrent.atomic.AtomicBoolean();
		engine.registerAdapter(new covia.adapter.AAdapter() {
			@Override public String getName() { return "telegram-format"; }
			@Override public String getDescription() { return "Application reply formatter fixture"; }
			@Override protected void installAssets() {
				installAsset("telegram-format/reply", Maps.of("name", "Format fixture", "operation", Maps.of("adapter", "telegram-format:reply")));
			}
			@Override public java.util.concurrent.CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
				assertEquals(owner, ctx.getUserDID());
				assertEquals(Strings.create(owner + "/telegram/hooks"), RT.getIn(input, "authentication", "authentication", "binding"));
				assertEquals(Strings.create(Long.toString(ALLOWED_ID)), RT.getIn(input, "authentication", "authentication", "details", "chat", "id"));
				assertTrue(RT.getIn(input, "response").toString().contains("APPLICATION_CARD"));
				calls.incrementAndGet();
				if (malformed.get()) return java.util.concurrent.CompletableFuture.completedFuture(Maps.of("internalCard", "APPLICATION_CARD"));
				return java.util.concurrent.CompletableFuture.completedFuture(Maps.of("text", "Readable application reply"));
			}
		});
		run(RequestContext.of(owner), "v/ops/telegram/create", Maps.of("name", "hooks", "token", "s/TG_HOOKS", "agent", AGENT,
			"allow", Vectors.of(ALLOWED_ID), "mediaOperation", "v/test/ops/echo", "replyOperation", "v/ops/telegram-format/reply"));
		try {
			for (int attempt = 0; attempt < 2; attempt++) {
				BotRunner runner = adapter.runner(owner, "hooks");
				await(() -> runner.state() == BotRunner.State.RUNNING, 10_000, () -> "hooks bot did not start");
				assertEquals("v/test/ops/echo", runner.spec().mediaOperation());
				assertEquals("v/ops/telegram-format/reply", runner.spec().replyOperation());
				telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "APPLICATION_CARD");
				var reply = telegram.awaitSent(token, 15_000);
				assertNotNull(reply);
				assertEquals("Readable application reply", reply.text());
				await(() -> runner.isChatIdle(ALLOWED_ID), 10_000, () -> "reply did not finish");
				if (attempt == 0) {
					adapter.forgetForTest(owner, "hooks");
					adapter.rearmForTest();
				}
			}
			assertEquals(2, calls.get());
			malformed.set(true);
			telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "APPLICATION_CARD");
			var failure = telegram.awaitSent(token, 15_000);
			assertNotNull(failure);
			assertTrue(failure.text().contains(BotRunner.INVALID_REPLY), failure.text());
			assertFalse(failure.text().contains("APPLICATION_CARD"), "invalid formatting must not send the original response");
		} finally {
			run(RequestContext.of(owner), "v/ops/telegram/delete", Maps.of("name", "hooks"));
		}
	}

	@Test
	public void formatAsTheReplyHookSendsTheAgentsMarkdownAsTelegramHtml() throws Exception {
		createAgent();
		String token = "779:FORMAT-BOT";
		telegram.registerBot(token, "format_bot");
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_FORMAT", "value", token));
		// No parseMode: the formatter's own parse_mode is the whole setup.
		run(RequestContext.of(owner), "v/ops/telegram/create", Maps.of("name", "formatted", "token", "s/TG_FORMAT", "agent", AGENT,
			"allow", Vectors.of(ALLOWED_ID), "replyOperation", "v/ops/telegram/format"));
		try {
			BotRunner runner = adapter.runner(owner, "formatted");
			await(() -> runner.state() == BotRunner.State.RUNNING, 10_000, () -> "formatted bot did not start");
			// The echo agent repeats the message, Markdown and all.
			telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "**Bold** & <tag>");
			var reply = telegram.awaitSent(token, 15_000);
			assertNotNull(reply);
			assertEquals(TelegramMarkdown.PARSE_MODE, reply.parseMode(), "sent under the parse mode the hook returned");
			assertTrue(reply.text().contains("<b>Bold</b>"), reply.text());
			TelegramMarkdownTest.assertTelegramHtml(reply.text());
			await(() -> runner.isChatIdle(ALLOWED_ID), 10_000, () -> "reply did not finish");

			// Telegram refuses the HTML: the resend is the words it shows, not its markup.
			telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "**Bold** " + FakeTelegramServer.BAD_MARKUP);
			var plain = telegram.awaitSent(token, 15_000);
			assertNotNull(plain);
			assertEquals(null, plain.parseMode(), "resent without a parse mode");
			assertTrue(plain.text().contains("Bold") && plain.text().contains(FakeTelegramServer.BAD_MARKUP), plain.text());
			assertFalse(plain.text().contains("<b>"), "no markup in the plain resend: " + plain.text());
			await(() -> runner.isChatIdle(ALLOWED_ID), 10_000, () -> "second reply did not finish");
		} finally {
			run(RequestContext.of(owner), "v/ops/telegram/delete", Maps.of("name", "formatted"));
		}
	}

	@Test
	public void sessionDeliveryCompletesBeforeTheTurnAndSurvivesReplyFailure() throws Exception {
		long chat = 17001;
		var entered = new java.util.concurrent.CountDownLatch(1);
		var response = new java.util.concurrent.CompletableFuture<ACell>();
		var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
		engine.registerAdapter(new covia.adapter.AAdapter() {
			@Override public String getName() { return "telegram-held"; }
			@Override public String getDescription() { return "Holds the model response after intake"; }
			@Override protected void installAssets() {
				installAsset("telegram-held/llm", Maps.of("name", "Held model", "operation", Maps.of("adapter", "telegram-held:llm")));
			}
			@Override public java.util.concurrent.CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
				modelCalls.incrementAndGet();
				entered.countDown();
				return response;
			}
		});
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "tg-held", Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/ops/telegram-held/llm", "caps", "unrestricted")));
		var inbox = echoInbox();
		var config = echoBotConfig().assoc(BotSpec.K_AGENT, Strings.create("tg-held"));
		try {
			startEcho(config);
			String first = Integer.toString(push(chat, ALLOWED_ID, "alice", "First input"));
			assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get(first).get(Fields.STATUS));
			assertFalse(response.isDone());
			String second = Integer.toString(push(chat, ALLOWED_ID, "alice", "Second input while thinking"));
			await(() -> inbox.get(second) != null && covia.adapter.webhook.WebhookInbox.COMPLETE.equals(inbox.get(second).get(Fields.STATUS)),
				10_000, () -> "second input was blocked behind the first response");
			assertEquals(inbox.get(first).get(Fields.SESSION_ID), inbox.get(second).get(Fields.SESSION_ID));
			assertFalse(response.isDone());
			telegram.failSends(echoToken, chat);
			response.complete(Maps.of("role", "assistant", "content", "Generated response"));
			await(() -> telegram.failedSends(echoToken) >= 4, 10_000, () -> "both replies and failure notices should fail");
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get(first).get(Fields.STATUS));
			assertEquals(covia.adapter.webhook.WebhookInbox.COMPLETE, inbox.get(second).get(Fields.STATUS));
			int calls = modelCalls.get();
			restartEcho(config, chat);
			assertNull(awaitSent(0));
			assertEquals(calls, modelCalls.get(), "a failed outbound reply must not redeliver session input");
		} finally {
			response.complete(Maps.of("role", "assistant", "content", "Generated response"));
		}
	}

	@Test
	public void pendingReceiptSurvivesRunnerRestartAndCompletedReceiptIsNotReplayed() throws Exception {
		var inbox = echoInbox();
		var message = telegram.message(15002, "private", ALLOWED_ID, "alice");
		message.put("text", "Recover this pending turn");
		ACell update = convex.core.util.JSON.parse(convex.core.util.JSON.toString(java.util.Map.of("update_id", 900001, "message", message)));
		inbox.accept("900001", update);
		inbox.accept("900002", update);
		inbox.claim("900002");
		startEcho();
		var reply = awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("Recover this pending turn"), reply.text());
		await(() -> covia.adapter.webhook.WebhookInbox.COMPLETE.equals(inbox.get("900001").get(Fields.STATUS)), 10_000, () -> "receipt did not complete");
		inbox.accept("900001", update);
		restartEcho(echoBotConfig(), 15002);
		assertNull(awaitSent(0), "completed or uncertain receipts must not run again");
		assertEquals(covia.adapter.webhook.WebhookInbox.STARTED, inbox.get("900002").get(Fields.STATUS));
	}

	@Test
	public void undeliverableTurnNotifiesOnceAndIsNotRetriedAfterRestart() throws Exception {
		startEcho();
		// Intake downloads the document first; the file never becomes available.
		var message = telegram.message(17101, "private", ALLOWED_ID, "alice");
		message.put("document", java.util.Map.of("file_id", "never-uploaded", "file_unique_id", "never-uploaded-u",
			"mime_type", "application/pdf", "file_name", "missing.pdf"));
		message.put("caption", "Please read this");
		String id = Integer.toString(telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message))));
		var notice = awaitSent(15_000);
		assertNotNull(notice, "the sender is told after the failed attempt");
		var inbox = echoInbox();
		var receipt = inbox.get(id);
		assertEquals(covia.adapter.webhook.WebhookInbox.FAILED, receipt.get(Fields.STATUS));
		assertEquals(Strings.create(BotRunner.DELIVERY_FAILED), receipt.get(Fields.REASON));
		String lastError = receipt.get(covia.adapter.webhook.WebhookInbox.LAST_ERROR).toString();
		assertEquals(BotRunner.UNDELIVERED.formatted(lastError), notice.text());
		assertEquals(1, telegram.getFileCalls.get("never-uploaded").get());
		adapter.runner(echo).whenChatIdle(17101).get(10, java.util.concurrent.TimeUnit.SECONDS);
		assertNull(awaitSent(0), "one notice per update");

		restartEcho(echoBotConfig(), 17101);
		assertNull(awaitSent(0), "a FAILED update is neither retried nor reported again after restart");
		assertEquals(receipt, inbox.get(id));
		assertEquals(1, telegram.getFileCalls.get("never-uploaded").get());
		assertTrue(((CVMLong) adapter.runner(echo).status().get(Strings.intern("failedUpdates"))).longValue() >= 1);
	}

	@Test
	public void suspendedAgentFailsTheUpdateOnceWithoutJobs() throws Exception {
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "tg-resting", Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm", "caps", "unrestricted")));
		run(RequestContext.of(owner), "v/ops/agent/suspend", Maps.of(Fields.AGENT_ID, "tg-resting"));
		startEcho(echoBotConfig().assoc(BotSpec.K_AGENT, Strings.create("tg-resting")));
		long jobs = engine.jobs().getJobs(RequestContext.of(owner)).count();
		String id = Integer.toString(push(17102, ALLOWED_ID, "alice", "Are you there?"));
		var notice = awaitSent(10_000);
		assertNotNull(notice);
		String refusal = covia.adapter.AgentAdapter.DELIVERY_AGENT_SUSPENDED.formatted("tg-resting");
		assertEquals(BotRunner.UNDELIVERED.formatted(refusal), notice.text());
		var receipt = echoInbox().get(id);
		assertEquals(covia.adapter.webhook.WebhookInbox.FAILED, receipt.get(Fields.STATUS));
		assertEquals(Strings.create(BotRunner.DELIVERY_FAILED), receipt.get(Fields.REASON));
		adapter.runner(echo).whenChatIdle(17102).get(10, java.util.concurrent.TimeUnit.SECONDS);
		assertNull(awaitSent(0), "one notice per update");
		assertEquals(jobs, engine.jobs().getJobs(RequestContext.of(owner)).count(), "a suspended agent gets no failed chat Job");
	}

	@Test
	public void voiceAndDocumentBytesAreStoredPrivately() throws Exception {
		startEcho();
		for (String kind : List.of("voice", "document")) {
			String fileId = "test_" + kind;
			byte[] bytes = ("bytes for " + kind).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			telegram.files.put(fileId, bytes);
			var message = telegram.message(14001, "private", ALLOWED_ID, "alice");
			message.put(kind, java.util.Map.of("file_id", fileId, "file_unique_id", fileId + "u", "duration", 2,
				"mime_type", kind.equals("voice") ? "audio/ogg" : "application/pdf", "file_name", "document-transport.pdf"));
			message.put("caption", "Please read this");
			telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message)));
			var reply = awaitSent(15_000);
			assertNotNull(reply);
			assertTrue(reply.text().contains(fileId) && reply.text().contains("Please read this"), reply.text());
			String ref = owner + "/dlfs/telegram/" + (kind.equals("document") ? "document-transport.pdf" : echo + "-" + Strings.create(fileId).getHash().toHexString() + ".bin");
			var stored = engine.resolveContent(Strings.create(ref), RequestContext.of(owner));
			org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, stored.content().getBlob().getBytes());
			assertThrows(RuntimeException.class, () -> engine.resolveContent(Strings.create(ref), RequestContext.of(other)));
			assertFalse(reply.text().contains(echoToken));
		}
	}

	@Test
	public void unauthorisedMediaIsNotDownloaded() throws Exception {
		startEcho();
		telegram.pushPhoto(echoToken, 14002, STRANGER_ID, "stranger", "private photo");
		assertNotNull(awaitSent(10_000));
		assertEquals(0, telegram.downloads(echoToken));
	}

	@Test
	public void downloadAndMultipartUploadUseProtectedReferences() throws Exception {
		startEcho();
		telegram.files.put("outbound", "voice-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		ACell downloaded = run(RequestContext.of(owner), "v/ops/telegram/download", Maps.of("file_id", "outbound"));
		ACell ref = RT.getIn(downloaded, Fields.REF);
		run(RequestContext.of(owner), "v/ops/telegram/call", Maps.of("method", "sendVoice",
			"params", Maps.of("chat_id", ALLOWED_ID), "files", Maps.of("voice", Maps.of("ref", ref, "filename", "note.ogg"))));
		var sent = awaitSent(10_000);
		assertEquals("sendVoice", sent.method());
		assertEquals("voice-bytes", sent.form().get("voice"));
		assertTrue(telegram.uploadType(echoToken, "voice").contains("filename=\"note.ogg\""));
		// A caller cannot use a file part as a substitute for the checked target parameter.
		assertThrows(RuntimeException.class, () -> run(RequestContext.of(owner), "v/ops/telegram/call", Maps.of("method", "sendVoice",
			"params", Maps.of("chat_id", STRANGER_ID), "files", Maps.of("voice", Maps.of("ref", ref)))));
	}

	@Test
	public void downloadAcceptsContentDestinationsAndPreservesCallerWriteScope() throws Exception {
		startEcho();
		byte[] bytes = "downloaded-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		telegram.files.put("destination_test", bytes);
		RequestContext ctx = RequestContext.of(owner);
		run(ctx, "v/ops/dlfs/create-drive", Maps.of("name", "imports"));
		ACell result = run(ctx, "v/ops/telegram/download", Maps.of("file_id", "destination_test", "destination", "dlfs/imports", "fileName", "note.ogg"));
		assertEquals(Strings.create("dlfs/imports/note.ogg"), RT.getIn(result, Fields.REF));
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,
			engine.resolveContent(Strings.create("dlfs/imports/note.ogg"), ctx).content().getBlob().getBytes());

		// Creating a file needs write on its directory, which the owner makes
		run(ctx, "v/ops/file/mkdir", Maps.of("root", "tmp", "path", "telegram-scope"));
		AString file = Strings.create("file://tmp/telegram-scope/telegram-download.ogg");
		RequestContext scoped = ctx.withCaps(Vectors.of(
			convex.auth.ucan.Capability.create(Strings.create("v/ops/telegram/download"), Strings.create("invoke")),
			convex.auth.ucan.Capability.create(Strings.create(owner + "/telegram/" + echo), TelegramAdapter.ABILITY_CALL),
			convex.auth.ucan.Capability.create(Strings.create("file://tmp/telegram-scope/"),
				convex.auth.ucan.Capability.CRUD_WRITE)));
		result = run(scoped, "v/ops/telegram/download", Maps.of("file_id", "destination_test", "destination", "file://tmp/telegram-scope/", "fileName", "telegram-download.ogg"));
		assertEquals(file, RT.getIn(result, Fields.REF));
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(file, ctx).content().getBlob().getBytes());

		// Bot authority must not replace the caller's scope for an explicit destination.
		AString outside = Strings.create("file://tmp/telegram-outside-scope.ogg");
		assertTrue(engine.putContent(outside, new java.io.ByteArrayInputStream(new byte[] {7}), null, ctx));
		assertThrows(RuntimeException.class, () -> run(scoped, "v/ops/telegram/download",
			Maps.of("file_id", "destination_test", "destination", "file://tmp", "fileName", "telegram-outside-scope.ogg")));
		org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {7}, engine.resolveContent(outside, ctx).content().getBlob().getBytes());
		assertThrows(RuntimeException.class, () -> run(ctx, "v/ops/telegram/download",
			Maps.of("file_id", "destination_test", "destination", other + "/dlfs/imports", "fileName", "note.ogg")));
	}

	@Test
	public void originalNamesAndMimeTypesSurviveRestartAndDownloadCollisions() throws Exception {
		startEcho();
		String fileId = "original_named_report";
		String original = "Lab report 日本語.pdf";
		byte[] bytes = "original-named-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		telegram.files.put(fileId, bytes);
		var message = telegram.message(16001, "private", ALLOWED_ID, "alice");
		message.put("document", java.util.Map.of("file_id", fileId, "file_unique_id", "originalu",
			"file_name", original, "mime_type", "application/pdf"));
		telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message)));
		var reply = awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains(original), reply.text());
		AString originalRef = Strings.create(owner + "/dlfs/telegram/" + java.net.URLEncoder.encode(original, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(originalRef, RequestContext.of(owner)).content().getBlob().getBytes());
		await(() -> adapter.runner(echo).isChatIdle(16001), 10_000, () -> "document turn did not complete");
		declare(echo, null);
		startEcho();
		ACell saved = run(RequestContext.of(owner), "v/ops/telegram/download", Maps.of("file_id", fileId));
		assertEquals(Strings.create("Lab report 日本語(1).pdf"), RT.getIn(saved, Fields.FILE_NAME));
		assertEquals(Strings.create(original), RT.getIn(saved, "file_name"));
		assertEquals(Strings.create("application/pdf"), RT.getIn(saved, Fields.CONTENT_TYPE), "getFile's generated .bin path must not replace message MIME metadata");
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(originalRef, RequestContext.of(owner)).content().getBlob().getBytes());
		saved = run(RequestContext.of(owner), "v/ops/telegram/download", Maps.of("file_id", fileId, "fileName", "chosen-name.pdf"));
		assertEquals(Strings.create("chosen-name.pdf"), RT.getIn(saved, Fields.FILE_NAME));
		assertEquals(Strings.create(owner + "/dlfs/telegram/chosen-name.pdf"), RT.getIn(saved, Fields.REF));
		saved = run(RequestContext.of(owner), "v/ops/telegram/download", Maps.of("file_id", fileId, "destination", "file://tmp"));
		assertEquals(Strings.create("file://tmp/" + original), RT.getIn(saved, Fields.REF));
	}

	@Test
	public void concurrentDownloadsNeverReplaceExistingContent() throws Exception {
		startEcho();
		String fileId = "collision_test";
		byte[] bytes = "new-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		telegram.files.put(fileId, bytes);
		RequestContext ctx = RequestContext.of(owner);
		run(ctx, "v/ops/dlfs/create-drive", Maps.of("name", "collisions"));
		for (String directory : List.of("file://tmp", "dlfs/collisions")) {
			String name = "collision-report.pdf";
			AString original = Strings.create(directory + "/" + name);
			assertTrue(engine.putContent(original, new java.io.ByteArrayInputStream(new byte[] {9}), null, ctx));
			var calls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<ACell>>();
			for (int i = 0; i < 6; i++) calls.add(java.util.concurrent.CompletableFuture.supplyAsync(() ->
				adapter.handleDownload(ctx, Maps.of("file_id", fileId, "destination", directory, "fileName", name))));
			Set<ACell> refs = new HashSet<>();
			for (var call : calls) {
				ACell result = call.get(10, java.util.concurrent.TimeUnit.SECONDS);
				AString ref = RT.ensureString(RT.getIn(result, Fields.REF));
				assertTrue(refs.add(ref), "every concurrent download needs a different destination");
				assertTrue(RT.getIn(result, Fields.FILE_NAME).toString().matches("collision-report\\([1-6]\\)\\.pdf"));
				org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(ref, ctx).content().getBlob().getBytes());
			}
			org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {9}, engine.resolveContent(original, ctx).content().getBlob().getBytes());
		}
	}

	@Test
	public void unsafeNamesAreRejectedOrFallBackWithoutLeavingTheDirectory() throws Exception {
		startEcho();
		String fileId = "unsafe_original";
		telegram.files.put(fileId, new byte[] {4});
		for (String name : List.of("../escape.pdf", "a/b.pdf", "a\\b.pdf", "report.pdf:stream", "a%2fb", "a#b", "NUL", "report.", " padded ")) {
			assertFalse(TelegramMedia.safeFileName(name));
			assertEquals(TelegramMedia.INVALID_FILE_NAME, assertThrows(InvalidInputException.class, () ->
				adapter.handleDownload(RequestContext.of(owner), Maps.of("file_id", fileId, "fileName", name))).getMessage());
		}
		var message = telegram.message(16002, "private", ALLOWED_ID, "alice");
		message.put("document", java.util.Map.of("file_id", fileId, "file_unique_id", "unsafeu", "file_name", "../escape.pdf"));
		telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message)));
		assertNotNull(awaitSent(15_000));
		String fallback = echo + "-" + Strings.create(fileId).getHash().toHexString() + ".bin";
		assertNotNull(engine.resolveContent(Strings.create(owner + "/dlfs/telegram/" + fallback), RequestContext.of(owner)));
		ACell result = run(RequestContext.of(owner), "v/ops/telegram/download", Maps.of("file_id", fileId));
		assertEquals(Strings.create("../escape.pdf"), RT.getIn(result, "file_name"), "original name remains metadata, never a path");
		assertEquals(Strings.create(TelegramMedia.collisionName(fallback, 1)), RT.getIn(result, Fields.FILE_NAME));
	}

	@Test
	public void downloadRejectsMalformedAndUnsupportedDestinations() throws Exception {
		startEcho();
		telegram.files.put("invalid_destination", new byte[] {1});
		for (ACell invalid : new ACell[] {Strings.EMPTY, Strings.create(" "), CVMLong.ONE}) {
			assertEquals(TelegramMedia.INVALID_DESTINATION, assertThrows(InvalidInputException.class,
				() -> adapter.handleDownload(RequestContext.of(owner), Maps.of("file_id", "invalid_destination", "destination", invalid))).getMessage());
		}
		assertEquals(0, telegram.downloads(echoToken));
		var failure = assertThrows(InvalidInputException.class, () -> adapter.handleDownload(RequestContext.of(owner),
			Maps.of("file_id", "invalid_destination", "destination", "unsupported://destination")));
		assertTrue(failure.getMessage().startsWith(TelegramMedia.UNSUPPORTED_DESTINATION));
	}

	@Test
	public void downloadRejectsOversizedFilesBeforeTransfer() throws Exception {
		startEcho();
		telegram.files.put("oversized", new byte[] {1});
		telegram.declaredSizes.put("oversized", (long) TelegramMedia.MAX_BYTES + 1);
		assertThrows(RuntimeException.class, () -> run(RequestContext.of(owner), "v/ops/telegram/download", Maps.of("file_id", "oversized")));
		assertEquals(0, telegram.downloads(echoToken));
	}

	@Test
	public void streamingSizeLimitAndMultipartTargetChecksCannotBeBypassed() throws Exception {
		startEcho();
		telegram.files.put("chunked", new byte[TelegramMedia.MAX_BYTES + 1]);
		telegram.chunkedFiles.add("chunked");
		try {
			RuntimeException failure = assertThrows(RuntimeException.class, () -> run(RequestContext.of(owner),
				"v/ops/telegram/download", Maps.of("file_id", "chunked")));
			assertFalse(failure.toString().contains(echoToken));
		} finally { telegram.files.remove("chunked"); }
		assertThrows(RuntimeException.class, () -> run(RequestContext.of(owner), "v/ops/telegram/call",
			Maps.of("method", "sendVoice", "files", Maps.of("chat_id", Maps.of("ref", "dlfs/telegram/anything")))));
	}

	@Test
	public void forumTopicsHaveIndependentSessionsAndResetOnlyTheirOwnTopic() throws Exception {
		startEcho();
		var sessions = new covia.adapter.messaging.ConversationSessions(engine.adapterState("telegram"), "config/" + echo + "/sessions");
		for (int topic : List.of(11, 22)) {
			var message = telegram.message(15003, "private", ALLOWED_ID, "alice");
			message.put("text", "Hello topic " + topic);
			message.put("is_topic_message", true);
			message.put("message_thread_id", topic);
			telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", message)));
			var reply = awaitSent(15_000);
			assertNotNull(reply);
			assertEquals(Integer.toString(topic), reply.form().get("message_thread_id"));
		}
		String firstKey = covia.adapter.messaging.ConversationRouter.key("15003", "11");
		String secondKey = covia.adapter.messaging.ConversationRouter.key("15003", "22");
		String second = sessions.get(secondKey);
		assertNotNull(second);
		assertNotEquals(sessions.get(firstKey), second);
		var reset = telegram.message(15003, "private", ALLOWED_ID, "alice");
		reset.put("text", "/new");
		reset.put("is_topic_message", true);
		reset.put("message_thread_id", 11);
		telegram.pushUpdate(echoToken, new java.util.HashMap<>(java.util.Map.of("message", reset)));
		assertNotNull(awaitSent(10_000));
		assertNull(sessions.get(firstKey));
		assertEquals(second, sessions.get(secondKey));
	}

	@Test
	public void topicKeysAndReplyMetadataArePreserved() throws Exception {
		BotRunner runner = startEcho();
		var message = telegram.message(-12345, "supergroup", ALLOWED_ID, "alice");
		message.put("is_topic_message", true);
		message.put("message_thread_id", 101);
		message.put("media_group_id", "album1");
		var parsed = com.pengrad.telegrambot.utility.BotUtils.GSON.fromJson(convex.core.util.JSON.toString(message), com.pengrad.telegrambot.model.Message.class);
		String key = BotRunner.conversationKey(parsed, -12345L);
		assertNotEquals("-12345", key);
		var channel = runner.channel(parsed);
		assertEquals(Strings.create("101"), channel.details().get(Strings.create("thread")), "a topic is the channel's thread");
		assertEquals(Strings.create("album1"), RT.getIn(channel.details(), "telegram", "media_group_id"));
		assertEquals(Strings.create("group"), RT.getIn(channel.details(), "chat", "kind"));
		message.put("message_thread_id", 102);
		parsed = com.pengrad.telegrambot.utility.BotUtils.GSON.fromJson(convex.core.util.JSON.toString(message), com.pengrad.telegrambot.model.Message.class);
		assertNotEquals(key, BotRunner.conversationKey(parsed, -12345L));
	}

	@Test
	public void testNonAsciiSurvivesBothDirections() throws Exception {
		startEcho();
		String text = "em dash — ✅ ❌ £ é 日本語 🚀";
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of("chat_id", CVMLong.create(ALLOWED_ID), "text", text));
		FakeTelegramServer.Sent sent = awaitSent(10_000);
		assertNotNull(sent);
		assertEquals(text, sent.text(), "outbound text must reach Telegram byte-for-byte as UTF-8");

		push(1007L, ALLOWED_ID, "alice", "inbound " + text);
		FakeTelegramServer.Sent reply = awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains(text), "inbound text must survive the round trip: " + reply.text());
	}

	@Test
	@ResourceLock(REARM)
	public void testABotAnAgentCreatesIdentifiesItsSendersAsThatAgent() throws Exception {
		String token = "778:AGENT-BOT";
		telegram.registerBot(token, "agent_bot");
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_AGENT", "value", token));
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of("agentId", "maker",
			"config", Maps.of("operation", "v/test/ops/taskcomplete")));
		AString maker = covia.grid.Principals.agentDID(owner, Strings.create("maker"));

		run(RequestContext.ofAgent(owner, Strings.create("maker")), "v/ops/telegram/create",
			Maps.of("name", "made", "token", "s/TG_AGENT", "agent", AGENT));
		assertEquals(maker, adapter.runner(owner, "made").context().getCallerDID(),
			"the bot identifies its senders as the agent, never its owner");
		adapter.forgetForTest(owner, "made");
		adapter.rearmForTest();
		assertEquals(maker, adapter.runner(owner, "made").context().getCallerDID(), "and still after a restart");
		run(RequestContext.of(owner), "v/ops/telegram/delete", Maps.of("name", "made"));
	}

	@Test
	public void testACappedAgentsOwnBotAsksItAsItself() throws Exception {
		String token = "779:SELF-BOT";
		telegram.registerBot(token, "self_bot");
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_SELF", "value", token));
		// Its own bot's senders come from the agent, so it needs the right to ask itself.
		AVector<ACell> caps = Vectors.of(
			convex.auth.ucan.Capability.create(Strings.create(owner + "/telegram"), Strings.create("telegram/manage")),
			convex.auth.ucan.Capability.create(Strings.create("v/ops/telegram/create"), Strings.create("invoke")),
			convex.auth.ucan.Capability.create(Strings.create(owner + "/g/self-asker"), Strings.create("agent/message")),
			convex.auth.ucan.Capability.create(Strings.create("v/ops/agent/chat"), Strings.create("invoke")));
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "self-asker",
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm", "caps", caps)));
		run(RequestContext.ofAgent(owner, Strings.create("self-asker")).withCaps(caps), "v/ops/telegram/create",
			Maps.of("name", "self", "token", "s/TG_SELF", "agent", "self-asker", "allow", Vectors.of(CVMLong.create(ALLOWED_ID))));
		try {
			BotRunner self = adapter.runner(owner, "self");
			await(() -> self.state() == BotRunner.State.RUNNING, 10_000, () -> "self bot did not start: " + self.error());
			telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "talk to yourself");
			FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
			assertNotNull(reply);
			assertTrue(reply.text().contains("talk to yourself"), reply.text());
		} finally {
			run(RequestContext.of(owner), "v/ops/telegram/delete", Maps.of("name", "self"));
		}
	}

	@Test
	@ResourceLock(REARM)
	public void testCreateAndDeleteRuntimeBots() throws Exception {
		startEcho();
		String token = "777:CREATED-BOT";
		telegram.registerBot(token, "created_bot");
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_CREATED", "value", token));

		// Validation first: literal token, explicit user, no handler
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", token, "agent", AGENT), RequestContext.of(owner)), AMessagingAdapter.SECRET_REFERENCE_REQUIRED);
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT, "user", other), RequestContext.of(owner)), AMessagingAdapter.IMPLICIT_USER);
		assertFails(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED"), RequestContext.of(owner)));

		ACell created = run(RequestContext.of(owner), "v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT,
			"allow", Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@bob"))));
		assertEquals(Strings.create("runtime"), RT.getIn(created, BotRunner.K_MANAGED));
		assertEquals(owner, RT.getIn(created, BotSpec.K_USER), "a created bot acts as its creator");
		BotRunner mine = adapter.runner(owner, "mine");
		assertNotNull(mine);
		await(() -> mine.state() == BotRunner.State.RUNNING, 10_000, () -> "created bot did not start: " + mine.error());
		assertEquals("created_bot", mine.username());

		// Recorded in venue-private adapter state (settings only — no user, no literal token).
		String recordPath = adapter.userStatePath(owner, "bots/mine");
		ACell record = adapter.state().read(recordPath);
		assertNotNull(record, "registry record");
		assertEquals(Strings.create("s/TG_CREATED"), RT.getIn(record, BotSpec.K_TOKEN));
		assertNull(RT.getIn(record, BotSpec.K_USER));
		assertNull(engine.resolvePath(Strings.create("w/adapters/telegram/" + recordPath), RequestContext.of(owner)),
			"user association does not make adapter-owned state part of that user's workspace");

		// Same name again is refused; another user may use the name; bots lists it as runtime
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT), RequestContext.of(owner)), AMessagingAdapter.DUPLICATE_BOT.formatted("Telegram", "mine"));
		ACell listing = run(RequestContext.of(owner), "v/ops/telegram/bots", Maps.empty());
		assertTrue(listing.toString().contains("runtime") && listing.toString().contains("\"mine\""), listing.toString());

		// It works: an inbound message reaches the agent through it, and it can send
		telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "via created bot");
		FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("via created bot"), reply.text());
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of("bot", "mine", "chat_id", CVMLong.create(ALLOWED_ID), "text", "out"));
		assertEquals("out", telegram.awaitSent(token, 10_000).text());

		// A listed handle resolves to its account on first contact, in the bot's own adapter state
		long bob = 8002L;
		telegram.push(token, bob, "private", bob, "bob", "hi from bob");
		assertTrue(telegram.awaitSent(token, 15_000).text().contains("hi from bob"));
		String resolvedPath = adapter.userStatePath(owner, "resolved/mine/bob");
		assertEquals(CVMLong.create(bob), adapter.state().read(resolvedPath));

		// Simulated restart: forget the live runner, re-arm from the lattice registry
		adapter.forgetForTest(owner, "mine");
		assertNull(adapter.runner(owner, "mine"));
		adapter.rearmForTest();
		BotRunner rearmed = adapter.runner(owner, "mine");
		assertNotNull(rearmed, "a created bot is re-armed from the venue adapter workspace at install");
		assertNotSame(mine, rearmed);
		await(() -> rearmed.state() == BotRunner.State.RUNNING, 10_000, () -> "re-armed bot did not start: " + rearmed.error());

		// …and the resolved handle comes back with it: Bob is addressable without writing again
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of("bot", "mine", "chat_id", CVMLong.create(bob), "text", "still bob"));
		assertEquals("still bob", telegram.awaitSent(token, 10_000).text());

		// Delete: stops it, removes the record and sessions; config bots are refused
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/delete", Maps.of("name", echo),
			RequestContext.of(owner)), AMessagingAdapter.CONFIG_BOT.formatted(echo));
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/delete", Maps.of("name", "nope"),
			RequestContext.of(owner)), AMessagingAdapter.MISSING_BOT.formatted("Telegram", "nope"));
		ACell deleted = run(RequestContext.of(owner), "v/ops/telegram/delete", Maps.of("name", "mine"));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(deleted, TelegramAdapter.K_DELETED));
		assertNull(adapter.runner(owner, "mine"));
		assertEquals(BotRunner.State.STOPPED, rearmed.state());
		assertNull(adapter.state().read(recordPath), "record removed");
		assertNull(adapter.state().read(adapter.userStatePath(owner, "sessions/mine/" + ALLOWED_ID)), "sessions removed");
		assertNull(adapter.state().read(resolvedPath), "resolved handles removed");
	}

	@Test
	@ResourceLock(REARM)
	public void testLegacyRuntimeBotRegistryMigratesAndDeleteCleansBothRoots() throws Exception {
		String token="779:LEGACY-BOT";
		telegram.registerBot(token,"legacy_bot");
		run(RequestContext.of(other),"v/ops/secret/set",Maps.of("name","TG_LEGACY","value",token));
		AMap<AString,ACell> settings=Maps.of("token","s/TG_LEGACY","operation","v/test/ops/echo","open",true);
		run(RequestContext.of(other),"v/ops/covia/write",Maps.of(
			Fields.PATH,TelegramAdapter.LEGACY_REGISTRY_PATH+"/legacy",Fields.VALUE,settings));

		adapter.rearmForTest();
		BotRunner legacy=adapter.runner(other,"legacy");
		assertNotNull(legacy);
		await(()->legacy.state()==BotRunner.State.RUNNING,10_000,()->"legacy bot did not start: "+legacy.error());
		assertEquals(settings,adapter.state().read(adapter.userStatePath(other,"bots/legacy")),
			"legacy record is copied into the canonical venue-private adapter workspace");

		run(RequestContext.of(other),"v/ops/telegram/delete",Maps.of("name","legacy"));
		assertNull(adapter.state().read(adapter.userStatePath(other,"bots/legacy")));
		assertNull(RT.getIn(run(RequestContext.of(other),"v/ops/covia/read",Maps.of(
			Fields.PATH,TelegramAdapter.LEGACY_REGISTRY_PATH+"/legacy")),Fields.VALUE));
	}

	private static void assertFails(Job job) {
		try { job.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, job.getStatus());
		assertFalse(String.valueOf(job.getErrorMessage()).isBlank());
	}

	private static void assertFailsWith(Job job, String fragment) {
		try { job.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, job.getStatus(), "expected failure mentioning '" + fragment + "'");
		assertTrue(String.valueOf(job.getErrorMessage()).contains(fragment),
			"expected '" + fragment + "' in: " + job.getErrorMessage());
	}

	@Test
	public void testTelegramAgentTemplate() throws Exception {
		ACell template = engine.resolvePath(Strings.create("v/agents/templates/telegram"), RequestContext.of(owner));
		assertNotNull(template, "the module ships an agent template");
		assertEquals(template, engine.resolvePath(Strings.create("v/adapters/telegram/templates/telegram"), RequestContext.of(owner)),
			"mirrored in the adapter's own subtree");
		ACell cfg = RT.getIn(template, "agent", "config");
		assertTrue(RT.getIn(cfg, "systemPrompt").toString().contains("[From:"), "the prompt teaches the From element");
		assertEquals(Vectors.of((ACell) Strings.create("w/skills"), Strings.create("v/skills/root")),
			RT.getIn(cfg, "skillsets"), "the template discovers personal and venue skillsets");
		assertNull(RT.getIn(cfg, "skills"), "directories must not be declared as individual skills");
		assertTrue(RT.getIn(cfg, "tools").toString().contains("v/ops/memory"), "memory is a base tool");
		assertNotNull(RT.getIn(cfg, "context"), "memory is pinned into context");

		// An assistant from the template, provider composed at create time (the echoing test LLM)
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, "tg-templated",
			Fields.CONFIG, Vectors.of(Strings.create("v/agents/templates/telegram"),
				Maps.of("llmOperation", "v/test/ops/llm"))));
		ACell agentContext = run(RequestContext.of(owner), "v/ops/agent/context", Maps.of(
			Fields.AGENT_ID, "tg-templated", Fields.MESSAGE, "hello"));
		String messages = RT.getIn(agentContext, Fields.MESSAGES).toString();
		assertTrue(messages.contains("[Skills]"), messages);
		assertTrue(messages.contains("adapters"),
			"the venue adapter skill router must be discoverable: " + messages);
		String token = "888:TEMPLATE-BOT";
		telegram.registerBot(token, "templated_bot");
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_TEMPLATE", "value", token));
		run(RequestContext.of(owner), "v/ops/telegram/create", Maps.of(
			"name", "templated", "token", "s/TG_TEMPLATE", "agent", "tg-templated",
			"allow", Vectors.of(CVMLong.create(ALLOWED_ID))));
		try {
			BotRunner r = adapter.runner(owner, "templated");
			await(() -> r != null && r.state() == BotRunner.State.RUNNING, 10_000, () -> "templated bot did not start");
			telegram.push(token, 9001L, "private", ALLOWED_ID, "alice", "hello template");
			FakeTelegramServer.Sent reply = telegram.awaitSent(token, 20_000);
			assertNotNull(reply, "an agent built from the template answers through its bot");
			assertTrue(reply.text().contains("hello template"), reply.text());
		} finally {
			run(RequestContext.of(owner), "v/ops/telegram/delete", Maps.of("name", "templated"));
		}
	}

	@Test
	public void testTelegramSkillHierarchy() {
		RequestContext ctx = RequestContext.of(owner);
		ACell telegramSkill = engine.resolvePath(
			Strings.create("v/skills/adapters/telegram"), ctx);
		ACell managementSkill = engine.resolvePath(
			Strings.create("v/skills/adapters/telegram-bot-management"), ctx);

		assertNotNull(telegramSkill);
		assertNotNull(managementSkill);
		assertEquals(Vectors.of((ACell) Strings.create(
			"v/skills/adapters/telegram-bot-management")),
			RT.getIn(telegramSkill, "skill", "skills"));
		assertEquals(Vectors.of(
			(ACell) Strings.create("v/ops/telegram/create"),
			Strings.create("v/ops/telegram/delete"),
			Strings.create("v/ops/telegram/bots")),
			RT.getIn(managementSkill, "skill", "tools"));
		assertEquals(Vectors.of((ACell) Strings.create("v/skills/auth/secrets")),
			RT.getIn(managementSkill, "skill", "skills"));
		assertEquals(managementSkill, engine.resolvePath(
			Strings.create("v/adapters/telegram/skills/telegram-bot-management"), ctx));
	}

	@Test
	@ResourceLock(value = ADAPTER, mode = ResourceAccessMode.READ_WRITE)
	public void testDisabledAdapterIsOfflineUntilReenabled() throws Exception {
		BotRunner runner = startEcho();
		long chat = 1004L;
		engine.disableAdapter("telegram");
		try {
			await(runner::isOffline, 2_000,
				() -> "Telegram poll loop did not park after adapter disablement");
			push(chat, ALLOWED_ID, "alice", "while disabled");
			assertNull(awaitSent(0), "a disabled adapter must not act on Telegram traffic");
		} finally {
			engine.enableAdapter("telegram");
		}
		// Nothing was confirmed while offline, so Telegram redelivers the backlog first.
		FakeTelegramServer.Sent backlog = awaitSent(15_000);
		assertNotNull(backlog, "the message received while disabled is answered once re-enabled");
		assertTrue(backlog.text().contains("while disabled"), backlog.text());
		push(chat, ALLOWED_ID, "alice", "after enable");
		FakeTelegramServer.Sent reply = awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("after enable"), reply.text());
	}

	// ---------------------------------------------------------------- outbound

	@Test
	public void testSendOperationByOwner() throws Exception {
		startEcho();
		ACell out = run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(ALLOWED_ID),
			"text", "hello there",
			"disable_notification", convex.core.data.prim.CVMBool.TRUE,
			"reply_parameters", Maps.of("message_id", 77),
			"reply_markup", Maps.of("inline_keyboard", Vectors.of(Vectors.of(
				Maps.of("text", "Yes", "callback_data", "yes"), Maps.of("text", "No", "callback_data", "no"))))));
		FakeTelegramServer.Sent sent = awaitSent(10_000);
		assertNotNull(sent);
		assertEquals("hello there", sent.text());
		assertEquals(ALLOWED_ID, sent.chatId());
		assertTrue(sent.silent());
		assertEquals(77, sent.replyTo(), "reply_parameters passed through as JSON");
		assertTrue(sent.form().get("reply_markup").contains("callback_data"), "reply_markup passed through as JSON: " + sent.form());
		// The result is Telegram's Message, not a translation (single owned bot was the default)
		assertEquals(CVMLong.create(sent.messageId()), RT.getIn(out, "message_id"));
		assertEquals(CVMLong.create(ALLOWED_ID), RT.getIn(out, "chat", "id"));
	}

	@Test
	public void testSendIsDeniedToOtherUsers() throws Exception {
		startEcho();
		Job job = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_BOT, echo,
			"chat_id", CVMLong.create(3002L),
			"text", "sneaky"), RequestContext.of(other));
		try {
			job.awaitResult(10_000);
		} catch (Exception ignored) {
			// failure surfaces on the job
		}
		assertEquals(Status.FAILED, job.getStatus());
		assertFalse(String.valueOf(job.getErrorMessage()).isBlank());
		assertTrue(drainSent().isEmpty(), "nothing must reach Telegram");
	}

	@Test
	public void testSendUnknownBotAndNoDefaultBot() {
		Job unknown = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			TelegramAdapter.K_BOT, "nope", "chat_id", CVMLong.create(1), "text", "x"),
			RequestContext.of(owner));
		try { unknown.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, unknown.getStatus());
		assertEquals(AMessagingAdapter.UNKNOWN_BOT.formatted("Telegram", "nope"), unknown.getErrorMessage());

		Job none = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(1), "text", "x"), RequestContext.of(other));
		try { none.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, none.getStatus());
		assertEquals(AMessagingAdapter.NO_BOT.formatted("Telegram", other), none.getErrorMessage());
	}

	@Test
	public void testSendFallsBackToPlainTextWhenMarkupRejected() throws Exception {
		startEcho();
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(ALLOWED_ID),
			"text", "*unbalanced " + FakeTelegramServer.BAD_MARKUP,
			"parse_mode", "Markdown"));
		FakeTelegramServer.Sent sent = awaitSent(10_000);
		assertNotNull(sent);
		assertNull(sent.parseMode(), "resent as plain text after Telegram rejected the markup");
		assertTrue(sent.text().contains(FakeTelegramServer.BAD_MARKUP));
	}

	@Test
	public void testSendSplitsLongText() throws Exception {
		startEcho();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 700; i++) sb.append("line ").append(i).append(" of a rather long message\n");
		String text = sb.toString();
		assertTrue(text.length() > 2 * BotRunner.MAX_MESSAGE_LENGTH);
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(ALLOWED_ID), "text", text));
		List<String> expected = MessagingText.split(text, BotRunner.MAX_MESSAGE_LENGTH);
		List<String> actual = new java.util.ArrayList<>(expected.size());
		for (int i = 0; i < expected.size(); i++) {
			FakeTelegramServer.Sent sent = awaitSent(i == 0 ? 10_000 : 0);
			assertNotNull(sent, "missing chunk " + i);
			actual.add(sent.text());
		}
		assertEquals(expected, actual, "send every split chunk exactly once and in order");
		assertNull(awaitSent(0), "send no extra chunks");
	}

	@Test
	public void testSplitPrefersLineBreaks() {
		String text = "aaaa\nbbbb\ncccc";
		assertEquals(java.util.List.of("aaaa\nbbbb", "cccc"), MessagingText.split(text, 10));
		assertEquals(java.util.List.of("abcdefghij", "klm"), MessagingText.split("abcdefghijklm", 10));
		assertEquals(java.util.List.of("short"), MessagingText.split("short", 10));
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

	// ------------------------------------------------------- outbound confinement

	@Test
	public void testAllowListedBotOnlyMessagesItsAllowedUsers() throws Exception {
		startEcho();
		// The echo bot allows ALLOWED_ID and @alice. Its user may use it, but only
		// to reach those people: any other party a call addresses is refused
		// before anything goes to Telegram — the gate says who may use the bot,
		// this says whom the bot may reach.
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(STRANGER_ID), "text", "psst"), RequestContext.of(owner)),
			String.valueOf(STRANGER_ID));
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", "@somechannel", "text", "psst"), RequestContext.of(owner)),
			"@somechannel");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "sendPhoto", "params", Maps.of("chat_id", CVMLong.create(STRANGER_ID), "photo", "x")),
			RequestContext.of(owner)), String.valueOf(STRANGER_ID));
		// A forward or copy addresses two chats: the source would carry that chat's
		// content, the destination would be talked to. Both must be allowed.
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "forwardMessage", "params", Maps.of("chat_id", CVMLong.create(ALLOWED_ID),
				"from_chat_id", CVMLong.create(-2001L), "message_id", 5)),
			RequestContext.of(owner)), "-2001");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "getUserProfilePhotos", "params", Maps.of("user_id", CVMLong.create(STRANGER_ID))),
			RequestContext.of(owner)), String.valueOf(STRANGER_ID));
		assertTrue(drainSent().isEmpty(), "nothing must reach Telegram");

		// The allowed user's private chat (its id is their user id), as a numeric string too
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of(
			"chat_id", String.valueOf(ALLOWED_ID), "text", "for you"));
		FakeTelegramServer.Sent sent = awaitSent(10_000);
		assertNotNull(sent);
		assertEquals(ALLOWED_ID, sent.chatId());
		assertEquals("for you", sent.text());
	}

	@Test
	public void testAllowListedBotConversesOnlyInPrivateChats() throws Exception {
		startEcho();
		long group = -4001L;
		// An allowed user writes in a group: a reply there would be read by everyone
		// present, so the update is ignored — commands included…
		int inGroup = telegram.push(echoToken, group, "supergroup", ALLOWED_ID, "alice", "hey bot, in the group");
		int command = telegram.push(echoToken, group, "group", ALLOWED_ID, "alice", "/id");
		// …while their private chat is answered as usual.
		push(ALLOWED_ID, ALLOWED_ID, "alice", "and in private");
		FakeTelegramServer.Sent reply = awaitSent(15_000);
		assertNotNull(reply);
		assertEquals(ALLOWED_ID, reply.chatId(), "only the private chat is answered: " + reply.text());
		assertTrue(reply.text().contains("and in private"), reply.text());
		// The group updates came first and were dropped as they were read, with
		// no receipt, so nothing was queued that could reply later.
		assertNull(echoInbox().get(Integer.toString(inGroup)), "nothing is queued for the group");
		assertNull(echoInbox().get(Integer.toString(command)), "nothing is queued for a command in the group");
		assertNull(awaitSent(0), "no reply in the group, not even to a command");
		assertNull(readSession(echo, group), "no conversation is started for the group");
	}

	@Test
	public void testUsernameAllowEntryResolvesToItsAccountOnFirstContact() throws Exception {
		createAgent();
		String token = "444:NAMED-BOT";
		telegram.registerBot(token, "named_bot");
		AMap<AString, ACell> namedCfg = botConfig(token, owner.toString(), "agent", AGENT, Vectors.of(Strings.create("@carol")));
		declare("named", namedCfg);
		awaitState("named", BotRunner.State.RUNNING, 10_000);
		long carol = 5005L;
		// Before Carol has written there is no account to address: a handle is an unresolved id.
		assertFails(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"bot", "named", "chat_id", CVMLong.create(carol), "text", "hi"), RequestContext.of(owner)));

		// Carol writes: admitted by handle and answered, and Telegram's authenticated
		// sender resolves @carol to her account — kept beside the sessions, allow untouched.
		telegram.push(token, carol, "private", carol, "Carol", "hello named bot");
		FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
		assertNotNull(reply, "an @username entry admits the person's messages");
		assertEquals(carol, reply.chatId());
		assertEquals(CVMLong.create(carol), adapter.state().read("config/named/resolved/carol"),
			"the binding is persisted in the bot's adapter state, lower-cased");
		ACell status = adapter.runner("named").status();
		assertEquals(CVMLong.create(carol), RT.getIn(status, BotRunner.K_RESOLVED, "carol"),
			"status shows the binding: " + status);

		// Now the bot may message her…
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of(
			"bot", "named", "chat_id", CVMLong.create(carol), "text", "hi carol"));
		assertEquals("hi carol", telegram.awaitSent(token, 10_000).text());

		// …also after the bot restarts (a reconfigure that changes it replaces the runner)
		declare("named", namedCfg.assoc(BotSpec.K_GREETING, Strings.create("Hi!")));
		awaitState("named", BotRunner.State.RUNNING, 10_000);
		run(RequestContext.of(owner), "v/ops/telegram/send", Maps.of(
			"bot", "named", "chat_id", CVMLong.create(carol), "text", "still you"));
		assertEquals("still you", telegram.awaitSent(token, 10_000).text());

		// A different account that later holds @carol is not Carol: refused at the
		// front door, the first resolution stands, and it is not addressable.
		long impostor = 5006L;
		telegram.push(token, impostor, "private", impostor, "carol", "it's me, carol");
		FakeTelegramServer.Sent refused = telegram.awaitSent(token, 10_000);
		assertNotNull(refused);
		assertEquals(impostor, refused.chatId());
		assertTrue(refused.text().startsWith("Not authorised"), refused.text());
		assertEquals(CVMLong.create(carol), adapter.state().read("config/named/resolved/carol"));
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"bot", "named", "chat_id", CVMLong.create(impostor), "text", "hi"), RequestContext.of(owner)),
			String.valueOf(impostor));
	}

	// ------------------------------------------------------------------ status

	@Test
	public void testPendingRetryWarningIsDeduplicated() {
		assertTrue(BotRunner.shouldWarnPending(BotRunner.State.PENDING,
			"same reason", 2, "same reason"), "the first slow retry warns once");
		assertFalse(BotRunner.shouldWarnPending(BotRunner.State.PENDING,
			"same reason", 3, "same reason"), "unchanged retries are debug-only");
		assertTrue(BotRunner.shouldWarnPending(BotRunner.State.PENDING,
			"old reason", 3, "new reason"), "a changed failure remains visible");
		assertTrue(BotRunner.shouldWarnPending(BotRunner.State.RUNNING,
			null, 4, "outage"), "a new outage after recovery warns");
	}

	@Test
	public void testBotsListsOnlyTheCallersBots() throws Exception {
		startEcho();
		ACell mine = run(RequestContext.of(owner), "v/ops/telegram/bots", Maps.empty());
		AVector<ACell> bots = RT.ensureVector(RT.getIn(mine, TelegramAdapter.K_BOTS));
		assertNotNull(bots);
		boolean found = false;
		for (long i = 0; i < bots.count(); i++) {
			ACell b = bots.get(i);
			if (Strings.create(echo).equals(RT.getIn(b, Fields.NAME))) {
				found = true;
				assertEquals(Strings.create("RUNNING"), RT.getIn(b, BotRunner.K_STATE));
				assertEquals(Strings.create(echoUsername), RT.getIn(b, BotRunner.K_USERNAME));
				assertEquals(Strings.create("agent " + AGENT), RT.getIn(b, BotRunner.K_TARGET));
				assertNull(RT.getIn(b, BotSpec.K_TOKEN), "tokens are never listed");
			}
		}
		assertTrue(found, "owner sees their bot: " + mine);

		ACell theirs = run(RequestContext.of(other), "v/ops/telegram/bots", Maps.empty());
		assertEquals(0L, RT.ensureVector(RT.getIn(theirs, TelegramAdapter.K_BOTS)).count(),
			"another user sees no bots they don't own");

		ACell all = run(engine.venueContext(), "v/ops/telegram/bots", Maps.empty());
		assertTrue(RT.ensureVector(RT.getIn(all, TelegramAdapter.K_BOTS)).count() >= 1, "the venue sees every bot");
	}

	@Test
	public void testPendingBotStartsWhenSecretIsProvisioned() throws Exception {
		String token = "333:LATE-SECRET";
		telegram.registerBot(token, "late_bot");
		declare("late", botConfig("s/TG_LATE_TOKEN", owner.toString(), "agent", AGENT, null));
		awaitState("late", BotRunner.State.PENDING, 10_000);
		assertTrue(adapter.runner("late").error().contains("TG_LATE_TOKEN"), adapter.runner("late").error());
		assertEquals(0, telegram.getMeCalls(token), "no token, no Telegram call");

		ACell status = run(RequestContext.of(owner), "v/ops/telegram/bots", Maps.empty());
		assertTrue(status.toString().contains("PENDING"), status.toString());

		// Provisioning the secret in the bot user's store brings it up on the next retry.
		run(RequestContext.of(owner), "v/ops/secret/set", Maps.of("name", "TG_LATE_TOKEN", "value", token));
		awaitState("late", BotRunner.State.RUNNING, 10_000);
		assertEquals("late_bot", adapter.runner("late").username());
		assertNull(adapter.runner("late").error());
	}

	@Test
	public void testOnlyOperatorBotsUseVenueSecrets() throws Exception {
		String token = "444:VENUE-ONLY";
		telegram.registerBot(token, "venue_bot");
		run(engine.venueContext(), "v/ops/secret/set", Maps.of("name", "TG_VENUE_ONLY", "value", token));
		try {
			// A user's bot naming the venue's secret, bare or by the venue's DID, gets
			// nothing: the reference is the user's, so it never resolves as the venue.
			run(RequestContext.of(other), "v/ops/telegram/create", Maps.of(
				"name", "borrowed", "token", "s/TG_VENUE_ONLY", "agent", AGENT));
			run(RequestContext.of(other), "v/ops/telegram/create", Maps.of(
				"name", "named", "token", engine.getDIDString() + "/s/TG_VENUE_ONLY", "agent", AGENT));
			BotRunner borrowed = adapter.runner(other, "borrowed");
			BotRunner named = adapter.runner(other, "named");
			await(() -> borrowed.state() == BotRunner.State.PENDING && named.state() == BotRunner.State.PENDING,
				10_000, () -> "user bots must stay pending: " + borrowed.error() + " / " + named.error());
			assertTrue(borrowed.error().contains("TG_VENUE_ONLY"), borrowed.error());
			assertTrue(named.error().contains("token resolution failed"), named.error());
			assertEquals(0, telegram.getMeCalls(token), "the venue's token never reaches Telegram for a user's bot");

			// The operator wrote this reference, so the venue's store backs it.
			declare("operator", botConfig("s/TG_VENUE_ONLY", owner.toString(), "agent", AGENT, null));
			awaitState("operator", BotRunner.State.RUNNING, 10_000);
			assertEquals("venue_bot", adapter.runner("operator").username());
		} finally {
			run(RequestContext.of(other), "v/ops/telegram/delete", Maps.of("name", "borrowed"));
			run(RequestContext.of(other), "v/ops/telegram/delete", Maps.of("name", "named"));
		}
	}

	@Test
	public void testBadTokenIsPendingWithTelegramError() throws Exception {
		declare("bad", botConfig("999:NOT-REGISTERED", owner.toString(), "agent", AGENT, null));
		awaitState("bad", BotRunner.State.PENDING, 10_000);
		assertTrue(adapter.runner("bad").error().contains("401"), adapter.runner("bad").error());
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

	/** This test's echo bot: its user's agent, allowing ALLOWED_ID and @alice. */
	private AMap<AString, ACell> echoBotConfig() {
		return botConfig(echoToken, owner.toString(), "agent", AGENT,
			Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@alice")));
	}

	/** Creates the test user's agent: llmagent over the echoing test LLM, so its reply is the user's own words. */
	private void createAgent() {
		if (agentCreated) return;
		run(RequestContext.of(owner), "v/ops/agent/create", Maps.of(
			Fields.AGENT_ID, AGENT,
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, "v/ops/llmagent/chat",
				"llmOperation", "v/test/ops/llm",
				"systemPrompt", "Echo the user.",
				"caps", "unrestricted")));
		agentCreated = true;
	}

	/** Declares this test's echo bot with its usual settings and waits until it runs. */
	private BotRunner startEcho() throws InterruptedException {
		return startEcho(echoBotConfig());
	}

	/** Declares this test's echo bot with {@code config} and waits until it runs. */
	private BotRunner startEcho(AMap<AString, ACell> config) throws InterruptedException {
		createAgent();
		declare(echo, config);
		awaitState(echo, BotRunner.State.RUNNING, 10_000);
		return adapter.runner(echo);
	}

	/**
	 * Restarts the echo bot with {@code config} and waits until the new runner
	 * has replayed what it recovered for {@code chat}: it queues that replay
	 * before its first poll, and the chat's queue then drains it.
	 */
	private void restartEcho(AMap<AString, ACell> config, long chat) throws Exception {
		declare(echo, null);
		int polls = telegram.polls(echoToken);
		declare(echo, config);
		telegram.awaitPoll(echoToken, polls, 10_000);
		adapter.runner(echo).whenChatIdle(chat).get(10, java.util.concurrent.TimeUnit.SECONDS);
	}

	/**
	 * Declares one operator bot, or with a null {@code config} removes it,
	 * leaving every other test's bots as they are: the adapter restarts only a
	 * bot whose settings change.
	 */
	private void declare(String name, AMap<AString, ACell> config) {
		declared.add(name);
		synchronized (DECLARE) {
			AString key = Strings.create(name);
			configureBots(config == null ? declaredBots.dissoc(key) : declaredBots.assoc(key, config));
		}
	}

	/** Gives the adapter this whole set of declared bots; the caller holds {@link #DECLARE}. */
	private static void configureBots(AMap<AString, ACell> bots) {
		engine.configureAdapter("telegram", Maps.of(
			TelegramAdapter.K_API_URL, Strings.create(telegram.apiUrl()),
			TelegramAdapter.K_BOTS, bots));
		declaredBots = bots;
	}

	/** Queues a private-chat message to this test's echo bot. */
	private int push(long chat, long from, String username, String text) {
		return telegram.push(echoToken, chat, "private", from, username, text);
	}

	/** The echo bot's next outbound message, waiting up to {@code timeoutMs}; null on timeout. */
	private FakeTelegramServer.Sent awaitSent(long timeoutMs) throws InterruptedException {
		return telegram.awaitSent(echoToken, timeoutMs);
	}

	private List<FakeTelegramServer.Sent> drainSent() {
		return telegram.drainSent(echoToken);
	}

	private covia.adapter.webhook.WebhookInbox echoInbox() {
		return new covia.adapter.webhook.WebhookInbox(engine, engine.adapterState("telegram"),
			"config/" + echo + "/inbox/" + owner.getHash().toHexString() + "/42");
	}

	private static ACell run(RequestContext ctx, String op, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		ACell result = job.awaitResult(30_000);
		assertEquals(Status.COMPLETE, job.getStatus(), op + ": " + job.getErrorMessage());
		return result;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> firstTurn(String sid) {
		var agent = engine.getVenueState().users().user(owner).agent(Strings.create(AGENT));
		var frames = (AVector<ACell>) agent.getSession(Blob.fromHex(sid)).get(Agent.KEY_FRAMES);
		return (AMap<AString, ACell>) covia.adapter.agent.GoalTreeContext.activeConversation(RT.ensureMap(frames.get(0))).get(0);
	}

	private String readSession(String bot, long chatId) {
		BotRunner runner=adapter.runner(owner,bot);
		if(runner==null)runner=adapter.runner(bot);
		if(runner==null)return null;
		ACell v=adapter.state().read(runner.sessionsRelativePath()+"/"+chatId);
		return (v == null) ? null : v.toString();
	}

	/** The first job of {@code ctx} not in {@code known} whose input carries {@code marker}. */
	private static Job awaitNewJob(RequestContext ctx, Set<Blob> known, String marker, long timeoutMs)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() <= deadline) {
			for (Blob id : engine.jobs().getJobs(ctx).keySet()) {
				if (known.contains(id)) continue;
				Job job = engine.jobs().getJob(id, ctx);
				if (String.valueOf(RT.getIn(job.getData(), Fields.INPUT)).contains(marker)) return job;
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
