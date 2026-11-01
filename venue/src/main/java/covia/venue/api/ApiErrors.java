package covia.venue.api;

import java.nio.charset.StandardCharsets;

import convex.core.util.JSON;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The REST API's error body (COG-21): an RFC 9457 problem details object,
 * {@code {"title", "status", "detail", "error"}}. {@code type} is left out,
 * which means {@code about:blank}: the status identifies the problem and
 * {@code title} is its reason phrase. {@code detail} is the reason, and the
 * extension member {@code error} repeats it. The status is the route's own: it
 * reports how the API layer handled the request, never a translation of
 * whatever failed below it.
 */
public final class ApiErrors {

	/** Media type of an error body. */
	public static final String PROBLEM_JSON = "application/problem+json";

	/** Opening of the reason for a failure no route answered: a venue error. */
	public static final String INTERNAL_ERROR = "Internal server error: ";

	private ApiErrors() {}

	/** Writes an error response with the given 4xx or 5xx status and reason. */
	public static void write(Context ctx, int status, String reason) {
		ctx.status(status)
			.header("Content-Type", PROBLEM_JSON + "; charset=utf-8")
			.result(body(status, reason).getBytes(StandardCharsets.UTF_8));
	}

	/** The error body for a status and reason, as JSON. */
	public static String body(int status, String reason) {
		StringBuilder sb = new StringBuilder("{");
		HttpStatus known = HttpStatus.forStatus(status);
		if (known != HttpStatus.UNKNOWN) {
			sb.append("\"title\": \"").append(JSON.escape(known.getMessage())).append("\", ");
		}
		String detail = JSON.escape(reason).toString();
		return sb.append("\"status\": ").append(status)
			.append(", \"detail\": \"").append(detail)
			.append("\", \"error\": \"").append(detail).append("\"}")
			.toString();
	}
}
