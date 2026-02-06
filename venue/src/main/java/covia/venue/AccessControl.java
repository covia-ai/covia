package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.lattice.cursor.ACursor;

/**
 * Central authorization class for a venue. Backed by a lattice cursor to the
 * :auth section of VenueLattice.
 *
 * <p>Currently a skeleton that allows all access. Will be populated with
 * role/capability logic per AUTH_PROPOSAL.md.
 */
public class AccessControl {

	private final ACursor<AMap<Keyword, ACell>> authCursor;

	public AccessControl(ACursor<AMap<Keyword, ACell>> authCursor) {
		this.authCursor = authCursor;
	}

	/**
	 * Checks if the request has a system capability.
	 * Currently allows all — to be implemented per AUTH_PROPOSAL.md.
	 */
	public boolean hasCapability(RequestContext ctx, Keyword capability) {
		if (ctx.isInternal()) return true;
		return true; // TODO: implement role → capability resolution
	}

	/**
	 * Checks if the request can access a specific operation.
	 * Currently allows all — to be implemented per AUTH_PROPOSAL.md.
	 */
	public boolean canAccessOperation(RequestContext ctx, ACell opMeta) {
		if (ctx.isInternal()) return true;
		return true; // TODO: implement operation requires check
	}

	/**
	 * Checks if the request can see/manage a specific job.
	 * Currently allows all — to be implemented per AUTH_PROPOSAL.md.
	 */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData) {
		if (ctx.isInternal()) return true;
		return true; // TODO: implement job visibility check
	}

	/**
	 * Gets the raw auth cursor for direct lattice access.
	 */
	public ACursor<AMap<Keyword, ACell>> getCursor() {
		return authCursor;
	}
}
