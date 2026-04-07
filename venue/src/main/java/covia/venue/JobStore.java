package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for the venue's job store.
 *
 * <p>Wraps a lattice cursor at the {@code :jobs} level
 * ({@code Index<Blob, ACell>} with timestamp-prefixed Blob keys).
 * All writes propagate through the cursor chain and are automatically
 * signed at the {@code SignedCursor} boundary.</p>
 *
 * <p>Job records are maps with fields like "status", "updated", "input", etc.
 * The underlying IndexLattice uses LWW merge on the "updated" timestamp,
 * so the newest update for each job ID always wins.</p>
 */
public class JobStore extends ALatticeComponent<Index<Blob, ACell>> {

	JobStore(ALatticeCursor<Index<Blob, ACell>> cursor) {
		super(cursor);
	}

	/**
	 * Persists a job record to the lattice.
	 *
	 * @param jobID Job ID (16-byte Blob: timestamp + counter + random)
	 * @param record Job status record map
	 */
	public void persist(Blob jobID, AMap<AString, ACell> record) {
		final AMap<AString, ACell> rec = record;
		cursor.updateAndGet(jobs -> {
			if (jobs == null) jobs = Index.none();
			Index<Blob, ACell> idx = (Index<Blob, ACell>) jobs;
			// Preserve temp field from existing record (goal-scoped scratch)
			AMap<AString, ACell> merged = rec;
			ACell existing = idx.get(jobID);
			if (existing instanceof AMap<?,?> existingMap) {
				ACell temp = ((AMap<AString, ACell>) existingMap).get(K_TEMP);
				if (temp != null) merged = merged.assoc(K_TEMP, temp);
			}
			return idx.assoc(jobID, merged);
		});
	}

	private static final AString K_TEMP = convex.core.data.Strings.intern("temp");

	/**
	 * Gets a single job record from the lattice.
	 *
	 * @param jobID Job ID
	 * @return Job record map, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> get(Blob jobID) {
		Index<Blob, ACell> jobs = getAll();
		ACell record = jobs.get(jobID);
		return (record instanceof AMap) ? (AMap<AString, ACell>) record : null;
	}

	/**
	 * Gets all jobs from the lattice.
	 *
	 * @return Index of all job records, never null
	 */
	public Index<Blob, ACell> getAll() {
		Index<Blob, ACell> jobs = (Index<Blob, ACell>) cursor.get();
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
