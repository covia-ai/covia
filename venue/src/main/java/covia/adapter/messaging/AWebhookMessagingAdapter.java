package covia.adapter.messaging;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.adapter.webhook.WebhookHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.RequestContext;

/** Common operator/runtime bindings and durable workers for signed HTTP providers. */
public abstract class AWebhookMessagingAdapter<S extends WebhookBotSpec>
		extends AMessagingAdapter<S, WebhookBot<S>> implements WebhookHandler {
	public static final String UNAVAILABLE = "Webhook binding is unavailable";
	public static final String UNAUTHORIZED = "Webhook authentication failed";
	public static final String INVALID_REQUEST = "Invalid webhook request";
	public static final String ACCEPT_FAILED = "Webhook could not be durably accepted";
	public static final String UNSUPPORTED = "Unsupported messaging operation: %s";
	public static final String DESTINATION_DENIED = "Messaging destination is not allowed by this binding";
	public static final String TEXT_LENGTH = "text must contain between 1 and %d characters";
	private volatile String apiUrl;
	protected abstract String defaultApiUrl();
	protected abstract WebhookResponse receive(WebhookBot<S> bot, WebhookRequest request);
	protected abstract ACell send(WebhookBot<S> bot, AMap<AString, ACell> input);
	protected abstract void sendReply(WebhookBot<S> bot, AMap<AString, ACell> event, String text);

	@Override public final synchronized boolean configure(AMap<AString, ACell> settings, boolean strict) {
		if (settings == null) settings = Maps.empty();
		if (settings.containsKey(MessagingSettings.key("statePath"))) throw new IllegalArgumentException(MessagingSettings.UNKNOWN.formatted("statePath"));
		if (strict) MessagingSettings.known(settings, Set.of("bots", "apiUrl", "enabled"));
		String endpoint = MessagingSettings.optional(settings, "apiUrl");
		endpoint = MessagingHttp.endpoint(endpoint == null ? defaultApiUrl() : endpoint);
		var parsed = new LinkedHashMap<String, S>();
		ACell bots = settings.get(K_BOTS);
		if (bots != null) for (var entry : MessagingSettings.object(bots).entrySet()) {
			if (!(entry.getKey() instanceof AString name)) throw new IllegalArgumentException(MessagingSettings.INVALID_NAME);
			parsed.put(name.toString(), parseBot(name.toString(), entry.getValue(), strict));
		}
		apiUrl = endpoint;
		configureBots(parsed);
		return true;
	}
	public final String apiUrl() { return apiUrl == null ? defaultApiUrl() : apiUrl; }
	@Override public final AMap<AString, ACell> publicConfig() { return Maps.empty(); }
	@Override protected final WebhookBot<S> newBot(S spec, Managed managed) { return new WebhookBot<>(this, spec, managed); }
	@Override protected final boolean configurationMatches(WebhookBot<S> runner) { return true; }
	@Override protected final void installAssets() {
		for (String op : new String[] { "send", "create", "delete", "bots" }) {
			installAsset(getName() + "/" + op, "/adapters/" + getName() + "/" + op + ".json");
		}
		installSkill("adapters/" + getName(), "/adapters/" + getName() + "/skill.json");
	}
	@Override protected final void deleteRuntimeState(RequestContext ctx, String name) {
		state().delete(userStatePath(ctx.getUserDID(), "inbox/" + name));
	}
	@Override public final CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String op = getSubOperation(meta);
		return CompletableFuture.supplyAsync(() -> switch (op == null ? "" : op) {
			case "create" -> handleCreate(ctx, input);
			case "delete" -> handleDelete(ctx, input);
			case "bots" -> handleBots(ctx);
			case "send" -> {
				var in = MessagingSettings.object(input);
				WebhookBot<S> bot = selectBot(ctx, RT.ensureString(in.get(MessagingSettings.key("bot"))));
				requireBotAccess(ctx, bot, MessagingSettings.key(getName() + "/send"));
				bot.requireReady();
				yield send(bot, in);
			}
			default -> throw new UnsupportedOperationException(UNSUPPORTED.formatted(op));
		}, VIRTUAL_EXECUTOR);
	}
	@Override public final WebhookResponse handleWebhook(String binding, WebhookRequest request) {
		if (!isActive()) return WebhookResponse.text(404, UNAVAILABLE);
		WebhookBot<S> bot = null;
		for (var candidate : runnerList()) if (candidate.binding().equals(binding)) { bot = candidate; break; }
		if (bot == null || !bot.running()) return WebhookResponse.text(404, UNAVAILABLE);
		try { return receive(bot, request); }
		catch (IllegalArgumentException e) { return WebhookResponse.text(400, INVALID_REQUEST); }
		catch (RuntimeException e) { return WebhookResponse.text(503, ACCEPT_FAILED); }
	}
	protected static AMap<AString, ACell> json(WebhookRequest request) {
		try { return MessagingSettings.object(JSON.parse(new String(request.body(), StandardCharsets.UTF_8))); }
		catch (RuntimeException e) { throw new IllegalArgumentException(INVALID_REQUEST); }
	}
	protected static String text(AMap<AString, ACell> input, int max) {
		String text = MessagingSettings.required(input, "text");
		if (text.codePointCount(0, text.length()) > max) throw new IllegalArgumentException(TEXT_LENGTH.formatted(max));
		return text;
	}
	/** Reply truncation is explicit in the text; one send avoids ambiguous partial retries. */
	protected static String truncate(String text, int max) {
		return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
	}
}
