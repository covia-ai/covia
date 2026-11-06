package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.lattice.ACoviaComponent;

/**
 * The venue's entries for its unfinished jobs ({@code :jobs}), by job id: what
 * the venue needs to carry each job to its end. A job's record is what its
 * caller sees; its entry is the venue's own, and no route reads it.
 *
 * <p>An entry is written with its job's record when the job is admitted and
 * removed in the same transaction as the update that finishes the job, or with
 * the record or its owner's account. So an entry exists exactly while its
 * job's record is unfinished, and a restart recovers jobs from these entries
 * alone (JOBS.md, Recovery on restart). A job held only in memory has
 * none.</p>
 */
public final class JobIndex extends ACoviaComponent<Index<Blob, ACell>>
		implements TransactionalComponent<Index<Blob, ACell>, JobIndex> {

	/** The DID of the user whose {@code j/} holds the job's record. */
	public static final AString OWNER = Fields.OWNER;

	/** The job's {@link OutputRules}, as its operation declared them when it was admitted. */
	public static final AString RULES = Strings.intern("rules");

	JobIndex(AForkableCursor<Index<Blob, ACell>> cursor) {
		super(cursor);
	}

	@Override
	public JobIndex withCursor(AForkableCursor<Index<Blob, ACell>> draft) {
		return new JobIndex(draft);
	}

	/** Every entry, by job id; empty when no job is unfinished. */
	public Index<Blob, ACell> all() {
		Index<Blob, ACell> entries = cursor().get();
		return (entries == null) ? Index.none() : entries;
	}

	/**
	 * One job's entry.
	 *
	 * @param id Job id
	 * @return The entry, or null when the job has none: it is finished, held
	 *         only in memory, or unknown
	 */
	public AMap<AString, ACell> entry(Blob id) {
		return RT.ensureMap(all().get(id));
	}

	/** Writes a newly admitted job's entry. The caller writes the record in the same transaction. */
	void admit(Blob id, AMap<AString, ACell> entry) {
		cursor().updateAndGet(entries -> ((entries == null) ? Index.<Blob, ACell>none() : entries).assoc(id, entry));
	}

	/**
	 * Changes a job's entry, if it has one.
	 *
	 * @param id Job id
	 * @param change Pure edit of the entry; it may run again on a retried transaction
	 */
	void update(Blob id, UnaryOperator<AMap<AString, ACell>> change) {
		cursor().updateAndGet(entries -> {
			AMap<AString, ACell> current = (entries == null) ? null : RT.ensureMap(entries.get(id));
			if (current == null) return entries;
			AMap<AString, ACell> next = change.apply(current);
			return (next == current) ? entries : entries.assoc(id, next);
		});
	}

	/** Removes a job's entry, if it has one. */
	void remove(Blob id) {
		cursor().updateAndGet(entries -> (entries == null || !entries.containsKey(id)) ? entries : entries.dissoc(id));
	}

	/** Removes the entries of every job whose record {@code did} holds. */
	void removeOwner(AString did) {
		cursor().updateAndGet(entries -> {
			if (entries == null) return null;
			Index<Blob, ACell> kept = entries;
			for (var e : entries.entrySet()) {
				if (did.equals(RT.getIn(e.getValue(), OWNER))) kept = kept.dissoc(e.getKey());
			}
			return kept;
		});
	}
}
