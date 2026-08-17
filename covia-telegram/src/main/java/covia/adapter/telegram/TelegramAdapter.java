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

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
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
 * invoked per update with the Telegram {@code Update} exactly as sent
 * (snake_case, {@code message}/{@code callback_query}/… nested as Telegram
 * nests them) plus {@code bot}, the reply governed by {@code reply}
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
 *   <li>{@code telegram:send} — Telegram's {@code sendMessage} parameters as-is
 *       plus {@code bot}, returning the sent {@code Message}. Gated on
 *       {@code <owner>/telegram/<bot>} × {@code telegram/send}: the bot's user
 *       (and their agents, within scope) may send; anyone else needs a
 *       delegation from that user.</li>
 *   <li>{@code telegram:call} {@code {bot?, method, params}} — any Bot API method
 *       with its documented parameters (media by file_id/URL, edits, callback
 *       answers, keyboards…), gated on {@code telegram/call}; the methods that
 *       drive the update stream are refused.</li>
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
	static final AString K_METHOD = Strings.intern("method");
	static final AString K_PARAMS = Strings.intern("params");
	static final String K_PARSE_MODE_PARAM = "parse_mode";
	private static final AString K_ENABLED = Strings.intern("enabled");

	/** Ability required to send messages through a bot; resource {@code <owner>/telegram/<bot>}. */
	public static final AString ABILITY_SEND = Strings.intern("telegram/send");
	/** Ability required to call arbitrary Bot API methods through a bot (a superset of send). */
	public static final AString ABILITY_CALL = Strings.intern("telegram/call");

	/** Methods that belong to the venue's own update loop for a bot; refused by {@code telegram:call}. */
	static final Set<String> MANAGED_METHODS = Set.of("getUpdates", "setWebhook", "deleteWebhook", "logOut", "close");

	private static final Set<AString> KNOWN_KEYS = Set.of(K_BOTS, K_API_URL, K_ENABLED);
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
			+ "agents (one conversation per chat) or hand each Update to an operation, while telegram:send "
			+ "and telegram:call let agents and users use the Bot API through a bot they own.";
	}

	@Override
	protected void installAssets() {
		installAsset("telegram/send", "/adapters/telegram/send.json");
		installAsset("telegram/call", "/adapters/telegram/call.json");
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
			case "call" -> CompletableFuture.supplyAsync(() -> handleCall(ctx, input), VIRTUAL_EXECUTOR);
			case "bots" -> CompletableFuture.supplyAsync(() -> handleBots(ctx), VIRTUAL_EXECUTOR);
			default -> throw new UnsupportedOperationException("Unsupported telegram operation: " + subOp);
		};
	}

	/**
	 * {@code telegram:send}: Telegram's own {@code sendMessage} parameters
	 * ({@code chat_id}, {@code text}, {@code parse_mode}, {@code reply_parameters},
	 * {@code reply_markup}, …) plus {@code bot}; returns the sent {@code Message}
	 * as Telegram describes it. Long text is split and rejected markup falls
	 * back to plain text (see {@link BotRunner#sendMessage}).
	 */
	ACell handleSend(RequestContext ctx, ACell input) {
		AMap<AString, ACell> in = RT.castMap(input);
		if (in == null) throw new IllegalArgumentException("send expects an object of sendMessage parameters");
		BotRunner runner = selectBot(ctx, RT.ensureString(in.get(K_BOT)));
		requireBotAccess(ctx, runner, ABILITY_SEND);
		Map<String, Object> params = telegramParams(in.dissoc(K_BOT));
		if (!params.containsKey(K_PARSE_MODE_PARAM) && runner.spec.parseMode() != null) {
			params.put(K_PARSE_MODE_PARAM, runner.spec.parseMode());
		}
		return runner.sendMessage(params);
	}

	/**
	 * {@code telegram:call}: any Bot API method by name with its Telegram-form
	 * {@code params}, answering with the raw {@code result}. The methods that
	 * would interfere with the venue's own update stream are refused.
	 */
	ACell handleCall(RequestContext ctx, ACell input) {
		BotRunner runner = selectBot(ctx, RT.ensureString(RT.getIn(input, K_BOT)));
		requireBotAccess(ctx, runner, ABILITY_CALL);
		AString methodCell = RT.ensureString(RT.getIn(input, K_METHOD));
		if (methodCell == null || methodCell.isEmpty()) {
			throw new IllegalArgumentException("method is required: a Bot API method name such as sendPhoto");
		}
		String method = methodCell.toString().trim();
		if (!method.matches("[A-Za-z]+")) throw new IllegalArgumentException("method must be a Bot API method name: " + method);
		if (MANAGED_METHODS.contains(method)) {
			throw new IllegalArgumentException("Bot API method " + method + " is managed by the venue's own "
				+ "update loop for this bot and cannot be called");
		}
		ACell paramsCell = RT.getIn(input, K_PARAMS);
		AMap<AString, ACell> paramsMap = (paramsCell == null) ? Maps.empty() : RT.castMap(paramsCell);
		if (paramsMap == null) throw new IllegalArgumentException("params must be an object of Bot API parameters");
		return runner.call(method, telegramParams(paramsMap));
	}

	/** Gate on {@code <bot user>/telegram/<bot>} × ability: the bot's user and their agents, or a delegation. */
	private void requireBotAccess(RequestContext ctx, BotRunner runner, AString ability) {
		AString owner = runner.spec.userDID(engine);
		engine.requireLocalAccess(ctx, Strings.create(owner + "/telegram/" + runner.spec.name()), ability);
	}

	/** Cells → the plain Java values the HTTP layer encodes (scalars as text, maps/lists as JSON). */
	private static Map<String, Object> telegramParams(AMap<AString, ACell> cells) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (cells == null) return out;
		for (long i = 0; i < cells.count(); i++) {
			var e = cells.entryAt(i);
			out.put(String.valueOf(e.getKey()), JSON.json(e.getValue()));
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
