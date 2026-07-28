package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;

/**
 * Resolves the {@code t/} virtual namespace to the current Job's persistent
 * {@code temp} field.
 *
 * <p>A Covia agent task is its caller-facing {@code agent:request} Job:
 * {@code taskId == jobId}. The agent's {@code tasks} index is only the pending
 * work queue. Keeping a second {@code t} slot on that transient queue row lost
 * scratch data as soon as the task was claimed/completed. Both ordinary Job
 * scope and agent task scope therefore use the Job record as the single system
 * of record. When a task is focused its id takes precedence over the run-loop's
 * own infrastructure Job id.</p>
 *
 * <p>If neither scope is present the resolver throws helpfully rather than
 * silently returning the wrong location.</p>
 */
public class TempNamespaceResolver implements NamespaceResolver {

	static final AString K_TEMP = Strings.intern("temp");
	static final AString K_UPDATED = Strings.intern("updated");

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		AString agentId = ctx.getAgentId();
		Blob taskId = ctx.getTaskId();
		Blob jobId = ctx.getJobId();

		if (taskId != null && agentId == null) {
			throw new RuntimeException(
				"Cannot use task-scoped 't/' without agentId on RequestContext");
		}

		// A focused task is the caller-facing Job. Prefer it over the internal
		// transition/trigger Job that may also be present on the cycle context.
		Blob scopedJobId = (taskId != null) ? taskId : jobId;
		if (scopedJobId != null) {
			ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
			ACell[] jPath = userCursor.getLattice().resolvePath(new ACell[] { Strings.create("j") });
			if (jPath == null) {
				throw new RuntimeException("Cannot resolve jobs namespace");
			}
			ALatticeCursor<ACell> jobsCursor = userCursor.path(jPath);

			ACell[] remaining = new ACell[keys.length - 1];
			System.arraycopy(keys, 1, remaining, 0, remaining.length);
			return new ResolvedNamespace(jobsCursor, remaining, scopedJobId);
		}

		throw new RuntimeException(
			"Cannot use 't/' prefix outside job or task scope (requires agentId+taskId or jobId on RequestContext)");
	}

	@Override
	public boolean isWritable() {
		return true;
	}

	/**
	 * Reads the temp map from a job record via the jobs index cursor.
	 */
	@SuppressWarnings("unchecked")
	public
	static ACell getTemp(ALatticeCursor<ACell> jobsCursor, Blob jobId) {
		ACell index = jobsCursor.get();
		if (index == null) return null;
		ACell record = ((Index<Blob, ACell>) index).get(jobId);
		if (record == null) return null;
		return RT.getIn(record, K_TEMP);
	}

	/**
	 * Atomically updates the temp map within a job record, bumping the
	 * {@code updated} timestamp for LWW merge correctness.
	 */
	@SuppressWarnings("unchecked")
	public
	static void updateTemp(ALatticeCursor<ACell> jobsCursor, Blob jobId,
			java.util.function.UnaryOperator<ACell> fn) {
		jobsCursor.updateAndGet(index -> {
			if (index == null) index = Index.none();
			Index<Blob, ACell> idx = (Index<Blob, ACell>) index;
			ACell record = idx.get(jobId);
			if (record == null) record = Maps.empty();
			AMap<AString, ACell> recMap = (AMap<AString, ACell>) record;

			ACell oldTemp = recMap.get(K_TEMP);
			ACell newTemp = fn.apply(oldTemp);

			AMap<AString, ACell> updated = recMap
				.assoc(K_TEMP, newTemp)
				.assoc(K_UPDATED, CVMLong.create(Utils.getCurrentTimestamp()));
			return idx.assoc(jobId, updated);
		});
	}
}
