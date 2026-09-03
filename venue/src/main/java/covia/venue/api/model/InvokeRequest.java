package covia.venue.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class InvokeRequest {
	/** Operation reference: catalog path (v/ops/...), asset hash, o/ pin, or DID URL */
	String operation;
	/** Operation input, per the operation's declared input schema */
	Object input;
	/** For {@code /run} only: force the internal Job wrapper to remain transient.
	 *  Requires venue config {@code enablePrivateJobs: true}. The request stays
	 *  open until completion and returns only the operation result, never a Job ID. */
	@JsonProperty("private")
	Boolean privateJob;
	/** Optional wait window for a synchronous response: {@code true} blocks up
	 *  to the 120s cap; an integer blocks up to that many milliseconds (clamped
	 *  to the cap). If the job finishes in the window the finished record is
	 *  returned (200); otherwise the current record (201) for the caller to
	 *  poll. Default absent — asynchronous. May also be supplied as the
	 *  {@code ?wait=} query parameter. */
	Object wait;
}
