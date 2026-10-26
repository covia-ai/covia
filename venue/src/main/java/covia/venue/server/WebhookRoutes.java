package covia.venue.server;

import covia.adapter.AAdapter;
import covia.adapter.webhook.WebhookHandler;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.venue.Engine;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/** Fixed routes with dynamic adapter lookup: works for runtime module load/disable/unload. */
final class WebhookRoutes {
	static final String ROUTE = "/webhooks/{adapter}/{binding}";
	static final int MAX_BODY_BYTES = 1024 * 1024;
	static final String UNAVAILABLE = "Webhook endpoint is unavailable";
	static final String TOO_LARGE = "Webhook request body is too large";
	private final Engine engine;

	private WebhookRoutes(Engine engine) { this.engine = engine; }

	static void addRoutes(RoutesConfig routes, Engine engine) {
		WebhookRoutes webhook = new WebhookRoutes(engine);
		// Provider signatures supply authentication. Rate limiting is still useful
		// on a private venue; COVIA_API would incorrectly demand a venue bearer.
		routes.get(ROUTE, webhook::handle, VenueRouteFeature.RATE_LIMITED);
		routes.post(ROUTE, webhook::handle, VenueRouteFeature.RATE_LIMITED);
	}

	private void handle(Context ctx) throws Exception {
		AAdapter adapter = engine.getAdapter(ctx.pathParam("adapter"));
		if (!(adapter instanceof WebhookHandler webhook)) {
			ctx.status(404).result(UNAVAILABLE);
			return;
		}
		WebhookRequest request = read(ctx, engine, adapter, UNAVAILABLE, TOO_LARGE);
		if (request == null) return;
		answer(ctx, webhook.handleWebhook(ctx.pathParam("binding"), request));
	}

	/**
	 * The request, its body capped, or null once answered with 413 or (when the
	 * adapter was disabled, replaced or removed while the body was read) 404.
	 * Shared by the routes that look an adapter up on every request.
	 */
	static WebhookRequest read(Context ctx, Engine engine, AAdapter adapter, String unavailable, String tooLarge) throws Exception {
		byte[] body = ctx.req().getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			ctx.status(413).result(tooLarge);
			return null;
		}
		// Recheck after reading; do not retain a receiver between requests. An
		// already-dispatched request follows the normal in-flight module semantics.
		if (engine.getAdapter(adapter.getName()) != adapter) {
			ctx.status(404).result(unavailable);
			return null;
		}
		return new WebhookRequest(ctx.method().name(), ctx.headerMap(), ctx.queryParamMap(), body);
	}

	static void answer(Context ctx, WebhookResponse response) {
		ctx.status(response.status()).contentType(response.contentType());
		response.headers().forEach(ctx::header);
		ctx.result(response.body());
	}
}
