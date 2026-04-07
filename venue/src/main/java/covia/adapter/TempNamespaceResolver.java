package covia.adapter;

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
import covia.venue.RequestContext;

/**
 * Resolves the {@code t/} virtual namespace to the {@code temp} field within
 * the current job record on the user's lattice.
 *
 * <p>{@code t/draft} resolves to remaining keys {@code ["draft"]}. The
 * returned cursor is the user's jobs index cursor. Read and write operations
 * use {@link #getTemp} and {@link #updateTemp} to navigate into the
 * specific job record's temp field atomically.</p>
 *
 * <p>Requires {@code ctx.getJobId()} to be set (i.e. the request is running
 * within a goal tree execution).</p>
 */
class TempNamespaceResolver implements NamespaceResolver {

	static final AString K_TEMP = Strings.intern("temp");
	static final AString K_UPDATED = Strings.intern("updated");

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		Blob jobId = ctx.getJobId();
		if (jobId == null) {
			throw new RuntimeException("Cannot use 't/' prefix outside job scope (no jobId on RequestContext)");
		}

		// Navigate: user cursor → j (jobs index)
		ALatticeCursor<ACell> userCursor = adapter.ensureUserCursor(ctx);
		ACell[] jPath = userCursor.getLattice().resolvePath(new ACell[] { Strings.create("j") });
		if (jPath == null) {
			throw new RuntimeException("Cannot resolve jobs namespace");
		}
		ALatticeCursor<ACell> jobsCursor = userCursor.path(jPath);

		// Remaining keys: everything after the "t" prefix
		ACell[] remaining = new ACell[keys.length - 1];
		System.arraycopy(keys, 1, remaining, 0, remaining.length);
		return new ResolvedNamespace(jobsCursor, remaining, jobId);
	}

	@Override
	public boolean isWritable() {
		return true;
	}

	/**
	 * Reads the temp map from a job record via the jobs index cursor.
	 */
	@SuppressWarnings("unchecked")
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
