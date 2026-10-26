package covia.adapter.messaging;

import java.util.concurrent.CompletionStage;

/**
 * A provider event connection owned by one binding (e.g. Slack Socket Mode).
 * The provider authenticates it, feeds accepted events to
 * {@link WebhookBot#accept} and acknowledges only after that returns.
 * {@link WebhookBot} opens, replaces and closes it; nothing here is HTTP.
 */
public interface MessagingConnection extends AutoCloseable {
	/** Completes normally when the provider asks for a fresh connection, exceptionally on failure. */
	CompletionStage<Void> ended();
	/** Idempotent and non-blocking. */
	@Override void close();
}
