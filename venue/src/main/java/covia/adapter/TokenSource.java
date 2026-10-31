package covia.adapter;

import java.io.IOException;

import convex.core.data.AString;
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
	 * expired, for a request to {@code destination}. Releasing it is a use of
	 * the connection {@code <did>/oauth/<provider>}, so a capability-scoped
	 * caller needs {@code oauth/use} there, with any {@code nb.url} covering
	 * {@code destination}. Refusals carry an actionable message.
	 *
	 * @throws covia.exception.NotFoundException if the caller has no connection to the provider
	 * @throws covia.exception.AuthException if the connection has expired and cannot be refreshed
	 * @throws covia.exception.UnavailableException if the provider did not refresh it
	 */
	String accessToken(RequestContext ctx, String provider, String destination) throws IOException;

	/** Whether the user has a connection to the provider: presence only, never a token. */
	boolean connected(AString userDID, String provider);
}
