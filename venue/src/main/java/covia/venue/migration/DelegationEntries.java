package covia.venue.migration;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;

/**
 * Part of venue version 1 to 2, after {@link JobEntries}: a remote job's record shows its caller only what the
 * caller can follow of its remote work, and how the venue follows it is the
 * venue's own, in the job's entry.
 *
 * <p>Every job record's {@code delegation} keeps its {@code remoteId} and
 * {@code remoteStatus}, and goes when it holds neither. An unfinished job's
 * {@code protocol}, {@code target} and {@code ownsRemote} are copied into its
 * entry's adapter data, beside the connection kept there. Any other field,
 * the observation health among them, is left behind: recovery observes the
 * job afresh.</p>
 */
public final class DelegationEntries implements MigrationStep {

	static final AString DELEGATION = Strings.intern("delegation");
	/** What the record keeps: what its caller can follow. */
	static final AString[] SHOWN = {Strings.intern("remoteId"), Strings.intern("remoteStatus")};
	/** What the entry keeps: how the venue follows it. */
	static final AString[] FOLLOWING = {Strings.intern("protocol"), Strings.intern("target"), Strings.intern("ownsRemote")};

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> value)) return venue;
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) value;
		if (!(record.get(Covia.USER_DATA) instanceof AMap<?, ?> found)) return venue;
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> users = (AMap<ACell, ACell>) found;
		Index<Blob, ACell> entries = index(record.get(Covia.JOBS));
		Index<Blob, ACell> before = entries;
		for (var user : found.entrySet()) {
			if (!(user.getValue() instanceof AMap<?, ?> state)) continue;
			if (!(state.get(Namespace.J) instanceof Index<?, ?> stored)) continue;
			Index<Blob, ACell> jobs = index(stored);
			Index<Blob, ACell> converted = jobs;
			for (long i = 0, n = jobs.count(); i < n; i++) {
				var job = jobs.entryAt(i);
				if (!(job.getValue() instanceof AMap<?, ?> m)) continue;
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> jobRecord = (AMap<AString, ACell>) m;
				AMap<AString, ACell> delegation = RT.ensureMap(jobRecord.get(DELEGATION));
				if (delegation == null) continue;
				AMap<AString, ACell> shown = select(delegation, SHOWN);
				AMap<AString, ACell> rest = shown.isEmpty() ? jobRecord.dissoc(DELEGATION) : jobRecord.assoc(DELEGATION, shown);
				if (!rest.equals(jobRecord)) converted = converted.assoc(job.getKey(), rest);

				AMap<AString, ACell> entry = RT.ensureMap(entries.get(job.getKey()));
				AMap<AString, ACell> following = select(delegation, FOLLOWING);
				if (entry == null || following.isEmpty()) continue;
				AMap<AString, ACell> data = RT.ensureMap(entry.get(JobIndex.ADAPTER_DATA));
				if (data == null) data = Maps.empty();
				for (var field : following.entrySet()) data = data.assoc(field.getKey(), field.getValue());
				entries = entries.assoc(job.getKey(), entry.assoc(JobIndex.ADAPTER_DATA, data));
			}
			if (converted != jobs) users = users.assoc(user.getKey(), assoc(state, Namespace.J, converted));
		}
		if (entries == before && users == found) return venue;
		AMap<ACell, ACell> result = record.assoc(Covia.USER_DATA, users);
		return (entries == before) ? result : result.assoc(Covia.JOBS, entries);
	}

	/** The fields of {@code map} that {@code fields} names, as they are. */
	private static AMap<AString, ACell> select(AMap<AString, ACell> map, AString[] fields) {
		AMap<AString, ACell> selected = Maps.empty();
		for (AString field : fields) {
			ACell v = map.get(field);
			if (v != null) selected = selected.assoc(field, v);
		}
		return selected;
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
