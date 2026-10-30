package covia.adapter.slack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.adapter.messaging.ChannelAuthentication;
import covia.adapter.messaging.MessagingConnection;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBot;

/**
 * One Slack Socket Mode WebSocket, shared by the bindings that hold its app
 * token. Each event goes to every binding of its installation, which applies
 * its own admission. Envelopes are handled one at a time and acknowledged only
 * after durable acceptance; an unacknowledged envelope is redelivered by Slack
 * and deduplicated on its payload's {@code event_id}.
 */
final class SocketModeConnection implements MessagingConnection, WebSocket.Listener {
	private static final Logger log = LoggerFactory.getLogger(SocketModeConnection.class);
	static final String CLOSED = "Slack closed the Socket Mode connection before it was ready";
	static final String SILENT = "Slack Socket Mode connection went silent";
	static final String OVERSIZED = "Slack Socket Mode message exceeds the size limit";
	private static final int MAX_MESSAGE = 1024 * 1024;
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final SlackAdapter adapter;
	private final Supplier<List<WebhookBot<BotSpec>>> members;
	private final CompletableFuture<Void> ended = new CompletableFuture<>();
	private final StringBuilder message = new StringBuilder();
	private volatile WebSocket socket;
	private volatile boolean hello;
	private volatile long heard = System.nanoTime();
	private volatile ScheduledFuture<?> keepalive;

	private SocketModeConnection(SlackAdapter adapter, Supplier<List<WebhookBot<BotSpec>>> members) { this.adapter = adapter; this.members = members; }

	static SocketModeConnection open(SlackAdapter adapter, Supplier<List<WebhookBot<BotSpec>>> members, URI url) throws Exception {
		var connection = new SocketModeConnection(adapter, members);
		try { CLIENT.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(url, connection).get(30, TimeUnit.SECONDS); }
		catch (Exception e) { connection.close(); throw e; }
		connection.keepAlive();
		return connection;
	}
	@Override public CompletionStage<Void> ended() { return ended; }
	@Override public void close() { end(null); }

	@Override public void onOpen(WebSocket ws) {
		socket = ws;
		if (ended.isDone()) ws.abort(); else ws.request(1);
	}
	@Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
		heard = System.nanoTime();
		if (message.length() + data.length() > MAX_MESSAGE) { end(new IllegalStateException(OVERSIZED)); return null; }
		message.append(data);
		if (!last) { ws.request(1); return null; }
		String text = message.toString();
		message.setLength(0);
		// Next message is requested only after the acknowledgement is sent, so sends never overlap.
		return CompletableFuture.supplyAsync(() -> handle(text), AAdapter.VIRTUAL_EXECUTOR)
			.thenCompose(ack -> ack == null ? CompletableFuture.completedFuture(ws) : ws.sendText(ack, true))
			.whenComplete((ignored, failure) -> { if (failure != null) end(failure); else ws.request(1); });
	}
	@Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer data) { heard = System.nanoTime(); ws.request(1); return null; }
	@Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer data) { heard = System.nanoTime(); ws.request(1); return null; }
	@Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
		// A close after hello is a routine refresh; before it, the connection never worked.
		end(hello ? null : new IllegalStateException(CLOSED));
		return null;
	}
	@Override public void onError(WebSocket ws, Throwable error) { end(error); }

	/** The acknowledgement to send, or null for none. Never throws. */
	private String handle(String text) {
		try {
			AMap<AString, ACell> envelope = MessagingSettings.object(JSON.parse(text));
			String type = MessagingSettings.required(envelope, "type");
			if (type.equals("hello")) {
				hello = true;
				// Two are normal while Slack hands over a refreshed connection; more mean another app token.
				log.debug("Slack Socket Mode connected; app has {} connection(s)", envelope.get(MessagingSettings.key("num_connections")));
				return null;
			}
			// Slack refreshes connections periodically and warns shortly before it closes one.
			if (type.equals("disconnect")) { end(null); return null; }
			String id = MessagingSettings.optional(envelope, "envelope_id");
			if (id == null) return null;
			// Other envelope types (slash commands, interactivity) are acknowledged and ignored.
			if (type.equals("events_api")) {
				List<WebhookBot<BotSpec>> bots = members.get();
				// The last binding is leaving and none can accept: Slack redelivers elsewhere or later.
				if (bots.isEmpty()) return null;
				try {
					var payload = MessagingSettings.object(envelope.get(MessagingSettings.key("payload")));
					boolean bound = false;
					for (WebhookBot<BotSpec> bot : bots) bound |= adapter.admit(bot, payload, ChannelAuthentication.PROVIDER_SESSION);
					if (!bound) log.debug("Slack Socket Mode: dropped an event for an installation without a binding");
				} catch (IllegalArgumentException e) {
					log.debug("Slack Socket Mode: dropped a malformed event");
				}
			}
			return JSON.print(Maps.of("envelope_id", id)).toString();
		} catch (IllegalArgumentException e) {
			return null;
		} catch (RuntimeException e) {
			// Not durably accepted by every binding: leave it unacknowledged so Slack
			// redelivers it; bindings that did accept deduplicate the redelivery.
			log.warn("Slack Socket Mode: event not accepted: {}", e.toString());
			return null;
		}
	}
	private void keepAlive() {
		long every = adapter.keepaliveMillis;
		keepalive = adapter.scheduleRetry(() -> {
			if (ended.isDone()) return;
			if (System.nanoTime() - heard > TimeUnit.MILLISECONDS.toNanos(2 * every)) { end(new IllegalStateException(SILENT)); return; }
			socket.sendPing(ByteBuffer.allocate(0));
			keepAlive();
		}, every);
	}
	private void end(Throwable failure) {
		boolean first = failure == null ? ended.complete(null) : ended.completeExceptionally(failure);
		if (!first) return;
		ScheduledFuture<?> ping = keepalive;
		if (ping != null) ping.cancel(false);
		WebSocket ws = socket;
		if (ws != null) ws.abort();
	}
}
