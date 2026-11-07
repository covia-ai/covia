package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.lattice.NoSuchPathException;
import convex.core.lang.RT;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.grid.Job;
import covia.lattice.ACoviaComponent;
import covia.lattice.AccessPolicy;
import covia.lattice.Namespace;
import covia.lattice.Owner;
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
		implements TransactionalComponent<Index<Blob, ACell>, Jobs>, Owner {

	private final VenueContext context;
	private final AString owner;
	/** Who may use the list of jobs: see {@link #resolve(AString[], int, int)}. */
	private final AccessPolicy policy;

	/**
	 * @param owner DID of the user whose jobs these are, or null outside any user
	 */
	Jobs(VenueContext context, AForkableCursor<Index<Blob, ACell>> cursor, AString owner) {
		super(cursor);
		this.context = context;
		this.owner = owner;
		this.policy = (owner == null) ? null : jobsPolicy(owner);
	}

	private static AccessPolicy jobsPolicy(AString owner) {
		String base = owner + "/" + Namespace.J;
		return (ctx, ability, path) -> AccessPolicy.Need.authorityOver(AccessPolicy.address(base, path));
	}

	@Override
	public Jobs withCursor(AForkableCursor<Index<Blob, ACell>> draft) {
		return new Jobs(context, draft, owner);
	}

	/** Every job in its current state, keyed by job id; empty when there are none. */
	public Index<Blob, ACell> getAll() {
		Index<Blob, ACell> jobs = cursor().getOrDefault(null);
		return (jobs == null) ? Index.none() : current(jobs);
	}

	/** Each job in its current state ({@link Job#currentState}), without writing to storage. */
	private static Index<Blob, ACell> current(Index<Blob, ACell> records) {
		Index<Blob, ACell> result = records;
		for (var entry : records.entrySet()) {
			AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
			if (record == null) continue;
			AMap<AString, ACell> current = Job.currentState(record);
			if (current != record) result = result.assoc(entry.getKey(), current);
		}
		return result;
	}

	/** Counts the jobs without reading their records. */
	public long count() {
		Index<Blob, ACell> jobs = cursor().getOrDefault(null);
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
		Index<Blob, ACell> jobs = cursor().getOrDefault(null);
		return (jobs != null && jobs.containsKey(id)) ? at(id) : null;
	}

	/**
	 * One job's record in its current state.
	 *
	 * @param id Job id
	 * @return The record, or null if there is no such job
	 */
	public AMap<AString, ACell> getRecord(Blob id) {
		Index<Blob, ACell> jobs = cursor().getOrDefault(null);
		return (jobs == null) ? null : Job.currentState(RT.ensureMap(jobs.get(id)));
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
		return (before == null || !before.containsKey(id)) ? at(id) : null;
	}

	/**
	 * A job's scratch area ({@code temp}), which reads as absent and refuses
	 * changes while the job does not exist.
	 *
	 * @param id Job id
	 * @return The job's scratch area
	 */
	public Workspace scratch(Blob id) {
		return at(id).workspace();
	}

	/**
	 * The job at {@code id}, whether or not its record exists, as a path names
	 * it: a missing job reads as absent and takes no change. Domain callers use
	 * {@link #get}, which is null for a missing job.
	 */
	public JobState at(Blob id) {
		return new JobState(context, cursor().withDefault(Index.none()).path(id), owner, id);
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

	/** The jobs in their current state; null when there are none. */
	@Override
	public ACell read() {
		Index<Blob, ACell> jobs = cursor().getOrDefault(null);
		return (jobs == null) ? null : current(jobs);
	}

	/**
	 * A job is named by its id in hex, whether or not it exists; a segment that
	 * is not hex names no job. Each job has its own policy; the list of jobs, and
	 * a segment naming no job, need authority over {@code <owner>/j/<path>}.
	 */
	@Override
	public ACoviaComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		Route route = route(segments, start, end);
		if (route == null) throw new NoSuchPathException(segments, start, end);
		return route.child().resolve(segments, start + route.taken(), end);
	}

	/** A job, named by its id in hex, whether or not it exists. */
	@Override
	public Route route(AString[] segments, int start, int end) {
		Blob id = (start == end) ? null : ValuePath.blobKey(segments[start]);
		return (id == null) ? null : new Route(at(id), 1);
	}

	/** See {@link #resolve(AString[], int, int)}; none outside any user. */
	@Override
	public AccessPolicy policy() {
		return policy;
	}
}
