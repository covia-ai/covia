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
import covia.adapter.messaging.AMessagingAdapter;
import covia.adapter.messaging.MessagingText;
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
	public void preparedEngineDefersBotWorkersUntilActivation() throws Exception {
		try (FakeTelegramServer fake = new FakeTelegramServer()) {
			var key = convex.core.crypto.AKeyPair.generate();
			AMap<AString, ACell> botSettings = Maps.of("apiUrl", fake.apiUrl(), "bots",
				Maps.of("startup", botConfig(FakeTelegramServer.TOKEN, OWNER.toString(),
					"operation", "v/test/ops/echo", Vectors.of(CVMLong.create(ALLOWED_ID)))));
			Engine prepared = new Engine(Maps.of(Config.ADAPTERS, Maps.of("telegram", botSettings)),
				covia.venue.CoviaApplication.create(key), key).prepare();
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
		assertTrue(spec.allowsTarget(5L) && spec.allowsTarget("77") && spec.allowsTarget(" 5 "),
			"listed ids as numbers or numeric strings");
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
		await(() -> "[]".equals(telegram.lastFilters.get(token)), 10_000, () -> "operation bot did not reset allowed_updates");
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
			"params", Maps.of("chat_id", CVMLong.create(ALLOWED_ID), "photo", "https://example.org/cat.jpg",
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
		run(RequestContext.of(OWNER), "v/ops/agent/create", Maps.of(Fields.AGENT_ID, "tg-vision", Fields.CONFIG,
			Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/ops/telegram-vision/llm")));
		try {
			configureBots(Maps.of("echo", echoBotConfig().assoc(BotSpec.K_AGENT, Strings.create("tg-vision"))));
			awaitState("echo", BotRunner.State.RUNNING, 10_000);
			telegram.pushPhoto(FakeTelegramServer.TOKEN, 1006L, ALLOWED_ID, "alice", null);
			FakeTelegramServer.Sent reply = telegram.awaitSent(15_000);
			assertNotNull(reply);
			assertEquals("Photo received", reply.text());
			assertNotNull(readSession("echo", 1006L));
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
		} finally {
			configureBots(Maps.of("echo", echoBotConfig()));
			awaitState("echo", BotRunner.State.RUNNING, 10_000);
		}
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
				assertEquals(OWNER, ctx.getUserDID());
				assertEquals(CVMLong.create(15001), RT.getIn(input, "via", "chat", "id"));
				assertEquals(Strings.create("echo"), RT.getIn(input, "via", "bot"));
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
		try {
			configureBots(Maps.of("echo", echoBotConfig().assoc(BotSpec.K_MEDIA_OPERATION, Strings.create("v/ops/telegram-test/transcribe"))));
			awaitState("echo", BotRunner.State.RUNNING, 10_000);
			var message = telegram.message(15001, "private", ALLOWED_ID, "alice");
			message.put("voice", java.util.Map.of("file_id", fileId, "file_unique_id", "voiceu", "duration", 3));
			message.put("caption", "Transcribe this note");
			telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", message)));
			var reply = telegram.awaitSent(15_000);
			assertNotNull(reply);
			assertTrue(reply.text().contains("Please remind me about my appointment"), reply.text());
			assertTrue(reply.text().contains("application-record-17"), "structured application result is retained");
			// A speech-only operation need not invent text for every other media type.
			message = telegram.message(15001, "private", ALLOWED_ID, "alice");
			message.put("document", java.util.Map.of("file_id", fileId, "file_unique_id", "docu", "file_name", "note.ogg"));
			message.put("caption", "Keep this original file");
			telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", message)));
			reply = telegram.awaitSent(15_000);
			assertNotNull(reply);
			assertTrue(reply.text().contains("Keep this original file") && reply.text().contains("/dlfs/telegram/"), reply.text());
		} finally {
			configureBots(Maps.of("echo", echoBotConfig()));
			awaitState("echo", BotRunner.State.RUNNING, 10_000);
		}
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
				assertThrows(IllegalArgumentException.class, () -> TelegramMedia.processed(attachment, invalid)).getMessage());
		}
		assertEquals("Rendered", BotRunner.formattedReply(Strings.create("Rendered")));
		assertEquals("Rendered", BotRunner.formattedReply(Maps.of("text", "Rendered")));
		for (ACell invalid : new ACell[] {null, Maps.empty(), Maps.of("internalCard", "do not leak"), Strings.EMPTY}) {
			assertEquals(BotRunner.INVALID_REPLY,
				assertThrows(IllegalArgumentException.class, () -> BotRunner.formattedReply(invalid)).getMessage());
		}
		for (AString option : List.of(BotSpec.K_MEDIA_OPERATION, BotSpec.K_REPLY_OPERATION)) {
			assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("bad", Maps.of("token", "t", "user", OWNER,
				"operation", "v/test/ops/echo").assoc(option, Strings.create("v/test/ops/echo")), true));
		}
	}

	@Test
	public void runtimeProcessingAndReplyHooksSurviveRearming() throws Exception {
		String token = "778:HOOKS-BOT";
		telegram.registerBot(token, "hooks_bot");
		run(RequestContext.of(OWNER), "v/ops/secret/set", Maps.of("name", "TG_HOOKS", "value", token));
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var malformed = new java.util.concurrent.atomic.AtomicBoolean();
		engine.registerAdapter(new covia.adapter.AAdapter() {
			@Override public String getName() { return "telegram-format"; }
			@Override public String getDescription() { return "Application reply formatter fixture"; }
			@Override protected void installAssets() {
				installAsset("telegram-format/reply", Maps.of("name", "Format fixture", "operation", Maps.of("adapter", "telegram-format:reply")));
			}
			@Override public java.util.concurrent.CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
				assertEquals(OWNER, ctx.getUserDID());
				assertEquals(Strings.create("hooks"), RT.getIn(input, "via", "bot"));
				assertEquals(CVMLong.create(ALLOWED_ID), RT.getIn(input, "via", "chat", "id"));
				assertTrue(RT.getIn(input, "response").toString().contains("APPLICATION_CARD"));
				calls.incrementAndGet();
				if (malformed.get()) return java.util.concurrent.CompletableFuture.completedFuture(Maps.of("internalCard", "APPLICATION_CARD"));
				return java.util.concurrent.CompletableFuture.completedFuture(Maps.of("text", "Readable application reply"));
			}
		});
		run(RequestContext.of(OWNER), "v/ops/telegram/create", Maps.of("name", "hooks", "token", "s/TG_HOOKS", "agent", AGENT,
			"allow", Vectors.of(ALLOWED_ID), "mediaOperation", "v/test/ops/echo", "replyOperation", "v/ops/telegram-format/reply"));
		try {
			for (int attempt = 0; attempt < 2; attempt++) {
				BotRunner runner = adapter.runner(OWNER, "hooks");
				await(() -> runner.state() == BotRunner.State.RUNNING, 10_000, () -> "hooks bot did not start");
				assertEquals("v/test/ops/echo", runner.spec().mediaOperation());
				assertEquals("v/ops/telegram-format/reply", runner.spec().replyOperation());
				telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "APPLICATION_CARD");
				var reply = telegram.awaitSent(token, 15_000);
				assertNotNull(reply);
				assertEquals("Readable application reply", reply.text());
				await(() -> runner.isChatIdle(ALLOWED_ID), 10_000, () -> "reply did not finish");
				if (attempt == 0) {
					adapter.forgetForTest(OWNER, "hooks");
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
			run(RequestContext.of(OWNER), "v/ops/telegram/delete", Maps.of("name", "hooks"));
		}
	}

	@Test
	public void formatAsTheReplyHookSendsTheAgentsMarkdownAsTelegramHtml() throws Exception {
		String token = "779:FORMAT-BOT";
		telegram.registerBot(token, "format_bot");
		run(RequestContext.of(OWNER), "v/ops/secret/set", Maps.of("name", "TG_FORMAT", "value", token));
		// No parseMode: the formatter's own parse_mode is the whole setup.
		run(RequestContext.of(OWNER), "v/ops/telegram/create", Maps.of("name", "formatted", "token", "s/TG_FORMAT", "agent", AGENT,
			"allow", Vectors.of(ALLOWED_ID), "replyOperation", "v/ops/telegram/format"));
		try {
			BotRunner runner = adapter.runner(OWNER, "formatted");
			await(() -> runner.state() == BotRunner.State.RUNNING, 10_000, () -> "formatted bot did not start");
			// The echo agent repeats the message, Markdown and all.
			telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "**Bold** & <tag>");
			var reply = telegram.awaitSent(token, 15_000);
			assertNotNull(reply);
			assertEquals(TelegramMarkdown.PARSE_MODE, reply.parseMode(), "sent under the parse mode the hook returned");
			assertTrue(reply.text().contains("<b>Bold</b>"), reply.text());
			TelegramMarkdownTest.assertTelegramHtml(reply.text());
			await(() -> runner.isChatIdle(ALLOWED_ID), 10_000, () -> "reply did not finish");
		} finally {
			run(RequestContext.of(OWNER), "v/ops/telegram/delete", Maps.of("name", "formatted"));
		}
	}

	@Test
	public void pendingReceiptSurvivesRunnerRestartAndCompletedReceiptIsNotReplayed() throws Exception {
		configureBots(Maps.empty());
		var inbox = new covia.adapter.webhook.WebhookInbox(engine, engine.adapterWorkspace("telegram"), "config/echo/inbox/" + OWNER.getHash().toHexString() + "/42");
		var message = telegram.message(15002, "private", ALLOWED_ID, "alice");
		message.put("text", "Recover this pending turn");
		ACell update = convex.core.util.JSON.parse(convex.core.util.JSON.toString(java.util.Map.of("update_id", 900001, "message", message)));
		inbox.accept("900001", update);
		inbox.accept("900002", update);
		inbox.claim("900002");
		configureBots(Maps.of("echo", echoBotConfig()));
		awaitState("echo", BotRunner.State.RUNNING, 10_000);
		var reply = telegram.awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("Recover this pending turn"), reply.text());
		await(() -> covia.adapter.webhook.WebhookInbox.COMPLETE.equals(inbox.get("900001").get(Fields.STATUS)), 10_000, () -> "receipt did not complete");
		inbox.accept("900001", update);
		configureBots(Maps.empty());
		configureBots(Maps.of("echo", echoBotConfig()));
		awaitState("echo", BotRunner.State.RUNNING, 10_000);
		assertNull(telegram.awaitSent(600), "completed or uncertain receipts must not run again");
		assertEquals(covia.adapter.webhook.WebhookInbox.STARTED, inbox.get("900002").get(Fields.STATUS));
	}

	@Test
	public void voiceAndDocumentBytesAreStoredPrivately() throws Exception {
		for (String kind : List.of("voice", "document")) {
			String fileId = "test_" + kind;
			byte[] bytes = ("bytes for " + kind).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			telegram.files.put(fileId, bytes);
			var message = telegram.message(14001, "private", ALLOWED_ID, "alice");
			message.put(kind, java.util.Map.of("file_id", fileId, "file_unique_id", fileId + "u", "duration", 2,
				"mime_type", kind.equals("voice") ? "audio/ogg" : "application/pdf", "file_name", "document-transport.pdf"));
			message.put("caption", "Please read this");
			telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", message)));
			var reply = telegram.awaitSent(15_000);
			assertNotNull(reply);
			assertTrue(reply.text().contains(fileId) && reply.text().contains("Please read this"), reply.text());
			String ref = OWNER + "/dlfs/telegram/" + (kind.equals("document") ? "document-transport.pdf" : "echo-" + Strings.create(fileId).getHash().toHexString() + ".bin");
			var stored = engine.resolveContent(Strings.create(ref), RequestContext.of(OWNER));
			org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, stored.content().getBlob().getBytes());
			assertThrows(RuntimeException.class, () -> engine.resolveContent(Strings.create(ref), RequestContext.of(OTHER)));
			assertFalse(reply.text().contains(FakeTelegramServer.TOKEN));
		}
	}

	@Test
	public void unauthorisedMediaIsNotDownloaded() throws Exception {
		int before = telegram.downloads.get();
		telegram.pushPhoto(FakeTelegramServer.TOKEN, 14002, STRANGER_ID, "stranger", "private photo");
		assertNotNull(telegram.awaitSent(10_000));
		assertEquals(before, telegram.downloads.get());
	}

	@Test
	public void downloadAndMultipartUploadUseProtectedReferences() throws Exception {
		telegram.files.put("outbound", "voice-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		ACell downloaded = run(RequestContext.of(OWNER), "v/ops/telegram/download", Maps.of("file_id", "outbound"));
		ACell ref = RT.getIn(downloaded, Fields.REF);
		run(RequestContext.of(OWNER), "v/ops/telegram/call", Maps.of("method", "sendVoice",
			"params", Maps.of("chat_id", ALLOWED_ID), "files", Maps.of("voice", Maps.of("ref", ref, "filename", "note.ogg"))));
		var sent = telegram.awaitSent(10_000);
		assertEquals("sendVoice", sent.method());
		assertEquals("voice-bytes", sent.form().get("voice"));
		assertTrue(telegram.uploadTypes.get("voice").contains("filename=\"note.ogg\""));
		// A caller cannot use a file part as a substitute for the checked target parameter.
		assertThrows(RuntimeException.class, () -> run(RequestContext.of(OWNER), "v/ops/telegram/call", Maps.of("method", "sendVoice",
			"params", Maps.of("chat_id", STRANGER_ID), "files", Maps.of("voice", Maps.of("ref", ref)))));
	}

	@Test
	public void downloadAcceptsContentDestinationsAndPreservesCallerWriteScope() throws Exception {
		byte[] bytes = "downloaded-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		telegram.files.put("destination_test", bytes);
		RequestContext owner = RequestContext.of(OWNER);
		ACell result = run(owner, "v/ops/telegram/download", Maps.of("file_id", "destination_test", "destination", "dlfs/imports", "fileName", "note.ogg"));
		assertEquals(Strings.create("dlfs/imports/note.ogg"), RT.getIn(result, Fields.REF));
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,
			engine.resolveContent(Strings.create("dlfs/imports/note.ogg"), owner).content().getBlob().getBytes());

		AString file = Strings.create("file://tmp/telegram-download.ogg");
		RequestContext scoped = owner.withCaps(Vectors.of(
			convex.auth.ucan.Capability.create(Strings.create("v/ops/telegram/download"), Strings.create("invoke")),
			convex.auth.ucan.Capability.create(Strings.create(OWNER + "/telegram/echo"), TelegramAdapter.ABILITY_CALL),
			convex.auth.ucan.Capability.create(file, convex.auth.ucan.Capability.CRUD_WRITE)));
		result = run(scoped, "v/ops/telegram/download", Maps.of("file_id", "destination_test", "destination", "file://tmp/", "fileName", "telegram-download.ogg"));
		assertEquals(file, RT.getIn(result, Fields.REF));
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(file, owner).content().getBlob().getBytes());

		// Bot authority must not replace the caller's scope for an explicit destination.
		AString outside = Strings.create("file://tmp/telegram-outside-scope.ogg");
		assertTrue(engine.putContent(outside, new java.io.ByteArrayInputStream(new byte[] {7}), null, owner));
		assertThrows(RuntimeException.class, () -> run(scoped, "v/ops/telegram/download",
			Maps.of("file_id", "destination_test", "destination", "file://tmp", "fileName", "telegram-outside-scope.ogg")));
		org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {7}, engine.resolveContent(outside, owner).content().getBlob().getBytes());
		assertThrows(RuntimeException.class, () -> run(owner, "v/ops/telegram/download",
			Maps.of("file_id", "destination_test", "destination", OTHER + "/dlfs/imports", "fileName", "note.ogg")));
	}

	@Test
	public void originalNamesAndMimeTypesSurviveRestartAndDownloadCollisions() throws Exception {
		String fileId = "original_named_report";
		String original = "Lab report 日本語.pdf";
		byte[] bytes = "original-named-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		telegram.files.put(fileId, bytes);
		var message = telegram.message(16001, "private", ALLOWED_ID, "alice");
		message.put("document", java.util.Map.of("file_id", fileId, "file_unique_id", "originalu",
			"file_name", original, "mime_type", "application/pdf"));
		telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", message)));
		var reply = telegram.awaitSent(15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains(original), reply.text());
		AString originalRef = Strings.create(OWNER + "/dlfs/telegram/" + java.net.URLEncoder.encode(original, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(originalRef, RequestContext.of(OWNER)).content().getBlob().getBytes());
		await(() -> adapter.runner("echo").isChatIdle(16001), 10_000, () -> "document turn did not complete");
		configureBots(Maps.empty());
		configureBots(Maps.of("echo", echoBotConfig()));
		awaitState("echo", BotRunner.State.RUNNING, 10_000);
		ACell saved = run(RequestContext.of(OWNER), "v/ops/telegram/download", Maps.of("file_id", fileId));
		assertEquals(Strings.create("Lab report 日本語(1).pdf"), RT.getIn(saved, Fields.FILE_NAME));
		assertEquals(Strings.create(original), RT.getIn(saved, "file_name"));
		assertEquals(Strings.create("application/pdf"), RT.getIn(saved, Fields.CONTENT_TYPE), "getFile's generated .bin path must not replace message MIME metadata");
		org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(originalRef, RequestContext.of(OWNER)).content().getBlob().getBytes());
		saved = run(RequestContext.of(OWNER), "v/ops/telegram/download", Maps.of("file_id", fileId, "fileName", "chosen-name.pdf"));
		assertEquals(Strings.create("chosen-name.pdf"), RT.getIn(saved, Fields.FILE_NAME));
		assertEquals(Strings.create(OWNER + "/dlfs/telegram/chosen-name.pdf"), RT.getIn(saved, Fields.REF));
		saved = run(RequestContext.of(OWNER), "v/ops/telegram/download", Maps.of("file_id", fileId, "destination", "file://tmp"));
		assertEquals(Strings.create("file://tmp/" + original), RT.getIn(saved, Fields.REF));
	}

	@Test
	public void concurrentDownloadsNeverReplaceExistingContent() throws Exception {
		String fileId = "collision_test";
		byte[] bytes = "new-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		telegram.files.put(fileId, bytes);
		RequestContext owner = RequestContext.of(OWNER);
		for (String directory : List.of("file://tmp", "dlfs/collisions")) {
			String name = "collision-report.pdf";
			AString original = Strings.create(directory + "/" + name);
			assertTrue(engine.putContent(original, new java.io.ByteArrayInputStream(new byte[] {9}), null, owner));
			var calls = new java.util.ArrayList<java.util.concurrent.CompletableFuture<ACell>>();
			for (int i = 0; i < 6; i++) calls.add(java.util.concurrent.CompletableFuture.supplyAsync(() ->
				adapter.handleDownload(owner, Maps.of("file_id", fileId, "destination", directory, "fileName", name))));
			Set<ACell> refs = new HashSet<>();
			for (var call : calls) {
				ACell result = call.get(10, java.util.concurrent.TimeUnit.SECONDS);
				AString ref = RT.ensureString(RT.getIn(result, Fields.REF));
				assertTrue(refs.add(ref), "every concurrent download needs a different destination");
				assertTrue(RT.getIn(result, Fields.FILE_NAME).toString().matches("collision-report\\([1-6]\\)\\.pdf"));
				org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, engine.resolveContent(ref, owner).content().getBlob().getBytes());
			}
			org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {9}, engine.resolveContent(original, owner).content().getBlob().getBytes());
		}
	}

	@Test
	public void unsafeNamesAreRejectedOrFallBackWithoutLeavingTheDirectory() throws Exception {
		String fileId = "unsafe_original";
		telegram.files.put(fileId, new byte[] {4});
		for (String name : List.of("../escape.pdf", "a/b.pdf", "a\\b.pdf", "report.pdf:stream", "a%2fb", "a#b", "NUL", "report.", " padded ")) {
			assertFalse(TelegramMedia.safeFileName(name));
			assertEquals(TelegramMedia.INVALID_FILE_NAME, assertThrows(IllegalArgumentException.class, () ->
				adapter.handleDownload(RequestContext.of(OWNER), Maps.of("file_id", fileId, "fileName", name))).getMessage());
		}
		var message = telegram.message(16002, "private", ALLOWED_ID, "alice");
		message.put("document", java.util.Map.of("file_id", fileId, "file_unique_id", "unsafeu", "file_name", "../escape.pdf"));
		telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", message)));
		assertNotNull(telegram.awaitSent(15_000));
		String fallback = "echo-" + Strings.create(fileId).getHash().toHexString() + ".bin";
		assertNotNull(engine.resolveContent(Strings.create(OWNER + "/dlfs/telegram/" + fallback), RequestContext.of(OWNER)));
		ACell result = run(RequestContext.of(OWNER), "v/ops/telegram/download", Maps.of("file_id", fileId));
		assertEquals(Strings.create("../escape.pdf"), RT.getIn(result, "file_name"), "original name remains metadata, never a path");
		assertEquals(Strings.create(TelegramMedia.collisionName(fallback, 1)), RT.getIn(result, Fields.FILE_NAME));
	}

	@Test
	public void downloadRejectsMalformedAndUnsupportedDestinations() {
		telegram.files.put("invalid_destination", new byte[] {1});
		int before = telegram.downloads.get();
		for (ACell invalid : new ACell[] {Strings.EMPTY, Strings.create(" "), CVMLong.ONE}) {
			assertEquals(TelegramMedia.INVALID_DESTINATION, assertThrows(IllegalArgumentException.class,
				() -> adapter.handleDownload(RequestContext.of(OWNER), Maps.of("file_id", "invalid_destination", "destination", invalid))).getMessage());
		}
		assertEquals(before, telegram.downloads.get());
		var failure = assertThrows(IllegalArgumentException.class, () -> adapter.handleDownload(RequestContext.of(OWNER),
			Maps.of("file_id", "invalid_destination", "destination", "unsupported://destination")));
		assertTrue(failure.getMessage().startsWith(TelegramMedia.UNSUPPORTED_DESTINATION));
	}

	@Test
	public void downloadRejectsOversizedFilesBeforeTransfer() {
		telegram.files.put("oversized", new byte[] {1});
		telegram.declaredSizes.put("oversized", (long) TelegramMedia.MAX_BYTES + 1);
		int before = telegram.downloads.get();
		assertThrows(RuntimeException.class, () -> run(RequestContext.of(OWNER), "v/ops/telegram/download", Maps.of("file_id", "oversized")));
		assertEquals(before, telegram.downloads.get());
	}

	@Test
	public void streamingSizeLimitAndMultipartTargetChecksCannotBeBypassed() throws Exception {
		telegram.files.put("chunked", new byte[TelegramMedia.MAX_BYTES + 1]);
		telegram.chunkedFiles.add("chunked");
		try {
			RuntimeException failure = assertThrows(RuntimeException.class, () -> run(RequestContext.of(OWNER),
				"v/ops/telegram/download", Maps.of("file_id", "chunked")));
			assertFalse(failure.toString().contains(FakeTelegramServer.TOKEN));
		} finally { telegram.files.remove("chunked"); }
		assertThrows(RuntimeException.class, () -> run(RequestContext.of(OWNER), "v/ops/telegram/call",
			Maps.of("method", "sendVoice", "files", Maps.of("chat_id", Maps.of("ref", "dlfs/telegram/anything")))));
	}

	@Test
	public void forumTopicsHaveIndependentSessionsAndResetOnlyTheirOwnTopic() throws Exception {
		var sessions = new covia.adapter.messaging.ConversationSessions(engine.adapterWorkspace("telegram"), "config/echo/sessions");
		for (int topic : List.of(11, 22)) {
			var message = telegram.message(15003, "private", ALLOWED_ID, "alice");
			message.put("text", "Hello topic " + topic);
			message.put("is_topic_message", true);
			message.put("message_thread_id", topic);
			telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", message)));
			var reply = telegram.awaitSent(15_000);
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
		telegram.pushUpdate(FakeTelegramServer.TOKEN, new java.util.HashMap<>(java.util.Map.of("message", reset)));
		assertNotNull(telegram.awaitSent(10_000));
		assertNull(sessions.get(firstKey));
		assertEquals(second, sessions.get(secondKey));
	}

	@Test
	public void topicKeysAndReplyMetadataArePreserved() {
		var message = telegram.message(-12345, "supergroup", ALLOWED_ID, "alice");
		message.put("is_topic_message", true);
		message.put("message_thread_id", 101);
		message.put("media_group_id", "album1");
		var parsed = com.pengrad.telegrambot.utility.BotUtils.GSON.fromJson(convex.core.util.JSON.toString(message), com.pengrad.telegrambot.model.Message.class);
		String key = BotRunner.conversationKey(parsed, -12345L);
		assertNotEquals("-12345", key);
		assertEquals(CVMLong.create(101), RT.getIn(adapter.runner("echo").agentMessage(parsed, "hello"), "via", "message_thread_id"));
		message.put("message_thread_id", 102);
		parsed = com.pengrad.telegrambot.utility.BotUtils.GSON.fromJson(convex.core.util.JSON.toString(message), com.pengrad.telegrambot.model.Message.class);
		assertNotEquals(key, BotRunner.conversationKey(parsed, -12345L));
	}

	@Test
	public void testNonAsciiSurvivesBothDirections() throws Exception {
		String text = "em dash — ✅ ❌ £ é 日本語 🚀";
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of("chat_id", CVMLong.create(ALLOWED_ID), "text", text));
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
			"name", "mine", "token", token, "agent", AGENT), RequestContext.of(OWNER)), AMessagingAdapter.SECRET_REFERENCE_REQUIRED);
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT, "user", OTHER), RequestContext.of(OWNER)), AMessagingAdapter.IMPLICIT_USER);
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED"), RequestContext.of(OWNER)), "exactly one of agent");

		ACell created = run(RequestContext.of(OWNER), "v/ops/telegram/create", Maps.of(
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT,
			"allow", Vectors.of(CVMLong.create(ALLOWED_ID), Strings.create("@bob"))));
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
			"name", "mine", "token", "s/TG_CREATED", "agent", AGENT), RequestContext.of(OWNER)), AMessagingAdapter.DUPLICATE_BOT.formatted("Telegram", "mine"));
		ACell listing = run(RequestContext.of(OWNER), "v/ops/telegram/bots", Maps.empty());
		assertTrue(listing.toString().contains("runtime") && listing.toString().contains("\"mine\""), listing.toString());

		// It works: an inbound message reaches the agent through it, and it can send
		telegram.push(token, ALLOWED_ID, "private", ALLOWED_ID, "alice", "via created bot");
		FakeTelegramServer.Sent reply = telegram.awaitSent(token, 15_000);
		assertNotNull(reply);
		assertTrue(reply.text().contains("via created bot"), reply.text());
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of("bot", "mine", "chat_id", CVMLong.create(ALLOWED_ID), "text", "out"));
		assertEquals("out", telegram.awaitSent(token, 10_000).text());

		// A listed handle resolves to its account on first contact, in the bot's own adapter state
		long bob = 8002L;
		telegram.push(token, bob, "private", bob, "bob", "hi from bob");
		assertTrue(telegram.awaitSent(token, 15_000).text().contains("hi from bob"));
		String resolvedPath = adapter.userStatePath(OWNER, "resolved/mine/bob");
		assertEquals(CVMLong.create(bob), adapter.state().read(resolvedPath));

		// Simulated restart: forget the live runner, re-arm from the lattice registry
		adapter.forgetForTest(OWNER, "mine");
		assertNull(adapter.runner(OWNER, "mine"));
		adapter.rearmForTest();
		BotRunner rearmed = adapter.runner(OWNER, "mine");
		assertNotNull(rearmed, "a created bot is re-armed from the venue adapter workspace at install");
		await(() -> rearmed.state() == BotRunner.State.RUNNING, 10_000, () -> "re-armed bot did not start: " + rearmed.error());

		// …and the resolved handle comes back with it: Bob is addressable without writing again
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of("bot", "mine", "chat_id", CVMLong.create(bob), "text", "still bob"));
		assertEquals("still bob", telegram.awaitSent(token, 10_000).text());

		// Delete: stops it, removes the record and sessions; config bots are refused
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/delete", Maps.of("name", "echo"),
			RequestContext.of(OWNER)), AMessagingAdapter.CONFIG_BOT.formatted("echo"));
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/delete", Maps.of("name", "nope"),
			RequestContext.of(OWNER)), AMessagingAdapter.MISSING_BOT.formatted("Telegram", "nope"));
		ACell deleted = run(RequestContext.of(OWNER), "v/ops/telegram/delete", Maps.of("name", "mine"));
		assertEquals(convex.core.data.prim.CVMBool.TRUE, RT.getIn(deleted, TelegramAdapter.K_DELETED));
		assertNull(adapter.runner(OWNER, "mine"));
		assertEquals(BotRunner.State.STOPPED, rearmed.state());
		assertNull(engine.resolvePath(Strings.create(recordPath), engine.venueContext()), "record removed");
		assertNull(engine.resolvePath(Strings.create(adapter.state().path(
			adapter.userStatePath(OWNER, "sessions/mine/" + ALLOWED_ID))), engine.venueContext()), "sessions removed");
		assertNull(adapter.state().read(resolvedPath), "resolved handles removed");
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
	public void testTelegramSkillHierarchy() {
		RequestContext owner = RequestContext.of(OWNER);
		ACell telegramSkill = engine.resolvePath(
			Strings.create("v/skills/adapters/telegram"), owner);
		ACell managementSkill = engine.resolvePath(
			Strings.create("v/skills/adapters/telegram-bot-management"), owner);

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
			Strings.create("v/adapters/telegram/skills/telegram-bot-management"), owner));
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
			"chat_id", CVMLong.create(ALLOWED_ID),
			"text", "hello there",
			"disable_notification", convex.core.data.prim.CVMBool.TRUE,
			"reply_parameters", Maps.of("message_id", 77),
			"reply_markup", Maps.of("inline_keyboard", Vectors.of(Vectors.of(
				Maps.of("text", "Yes", "callback_data", "yes"), Maps.of("text", "No", "callback_data", "no"))))));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
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
		assertEquals(AMessagingAdapter.UNKNOWN_BOT.formatted("Telegram", "nope"), unknown.getErrorMessage());

		Job none = engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(1), "text", "x"), RequestContext.of(OTHER));
		try { none.awaitResult(10_000); } catch (Exception ignored) {}
		assertEquals(Status.FAILED, none.getStatus());
		assertEquals(AMessagingAdapter.NO_BOT.formatted("Telegram", OTHER), none.getErrorMessage());
	}

	@Test
	public void testSendFallsBackToPlainTextWhenMarkupRejected() throws Exception {
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(ALLOWED_ID),
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
			"chat_id", CVMLong.create(ALLOWED_ID), "text", text));
		List<String> expected = MessagingText.split(text, BotRunner.MAX_MESSAGE_LENGTH);
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
		// The echo bot allows ALLOWED_ID and @alice. Its user may use it, but only
		// to reach those people: any other party a call addresses is refused
		// before anything goes to Telegram — the gate says who may use the bot,
		// this says whom the bot may reach.
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", CVMLong.create(STRANGER_ID), "text", "psst"), RequestContext.of(OWNER)),
			"only messages its allowed users");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
			"chat_id", "@somechannel", "text", "psst"), RequestContext.of(OWNER)),
			"chat_id @somechannel is not one of them");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "sendPhoto", "params", Maps.of("chat_id", CVMLong.create(STRANGER_ID), "photo", "x")),
			RequestContext.of(OWNER)), "sendPhoto chat_id " + STRANGER_ID);
		// A forward or copy addresses two chats: the source would carry that chat's
		// content, the destination would be talked to. Both must be allowed.
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "forwardMessage", "params", Maps.of("chat_id", CVMLong.create(ALLOWED_ID),
				"from_chat_id", CVMLong.create(-2001L), "message_id", 5)),
			RequestContext.of(OWNER)), "from_chat_id -2001");
		assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/call", Maps.of(
			"method", "getUserProfilePhotos", "params", Maps.of("user_id", CVMLong.create(STRANGER_ID))),
			RequestContext.of(OWNER)), "user_id " + STRANGER_ID);
		assertTrue(telegram.drainSent().isEmpty(), "nothing must reach Telegram");

		// The allowed user's private chat (its id is their user id), as a numeric string too
		run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
			"chat_id", String.valueOf(ALLOWED_ID), "text", "for you"));
		FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
		assertNotNull(sent);
		assertEquals(ALLOWED_ID, sent.chatId());
		assertEquals("for you", sent.text());
	}

	@Test
	public void testAllowListedBotConversesOnlyInPrivateChats() throws Exception {
		long group = -4001L;
		// An allowed user writes in a group: a reply there would be read by everyone
		// present, so the update is ignored — commands included…
		telegram.push(FakeTelegramServer.TOKEN, group, "supergroup", ALLOWED_ID, "alice", "hey bot, in the group");
		telegram.push(FakeTelegramServer.TOKEN, group, "group", ALLOWED_ID, "alice", "/id");
		// …while their private chat is answered as usual.
		telegram.push(ALLOWED_ID, ALLOWED_ID, "alice", "and in private");
		FakeTelegramServer.Sent reply = telegram.awaitSent(15_000);
		assertNotNull(reply);
		assertEquals(ALLOWED_ID, reply.chatId(), "only the private chat is answered: " + reply.text());
		assertTrue(reply.text().contains("and in private"), reply.text());
		assertNull(telegram.awaitSent(1_000), "no reply in the group, not even to a command");
		assertNull(readSession("echo", group), "no conversation is started for the group");
	}

	@Test
	public void testUsernameAllowEntryResolvesToItsAccountOnFirstContact() throws Exception {
		String token = "444:NAMED-BOT";
		telegram.registerBot(token, "named_bot");
		AMap<AString, ACell> echoCfg = echoBotConfig();
		AMap<AString, ACell> namedCfg = botConfig(token, OWNER.toString(), "agent", AGENT, Vectors.of(Strings.create("@carol")));
		configureBots(Maps.of(Strings.create("echo"), echoCfg, Strings.create("named"), namedCfg));
		awaitState("named", BotRunner.State.RUNNING, 10_000);
		try {
			long carol = 5005L;
			// Before Carol has written there is no account to address: a handle is an unresolved id.
			assertFailsWith(engine.jobs().invokeOperation("v/ops/telegram/send", Maps.of(
				"bot", "named", "chat_id", CVMLong.create(carol), "text", "hi"), RequestContext.of(OWNER)),
				"once they have written to the bot");

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
			run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
				"bot", "named", "chat_id", CVMLong.create(carol), "text", "hi carol"));
			assertEquals("hi carol", telegram.awaitSent(token, 10_000).text());

			// …also after the bot restarts (a reconfigure that changes it replaces the runner)
			configureBots(Maps.of(Strings.create("echo"), echoCfg,
				Strings.create("named"), namedCfg.assoc(BotSpec.K_GREETING, Strings.create("Hi!"))));
			awaitState("named", BotRunner.State.RUNNING, 10_000);
			run(RequestContext.of(OWNER), "v/ops/telegram/send", Maps.of(
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
				"bot", "named", "chat_id", CVMLong.create(impostor), "text", "hi"), RequestContext.of(OWNER)),
				"only messages its allowed users");
		} finally {
			configureBots(Maps.of(Strings.create("echo"), echoCfg));
			adapter.state().delete("config/named/resolved");
		}
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
