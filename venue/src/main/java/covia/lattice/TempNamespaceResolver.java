package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import covia.api.Fields;
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
 * <p>{@code t/foo} resolves to the Job record cursor at {@code j/<jobId>} with
 * keys {@code ["temp", "foo"]}: the same shape as a physical path, navigated
 * by the adapter's ordinary deep read/write. The owning Job must already exist;
 * scoped scratch never creates it.</p>
 *
 * <p>If neither scope is present the resolver throws helpfully rather than
 * silently returning the wrong location.</p>
 */
public class TempNamespaceResolver implements NamespaceResolver {

	static final AString K_TEMP = Strings.intern("temp");

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
		if (scopedJobId == null) {
			throw new RuntimeException(
				"Cannot use 't/' prefix outside job or task scope (requires agentId+taskId or jobId on RequestContext)");
		}

		ALatticeCursor<ACell> jobCursor = adapter.ensureUserCursor(ctx).path(Namespace.J, scopedJobId);
		ACell[] rewritten = keys.clone();
		rewritten[0] = K_TEMP;
		return new ResolvedNamespace(jobCursor, rewritten, true, Fields.UPDATED);
	}

	@Override
	public boolean isWritable() {
		return true;
	}
}
