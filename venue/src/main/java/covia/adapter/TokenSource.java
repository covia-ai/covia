package covia.adapter;

import java.io.IOException;

import covia.venue.RequestContext;

/**
 * A currently valid access token for a user's connected account — the seam
 * by which {@code http:*} turns {@code bearerSecret: "oauth/<provider>"} into
 * an {@code Authorization} header without the token ever passing through a
 * model or a job record. Implemented by the {@code oauth} adapter; found via
 * {@link covia.venue.Engine#findAdapter(Class)}.
 */
public interface TokenSource {

	/** The reference prefix a caller uses in place of an {@code s/NAME} secret. */
	String PREFIX = "oauth/";

	/**
	 * The caller's access token for the provider, refreshed first when it has
	 * expired. Throws {@link IllegalArgumentException} with an actionable
	 * message when the caller has no connection to the provider or the
	 * connection can no longer be refreshed.
	 */
	String accessToken(RequestContext ctx, String provider) throws IOException;
}
