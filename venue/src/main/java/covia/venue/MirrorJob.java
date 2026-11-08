package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;

/**
 * A recorded Job standing for a call that has no record of its own
 * ({@link JobManager#mirror}). No adapter runs it: its work is the call, so
 * cancelling it cancels the call, with its reason.
 */
final class MirrorJob extends VenueJob {

	private final Job call;

	MirrorJob(JobRecord record, AMap<AString, ACell> data, OutputRules rules,
			AString ownerDID, JobManager manager, Job call) {
		super(record, data, rules, ownerDID, manager, null);
		this.call = call;
	}

	@Override
	protected void stopWork() {
		AString reason = RT.ensureString(getData().get(Fields.ERROR));
		call.cancel((reason != null) ? reason.toString() : null);
	}
}
