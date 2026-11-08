package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Path;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.api.Fields;
import covia.lattice.AccessPolicy;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.CoviaValue;
import covia.lattice.Namespace;
import covia.lattice.Owner;
import covia.lattice.Workspace;

/** Primary record of an existing venue job. Updates never create a missing row.
 * Transactions compose data changes only; execution and notifications belong
 * to the runtime after the enclosing transaction commits. */
public final class JobRecord extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, JobRecord>, Owner {
	private final VenueContext context;
	private final AForkableCursor<ACell> edits;
	private final AString owner;
	private final Blob id;
	/** Who may use this job's data: see {@link #resolve(AString[], int, int)}. */
	private final AccessPolicy policy;

	/**
	 * @param owner DID of the user whose job this is, or null outside any user
	 * @param id The job's id
	 */
	JobRecord(VenueContext context, AForkableCursor<ACell> storage, AString owner, Blob id) {
		super(storage);
		this.context = context;
		this.owner = owner;
		this.id = id;
		this.policy = (owner == null || id == null) ? null : jobPolicy(owner, id);
		this.edits = StampedCursor.create(storage, context::currentTimestamp, Covia::stampUpdated);
	}

	@Override public AForkableCursor<ACell> cursor() { return edits; }
	@Override public JobRecord withCursor(AForkableCursor<ACell> draft) {
		return new JobRecord(context, draft, owner, id);
	}

	/**
	 * Work running in a job reaches its {@code temp} of right; anything else in
	 * the job needs authority over {@code <owner>/j/<id>/<path>}.
	 */
	private static AccessPolicy jobPolicy(AString owner, Blob id) {
		String base = owner + "/" + Namespace.J + "/" + id.toHexString();
		return (ctx, ability, path) -> {
			if (!path.isEmpty() && Fields.TEMP.equals(path.get(0)) && ctx != null
					&& owner.equals(ctx.getUserDID()) && (isId(id, ctx.getTaskId()) || isId(id, ctx.getJobId()))) {
				return AccessPolicy.Need.OF_RIGHT;
			}
			return AccessPolicy.Need.authorityOver(AccessPolicy.address(base, path));
		};
	}

	/** Whether {@code request}, a request's job or task id, is {@code id}. */
	static boolean isId(Blob id, Blob request) {
		return request != null && id.equals(request);
	}

	/** The job's record, or null when there is no such job. */
	public AMap<AString, ACell> getRecord() { return RT.ensureMap(cursor().getOrDefault(null)); }

	/** The job's scratch area ({@code temp}); a change to it needs the job. */
	public Workspace workspace() {
		return Workspace.inRecord(cursor(), Fields.TEMP);
	}

	/**
	 * {@code temp} is the job's workspace; the rest of the job is its record,
	 * read-only. Work running in the job reaches its {@code temp} of right, and
	 * anything else needs authority over {@code <owner>/j/<id>/<path>}.
	 */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		if (Fields.TEMP.equals(segments[start])) return workspace().resolve(segments, start + 1, end);
		return CoviaValue.within(this, segments, start, end);
	}

	/** See {@link #resolve(AString[], int, int)}; none outside any user. */
	@Override
	public AccessPolicy policy() {
		return policy;
	}

	/**
	 * The path in the job of a stretch of its scratch area, as a request's
	 * {@code t/} names one: {@code temp/<path>}, which the job's policy reads.
	 */
	public static Path inScratch(Path path) {
		return SCRATCH.concat(path);
	}

	private static final Path SCRATCH = CoviaPath.segments(Fields.TEMP);

	/** Pure record edit; null rejects the edit. Returns the committed record,
	 * or null for a rejected/missing row. */
	AMap<AString, ACell> update(UnaryOperator<AMap<AString, ACell>> action) {
		return transact(draft -> {
			AMap<AString, ACell> current = draft.getRecord();
			AMap<AString, ACell> next = current == null ? null : action.apply(current);
			if (next == null) return null;
			return RT.ensureMap(draft.cursor().updateAndGet(value -> next));
		});
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
