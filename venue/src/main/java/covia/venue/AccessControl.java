package covia.venue;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.Attribution;

/**
 * Central authorisation for a venue.
 *
 * <p>Job ownership enforcement. A request reaches a job whose owner (its
 * {@code owner}, else its {@code caller}) is the request's user. Jobs with no
 * {@code caller} are venue-internal and visible only to internal requests.</p>
 *
 * <p>Fine-grained operation capabilities are enforced at adapter action
 * points through {@link RequestContext#requireCapability}; they do not belong
 * in this ownership-only helper.</p>
 */
public class AccessControl {

	private final Engine engine;

	AccessControl(Engine engine) {
		this.engine = engine;
	}

	/**
	 * Checks if the request can see a specific job: {@link #canAccessJob(RequestContext,
	 * AMap, AString)} for {@code crud/read}.
	 */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData) {
		return canAccessJob(ctx, jobData, Capability.CRUD_READ);
	}

	/**
	 * Checks if the request can see ({@code crud/read}) or manage ({@code crud/write})
	 * a specific job.
	 *
	 * <p>Ownership first: the job's owner must be the request's user.
	 * Anonymous callers and jobs with no recorded caller are denied. Framework
	 * code that needs to bypass ownership (recovery, scheduler) goes through the
	 * no-ctx variants of {@link JobManager#getJobData(Blob)} and
	 * {@code deliverMessage} — trust is established by call path, not by a flag.</p>
	 *
	 * <p>Sharing the owner's namespace is proximity, not permission. A context
	 * with a capability scope (an agent with {@code caps}, a sudo delegate, the
	 * venue's public principal under {@code auth.public.caps}) reaches a job it
	 * made as a principal other than the owner, recorded as the job's
	 * {@code caller}, or one its authority covers for {@code ability} on
	 * {@code <owner>/j/<id>}. Only an unscoped context is the owner outright, so
	 * a scope on the owner itself (the shared public principal, say) reaches
	 * only what its authority covers.</p>
	 *
	 * @param ctx Request context with caller identity
	 * @param jobData Job record map
	 * @param ability {@code crud/read} to see the job, {@code crud/write} to manage it
	 * @return true if access is allowed
	 */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData, AString ability) {
		if (jobData == null) return false;
		AString owner = Attribution.owner(jobData);
		if (owner == null) return false;              // venue-internal job
		// Jobs are owned by the user, so ownership compares on the user DID.
		AString userDID = ctx.getUserDID();
		if (userDID == null) return false;            // anonymous
		if (!owner.equals(userDID)) return false;
		if (ctx.getCaps() == null) return true;
		if (madeItFor(ctx, jobData)) return true;
		if (!(jobData.get(Fields.ID) instanceof Blob id)) return false;
		return engine.authorityCovers(ctx, Strings.create(userDID + "/j/" + id.toHexString()), ability);
	}

	/**
	 * Checks if the request can see or manage a scheduled event record.
	 *
	 * <p>Ownership first: the event's owner must be the request's user. As for
	 * jobs, sharing the owner's namespace is proximity, not permission: a
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
		if (ctx.getCaps() == null) return true;
		return madeItFor(ctx, event);
	}

	/**
	 * Whether {@code ctx}'s caller made {@code record} for the owner whose
	 * namespace it acts in: a sub-principal (an agent or a delegate) named as the
	 * record's caller. The owner itself under a scope is not such a caller, so its
	 * scope alone decides.
	 */
	private static boolean madeItFor(RequestContext ctx, AMap<AString, ACell> record) {
		AString caller = ctx.getCallerDID();
		return !caller.equals(ctx.getUserDID()) && caller.equals(Attribution.caller(record));
	}

}
