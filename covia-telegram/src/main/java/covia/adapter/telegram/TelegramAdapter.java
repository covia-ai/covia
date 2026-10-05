package covia.adapter.telegram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.AMessagingAdapter;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.adapter.messaging.ConversationRouter;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * Telegram adapter: operator-declared bots that connect Telegram chats to a
 * venue, and operations for sending messages through them.
 *
 * <p><b>Bots</b> are declared under {@code adapters.telegram.bots.<name>}
 * (see {@link BotSpec}) by the operator, or created at runtime by a user with
 * {@code telegram:create} (acting as that user, recorded at
 * {@code w/adapters/telegram/users/<did>/bots/<name>} in the venue's private
 * adapter workspace and re-armed at boot;
 * {@code telegram:delete} removes them). Each runs as its {@code user} with one
 * <b>inbound handler</b>: an agent — one {@code agent:chat} session per
 * Telegram chat, persisted in the same private adapter workspace so
 * conversations survive restarts — or an operation,
 * invoked per update with the Telegram {@code Update} exactly as sent
 * (snake_case, {@code message}/{@code callback_query}/… nested as Telegram
 * nests them) plus {@code bot}, the reply governed by {@code reply}
 * (result, silent, or a fixed acknowledgement). Every inbound message runs
 * as a Job in the bot user's job index — the canonical record of the
 * interaction; the module keeps no log of its own and never reshapes
 * messages: a target that wants a different input (a SQL write, a webhook,
 * a log somewhere) is reached through a mapping operation the operator
 * owns. Access is fail-closed in both directions: only Telegram users on the
 * bot's {@code allow} list are answered unless the bot is {@code open}, and
 * an allow-listed bot is its user's channel to those people alone — it
 * converses only in private chats, and {@code telegram:send} /
 * {@code telegram:call} may only address the ids on its list or the accounts
 * its listed handles resolved to ({@link BotRunner#requireAllowedTarget},
 * #532).</p>
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
 *   <li>{@code telegram:create} / {@code telegram:delete} — the caller's own
 *       bots, gated on {@code telegram/manage}.</li>
 *   <li>{@code telegram:bots} — status of the caller's bots (all bots for the
 *       venue identity), with {@code managed: config | runtime}. Tokens are
 *       never returned.</li>
 * </ul>
 *
 * <p>Tokens are {@code s/NAME} secret references resolved in the bot user's
 * store, then the venue's; a bot whose secret is absent parks as
 * {@code PENDING} and retries, so provisioning the secret later brings it up
 * without a restart. Literal tokens are accepted (config is operator-side)
 * but never logged or listed.</p>
 */
public class TelegramAdapter extends AMessagingAdapter<BotSpec, BotRunner> {

	private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);

	public static final String NAME = "telegram";
	static final String DEFAULT_API_URL = "https://api.telegram.org/bot";

	static final AString K_BOTS = Strings.intern("bots");
	static final AString K_API_URL = Strings.intern("apiUrl");
	static final AString K_BOT = Strings.intern("bot");
	static final AString K_METHOD = Strings.intern("method");
	static final AString K_PARAMS = Strings.intern("params");
	static final AString K_DELETED = Strings.intern("deleted");
	/** Pre-adapter-workspace location, read and cleaned up for compatibility. */
	static final String LEGACY_REGISTRY_PATH = "w/telegram/bots";
	private static final AString K_STATE_PATH = Strings.intern("statePath");
	static final String K_PARSE_MODE_PARAM = "parse_mode";
	private static final AString K_ENABLED = Strings.intern("enabled");

	/** Ability required to send messages through a bot; resource {@code <owner>/telegram/<bot>}. */
	public static final AString ABILITY_SEND = Strings.intern("telegram/send");
	/** Ability required to call arbitrary Bot API methods through a bot (a superset of send). */
	public static final AString ABILITY_CALL = Strings.intern("telegram/call");
	/** Ability required to create or delete one's own bots; resource {@code <owner>/telegram/<bot>}. */
	public static final AString ABILITY_MANAGE = Strings.intern("telegram/manage");

	/** Methods that belong to the venue's own update loop for a bot; refused by {@code telegram:call}. */
	static final Set<String> MANAGED_METHODS = Set.of("getUpdates", "setWebhook", "deleteWebhook", "logOut", "close");

	private static final Set<AString> KNOWN_KEYS = Set.of(K_BOTS, K_API_URL, K_ENABLED);
	private volatile String apiUrl = DEFAULT_API_URL;

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

	/** Public settings: the effective Bot API base URL. Bots themselves (tokens, allow-lists) are not published — telegram:bots shows each user their own. */
	@Override
	public AMap<AString, ACell> publicConfig() {
		return Maps.of(K_API_URL, Strings.create(apiUrl));
	}

	@Override
	protected void installAssets() {
		installAsset("telegram/send", "/adapters/telegram/send.json");
		installAsset("telegram/call", "/adapters/telegram/call.json");
		installAsset("telegram/create", "/adapters/telegram/create.json");
		installAsset("telegram/delete", "/adapters/telegram/delete.json");
		installAsset("telegram/bots", "/adapters/telegram/bots.json");
		// The skills travel with the capability: ordinary Telegram use stays
		// lightweight, while the parent reveals bot-management authority on demand.
		installSkill("adapters/telegram", "/skills/telegram.json");
		installSkill("adapters/telegram-bot-management", "/skills/telegram-bot-management.json");
		// …and the agent template for a bot-facing assistant: v/agents/templates/telegram
		// (mirrored at v/adapters/telegram/templates/telegram), gone with the module.
		installAgentTemplate("telegram", "/agent-templates/telegram.json");
	}

	// ------------------------------------------------------------ configuration

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (config.containsKey(K_STATE_PATH)) {
			throw new IllegalArgumentException("adapters.telegram.statePath is fixed at w/adapters/telegram");
		}
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
		configureBots(parsed);
		return true;
	}

	@Override
	protected BotSpec parseBot(String name, ACell settings, boolean strict) {
		return BotSpec.parse(name, settings, strict);
	}

	@Override
	protected BotRunner newBot(BotSpec spec, Managed managed) {
		return new BotRunner(this, spec, apiUrl, managed);
	}

	@Override
	protected boolean configurationMatches(BotRunner runner) {
		return apiUrl.equals(runner.apiUrl);
	}

	/** Canonical records are loaded first; legacy records only fill missing bindings. */
	@Override
	protected void rearmLegacyBots() {
		AMap<AString, ACell> users = engine.getVenueState().users().getAll();
		if (users == null || users.isEmpty()) return;
		for (var userEntry : users.entrySet()) {
			if (!(userEntry.getKey() instanceof AString owner)) continue;
			AMap<AString, ACell> registry;
			try {
				registry = RT.castMap(engine.resolvePath(Strings.create(LEGACY_REGISTRY_PATH), RequestContext.of(owner)));
			} catch (RuntimeException e) {
				log.warn("Telegram: could not read legacy bot registry of {}: {}", owner, e.getMessage());
				continue;
			}
			startRegistry(owner, registry, true);
		}
	}

	String resolveToken(BotSpec spec) {
		return resolveCredential(spec.tokenRef(), spec.userDID(engine));
	}

	/** Test hooks retain the provider tests' restart simulation. */
	void forgetForTest(AString owner, String name) { forgetRuntimeBot(owner, name); }
	void rearmForTest() { rearmRuntimeBots(); }

	// --------------------------------------------------------------- operations

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) throw new IllegalArgumentException("Insufficient specification for telegram operation");
		return switch (subOp) {
			case "send" -> CompletableFuture.supplyAsync(() -> handleSend(ctx, input), VIRTUAL_EXECUTOR);
			case "call" -> CompletableFuture.supplyAsync(() -> handleCall(ctx, input), VIRTUAL_EXECUTOR);
			case "create" -> CompletableFuture.supplyAsync(() -> handleCreate(ctx, input), VIRTUAL_EXECUTOR);
			case "delete" -> CompletableFuture.supplyAsync(() -> handleDelete(ctx, input), VIRTUAL_EXECUTOR);
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

	@Override
	protected void deleteRuntimeState(RequestContext ctx, String name) {
		state().delete(userStatePath(ctx.getUserDID(), "resolved/" + name));
		deleteLegacyPath(ctx, LEGACY_REGISTRY_PATH + "/" + name);
		deleteLegacyPath(ctx, BotRunner.legacySessionsPath(name));
	}

	void deleteLegacyPath(RequestContext ctx, String path) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/delete",
				Maps.of(Fields.PATH, Strings.create(path)), ctx).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Telegram: could not delete {}: {}", path, BotRunner.concise(e));
		}
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * A result as reply text: a string as-is; a map's first string-valued
	 * {@code text}/{@code response}/{@code content}/{@code message}/{@code result};
	 * anything else as pretty JSON.
	 */
	static String renderText(ACell value) { return ConversationRouter.renderText(value); }
}
