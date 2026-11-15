package covia.adapter.token;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.venue.RequestContext;

/**
 * An adapter that accepts access tokens (ACCESS_TOKENS.md). Its name is the
 * key of its entry in a token's {@code access}; a token is accepted there
 * only while it has that entry and the adapter is running. The entry may
 * narrow what the token's core allows, never widen it, and only this adapter
 * reads it.
 */
public interface TokenAccessPoint {

	/**
	 * The entry a token keeps for this access point, from the one a caller asks
	 * for, checked with the caller's authority.
	 *
	 * @param ctx the request creating or changing the token
	 * @param requested what the caller gave for this access point
	 * @return the entry to store
	 * @throws covia.exception.InvalidInputException saying what is wrong with it
	 */
	AMap<AString, ACell> tokenEntry(RequestContext ctx, ACell requested);
}
