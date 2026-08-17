package covia.adapter.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyParameters;
import com.pengrad.telegrambot.request.GetMe;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendChatAction;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetMeResponse;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import com.pengrad.telegrambot.response.SendResponse;

import okhttp3.OkHttpClient;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
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
	private static final String COVIA_READ = "v/ops/covia/read";
	private static final String COVIA_WRITE = "v/ops/covia/write";
	private static final String COVIA_DELETE = "v/ops/covia/delete";

	static final AString K_STATE = Strings.intern("state");
	static final AString K_USERNAME = Strings.intern("username");
	static final AString K_TARGET = Strings.intern("target");
	static final AString K_RECEIVED = Strings.intern("received");
	static final AString K_SENT = Strings.intern("sent");
	static final AString K_FAILED = Strings.intern("failed");
	private static final AString K_BOT = Strings.intern("bot");
	private static final AString K_CHAT_ID = Strings.intern("chatId");
	private static final AString K_CHAT = Strings.intern("chat");
	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_ID = Strings.intern("id");
	private static final AString K_TYPE = Strings.intern("type");
	private static final AString K_TITLE = Strings.intern("title");
	private static final AString K_FIRST_NAME = Strings.intern("firstName");
	private static final AString K_LAST_NAME = Strings.intern("lastName");
	private static final AString K_DATE = Strings.intern("date");

	enum State { STARTING, PENDING, RUNNING, STOPPED }

	private final TelegramAdapter adapter;
	final BotSpec spec;
	final String apiUrl;

	private volatile TelegramBot bot;
	private volatile OkHttpClient http;
	private volatile String username;
	private volatile State state = State.STARTING;
	private volatile String error;
	private volatile boolean stopped;
	private volatile ScheduledFuture<?> retry;
	private volatile Thread pollThread;

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

	BotRunner(TelegramAdapter adapter, BotSpec spec, String apiUrl) {
		this.adapter = adapter;
		this.spec = spec;
		this.apiUrl = apiUrl;
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
				sleep(1_000);
				continue;
			}
			try {
				GetUpdatesResponse r = b.execute(
					new GetUpdates().offset(offset).timeout(POLL_TIMEOUT_SECS).allowedUpdates("message"));
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

	private void handle(Update update) {
		Message m = update.message();
		if (m == null || m.chat() == null) return;
		String text = (m.text() != null) ? m.text() : m.caption();
		if (text == null || text.isBlank()) return;
		received.incrementAndGet();
		Long chatId = m.chat().id();
		User from = m.from();
		Long fromId = (from != null) ? from.id() : null;
		String fromName = (from != null) ? from.username() : null;
		if (!spec.allows(fromId, fromName)) {
			log.info("Telegram bot '{}': unauthorised message from user {} (@{}) in chat {}",
				spec.name(), fromId, fromName, chatId);
			if (m.chat().type() == Chat.Type.Private) {
				sendQuietly(chatId, "Not authorised to use this bot. Your Telegram user id is " + fromId
					+ (fromName != null ? " (@" + fromName + ")" : "")
					+ " — ask the venue operator to add it to the bot's allow list.", m);
			}
			return;
		}
		text = stripMention(text.trim());
		if (addressedToAnotherBot(text)) return;   // /cmd@otherbot in a group
		String command = commandOf(text);
		switch (command) {
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
				String body = text;
				enqueue(chatId, () -> respond(m, body));
			}
		}
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
	 * One inbound message through the bot's handler — an agent conversation
	 * turn or an operation invocation — as a durable Job in the bot user's job
	 * index, which is the canonical record of the interaction (nothing else is
	 * logged by this module). Replies follow {@code reply}.
	 */
	private void respond(Message m, String text) {
		Long chatId = m.chat().id();
		try {
			if (spec.routesToAgent()) {
				typing(chatId, m);
				String reply = TelegramAdapter.renderText(chatAgent(chatId, text));
				if (reply == null || reply.isBlank()) reply = "(no response)";
				send(chatId, reply, spec.parseMode(), m.messageId(), threadOf(m), false);
			} else {
				if (!spec.silent() && spec.fixedReply() == null) typing(chatId, m);
				ACell result = runJob(spec.operation(), inboundRecord(m, text), context());
				replyAfter(m, result);
			}
		} catch (Throwable t) {
			failed.incrementAndGet();
			log.warn("Telegram bot '{}': failed to respond in chat {}: {}", spec.name(), chatId, concise(t));
			sendQuietly(chatId, "⚠️ " + concise(t), m);
		}
	}

	private ACell chatAgent(Long chatId, String text) {
		RequestContext ctx = context();
		String sid = sessionFor(chatId, ctx);
		AMap<AString, ACell> input = Maps.of(
			Fields.AGENT_ID, Strings.create(spec.agent()),
			Fields.MESSAGE, Strings.create(text));
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
	private void replyAfter(Message m, ACell result) {
		if (spec.silent()) return;
		Long chatId = m.chat().id();
		String fixed = spec.fixedReply();
		if (fixed != null) {
			send(chatId, fixed, spec.parseMode(), m.messageId(), threadOf(m), false);
			return;
		}
		String reply = TelegramAdapter.renderText(result);
		if (reply == null || reply.isBlank()) reply = "(no response)";
		send(chatId, reply, spec.parseMode(), m.messageId(), threadOf(m), false);
	}


	/** The message as an operation input: {@code {bot, chatId, messageId, text, from, chat, date}}. */
	private AMap<AString, ACell> inboundRecord(Message m, String text) {
		AMap<AString, ACell> chat = Maps.of(K_ID, CVMLong.create(m.chat().id()));
		if (m.chat().type() != null) chat = chat.assoc(K_TYPE, Strings.create(m.chat().type().name().toLowerCase(Locale.ROOT)));
		if (m.chat().title() != null) chat = chat.assoc(K_TITLE, Strings.create(m.chat().title()));
		AMap<AString, ACell> record = Maps.of(
			K_BOT, Strings.create(spec.name()),
			K_CHAT_ID, CVMLong.create(m.chat().id()),
			Fields.TEXT, Strings.create(text),
			K_CHAT, chat);
		if (m.messageId() != null) record = record.assoc(Fields.MESSAGE_ID, CVMLong.create(m.messageId()));
		if (m.date() != null) record = record.assoc(K_DATE, CVMLong.create(m.date()));
		User from = m.from();
		if (from != null) {
			AMap<AString, ACell> f = Maps.of(K_ID, CVMLong.create(from.id()));
			if (from.username() != null) f = f.assoc(K_USERNAME, Strings.create(from.username()));
			if (from.firstName() != null) f = f.assoc(K_FIRST_NAME, Strings.create(from.firstName()));
			if (from.lastName() != null) f = f.assoc(K_LAST_NAME, Strings.create(from.lastName()));
			record = record.assoc(K_FROM, f);
		}
		return record;
	}

	RequestContext context() {
		return RequestContext.of(spec.userDID(adapter.engine));
	}

	// ----------------------------------------------------------- session state

	private String sessionPath(Long chatId) {
		return "w/telegram/" + spec.name() + "/sessions/" + chatId;
	}

	private String sessionFor(Long chatId, RequestContext ctx) {
		String sid = sessions.get(chatId);
		if (sid != null) return sid;
		try {
			ACell v = adapter.engine.jobs().invokeInternal(COVIA_READ,
				Maps.of(Fields.PATH, Strings.create(sessionPath(chatId))), ctx).get(10, TimeUnit.SECONDS);
			AString s = RT.ensureString(RT.getIn(v, Fields.VALUE));   // {exists, value, valueBytes}
			if (s != null && !s.isEmpty()) {
				sessions.put(chatId, s.toString());
				return s.toString();
			}
		} catch (Exception e) {
			log.debug("Telegram bot '{}': could not read persisted session for chat {}: {}",
				spec.name(), chatId, concise(e));
		}
		return null;
	}

	private void rememberSession(Long chatId, String sid, RequestContext ctx) {
		sessions.put(chatId, sid);
		try {
			adapter.engine.jobs().invokeInternal(COVIA_WRITE, Maps.of(
				Fields.PATH, Strings.create(sessionPath(chatId)),
				Fields.VALUE, Strings.create(sid)), ctx).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Telegram bot '{}': could not persist session for chat {}: {}",
				spec.name(), chatId, concise(e));
		}
	}

	private void forgetSession(Long chatId, RequestContext ctx) {
		sessions.remove(chatId);
		try {
			adapter.engine.jobs().invokeInternal(COVIA_DELETE,
				Maps.of(Fields.PATH, Strings.create(sessionPath(chatId))), ctx).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.debug("Telegram bot '{}': could not delete persisted session for chat {}: {}",
				spec.name(), chatId, concise(e));
		}
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
	 * Sends {@code text} to a chat, splitting at Telegram's length limit. When
	 * a parse mode is requested and Telegram rejects the formatting (HTTP 400,
	 * typically unbalanced markup), the chunk is resent as plain text — a
	 * garbled asterisk must not lose the message.
	 *
	 * @param chatId   Numeric chat id or {@code @channelusername}
	 * @param text     Message text
	 * @param parseMode {@code Markdown}, {@code MarkdownV2}, {@code HTML} or null
	 * @param replyTo  Message id to reply to (first chunk only), or null
	 * @param threadId Forum topic thread id, or null
	 * @param silent   Send without notification
	 * @return the last chunk's response
	 * @throws JobFailedException when Telegram rejects the send
	 * @throws IllegalStateException when the bot is not running
	 */
	SendResponse send(Object chatId, String text, String parseMode, Integer replyTo, Long threadId, boolean silent) {
		TelegramBot b = bot;
		if (b == null) {
			throw new IllegalStateException("Telegram bot '" + spec.name() + "' is not running"
				+ (error != null ? ": " + error : ""));
		}
		SendResponse last = null;
		for (String chunk : split(text, MAX_MESSAGE_LENGTH)) {
			SendResponse r = b.execute(request(chatId, chunk, parseMode, replyTo, threadId, silent));
			if (!r.isOk() && parseMode != null && r.errorCode() == 400) {
				log.debug("Telegram bot '{}': {} formatting rejected ({}), resending as plain text",
					spec.name(), parseMode, r.description());
				r = b.execute(request(chatId, chunk, null, replyTo, threadId, silent));
			}
			if (!r.isOk()) {
				failed.incrementAndGet();
				throw new JobFailedException("Telegram sendMessage failed: " + describe(r));
			}
			sent.incrementAndGet();
			last = r;
			replyTo = null;
		}
		return last;
	}

	private static SendMessage request(Object chatId, String text, String parseMode, Integer replyTo,
			Long threadId, boolean silent) {
		SendMessage req = new SendMessage(chatId, text);
		if (parseMode != null) req.parseMode(ParseMode.valueOf(parseMode));
		if (replyTo != null) req.replyParameters(new ReplyParameters(replyTo).allowSendingWithoutReply(true));
		if (threadId != null) req.messageThreadId(threadId);
		if (silent) req.disableNotification(true);
		return req;
	}

	/** Best-effort plain-text reply used for bot chatter and error notices. */
	private void sendQuietly(Long chatId, String text, Message inReplyTo) {
		try {
			send(chatId, text, null, null, threadOf(inReplyTo), false);
		} catch (RuntimeException e) {
			log.debug("Telegram bot '{}': notice to chat {} failed: {}", spec.name(), chatId, concise(e));
		}
	}

	private void typing(Long chatId, Message m) {
		TelegramBot b = bot;
		if (b == null) return;
		try {
			SendChatAction action = new SendChatAction(chatId, ChatAction.typing);
			Long thread = threadOf(m);
			if (thread != null) action.messageThreadId(thread);
			b.execute(action);
		} catch (RuntimeException e) {
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

	static String concise(Throwable t) {
		Throwable c = t;
		while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
			c = c.getCause();
		}
		String msg = c.getMessage();
		if (msg == null || msg.isBlank()) msg = c.getClass().getSimpleName();
		return (msg.length() > 400) ? msg.substring(0, 400) + "…" : msg;
	}
}
