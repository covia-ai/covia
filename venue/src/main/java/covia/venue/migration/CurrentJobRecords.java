package covia.venue.migration;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * Part of venue version 1 to 2, applied first: a job record holds only its
 * current state. A venue before 0.10.0 chained a job's earlier states under a
 * top-level {@code prev}; it goes from every job of every account, so the
 * parts after this one, and every reader, see current records only. The
 * chain itself is never walked: its depth and contents are unbounded.
 */
public final class CurrentJobRecords implements MigrationStep {

	/** The obsolete field that chained a job's earlier states. */
	static final AString PREV = Strings.intern("prev");

	@Override
	@SuppressWarnings("unchecked")
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> value)) return venue;
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) value;
		if (!(record.get(Covia.USER_DATA) instanceof AMap<?, ?> found)) return venue;
		AMap<ACell, ACell> users = (AMap<ACell, ACell>) found;
		AMap<ACell, ACell> converted = users;
		for (var user : users.entrySet()) {
			if (!(user.getValue() instanceof AMap<?, ?> state)) continue;
			if (!(state.get(Namespace.J) instanceof Index<?, ?> stored)) continue;
			Index<Blob, ACell> jobs = (Index<Blob, ACell>) stored;
			Index<Blob, ACell> current = jobs;
			for (long i = 0, n = jobs.count(); i < n; i++) {
				var job = jobs.entryAt(i);
				if (job.getValue() instanceof AMap<?, ?> m && m.containsKey(PREV)) {
					current = current.assoc(job.getKey(), ((AMap<AString, ACell>) m).dissoc(PREV));
				}
			}
			if (current != jobs) {
				converted = converted.assoc(user.getKey(), ((AMap<ACell, ACell>) state).assoc(Namespace.J, current));
			}
		}
		return (converted == users) ? venue : record.assoc(Covia.USER_DATA, converted);
	}
}
