package covia.adapter.telegram;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import convex.core.util.JSON;

/**
 * A minimal in-process stand-in for the Telegram Bot API, just enough for the
 * pengrad client's long-polling loop and the requests this module makes:
 * {@code getMe}, {@code getUpdates}, {@code sendMessage}, {@code sendChatAction}.
 *
 * <p>Requests arrive as {@code POST <base>/bot<token>/<method>} with
 * form-encoded bodies. Any number of bots may be {@link #registerBot registered}
 * by token; an unknown token gets {@code 401 Unauthorized} exactly like
 * Telegram. Tests push inbound updates with {@link #push} and observe outbound
 * messages via {@link #awaitSent}.</p>
 */
final class FakeTelegramServer implements AutoCloseable {

	static final String TOKEN = "123456:FAKE-TOKEN-FOR-TESTS";
	static final String BOT_USERNAME = "covia_fake_bot";

	/** Text that makes {@code sendMessage} fail with a 400 when a parse_mode is set. */
	static final String BAD_MARKUP = "BAD_MARKUP";

	/** One outbound send* call as observed by the fake ({@code form} holds every parameter as sent). */
	record Sent(String method, long chatId, String text, String parseMode, Integer replyTo, boolean silent,
			int messageId, Map<String, String> form) {}

	/** Per-token state: the fake serves any number of bots at once. */
	private static final class Bot {
		final String username;
		/** Updates not yet confirmed by an offset — Telegram keeps redelivering these. */
		final List<Map<String, Object>> updates = new ArrayList<>();
		final BlockingQueue<Sent> sent = new LinkedBlockingQueue<>();
		final AtomicInteger getMeCalls = new AtomicInteger();
		Bot(String username) { this.username = username; }
	}

	private final HttpServer server;
	private final Map<String, Bot> bots = new ConcurrentHashMap<>();
	private final AtomicInteger updateIds = new AtomicInteger(1000);
	private final AtomicInteger messageIds = new AtomicInteger(1);

	FakeTelegramServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "fake-telegram");
			t.setDaemon(true);
			return t;
		}));
		server.start();
		registerBot(TOKEN, BOT_USERNAME);
	}

	/** The {@code apiUrl} to configure the adapter with. */
	String apiUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/bot";
	}

	/** Accept another token, answering getMe with the given username. */
	void registerBot(String token, String username) {
		bots.put(token, new Bot(username));
	}

	int getMeCalls(String token) {
		return bot(token).getMeCalls.get();
	}

	/** Queue an inbound text message from a Telegram user in a private chat of the default bot. */
	int push(long chatId, long fromId, String fromUsername, String text) {
		return push(TOKEN, chatId, "private", fromId, fromUsername, text);
	}

	int push(String token, long chatId, String chatType, long fromId, String fromUsername, String text) {
		Map<String, Object> message = message(chatId, chatType, fromId, fromUsername);
		message.put("text", text);
		Map<String, Object> update = new HashMap<>();
		update.put("message", message);
		return pushUpdate(token, update);
	}

	/** Queue an inbound photo (two sizes) with a caption — a message with no {@code text}. */
	int pushPhoto(String token, long chatId, long fromId, String fromUsername, String caption) {
		Map<String, Object> message = message(chatId, "private", fromId, fromUsername);
		List<Map<String, Object>> sizes = new ArrayList<>();
		for (String id : new String[] {"AgACsmall", "AgACbig"}) {
			Map<String, Object> size = new HashMap<>();
			size.put("file_id", id);
			size.put("file_unique_id", id + "u");
			size.put("width", id.endsWith("big") ? 800 : 90);
			size.put("height", id.endsWith("big") ? 600 : 67);
			sizes.add(size);
		}
		message.put("photo", sizes);
		if (caption != null) message.put("caption", caption);
		Map<String, Object> update = new HashMap<>();
		update.put("message", message);
		return pushUpdate(token, update);
	}

	/** Queue an inline-keyboard tap: a callback_query on a message in the chat. */
	int pushCallback(String token, long chatId, long fromId, String fromUsername, String data) {
		Map<String, Object> from = user(fromId, fromUsername);
		Map<String, Object> message = message(chatId, "private", 42L, BOT_USERNAME);
		message.put("text", "Approve?");
		Map<String, Object> cq = new HashMap<>();
		cq.put("id", "cq" + updateIds.get());
		cq.put("from", from);
		cq.put("message", message);
		cq.put("chat_instance", "ci1");
		cq.put("data", data);
		Map<String, Object> update = new HashMap<>();
		update.put("callback_query", cq);
		return pushUpdate(token, update);
	}

	/** Queue any Telegram Update object (without {@code update_id}, which is assigned here). */
	int pushUpdate(String token, Map<String, Object> update) {
		int updateId = updateIds.incrementAndGet();
		update.put("update_id", updateId);
		Bot b = bot(token);
		synchronized (b.updates) {
			b.updates.add(update);
			b.updates.notifyAll();
		}
		return updateId;
	}

	private static Map<String, Object> user(long id, String username) {
		Map<String, Object> from = new HashMap<>();
		from.put("id", id);
		from.put("is_bot", false);
		from.put("first_name", "Test");
		if (username != null) from.put("username", username);
		return from;
	}

	private Map<String, Object> message(long chatId, String chatType, long fromId, String fromUsername) {
		Map<String, Object> chat = new HashMap<>();
		chat.put("id", chatId);
		chat.put("type", chatType);
		Map<String, Object> message = new HashMap<>();
		message.put("message_id", messageIds.incrementAndGet());
		message.put("date", System.currentTimeMillis() / 1000);
		message.put("chat", chat);
		message.put("from", user(fromId, fromUsername));
		return message;
	}

	/** Next outbound message of the default bot, waiting up to {@code timeoutMs}; null on timeout. */
	Sent awaitSent(long timeoutMs) throws InterruptedException {
		return awaitSent(TOKEN, timeoutMs);
	}

	Sent awaitSent(String token, long timeoutMs) throws InterruptedException {
		return bot(token).sent.poll(timeoutMs, TimeUnit.MILLISECONDS);
	}

	/** Drain any already-observed outbound messages of the default bot. */
	List<Sent> drainSent() {
		List<Sent> out = new ArrayList<>();
		bot(TOKEN).sent.drainTo(out);
		return out;
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private Bot bot(String token) {
		Bot b = bots.get(token);
		if (b == null) throw new IllegalArgumentException("unregistered fake bot token");
		return b;
	}

	// ------------------------------------------------------------ HTTP handling

	private void handle(HttpExchange ex) throws IOException {
		try {
			String path = ex.getRequestURI().getPath();          // /bot<token>/<method>
			Map<String, String> form = parseForm(ex.getRequestBody());
			if (!path.startsWith("/bot")) {
				reply(ex, 404, error(404, "Not Found"));
				return;
			}
			int slash = path.indexOf('/', 4);
			String token = (slash < 0) ? path.substring(4) : path.substring(4, slash);
			String method = (slash < 0) ? "" : path.substring(slash + 1);
			Bot bot = bots.get(token);
			if (bot == null) {
				reply(ex, 401, error(401, "Unauthorized"));
				return;
			}
			switch (method) {
				case "getMe" -> {
					bot.getMeCalls.incrementAndGet();
					Map<String, Object> me = new HashMap<>();
					me.put("id", 42L);
					me.put("is_bot", true);
					me.put("first_name", "Covia Fake");
					me.put("username", bot.username);
					reply(ex, 200, ok(me));
				}
				case "getUpdates" -> {
					int offset = form.containsKey("offset") ? Integer.parseInt(form.get("offset")) : 0;
					long timeoutSecs = form.containsKey("timeout") ? Long.parseLong(form.get("timeout")) : 0;
					// Telegram semantics: an offset confirms (drops) everything below it;
					// everything at or above it is (re)delivered. Honour long polling,
					// but never wait long enough to slow tests.
					long deadline = System.currentTimeMillis() + Math.min(timeoutSecs * 1000, 500);
					List<Map<String, Object>> result = new ArrayList<>();
					synchronized (bot.updates) {
						bot.updates.removeIf(u -> ((Integer) u.get("update_id")) < offset);
						while (bot.updates.isEmpty()) {
							long left = deadline - System.currentTimeMillis();
							if (left <= 0) break;
							bot.updates.wait(left);
						}
						result.addAll(bot.updates);
					}
					reply(ex, 200, ok(result));
				}
				case "sendMessage" -> {
					String text = form.getOrDefault("text", "");
					String parseMode = form.get("parse_mode");
					if (parseMode != null && text.contains(BAD_MARKUP)) {
						reply(ex, 400, error(400, "Bad Request: can't parse entities"));
						return;
					}
					long chatId = Long.parseLong(form.get("chat_id"));
					Integer replyTo = null;
					String rp = form.get("reply_parameters");
					if (rp != null) {
						Map<String, Object> rpm = JSON.jvm(rp);
						Object mid = rpm.get("message_id");
						if (mid instanceof Number n) replyTo = n.intValue();
					}
					boolean silent = "true".equals(form.get("disable_notification"));
					int messageId = messageIds.incrementAndGet();
					bot.sent.add(new Sent("sendMessage", chatId, text, parseMode, replyTo, silent, messageId, form));
					Map<String, Object> chat = new HashMap<>();
					chat.put("id", chatId);
					chat.put("type", "private");
					Map<String, Object> message = new HashMap<>();
					message.put("message_id", messageId);
					message.put("date", System.currentTimeMillis() / 1000);
					message.put("chat", chat);
					message.put("text", text);
					reply(ex, 200, ok(message));
				}
				case "sendChatAction", "deleteWebhook", "answerCallbackQuery", "deleteMessage" -> reply(ex, 200, ok(Boolean.TRUE));
				default -> {
					if (method.startsWith("send") && form.containsKey("chat_id")) {
						// Any other send* method (sendPhoto, sendDocument…): record it and
						// answer with a Message carrying the caption as text.
						long chatId = Long.parseLong(form.get("chat_id"));
						int messageId = messageIds.incrementAndGet();
						bot.sent.add(new Sent(method, chatId, form.get("caption"), form.get("parse_mode"), null,
							"true".equals(form.get("disable_notification")), messageId, form));
						Map<String, Object> chat = new HashMap<>();
						chat.put("id", chatId);
						chat.put("type", "private");
						Map<String, Object> message = new HashMap<>();
						message.put("message_id", messageId);
						message.put("date", System.currentTimeMillis() / 1000);
						message.put("chat", chat);
						if (form.get("caption") != null) message.put("caption", form.get("caption"));
						reply(ex, 200, ok(message));
					} else {
						reply(ex, 404, error(404, "Not Found: method " + method));
					}
				}
			}
		} catch (Exception e) {
			reply(ex, 500, error(500, e.toString()));
		}
	}

	private static Map<String, String> parseForm(InputStream body) throws IOException {
		String raw = new String(body.readAllBytes(), StandardCharsets.UTF_8);
		Map<String, String> out = new HashMap<>();
		if (raw.isEmpty()) return out;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			out.put(k, v);
		}
		return out;
	}

	private static String ok(Object result) {
		Map<String, Object> m = new HashMap<>();
		m.put("ok", true);
		m.put("result", result);
		return JSON.toString(m);
	}

	private static String error(int code, String description) {
		Map<String, Object> m = new HashMap<>();
		m.put("ok", false);
		m.put("error_code", code);
		m.put("description", description);
		return JSON.toString(m);
	}

	private static void reply(HttpExchange ex, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (var os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
