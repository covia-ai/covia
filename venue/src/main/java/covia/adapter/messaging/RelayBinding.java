package covia.adapter.messaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import covia.adapter.relay.MessageRelay;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.RequestContext;
import covia.venue.server.RateLimiter;

/**
 * The {@code relay} handler of a webhook messaging binding: one provider binding (a
 * WhatsApp number, a Slack app) serving many downstream principals, each known by its
 * DID. The provider's adapter authenticates, parses and sends exactly as for its other
 * bindings; this class is everything else, the same for every provider.
 *
 * <p>Senders arrive as the provider's normalised events ({@code text}, and
 * {@code via.from} naming the sender). {@code LINK <code>} links a sender to the
 * principal that asked for the code; {@code STOP} unlinks; texts from a linked sender
 * wait in memory ({@link MessageRelay}) for that principal's poll. A principal answers
 * one message once, through the provider's own {@code sendReply}, so the provider's
 * threading and reply window apply. Nothing but links is written down, and no hook
 * leaves a Job record.
 *
 * <p>Principals call {@code /relay/<adapter>/<binding>/<action>}; the actions and
 * error codes are the constants below.
 */
public final class RelayBinding<S extends WebhookBotSpec> {
	private static final Logger log = LoggerFactory.getLogger(RelayBinding.class);

	public static final String STATUS = "status", LINK = "link", UNLINK = "unlink", POLL = "poll", REPLY = "reply", MEDIA = "media";

	public static final String E_UNKNOWN_RELAY = "unknown_relay";
	public static final String E_UNAVAILABLE = "relay_unavailable";
	public static final String E_UNKNOWN_ACTION = "unknown_action";
	public static final String E_POST_REQUIRED = "post_required";
	public static final String E_RATE_LIMITED = "rate_limited";
	public static final String E_INVALID_REQUEST = "invalid_request";
	public static final String E_UNKNOWN_MESSAGE = "unknown_message";
	public static final String E_TOO_LONG = "too_long";
	public static final String E_ALREADY_REPLIED = "already_replied";
	public static final String E_SEND_FAILED = "send_failed";
	public static final String E_NOT_ADMITTED = "not_admitted";
	public static final String E_ADMISSION_UNAVAILABLE = "admission_unavailable";
	public static final String E_NO_MEDIA = "no_media";
	public static final String E_MEDIA_TOO_LARGE = "media_too_large";
	public static final String E_MEDIA_UNAVAILABLE = "media_unavailable";

	public static final String N_LINKED = "linked", N_UNLINKED = "unlinked", N_NOT_LINKED = "notLinked",
		N_CODE_UNKNOWN = "codeUnknown", N_NOT_OPEN = "notOpen", N_HELD = "held", N_TEXT_ONLY = "textOnly",
		N_NOT_AVAILABLE = "notAvailable";

	/** The longest a principal's poll is held open. */
	public static final long MAX_WAIT_MS = 20_000L;
	/** Whether the binding's credentials resolve, rechecked at most this often: polls ask every few seconds. */
	static final long READY_CACHE_MS = 30_000L;
	static final long HOOK_TIMEOUT_MS = 15_000L;
	private static final Pattern LINK_COMMAND = Pattern.compile("(?i)^link\\s+([a-z0-9-]{4,20})$");
	private static final String STOP_COMMAND = "stop";
	/** The parts of a provider event's {@code via} a principal sees. */
	private static final String[] VISIBLE_VIA = { "channel", "from", "chat", "thread" };
	/** The parts of an attachment's description a principal sees; its bytes come from {@link #MEDIA}. */
	private static final String[] VISIBLE_MEDIA = { "kind", "mediaType", "fileName", "voice" };

	private static final AString K_VIA = Strings.intern("via"), K_FROM = Strings.intern("from"),
		K_TEXT = Strings.intern("text"), K_TIMESTAMP = Strings.intern("timestamp"), K_ALLOWED = Strings.intern("allowed");

	private final WebhookBot<S> bot;
	private final AWebhookMessagingAdapter<S> adapter;
	private final RelaySettings settings;
	private final MessageRelay relay;
	// Per-binding limits. Sender keys come only from authenticated provider events.
	private final RateLimiter linkLimit = new RateLimiter(10, 10 / 60.0);
	private final RateLimiter pollLimit = new RateLimiter(60, 1);
	private final RateLimiter replyLimit = new RateLimiter(30, 0.5);
	private final RateLimiter mediaLimit = new RateLimiter(30, 0.5);
	private final RateLimiter codeLimit = new RateLimiter(5, 5 / 3600.0);
	private final RateLimiter notLinkedLimit = new RateLimiter(1, 1 / 600.0);
	private final RateLimiter textOnlyLimit = new RateLimiter(1, 1 / 60.0);
	private volatile long readyCheckedAt = Long.MIN_VALUE / 2;
	private volatile boolean readyCached;

	RelayBinding(WebhookBot<S> bot, AWebhookMessagingAdapter<S> adapter, String root) {
		this.bot = bot;
		this.adapter = adapter;
		this.settings = RelaySettings.parse(bot.spec().relay(), false);
		this.relay = new MessageRelay(bot.state(), root, settings.holdSeconds(), adapter.relayReplyWindowMs(),
			System::currentTimeMillis);
	}

	public RelaySettings settings() { return settings; }

	/** Release every waiting poll; the binding is stopping. Links stay. */
	void close() { relay.close(); }

	// ---- the provider's side ----

	/** An authenticated provider event; handled off the caller's thread, once per id. */
	void accept(String id, AMap<AString, ACell> event) {
		if (!relay.firstSighting(id)) return;
		AAdapter.VIRTUAL_EXECUTOR.execute(() -> {
			try {
				onEvent(id, event);
			} catch (RuntimeException e) {
				log.warn("{} relay '{}': a message could not be handled ({})", adapter.getName(), bot.spec().name(),
					e.getClass().getSimpleName());
			}
		});
	}

	private void onEvent(String id, AMap<AString, ACell> event) {
		String sender = str(RT.ensureMap(event.get(K_VIA)), K_FROM);
		if (sender == null) return;
		String text = str(event, K_TEXT);
		text = text == null ? null : text.strip();
		Matcher link = text == null ? null : LINK_COMMAND.matcher(text);
		if (link != null && link.matches()) {
			if (!codeLimit.tryAcquire(sender)) return;
			AString principal = relay.redeem(link.group(1));
			if (principal == null) { notice(event, N_CODE_UNKNOWN); return; }
			Admission a = admit(principal);
			if (!a.allowed()) { notice(event, a.notice()); return; }
			relay.link(sender, principal);
			log.info("{} relay '{}': a sender was linked", adapter.getName(), bot.spec().name());
			notice(event, N_LINKED);
			return;
		}
		if (STOP_COMMAND.equalsIgnoreCase(text)) {
			notice(event, relay.unlinkSender(sender) != null ? N_UNLINKED : N_NOT_LINKED);
			return;
		}
		AString principal = relay.principalFor(sender);
		if (principal == null) {
			if (notLinkedLimit.tryAcquire(sender)) notice(event, N_NOT_LINKED);
			return;
		}
		AVector<ACell> media = visibleMedia(event);
		if ((text == null || text.isEmpty()) && media.isEmpty()) {
			if (textOnlyLimit.tryAcquire(sender)) notice(event, N_TEXT_ONLY);
			return;
		}
		Admission a = admit(principal);
		if (!a.allowed()) { notice(event, a.notice()); return; }
		MessageRelay.Inbound inbound = new MessageRelay.Inbound(id, text == null ? "" : text, timestamp(event), via(event), media);
		switch (relay.offer(principal, sender, inbound, event)) {
			case DELIVERED -> { }
			case HELD -> notice(event, N_HELD);
			case NOT_OPEN -> notice(event, N_NOT_OPEN);
		}
	}

	/** One of the hub's own replies; a name selects a configured notice, anything else is the text. */
	private void notice(AMap<AString, ACell> event, String nameOrText) {
		String text = settings.notice(nameOrText);
		try {
			adapter.sendReply(bot, event, text != null ? text : nameOrText);
		} catch (RuntimeException e) {
			log.warn("{} relay '{}': a notice could not be sent ({})", adapter.getName(), bot.spec().name(), e.getMessage());
		}
	}

	private long timestamp(AMap<AString, ACell> event) {
		String ts = str(event, K_TIMESTAMP);
		if (ts != null && ts.matches("[0-9]{1,12}")) return Long.parseLong(ts);
		return System.currentTimeMillis() / 1000;
	}

	/** An event's attachments as a principal sees them: no provider ids, which grant nothing to it. */
	private static AVector<ACell> visibleMedia(AMap<AString, ACell> event) {
		AVector<ACell> media = RT.ensureVector(event.get(MessagingMedia.K_MEDIA));
		AVector<ACell> out = Vectors.empty();
		if (media == null) return out;
		for (ACell cell : media) {
			AMap<AString, ACell> item = RT.ensureMap(cell);
			if (item == null) continue;
			AMap<AString, ACell> visible = Maps.empty();
			for (String key : VISIBLE_MEDIA) {
				ACell v = item.get(MessagingSettings.key(key));
				if (v != null) visible = visible.assoc(MessagingSettings.key(key), v);
			}
			out = out.conj(visible);
		}
		return out;
	}

	private static AMap<AString, ACell> via(AMap<AString, ACell> event) {
		AMap<AString, ACell> via = RT.ensureMap(event.get(K_VIA));
		AMap<AString, ACell> out = Maps.empty();
		if (via == null) return out;
		for (String key : VISIBLE_VIA) {
			ACell v = via.get(MessagingSettings.key(key));
			if (v != null) out = out.assoc(MessagingSettings.key(key), v);
		}
		return out;
	}

	// ---- the principals' side ----

	/** One relay action from an authenticated principal. */
	public CompletableFuture<WebhookResponse> handle(String action, AString caller, WebhookRequest request) {
		if (!ready()) return done(error(503, E_UNAVAILABLE));
		return switch (action == null ? "" : action) {
			case STATUS -> done(json(200, contact().assoc(MessagingSettings.key("enabled"), CVMBool.TRUE)
				.assoc(MessagingSettings.key("linked"), strOrNull(relay.senderFor(caller)))));
			case LINK -> link(caller);
			case UNLINK -> done(post(request)
				? json(200, Maps.of("unlinked", CVMBool.create(relay.unlinkPrincipal(caller) != null)))
				: error(405, E_POST_REQUIRED));
			case POLL -> poll(caller, request);
			case REPLY -> reply(caller, request);
			case MEDIA -> media(caller, request);
			default -> done(error(404, E_UNKNOWN_ACTION));
		};
	}

	private CompletableFuture<WebhookResponse> link(AString caller) {
		if (!linkLimit.tryAcquire(caller.toString())) return done(error(429, E_RATE_LIMITED));
		// The admission hook may be slow: never on the request thread.
		return CompletableFuture.supplyAsync(() -> {
			Admission a = admit(caller);
			if (!a.allowed()) return error(403, a.code());
			String code = relay.newCode(caller);
			AMap<AString, ACell> out = contact().assoc(MessagingSettings.key("code"), Strings.create(code))
				.assoc(MessagingSettings.key("expires_in"), CVMLong.create(MessageRelay.CODE_TTL_MS / 1000));
			String url = adapter.relayLink(bot, code);
			if (url != null) out = out.assoc(MessagingSettings.key("link"), Strings.create(url));
			return json(200, out);
		}, AAdapter.VIRTUAL_EXECUTOR);
	}

	private CompletableFuture<WebhookResponse> poll(AString caller, WebhookRequest request) {
		if (!pollLimit.tryAcquire(caller.toString())) return done(error(429, E_RATE_LIMITED));
		AMap<AString, ACell> body = body(request);
		CVMLong asked = body == null ? null : RT.ensureLong(body.get(MessagingSettings.key("wait_ms")));
		long wait = asked == null ? MAX_WAIT_MS : Math.max(0, Math.min(MAX_WAIT_MS, asked.longValue()));
		// Admission first, off the request thread; then the poll's own future holds the response.
		return CompletableFuture.supplyAsync(() -> admit(caller), AAdapter.VIRTUAL_EXECUTOR).thenCompose(a -> {
			if (!a.allowed()) return done(error(403, a.code()));
			return relay.poll(caller, wait).thenApply(messages -> {
				List<ACell> out = new ArrayList<>();
				for (MessageRelay.Inbound m : messages) {
					AMap<AString, ACell> message = Maps.of("id", m.id(), "text", m.text(), "timestamp", CVMLong.create(m.timestampSec()), "via", m.via());
					out.add(m.media().isEmpty() ? message : message.assoc(MessagingMedia.K_MEDIA, m.media()));
				}
				return json(200, Maps.of("linked", strOrNull(relay.senderFor(caller)), "messages", Vectors.create(out)));
			});
		});
	}

	private CompletableFuture<WebhookResponse> reply(AString caller, WebhookRequest request) {
		if (!post(request)) return done(error(405, E_POST_REQUIRED));
		AMap<AString, ACell> body = body(request);
		String messageId = body == null ? null : str(body, MessagingSettings.key("message_id"));
		String text = body == null ? null : str(body, K_TEXT);
		if (messageId == null || text == null || text.isBlank()) return done(error(400, E_INVALID_REQUEST));
		MessageRelay.Target target = relay.target(messageId);
		if (target == null || !target.principal().equals(caller)) return done(error(404, E_UNKNOWN_MESSAGE));
		if (!replyLimit.tryAcquire(caller.toString())) return done(error(429, E_RATE_LIMITED));
		List<String> parts = MessagingText.split(text.strip(), adapter.maxText());
		if (parts.size() > settings.maxParts()) return done(error(400, E_TOO_LONG));
		if (!relay.claimReply(messageId)) return done(error(409, E_ALREADY_REPLIED));
		AMap<AString, ACell> event = RT.ensureMap(target.address());
		// The sends are blocking provider calls: off the server thread.
		return CompletableFuture.supplyAsync(() -> {
			int sent = 0;
			try {
				for (String part : parts) {
					if (sent == 0) adapter.sendReply(bot, event, part);
					else adapter.sendFollowUp(bot, event, part);
					sent++;
				}
			} catch (RuntimeException e) {
				log.warn("{} relay '{}': reply part {} of {} failed ({})", adapter.getName(), bot.spec().name(),
					sent + 1, parts.size(), e.getMessage());
			}
			if (sent == 0) {
				relay.releaseReply(messageId);
				return error(502, E_SEND_FAILED);
			}
			charge(caller, messageId, sent);
			return json(sent == parts.size() ? 200 : 502, Maps.of("sent", CVMLong.create(sent), "parts", CVMLong.create(parts.size())));
		}, AAdapter.VIRTUAL_EXECUTOR);
	}

	/**
	 * One attachment of a message delivered to the caller, fetched from the provider on
	 * this request and passed straight through: the hub keeps no copy. The message is
	 * named by {@code message_id}, the attachment by an optional {@code index} (default 0),
	 * in the query or a JSON body. Fetching may be repeated while the reply window is open.
	 */
	private CompletableFuture<WebhookResponse> media(AString caller, WebhookRequest request) {
		AMap<AString, ACell> body = body(request);
		String messageId = request.queryParam("message_id");
		if (messageId == null && body != null) messageId = str(body, MessagingSettings.key("message_id"));
		String indexParam = request.queryParam("index");
		CVMLong indexCell = body == null ? null : RT.ensureLong(body.get(MessagingSettings.key("index")));
		int index;
		try { index = indexParam != null ? Integer.parseInt(indexParam) : indexCell != null ? (int) indexCell.longValue() : 0; }
		catch (NumberFormatException e) { index = -1; }
		if (messageId == null || index < 0) return done(error(400, E_INVALID_REQUEST));
		MessageRelay.Target target = relay.target(messageId);
		if (target == null || !target.principal().equals(caller)) return done(error(404, E_UNKNOWN_MESSAGE));
		AVector<ACell> media = RT.ensureVector(RT.getIn(target.address(), MessagingMedia.K_MEDIA));
		if (media == null || index >= media.count()) return done(error(404, E_NO_MEDIA));
		if (!mediaLimit.tryAcquire(caller.toString())) return done(error(429, E_RATE_LIMITED));
		AMap<AString, ACell> item = MessagingSettings.object(media.get(index));
		// The fetch is a blocking provider call: off the server thread.
		return CompletableFuture.supplyAsync(() -> {
			try {
				MessagingMedia.Fetched fetched = adapter.fetchMedia(bot, item);
				String type = str(item, MessagingMedia.K_MEDIA_TYPE);
				if (type == null) type = fetched.contentType() != null ? fetched.contentType() : "application/octet-stream";
				return new WebhookResponse(200, type, Map.of("Cache-Control", "no-store"), fetched.bytes());
			} catch (RuntimeException e) {
				log.warn("{} relay '{}': an attachment could not be fetched ({})", adapter.getName(), bot.spec().name(), e.getMessage());
				return MessagingMedia.TOO_LARGE.equals(e.getMessage()) ? error(413, E_MEDIA_TOO_LARGE) : error(502, E_MEDIA_UNAVAILABLE);
			}
		}, AAdapter.VIRTUAL_EXECUTOR);
	}

	/** The provider's address for principals, such as a phone number; may be empty. */
	private AMap<AString, ACell> contact() {
		AMap<AString, ACell> contact = adapter.relayContact(bot);
		return contact == null ? Maps.empty() : contact;
	}

	private boolean ready() {
		long now = System.currentTimeMillis();
		if (now - readyCheckedAt < READY_CACHE_MS) return readyCached && bot.running();
		boolean ok = bot.ready();
		readyCached = ok;
		readyCheckedAt = now;
		return ok;
	}

	// ---- hooks ----

	/** A hook's answer; {@code text} is the notice it asks the sender be sent. */
	private record Admission(boolean allowed, String code, String text) {
		static final Admission YES = new Admission(true, null, null);
		String notice() { return text != null ? text : N_NOT_AVAILABLE; }
	}

	private RequestContext hookContext() {
		return RequestContext.of(bot.spec().userDID(adapter.engine));
	}

	private Admission admit(AString principal) {
		if (settings.admit() == null) return Admission.YES;
		try {
			ACell result = adapter.engine.jobs().invokeInternal(settings.admit(), Maps.of("principal", principal), hookContext())
				.get(HOOK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			AMap<AString, ACell> map = RT.ensureMap(result);
			if (map != null && map.get(K_ALLOWED) instanceof CVMBool b && b.booleanValue()) return Admission.YES;
			String code = map == null ? null : str(map, MessagingSettings.key("code"));
			return new Admission(false, code != null ? code : E_NOT_ADMITTED, map == null ? null : str(map, MessagingSettings.key("notice")));
		} catch (Exception e) {
			log.warn("{} relay '{}': admission hook failed ({})", adapter.getName(), bot.spec().name(), e.getClass().getSimpleName());
			return new Admission(false, E_ADMISSION_UNAVAILABLE, null);
		}
	}

	private void charge(AString principal, String messageId, int parts) {
		if (settings.charge() == null) return;
		adapter.engine.jobs().invokeInternal(settings.charge(), Maps.of("principal", principal, "parts", CVMLong.create(parts),
			"message_id", messageId), hookContext()).whenComplete((r, e) -> {
				if (e != null) log.warn("{} relay '{}': charge hook failed ({})", adapter.getName(), bot.spec().name(), e.getClass().getSimpleName());
			});
	}

	// ---- plumbing ----

	private static boolean post(WebhookRequest request) { return "POST".equals(request.method()); }

	private static AMap<AString, ACell> body(WebhookRequest request) {
		byte[] bytes = request.body();
		if (bytes.length == 0) return Maps.empty();
		try { return RT.ensureMap(JSON.parse(new String(bytes, StandardCharsets.UTF_8))); }
		catch (RuntimeException e) { return null; }
	}

	private static String str(AMap<AString, ACell> map, AString key) {
		if (map == null) return null;
		AString s = RT.ensureString(map.get(key));
		return s == null ? null : s.toString();
	}

	private static ACell strOrNull(String s) { return s == null ? null : Strings.create(s); }

	private static CompletableFuture<WebhookResponse> done(WebhookResponse r) { return CompletableFuture.completedFuture(r); }

	static WebhookResponse json(int status, AMap<AString, ACell> body) {
		return new WebhookResponse(status, "application/json", Map.of(), JSON.print(body).toString().getBytes(StandardCharsets.UTF_8));
	}

	static WebhookResponse error(int status, String code) {
		return json(status, Maps.of("error", code));
	}
}
