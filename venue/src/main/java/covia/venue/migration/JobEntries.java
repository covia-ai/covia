package covia.venue.migration;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;

/**
 * Venue version 2 to 3: the venue keeps an entry for each unfinished job in
 * {@code :jobs} ({@link JobIndex}), and a restart recovers jobs from those
 * entries alone. Each unfinished job record of a live account gets one naming
 * its owner.
 *
 * <p>An entry made by this step keeps no output rules: they are what the
 * job's operation declared when it was admitted, and a step cannot resolve an
 * operation. So a job admitted before the upgrade hides no further secret
 * fields and has no output schema checked when it finishes.</p>
 */
public final class JobEntries implements MigrationStep {

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> value)) return venue;
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) value;
		Index<Blob, ACell> entries = existing(record.get(Covia.JOBS));
		Index<Blob, ACell> before = entries;
		if (record.get(Covia.USER_DATA) instanceof AMap<?, ?> users) {
			for (var user : users.entrySet()) {
				if (!(user.getKey() instanceof AString did)) continue;
				if (RT.getIn(user.getValue(), Covia.K_DELETED) != null) continue;
				if (!(RT.getIn(user.getValue(), Namespace.J) instanceof Index<?, ?> jobs)) continue;
				for (long i = 0, n = jobs.count(); i < n; i++) {
					var job = jobs.entryAt(i);
					if (!(job.getKey() instanceof Blob id) || entries.containsKey(id)) continue;
					if (!(job.getValue() instanceof AMap<?, ?> stored)) continue;
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> current = Job.currentState((AMap<AString, ACell>) stored);
					if (current == null || Job.isFinished(current)) continue;
					entries = entries.assoc(id, Maps.of(JobIndex.OWNER, did));
				}
			}
		}
		return (entries == before) ? venue : record.assoc(Covia.JOBS, entries);
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> existing(ACell jobs) {
		return (jobs instanceof Index<?, ?> index) ? (Index<Blob, ACell>) index : Index.none();
	}
}
