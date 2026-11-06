package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.lattice.ALattice;
import convex.lattice.LatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.grid.Job;
import covia.lattice.ACoviaComponent;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;
import covia.lattice.Position;
import covia.lattice.ValuePath;
import covia.lattice.Workspace;

/**
 * A user's jobs ({@code j/}): job records keyed by job id, each the
 * {@link JobState} of one job.
 *
 * <p>Only the venue admits jobs, through {@link #create}, and every record is
 * read in its current state. Callers name a job by its hex id, which this
 * component turns into the stored key. Transactions compose admissions,
 * removals and job edits; execution and notifications belong to the runtime
 * after the transaction commits.</p>
 */
public final class Jobs extends ACoviaComponent<Index<Blob, ACell>>
		implements TransactionalComponent<Index<Blob, ACell>, Jobs> {

	private static final ALattice<ACell> LATTICE = Covia.USER_RECORD.path(Namespace.J);

	private final VenueContext context;

	Jobs(VenueContext context, AForkableCursor<Index<Blob, ACell>> cursor) {
		super(cursor);
		this.context = context;
	}

	@Override
	public Jobs withCursor(AForkableCursor<Index<Blob, ACell>> draft) {
		return new Jobs(context, draft);
	}

	/** Every job as callers see it ({@link JobState#view}), keyed by job id; empty when there are none. */
	public Index<Blob, ACell> getAll() {
		Index<Blob, ACell> jobs = cursor().get();
		return (jobs == null) ? Index.none() : views(jobs);
	}

	/** Each job as callers see it ({@link JobState#view}), without writing to storage. */
	private static Index<Blob, ACell> views(Index<Blob, ACell> records) {
		Index<Blob, ACell> result = records;
		for (var entry : records.entrySet()) {
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			if (record == null) continue;
			AMap<AString, ACell> view = JobState.view(record);
			if (view != record) result = result.assoc(entry.getKey(), view);
		}
		return result;
	}

	/** Counts the jobs without reading their records. */
	public long count() {
		Index<Blob, ACell> jobs = cursor().get();
		return (jobs == null) ? 0 : jobs.count();
	}

	/**
	 * One job. A job removed while a caller holds it reads as null, and its
	 * updates change nothing.
	 *
	 * @param id Job id
	 * @return The job, bound to this cursor or transaction draft, or null if
	 *         there is no such job
	 */
	public JobState get(Blob id) {
		Index<Blob, ACell> jobs = cursor().get();
		return (jobs != null && jobs.containsKey(id)) ? state(id) : null;
	}

	/**
	 * One job's record as callers see it ({@link JobState#view}).
	 *
	 * @param id Job id
	 * @return The record, or null if there is no such job
	 */
	public AMap<AString, ACell> getRecord(Blob id) {
		Index<Blob, ACell> jobs = cursor().get();
		return (jobs == null) ? null : JobState.view(RT.ensureMap(jobs.get(id)));
	}

	/**
	 * Admits a job: creates its record exactly once.
	 *
	 * @param id Job id
	 * @param record Job record
	 * @return The new job, or null if the job already existed
	 */
	JobState create(Blob id, AMap<AString, ACell> record) {
		AMap<AString, ACell> current = Job.currentState(record);
		Index<Blob, ACell> before = cursor().getAndUpdate(jobs -> {
			if (jobs == null) return Index.<Blob, ACell>none().assoc(id, current);
			return jobs.containsKey(id) ? jobs : jobs.assoc(id, current);
		});
		return (before == null || !before.containsKey(id)) ? state(id) : null;
	}

	/**
	 * A job's scratch area ({@code temp}), which reads as absent and refuses
	 * changes while the job does not exist.
	 *
	 * @param id Job id
	 * @return The job's scratch area
	 */
	public Workspace scratch(Blob id) {
		return state(id).workspace();
	}

	private JobState state(Blob id) {
		return new JobState(context, LatticeComponent.resolve(cursor(), LATTICE, id));
	}

	/**
	 * Removes a job's record, if present. The removal is durable: the venue
	 * merge is whole-value LWW, so the post-delete snapshot wins and the record
	 * is not resurrected on sync.
	 *
	 * @param id Job id
	 * @return true if the job existed and was removed
	 */
	public boolean remove(Blob id) {
		Index<Blob, ACell> before = cursor().getAndUpdate(jobs -> (jobs == null) ? null : jobs.dissoc(id));
		return before != null && before.containsKey(id);
	}

	/** The jobs as callers see them; null when there are none. */
	@Override
	public ACell read() {
		Index<Blob, ACell> jobs = cursor().get();
		return (jobs == null) ? null : views(jobs);
	}

	/** A job is named by its id in hex, whether or not it exists; a segment that is not hex names no job. */
	@Override
	public Position resolve(CoviaPath path) {
		if (path.isEmpty()) return super.resolve(path);
		Blob id = ValuePath.blobKey(path.first());
		if (id == null) return Position.invalid(path);
		return state(id).resolve(path.rest());
	}
}
