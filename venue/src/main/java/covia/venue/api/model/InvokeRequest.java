package covia.venue.api.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class InvokeRequest {
	/** Operation reference: catalog path (v/ops/...), asset hash, o/ pin, or DID URL */
	String operation;
	/** Operation input, per the operation's declared input schema */
	Object input;
	/** Optional: true to block until the job completes (up to 120s) and return
	 *  the finished record with status 200. Default false — the invocation is
	 *  asynchronous and returns 201 with a job record to poll. May also be
	 *  supplied as the {@code ?wait=true} query parameter. */
	Boolean wait;
}
