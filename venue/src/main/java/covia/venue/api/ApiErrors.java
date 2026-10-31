package covia.venue.api;

import java.nio.charset.StandardCharsets;

import convex.api.ContentTypes;
import convex.core.util.JSON;
import io.javalin.http.Context;

/**
 * The REST API's error body (COG-21): {@code {"error": reason}}. The status is
 * the route's own: it reports how the API layer handled the request, never a
 * translation of whatever failed below it.
 */
public final class ApiErrors {

	/** Opening of the reason for a failure no route answered: a venue error. */
	public static final String INTERNAL_ERROR = "Internal server error: ";

	private ApiErrors() {}

	/** Writes an error response with the given 4xx or 5xx status and reason. */
	public static void write(Context ctx, int status, String reason) {
		String body = "{\"error\": \"" + JSON.escape(reason) + "\"}";
		ctx.status(status)
			.header("Content-Type", ContentTypes.JSON + "; charset=utf-8")
			.result(body.getBytes(StandardCharsets.UTF_8));
	}
}
