package covia.adapter.messaging;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import convex.core.data.Hash;
import convex.core.data.Strings;

/**
 * One provider connection per credential, shared by every binding that
 * resolves to it. Providers such as Slack balance a credential's events across
 * all of its open connections, so separate connections per binding would split
 * them. Holding the credential already entitles a binding to every event it
 * receives, so sharing grants nothing new; bindings with different credentials
 * never share. Credentials are keyed by hash in memory and never stored.
 *
 * <p>Each binding holds a lease: a {@link MessagingConnection} that ends when
 * the shared connection ends and leaves the group when closed. The last lease
 * out closes the connection.</p>
 */
public final class SharedConnections<S extends WebhookBotSpec> {
	@FunctionalInterface
	public interface Opener<S extends WebhookBotSpec> {
		/** Open a connection for a credential, routing events to the bindings currently holding a lease. */
		MessagingConnection open(String credential, Supplier<List<WebhookBot<S>>> members) throws Exception;
	}

	private final Map<Hash, Group> groups = new HashMap<>();

	private final class Group {
		final Hash key;
		final Set<WebhookBot<S>> members = new LinkedHashSet<>();
		CompletableFuture<MessagingConnection> connection;
		Group(Hash key) { this.key = key; }
		List<WebhookBot<S>> members() { synchronized (SharedConnections.this) { return List.copyOf(members); } }
	}

	/** Joins the credential's connection, opening it first when none is open. May block while opening. */
	public MessagingConnection lease(WebhookBot<S> bot, String credential, Opener<S> opener) throws Exception {
		Hash key = Strings.create(credential).getHash();
		Group group;
		CompletableFuture<MessagingConnection> pending;
		boolean opening = false;
		synchronized (this) {
			group = groups.computeIfAbsent(key, Group::new);
			group.members.add(bot);
			if (group.connection == null || ended(group.connection)) {
				group.connection = new CompletableFuture<>();
				opening = true;
			}
			pending = group.connection;
		}
		if (opening) {
			try {
				MessagingConnection opened = opener.open(credential, group::members);
				opened.ended().whenComplete((ignored, failure) -> forget(group, pending));
				pending.complete(opened);
			} catch (Exception | Error e) {
				forget(group, pending);
				pending.completeExceptionally(e);
			}
		}
		try { return new Lease(group, bot, pending.get()); }
		catch (ExecutionException e) {
			leave(group, bot);
			if (e.getCause() instanceof Exception cause) throw cause;
			if (e.getCause() instanceof Error error) throw error;
			throw e;
		}
	}

	private static boolean ended(CompletableFuture<MessagingConnection> connection) {
		if (!connection.isDone()) return false;
		if (connection.isCompletedExceptionally()) return true;
		return connection.join().ended().toCompletableFuture().isDone();
	}

	private synchronized void forget(Group group, CompletableFuture<MessagingConnection> connection) {
		if (group.connection == connection) group.connection = null;
		if (group.connection == null && group.members.isEmpty()) groups.remove(group.key, group);
	}

	private void leave(Group group, WebhookBot<S> bot) {
		MessagingConnection last = null;
		synchronized (this) {
			if (!group.members.remove(bot) || !group.members.isEmpty()) return;
			CompletableFuture<MessagingConnection> current = group.connection;
			// An open in progress belongs to its opener, which is still a member.
			if (current != null && !current.isDone()) return;
			if (current != null && !current.isCompletedExceptionally()) last = current.join();
			group.connection = null;
			groups.remove(group.key, group);
		}
		if (last != null) last.close();
	}

	private final class Lease implements MessagingConnection {
		private final CompletableFuture<Void> ended = new CompletableFuture<>();
		private final Group group;
		private final WebhookBot<S> bot;
		Lease(Group group, WebhookBot<S> bot, MessagingConnection shared) {
			this.group = group; this.bot = bot;
			// Leave before the lease ends, so the binding's prompt reconnect rejoins cleanly.
			shared.ended().whenComplete((ignored, failure) -> {
				leave(group, bot);
				if (failure == null) ended.complete(null); else ended.completeExceptionally(failure);
			});
		}
		@Override public CompletionStage<Void> ended() { return ended; }
		@Override public void close() {
			if (ended.isDone()) return;
			leave(group, bot);
			ended.complete(null);
		}
	}
}
