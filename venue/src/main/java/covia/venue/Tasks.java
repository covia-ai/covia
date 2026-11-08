package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.lattice.ALatticeComponent;
import convex.lattice.IndexComponent;
import convex.lattice.NoSuchPathException;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.ValuePath;

/**
 * An agent's queue of tasks ({@code g/<id>/tasks}), keyed by task id, which is
 * the id of the request job waiting on it. {@link #task} gives one task, which
 * has its own operations ({@link Task}).
 *
 * <p>Access is the agent's: neither the queue nor a task has a policy of its
 * own. The cursor sits inside the agent's stamped record, so every change
 * stamps the agent.</p>
 */
public final class Tasks extends IndexComponent<Blob, ACell>
		implements TransactionalComponent<Index<Blob, ACell>, Tasks> {

	Tasks(AForkableCursor<Index<Blob, ACell>> cursor) {
		super(cursor);
	}

	@Override
	public Tasks withCursor(AForkableCursor<Index<Blob, ACell>> draft) {
		return new Tasks(draft);
	}

	/** {@code <hex>} names a task; anything else names nothing. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		Blob id = ValuePath.blobKey(segments[start]);
		if (id == null) throw new NoSuchPathException(segments, start, end);
		return task(id).resolve(segments, start + 1, end);
	}

	/** Every queued task's row, by id; empty when none is queued. */
	public Index<Blob, ACell> all() {
		Index<Blob, ACell> rows = get();
		return (rows == null) ? Index.none() : rows;
	}

	/** The task under {@code id}, which need not be queued. */
	public Task task(Blob id) {
		return new Task(path(id), id);
	}

	/**
	 * Queues a task.
	 *
	 * @return whether it was added: false when a task is already queued under {@code id}
	 */
	public boolean add(Blob id, ACell row) {
		return transact(draft -> {
			if (draft.containsKey(id)) return false;
			draft.put(id, row);
			return true;
		});
	}

	/**
	 * Removes a task and returns its row, for whoever is ending it. Only the
	 * caller whose view of the task is still current takes it, so a task that
	 * received a continuation stays queued for a fresh cycle.
	 *
	 * @param expectedRevision The revision the caller saw, which must still be
	 *        the task's with every input shown; negative to take it regardless
	 * @return the row, or null when nothing was taken
	 */
	public ACell take(Blob id, long expectedRevision) {
		return transact(draft -> {
			ACell row = draft.all().get(id);
			if (row == null) return null;
			if (expectedRevision >= 0 && (Task.revision(row) != expectedRevision || Task.hasUnshownInput(row))) return null;
			draft.remove(id);
			return row;
		});
	}
}
