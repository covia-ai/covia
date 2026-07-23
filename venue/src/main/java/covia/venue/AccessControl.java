package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
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

	/**
	 * Checks if the request can see/manage a specific job.
	 *
	 * <p>Pure ownership check: the caller's DID must match the job's
	 * {@code :caller} field. Anonymous callers and jobs with no recorded
	 * caller are denied. Framework code that needs to bypass ownership
	 * (recovery, scheduler) goes through the no-ctx variants of
	 * {@link JobManager#getJobData(Blob)} and {@code deliverMessage} —
	 * trust is established by call path, not by a flag.</p>
	 *
	 * @param ctx Request context with caller identity
	 * @param jobData Job record map
	 * @return true if access is allowed
	 */
	public boolean canAccessJob(RequestContext ctx, AMap<AString, ACell> jobData) {
		if (jobData == null) return false;
		AString jobCaller = RT.ensureString(jobData.get(Fields.CALLER));
		if (jobCaller == null) return false;          // venue-internal job
		// Jobs are owned by the user, so ownership compares on the user DID: an
		// agent sub-principal can reach the jobs it ran for its owner.
		AString callerDID = ctx.getUserDID();
		if (callerDID == null) return false;          // anonymous
		return jobCaller.equals(callerDID);
	}

}
