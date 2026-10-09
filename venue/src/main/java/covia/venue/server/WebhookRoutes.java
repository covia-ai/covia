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
		byte[] body = ctx.req().getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			ctx.status(413).result(TOO_LARGE);
			return;
		}
		// Recheck after reading; do not retain a receiver between requests. An
		// already-dispatched request follows the normal in-flight module semantics.
		if (engine.getAdapter(adapter.getName()) != adapter) {
			ctx.status(404).result(UNAVAILABLE);
			return;
		}
		WebhookResponse response = webhook.handleWebhook(ctx.pathParam("binding"),
			new WebhookRequest(ctx.method().name(), ctx.headerMap(), ctx.queryParamMap(), body));
		ctx.status(response.status()).contentType(response.contentType());
		response.headers().forEach(ctx::header);
		ctx.result(response.body());
	}
}
