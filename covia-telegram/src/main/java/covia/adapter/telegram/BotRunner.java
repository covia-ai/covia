package covia.adapter.telegram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.request.GetMe;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetMeResponse;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import com.pengrad.telegrambot.utility.BotUtils;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;

/**
 * The live side of one {@link BotSpec}: a Telegram client, its long-polling
 * update listener, per-chat conversation state, and outbound sending.
 *
 * <p>Lifecycle: {@link #start()} resolves the token and validates it with
 * {@code getMe} on a virtual thread, then long-polls {@code getUpdates} on
 * a dedicated virtual thread. A missing secret or unreachable API parks the
 * runner as {@link State#PENDING} and retries on the adapter's retry
 * schedule, so a bot whose token is provisioned after boot comes up by
 * itself. While the adapter is disabled the loop stops fetching without
 * confirming, so Telegram redelivers on re-enable — the bot is simply
 * offline. {@link #stop()} cancels the in-flight poll and is final.</p>
 *
 * <p>Inbound messages are serialised per Telegram chat (an agent session
 * accepts one chat at a time) and processed on virtual threads, so a slow
 * agent in one chat never blocks another. Every invocation runs as the bot's
 * configured user via {@link RequestContext#of}.</p>
 */
final class BotRunner {

	private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

	/** Telegram's hard limit on message text length. */
	static final int MAX_MESSAGE_LENGTH = 4096;
	/** Long-poll wait requested from Telegram, in seconds. */
	static final int POLL_TIMEOUT_SECS = 30;

	private static final String AGENT_CHAT = "v/ops/agent/chat";

	static final AString K_STATE = Strings.intern("state");
	static final AString K_USERNAME = Strings.intern("username");
	static final AString K_TARGET = Strings.intern("target");
	static final AString K_RECEIVED = Strings.intern("received");
	static final AString K_SENT = Strings.intern("sent");
	static final AString K_FAILED = Strings.intern("failed");
	static final AString K_MANAGED = Strings.intern("managed");
	private static final AString K_BOT = Strings.intern("bot");
	private static final AString K_VIA = Strings.intern("via");
	private static final AString K_CHANNEL = Strings.intern("channel");
	private static final AString K_ACCESS = Strings.intern("access");
	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_CHAT = Strings.intern("chat");
	private static final AString K_MESSAGE_ID = Strings.intern("message_id");

	enum State { STARTING, PENDING, RUNNING, STOPPED }

	/** How the bot came to exist: declared in venue config, or created at runtime by its user. */
	enum Managed { CONFIG, RUNTIME }

	private final TelegramAdapter adapter;
	final BotSpec spec;
	final String apiUrl;
	final Managed managed;

	private volatile TelegramBot bot;
	private volatile OkHttpClient http;
	/** The live token — held only for raw method calls; never logged or listed. */
	private volatile String token;
	private volatile String username;
	private volatile State state = State.STARTING;
	private volatile String error;
	private volatile boolean stopped;
	private volatile ScheduledFuture<?> retry;
	private volatile Thread pollThread;
	/** True once the poll loop has observed that its adapter is disabled. */
	private volatile boolean offline;

	private final AtomicLong received = new AtomicLong();
	private final AtomicLong sent = new AtomicLong();
	private final AtomicLong failed = new AtomicLong();
	private final AtomicInteger pollErrors = new AtomicInteger();
	private final AtomicInteger startAttempts = new AtomicInteger();
	/** getUpdates offset: one past the last confirmed update. */
	private int offset = 0;

	/** Per-chat serialisation: the tail of each chat's processing chain. */
	private final ConcurrentHashMap<Long, CompletableFuture<Void>> chatTails = new ConcurrentHashMap<>();
	/** chatId → agent session id (hex); mirrors the persisted mapping. */
	private final ConcurrentHashMap<Long, String> sessions = new ConcurrentHashMap<>();

	BotRunner(TelegramAdapter adapter, BotSpec spec, String apiUrl, Managed managed) {
		this.adapter = adapter;
		this.spec = spec;
		this.apiUrl = apiUrl;
		this.managed = managed;
	}

	// ---------------------------------------------------------------- lifecycle

	void start() {
		AAdapter.VIRTUAL_EXECUTOR.execute(this::tryStart);
	}

	private synchronized void tryStart() {
		if (stopped || state == State.RUNNING) return;
		String token;
		try {
			token = adapter.resolveToken(spec);
		} catch (RuntimeException e) {
			pending("token resolution failed: " + e.getMessage());
			return;
		}
		if (token == null) {
			pending("token secret " + spec.tokenRef() + " not found in the store of " + spec.userRef()
				+ " or the venue — store it with v/ops/secret/set");
			return;
		}
		// Our own client so stop() can cancel the in-flight long poll; the read
		// timeout must outlast the poll wait or every idle poll is an error.
		OkHttpClient client = new OkHttpClient.Builder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(POLL_TIMEOUT_SECS + 15, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.build();
		TelegramBot candidate = new TelegramBot.Builder(token).apiUrl(apiUrl).okHttpClient(client).build();
		GetMeResponse me;
		try {
			me = candidate.execute(new GetMe());
		} catch (RuntimeException e) {
			release(candidate, client);
			pending("Telegram API unreachable: " + concise(e));
			return;
		}
		if (me == null || !me.isOk() || me.user() == null) {
			release(candidate, client);
			pending("getMe rejected the token: " + describe(me));
			return;
		}
		if (stopped) {
			release(candidate, client);
			return;
		}
		username = me.user().username();
		bot = candidate;
		http = client;
		this.token = token;
		error = null;
		state = State.RUNNING;
		pollThread = Thread.ofVirtual().name("telegram-poll-" + spec.name()).start(this::pollLoop);
		log.info("Telegram bot '{}' (@{}) running as {} -> {}", spec.name(), username, spec.userRef(), spec.target());
	}

	private void pending(String why) {
		state = State.PENDING;
		error = why;
		// Config-bootstrapped secrets are provisioned after adapters install, so the
		// first attempt at boot commonly finds no token yet: retry that one quickly,
		// then settle into the slow cadence for genuine outages.
		int attempt = startAttempts.incrementAndGet();
		long delay = (attempt == 1) ? Math.min(2_000, adapter.retryMillis) : adapter.retryMillis;
		if (attempt == 1) {
			log.info("Telegram bot '{}' not started yet: {} (retrying shortly)", spec.name(), why);
		} else {
			log.warn("Telegram bot '{}' not started: {} (will retry)", spec.name(), why);
		}
		retry = adapter.scheduleRetry(this::tryStart, delay);
	}

	synchronized void stop() {
		stopped = true;
		ScheduledFuture<?> r = retry;
		if (r != null) r.cancel(false);
		TelegramBot b = bot;
		OkHttpClient c = http;
		bot = null;
		http = null;
		token = null;
		if (b != null) release(b, c);
		// Let the poll thread unwind its cancelled call before the caller (an
		// unload) closes the module classloader under it.
		Thread t = pollThread;
		if (t != null && t != Thread.currentThread()) {
			try {
				t.join(5_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		state = State.STOPPED;
		log.info("Telegram bot '{}' stopped", spec.name());
	}

	/** Cancel in-flight calls (unblocking the poll loop) and drop the client. */
	private static void release(TelegramBot b, OkHttpClient c) {
		try {
			if (c != null) {
				c.dispatcher().cancelAll();
				c.connectionPool().evictAll();
			}
			b.shutdown();
		} catch (RuntimeException e) {
			log.debug("Telegram client release failed", e);
		}
	}

	State state() {
		return state;
	}

	String error() {
		return error;
	}

	String username() {
		return username;
	}

	/** Status record for {@code telegram:bots}. The token is never included. */
	AMap<AString, ACell> status() {
		AMap<AString, ACell> m = Maps.of(
			Fields.NAME, Strings.create(spec.name()),
			BotSpec.K_USER, Strings.create(spec.userRef()),
			K_TARGET, Strings.create(spec.target()),
			K_STATE, Strings.create(state.name()),
			K_MANAGED, Strings.create(managed.name().toLowerCase(Locale.ROOT)),
			K_RECEIVED, CVMLong.create(received.get()),
			K_SENT, CVMLong.create(sent.get()),
			K_FAILED, CVMLong.create(failed.get()));
		if (username != null) m = m.assoc(K_USERNAME, Strings.create(username));
		if (error != null) m = m.assoc(Fields.ERROR, Strings.create(error));
		return m;
	}

	// ------------------------------------------------------------------ polling

	/**
	 * The long-poll loop: fetch, dispatch, confirm by advancing the offset.
	 * Runs until {@link #stop()}. Errors back off (2s doubling to 60s) and are
	 * logged once per outage. While the adapter is disabled nothing is fetched
	 * and nothing is confirmed, so Telegram holds the backlog (24h) and
	 * redelivers when the adapter is enabled again.
	 */
	private void pollLoop() {
		long backoff = 2_000;
		while (!stopped) {
			TelegramBot b = bot;
			if (b == null) return;
			if (!adapter.isActive()) {
				offline = true;
				sleep(1_000);
				continue;
			}
			offline = false;
			try {
				GetUpdates req = new GetUpdates().offset(offset).timeout(POLL_TIMEOUT_SECS);
				// A conversation only needs messages; an operation handler gets every
				// update type Telegram delivers by default (callback queries, edits…).
				if (spec.routesToAgent()) req.allowedUpdates("message");
				GetUpdatesResponse r = b.execute(req);
				if (stopped) return;
				if (r == null || !r.isOk()) {
					pollError(describe(r));
					sleep(backoff);
					backoff = Math.min(backoff * 2, 60_000);
					continue;
				}
				if (pollErrors.getAndSet(0) > 0) {
					log.info("Telegram bot '{}' polling recovered", spec.name());
				}
				backoff = 2_000;
				List<Update> updates = r.updates();
				if (updates == null) continue;
				for (Update u : updates) {
					// Went offline mid-batch: leave the rest unconfirmed for redelivery.
					if (!adapter.isActive()) break;
					try {
						handle(u);
					} catch (RuntimeException e) {
						log.warn("Telegram bot '{}' failed to handle update {}", spec.name(), u.updateId(), e);
					}
					if (u.updateId() != null) offset = u.updateId() + 1;
				}
			} catch (Throwable e) {
				if (stopped) return;
				pollError(concise(e));
				sleep(backoff);
				backoff = Math.min(backoff * 2, 60_000);
			}
		}
		offline = false;
	}

	/** Whether the long-poll loop has parked after observing adapter disablement. */
	boolean isOffline() {
		return offline;
	}

	/** Whether all work already accepted for one chat has finished. */
	boolean isChatIdle(long chatId) {
		CompletableFuture<Void> tail = chatTails.get(chatId);
		return tail == null || tail.isDone();
	}

	private void pollError(String why) {
		int n = pollErrors.incrementAndGet();
		if (n == 1) {
			log.warn("Telegram bot '{}' polling error: {}", spec.name(), why);
		} else {
			log.debug("Telegram bot '{}' polling error #{}: {}", spec.name(), n, why);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ------------------------------------------------------------------ inbound

	/**
	 * Route one Telegram {@code Update}. Access is checked against the update's
	 * sender; the built-in commands are answered here for text messages; then an
	 * agent bot gets the message text as a conversation turn, and an operation
	 * bot gets the whole Update exactly as Telegram sent it (plus {@code bot}) —
	 * photos, documents, callback queries and all — so the handler sees what a
	 * webhook would.
	 */
	private void handle(Update update) {
		received.incrementAndGet();
		Message m = update.message();
		User from = senderOf(update);
		Long chatId = chatOf(update);
		Long fromId = (from != null) ? from.id() : null;
		String fromName = (from != null) ? from.username() : null;
		if (!spec.allows(fromId, fromName)) {
			log.info("Telegram bot '{}': unauthorised update {} from user {} (@{}) in chat {}",
				spec.name(), update.updateId(), fromId, fromName, chatId);
			if (isPrivate(m)) {
				sendQuietly(chatId, "Not authorised to use this bot. Your Telegram user id is " + fromId
					+ (fromName != null ? " (@" + fromName + ")" : "")
					+ " — ask the venue operator to add it to the bot's allow list.", m);
			}
			return;
		}
		String text = (m == null) ? null : (m.text() != null) ? m.text() : m.caption();
		if (text != null && !text.isBlank()) {
			text = stripMention(text.trim());
			if (addressedToAnotherBot(text)) return;   // /cmd@otherbot in a group
			if (handleCommand(m, chatId, fromId, fromName, text)) return;
		}
		if (spec.routesToAgent()) {
			// A conversation needs words: anything else in a private chat gets a
			// short notice; in groups (and for non-message updates) stay silent.
			if (m == null || text == null || text.isBlank()) {
				if (isPrivate(m)) sendQuietly(chatId, "I can only read text messages and captions.", m);
				return;
			}
			final String body = text;
			enqueue(chatId, () -> respondAgent(m, body));
		} else {
			enqueue((chatId != null) ? chatId : 0L, () -> respondOperation(update));
		}
	}

	/** The built-in commands; true when the message was one and has been answered. */
	private boolean handleCommand(Message m, Long chatId, Long fromId, String fromName, String text) {
		switch (commandOf(text)) {
			case "/start" -> sendQuietly(chatId, greeting(), m);
			case "/help" -> sendQuietly(chatId, greeting() + "\n\nCommands:\n/new — start a new conversation\n"
				+ "/id — show this chat's id and your user id\n/help — this message", m);
			case "/id" -> sendQuietly(chatId, "Chat id: " + chatId + "\nYour user id: " + fromId
				+ (fromName != null ? " (@" + fromName + ")" : ""), m);
			case "/new" -> {
				if (spec.routesToAgent()) {
					forgetSession(chatId, context());
					sendQuietly(chatId, "Started a new conversation.", m);
				} else {
					sendQuietly(chatId, "This bot has no conversation state.", m);
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/** The user behind an update, for the allow-list; null when it has none (channel posts, polls…). */
	private static User senderOf(Update u) {
		if (u.message() != null) return u.message().from();
		if (u.editedMessage() != null) return u.editedMessage().from();
		if (u.callbackQuery() != null) return u.callbackQuery().from();
		if (u.channelPost() != null) return u.channelPost().from();
		if (u.editedChannelPost() != null) return u.editedChannelPost().from();
		if (u.inlineQuery() != null) return u.inlineQuery().from();
		if (u.myChatMember() != null) return u.myChatMember().from();
		if (u.chatMember() != null) return u.chatMember().from();
		return null;
	}

	/** The chat an update belongs to, for serialisation and replies; null when there is none. */
	private static Long chatOf(Update u) {
		Message m = (u.message() != null) ? u.message()
			: (u.editedMessage() != null) ? u.editedMessage()
			: (u.channelPost() != null) ? u.channelPost()
			: (u.editedChannelPost() != null) ? u.editedChannelPost()
			: (u.callbackQuery() != null) ? u.callbackQuery().message() : null;
		if (m != null && m.chat() != null) return m.chat().id();
		if (u.myChatMember() != null && u.myChatMember().chat() != null) return u.myChatMember().chat().id();
		if (u.chatMember() != null && u.chatMember().chat() != null) return u.chatMember().chat().id();
		return null;
	}

	private static boolean isPrivate(Message m) {
		return m != null && m.chat() != null && m.chat().type() == Chat.Type.Private;
	}

	private String greeting() {
		if (spec.greeting() != null) return spec.greeting();
		String venue = (adapter.engine != null && adapter.engine.getName() != null)
			? adapter.engine.getName().toString() : "the venue";
		if (spec.routesToAgent()) {
			return "Connected to agent '" + spec.agent() + "' on " + venue
				+ ". Send a message to talk to it; /new starts a fresh conversation.";
		}
		return "Connected to " + venue + ". Messages are handled by " + spec.operation() + ".";
	}

	/** Serialise work per chat: the next unit starts when the previous finishes. */
	private void enqueue(Long chatId, Runnable work) {
		chatTails.compute(chatId, (id, tail) -> {
			CompletableFuture<Void> prev = (tail != null) ? tail : CompletableFuture.completedFuture(null);
			CompletableFuture<Void> next = prev.handleAsync((r, e) -> {
				work.run();
				return null;
			}, AAdapter.VIRTUAL_EXECUTOR);
			// Forget the tail once it is done and still the tail: idle chats cost nothing.
			next.whenComplete((r, e) -> chatTails.remove(id, next));
			return next;
		});
	}

	/**
	 * One conversation turn: {@code agent:chat} as a durable Job in the bot
	 * user's job index (the canonical record of the interaction), the response
	 * sent back as a reply.
	 */
	private void respondAgent(Message m, String text) {
		Long chatId = m.chat().id();
		try {
			typing(chatId, m);
			String reply = TelegramAdapter.renderText(chatAgent(chatId, agentMessage(m, text)));
			if (reply == null || reply.isBlank()) reply = "(no response)";
			send(chatId, reply, spec.parseMode(), m.messageId(), threadOf(m), false);
		} catch (Throwable t) {
			failed.incrementAndGet();
			logFailure("failed to respond", chatId, t);
			sendQuietly(chatId, "⚠️ " + concise(t), m);
		}
	}

	/**
	 * One operation invocation for an Update, as a durable Job in the bot user's
	 * job index; the reply (if any) follows {@code reply}. Updates without a chat
	 * (polls, inline queries) run but cannot be replied to.
	 */
	private void respondOperation(Update update) {
		Message m = update.message();
		Long chatId = chatOf(update);
		try {
			if (chatId != null && !spec.silent() && spec.fixedReply() == null) typing(chatId, m);
			ACell result = runJob(spec.operation(), updateRecord(update), context());
			if (chatId != null) replyAfter(chatId, m, result);
		} catch (Throwable t) {
			failed.incrementAndGet();
			logFailure("handler failed for update " + update.updateId(), chatId, t);
			if (chatId != null) sendQuietly(chatId, "⚠️ " + concise(t), m);
		}
	}

	/** The Update exactly as Telegram sent it (snake_case, nested objects), plus {@code bot}. */
	AMap<AString, ACell> updateRecord(Update update) {
		AMap<AString, ACell> record = RT.castMap(JSON.parse(BotUtils.toJson(update)));
		if (record == null) record = Maps.empty();
		return record.assoc(K_BOT, Strings.create(spec.name()));
	}

	/**
	 * The chat message an agent receives: the text plus, as structure it cannot
	 * mistake for typed text, who is on the other end — Telegram's own {@code from}
	 * and {@code chat} objects (authenticated by Telegram, admitted by this bot's
	 * allow-list or its {@code open} setting), the bot, and the message id. The
	 * framework's own "[Authenticated sender: …]" line names the bot's Covia
	 * identity; this names the human.
	 */
	AMap<AString, ACell> agentMessage(Message m, String text) {
		AMap<AString, ACell> via = Maps.of(
			K_CHANNEL, Strings.create("telegram"),
			K_BOT, Strings.create(spec.name()),
			K_ACCESS, Strings.create(spec.open() ? "open" : "allow"));
		if (m.from() != null) via = via.assoc(K_FROM, telegramCell(m.from()));
		if (m.chat() != null) via = via.assoc(K_CHAT, telegramCell(m.chat()));
		if (m.messageId() != null) via = via.assoc(K_MESSAGE_ID, CVMLong.create(m.messageId()));
		return Maps.of(Fields.TEXT, Strings.create(text), K_VIA, via);
	}

	/** A pengrad model object as Telegram's JSON (snake_case), as a cell. */
	private static ACell telegramCell(Object model) {
		return JSON.parse(BotUtils.toJson(model));
	}

	private ACell chatAgent(Long chatId, ACell message) {
		RequestContext ctx = context();
		String sid = sessionFor(chatId, ctx);
		AMap<AString, ACell> input = Maps.of(
			Fields.AGENT_ID, Strings.create(spec.agent()),
			Fields.MESSAGE, message);
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid));
		ACell result;
		try {
			result = runJob(AGENT_CHAT, input, ctx);
		} catch (RuntimeException e) {
			// A persisted session the agent no longer knows (deleted, agent
			// recreated): fall back to a fresh conversation rather than failing
			// every message from now on.
			if (sid != null && isUnknownSession(e)) {
				log.info("Telegram bot '{}': session {} for chat {} is unknown, starting a new one",
					spec.name(), sid, chatId);
				forgetSession(chatId, ctx);
				input = input.dissoc(Fields.SESSION_ID);
				result = runJob(AGENT_CHAT, input, ctx);
			} else {
				throw e;
			}
		}
		AString newSid = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		if (newSid != null && !newSid.toString().equals(sid)) {
			rememberSession(chatId, newSid.toString(), ctx);
		}
		return RT.getIn(result, Fields.RESPONSE);
	}

	/** Submit an operation as a Job for the bot user and wait for its result. */
	private ACell runJob(String operation, ACell input, RequestContext ctx) {
		Job job = adapter.engine.jobs().invokeOperation(operation, input, ctx);
		ACell result = job.awaitResult();
		if (job.getStatus() != Status.COMPLETE) {
			String why = job.getErrorMessage();
			throw new JobFailedException(operation + " " + job.getStatus()
				+ (why != null ? ": " + why : ""));
		}
		return result;
	}

	/** The configured reply after the operation handler ran. */
	private void replyAfter(Long chatId, Message m, ACell result) {
		if (spec.silent()) return;
		Integer replyTo = (m != null) ? m.messageId() : null;
		String fixed = spec.fixedReply();
		if (fixed != null) {
			send(chatId, fixed, spec.parseMode(), replyTo, threadOf(m), false);
			return;
		}
		String reply = TelegramAdapter.renderText(result);
		if (reply == null || reply.isBlank()) reply = "(no response)";
		send(chatId, reply, spec.parseMode(), replyTo, threadOf(m), false);
	}

	RequestContext context() {
		return RequestContext.of(spec.userDID(adapter.engine));
	}

	// ----------------------------------------------------------- session state

	/** Pre-adapter-workspace session location, retained for upgrade reads and cleanup. */
	static String legacySessionsPath(String bot) {
		return "w/telegram/sessions/" + bot;
	}

	String sessionsPath() {
		return adapter.state().path(sessionsRelativePath());
	}

	private String sessionsRelativePath() {
		return managed == Managed.CONFIG
			? "config/" + spec.name() + "/sessions"
			: adapter.userStatePath(spec.userDID(adapter.engine), "sessions/" + spec.name());
	}

	private String sessionRelativePath(Long chatId) {
		return sessionsRelativePath() + "/" + chatId;
	}

	private String sessionFor(Long chatId, RequestContext ctx) {
		String sid = sessions.get(chatId);
		if (sid != null) return sid;
		AString s = RT.ensureString(adapter.state().read(sessionRelativePath(chatId)));
		if (s == null && managed == Managed.RUNTIME) {
			try {
				s = RT.ensureString(adapter.engine.resolvePath(
					Strings.create(legacySessionsPath(spec.name()) + "/" + chatId), ctx));
				if (s != null && !s.isEmpty()) adapter.state().write(sessionRelativePath(chatId), s);
			} catch (RuntimeException e) {
				log.debug("Telegram bot '{}': could not migrate persisted session for chat {}: {}",
					spec.name(), chatId, concise(e));
			}
		}
		if (s != null && !s.isEmpty()) {
			sessions.put(chatId, s.toString());
			return s.toString();
		}
		return null;
	}

	private void rememberSession(Long chatId, String sid, RequestContext ctx) {
		sessions.put(chatId, sid);
		adapter.state().write(sessionRelativePath(chatId), Strings.create(sid));
	}

	private void forgetSession(Long chatId, RequestContext ctx) {
		sessions.remove(chatId);
		adapter.state().delete(sessionRelativePath(chatId));
		if (managed == Managed.RUNTIME) adapter.deleteLegacyPath(ctx, legacySessionsPath(spec.name()) + "/" + chatId);
	}

	private static boolean isUnknownSession(Throwable t) {
		Throwable c = t;
		while (c != null) {
			String msg = c.getMessage();
			if (msg != null && msg.contains("Unknown session")) return true;
			c = (c.getCause() == c) ? null : c.getCause();
		}
		return false;
	}

	// ----------------------------------------------------------------- outbound

	/**
	 * Execute any Bot API method with Telegram-form parameters and return its
	 * {@code result} as a cell (a {@code Message} for the send methods,
	 * {@code true} for most others).
	 *
	 * @throws JobFailedException when Telegram answers {@code ok: false}
	 * @throws IllegalStateException when the bot is not running
	 */
	ACell call(String method, Map<String, Object> params) {
		ApiResponse r = execute(method, params);
		if (!r.ok()) {
			failed.incrementAndGet();
			throw new JobFailedException("Telegram " + method + " failed: " + r.describe());
		}
		if (method.startsWith("send")) sent.incrementAndGet();
		return r.resultCell();
	}

	/**
	 * {@code sendMessage} with the module's two conveniences on top of
	 * Telegram's own parameters: text longer than the 4096-character limit is
	 * split (line breaks preferred; {@code reply_parameters} apply to the first
	 * chunk only), and when a {@code parse_mode} is set and Telegram rejects the
	 * formatting (HTTP 400, typically unbalanced markup) the chunk is resent as
	 * plain text — a garbled asterisk must not lose the message.
	 *
	 * @param params Telegram {@code sendMessage} parameters ({@code chat_id} and
	 *               {@code text} required)
	 * @return the last sent {@code Message} as Telegram returned it
	 */
	ACell sendMessage(Map<String, Object> params) {
		if (params.get("chat_id") == null) {
			throw new IllegalArgumentException("chat_id is required: a Telegram chat id or @channelusername");
		}
		Object textObj = params.get("text");
		String text = (textObj == null) ? "" : String.valueOf(textObj);
		if (text.isBlank()) throw new IllegalArgumentException("text is required");
		ACell last = null;
		boolean first = true;
		for (String chunk : split(text, MAX_MESSAGE_LENGTH)) {
			Map<String, Object> p = new LinkedHashMap<>(params);
			p.put("text", chunk);
			if (!first) p.remove("reply_parameters");
			ApiResponse r = execute("sendMessage", p);
			if (!r.ok() && r.errorCode() == 400 && p.containsKey("parse_mode")) {
				log.debug("Telegram bot '{}': {} formatting rejected ({}), resending as plain text",
					spec.name(), p.get("parse_mode"), r.description());
				p.remove("parse_mode");
				r = execute("sendMessage", p);
			}
			if (!r.ok()) {
				failed.incrementAndGet();
				throw new JobFailedException("Telegram sendMessage failed: " + r.describe());
			}
			sent.incrementAndGet();
			last = r.resultCell();
			first = false;
		}
		return last;
	}

	/** Telegram's response envelope: {@code {ok, result?, error_code?, description?}}. */
	record ApiResponse(boolean ok, int errorCode, String description, JsonElement result) {
		String describe() {
			return errorCode + " " + description;
		}

		ACell resultCell() {
			return (result == null || result.isJsonNull()) ? null : JSON.parse(result.toString());
		}
	}

	/**
	 * POST a Bot API method as Telegram expects it: form fields, scalars as
	 * text and maps/lists as JSON (the same encoding the pengrad client uses).
	 */
	private ApiResponse execute(String method, Map<String, Object> params) {
		OkHttpClient c = http;
		String tok = token;
		if (c == null || tok == null) {
			throw new IllegalStateException("Telegram bot '" + spec.name() + "' is not running"
				+ (error != null ? ": " + error : ""));
		}
		FormBody.Builder form = new FormBody.Builder();
		if (params != null) {
			for (Map.Entry<String, Object> e : params.entrySet()) {
				if (e.getValue() != null) form.add(e.getKey(), paramValue(e.getValue()));
			}
		}
		Request request = new Request.Builder().url(apiUrl + tok + "/" + method).post(form.build()).build();
		try (Response response = c.newCall(request).execute()) {
			String body = (response.body() != null) ? response.body().string() : "";
			JsonObject o = JsonParser.parseString(body.isBlank() ? "{}" : body).getAsJsonObject();
			boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
			int code = o.has("error_code") ? o.get("error_code").getAsInt() : (ok ? 200 : response.code());
			String description = o.has("description") ? o.get("description").getAsString()
				: (ok ? "" : "HTTP " + response.code());
			return new ApiResponse(ok, code, description, o.get("result"));
		} catch (java.io.IOException | RuntimeException e) {
			throw new JobFailedException("Telegram " + method + " failed: " + concise(e));
		}
	}

	private static String paramValue(Object v) {
		if (v instanceof String s) return s;
		if (v instanceof Number || v instanceof Boolean || v instanceof Character || v.getClass().isEnum()) {
			return String.valueOf(v);
		}
		return BotUtils.GSON.toJson(v);
	}

	/** The runner's own chatter: a text message built the Telegram way. */
	ACell send(Object chatId, String text, String parseMode, Integer replyTo, Long threadId, boolean silent) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("chat_id", chatId);
		p.put("text", text);
		if (parseMode != null) p.put("parse_mode", parseMode);
		if (replyTo != null) {
			Map<String, Object> rp = new LinkedHashMap<>();
			rp.put("message_id", replyTo);
			rp.put("allow_sending_without_reply", true);
			p.put("reply_parameters", rp);
		}
		if (threadId != null) p.put("message_thread_id", threadId);
		if (silent) p.put("disable_notification", true);
		return sendMessage(p);
	}

	/** Best-effort plain-text reply used for bot chatter and error notices. */
	private void sendQuietly(Long chatId, String text, Message inReplyTo) {
		if (chatId == null) return;
		try {
			send(chatId, text, null, null, threadOf(inReplyTo), false);
		} catch (RuntimeException e) {
			log.debug("Telegram bot '{}': notice to chat {} failed: {}", spec.name(), chatId, concise(e));
		}
	}

	private void typing(Long chatId, Message m) {
		TelegramBot b = bot;
		if (b == null || chatId == null) return;
		try {
			SendChatAction action = new SendChatAction(chatId, ChatAction.typing);
			Long thread = threadOf(m);
			if (thread != null) action.messageThreadId(thread);
			b.execute(action);
		} catch (RuntimeException | LinkageError e) {
			// Best-effort: a typing indicator must never cost the reply — including
			// when its classes cannot be loaded (jar replaced under the venue).
			log.debug("Telegram bot '{}': typing indicator failed: {}", spec.name(), concise(e));
		}
	}

	private static Long threadOf(Message m) {
		if (m == null) return null;
		return Boolean.TRUE.equals(m.isTopicMessage()) ? m.messageThreadId() : null;
	}

	// ------------------------------------------------------------------ helpers

	/** Splits text into chunks of at most {@code max} chars, preferring line breaks. */
	static List<String> split(String text, int max) {
		List<String> out = new ArrayList<>();
		if (text == null) return out;
		String rest = text;
		while (rest.length() > max) {
			int cut = rest.lastIndexOf('\n', max);
			if (cut < max / 2) cut = rest.lastIndexOf(' ', max);
			if (cut < max / 2) cut = max;
			out.add(rest.substring(0, cut));
			rest = rest.substring(cut).stripLeading();
		}
		out.add(rest);
		return out;
	}

	/** Removes a leading {@code @botusername} mention (group chats). */
	private String stripMention(String text) {
		if (username != null && text.startsWith("@" + username)) {
			return text.substring(username.length() + 1).trim();
		}
		return text;
	}

	/** Whether a command carries an @suffix naming a different bot ({@code /start@otherbot}). */
	private boolean addressedToAnotherBot(String text) {
		if (!text.startsWith("/")) return false;
		int end = text.indexOf(' ');
		String cmd = (end < 0) ? text : text.substring(0, end);
		int at = cmd.indexOf('@');
		if (at < 0) return false;
		String target = cmd.substring(at + 1);
		return username != null && !target.equalsIgnoreCase(username);
	}

	/** The command word of a message ({@code /start@bot payload} → {@code /start}), or "". */
	private static String commandOf(String text) {
		if (!text.startsWith("/")) return "";
		int end = text.indexOf(' ');
		String cmd = (end < 0) ? text : text.substring(0, end);
		int at = cmd.indexOf('@');
		if (at > 0) cmd = cmd.substring(0, at);
		return cmd.toLowerCase(Locale.ROOT);
	}

	private static String describe(BaseResponse r) {
		if (r == null) return "no response";
		return r.errorCode() + " " + r.description();
	}

	/** What a person should read about a failure: the venue's shared rendering (type named unless self-describing). */
	static String concise(Throwable t) {
		Throwable c = t;
		while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
			c = c.getCause();
		}
		String msg = AAdapter.describeFailure(c);
		return (msg.length() > 400) ? msg.substring(0, 400) + "…" : msg;
	}

	/** Log a handler failure: one line for expected, self-describing errors; the stack trace for anything else. */
	private void logFailure(String what, Long chatId, Throwable t) {
		Throwable c = t;
		while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) c = c.getCause();
		if (AAdapter.isSelfDescribing(c)) {
			log.warn("Telegram bot '{}': {} in chat {}: {}", spec.name(), what, chatId, concise(c));
		} else {
			log.warn("Telegram bot '{}': {} in chat {}: {}", spec.name(), what, chatId, concise(c), c);
		}
	}
}
