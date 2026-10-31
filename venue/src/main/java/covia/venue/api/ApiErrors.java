package covia.venue.api;

import java.nio.charset.StandardCharsets;

import convex.api.ContentTypes;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.exception.AuthException;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.RateLimitException;
import covia.exception.RemoteFetchException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.exception.WrongScopeException;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;

/**
 * The REST API's error responses (COG-21): one body, {@code {"error": reason}},
 * and the status for a failure that reaches the API layer without a route
 * having answered it. Routes that answer a failure themselves, such as
 * {@code /run} telling a refused call from a failed job, keep their own
 * mapping and write it through {@link #write}.
 */
public final class ApiErrors {

	/** Opening of the reason for a failure no Covia type explains: a venue error. */
	public static final String INTERNAL_ERROR = "Internal server error: ";

	private ApiErrors() {}

	/** Writes an error response with the given 4xx or 5xx status and reason. */
	public static void write(Context ctx, int status, String reason) {
		String body = "{\"error\": \"" + JSON.escape(reason) + "\"}";
		ctx.status(status)
			.header("Content-Type", ContentTypes.JSON + "; charset=utf-8")
			.result(body.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The status for a failure the API layer did not otherwise answer. A Covia
	 * type says what kind of refusal it is; anything else is a venue error.
	 */
	public static int status(Throwable failure) {
		if (failure instanceof HttpResponseException http) return http.getStatus();
		if (failure instanceof AuthException) return 403;
		if (failure instanceof NotFoundException) return 404;
		if (failure instanceof ConflictException) return 409;
		if (failure instanceof RateLimitException) return 429;
		if (failure instanceof RemoteFetchException || failure instanceof ResponseException) return 502;
		if (failure instanceof UnavailableException) return 503;
		if (failure instanceof InvalidInputException || failure instanceof NotSupportedException
				|| failure instanceof WrongScopeException) return 400;
		return 500;
	}

	/** Answers a failure with {@link #status} and its reason. */
	public static void respond(Context ctx, Throwable failure) {
		int status = status(failure);
		if (failure instanceof RateLimitException rate) {
			ctx.header("Retry-After", Long.toString(rate.getRetryAfterSeconds()));
		}
		String reason = (status == 500 || failure.getMessage() == null || failure.getMessage().isBlank())
			? INTERNAL_ERROR + AAdapter.describeFailure(failure)
			: failure.getMessage();
		write(ctx, status, reason);
	}
}
