package covia.adapter.whatsapp;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import covia.adapter.messaging.MessagingHttp;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.relay.MessageRelay;
import covia.adapter.relay.RelayHandler;
import covia.adapter.webhook.WebhookHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.adapter.webhook.WebhookSignatures;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * A hub for one WhatsApp Cloud API number serving many downstream principals: the
 * venues or clients the hub relays to, each identified by its DID. WhatsApp delivers
 * only to a public webhook, so a principal without one (an app on someone's laptop)
 * reaches WhatsApp through the hub instead of a number of its own.
 *
 * <p>A principal asks for a linking code ({@code POST /relay/whatsapp-relay/c-<relay>/link});
 * the person sends {@code LINK <code>} from their phone, and the hub links that number
 * to the principal. Each later text from the number waits in memory for the principal's
 * long poll ({@code .../poll}); the principal answers it once ({@code .../reply}, by
 * message id, inside WhatsApp's 24 hours). STOP from the phone, or {@code .../unlink},
 * removes the link. Messages are never written to the venue: only links are
 * ({@link MessageRelay}).
 *
 * <p>Two optional hooks let the hub's operator decide and account, both invoked without
 * a Job record, as the relay's {@code user}: {@code admit} ({@code {principal}} →
 * {@code {allowed, code?, notice?}}) before linking, polling and delivery; and
 * {@code charge} ({@code {principal, parts, message_id}}) after a reply is sent.
 *
 * <p>Configuration, under {@code adapters.whatsapp-relay}: {@code apiUrl} (tests) and
 * {@code relays.<name>} with {@code user}, the {@code s/NAME} references {@code token},
 * {@code appSecret} and {@code verifyToken}, {@code businessAccountId},
 * {@code phoneNumberId}, {@code apiVersion}, {@code number}, and optionally
 * {@code holdSeconds}, {@code admit}, {@code charge} and {@code notices}.
 */
public final class WhatsAppRelayAdapter extends AAdapter implements WebhookHandler, RelayHandler, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppRelayAdapter.class);

	public static final String NAME = "whatsapp-relay";
	/** The longest a principal's poll is held open. */
	public static final long MAX_WAIT_MS = 20_000L;
	/** WhatsApp's limit on one text message, in code points. */
	public static final int MAX_TEXT = 4096;
	static final String UNKNOWN_RELAY = "Unknown relay";
	private static final Pattern LINK = Pattern.compile("(?i)^link\\s+([a-z0-9-]{4,20})$");
	private static final AString K_ALLOWED = Strings.intern("allowed");

	private final Clock clock;
	private volatile String apiUrl = "https://graph.facebook.com";
	private volatile Map<String, RelaySpec> specs = Map.of();
	private final Map<String, MessageRelay> cores = new ConcurrentHashMap<>();
	private final Windows limits = new Windows();
	/** Whether each relay's credentials resolve, rechecked at most this often: polls ask every few seconds. */
	private static final long READY_CACHE_MS = 30_000L;
	private final Map<String, long[]> readiness = new ConcurrentHashMap<>();

	public WhatsAppRelayAdapter() { this(Clock.systemUTC()); }

	WhatsAppRelayAdapter(Clock clock) { this.clock = Objects.requireNonNull(clock); }

	@Override public String getName() { return NAME; }

	@Override public String getDescription() {
		return "Relays one WhatsApp Cloud API number to many principals: linking by code, messages held in memory for each principal's poll, one reply per message. Only links are stored.";
	}

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		AMap<AString, ACell> map = config == null ? Maps.empty() : config;
		if (strict) MessagingSettings.known(map.dissoc(MessagingSettings.key("enabled")), java.util.Set.of("apiUrl", "relays"));
		String url = MessagingSettings.optional(map, "apiUrl");
		String endpoint = url == null ? "https://graph.facebook.com" : MessagingHttp.endpoint(url);
		Map<String, RelaySpec> parsed = new LinkedHashMap<>();
		ACell relays = map.get(MessagingSettings.key("relays"));
		if (relays != null) {
			for (var e : MessagingSettings.object(relays).entrySet()) {
				String name = e.getKey().toString();
				parsed.put(name, RelaySpec.parse(name, e.getValue(), strict));
			}
		}
		// Validated whole; only now do live fields change. Waiting messages do not
		// survive a reconfiguration; links do, in the workspace.
		apiUrl = endpoint;
		specs = Map.copyOf(parsed);
		for (MessageRelay core : cores.values()) core.close();
		cores.clear();
		readiness.clear();
		return true;
	}

	@Override protected void installAssets() {
		installAsset(NAME + "/relays", "/adapters/" + NAME + "/relays.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		if (!"relays".equals(getSubOperation(meta))) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown operation"));
		}
		AString caller = ctx.getUserDID();
		boolean venue = engine != null && Objects.equals(caller, engine.getDIDString());
		List<ACell> out = new ArrayList<>();
		for (RelaySpec spec : specs.values()) {
			if (!venue && !spec.user().equals(caller)) continue;
			out.add(Maps.of("name", spec.name(), "user", spec.user(), "number", spec.number(),
				"webhookPath", "/webhooks/" + NAME + "/c-" + spec.name(),
				"relayPath", "/relay/" + NAME + "/c-" + spec.name(),
				"ready", CVMBool.create(ready(spec))));
		}
		return CompletableFuture.completedFuture(Maps.of("relays", Vectors.create(out)));
	}

	@Override public void close() {
		for (MessageRelay core : cores.values()) core.close();
	}

	// ---- the webhook: Meta's side ----

	@Override
	public WebhookResponse handleWebhook(String binding, WebhookRequest request) {
		RelaySpec spec = specFor(binding);
		if (spec == null) return WebhookResponse.text(404, UNKNOWN_RELAY);
		if ("GET".equals(request.method())) {
			String token = credential(spec, spec.verifyToken(), null);
			if (token == null || token.isBlank()) return WebhookResponse.text(503, MessagingHttp.UNAVAILABLE);
			if (!"subscribe".equals(request.queryParam("hub.mode"))
				|| !WebhookSignatures.equal(token, request.queryParam("hub.verify_token"))) return WebhookResponse.text(403, "Forbidden");
			String challenge = request.queryParam("hub.challenge");
			if (challenge == null || challenge.isBlank()) return WebhookResponse.text(400, "Invalid request");
			return WebhookResponse.text(200, challenge);
		}
		if (!"POST".equals(request.method())) return WebhookResponse.text(405, "Invalid request");
		String secret = credential(spec, spec.appSecret(), null);
		if (secret == null || secret.isBlank()) return WebhookResponse.text(503, MessagingHttp.UNAVAILABLE);
		if (!WebhookSignatures.verify(secret, request.header("X-Hub-Signature-256"), "sha256=", "", request.body())) {
			return WebhookResponse.text(401, "Unauthorised");
		}
		List<Incoming> incoming;
		try {
			incoming = parse(spec, JSON.parse(new String(request.body(), StandardCharsets.UTF_8)));
		} catch (RuntimeException e) {
			return WebhookResponse.text(400, "Invalid request");
		}
		MessageRelay core = core(spec);
		if (!incoming.isEmpty()) VIRTUAL_EXECUTOR.execute(() -> {
			for (Incoming m : incoming) {
				try {
					handle(spec, core, m);
				} catch (RuntimeException e) {
					log.warn("{} '{}': a message could not be handled ({})", NAME, spec.name(), e.getClass().getSimpleName());
				}
			}
		});
		return WebhookResponse.text(200, "");
	}

	private record Incoming(String id, String from, String type, String text, long timestampSec) {}

	private void handle(RelaySpec spec, MessageRelay core, Incoming m) {
		if (!core.firstSighting(m.id())) return;
		long now = Instant.now(clock).toEpochMilli();
		if (now - m.timestampSec() * 1000L >= MessageRelay.REPLY_WINDOW_MS) return;
		if (m.timestampSec() * 1000L > now + 300_000) return;
		String text = m.text() == null ? null : m.text().strip();
		Matcher link = text == null ? null : LINK.matcher(text);
		if (link != null && link.matches()) {
			if (!limits.allow("code:" + spec.name() + ":" + m.from(), 5, 60 * 60_000L)) return;
			AString principal = core.redeem(link.group(1));
			if (principal == null) { notice(spec, m, "codeUnknown", true); return; }
			Admission a = admit(spec, principal);
			if (!a.allowed()) { notice(spec, m, a.notice(spec), true); return; }
			core.link(m.from(), principal);
			log.info("{} '{}': a number was linked", NAME, spec.name());
			notice(spec, m, "linked", true);
			return;
		}
		if ("stop".equalsIgnoreCase(text)) {
			AString principal = core.unlinkSender(m.from());
			notice(spec, m, principal != null ? "unlinked" : "notLinked", true);
			return;
		}
		AString principal = core.principalFor(m.from());
		if (principal == null) {
			if (limits.allow("notice:" + spec.name() + ":" + m.from(), 1, 10 * 60_000L)) notice(spec, m, "notLinked", true);
			return;
		}
		if (!"text".equals(m.type()) || text == null || text.isEmpty()) {
			if (limits.allow("notice:" + spec.name() + ":" + m.from(), 1, 60_000L)) notice(spec, m, "textOnly", true);
			return;
		}
		Admission a = admit(spec, principal);
		if (!a.allowed()) { notice(spec, m, a.notice(spec), true); return; }
		switch (core.offer(principal, m.from(), new MessageRelay.Inbound(m.id(), text, m.timestampSec()))) {
			case DELIVERED -> { }
			case HELD -> notice(spec, m, "held", false);
			case NOT_OPEN -> notice(spec, m, "notOpen", true);
		}
	}

	/** One of the hub's own replies; a key names a configured notice, anything else is the text. */
	private void notice(RelaySpec spec, Incoming m, String keyOrText, boolean asReply) {
		String text = spec.notice(keyOrText);
		try {
			send(spec, m.from(), text != null ? text : keyOrText, asReply ? m.id() : null);
		} catch (RuntimeException e) {
			log.warn("{} '{}': a notice could not be sent ({})", NAME, spec.name(), e.getMessage());
		}
	}

	private List<Incoming> parse(RelaySpec spec, ACell payload) {
		AMap<AString, ACell> root = MessagingSettings.object(payload);
		if (!"whatsapp_business_account".equals(MessagingSettings.required(root, "object"))) {
			throw new IllegalArgumentException("Not a WhatsApp business account event");
		}
		List<Incoming> out = new ArrayList<>();
		for (ACell entryCell : array(root, "entry")) {
			AMap<AString, ACell> entry = MessagingSettings.object(entryCell);
			if (!spec.businessAccountId().equals(MessagingSettings.optional(entry, "id"))) continue;
			for (ACell changeCell : array(entry, "changes")) {
				AMap<AString, ACell> change = MessagingSettings.object(changeCell);
				if (!"messages".equals(MessagingSettings.optional(change, "field"))) continue;
				AMap<AString, ACell> value = MessagingSettings.object(change.get(MessagingSettings.key("value")));
				AMap<AString, ACell> metadata = MessagingSettings.object(value.get(MessagingSettings.key("metadata")));
				if (!spec.phoneNumberId().equals(MessagingSettings.optional(metadata, "phone_number_id"))) continue;
				// Delivery receipts arrive as "statuses" and are dropped here.
				for (ACell messageCell : array(value, "messages")) {
					AMap<AString, ACell> message = MessagingSettings.object(messageCell);
					String id = MessagingSettings.optional(message, "id");
					String from = MessagingSettings.optional(message, "from");
					String ts = MessagingSettings.optional(message, "timestamp");
					if (id == null || from == null || !from.matches("[0-9]{5,20}") || ts == null || !ts.matches("[0-9]{1,12}")) continue;
					ACell textCell = message.get(MessagingSettings.key("text"));
					String body = textCell instanceof AMap<?, ?> t ? MessagingSettings.optional(RT.castMap(t), "body") : null;
					out.add(new Incoming(id, from, MessagingSettings.optional(message, "type"), body, Long.parseLong(ts)));
				}
			}
		}
		return out;
	}

	// ---- the relay: the principals' side ----

	@Override
	public CompletableFuture<WebhookResponse> handleRelay(String binding, String action, AString caller, WebhookRequest request) {
		RelaySpec spec = specFor(binding);
		if (spec == null) return done(error(404, "unknown_relay"));
		if (!ready(spec)) return done(error(503, "relay_unavailable"));
		MessageRelay core = core(spec);
		return switch (action) {
			case "status" -> done(json(200, Maps.of("enabled", CVMBool.TRUE, "number", spec.number(),
				"linked", strOrNull(core.senderFor(caller)))));
			case "link" -> link(spec, core, caller);
			case "unlink" -> done(post(request) ? json(200, Maps.of("unlinked", CVMBool.create(core.unlinkPrincipal(caller) != null)))
				: error(405, "post_required"));
			case "poll" -> poll(spec, core, caller, request);
			case "reply" -> reply(spec, core, caller, request);
			default -> done(error(404, "unknown_action"));
		};
	}

	private CompletableFuture<WebhookResponse> link(RelaySpec spec, MessageRelay core, AString caller) {
		if (!limits.allow("link:" + spec.name() + ":" + caller, 10, 60_000L)) return done(error(429, "rate_limited"));
		// The admission hook may be slow: never on the request thread.
		return CompletableFuture.supplyAsync(() -> {
			Admission a = admit(spec, caller);
			if (!a.allowed()) return error(403, a.code());
			String code = core.newCode(caller);
			return json(200, Maps.of("code", code, "number", spec.number(),
				"link", "https://wa.me/" + spec.number() + "?text=LINK%20" + code,
				"expires_in", CVMLong.create(MessageRelay.CODE_TTL_MS / 1000)));
		}, VIRTUAL_EXECUTOR);
	}

	private CompletableFuture<WebhookResponse> poll(RelaySpec spec, MessageRelay core, AString caller, WebhookRequest request) {
		if (!limits.allow("poll:" + spec.name() + ":" + caller, 60, 60_000L)) return done(error(429, "rate_limited"));
		AMap<AString, ACell> body = body(request);
		CVMLong asked = body == null ? null : RT.ensureLong(body.get(MessagingSettings.key("wait_ms")));
		long wait = asked == null ? MAX_WAIT_MS : Math.max(0, Math.min(MAX_WAIT_MS, asked.longValue()));
		// Admission first, off the request thread; then the poll's own future holds the response.
		return CompletableFuture.supplyAsync(() -> admit(spec, caller), VIRTUAL_EXECUTOR).thenCompose(a -> {
			if (!a.allowed()) return done(error(403, a.code()));
			return pollAdmitted(core, caller, wait);
		});
	}

	private CompletableFuture<WebhookResponse> pollAdmitted(MessageRelay core, AString caller, long wait) {
		return core.poll(caller, wait).thenApply(messages -> {
			List<ACell> out = new ArrayList<>();
			for (MessageRelay.Inbound m : messages) {
				out.add(Maps.of("id", m.id(), "text", m.text(), "timestamp", CVMLong.create(m.timestampSec())));
			}
			return json(200, Maps.of("linked", strOrNull(core.senderFor(caller)), "messages", Vectors.create(out)));
		});
	}

	private CompletableFuture<WebhookResponse> reply(RelaySpec spec, MessageRelay core, AString caller, WebhookRequest request) {
		if (!post(request)) return done(error(405, "post_required"));
		AMap<AString, ACell> body = body(request);
		String messageId = body == null ? null : str(body, "message_id");
		String text = body == null ? null : str(body, "text");
		if (messageId == null || text == null || text.isBlank()) return done(error(400, "invalid_request"));
		MessageRelay.Target target = core.target(messageId);
		if (target == null || !target.principal().equals(caller)) return done(error(404, "unknown_message"));
		if (!limits.allow("reply:" + spec.name() + ":" + caller, 30, 60_000L)) return done(error(429, "rate_limited"));
		List<String> parts = parts(text);
		if (parts.size() > spec.maxParts()) return done(error(400, "too_long"));
		if (!core.claimReply(messageId)) return done(error(409, "already_replied"));
		// The sends are blocking provider calls: off the server thread.
		return CompletableFuture.supplyAsync(() -> {
			int sent = 0;
			try {
				for (String part : parts) {
					send(spec, target.sender(), part, sent == 0 ? messageId : null);
					sent++;
				}
			} catch (RuntimeException e) {
				log.warn("{} '{}': reply part {} of {} failed ({})", NAME, spec.name(), sent + 1, parts.size(), e.getMessage());
			}
			if (sent == 0) {
				core.releaseReply(messageId);
				return error(502, "send_failed");
			}
			charge(spec, caller, messageId, sent);
			return json(sent == parts.size() ? 200 : 502, Maps.of("sent", CVMLong.create(sent), "parts", CVMLong.create(parts.size())));
		}, VIRTUAL_EXECUTOR);
	}

	/** Split text into parts WhatsApp accepts, preferring paragraph, line and word breaks. */
	static List<String> parts(String text) {
		List<String> out = new ArrayList<>();
		String rest = text.strip();
		while (rest.codePointCount(0, rest.length()) > MAX_TEXT) {
			int limit = rest.offsetByCodePoints(0, MAX_TEXT);
			int cut = rest.lastIndexOf("\n\n", limit);
			if (cut < limit / 2) cut = rest.lastIndexOf('\n', limit);
			if (cut < limit / 2) cut = rest.lastIndexOf(' ', limit);
			if (cut < limit / 2) cut = limit;
			out.add(rest.substring(0, cut).strip());
			rest = rest.substring(cut).strip();
		}
		if (!rest.isEmpty()) out.add(rest);
		return out;
	}

	// ---- hooks ----

	private record Admission(boolean allowed, String code, String notice) {
		String notice(RelaySpec spec) { return notice != null ? notice : "notAvailable"; }
	}

	private Admission admit(RelaySpec spec, AString principal) {
		if (spec.admit() == null) return new Admission(true, null, null);
		try {
			ACell result = engine.jobs().invokeInternal(spec.admit(), Maps.of("principal", principal),
				RequestContext.of(spec.user())).get(15, java.util.concurrent.TimeUnit.SECONDS);
			AMap<AString, ACell> map = RT.ensureMap(result);
			if (map != null && map.get(K_ALLOWED) instanceof CVMBool b && b.booleanValue()) return new Admission(true, null, null);
			String code = map == null ? null : str(map, "code");
			return new Admission(false, code != null ? code : "not_admitted", map == null ? null : str(map, "notice"));
		} catch (Exception e) {
			log.warn("{} '{}': admission hook failed ({})", NAME, spec.name(), e.getClass().getSimpleName());
			return new Admission(false, "admission_unavailable", null);
		}
	}

	private void charge(RelaySpec spec, AString principal, String messageId, int parts) {
		if (spec.charge() == null) return;
		engine.jobs().invokeInternal(spec.charge(), Maps.of("principal", principal, "parts", CVMLong.create(parts),
			"message_id", messageId), RequestContext.of(spec.user())).whenComplete((r, e) -> {
				if (e != null) log.warn("{} '{}': charge hook failed ({})", NAME, spec.name(), e.getClass().getSimpleName());
			});
	}

	// ---- plumbing ----

	private void send(RelaySpec spec, String to, String text, String replyTo) {
		String url = apiUrl + "/" + spec.apiVersion() + "/" + spec.phoneNumberId() + "/messages";
		String token = credential(spec, spec.token(), url);
		if (token == null || token.isBlank()) throw new IllegalStateException(MessagingHttp.UNAVAILABLE);
		AMap<AString, ACell> body = Maps.of("messaging_product", "whatsapp", "recipient_type", "individual", "to", to,
			"type", "text", "text", Maps.of("body", text, "preview_url", CVMBool.FALSE));
		if (replyTo != null) body = body.assoc(MessagingSettings.key("context"), Maps.of("message_id", replyTo));
		ACell response = MessagingHttp.post(url, token, body);
		AVector<ACell> messages = RT.ensureVector(RT.getIn(response, MessagingSettings.key("messages")));
		if (messages == null || messages.isEmpty() || RT.ensureString(RT.getIn(messages.get(0), MessagingSettings.key("id"))) == null) {
			throw new IllegalStateException("WhatsApp did not return a message ID; delivery may be uncertain");
		}
	}

	/** A credential from the relay user's secret store, then the venue's. */
	private String credential(RelaySpec spec, String reference, String destination) {
		if (engine == null) return null;
		RequestContext ctx = RequestContext.of(spec.user());
		String value = destination == null ? engine.resolveSecret(reference, ctx) : engine.resolveSecret(reference, ctx, destination);
		if (value != null) return value;
		return destination == null ? engine.resolveSecret(reference, engine.venueContext())
			: engine.resolveSecret(reference, engine.venueContext(), destination);
	}

	private boolean ready(RelaySpec spec) {
		long now = System.currentTimeMillis();
		long[] cached = readiness.get(spec.name());
		if (cached != null && now - cached[0] < READY_CACHE_MS) return cached[1] == 1;
		boolean ok = engine != null;
		for (String ref : new String[] { spec.token(), spec.appSecret(), spec.verifyToken() }) {
			String v = ok ? credential(spec, ref, null) : null;
			if (v == null || v.isBlank()) { ok = false; break; }
		}
		readiness.put(spec.name(), new long[] { now, ok ? 1 : 0 });
		return ok;
	}

	private RelaySpec specFor(String binding) {
		if (binding == null || !binding.startsWith("c-")) return null;
		return specs.get(binding.substring(2));
	}

	private MessageRelay core(RelaySpec spec) {
		return cores.computeIfAbsent(spec.name(), n -> new MessageRelay(adapterWorkspace(), "config/" + n, spec.holdSeconds()));
	}

	/** Engine access for tests in this package. */
	Engine engine() { return engine; }

	private static boolean post(WebhookRequest request) { return "POST".equals(request.method()); }

	private static AMap<AString, ACell> body(WebhookRequest request) {
		byte[] bytes = request.body();
		if (bytes.length == 0) return Maps.empty();
		try { return RT.ensureMap(JSON.parse(new String(bytes, StandardCharsets.UTF_8))); }
		catch (RuntimeException e) { return null; }
	}

	private static AVector<ACell> array(AMap<AString, ACell> map, String key) {
		AVector<ACell> v = RT.ensureVector(map.get(MessagingSettings.key(key)));
		return v == null ? Vectors.empty() : v;
	}

	private static String str(AMap<AString, ACell> map, String key) {
		AString s = RT.ensureString(map.get(MessagingSettings.key(key)));
		return s == null ? null : s.toString();
	}

	private static ACell strOrNull(String s) { return s == null ? null : Strings.create(s); }

	private static CompletableFuture<WebhookResponse> done(WebhookResponse r) { return CompletableFuture.completedFuture(r); }

	private static WebhookResponse json(int status, AMap<AString, ACell> body) {
		return new WebhookResponse(status, "application/json", Map.of(),
			JSON.print(body).toString().getBytes(StandardCharsets.UTF_8));
	}

	private static WebhookResponse error(int status, String code) {
		return json(status, Maps.of("error", code));
	}

	/**
	 * Fixed-window counters per key, in memory. Keys include senders' numbers, which
	 * anyone can vary, so the table is capped: past the cap the oldest window goes.
	 */
	static final class Windows {
		static final int MAX_KEYS = 20_000;
		private final LinkedHashMap<String, long[]> windows = new LinkedHashMap<>();

		synchronized boolean allow(String key, int limit, long windowMs) {
			long now = System.currentTimeMillis();
			long[] w = windows.get(key);
			if (w == null || now - w[0] >= windowMs) {
				windows.remove(key);
				if (windows.size() >= MAX_KEYS) {
					var oldest = windows.keySet().iterator();
					oldest.next();
					oldest.remove();
				}
				windows.put(key, new long[] { now, 1 });
				return true;
			}
			return ++w[1] <= limit;
		}

		synchronized int size() { return windows.size(); }
	}
}
