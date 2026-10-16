package covia.venue.server;

import java.util.concurrent.CompletableFuture;

import convex.core.data.AString;
import covia.adapter.AAdapter;
import covia.adapter.relay.RelayHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.Engine;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/**
 * Fixed relay routes with dynamic adapter lookup, the counterpart of {@link WebhookRoutes}
 * for the principals a relay serves: {@code GET|POST /relay/{adapter}/{binding}/{action}}.
 * The caller must present a credential the venue accepts; it is not admitted as a venue
 * user. Works across runtime module load, disable, enable and unload.
 */
final class RelayRoutes {
	static final String ROUTE = "/relay/{adapter}/{binding}/{action}";
	static final int MAX_BODY_BYTES = WebhookRoutes.MAX_BODY_BYTES;
	static final String UNAVAILABLE = "Relay endpoint is unavailable";
	static final String TOO_LARGE = "Relay request body is too large";
	static final String AUTH_REQUIRED = "Authentication required";
	static final String FAILED = "Relay request failed";
	private final Engine engine;

	private RelayRoutes(Engine engine) { this.engine = engine; }

	static void addRoutes(RoutesConfig routes, Engine engine) {
		RelayRoutes relay = new RelayRoutes(engine);
		routes.get(ROUTE, relay::handle, VenueRouteFeature.AUTHENTICATED_IDENTITY, VenueRouteFeature.RATE_LIMITED);
		routes.post(ROUTE, relay::handle, VenueRouteFeature.AUTHENTICATED_IDENTITY, VenueRouteFeature.RATE_LIMITED);
	}

	private void handle(Context ctx) throws Exception {
		AAdapter adapter = engine.getAdapter(ctx.pathParam("adapter"));
		if (!(adapter instanceof RelayHandler relay)) {
			ctx.status(404).result(UNAVAILABLE);
			return;
		}
		AString caller = AuthMiddleware.getVenueUserDID(ctx);
		if (caller == null) caller = AuthMiddleware.getAuthenticatedIdentity(ctx);
		if (caller == null) {
			ctx.status(401).result(AUTH_REQUIRED);
			return;
		}
		WebhookRequest request = WebhookRoutes.read(ctx, engine, adapter, UNAVAILABLE, TOO_LARGE);
		if (request == null) return;
		CompletableFuture<WebhookResponse> answer;
		try {
			answer = relay.handleRelay(ctx.pathParam("binding"), ctx.pathParam("action"), caller, request);
		} catch (RuntimeException e) {
			answer = CompletableFuture.failedFuture(e);
		}
		CompletableFuture<WebhookResponse> settled = answer == null
			? CompletableFuture.completedFuture(WebhookResponse.text(404, UNAVAILABLE)) : answer;
		// The handler's future holds the response, so a long poll holds no server thread.
		ctx.future(() -> settled.handle((response, failure) -> {
			WebhookRoutes.answer(ctx, failure != null || response == null ? WebhookResponse.text(500, FAILED) : response);
			return null;
		}));
	}
}
