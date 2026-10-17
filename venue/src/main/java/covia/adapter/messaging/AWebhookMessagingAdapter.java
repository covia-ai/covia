package covia.adapter.messaging;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.adapter.relay.MessageRelay;
import covia.adapter.relay.RelayHandler;
import covia.adapter.webhook.WebhookHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.RequestContext;

/**
 * Common operator/runtime bindings and durable workers for signed HTTP providers.
 * A binding's handler is an agent, an operation, or a {@code relay}
 * ({@link RelayBinding}), which principals reach at {@code /relay/<adapter>/<binding>};
 * a provider supports the relay with no code beyond the hooks below.
 */
public abstract class AWebhookMessagingAdapter<S extends WebhookBotSpec>
		extends AMessagingAdapter<S, WebhookBot<S>> implements WebhookHandler, RelayHandler {
	public static final String UNAVAILABLE = "Webhook binding is unavailable";
	public static final String UNAUTHORIZED = "Webhook authentication failed";
	public static final String INVALID_REQUEST = "Invalid webhook request";
	public static final String ACCEPT_FAILED = "Webhook could not be durably accepted";
	public static final String UNSUPPORTED = "Unsupported messaging operation: %s";
	public static final String DESTINATION_DENIED = "Messaging destination is not allowed by this binding";
	public static final String TEXT_LENGTH = "text must contain between 1 and %d characters";
	public static final String NO_CONNECTION = "This provider does not support connection ingress";
	private volatile String apiUrl;
	protected abstract String defaultApiUrl();
	protected abstract WebhookResponse receive(WebhookBot<S> bot, WebhookRequest request);
	protected abstract ACell send(WebhookBot<S> bot, AMap<AString, ACell> input);
	protected abstract void sendReply(WebhookBot<S> bot, AMap<AString, ACell> event, String text);
	/**
	 * Open the event connection of a binding whose spec has connection ingress.
	 * Called off the retry thread once credentials resolve; may block while
	 * connecting. {@link WebhookBot} replaces it when it ends and closes it on stop.
	 */
	protected MessagingConnection openConnection(WebhookBot<S> bot) throws Exception {
		throw new UnsupportedOperationException(NO_CONNECTION);
	}
	/**
	 * Send a later part of a reply already begun with {@link #sendReply}. By default
	 * another reply to the event; a provider whose reply quotes the message may send
	 * the later parts plainly.
	 */
	protected void sendFollowUp(WebhookBot<S> bot, AMap<AString, ACell> event, String text) {
		sendReply(bot, event, text);
	}
	/** The longest text one send accepts, in UTF-16 units; a relay splits longer replies. */
	protected int maxText() { return 4000; }
	/** How long a reply may follow its message under the provider's rules. */
	protected long relayReplyWindowMs() { return MessageRelay.DEFAULT_REPLY_WINDOW_MS; }
	/** How a relay binding is addressed by people, such as a phone number; given to its principals. */
	protected AMap<AString, ACell> relayContact(WebhookBot<S> bot) { return Maps.empty(); }
	/** A link that opens a conversation with the binding ready to send {@code LINK <code>}, or null. */
	protected String relayLink(WebhookBot<S> bot, String code) { return null; }
	/**
	 * Fetch one attachment named by an item of an event's {@code media}, with the
	 * binding's credentials, at most {@link MessagingMedia#MAX_BYTES}. A provider that
	 * puts {@code media} on its events implements this; nothing is stored here.
	 * Validating the item's {@code id} is the provider's.
	 */
	protected MessagingMedia.Fetched fetchMedia(WebhookBot<S> bot, AMap<AString, ACell> item) {
		throw new UnsupportedOperationException(MessagingMedia.NOT_FETCHED);
	}

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
	/**
	 * Publishes the four operations and the provider skill. A provider that
	 * implements {@link #fetchMedia} may also ship {@code /adapters/<name>/download.json},
	 * published as {@code <name>:download}, which this class runs; without the file
	 * the operation does not exist. A provider may also
	 * ship {@code /adapters/<name>/bot-management-skill.json}, published as the
	 * skill {@code adapters/<name>-bot-management} for its main skill to reveal,
	 * and {@code /agent-templates/<name>.json}, published as the agent template
	 * {@code v/agents/templates/<name>}.
	 */
	@Override protected final void installAssets() {
		for (String op : new String[] { "send", "create", "delete", "bots" }) {
			installAsset(getName() + "/" + op, "/adapters/" + getName() + "/" + op + ".json");
		}
		if (offersDownload()) installAsset(getName() + "/download", downloadResource());
		installSkill("adapters/" + getName(), "/adapters/" + getName() + "/skill.json");
		String management = "/adapters/" + getName() + "/bot-management-skill.json";
		if (getClass().getResource(management) != null) installSkill("adapters/" + getName() + "-bot-management", management);
		String template = "/agent-templates/" + getName() + ".json";
		if (getClass().getResource(template) != null) installAgentTemplate(getName(), template);
	}
	@Override protected final void deleteRuntimeState(RequestContext ctx, String name) {
		state().delete(userStatePath(ctx.getUserDID(), "inbox/" + name));
		state().delete(userStatePath(ctx.getUserDID(), "relay/" + name));
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
			case "download" -> {
				if (!offersDownload()) throw new UnsupportedOperationException(UNSUPPORTED.formatted(op));
				yield handleDownload(ctx, MessagingSettings.object(input));
			}
			default -> throw new UnsupportedOperationException(UNSUPPORTED.formatted(op));
		}, VIRTUAL_EXECUTOR);
	}
	private String downloadResource() { return "/adapters/" + getName() + "/download.json"; }
	private boolean offersDownload() { return getClass().getResource(downloadResource()) != null; }
	/**
	 * {@code <name>:download} {@code {bot?, media_id, destination?, fileName?}}: one
	 * attachment by the provider's media id, gated on {@code <owner>/<name>/<bot>} ×
	 * {@code <name>/download}. An explicit {@code destination} is written with the
	 * caller's authority; by default the file goes to the binding owner's private drive
	 * named for the provider, as an agent binding's attachments do. Without
	 * {@code fileName} the name comes from the binding and the media id.
	 *
	 * @return {@code {ref, fileName, size, media_id, contentType?}}
	 */
	private ACell handleDownload(RequestContext ctx, AMap<AString, ACell> in) {
		MessagingSettings.known(in, Set.of("bot", "media_id", "destination", "fileName"));
		WebhookBot<S> bot = selectBot(ctx, RT.ensureString(in.get(MessagingSettings.key("bot"))));
		requireBotAccess(ctx, bot, MessagingSettings.key(getName() + "/download"));
		String id = MessagingSettings.required(in, "media_id");
		ACell destination = in.get(MessagingSettings.key("destination"));
		if (destination != null && (!(destination instanceof AString d) || d.isBlank())) throw new IllegalArgumentException(MessagingMedia.INVALID_DESTINATION);
		ACell fileName = in.get(MessagingSettings.key("fileName"));
		if (fileName != null && !(fileName instanceof AString f && MessagingMedia.safeFileName(f.toString()))) {
			throw new IllegalArgumentException(MessagingMedia.INVALID_FILE_NAME);
		}
		MessagingMedia.Fetched fetched = fetchMedia(bot, Maps.of("id", id));
		AString owner = bot.spec().userDID(engine);
		String directory = destination == null ? MessagingMedia.ownerDirectory(owner, getName()) : destination.toString();
		String name = fileName == null ? MessagingMedia.fallbackName(bot.spec().name(), id, fetched.contentType()) : fileName.toString();
		try {
			return MessagingMedia.store(engine, fetched.bytes(), fetched.contentType(), directory, name,
				destination == null ? RequestContext.of(owner) : ctx).assoc(MessagingSettings.key("media_id"), Strings.create(id));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(MessagingMedia.UNSUPPORTED_DESTINATION + ": " + directory);
		}
	}
	@Override public final WebhookResponse handleWebhook(String binding, WebhookRequest request) {
		WebhookBot<S> bot = runningBinding(binding);
		if (bot == null || !bot.spec().webhookIngress()) return WebhookResponse.text(404, UNAVAILABLE);
		try { return receive(bot, request); }
		catch (IllegalArgumentException e) { return WebhookResponse.text(400, INVALID_REQUEST); }
		catch (RuntimeException e) { return WebhookResponse.text(503, ACCEPT_FAILED); }
	}
	/** A principal's relay action, for a running binding whose handler is {@code relay}. */
	@Override public final CompletableFuture<WebhookResponse> handleRelay(String binding, String action, AString caller, WebhookRequest request) {
		WebhookBot<S> bot = runningBinding(binding);
		RelayBinding<S> relay = bot == null ? null : bot.relay();
		if (relay == null) return CompletableFuture.completedFuture(RelayBinding.error(404, RelayBinding.E_UNKNOWN_RELAY));
		return relay.handle(action, caller, request);
	}
	private WebhookBot<S> runningBinding(String binding) {
		if (!isActive()) return null;
		for (var candidate : runnerList()) if (candidate.binding().equals(binding)) return candidate.running() ? candidate : null;
		return null;
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
