package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.api.Abilities;
import covia.grid.Attribution;

/** Shared resource checks for Job APIs and scheduled events. */
public class AccessControl {

	private final Engine engine;

	AccessControl(Engine engine) {
		this.engine = engine;
	}

	/** Whether the caller may read this Job through the Job API. */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData) {
		return canAccessJob(ctx, jobData, Abilities.JOB_READ);
	}

	/**
	 * Job abilities are independent of raw lattice CRUD. Ownership supplies
	 * ordinary standing, but never bypasses a scope or MCP token. Attribution
	 * as the creator is not a grant. Other principals need a valid delegation.
	 */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData, AString ability) {
		if (ctx == null || jobData == null) return false;
		AString owner = Attribution.owner(jobData);
		if (owner == null || ctx.getUserDID() == null) return false;
		// An incomplete record has no delegable resource; only its unscoped owner.
		if (!(jobData.get(Fields.ID) instanceof Blob id))
			return owner.equals(ctx.getUserDID()) && isOwnerOutright(ctx) && ctx.getTokens().isEmpty();
		AString resource = JobManager.resource(owner, id);
		AString destination = Strings.create(engine.config().getBaseUrl());
		RequestContext authority = ctx.authorityContext();
		if (!owner.equals(authority.getUserDID()) && !owner.equals(authority.getCallerDID())
				&& !engine.crossUserAllows(ctx, resource, ability, destination)) return false;
		return engine.authorityCovers(ctx, resource, ability, destination);
	}

	/**
	 * Checks if the request can see or manage a scheduled event record.
	 *
	 * <p>Ownership first: the event's owner must be the request's user. Sharing the owner's namespace is proximity, not permission: a
	 * context with a capability scope reaches only an event it scheduled itself as
	 * a principal other than the owner, recorded as the event's {@code caller}.
	 * Scheduled events are not a capability resource, so no caps extend that
	 * reach.</p>
	 *
	 * @param ctx Request context with caller identity
	 * @param event Scheduled event record
	 * @return true if access is allowed
	 */
	public boolean canAccessEvent(RequestContext ctx, AMap<AString, ACell> event) {
		if (event == null) return false;
		AString userDID = ctx.getUserDID();
		if (userDID == null || !userDID.equals(Attribution.owner(event))) return false;
		if (isOwnerOutright(ctx)) return true;
		return madeItFor(ctx, event);
	}

	/**
	 * Whether the request acts with its namespace owner's whole authority: the
	 * authority it acts with ({@link RequestContext#authorityContext}) is that
	 * owner's, unscoped. A request acting with a separate authority is the owner
	 * outright only when that authority is.
	 */
	private static boolean isOwnerOutright(RequestContext ctx) {
		RequestContext authority = ctx.authorityContext();
		return authority.getCaps() == null && ctx.getUserDID().equals(authority.getUserDID());
	}

	/**
	 * Whether {@code ctx}'s caller made {@code record} for the owner whose
	 * namespace it acts in: a sub-principal (an agent or a delegate) named as the
	 * record's caller. The owner itself under a scope is not such a caller, so its
	 * scope alone decides, and nor is a request an MCP token narrows, whose
	 * caller is the token's issuer: the token's caps decide.
	 */
	private static boolean madeItFor(RequestContext ctx, AMap<AString, ACell> record) {
		AString caller = ctx.getCallerDID();
		return ctx.getTokens().isEmpty() && !caller.equals(ctx.getUserDID()) && caller.equals(Attribution.caller(record));
	}

}
