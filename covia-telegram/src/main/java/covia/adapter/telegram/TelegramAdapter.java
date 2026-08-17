package covia.adapter.telegram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pengrad.telegrambot.response.SendResponse;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Telegram adapter: operator-declared bots that connect Telegram chats to a
 * venue, and operations for sending messages through them.
 *
 * <p><b>Bots</b> are declared under {@code adapters.telegram.bots.<name>}
 * (see {@link BotSpec}). Each runs as its configured {@code user} with one
 * <b>inbound handler</b>: an agent — one {@code agent:chat} session per
 * Telegram chat, persisted at {@code w/telegram/<bot>/sessions/<chatId>} in
 * the user's workspace so conversations survive restarts — or an operation,
 * invoked per message with the record {@code {bot, chatId, messageId, text,
 * from, chat, date}} as its input, the reply governed by {@code reply}
 * (result, silent, or a fixed acknowledgement). Every inbound message runs
 * as a Job in the bot user's job index — the canonical record of the
 * interaction; the module keeps no log of its own and never reshapes
 * messages: a target that wants a different input (a SQL write, a webhook,
 * a log somewhere) is reached through a mapping operation the operator
 * owns. Inbound access is fail-closed: only Telegram users on the bot's
 * {@code allow} list are answered unless the bot is {@code open}.</p>
 *
 * <p>Because bots are effective adapter configuration, they follow the
 * runtime adapter lifecycle: {@code v/ops/venue/adapter/configure} adds,
 * removes and changes bots on a running venue (changed bots restart; the
 * rest are untouched), and {@code adapters.telegram.enabled: false} parks the
 * whole adapter. Runtime changes are not persisted.</p>
 *
 * <p><b>Operations</b>:</p>
 * <ul>
 *   <li>{@code telegram:send} {@code {bot?, chatId, text, parseMode?, replyTo?,
 *       silent?}} — send a message. Gated on {@code <owner>/telegram/<bot>}
 *       × {@code telegram/send}: the bot's user (and their agents, within
 *       scope) may send; anyone else needs a delegation from that user.</li>
 *   <li>{@code telegram:bots} — status of the caller's bots (all bots for the
 *       venue identity). Tokens are never returned.</li>
 * </ul>
 *
 * <p>Tokens are {@code s/NAME} secret references resolved in the bot user's
 * store, then the venue's; a bot whose secret is absent parks as
 * {@code PENDING} and retries, so provisioning the secret later brings it up
 * without a restart. Literal tokens are accepted (config is operator-side)
 * but never logged or listed.</p>
 */
public class TelegramAdapter extends AAdapter implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);

	public static final String NAME = "telegram";
	static final String DEFAULT_API_URL = "https://api.telegram.org/bot";

	static final AString K_BOTS = Strings.intern("bots");
	static final AString K_API_URL = Strings.intern("apiUrl");
	static final AString K_BOT = Strings.intern("bot");
	static final AString K_CHAT_ID = Strings.intern("chatId");
	static final AString K_REPLY_TO = Strings.intern("replyTo");
	static final AString K_SILENT = Strings.intern("silent");
	private static final AString K_ENABLED = Strings.intern("enabled");

	/** Ability required to send through a bot; resource {@code <owner>/telegram/<bot>}. */
	public static final AString ABILITY_SEND = Strings.intern("telegram/send");

	private static final Set<AString> KNOWN_KEYS = Set.of(K_BOTS, K_API_URL, K_ENABLED);
	private static final Set<String> PARSE_MODES = Set.of("Markdown", "MarkdownV2", "HTML");
	private static final AString[] TEXT_KEYS = {
		Fields.TEXT, Fields.RESPONSE, Strings.intern("content"), Fields.MESSAGE, Fields.RESULT };

	private volatile Map<String, BotSpec> specs = Map.of();
	private volatile String apiUrl = DEFAULT_API_URL;
	/** Live runners by bot name. Guarded by {@code this}. */
	private final Map<String, BotRunner> runners = new LinkedHashMap<>();
	private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "telegram-retry");
		t.setDaemon(true);
		return t;
	});
	/** Delay before a PENDING bot retries. Package-private so tests can shorten it. */
	volatile long retryMillis = 30_000;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Telegram bots as a venue front door: operator-declared bots route Telegram chats to "
			+ "agents (one conversation per chat) or to any operation, and telegram:send lets agents "
			+ "and users message Telegram chats through a bot they own.";
	}

	@Override
	protected void installAssets() {
		installAsset("telegram/send", "/adapters/telegram/send.json");
		installAsset("telegram/bots", "/adapters/telegram/bots.json");
		// The skill travels with the capability: v/skills/telegram exists
		// exactly when this module is loaded.
		installSkill("telegram", "/skills/telegram.json");
	}

	// ------------------------------------------------------------ configuration

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (strict) {
			for (long i = 0; i < config.count(); i++) {
				ACell k = config.entryAt(i).getKey();
				if (!(k instanceof AString ks) || !KNOWN_KEYS.contains(ks)) {
					throw new IllegalArgumentException("adapters.telegram: unknown setting " + k
						+ " (known: bots, apiUrl, enabled)");
				}
			}
		}
		String url = DEFAULT_API_URL;
		ACell urlCell = config.get(K_API_URL);
		if (urlCell != null) {
			if (!(urlCell instanceof AString s) || s.isEmpty()) {
				throw new IllegalArgumentException("adapters.telegram.apiUrl must be a non-empty string");
			}
			url = s.toString();
			if (!url.startsWith("http://") && !url.startsWith("https://")) {
				throw new IllegalArgumentException("adapters.telegram.apiUrl must be an http(s) URL: " + url);
			}
		}
		Map<String, BotSpec> parsed = new LinkedHashMap<>();
		ACell botsCell = config.get(K_BOTS);
		if (botsCell != null) {
			AMap<AString, ACell> bots = RT.castMap(botsCell);
			if (bots == null) throw new IllegalArgumentException("adapters.telegram.bots must be an object of bot name -> settings");
			for (long i = 0; i < bots.count(); i++) {
				var e = bots.entryAt(i);
				String name = String.valueOf(e.getKey());
				parsed.put(name, BotSpec.parse(name, e.getValue(), strict));
			}
		}
		this.apiUrl = url;
		this.specs = Map.copyOf(parsed);
		if (engine != null) reconcile();
		return true;
	}

	@Override
	public void install(Engine engine) {
		super.install(engine);
		reconcile();
	}

	/** Bring the running bots in line with the configured specs. */
	private synchronized void reconcile() {
		String url = apiUrl;
		Map<String, BotSpec> wanted = specs;
		List<String> stale = new ArrayList<>();
		for (var e : runners.entrySet()) {
			BotRunner r = e.getValue();
			BotSpec want = wanted.get(e.getKey());
			if (want == null || !want.equals(r.spec) || !url.equals(r.apiUrl)) stale.add(e.getKey());
		}
		for (String name : stale) {
			runners.remove(name).stop();
		}
		for (BotSpec spec : wanted.values()) {
			if (runners.containsKey(spec.name())) continue;
			BotRunner r = new BotRunner(this, spec, url);
			runners.put(spec.name(), r);
			r.start();
		}
	}

	@Override
	public synchronized void close() {
		for (BotRunner r : runners.values()) r.stop();
		runners.clear();
		retryExecutor.shutdownNow();
	}

	// ------------------------------------------------------- runner support API

	/** Whether this adapter is currently the active {@code telegram} adapter. */
	boolean isActive() {
		return engine != null && engine.getAdapter(NAME) == this;
	}

	ScheduledFuture<?> scheduleRetry(Runnable task, long delayMillis) {
		if (retryExecutor.isShutdown()) return null;
		return retryExecutor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * The bot token for a spec: an {@code s/} reference resolved in the bot
	 * user's store, then the venue's; a literal otherwise. Null when the
	 * referenced secret does not exist.
	 */
	String resolveToken(BotSpec spec) {
		String ref = spec.tokenRef();
		if (!(ref.startsWith("s/") || ref.startsWith("/s/"))) return ref;
		String value = engine.resolveSecret(ref, RequestContext.of(spec.userDID(engine)));
		if (value == null) value = engine.resolveSecret(ref, engine.venueContext());
		return value;
	}

	synchronized BotRunner runner(String name) {
		return runners.get(name);
	}

	private synchronized List<BotRunner> runnerList() {
		return new ArrayList<>(runners.values());
	}

	// --------------------------------------------------------------- operations

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) throw new IllegalArgumentException("Insufficient specification for telegram operation");
		return switch (subOp) {
			case "send" -> CompletableFuture.supplyAsync(() -> handleSend(ctx, input), VIRTUAL_EXECUTOR);
			case "bots" -> CompletableFuture.supplyAsync(() -> handleBots(ctx), VIRTUAL_EXECUTOR);
			default -> throw new UnsupportedOperationException("Unsupported telegram operation: " + subOp);
		};
	}

	ACell handleSend(RequestContext ctx, ACell input) {
		BotRunner runner = selectBot(ctx, RT.ensureString(RT.getIn(input, K_BOT)));
		AString owner = runner.spec.userDID(engine);
		engine.requireLocalAccess(ctx, Strings.create(owner + "/telegram/" + runner.spec.name()), ABILITY_SEND);

		ACell chatCell = RT.getIn(input, K_CHAT_ID);
		Object chatId;
		CVMLong chatLong = RT.ensureLong(chatCell);
		if (chatLong != null) {
			chatId = chatLong.longValue();
		} else if (chatCell instanceof AString s && !s.isEmpty()) {
			String str = s.toString().trim();
			chatId = str.matches("-?\\d+") ? (Object) Long.parseLong(str) : str;
		} else {
			throw new IllegalArgumentException("chatId is required: a numeric Telegram chat id or @channelusername");
		}
		AString text = RT.ensureString(RT.getIn(input, Fields.TEXT));
		if (text == null || text.isEmpty()) throw new IllegalArgumentException("text is required");
		AString pm = RT.ensureString(RT.getIn(input, BotSpec.K_PARSE_MODE));
		String parseMode = (pm != null) ? pm.toString() : runner.spec.parseMode();
		if (parseMode != null && !PARSE_MODES.contains(parseMode)) {
			throw new IllegalArgumentException("parseMode must be one of Markdown, MarkdownV2, HTML: " + parseMode);
		}
		CVMLong replyTo = RT.ensureLong(RT.getIn(input, K_REPLY_TO));
		boolean silent = CVMBool.TRUE.equals(RT.getIn(input, K_SILENT));

		SendResponse resp = runner.send(chatId, text.toString(), parseMode,
			(replyTo != null) ? (int) replyTo.longValue() : null, null, silent);
		AMap<AString, ACell> out = Maps.of(
			K_BOT, Strings.create(runner.spec.name()),
			K_CHAT_ID, (chatId instanceof Long l) ? CVMLong.create(l) : Strings.create(chatId.toString()));
		if (resp != null && resp.message() != null && resp.message().messageId() != null) {
			out = out.assoc(Fields.MESSAGE_ID, CVMLong.create(resp.message().messageId()));
		}
		return out;
	}

	ACell handleBots(RequestContext ctx) {
		AString callerUser = ctx.getUserDID();
		boolean venue = engine.getDIDString().equals(ctx.getCallerDID());
		AVector<ACell> out = Vectors.empty();
		for (BotRunner r : runnerList()) {
			if (venue || (callerUser != null && callerUser.equals(r.spec.userDID(engine)))) {
				out = out.conj(r.status());
			}
		}
		return Maps.of(K_BOTS, out);
	}

	/** The named bot, or the caller's only bot when unnamed. */
	private BotRunner selectBot(RequestContext ctx, AString name) {
		if (name != null && !name.isEmpty()) {
			BotRunner r = runner(name.toString());
			if (r == null) throw new IllegalArgumentException("Unknown Telegram bot: " + name);
			return r;
		}
		AString callerUser = ctx.getUserDID();
		List<BotRunner> mine = new ArrayList<>();
		for (BotRunner r : runnerList()) {
			if (callerUser != null && callerUser.equals(r.spec.userDID(engine))) mine.add(r);
		}
		if (mine.size() == 1) return mine.get(0);
		if (mine.isEmpty()) {
			throw new IllegalArgumentException("No Telegram bot is configured for " + callerUser
				+ " — name one with 'bot' or declare it under adapters.telegram.bots");
		}
		List<String> names = new ArrayList<>();
		for (BotRunner r : mine) names.add(r.spec.name());
		throw new IllegalArgumentException("Several Telegram bots are available; specify 'bot': " + names);
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * A result as reply text: a string as-is; a map's first string-valued
	 * {@code text}/{@code response}/{@code content}/{@code message}/{@code result};
	 * anything else as pretty JSON.
	 */
	static String renderText(ACell value) {
		if (value == null) return null;
		if (value instanceof AString s) return s.toString();
		if (value instanceof AMap<?, ?> m) {
			for (AString key : TEXT_KEYS) {
				ACell v = RT.getIn(m, key);
				if (v instanceof AString s) return s.toString();
			}
		}
		return JSON.printPretty(value).toString();
	}
}
