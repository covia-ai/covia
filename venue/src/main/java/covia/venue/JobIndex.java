package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.IndexComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;

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
 *
 * <p>The admission writes an entry with {@link #put}, in the same transaction
 * as the job's record, and the finishing update removes it with
 * {@link #remove}.</p>
 */
public final class JobIndex extends IndexComponent<Blob, ACell>
		implements TransactionalComponent<Index<Blob, ACell>, JobIndex> {

	/** The DID of the user whose {@code j/} holds the job's record. */
	public static final AString OWNER = Fields.OWNER;

	/** The job's {@link OutputRules}, as its operation declared them when it was admitted. */
	public static final AString RULES = Strings.intern("rules");

	/**
	 * What the job's caller presented beyond its identity, kept for work that
	 * continues for the caller ({@link JobManager#keepCallerContext}): its
	 * verified {@code proofs} and its execution scopes {@code agentId},
	 * {@code sessionId}, {@code jobId} and {@code taskId}. Present, if empty,
	 * whenever the job's adapter asked for it.
	 */
	public static final AString CONTEXT = Strings.intern("context");

	/**
	 * What the job's adapter keeps to carry it to its end, such as how to reach
	 * a remote job again ({@link JobManager#adapterData}). Only that adapter
	 * reads it.
	 */
	public static final AString ADAPTER_DATA = Strings.intern("adapterData");

	JobIndex(AForkableCursor<Index<Blob, ACell>> cursor) {
		super(cursor);
	}

	@Override
	public JobIndex rebind(AForkableCursor<Index<Blob, ACell>> cursor) {
		return new JobIndex(cursor);
	}

	@Override
	public JobIndex withCursor(AForkableCursor<Index<Blob, ACell>> cursor) {
		return rebind(cursor);
	}

	/** Every entry, by job id; empty when no job is unfinished. */
	public Index<Blob, ACell> all() {
		Index<Blob, ACell> entries = get();
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
		return RT.ensureMap(get(id));
	}

	/**
	 * Changes a job's entry, if it has one.
	 *
	 * @param id Job id
	 * @param change Pure edit of the entry; it may run again on a retried transaction
	 */
	void update(Blob id, UnaryOperator<AMap<AString, ACell>> change) {
		path(id).updateAndGet(current -> {
			AMap<AString, ACell> entry = RT.ensureMap(current);
			return (entry == null) ? current : change.apply(entry);
		});
	}

	/**
	 * What a job's adapter keeps to carry it to its end ({@link #ADAPTER_DATA}).
	 *
	 * @return the data, or null when the adapter keeps none or the job has no entry
	 */
	public AMap<AString, ACell> adapterData(Blob id) {
		return RT.ensureMap(RT.getIn(entry(id), ADAPTER_DATA));
	}

	/**
	 * Changes what a job's adapter keeps, if the job has an entry.
	 *
	 * @param change Pure edit of the data, empty when there is none; null removes it
	 */
	public void updateAdapterData(Blob id, UnaryOperator<AMap<AString, ACell>> change) {
		update(id, entry -> {
			AMap<AString, ACell> kept = RT.ensureMap(entry.get(ADAPTER_DATA));
			AMap<AString, ACell> next = change.apply((kept == null) ? Maps.empty() : kept);
			if (next != null && next == kept) return entry;
			return (next == null) ? entry.dissoc(ADAPTER_DATA) : entry.assoc(ADAPTER_DATA, next);
		});
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
