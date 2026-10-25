package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeContext;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.Covia;

/** Primary record of an existing venue job. Updates never create a missing row.
 * Transactions compose data changes only; execution and notifications belong
 * to the runtime after the enclosing transaction commits. */
public final class JobState extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, JobState> {
	private final LatticeContext context;
	private final AForkableCursor<ACell> edits;

	JobState(LatticeContext context, AForkableCursor<ACell> storage) {
		super(storage);
		this.context = context;
		this.edits = StampedCursor.create(storage, context::currentTimestamp,
			(value, ts) -> Covia.stampUpdated(Job.currentState(RT.ensureMap(value)), ts));
	}

	@Override public AForkableCursor<ACell> cursor() { return edits; }
	@Override public JobState withCursor(AForkableCursor<ACell> draft) {
		return new JobState(context, draft);
	}

	public AMap<AString, ACell> getRecord() { return Job.currentState(RT.ensureMap(cursor().get())); }

	/** Pure record edit; null rejects the edit. Returns the committed record or
	 * null for a rejected/missing row, resetting acceptance on every retry. */
	AMap<AString, ACell> update(UnaryOperator<AMap<AString, ACell>> action) {
		boolean[] accepted = {false};
		ACell committed = cursor().updateAndGet(value -> {
			AMap<AString, ACell> current = Job.currentState(RT.ensureMap(value));
			AMap<AString, ACell> next = current == null ? null : action.apply(current);
			accepted[0] = next != null;
			return next == null ? value : Job.currentState(next);
		});
		return accepted[0] ? RT.ensureMap(committed) : null;
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
