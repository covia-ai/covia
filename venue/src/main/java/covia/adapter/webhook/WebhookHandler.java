package covia.adapter.webhook;

/**
 * Optional adapter HTTP ingress at {@code /webhooks/<adapter>/<binding>}.
 * The server dispatches only to active adapters implementing this interface.
 * No venue identity is inferred from an HTTP request or its path.
 *
 * <p>The implementation MUST authenticate the raw request, validate the app,
 * installation and destination binding, and admit its sender before effects.
 * It owns challenges, signature/timestamp checks, provider responses, and any
 * durable acceptance. Return promptly; never wait for an agent/operation here.
 * {@code WebhookInbox} can persist an accepted event before acknowledgement.</p>
 *
 * <p>This SPI uses only JDK types so optional modules need no Javalin/Servlet
 * dependency. A Socket Mode transport can feed the same inbox directly.</p>
 */
public interface WebhookHandler {
	WebhookResponse handleWebhook(String binding, WebhookRequest request) throws Exception;
}
