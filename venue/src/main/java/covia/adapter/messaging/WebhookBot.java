package covia.adapter.messaging;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.adapter.webhook.WebhookInbox;
import covia.api.Fields;

/** A durable webhook binding. Uncertain STARTED work is reported, never blindly retried. */
public final class WebhookBot<S extends WebhookBotSpec> implements MessagingBot<S> {
	public static final AString VERSION = Strings.intern("bindingVersion");
	public static final String PROCESSING_FAILED = "Processing failed; inspect the STARTED receipt before any manual retry";
	private final AWebhookMessagingAdapter<S> adapter;
	private final S spec;
	private final Managed managed;
	private final WebhookInbox inbox;
	private final ConversationRouter router;
	private final String binding;
	private final AString version;
	private final Set<String> queued = ConcurrentHashMap.newKeySet();
	private volatile boolean running;
	private volatile String lastError;
	private ScheduledFuture<?> wake;

	WebhookBot(AWebhookMessagingAdapter<S> adapter, S spec, Managed managed) {
		this.adapter = adapter; this.spec = spec; this.managed = managed;
		AString owner = spec.userDID(adapter.engine);
		String identity = Strings.create(ConversationRouter.key(owner.toString(), spec.installationKey())).getHash().toHexString();
		version = Strings.create(spec.settings().getHash().toHexString());
		binding = managed == Managed.CONFIG ? "c-" + spec.name()
			: "u-" + Strings.create(ConversationRouter.key(owner.toString(), spec.name())).getHash().toHexString();
		String inboxRoot = managed == Managed.CONFIG ? "config/" + spec.name() + "/inbox/" + identity
			: adapter.userStatePath(owner, "inbox/" + spec.name() + "/" + identity);
		String sessionsRoot = managed == Managed.CONFIG ? "config/" + spec.name() + "/sessions/" + identity
			: adapter.userStatePath(owner, "sessions/" + spec.name() + "/" + identity);
		inbox = new WebhookInbox(adapter.engine, adapter.state(), inboxRoot);
		router = new ConversationRouter(adapter.engine, spec, new ConversationSessions(adapter.state(), sessionsRoot + "/" + version,
			this, () -> running && adapter.isActive()));
	}
	@Override public S spec() { return spec; }
	@Override public Managed managed() { return managed; }
	public String binding() { return binding; }
	public boolean running() { return running; }
	public WebhookInbox inbox() { return inbox; }
	public String credential(String field) {
		return adapter.resolveCredential(MessagingSettings.required(spec.settings(), field), spec.userDID(adapter.engine));
	}
	public void requireReady() {
		if (!running || !adapter.isActive()) throw new IllegalStateException(AWebhookMessagingAdapter.UNAVAILABLE);
		for (String field : spec.credentialFields()) {
			String value = credential(field);
			if (value == null || value.isBlank()) throw new IllegalStateException(MessagingHttp.UNAVAILABLE);
		}
	}
	private boolean ready() {
		try { requireReady(); return true; }
		catch (RuntimeException e) { return false; }
	}
	@Override public synchronized void start() {
		if (running) return;
		running = true;
		wake = adapter.scheduleRetry(this::recoverPending, 0);
	}
	@Override public synchronized void stop() {
		running = false;
		if (wake != null) wake.cancel(false);
	}
	/** Durable acceptance and asynchronous execution are deliberately separate. */
	public void accept(String id, AMap<AString, ACell> event) {
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
		// Reconfiguration cannot execute old accepted work using a different handler
		// or admission policy. Keep these pending receipts visible for the operator.
		if (!version.equals(event.get(VERSION))) return;
		String id = MessagingSettings.required(receipt, "id");
		String conversation = MessagingSettings.required(event, "conversation");
		if (!queued.add(id)) return;
		router.enqueue(conversation, () -> {
			if (!claim(id)) return;
			ACell input = event.get(Fields.INPUT);
			String reply;
			if (spec.routesToAgent()) {
				ACell message = Maps.of("text", MessagingSettings.required(event, "text"), "via", event.get(MessagingSettings.key("via")));
				reply = ConversationRouter.responseText(router.chat(conversation, message));
			} else reply = router.operationReply(router.runOperation(spec.operation(), input));
			if (!running || !adapter.isActive()) return;
			if (reply != null) adapter.sendReply(this, event, reply);
			complete(id);
		}).whenComplete((ignored, failure) -> {
			queued.remove(id);
			if (failure != null) lastError = PROCESSING_FAILED;
		});
	}
	@Override public AMap<AString, ACell> status() {
		return Maps.of("name", spec.name(), "user", spec.userDID(adapter.engine),
			"managed", managed == Managed.CONFIG ? "config" : "runtime", "state", ready() ? "RUNNING" : "PENDING",
			"webhookPath", "/webhooks/" + adapter.getName() + "/" + binding,
			"pending", inbox.records(WebhookInbox.PENDING).size(), "started", inbox.records(WebhookInbox.STARTED).size(),
			"queued", queued.size(), "error", lastError);
	}
}
