package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Central authorisation for a venue.
 *
 * <p>Phase 2 enforcement: job ownership check. Authenticated users can
 * access jobs where {@code :caller} matches their DID. Internal requests
 * bypass all checks. Jobs without a {@code :caller} field are venue-internal
 * and visible only to internal requests.</p>
 *
 * <p>Fine-grained capability checking (UCAN {@code with}/{@code can} pairs)
 * will be added in Phase 3/4.</p>
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
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) return false;          // anonymous
		return jobCaller.equals(callerDID);
	}

	/**
	 * Checks if the request can invoke a specific operation.
	 * Currently allows all — capability checking in Phase 3/4.
	 *
	 * @param ctx Request context with caller identity
	 * @param opMeta Operation metadata
	 * @return true if access is allowed
	 */
	public boolean canAccessOperation(RequestContext ctx, ACell opMeta) {
		return true;
	}
}
