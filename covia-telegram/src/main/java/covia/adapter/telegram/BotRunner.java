package covia.adapter.telegram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.adapter.webhook.WebhookInbox;
import covia.adapter.messaging.MessagingBot;
import covia.adapter.messaging.MessagingText;
import covia.adapter.messaging.ConversationRouter;
import covia.adapter.messaging.ConversationSessions;
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
final class BotRunner implements MessagingBot<BotSpec> {

	private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

	/** Telegram's hard limit on message text length. */
	static final int MAX_MESSAGE_LENGTH = 4096;
	/** Long-poll wait requested from Telegram, in seconds. */
	static final int POLL_TIMEOUT_SECS = 30;
	static final String INVALID_REPLY = "replyOperation must return a non-empty string or an object with non-empty text";


	static final AString K_STATE = Strings.intern("state");
	static final AString K_USERNAME = Strings.intern("username");
	static final AString K_RESOLVED = Strings.intern("resolved");
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

	private final ConversationRouter conversations;
	private volatile WebhookInbox inbox;
	private volatile String fileMetadataRoot;
	/** Allow-listed handle (lower-case) → the Telegram account it resolved to; mirrors the persisted map once loaded. */
	private final ConcurrentHashMap<String, Long> resolved = new ConcurrentHashMap<>();
	private volatile boolean resolvedLoaded;

	BotRunner(TelegramAdapter adapter, BotSpec spec, String apiUrl, Managed managed) {
		this.adapter = adapter;
		this.spec = spec;
		this.apiUrl = apiUrl;
		this.managed = managed;
		ConversationSessions sessionStore = new ConversationSessions(adapter.state(), sessionsRelativePath(),
			this::legacySession, chat -> {
				if (managed == Managed.RUNTIME) adapter.deleteLegacyPath(context(), legacySessionsPath(spec.name()) + "/" + chat);
			});
		conversations = new ConversationRouter(adapter.engine, spec, sessionStore);
		inbox = new WebhookInbox(adapter.engine, adapter.state(), statePath("inbox") + "/unbound");
	}

	// ---------------------------------------------------------------- lifecycle

	@Override public BotSpec spec() { return spec; }
	@Override public Managed managed() { return managed; }

	@Override public void start() {
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
			.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
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
		// Bot names and secret references can be reused for another account.
		// Receipts must be scoped to the actual Telegram identity and Covia owner.
		String binding = spec.userDID(adapter.engine).getHash().toHexString() + "/" + me.user().id();
		inbox = new WebhookInbox(adapter.engine, adapter.state(), statePath("inbox") + "/" + binding);
		fileMetadataRoot = statePath("files") + "/" + binding;
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
		State previousState = state;
		String previousError = error;
		state = State.PENDING;
		error = why;
		// Config-bootstrapped secrets are provisioned after adapters install, so the
		// first attempt at boot commonly finds no token yet: retry that one quickly,
		// then settle into the slow cadence for genuine outages.
		int attempt = startAttempts.incrementAndGet();
		long delay = (attempt == 1) ? Math.min(2_000, adapter.retryMillis) : adapter.retryMillis;
		if (attempt == 1) {
			log.info("Telegram bot '{}' not started yet: {} (retrying shortly)", spec.name(), why);
		} else if (shouldWarnPending(previousState, previousError, attempt, why)) {
			log.warn("Telegram bot '{}' not started: {} (will retry)", spec.name(), why);
		} else {
			log.debug("Telegram bot '{}' still pending: {} (will retry)", spec.name(), why);
		}
		retry = adapter.scheduleRetry(this::tryStart, delay);
	}

	/** Warn once when an initial problem persists, and again only on a new outage or reason. */
	static boolean shouldWarnPending(State previousState, String previousError, int attempt, String why) {
		return attempt == 2 || previousState != State.PENDING
			|| !java.util.Objects.equals(previousError, why);
	}

	@Override public synchronized void stop() {
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
	@Override public AMap<AString, ACell> status() {
		AMap<AString, ACell> m = Maps.of(
			Fields.NAME, Strings.create(spec.name()),
			BotSpec.K_USER, Strings.create(spec.userRef()),
			K_TARGET, Strings.create(spec.target()),
			K_STATE, Strings.create(state.name()),
			K_MANAGED, Strings.create(managed.name().toLowerCase(Locale.ROOT)),
			K_RECEIVED, CVMLong.create(received.get()),
			K_SENT, CVMLong.create(sent.get()),
			K_FAILED, CVMLong.create(failed.get()));
		m = m.assoc(Strings.intern("pendingUpdates"), CVMLong.create(inbox.records(WebhookInbox.PENDING).size()))
			.assoc(Strings.intern("startedUpdates"), CVMLong.create(inbox.records(WebhookInbox.STARTED).size()));
		if (username != null) m = m.assoc(K_USERNAME, Strings.create(username));
		if (error != null) m = m.assoc(Fields.ERROR, Strings.create(error));
		if (!spec.open() && !spec.allowNames().isEmpty()) {
			AMap<AString, ACell> r = Maps.empty();
			for (Map.Entry<String, Long> e : resolved().entrySet()) {
				r = r.assoc(Strings.create(e.getKey()), CVMLong.create(e.getValue()));
			}
			m = m.assoc(K_RESOLVED, r);
		}
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
		boolean recover = true;
		long nextPrune = 0;
		long backoff = 2_000;
		while (!stopped) {
			TelegramBot b = bot;
			if (b == null) return;
			if (!adapter.isActive()) {
				offline = true;
				recover = true;
				sleep(1_000);
				continue;
			}
			offline = false;
			try {
				if (recover) { recoverPending(); recover = false; }
				if (System.currentTimeMillis() >= nextPrune) {
					inbox.pruneCompleted(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7));
					nextPrune = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1);
				}
				GetUpdates req = new GetUpdates().offset(offset).timeout(POLL_TIMEOUT_SECS);
				// A conversation only needs messages; an operation handler gets every
				// update type Telegram delivers by default (callback queries, edits…).
				req.allowedUpdates(spec.routesToAgent() ? new String[] {"message"} : new String[0]);
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
					handle(u); // A failed durable acceptance must leave this update unconfirmed.
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
		return conversations.isIdle(Long.toString(chatId));
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
	 * bot gets the SDK-decoded Update in Telegram wire format (plus {@code bot}) —
	 * photos, documents, callback queries and all — so the handler sees what a
	 * webhook would.
	 */
	private void handle(Update update) {
		received.incrementAndGet();
		Message m = update.message();
		User from = senderOf(update);
		Chat chat = chatOfUpdate(update);
		Long chatId = (chat != null) ? chat.id() : null;
		Long fromId = (from != null) ? from.id() : null;
		String fromName = (from != null) ? from.username() : null;
		if (!admits(fromId, fromName)) {
			log.info("Telegram bot '{}': unauthorised update {} from user {} (@{}) in chat {}",
				spec.name(), update.updateId(), fromId, fromName, chatId);
			if (isPrivate(m)) {
				// The front door's own refusal: it carries nothing but the sender's
				// id, which the onboarding flow needs — the one message an
				// allow-listed bot sends outside its allowed users' chats.
				sendQuietly(chatId, "Not authorised to use this bot. Your Telegram user id is " + fromId
					+ (fromName != null ? " (@" + fromName + ")" : "")
					+ " — ask the venue operator to add it to the bot's allow list.", m);
			}
			return;
		}
		if (!spec.open() && chat != null && chat.type() != Chat.Type.Private) {
			// An allow-listed bot converses only in private chats: a reply in a
			// group would be read by people who are not on its allow list.
			log.info("Telegram bot '{}': ignoring update {} from allowed user {} in {} chat {} — "
				+ "an allow-listed bot converses only in private chats",
				spec.name(), update.updateId(), fromId, chat.type(), chatId);
			return;
		}
		rememberMedia(update.message() != null ? update.message()
			: update.editedMessage() != null ? update.editedMessage()
			: update.channelPost() != null ? update.channelPost() : update.editedChannelPost());
		String id = Integer.toString(update.updateId());
		inbox.accept(id, updateRecord(update));
		schedule(update);
	}

	private void schedule(Update update) {
		String id = Integer.toString(update.updateId());
		conversations.enqueue(conversationKey(update.message(), chatOf(update)), () -> {
			if (stopped || !adapter.isActive() || !inbox.claim(id)) return;
			try {
				dispatch(update);
				inbox.complete(id);
			} catch (Throwable t) {
				failed.incrementAndGet();
				error = "Update " + id + " requires inspection: " + concise(t);
				logFailure("update " + id + " requires inspection", chatOf(update), t);
				// STARTED is deliberately retained: neither a Job nor an external
				// send may be repeated safely after an uncertain outcome.
			}
		});
	}

	private void recoverPending() {
		var pending = new ArrayList<>(inbox.records(WebhookInbox.PENDING));
		pending.sort(java.util.Comparator.comparingLong(r -> Long.parseLong(r.get(Fields.ID).toString())));
		for (var receipt : pending) {
			schedule(BotUtils.GSON.fromJson(JSON.print(receipt.get(Fields.INPUT)).toString(), Update.class));
		}
	}

	private void dispatch(Update update) {
		Message m = update.message();
		Long chatId = chatOf(update);
		User from = senderOf(update);
		Long fromId = from == null ? null : from.id();
		String fromName = from == null ? null : from.username();
		Chat chat = chatOfUpdate(update);
		if (!admits(fromId, fromName) || (!spec.open() && chat != null && chat.type() != Chat.Type.Private)) {
			throw new IllegalStateException("Accepted Telegram update is no longer admitted by this bot's policy");
		}
		String text = (m == null) ? null : (m.text() != null) ? m.text() : m.caption();
		if (text != null && !text.isBlank()) {
			text = stripMention(text.trim());
			if (addressedToAnotherBot(text)) return;   // /cmd@otherbot in a group
			if (handleCommand(m, chatId, fromId, fromName, text)) return;
		}
		if (spec.routesToAgent()) {
			// Text and supported media become turns; other update types stay provider-specific.
			if (m == null || ((text == null || text.isBlank()) && TelegramMedia.attachments(m).isEmpty())) {
				if (isPrivate(m)) sendQuietly(chatId, "Send text, a voice note, a photo or a file.", m);
				return;
			}
			respondAgent(m, text == null ? "" : text);
		} else {
			respondOperation(update);
		}
	}

	/** The built-in commands; true when the message was one and has been answered. */
	private boolean handleCommand(Message m, Long chatId, Long fromId, String fromName, String text) {
		switch (commandOf(text)) {
			case "/start" -> commandReply(chatId, greeting(), m);
			case "/help" -> commandReply(chatId, greeting() + "\n\nCommands:\n/new — start a new conversation\n"
				+ "/id — show this chat's id and your user id\n/help — this message", m);
			case "/id" -> commandReply(chatId, "Chat id: " + chatId + "\nYour user id: " + fromId
				+ (fromName != null ? " (@" + fromName + ")" : ""), m);
			case "/new" -> {
				if (spec.routesToAgent()) {
					conversations.reset(conversationKey(m, chatId));
					send(chatId, "Started a new conversation.", null, null, threadOf(m), false);
				} else {
					commandReply(chatId, "This bot has no conversation state.", m);
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private void commandReply(Long chat, String text, Message message) {
		send(chat, text, null, null, threadOf(message), false);
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
		Chat chat = chatOfUpdate(u);
		return (chat != null) ? chat.id() : null;
	}

	/** The chat an update belongs to; null for updates without one (inline queries, polls…). */
	private static Chat chatOfUpdate(Update u) {
		Message m = (u.message() != null) ? u.message()
			: (u.editedMessage() != null) ? u.editedMessage()
			: (u.channelPost() != null) ? u.channelPost()
			: (u.editedChannelPost() != null) ? u.editedChannelPost()
			: (u.callbackQuery() != null) ? u.callbackQuery().message() : null;
		if (m != null && m.chat() != null) return m.chat();
		if (u.myChatMember() != null && u.myChatMember().chat() != null) return u.myChatMember().chat();
		if (u.chatMember() != null && u.chatMember().chat() != null) return u.chatMember().chat();
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

	/**
	 * One conversation turn: {@code agent:chat} as a durable Job in the bot
	 * user's job index (the canonical record of the interaction), the response
	 * sent back as a reply.
	 */
	private void respondAgent(Message m, String text) {
		Long chatId = m.chat().id();
		try {
			typing(chatId, m);
			ACell response = conversations.chat(conversationKey(m, chatId), prepareMessage(m, text));
			String reply;
			String parseMode = spec.parseMode();
			if (spec.replyOperation() == null) {
				reply = ConversationRouter.responseText(response);
			} else {
				ACell formatted = conversations.runOperation(spec.replyOperation(), Maps.of(
					Fields.RESPONSE, response, K_VIA, agentMessage(m, text).get(K_VIA)));
				reply = formattedReply(formatted);
				parseMode = formattedParseMode(formatted, parseMode);
			}
			send(chatId, reply, parseMode, m.messageId(), threadOf(m), false);
		} catch (Throwable t) {
			logFailure("failed to respond", chatId, t);
			sendQuietly(chatId, "⚠️ " + concise(t), m);
			throw new JobFailedException(concise(t));
		}
	}

	/** The parse modes Telegram knows; a reply hook naming any other is not obeyed. */
	static final java.util.Set<String> PARSE_MODES = java.util.Set.of("Markdown", "MarkdownV2", "HTML");

	/**
	 * The parse mode a reply hook's text is written for, when it says so
	 * ({@code {text, parse_mode}}, as {@code telegram:format} returns): it wins over
	 * the bot's own {@code parseMode}, so naming the formatter is the whole setup.
	 * Otherwise, or for a mode Telegram does not know, the bot's own.
	 */
	static String formattedParseMode(ACell result, String botParseMode) {
		if (result instanceof AMap<?, ?> && RT.getIn(result, TelegramAdapter.K_PARSE_MODE_PARAM) instanceof AString mode
				&& PARSE_MODES.contains(mode.toString())) {
			return mode.toString();
		}
		return botParseMode;
	}

	static String formattedReply(ACell result) {
		ACell text = result instanceof AString ? result : result instanceof AMap<?, ?> ? RT.getIn(result, Fields.TEXT) : null;
		if (!(text instanceof AString string) || string.toString().isBlank()) throw new IllegalArgumentException(INVALID_REPLY);
		return string.toString();
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
			ACell result = conversations.runOperation(spec.operation(), updateRecord(update));
			if (chatId != null) replyAfter(chatId, m, result);
		} catch (Throwable t) {
			logFailure("handler failed for update " + update.updateId(), chatId, t);
			if (chatId != null) sendQuietly(chatId, "⚠️ " + concise(t), m);
			throw new JobFailedException(concise(t));
		}
	}

	/** The SDK-decoded Update in Telegram wire format (snake_case, nested objects), plus {@code bot}. */
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
		if (threadOf(m) != null) via = via.assoc(Strings.intern("message_thread_id"), CVMLong.create(threadOf(m)));
		if (m.replyToMessage() != null) via = via.assoc(Strings.intern("reply_to_message_id"), CVMLong.create(m.replyToMessage().messageId()));
		if (m.mediaGroupId() != null) via = via.assoc(Strings.intern("media_group_id"), Strings.create(m.mediaGroupId()));
		if (m.messageId() != null) via = via.assoc(K_MESSAGE_ID, CVMLong.create(m.messageId()));
		return Maps.of(Fields.TEXT, Strings.create(text), K_VIA, via);
	}

	/** A pengrad model object as Telegram's JSON (snake_case), as a cell. */
	private static ACell telegramCell(Object model) {
		return JSON.parse(BotUtils.toJson(model));
	}

	/** The configured reply after the operation handler ran. */
	private void replyAfter(Long chatId, Message m, ACell result) {
		String reply = conversations.operationReply(result);
		if (reply == null) return;
		Integer replyTo = m == null ? null : m.messageId();
		send(chatId, reply, spec.parseMode(), replyTo, threadOf(m), false);
	}
	RequestContext context() {
		return RequestContext.of(spec.userDID(adapter.engine));
	}

	private String statePath(String region) {
		return managed == Managed.CONFIG ? "config/" + spec.name() + "/" + region
			: adapter.userStatePath(spec.userDID(adapter.engine), region + "/" + spec.name());
	}

	static String conversationKey(Message message, Long chat) {
		String chatId = chat == null ? "0" : chat.toString();
		Long topic = threadOf(message);
		return topic == null ? chatId : ConversationRouter.key(chatId, topic.toString());
	}

	/** Download privately, then give the agent references rather than token-bearing URLs or bytes. */
	private ACell prepareMessage(Message m, String text) {
		rememberMedia(m);
		AMap<AString, ACell> message = agentMessage(m, text);
		AVector<ACell> attachments = Vectors.empty();
		AVector<ACell> images = Vectors.empty();
		for (JsonObject media : TelegramMedia.attachments(m)) {
			if (media.has("file_size") && media.get("file_size").getAsLong() > TelegramMedia.MAX_BYTES) {
				throw new IllegalArgumentException(TelegramMedia.TOO_LARGE);
			}
			AMap<AString, ACell> attachment = download(media.get("file_id").getAsString())
				.merge(RT.castMap(JSON.parse(media.toString())));
			if (spec.mediaOperation() != null) {
				ACell processed = conversations.runOperation(spec.mediaOperation(), attachment
					.assoc(Strings.intern("caption"), Strings.create(text)).assoc(K_VIA, message.get(K_VIA)));
				attachment = TelegramMedia.processed(attachment, processed);
			}
			attachments = attachments.conj(attachment);
			String mime = media.get("mediaType").getAsString();
			if (mime.startsWith("image/")) images = images.conj(Maps.of("type", "image", "source",
				Maps.of("type", "asset", "ref", attachment.get(Fields.REF), "mediaType", mime)));
		}
		if (!attachments.isEmpty()) message = message.assoc(Strings.intern("attachments"), attachments);
		if (!images.isEmpty()) message = message.assoc(Fields.CONTENT, images);
		return message;
	}

	/** getFile loses names/MIME types: persist message metadata before acknowledging intake. */
	private void rememberMedia(Message message) {
		for (JsonObject media : TelegramMedia.attachments(message)) {
			String fileId = media.get("file_id").getAsString();
			AMap<AString, ACell> previous = RT.castMap(adapter.state().read(fileMetadataPath(fileId)));
			adapter.state().write(fileMetadataPath(fileId), previous.merge(RT.castMap(JSON.parse(media.toString()))));
		}
	}

	private String fileMetadataPath(String fileId) {
		return fileMetadataRoot + "/" + Strings.create(fileId).getHash().toHexString();
	}

	AMap<AString, ACell> download(String fileId) {
		return download(fileId, null, null, context());
	}

	/** Explicit destinations use caller authority; automatic intake uses the bot owner's context. */
	AMap<AString, ACell> download(String fileId, AString destination, String fileName, RequestContext writeContext) {
		ACell info = call("getFile", Map.of("file_id", fileId));
		AString path = RT.ensureString(RT.getIn(info, "file_path"));
		if (path == null || !path.toString().matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
				|| path.toString().contains("..")) throw new IllegalArgumentException(TelegramMedia.INVALID_PATH);
		ACell size = RT.getIn(info, "file_size");
		if (size instanceof CVMLong n && n.longValue() > TelegramMedia.MAX_BYTES) throw new IllegalArgumentException(TelegramMedia.TOO_LARGE);
		// Both official and local Bot API servers use /file/bot<token>/<path>.
		if (!apiUrl.endsWith("/bot")) throw new IllegalArgumentException("Telegram downloads require an apiUrl ending in /bot");
		String base = apiUrl.substring(0, apiUrl.length() - 4) + "/file/bot";
		OkHttpClient client = http;
		String liveToken = token;
		if (client == null || liveToken == null) throw new IllegalStateException("Telegram bot is not running");
		Request request = new Request.Builder().url(base + liveToken + "/" + path).build();
		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful() || response.body() == null) throw new JobFailedException(TelegramMedia.DOWNLOAD_FAILED);
			if (response.body().contentLength() > TelegramMedia.MAX_BYTES) throw new IllegalArgumentException(TelegramMedia.TOO_LARGE);
			byte[] bytes = response.body().byteStream().readNBytes(TelegramMedia.MAX_BYTES + 1);
			if (bytes.length > TelegramMedia.MAX_BYTES) throw new IllegalArgumentException(TelegramMedia.TOO_LARGE);
			ACell metadata = fileMetadataRoot == null ? null : adapter.state().read(fileMetadataPath(fileId));
			AString originalName = RT.ensureString(RT.getIn(metadata, "file_name"));
			AString originalType = RT.ensureString(RT.getIn(metadata, "mime_type"));
			String contentType = originalType != null ? originalType.toString()
				: response.body().contentType() == null ? null : response.body().contentType().toString();
			String name = fileName;
			if (name == null && originalName != null && TelegramMedia.safeFileName(originalName.toString())) name = originalName.toString();
			if (name == null) {
				String remoteName = path.toString().substring(path.toString().lastIndexOf('/') + 1);
				int dot = remoteName.lastIndexOf('.');
				String extension = dot < 0 ? ".bin" : remoteName.substring(dot);
				if (!extension.matches("\\.[A-Za-z0-9]{1,10}")) extension = ".bin";
				name = spec.name().substring(0, Math.min(64, spec.name().length())) + "-" + Strings.create(fileId).getHash().toHexString() + extension;
			}
			String directory = destination == null ? spec.userDID(adapter.engine) + "/dlfs/telegram/" : destination.toString();
			if (!directory.endsWith("/")) directory += "/";
			for (int attempt = 0; attempt < 1000; attempt++) {
				String actualName = TelegramMedia.collisionName(name, attempt);
				// DID references are URLs; encode the filename while preserving its stored spelling.
				String referenceName = directory.startsWith("did:")
					? java.net.URLEncoder.encode(actualName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
					: actualName;
				AString ref = Strings.create(directory + referenceName);
				try {
					if (!adapter.engine.createContent(ref, new java.io.ByteArrayInputStream(bytes), contentType, writeContext)) {
						throw new IllegalArgumentException(TelegramMedia.UNSUPPORTED_DESTINATION + ": " + directory);
					}
				} catch (java.nio.file.FileAlreadyExistsException conflict) {
					continue;
				}
				adapter.engine.flush();
				AMap<AString, ACell> result = Maps.of("file_id", fileId, "ref", ref, "size", bytes.length, Fields.FILE_NAME, actualName);
				if (originalName != null) result = result.assoc(Strings.intern("file_name"), originalName);
				if (contentType != null) result = result.assoc(Fields.CONTENT_TYPE, Strings.create(contentType));
				return result;
			}
			throw new IllegalStateException(TelegramMedia.NAME_CONFLICT);
		} catch (java.io.IOException e) {
			// HTTP client exceptions can contain the URL and hence the bot token.
			throw new JobFailedException(TelegramMedia.DOWNLOAD_FAILED);
		}
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

	private String legacySession(String chatId) {
		if (managed != Managed.RUNTIME) return null;
		try {
			AString value = RT.ensureString(adapter.engine.resolvePath(
				Strings.create(legacySessionsPath(spec.name()) + "/" + chatId), context()));
			return value == null ? null : value.toString();
		} catch (RuntimeException e) {
			log.debug("Telegram bot '{}': could not migrate persisted session for chat {}: {}", spec.name(), chatId, concise(e));
			return null;
		}
	}

	// ---------------------------------------------------------- resolved handles

	/**
	 * Whether this Telegram user may talk to the bot: the spec's
	 * {@link BotSpec#allows allow list}, with a handle bound to an account.
	 *
	 * <p>A Telegram user id is the identity — permanent for the account, the
	 * same for every bot. A username is an alias the person can change or drop,
	 * and a freed handle can be taken by someone else. So an {@code @username}
	 * entry is an unresolved id: the first time that person writes, Telegram's
	 * authenticated sender resolves it, and the binding is kept beside the bot's
	 * sessions ({@code allow} itself is never rewritten). From then on the
	 * handle means that account: a later holder of the same handle is refused,
	 * and the bot may message the account ({@link #allowsTarget}). The operator
	 * clears a binding by deleting its record, or by listing the id instead.</p>
	 */
	private boolean admits(Long fromId, String fromName) {
		if (spec.open()) return true;
		if (fromId != null && spec.allowIds().contains(fromId)) return true;
		if (fromId == null || fromName == null) return false;
		String handle = fromName.toLowerCase(Locale.ROOT);
		if (!spec.allowNames().contains(handle)) return false;
		Long bound = resolved().putIfAbsent(handle, fromId);
		if (bound == null) {
			adapter.state().write(resolvedRelativePath() + "/" + handle, CVMLong.create(fromId));
			log.info("Telegram bot '{}': @{} resolved to Telegram account {}", spec.name(), handle, fromId);
			return true;
		}
		if (bound.longValue() == fromId) return true;
		log.warn("Telegram bot '{}': @{} is now account {} but resolved to {} when first seen — refusing; "
			+ "delete {} to rebind, or list the id in allow", spec.name(), handle, fromId, bound,
			adapter.state().path(resolvedRelativePath() + "/" + handle));
		return false;
	}

	/** Whether the bot may address this chat or user: the spec's ids, or an account a listed handle resolved to. */
	boolean allowsTarget(Object target) {
		if (spec.allowsTarget(target)) return true;
		Long id = BotSpec.targetId(target);
		return id != null && resolved().containsValue(id);
	}

	private String resolvedRelativePath() {
		return managed == Managed.CONFIG
			? "config/" + spec.name() + "/resolved"
			: adapter.userStatePath(spec.userDID(adapter.engine), "resolved/" + spec.name());
	}

	/** The persisted handle → account map, read once per runner. */
	private Map<String, Long> resolved() {
		if (!resolvedLoaded) {
			synchronized (resolved) {
				if (!resolvedLoaded) {
					AMap<AString, ACell> m = RT.castMap(adapter.state().read(resolvedRelativePath()));
					if (m != null) {
						for (var e : m.entrySet()) {
							CVMLong id = RT.ensureLong(e.getValue());
							if (e.getKey() instanceof AString handle && id != null) {
								resolved.putIfAbsent(handle.toString(), id.longValue());
							}
						}
					}
					resolvedLoaded = true;
				}
			}
		}
		return resolved;
	}


	// ----------------------------------------------------------------- outbound

	/** The Bot API parameters through which a call addresses a chat or user. */
	static final Set<String> TARGET_PARAMS = Set.of("chat_id", "from_chat_id", "user_id");

	/**
	 * Outbound confinement (#532). An allow-listed bot is its user's channel to
	 * the people on its allow list and must never talk to anyone else, so every
	 * party a call addresses — {@code chat_id}, the {@code from_chat_id} of a
	 * forward or copy, a {@code user_id} — must be one of its allowed users: a
	 * listed id, or the account a listed handle resolved to
	 * ({@link #allowsTarget}). A call that addresses no chat or user
	 * (answering a callback or inline query, editing by inline message id…)
	 * passes: its ids come from updates this bot admitted. The gate on the bot
	 * ({@code telegram/send}, {@code telegram/call}) says who may use the bot;
	 * this says whom the bot may reach, and the two are checked in that order.
	 *
	 * <p>The runner's own replies do not pass through here: they are addressed
	 * to the chat of an update this bot admitted, which for an allow-listed bot
	 * is an allowed user's private chat (see {@link #handle}).</p>
	 *
	 * @throws IllegalArgumentException naming the rule and the offending target
	 */
	void requireAllowedTarget(String method, Map<String, Object> params) {
		if (spec.open() || params == null) return;
		for (String key : TARGET_PARAMS) {
			Object target = params.get(key);
			if (target == null || allowsTarget(target)) continue;
			StringBuilder msg = new StringBuilder("Telegram bot '").append(spec.name())
				.append("' is allow-listed and only messages its allowed users: ").append(method).append(' ')
				.append(key).append(' ').append(target).append(" is not one of them (allowed ids: ")
				.append(spec.allowIds());
			if (!spec.allowNames().isEmpty()) {
				msg.append("; resolved handles: ").append(resolved())
					.append(" — a person listed by @username becomes addressable once they have written to the bot");
			}
			throw new IllegalArgumentException(msg.append(')').toString());
		}
	}

	/**
	 * Execute any Bot API method with Telegram-form parameters and return its
	 * {@code result} as a cell (a {@code Message} for the send methods,
	 * {@code true} for most others). Subject to {@link #requireAllowedTarget}.
	 *
	 * @throws JobFailedException when Telegram answers {@code ok: false}
	 * @throws IllegalStateException when the bot is not running
	 */
	record Upload(String filename, String contentType, byte[] bytes) {}

	ACell call(String method, Map<String, Object> params) {
		return call(method, params, Map.of());
	}

	ACell call(String method, Map<String, Object> params, Map<String, Upload> uploads) {
		requireAllowedTarget(method, params);
		ApiResponse r = execute(method, params, uploads);
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
		requireAllowedTarget("sendMessage", params);
		return deliver(params);
	}

	/** {@link #sendMessage} without the target check: the runner's own replies, addressed to an admitted update's chat. */
	private ACell deliver(Map<String, Object> params) {
		if (params.get("chat_id") == null) {
			throw new IllegalArgumentException("chat_id is required: a Telegram chat id or @channelusername");
		}
		Object textObj = params.get("text");
		String text = (textObj == null) ? "" : String.valueOf(textObj);
		if (text.isBlank()) throw new IllegalArgumentException("text is required");
		ACell last = null;
		boolean first = true;
		for (String chunk : MessagingText.split(text, MAX_MESSAGE_LENGTH)) {
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
		return execute(method, params, Map.of());
	}

	private ApiResponse execute(String method, Map<String, Object> params, Map<String, Upload> uploads) {
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
		okhttp3.RequestBody body = form.build();
		if (!uploads.isEmpty()) {
			okhttp3.MultipartBody.Builder multipart = new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM);
			if (params != null) params.forEach((key, value) -> { if (value != null) multipart.addFormDataPart(key, paramValue(value)); });
			uploads.forEach((key, file) -> multipart.addFormDataPart(key, file.filename(), okhttp3.RequestBody.create(
				okhttp3.MediaType.parse(file.contentType() == null ? "application/octet-stream" : file.contentType()), file.bytes())));
			body = multipart.build();
		}
		Request request = new Request.Builder().url(apiUrl + tok + "/" + method).post(body).build();
		try (Response response = c.newCall(request).execute()) {
			String responseBody = (response.body() != null) ? response.body().string() : "";
			JsonObject o = JsonParser.parseString(responseBody.isBlank() ? "{}" : responseBody).getAsJsonObject();
			boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
			int code = o.has("error_code") ? o.get("error_code").getAsInt() : (ok ? 200 : response.code());
			String description = o.has("description") ? o.get("description").getAsString()
				: (ok ? "" : "HTTP " + response.code());
			return new ApiResponse(ok, code, description, o.get("result"));
		} catch (java.io.IOException | RuntimeException e) {
			throw new JobFailedException("Telegram " + method + " transport failed");
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
		return deliver(p);
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
