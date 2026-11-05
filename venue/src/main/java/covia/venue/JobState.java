package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.lattice.ACoviaComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Position;
import covia.lattice.Workspace;

/** Primary record of an existing venue job. Updates never create a missing row.
 * Transactions compose data changes only; execution and notifications belong
 * to the runtime after the enclosing transaction commits. */
public final class JobState extends ACoviaComponent<ACell>
		implements TransactionalComponent<ACell, JobState> {
	private final VenueContext context;
	private final AForkableCursor<ACell> edits;

	JobState(VenueContext context, AForkableCursor<ACell> storage) {
		super(storage);
		this.context = context;
		this.edits = StampedCursor.create(storage, context::currentTimestamp,
			(value, ts) -> Covia.stampUpdated(Job.currentState(RT.ensureMap(value)), ts));
	}

	@Override public AForkableCursor<ACell> cursor() { return edits; }
	@Override public JobState withCursor(AForkableCursor<ACell> draft) {
		return new JobState(context, draft);
	}

	/** The job as callers see it ({@link #view}). */
	public AMap<AString, ACell> getRecord() { return view(RT.ensureMap(cursor().get())); }

	/** The job as callers see it: its current state. */
	@Override public ACell read() { return getRecord(); }

	/**
	 * A job record as callers see it: its current state, without the proofs it
	 * keeps for work that continues after the request ({@link #proofs}). Every
	 * read of a job goes through this, so stored proofs reach no output.
	 */
	public static AMap<AString, ACell> view(AMap<AString, ACell> record) {
		AMap<AString, ACell> current = Job.currentState(record);
		return (current == null) ? null : current.dissoc(Fields.PROOFS);
	}

	/**
	 * The verified UCAN proofs the job's caller presented, which the job keeps
	 * while its work can continue after the request, as a session that resumes
	 * after a restart does. Dropped when the job ends, and never shown to a
	 * caller.
	 *
	 * @return the proofs, or null when the job keeps none
	 */
	public AVector<ACell> proofs() {
		return RT.ensureVector(RT.getIn(cursor().get(), Fields.PROOFS));
	}

	/** The job's scratch area ({@code temp}); a change to it needs the job. */
	public Workspace workspace() {
		return Workspace.inRecord(cursor(), Fields.TEMP);
	}

	/** {@code temp} is the job's workspace; the rest of the job is read in its current state. */
	@Override
	public Position resolve(CoviaPath path) {
		if (!path.isEmpty() && Fields.TEMP.equals(path.first())) return workspace().resolve(path.rest());
		return super.resolve(path);
	}

	/** Pure record edit; null rejects the edit. The action sees the stored
	 * record, kept proofs included, and an edit that leaves them out keeps them
	 * (it may have started from a record as callers see it); the edit that ends
	 * the job drops them. Returns the committed record as callers see it
	 * ({@link #view}), or null for a rejected/missing row, resetting acceptance
	 * on every retry. */
	AMap<AString, ACell> update(UnaryOperator<AMap<AString, ACell>> action) {
		boolean[] accepted = {false};
		ACell committed = cursor().updateAndGet(value -> {
			AMap<AString, ACell> current = Job.currentState(RT.ensureMap(value));
			AMap<AString, ACell> next = current == null ? null : action.apply(current);
			accepted[0] = next != null;
			if (next == null) return value;
			next = Job.currentState(next);
			if (Job.isFinished(next)) return next.dissoc(Fields.PROOFS);
			ACell kept = current.get(Fields.PROOFS);
			return (kept == null || next.containsKey(Fields.PROOFS)) ? next : next.assoc(Fields.PROOFS, kept);
		});
		return accepted[0] ? view(RT.ensureMap(committed)) : null;
	}

	/** History remains appendable after terminal completion. Repeated message IDs
	 * are idempotent, regardless of whether a runtime handle is still cached. */
	public boolean appendHistory(AMap<AString, ACell> message) {
		return update(data -> {
			AVector<ACell> history = RT.ensureVector(data.get(Fields.HISTORY));
			if (history == null) history = Vectors.empty();
			ACell messageId = message.get(Fields.MESSAGE_ID);
			if (messageId != null) {
				for (ACell item : history) {
					if (item instanceof AMap<?, ?> map && messageId.equals(map.get(Fields.MESSAGE_ID))) return null;
				}
			}
			return data.assoc(Fields.HISTORY, history.conj(message));
		}) != null;
	}
}
