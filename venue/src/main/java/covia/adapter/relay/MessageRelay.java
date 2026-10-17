package covia.adapter.relay;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.AdapterWorkspace;

/**
 * Provider-neutral core of a message relay: one provider binding (a phone number, a
 * bot) held by a hub venue, serving many downstream principals. A sender links to a
 * principal with a short-lived code the principal requested; each message from a
 * linked sender waits in memory for that principal to collect it by polling; the
 * principal may reply once to each message, inside the provider's reply window, and
 * only to that message's sender.
 *
 * <p><b>No message is written down.</b> The only durable state is the link between a
 * sender and a principal, under {@code <root>/links} and {@code <root>/principals} in
 * the adapter's private workspace. Message text, linking codes, the message→sender
 * targets and the de-duplication set live in bounded memory, so a restart loses
 * messages not yet collected, never a link.
 *
 * <p>A principal counts as <em>open</em> while it has a poll waiting or polled within
 * {@link #OPEN_WINDOW_MS}. A message for a principal that is not open waits only when
 * the configured hold is longer than that window; otherwise it is refused at once
 * ({@link Offer#NOT_OPEN}) so the provider can tell the sender.
 *
 * <p>Senders and reply addresses are the provider's own: a sender is whatever
 * identifies one person to the provider, and a message's reply address is an opaque
 * value the provider gets back with {@link #target} (a chat, a channel and thread, a
 * phone number). How long a reply may follow its message is the provider's rule too,
 * given to the constructor; it also bounds how long targets are remembered.
 */
public final class MessageRelay {

	/** How recently a principal must have polled to count as open. */
	public static final long OPEN_WINDOW_MS = 60_000L;
	/** How long a reply may follow its message when the provider sets no other rule. */
	public static final long DEFAULT_REPLY_WINDOW_MS = 24L * 60 * 60 * 1000;
	/** Linking codes live this long. */
	public static final long CODE_TTL_MS = 10 * 60 * 1000L;
	/** Messages waiting for one principal; past this the oldest is dropped. */
	public static final int MAX_QUEUE = 20;
	static final int MAX_PRINCIPALS = 10_000;
	static final int MAX_MESSAGES = 50_000;
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int CODE_LENGTH = 8;

	private static final AString K_SENDER = Strings.intern("sender");
	private static final AString K_PRINCIPAL = Strings.intern("principal");
	private static final AString K_LINKED_AT = Strings.intern("linkedAt");

	/**
	 * One message as a principal collects it.
	 *
	 * @param text  the text or caption; empty for a bare attachment
	 * @param via   where it came from, as the principal may see it (provider, sender,
	 *              chat or thread); never null
	 * @param media its attachments as the principal may see them, fetched on request;
	 *              never null
	 */
	public record Inbound(String id, String text, long timestampSec, AMap<AString, ACell> via, AVector<ACell> media) {
		public Inbound {
			if (via == null) via = Maps.empty();
			if (media == null) media = Vectors.empty();
		}

		public Inbound(String id, String text, long timestampSec, AMap<AString, ACell> via) {
			this(id, text, timestampSec, via, Vectors.empty());
		}

		public Inbound(String id, String text, long timestampSec) {
			this(id, text, timestampSec, Maps.empty(), Vectors.empty());
		}
	}

	/**
	 * Where a message came from, kept only so its reply can go back.
	 *
	 * @param address the provider's reply address for the message, opaque here
	 */
	public record Target(AString principal, String sender, ACell address, long timestampSec) {}

	/** What became of a message offered to a principal. */
	public enum Offer { DELIVERED, HELD, NOT_OPEN }

	private record Queued(Inbound message, long expiresAtMs) {}

	private static final class Mailbox {
		final ArrayDeque<Queued> queue = new ArrayDeque<>();
		CompletableFuture<List<Inbound>> waiter;
		long lastPollMs = Long.MIN_VALUE / 2;
	}

	private final AdapterWorkspace state;
	private final String root;
	private final long holdMs;
	private final long replyWindowMs;
	private final LongSupplier clock;
	private final SecureRandom random = new SecureRandom();
	private final Map<AString, Mailbox> boxes = new LinkedHashMap<>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;
		@Override protected boolean removeEldestEntry(Map.Entry<AString, Mailbox> eldest) {
			return size() > MAX_PRINCIPALS;
		}
	};
	private final Expiring<String, Target> targets;
	private final Expiring<String, Boolean> seen;
	private final Expiring<String, Boolean> replied;
	private final Expiring<String, AString> codes;

	/**
	 * @param state       the adapter's private workspace
	 * @param root        this relay's root in it
	 * @param holdSeconds how long a message may wait for collection
	 */
	public MessageRelay(AdapterWorkspace state, String root, int holdSeconds) {
		this(state, root, holdSeconds, DEFAULT_REPLY_WINDOW_MS, System::currentTimeMillis);
	}

	/**
	 * @param replyWindowMs how long a reply may follow its message, the provider's rule
	 */
	public MessageRelay(AdapterWorkspace state, String root, int holdSeconds, long replyWindowMs, LongSupplier clock) {
		this.state = Objects.requireNonNull(state);
		this.root = Objects.requireNonNull(root);
		state.path(root + "/links");   // validates the root
		if (holdSeconds < 0) throw new IllegalArgumentException("holdSeconds must not be negative");
		if (replyWindowMs <= 0) throw new IllegalArgumentException("replyWindowMs must be positive");
		this.holdMs = holdSeconds * 1000L;
		this.replyWindowMs = replyWindowMs;
		this.clock = clock;
		this.targets = new Expiring<>(MAX_MESSAGES, replyWindowMs, clock);
		this.seen = new Expiring<>(MAX_MESSAGES, replyWindowMs, clock);
		this.replied = new Expiring<>(MAX_MESSAGES, replyWindowMs, clock);
		this.codes = new Expiring<>(MAX_PRINCIPALS, CODE_TTL_MS, clock);
	}

	/** How long a reply may follow its message. */
	public long replyWindowMs() {
		return replyWindowMs;
	}

	// ---- links (the one durable fact) ----

	/** The principal a sender is linked to, or null. */
	public AString principalFor(String sender) {
		return RT.ensureString(map(state.read(linkPath(sender))).get(K_PRINCIPAL));
	}

	/** The sender a principal is linked to, or null. */
	public String senderFor(AString principal) {
		AString sender = RT.ensureString(map(state.read(principalPath(principal))).get(K_SENDER));
		return sender == null ? null : sender.toString();
	}

	/** Link a sender to a principal, replacing either side's earlier link. */
	public synchronized void link(String sender, AString principal) {
		String oldSender = senderFor(principal);
		if (oldSender != null) state.delete(linkPath(oldSender));
		AString oldPrincipal = principalFor(sender);
		if (oldPrincipal != null) state.delete(principalPath(oldPrincipal));
		state.write(linkPath(sender), Maps.of(K_SENDER, Strings.create(sender), K_PRINCIPAL, principal,
			K_LINKED_AT, CVMLong.create(clock.getAsLong())));
		state.write(principalPath(principal), Maps.of(K_SENDER, Strings.create(sender)));
		wake(principal);
	}

	/** Remove a principal's link; the sender it held, or null. */
	public synchronized String unlinkPrincipal(AString principal) {
		String sender = senderFor(principal);
		state.delete(principalPath(principal));
		if (sender != null) state.delete(linkPath(sender));
		clear(principal);
		return sender;
	}

	/** Remove a sender's link; the principal it was linked to, or null. */
	public synchronized AString unlinkSender(String sender) {
		AString principal = principalFor(sender);
		state.delete(linkPath(sender));
		if (principal != null) {
			state.delete(principalPath(principal));
			clear(principal);
		}
		return principal;
	}

	/** Remove every link this relay holds (the relay is being deleted). */
	public synchronized void deleteAll() {
		state.delete(root);
		close();
	}

	private String linkPath(String sender) {
		return root + "/links/" + Strings.create(sender).getHash().toHexString();
	}

	private String principalPath(AString principal) {
		return root + "/principals/" + principal.getHash().toHexString();
	}

	// ---- linking codes ----

	/** A fresh linking code for a principal; codes simply expire. */
	public String newCode(AString principal) {
		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
		String code = sb.toString();
		codes.put(code, principal);
		return code;
	}

	/** The principal a code was issued to, once; null when unknown or expired. */
	public AString redeem(String code) {
		return code == null ? null : codes.remove(code.strip().toUpperCase(Locale.ROOT));
	}

	// ---- messages ----

	/** True the first time a provider message id is seen: providers may deliver twice. */
	public boolean firstSighting(String messageId) {
		return seen.putIfAbsent(messageId, Boolean.TRUE);
	}

	/**
	 * Hand a message from {@code sender} to {@code principal}.
	 *
	 * @param address the provider's reply address, returned by {@link #target}
	 */
	public Offer offer(AString principal, String sender, Inbound message, ACell address) {
		long now = clock.getAsLong();
		CompletableFuture<List<Inbound>> wake;
		List<Inbound> ready;
		Offer outcome;
		synchronized (boxes) {
			Mailbox box = boxes.computeIfAbsent(principal, k -> new Mailbox());
			boolean open = box.waiter != null || now - box.lastPollMs <= OPEN_WINDOW_MS;
			if (!open && holdMs <= OPEN_WINDOW_MS) return Offer.NOT_OPEN;
			if (box.queue.size() >= MAX_QUEUE) box.queue.pollFirst();
			box.queue.addLast(new Queued(message, now + Math.max(holdMs, OPEN_WINDOW_MS)));
			targets.put(message.id(), new Target(principal, sender, address, message.timestampSec()));
			outcome = open ? Offer.DELIVERED : Offer.HELD;
			wake = box.waiter;
			box.waiter = null;
			ready = wake == null ? null : drain(box, now);
		}
		if (wake != null) wake.complete(ready);
		return outcome;
	}

	/**
	 * A principal's poll: what is waiting now, or a future that completes when a message
	 * arrives, the principal's link changes, or {@code waitMs} passes.
	 */
	public CompletableFuture<List<Inbound>> poll(AString principal, long waitMs) {
		long now = clock.getAsLong();
		CompletableFuture<List<Inbound>> superseded;
		CompletableFuture<List<Inbound>> waiter;
		synchronized (boxes) {
			Mailbox box = boxes.computeIfAbsent(principal, k -> new Mailbox());
			box.lastPollMs = now;
			List<Inbound> ready = drain(box, now);
			if (!ready.isEmpty() || waitMs <= 0) return CompletableFuture.completedFuture(ready);
			superseded = box.waiter;
			waiter = new CompletableFuture<>();
			box.waiter = waiter;
		}
		if (superseded != null) superseded.complete(List.of());
		waiter.completeOnTimeout(List.of(), waitMs, TimeUnit.MILLISECONDS);
		waiter.whenComplete((r, e) -> {
			synchronized (boxes) {
				Mailbox box = boxes.get(principal);
				if (box == null) return;
				box.lastPollMs = clock.getAsLong();
				if (box.waiter == waiter) box.waiter = null;
			}
		});
		return waiter;
	}

	/** End a principal's waiting poll early, so it reads its link again. */
	public void wake(AString principal) {
		CompletableFuture<List<Inbound>> waiter;
		synchronized (boxes) {
			Mailbox box = boxes.get(principal);
			if (box == null) return;
			waiter = box.waiter;
			box.waiter = null;
		}
		if (waiter != null) waiter.complete(List.of());
	}

	/** Forget a principal's waiting messages (it unlinked) and end its poll. */
	public void clear(AString principal) {
		synchronized (boxes) {
			Mailbox box = boxes.get(principal);
			if (box != null) box.queue.clear();
		}
		wake(principal);
	}

	/** Where a message came from, while its reply window is open; null otherwise. */
	public Target target(String messageId) {
		Target t = targets.get(messageId);
		if (t == null) return null;
		if (clock.getAsLong() - t.timestampSec() * 1000L >= replyWindowMs) return null;
		return t;
	}

	/** True the first time a reply is claimed for a message: one answer per message. */
	public boolean claimReply(String messageId) {
		return replied.putIfAbsent(messageId, Boolean.TRUE);
	}

	/** Undo {@link #claimReply} when nothing could be sent, so a retry may. */
	public void releaseReply(String messageId) {
		replied.remove(messageId);
	}

	/** Release every waiting poll (the relay is stopping). */
	public void close() {
		List<CompletableFuture<List<Inbound>>> waiters = new ArrayList<>();
		synchronized (boxes) {
			for (Mailbox box : boxes.values()) {
				if (box.waiter != null) waiters.add(box.waiter);
				box.waiter = null;
			}
		}
		for (var w : waiters) w.complete(List.of());
	}

	private static List<Inbound> drain(Mailbox box, long now) {
		List<Inbound> out = new ArrayList<>();
		while (!box.queue.isEmpty()) {
			Queued q = box.queue.pollFirst();
			if (q.expiresAtMs() > now) out.add(q.message());
		}
		return out;
	}

	private static AMap<AString, ACell> map(ACell value) {
		return value instanceof AMap<?, ?> ? RT.castMap(value) : Maps.empty();
	}

	/** A bounded map whose entries expire a fixed time after they were written. */
	static final class Expiring<K, V> {
		private record Entry<V>(V value, long expiresAt) {}
		private final int max;
		private final long ttl;
		private final LongSupplier clock;
		private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();

		Expiring(int max, long ttl, LongSupplier clock) {
			this.max = max;
			this.ttl = ttl;
			this.clock = clock;
		}

		synchronized V get(K key) {
			Entry<V> e = entries.get(key);
			if (e == null) return null;
			if (e.expiresAt() <= clock.getAsLong()) {
				entries.remove(key);
				return null;
			}
			return e.value();
		}

		synchronized void put(K key, V value) {
			long now = clock.getAsLong();
			entries.remove(key);
			for (Iterator<Entry<V>> it = entries.values().iterator(); it.hasNext();) {
				if (it.next().expiresAt() <= now) it.remove();
				else break;
			}
			if (entries.size() >= max) {
				Iterator<K> oldest = entries.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
			entries.put(key, new Entry<>(value, now + ttl));
		}

		/** True when the key was absent (or expired) and is now present. */
		synchronized boolean putIfAbsent(K key, V value) {
			if (get(key) != null) return false;
			put(key, value);
			return true;
		}

		synchronized V remove(K key) {
			Entry<V> e = entries.remove(key);
			return e == null || e.expiresAt() <= clock.getAsLong() ? null : e.value();
		}
	}
}
