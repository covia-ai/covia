package covia.venue;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.lattice.ALattice;
import convex.lattice.LatticeComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.grid.Job;
import covia.lattice.ACoviaComponent;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * A user's jobs ({@code j/}): job records keyed by job id, each the
 * {@link JobState} of one job.
 *
 * <p>Only the venue admits jobs, through {@link #create}, and every record is
 * read in its current state. Callers name a job by its hex id, which this
 * component turns into the stored key.</p>
 */
public final class Jobs extends ACoviaComponent<ACell> {

	private static final ALattice<ACell> LATTICE = Covia.USER_RECORD.path(Namespace.J);

	private final VenueContext context;

	Jobs(VenueContext context, AForkableCursor<ACell> cursor) {
		super(cursor);
		this.context = context;
	}

	/** Every job in its current state, keyed by job id; empty when there are none. */
	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getAll() {
		ACell value = cursor().get();
		return (value instanceof Index) ? Job.currentStates((Index<Blob, ACell>) value) : Index.none();
	}

	/** Counts the jobs without reading their records. */
	public long count() {
		return cursor().get() instanceof Index<?, ?> index ? index.count() : 0;
	}

	/**
	 * One job, which may not exist: its record is then null and its updates
	 * change nothing.
	 *
	 * @param id Job id
	 * @return The job, bound to this cursor or transaction draft
	 */
	public JobState get(Blob id) {
		return new JobState(context, LatticeComponent.resolve(cursor(), LATTICE, id));
	}

	/**
	 * Admits a job: creates its record exactly once.
	 *
	 * @param id Job id
	 * @param record Job record
	 * @return true if the record was created, false if the job already existed
	 */
	@SuppressWarnings("unchecked")
	boolean create(Blob id, AMap<AString, ACell> record) {
		AMap<AString, ACell> current = Job.currentState(record);
		ACell before = cursor().getAndUpdate(value -> {
			Index<Blob, ACell> jobs = value instanceof Index<?, ?> index
				? (Index<Blob, ACell>) index : Index.none();
			return jobs.containsKey(id) ? value : jobs.assoc(id, current);
		});
		return !(before instanceof Index<?, ?> index) || !index.containsKey(id);
	}

	/**
	 * Removes a job's record, if present. The removal is durable: the venue
	 * merge is whole-value LWW, so the post-delete snapshot wins and the record
	 * is not resurrected on sync.
	 *
	 * @param id Job id
	 * @return true if the job existed and was removed
	 */
	@SuppressWarnings("unchecked")
	public boolean remove(Blob id) {
		ACell before = cursor().getAndUpdate(value -> {
			if (!(value instanceof Index)) return value;
			return ((Index<Blob, ACell>) value).dissoc(id);
		});
		return before instanceof Index<?, ?> index && index.containsKey(id);
	}

	/** The jobs in their current state; null when there are none. */
	@SuppressWarnings("unchecked")
	@Override
	public ACell read() {
		ACell value = cursor().get();
		return (value instanceof Index) ? Job.currentStates((Index<Blob, ACell>) value) : value;
	}

	@Override
	public ACoviaComponent<?> resolve(ACell[] keys, int from) {
		Blob id = (from < keys.length) ? parseID(keys[from]) : null;
		if (id == null) return super.resolve(keys, from);
		return get(id).resolve(keys, from + 1);
	}

	/** The stored key a caller's job key names, or null when it names none. */
	private static Blob parseID(ACell key) {
		if (key instanceof AString s) return Blob.parse(s.toString());
		return (key instanceof ABlob b) ? b.toFlatBlob() : null;
	}
}
