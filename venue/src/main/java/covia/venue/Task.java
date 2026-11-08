package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.lattice.CoviaValue;

/**
 * One task queued on an agent ({@code g/<id>/tasks/<hex>}): the row of work
 * the request job of the same id is waiting on. It holds the task's input,
 * the continuations queued behind it, how many of those a cycle has been
 * shown, and when it next runs. The result lives on the job, not here.
 *
 * <p>Each operation is one transaction that returns whether it was accepted.
 * A task's row goes when the task ends ({@link Tasks#take}), so a stale view
 * of a task finds nothing and changes nothing. The static forms read a row
 * already in hand. Anything spanning the task and the rest of the agent, such
 * as beginning a cycle, belongs to {@link Agent}.</p>
 */
public final class Task extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, Task> {

	/** Monotonic revision. A continuation increments it so a transition that
	 *  started against an older view cannot complete the task concurrently. */
	private static final AString K_REVISION = Strings.intern("revision");
	/** Message ids already accepted, retained so retries are idempotent even
	 *  after the session's pending entry is drained. */
	private static final AString K_CONTINUATION_IDS = Strings.intern("continuationIds");
	/** Ordered continuation envelopes queued behind the original input. */
	private static final AString K_CONTINUATIONS = Strings.intern("continuations");
	/** Number of original and continuation inputs already shown to cycles. */
	private static final AString K_PRESENTED_INPUTS = Strings.intern("presentedInputs");
	/** Set when the task yielded with something outstanding: it runs again only
	 *  when something new arrives for it. */
	private static final AString K_PARKED = Strings.intern("parked");

	private final Blob id;

	Task(AForkableCursor<ACell> cursor, Blob id) {
		super(cursor);
		this.id = id;
	}

	@Override
	public Task withCursor(AForkableCursor<ACell> draft) {
		return new Task(draft, id);
	}

	/** The fields below a task read as stored. */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		return CoviaValue.within(this, segments, start, end);
	}

	public Blob getID() { return id; }

	/** The task's row, or null when it is not queued. */
	public AMap<AString, ACell> getRecord() { return RT.ensureMap(cursor().getOrDefault(null)); }

	// ========== Reading ==========

	public long revision() { return revision(getRecord()); }
	public boolean isParked() { return isParked(getRecord()); }
	public boolean hasUnshownInput() { return hasUnshownInput(getRecord()); }
	public ACell nextInput() { return nextInput(getRecord()); }
	public ACell nextInputSource() { return nextInputSource(getRecord()); }

	/**
	 * Whether the task has something of its own to run on: it is not parked, or
	 * it has an input it has not been shown, or its wake time has come.
	 */
	public boolean isReady(long now) {
		return !isParked() || hasOwnWork(getRecord(), now);
	}

	/** The revision of a row (zero for one that has never had a continuation). */
	public static long revision(ACell row) {
		return (row instanceof AMap<?, ?> map && map.get(K_REVISION) instanceof CVMLong revision)
			? revision.longValue() : 0L;
	}

	/** Whether a row has been parked by a yield. */
	public static boolean isParked(ACell row) {
		return row instanceof AMap<?, ?> map && CVMBool.TRUE.equals(map.get(K_PARKED));
	}

	/** Whether a row still has an original or continuation input not yet shown to a cycle. */
	public static boolean hasUnshownInput(ACell row) {
		if (!(row instanceof AMap<?, ?> map)) return false;
		return shown(map) < 1 + ((map.get(K_CONTINUATIONS) instanceof AVector<?> queued) ? queued.count() : 0L);
	}

	/**
	 * The input a row has not been shown yet: its original input, then each
	 * continuation in order.
	 *
	 * @return The input, or null when every input has been shown
	 */
	public static ACell nextInput(ACell row) {
		if (!hasUnshownInput(row)) return null;
		AMap<?, ?> task = (AMap<?, ?>) row;
		long shown = shown(task);
		if (shown == 0) return RT.getIn(task, Fields.INPUT);
		return RT.getIn(continuation(task, shown), Fields.MESSAGE);
	}

	/**
	 * Who {@link #nextInput} came from, for its provenance: the row itself for
	 * the original input, the continuation's envelope for the rest.
	 *
	 * @return The source, or null when every input has been shown
	 */
	public static ACell nextInputSource(ACell row) {
		if (!hasUnshownInput(row)) return null;
		AMap<?, ?> task = (AMap<?, ?>) row;
		long shown = shown(task);
		return (shown == 0) ? task : continuation(task, shown);
	}

	/** Whether a row has an unshown input, or a wake time that has come. */
	static boolean hasOwnWork(ACell row, long now) {
		if (hasUnshownInput(row)) return true;
		return row instanceof AMap<?, ?> map && map.get(Fields.WAKE_TIME) instanceof CVMLong wake && wake.longValue() <= now;
	}

	private static long shown(AMap<?, ?> row) {
		return (row.get(K_PRESENTED_INPUTS) instanceof CVMLong count) ? count.longValue() : 0L;
	}

	private static ACell continuation(AMap<?, ?> row, long shown) {
		return ((AVector<?>) row.get(K_CONTINUATIONS)).get(shown - 1);
	}

	// ========== Operations ==========

	/**
	 * Queues a message behind the task's input and increments its revision.
	 *
	 * @param sid The session the message belongs to, which must be the task's
	 * @param envelope The message's envelope
	 * @param messageId Identifies the message so a retry adds nothing, or null
	 * @return whether the task holds the message: false when it is not queued or is in another session
	 */
	public boolean appendContinuation(Blob sid, ACell envelope, AString messageId) {
		return transact(draft -> {
			AMap<AString, ACell> task = draft.getRecord();
			if (task == null) return false;
			AString taskSid = RT.ensureString(task.get(Fields.SESSION_ID));
			if (taskSid == null || !sid.toHexString().equals(taskSid.toString())) return false;
			AVector<ACell> ids = vector(task.get(K_CONTINUATION_IDS));
			if (messageId != null) {
				for (long i = 0; i < ids.count(); i++) {
					if (messageId.equals(ids.get(i))) return true;
				}
			}
			task = task.assoc(K_REVISION, CVMLong.create(revision(task) + 1))
				.assoc(K_CONTINUATIONS, vector(task.get(K_CONTINUATIONS)).conj(envelope));
			if (messageId != null) task = task.assoc(K_CONTINUATION_IDS, ids.conj(messageId));
			draft.cursor().set(task);
			return true;
		});
	}

	/**
	 * Parks a task that yielded with something outstanding, so the run loop
	 * passes it over until something new arrives for it.
	 *
	 * @param expectedRevision The revision the yield saw
	 * @return whether it parked: false when the task is not queued or the
	 *         revision moved, since a continuation arrived and the task is ready anyway
	 */
	public boolean park(long expectedRevision) {
		return transact(draft -> {
			AMap<AString, ACell> task = draft.getRecord();
			if (task == null || revision(task) != expectedRevision) return false;
			draft.cursor().set(task.assoc(K_PARKED, CVMBool.TRUE));
			return true;
		});
	}

	/**
	 * Sets when the task next runs.
	 *
	 * @param time The wake time; zero or less clears it
	 * @return whether the task is queued
	 */
	public boolean setWakeTime(long time) {
		return transact(draft -> {
			AMap<AString, ACell> task = draft.getRecord();
			if (task == null) return false;
			draft.cursor().set((time > 0) ? task.assoc(Fields.WAKE_TIME, CVMLong.create(time)) : task.dissoc(Fields.WAKE_TIME));
			return true;
		});
	}

	/**
	 * Un-parks the task and records its next input as shown to a cycle.
	 *
	 * @return whether the task is queued
	 */
	public boolean present() {
		return transact(draft -> {
			AMap<AString, ACell> task = draft.getRecord();
			if (task == null) return false;
			AMap<AString, ACell> running = task.dissoc(K_PARKED);
			draft.cursor().set(hasUnshownInput(running)
				? running.assoc(K_PRESENTED_INPUTS, CVMLong.create(shown(running) + 1)) : running);
			return true;
		});
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> vector(ACell value) {
		return (value instanceof AVector<?> v) ? (AVector<ACell>) v : Vectors.empty();
	}
}
