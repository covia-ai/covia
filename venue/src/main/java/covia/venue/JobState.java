package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.lattice.ACoviaComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.AccessPolicy;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;
import covia.lattice.Position;
import covia.lattice.Workspace;

/** Primary record of an existing venue job. Updates never create a missing row.
 * Transactions compose data changes only; execution and notifications belong
 * to the runtime after the enclosing transaction commits. */
public final class JobState extends ACoviaComponent<ACell>
		implements TransactionalComponent<ACell, JobState> {
	private final VenueContext context;
	private final AForkableCursor<ACell> edits;
	private final AString owner;
	private final Blob id;
	/** Who may use this job's data: see {@link #resolve}. */
	private final AccessPolicy policy;

	/**
	 * @param owner DID of the user whose job this is, or null outside any user
	 * @param id The job's id
	 */
	JobState(VenueContext context, AForkableCursor<ACell> storage, AString owner, Blob id) {
		super(storage);
		this.context = context;
		this.owner = owner;
		this.id = id;
		this.policy = (owner == null || id == null) ? null : jobPolicy(owner, id);
		this.edits = StampedCursor.create(storage, context::currentTimestamp,
			(value, ts) -> Covia.stampUpdated(Job.currentState(RT.ensureMap(value)), ts));
	}

	@Override public AForkableCursor<ACell> cursor() { return edits; }
	@Override public JobState withCursor(AForkableCursor<ACell> draft) {
		return new JobState(context, draft, owner, id);
	}

	/**
	 * Work running in a job reaches its {@code temp} of right; anything else in
	 * the job needs authority over {@code <owner>/j/<id>/<path>}.
	 */
	private static AccessPolicy jobPolicy(AString owner, Blob id) {
		String base = owner + "/" + Namespace.J + "/" + id.toHexString();
		return (ctx, ability, path) -> {
			if (!path.isEmpty() && Fields.TEMP.equals(path.first()) && ctx != null
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

	/** The job's record: its current state, which is what callers see. */
	public AMap<AString, ACell> getRecord() { return Job.currentState(RT.ensureMap(cursor().get())); }

	/** The job as callers see it: its current state. */
	@Override public ACell read() { return getRecord(); }

	/** The job's scratch area ({@code temp}); a change to it needs the job. */
	public Workspace workspace() {
		return Workspace.inRecord(cursor(), Fields.TEMP);
	}

	/**
	 * {@code temp} is the job's workspace; the rest of the job is read in its
	 * current state. Work running in the job reaches its {@code temp} of right,
	 * and anything else needs authority over {@code <owner>/j/<id>/<path>}.
	 */
	@Override
	public Position resolve(CoviaPath path) {
		Position position = (!path.isEmpty() && Fields.TEMP.equals(path.first()))
			? workspace().resolve(path.rest()) : super.resolve(path);
		return (policy == null) ? position : position.ownedBy(policy, path);
	}

	/**
	 * Where {@code path} ends in the job's scratch area, as a request's
	 * {@code t/} names it: {@code path} is the stretch below the area, and the
	 * job's policy reads it as {@code temp/<path>}.
	 */
	public Position resolveScratch(CoviaPath path) {
		Position position = workspace().resolve(path);
		return (policy == null) ? position : position.ownedBy(policy, SCRATCH.append(path));
	}

	private static final CoviaPath SCRATCH = CoviaPath.segments(Fields.TEMP);

	/** Pure record edit; null rejects the edit. Returns the committed record,
	 * or null for a rejected/missing row, resetting acceptance on every retry. */
	AMap<AString, ACell> update(UnaryOperator<AMap<AString, ACell>> action) {
		boolean[] accepted = {false};
		ACell committed = cursor().updateAndGet(value -> {
			AMap<AString, ACell> current = Job.currentState(RT.ensureMap(value));
			AMap<AString, ACell> next = current == null ? null : action.apply(current);
			accepted[0] = next != null;
			return (next == null) ? value : Job.currentState(next);
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
