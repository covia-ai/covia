package covia.adapter.messaging;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.webhook.WebhookInbox;
import covia.api.Fields;

/**
 * A durable binding fed by signed HTTP callbacks, a provider connection, or
 * both. Uncertain STARTED work is reported, never blindly retried. A binding
 * whose handler is {@code relay} hands its events to a {@link RelayBinding}
 * instead: nothing reaches the inbox, and only links are stored.
 */
public final class WebhookBot<S extends WebhookBotSpec> implements MessagingBot<S> {
	private static final Logger log = LoggerFactory.getLogger(WebhookBot.class);
	public static final String REPLY_FAILED = "Reply failed after session delivery";
	public static final String RECONFIGURED = "Bot settings changed after this message was accepted";
	public static final AString VERSION = Strings.intern("bindingVersion");
	public static final String PROCESSING_FAILED = "Processing failed; inspect the receipt before any manual retry";
	public static final String CONNECTED = "CONNECTED", CONNECTING = "CONNECTING", RETRYING = "RETRYING";
	private final AWebhookMessagingAdapter<S> adapter;
	private final S spec;
	private final Managed managed;
	private final covia.venue.AdapterState state;
	private final WebhookInbox inbox;
	private final ConversationRouter router;
	private final RelayBinding<S> relay;
	private final String binding;
	private final AString version;
	private final Set<String> queued = ConcurrentHashMap.newKeySet();
	private volatile boolean running;
	private volatile String lastError;
	private volatile boolean retrying;
	private ScheduledFuture<?> wake, reconnect;
	private MessagingConnection connection;

	WebhookBot(AWebhookMessagingAdapter<S> adapter, S spec, Managed managed) {
		this.adapter = adapter; this.spec = spec; this.managed = managed;
		AString owner = spec.userDID(adapter.engine);
		state = adapter.state(owner);
		String identity = Strings.create(ConversationRouter.key(owner.toString(), spec.installationKey())).getHash().toHexString();
		version = Strings.create(spec.settings().getHash().toHexString());
		binding = managed == Managed.CONFIG ? "c-" + spec.name()
			: "u-" + Strings.create(ConversationRouter.key(owner.toString(), spec.name())).getHash().toHexString();
		String inboxRoot = managed == Managed.CONFIG ? "config/" + spec.name() + "/inbox/" + identity
			: adapter.userStatePath(owner, "inbox/" + spec.name() + "/" + identity);
		String sessionsRoot = managed == Managed.CONFIG ? "config/" + spec.name() + "/sessions/" + identity
			: adapter.userStatePath(owner, "sessions/" + spec.name() + "/" + identity);
		String relayRoot = managed == Managed.CONFIG ? "config/" + spec.name() + "/relay/" + identity
			: adapter.userStatePath(owner, "relay/" + spec.name() + "/" + identity);
		inbox = new WebhookInbox(adapter.engine, state, inboxRoot);
		router = new ConversationRouter(adapter.engine, spec, adapter.principalOf(spec, managed), new ConversationSessions(state, sessionsRoot + "/" + version,
			this, () -> running && adapter.isActive()));
		relay = spec.routesToRelay() ? new RelayBinding<>(this, adapter, relayRoot) : null;
	}
	@Override public S spec() { return spec; }
	@Override public Managed managed() { return managed; }
	public String binding() { return binding; }
	public boolean running() { return running; }
	public WebhookInbox inbox() { return inbox; }
	covia.venue.AdapterState state() { return state; }
	/** This binding's relay, or null when its handler is an agent or operation. */
	public RelayBinding<S> relay() { return relay; }
	/**
	 * Whether this binding's agent already holds a conversation, for providers
	 * that let a conversation continue without re-addressing the bot.
	 */
	public boolean holdsConversation(String conversation) { return router.holds(conversation); }
	public String credential(String field) {
		return credential(field, null);
	}
	/**
	 * A credential about to be sent to {@code destination}, so a {@code secret/use}
	 * grant bound to a url prefix covers it only there.
	 */
	public String credential(String field, String destination) {
		state.requireCurrentUser();
		return adapter.resolveCredential(MessagingSettings.required(spec.settings(), field), this, destination);
	}
	public void requireReady() {
		if (!running || !adapter.isActive()) throw new IllegalStateException(AWebhookMessagingAdapter.UNAVAILABLE);
		for (String field : spec.credentialFields()) {
			String value = credential(field);
			if (value == null || value.isBlank()) throw new IllegalStateException(MessagingHttp.UNAVAILABLE);
		}
	}
	boolean ready() {
		try { requireReady(); return true; }
		catch (RuntimeException e) { return false; }
	}
	@Override public synchronized void start() {
		if (running) return;
		running = true;
		// A relay keeps no inbox, so there is nothing to recover.
		if (relay == null) wake = adapter.scheduleRetry(this::recoverPending, 0);
		if (spec.connectionIngress()) scheduleConnect(0);
	}
	@Override public void stop() {
		MessagingConnection open;
		synchronized (this) {
			running = false;
			if (wake != null) wake.cancel(false);
			if (reconnect != null) reconnect.cancel(false);
			open = connection;
			connection = null;
		}
		if (open != null) open.close();
		if (relay != null) relay.close();
	}
	private synchronized void scheduleConnect(long millis) {
		if (running) reconnect = adapter.scheduleRetry(() -> AAdapter.VIRTUAL_EXECUTOR.execute(this::connect), millis);
	}
	/** Opening may block, so it runs on a virtual thread; the shared retry thread only schedules. */
	private void connect() {
		synchronized (this) { if (!running || connection != null) return; }
		if (!ready()) { scheduleConnect(adapter.retryMillis); return; }
		MessagingConnection opened;
		try { opened = adapter.openConnection(this); }
		catch (Exception e) {
			retrying = true;
			log.warn("{} bot '{}': connection failed: {}", adapter.getName(), spec.name(), e.toString());
			scheduleConnect(adapter.retryMillis);
			return;
		}
		synchronized (this) {
			if (!running) { opened.close(); return; }
			connection = opened;
			retrying = false;
		}
		// A provider-requested refresh reconnects at once; a failure waits for the retry cadence.
		opened.ended().whenComplete((ignored, failure) -> {
			synchronized (this) {
				if (connection != opened) return;
				connection = null;
				retrying = failure != null;
			}
			if (failure != null) log.warn("{} bot '{}': connection lost: {}", adapter.getName(), spec.name(), failure.toString());
			scheduleConnect(failure == null ? 0 : adapter.retryMillis);
		});
	}
	/**
	 * Durable acceptance and asynchronous execution are deliberately separate. A
	 * relay binding writes nothing: its event goes to the relay in memory.
	 */
	public void accept(String id, AMap<AString, ACell> event) {
		if (relay != null) {
			if (!running || !adapter.isActive()) throw new IllegalStateException(AWebhookMessagingAdapter.UNAVAILABLE);
			relay.accept(id, event);
			return;
		}
		AMap<AString, ACell> receipt;
		// Engine precedes the binding monitor, matching adapter registration. Stop
		// cannot return while an acceptance is still able to recreate deleted state.
		synchronized (adapter.engine) {
			synchronized (this) {
				if (!running || !adapter.isActive()) throw new IllegalStateException(AWebhookMessagingAdapter.UNAVAILABLE);
				receipt = inbox.accept(id, event.assoc(VERSION, version));
			}
		}
		queue(receipt);
	}
	/**
	 * An authenticated message of a kind this provider does not route (media, say),
	 * as an event without {@code text}. A relay tells a linked sender it relays only
	 * text; other bindings ignore it, as before.
	 */
	public void unsupported(String id, AMap<AString, ACell> event) {
		if (relay != null && running && adapter.isActive()) relay.accept(id, event.dissoc(MessagingSettings.key("text")));
	}
	private boolean claim(String id) {
		synchronized (adapter.engine) {
			synchronized (this) { return ready() && inbox.claim(id); }
		}
	}
	private void complete(String id) {
		synchronized (adapter.engine) {
			synchronized (this) { if (running) inbox.complete(id); }
		}
	}
	/** Receipt writes cannot recreate state after stop/delete; null once stopped. */
	private AMap<AString, ACell> guarded(Supplier<AMap<AString, ACell>> write) {
		synchronized (adapter.engine) {
			synchronized (this) { return running && adapter.isActive() ? write.get() : null; }
		}
	}
	private void recoverPending() {
		try {
			if (ready()) inbox.records(WebhookInbox.PENDING).stream()
				.sorted(Comparator.comparingLong(r -> r.get(WebhookInbox.RECEIVED_AT) instanceof CVMLong n ? n.longValue() : 0))
				.forEach(this::queue);
		} catch (RuntimeException e) { lastError = PROCESSING_FAILED; }
		finally {
			synchronized (this) { if (running) wake = adapter.scheduleRetry(this::recoverPending, adapter.retryMillis); }
		}
	}
	private void queue(AMap<AString, ACell> receipt) {
		if (!WebhookInbox.PENDING.equals(receipt.get(Fields.STATUS)) || !ready()) return;
		var event = MessagingSettings.object(receipt.get(Fields.INPUT));
		String id = MessagingSettings.required(receipt, "id");
		// Reconfiguration cannot execute old accepted work using a different handler
		// or admission policy. Fail it once; the receipt stays visible for the operator.
		if (!version.equals(event.get(VERSION))) {
			if (guarded(() -> inbox.fail(id, RECONFIGURED, null, System.currentTimeMillis())) != null) lastError = RECONFIGURED;
			return;
		}
		String conversation = MessagingSettings.required(event, "conversation");
		if (!queued.add(id)) return;
		AMap<AString, ACell> channel = RT.ensureMap(event.get(Fields.VIA));
		boolean localSession = spec.routesToAgent() && router.supportsSessionDelivery(channel);
		router.enqueue(conversation, () -> {
			if (!claim(id)) return;
			if (localSession) {
				String refusal = router.deliveryRefusal(channel);
				if (refusal != null) throw new IllegalStateException(refusal);
				deliverSession(id, event, conversation);
				return;
			}
			ACell input = event.get(Fields.INPUT);
			String reply;
			if (spec.routesToAgent()) {
				reply = ConversationRouter.responseText(router.chat(conversation, agentMessage(event)));
			} else reply = router.operationReply(router.runOperation(spec.operation(), input, channel));
			if (!running || !adapter.isActive()) return;
			if (reply != null) adapter.sendReply(this, event, reply);
			complete(id);
		}).whenComplete((ignored, failure) -> {
			queued.remove(id);
			if (failure != null) {
				lastError = PROCESSING_FAILED;
				log.warn("{} bot '{}': {}: {}", adapter.getName(), spec.name(), PROCESSING_FAILED, AAdapter.describeFailure(failure));
				try { if (localSession) guarded(() -> inbox.fail(id, PROCESSING_FAILED, AAdapter.describeFailure(failure), System.currentTimeMillis())); }
				catch (RuntimeException writeFailure) { log.warn("Could not record processing failure: {}", writeFailure.toString()); }
			}
		});
	}
	/**
	 * An agent's turn: {@code {text, via, attachments?, content?}}. Each item of the
	 * event's {@code media} is fetched by the provider and stored in the owner's
	 * private drive named for the provider, then given to the binding's
	 * {@code mediaOperation}; images also become image blocks. The text is the
	 * caption, empty for a bare voice note. A failed fetch fails the turn like any
	 * other processing failure.
	 */
	private AMap<AString, ACell> agentMessage(AMap<AString, ACell> event) {
		String text = MessagingSettings.optional(event, "text");
		ACell via = event.get(MessagingSettings.key("via"));
		AMap<AString, ACell> message = Maps.of(Fields.TEXT, text == null ? "" : text, "via", via);
		AVector<ACell> media = RT.ensureVector(event.get(MessagingMedia.K_MEDIA));
		if (media == null || media.isEmpty()) return message;
		AVector<ACell> attachments = Vectors.empty(), content = Vectors.empty();
		String directory = MessagingMedia.ownerDirectory(spec.userDID(adapter.engine), adapter.getName());
		for (ACell cell : media) {
			AMap<AString, ACell> item = MessagingSettings.object(cell);
			MessagingMedia.Fetched fetched = adapter.fetchMedia(this, item);
			String type = MessagingSettings.optional(item, "mediaType");
			if (type == null) type = fetched.contentType();
			String name = MessagingSettings.optional(item, "fileName");
			if (!MessagingMedia.safeFileName(name)) name = MessagingMedia.fallbackName(spec.name(), MessagingSettings.required(item, "id"), type);
			AMap<AString, ACell> attachment;
			try {
				// The stored name, ref and size win over the provider's own description.
				attachment = item.merge(MessagingMedia.store(adapter.engine, fetched.bytes(), type, directory, name, router.context()));
			} catch (java.io.IOException e) {
				throw new IllegalStateException(MessagingMedia.UNSUPPORTED_DESTINATION + ": " + directory);
			}
			if (spec.mediaOperation() != null) {
				attachment = MessagingMedia.processed(attachment, router.runOperation(spec.mediaOperation(),
					attachment.assoc(MessagingMedia.K_CAPTION, Strings.create(text == null ? "" : text)).assoc(MessagingSettings.key("via"), via)));
			}
			attachments = attachments.conj(attachment);
			AMap<AString, ACell> image = MessagingMedia.imageBlock(attachment);
			if (image != null) content = content.conj(image);
		}
		message = message.assoc(MessagingMedia.K_ATTACHMENTS, attachments);
		return content.isEmpty() ? message : message.assoc(Fields.CONTENT, content);
	}
	/** One atomic handoff: input, session mapping and COMPLETE receipt commit together. */
	private void deliverSession(String id, AMap<AString, ACell> event, String conversation) {
		ACell message = agentMessage(event);
		covia.grid.Job job;
		synchronized (adapter.engine) {
			synchronized (this) {
				if (!ready()) throw new IllegalStateException(AWebhookMessagingAdapter.UNAVAILABLE);
				job = router.deliver(conversation, message, (draft, sid, jid) -> inbox.deliver(draft, id, sid, jid));
			}
		}
		ConversationRouter.onResponse(job, response -> {
			String reply = ConversationRouter.responseText(response);
			if (running && adapter.isActive() && reply != null) adapter.sendReply(this, event, reply);
		}, failure -> { if (running) lastError = REPLY_FAILED; });
	}
	@Override public AMap<AString, ACell> status() {
		boolean connected;
		synchronized (this) { connected = connection != null; }
		AMap<AString, ACell> status = Maps.of("name", spec.name(), "user", spec.userDID(adapter.engine),
			"managed", managed == Managed.CONFIG ? "config" : "runtime",
			"state", ready() && (connected || !spec.connectionIngress()) ? "RUNNING" : "PENDING",
			"pending", inbox.records(WebhookInbox.PENDING).size(), "started", inbox.records(WebhookInbox.STARTED).size(),
			"failed", inbox.records(WebhookInbox.FAILED).size(), "queued", queued.size(), "error", lastError);
		if (spec.webhookIngress()) status = status.assoc(MessagingSettings.key("webhookPath"), Strings.create("/webhooks/" + adapter.getName() + "/" + binding));
		if (relay != null) status = status.assoc(MessagingSettings.key("relayPath"), Strings.create("/relay/" + adapter.getName() + "/" + binding));
		if (spec.connectionIngress()) status = status.assoc(MessagingSettings.key("connection"),
			Strings.create(connected ? CONNECTED : retrying ? RETRYING : CONNECTING));
		return status;
	}
}
