package covia.venue;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Central authorisation for a venue.
 *
 * <p>Job ownership enforcement. Authenticated users can
 * access jobs where {@code :caller} matches their DID. Internal requests
 * bypass all checks. Jobs without a {@code :caller} field are venue-internal
 * and visible only to internal requests.</p>
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
	 * <p>Ownership first: the job's {@code :caller} must be the request's user.
	 * Anonymous callers and jobs with no recorded caller are denied. Framework
	 * code that needs to bypass ownership (recovery, scheduler) goes through the
	 * no-ctx variants of {@link JobManager#getJobData(Blob)} and
	 * {@code deliverMessage} — trust is established by call path, not by a flag.</p>
	 *
	 * <p>Sharing the owner's namespace is proximity, not permission. A
	 * capability-scoped sub-principal (an agent with {@code caps}, a sudo
	 * delegate) reaches a job it launched itself, recorded as the job's
	 * {@code actor}, or one its authority covers for {@code ability} on
	 * {@code <owner>/j/<id>}.</p>
	 *
	 * @param ctx Request context with caller identity
	 * @param jobData Job record map
	 * @param ability {@code crud/read} to see the job, {@code crud/write} to manage it
	 * @return true if access is allowed
	 */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData, AString ability) {
		if (jobData == null) return false;
		AString jobCaller = RT.ensureString(jobData.get(Fields.CALLER));
		if (jobCaller == null) return false;          // venue-internal job
		// Jobs are owned by the user, so ownership compares on the user DID.
		AString userDID = ctx.getUserDID();
		if (userDID == null) return false;            // anonymous
		if (!jobCaller.equals(userDID)) return false;
		if (!ctx.isSubPrincipal() || ctx.getCaps() == null) return true;
		if (ctx.getCallerDID().equals(jobData.get(Fields.ACTOR))) return true;
		if (!(jobData.get(Fields.ID) instanceof Blob id)) return false;
		return engine.authorityCovers(ctx, Strings.create(userDID + "/j/" + id.toHexString()), ability);
	}

}
