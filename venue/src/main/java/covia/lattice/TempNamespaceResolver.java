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
 * Resolves the {@code t/} virtual namespace to an ephemeral, scope-private
 * slot. The resolver has two modes depending on what scope is present on
 * the {@link RequestContext}:
 *
 * <ol>
 *   <li><b>Agent + task scope.</b> When both {@code agentId} and {@code taskId}
 *       are set, {@code t/draft} resolves to the caller's cursor with rewritten
 *       keys {@code ["g", agentId, "tasks", taskId, "t", "draft"]}. This is
 *       the preferred path for agent task work — each task keeps its own
 *       temp slot within its record, and the cursor flow in
 *       {@link CoviaAdapter} treats it as an ordinary writable path.</li>
 *   <li><b>Job scope (legacy).</b> When only {@code jobId} is set (no agent
 *       context), {@code t/draft} resolves to remaining keys {@code ["draft"]}
 *       over the user's jobs index cursor. Reads and writes go through
 *       {@link #getTemp}/{@link #updateTemp} for atomic access to the
 *       specific job record's {@code temp} field. This preserves the
 *       goal-tree execution path that predates per-task scopes.</li>
 * </ol>
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

		// Preferred: agent + task scope — rewrite to a concrete lattice path.
		if (agentId != null && taskId != null) {
			ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
			ACell[] rewritten = new ACell[keys.length + 4];
			rewritten[0] = Strings.create("g");
			rewritten[1] = agentId;
			rewritten[2] = Strings.create("tasks");
			rewritten[3] = taskId;
			rewritten[4] = Strings.create("t");
			System.arraycopy(keys, 1, rewritten, 5, keys.length - 1);
			return new ResolvedNamespace(userCursor, rewritten);
		}

		// Legacy: job scope — specialised cursor access via getTemp/updateTemp.
		if (jobId != null) {
			ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
			ACell[] jPath = userCursor.getLattice().resolvePath(new ACell[] { Strings.create("j") });
			if (jPath == null) {
				throw new RuntimeException("Cannot resolve jobs namespace");
			}
			ALatticeCursor<ACell> jobsCursor = userCursor.path(jPath);

			ACell[] remaining = new ACell[keys.length - 1];
			System.arraycopy(keys, 1, remaining, 0, remaining.length);
			return new ResolvedNamespace(jobsCursor, remaining, jobId);
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
