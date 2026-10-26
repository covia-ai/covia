package covia.adapter.relay;

import java.util.concurrent.CompletableFuture;

import convex.core.data.AString;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;

/**
 * Optional SPI for an adapter that relays provider messages to downstream principals:
 * a hub venue holds one provider binding, and the venues or clients it serves call
 * {@code GET|POST /relay/{adapter}/{binding}/{action}} with their own DID to link,
 * collect messages and reply. The route authenticates the caller's DID (a venue user
 * is not required) and looks the adapter up on every request, as webhook routes do.
 *
 * <p>The response is a future so an action such as a long poll can wait without
 * holding a server thread. The caller is authenticated; what it may do is the
 * adapter's decision. JDK types only, so modules depend on no web framework.
 */
public interface RelayHandler {

	/**
	 * Handle one relay action.
	 *
	 * @param binding the relay binding named in the path
	 * @param action  the action named in the path, such as {@code poll}
	 * @param caller  the authenticated caller's DID
	 * @param request the raw request, body capped by the route
	 */
	CompletableFuture<WebhookResponse> handleRelay(String binding, String action, AString caller, WebhookRequest request);
}
