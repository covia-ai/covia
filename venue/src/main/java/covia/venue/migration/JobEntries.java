package covia.venue.migration;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;

/**
 * Venue version 2 to 3: the venue keeps an entry for each unfinished job in
 * {@code :jobs} ({@link JobIndex}), and a restart recovers jobs from those
 * entries alone. Each unfinished job record of a live account gets one naming
 * its owner, and the proofs a record kept for work that continues move from
 * the record into the entry's caller context.
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
		Index<Blob, ACell> entries = index(record.get(Covia.JOBS));
		Index<Blob, ACell> before = entries;
		ACell storedUsers = record.get(Covia.USER_DATA);
		if (storedUsers instanceof AMap<?, ?> found) {
			@SuppressWarnings("unchecked")
			AMap<ACell, ACell> users = (AMap<ACell, ACell>) found;
			for (var user : users.entrySet()) {
				if (!(user.getKey() instanceof AString did)) continue;
				if (RT.getIn(user.getValue(), Covia.K_DELETED) != null) continue;
				if (!(user.getValue() instanceof AMap<?, ?> state)) continue;
				if (!(state.get(Namespace.J) instanceof Index<?, ?> stored)) continue;
				Index<Blob, ACell> jobs = index(stored);
				Index<Blob, ACell> converted = jobs;
				for (long i = 0, n = jobs.count(); i < n; i++) {
					var job = jobs.entryAt(i);
					Blob id = job.getKey();
					if (entries.containsKey(id) || !(job.getValue() instanceof AMap<?, ?> m)) continue;
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> jobRecord = (AMap<AString, ACell>) m;
					AMap<AString, ACell> current = Job.currentState(jobRecord);
					if (current == null || Job.isFinished(current)) continue;
					AMap<AString, ACell> entry = Maps.of(JobIndex.OWNER, did);
					ACell proofs = jobRecord.get(Fields.PROOFS);
					if (proofs != null) {
						entry = entry.assoc(JobIndex.CONTEXT, Maps.of(Fields.PROOFS, proofs));
						converted = converted.assoc(id, jobRecord.dissoc(Fields.PROOFS));
					}
					entries = entries.assoc(id, entry);
				}
				if (converted != jobs) users = users.assoc(did, assoc(state, Namespace.J, converted));
			}
			if (users != found) record = record.assoc(Covia.USER_DATA, users);
		}
		return (entries == before && record == value) ? venue : record.assoc(Covia.JOBS, entries);
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> index(ACell jobs) {
		return (jobs instanceof Index<?, ?> index) ? (Index<Blob, ACell>) index : Index.none();
	}

	@SuppressWarnings("unchecked")
	private static ACell assoc(AMap<?, ?> map, ACell key, ACell value) {
		return ((AMap<ACell, ACell>) map).assoc(key, value);
	}
}
