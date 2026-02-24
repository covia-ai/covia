package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for the venue's job store.
 *
 * <p>Wraps a lattice cursor at the {@code :jobs} level
 * ({@code Index<AString, ACell>} with timestamp-prefixed keys).
 * All writes propagate through the cursor chain and are automatically
 * signed at the {@code SignedCursor} boundary.</p>
 *
 * <p>Job records are maps with fields like "status", "updated", "input", etc.
 * The underlying IndexLattice uses LWW merge on the "updated" timestamp,
 * so the newest update for each job ID always wins.</p>
 */
public class JobStore extends ALatticeComponent<Index<AString, ACell>> {

	JobStore(ALatticeCursor<Index<AString, ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Persists a job record to the lattice.
	 *
	 * @param jobID Job ID (timestamp-prefixed hex string)
	 * @param record Job status record map
	 */
	public void persist(AString jobID, AMap<AString, ACell> record) {
		cursor.updateAndGet(jobs -> {
			if (jobs == null) jobs = Index.none();
			return ((Index<AString, ACell>) jobs).assoc(jobID, record);
		});
	}

	/**
	 * Gets a single job record from the lattice.
	 *
	 * @param jobID Job ID
	 * @return Job record map, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> get(AString jobID) {
		Index<AString, ACell> jobs = getAll();
		ACell record = jobs.get(jobID);
		return (record instanceof AMap) ? (AMap<AString, ACell>) record : null;
	}

	/**
	 * Gets all jobs from the lattice.
	 *
	 * @return Index of all job records, never null
	 */
	public Index<AString, ACell> getAll() {
		Index<AString, ACell> jobs = (Index<AString, ACell>) cursor.get();
		if (jobs == null) return Index.none();
		return jobs;
	}

	/**
	 * Returns the number of persisted jobs.
	 *
	 * @return Job count
	 */
	public long count() {
		return getAll().count();
	}

}
