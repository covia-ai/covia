package covia.venue;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;

/**
 * Cursor wrapper for a single agent's state within a user's lattice.
 *
 * <p>All mutations use atomic {@code cursor.updateAndGet} — no external
 * locking needed. The {@code update} method is private; callers use
 * named mutation methods that encapsulate the record structure.</p>
 */
public class AgentState extends ALatticeComponent<ACell> {

	// Record field keys
	private static final AString K_TS       = Strings.intern("ts");
	private static final AString K_STATUS   = Strings.intern("status");
	private static final AString K_CONFIG   = Strings.intern("config");
	private static final AString K_STATE    = Strings.intern("state");
	private static final AString K_TASKS    = Strings.intern("tasks");
	private static final AString K_SESSIONS = Strings.intern("sessions");
	private static final AString K_PENDING  = Strings.intern("pending");
	private static final AString K_INBOX    = Strings.intern("inbox");
	private static final AString K_TIMELINE = Strings.intern("timeline");
	private static final AString K_ERROR    = Strings.intern("error");
	private static final AString K_WAKE     = Strings.intern("wake");

	// Status constants
	public static final AString SLEEPING   = Strings.intern("SLEEPING");
	public static final AString RUNNING    = Strings.intern("RUNNING");
	public static final AString SUSPENDED  = Strings.intern("SUSPENDED");
	public static final AString TERMINATED = Strings.intern("TERMINATED");

	// Public key constants (for transition input/output field names)
	public static final AString KEY_STATE    = K_STATE;
	public static final AString KEY_STATUS   = K_STATUS;
	public static final AString KEY_CONFIG   = K_CONFIG;
	public static final AString KEY_TASKS    = K_TASKS;
	public static final AString KEY_SESSIONS = K_SESSIONS;
	public static final AString KEY_PENDING  = K_PENDING;
	public static final AString KEY_INBOX    = K_INBOX;
	public static final AString KEY_TIMELINE = K_TIMELINE;
	public static final AString KEY_ERROR    = K_ERROR;
	public static final AString KEY_WAKE     = K_WAKE;

	private final AString agentId;

	AgentState(ALatticeCursor<ACell> cursor, AString agentId) {
		super(cursor);
		this.agentId = agentId;
	}

	public AString getAgentId() { return agentId; }

	public boolean exists() { return cursor.get() != null; }

	// ========== Record access ==========

	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getRecord() {
		ACell v = cursor.get();
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/** Replaces the entire record. Use for initialisation only. */
	public void putRecord(AMap<AString, ACell> record) {
		cursor.set(record.assoc(K_TS, CVMLong.create(Utils.getCurrentTimestamp())));
	}

	// ========== Atomic update (private) ==========

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> update(UnaryOperator<AMap<AString, ACell>> fn) {
		ACell result = cursor.updateAndGet(current -> {
			if (!(current instanceof AMap)) return current;
			AMap<AString, ACell> r = (AMap<AString, ACell>) current;
			AMap<AString, ACell> updated = fn.apply(r);
			if (updated == r) return r;
			return updated.assoc(K_TS, CVMLong.create(Utils.getCurrentTimestamp()));
		});
		return (result instanceof AMap) ? (AMap<AString, ACell>) result : null;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> getAndUpdate(UnaryOperator<AMap<AString, ACell>> fn) {
		ACell old = cursor.getAndUpdate(current -> {
			if (!(current instanceof AMap)) return current;
			AMap<AString, ACell> r = (AMap<AString, ACell>) current;
			AMap<AString, ACell> updated = fn.apply(r);
			if (updated == r) return r;
			return updated.assoc(K_TS, CVMLong.create(Utils.getCurrentTimestamp()));
		});
		return (old instanceof AMap) ? (AMap<AString, ACell>) old : null;
	}

	// ========== Initialisation ==========

	public void initialise(AMap<AString, ACell> config, ACell initialState) {
		if (exists()) return;
		AMap<AString, ACell> record = Maps.of(
			K_STATUS, SLEEPING,
			K_TASKS, Index.none(),
			K_SESSIONS, Index.none(),
			K_PENDING, Index.none(),
			K_INBOX, Vectors.empty(),
			K_TIMELINE, Vectors.empty());
		if (config != null) record = record.assoc(K_CONFIG, config);
		if (initialState != null) record = record.assoc(K_STATE, initialState);
		putRecord(record);
	}

	/**
	 * Initialises an agent record as a fork of another agent. Copies config
	 * and state, optionally copies timeline. Tasks, sessions, pending, and
	 * inbox are fresh; status is SLEEPING. Does nothing if this agent
	 * already exists.
	 */
	public void initialiseFromFork(AMap<AString, ACell> config, ACell state, AVector<ACell> timeline) {
		if (exists()) return;
		AMap<AString, ACell> record = Maps.of(
			K_STATUS, SLEEPING,
			K_TASKS, Index.none(),
			K_SESSIONS, Index.none(),
			K_PENDING, Index.none(),
			K_INBOX, Vectors.empty(),
			K_TIMELINE, (timeline != null) ? timeline : Vectors.empty());
		if (config != null) record = record.assoc(K_CONFIG, config);
		if (state != null) record = record.assoc(K_STATE, state);
		putRecord(record);
	}

	// ========== Read accessors ==========

	public AString getStatus() {
		AMap<AString, ACell> r = getRecord();
		return (r != null) ? RT.ensureString(r.get(K_STATUS)) : null;
	}

	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getConfig() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return null;
		ACell v = r.get(K_CONFIG);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	public ACell getState() {
		AMap<AString, ACell> r = getRecord();
		return (r != null) ? r.get(K_STATE) : null;
	}

	@SuppressWarnings("unchecked")
	public AVector<ACell> getInbox() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return null;
		ACell v = r.get(K_INBOX);
		return (v instanceof AVector) ? (AVector<ACell>) v : null;
	}

	@SuppressWarnings("unchecked")
	public AVector<ACell> getTimeline() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return null;
		ACell v = r.get(K_TIMELINE);
		return (v instanceof AVector) ? (AVector<ACell>) v : null;
	}

	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getTasks() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return Index.none();
		ACell v = r.get(K_TASKS);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	/**
	 * Returns the agent's sessions Index (sid → session record).
	 * Currently reserved for Phase 1 sessions work — returns an empty
	 * Index on legacy records that predate the field.
	 */
	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getSessions() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return Index.none();
		ACell v = r.get(K_SESSIONS);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getPending() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return Index.none();
		ACell v = r.get(K_PENDING);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	public AString getError() {
		AMap<AString, ACell> r = getRecord();
		return (r != null) ? RT.ensureString(r.get(K_ERROR)) : null;
	}

	public long getTs() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return 0;
		ACell v = r.get(K_TS);
		return (v instanceof CVMLong l) ? l.longValue() : 0;
	}

	public long getWakeTime() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return 0;
		ACell v = r.get(K_WAKE);
		return (v instanceof CVMLong l) ? l.longValue() : 0;
	}

	public boolean shouldWake() {
		long wt = getWakeTime();
		return wt > 0 && Utils.getCurrentTimestamp() >= wt;
	}

	// ========== Simple mutations ==========

	public void setStatus(AString status) {
		update(r -> r.assoc(K_STATUS, status));
	}

	public void setError(AString error) {
		update(r -> r.assoc(K_ERROR, error));
	}

	public void clearError() {
		update(r -> r.dissoc(K_ERROR));
	}

	public void deliverMessage(ACell message) {
		update(r -> {
			@SuppressWarnings("unchecked")
			AVector<ACell> inbox = extractInbox(r);
			return r.assoc(K_INBOX, inbox.conj(message));
		});
	}

	public void addTask(Blob taskId, ACell taskData) {
		update(r -> r.assoc(K_TASKS, extractTasks(r).assoc(taskId, taskData)));
	}

	public void removeTask(Blob taskId) {
		update(r -> r.assoc(K_TASKS, extractTasks(r).dissoc(taskId)));
	}

	public void addPending(Blob jobId, ACell snapshot) {
		update(r -> r.assoc(K_PENDING, extractPending(r).assoc(jobId, snapshot)));
	}

	/** Sets wake time using min semantics — an earlier wake always wins. */
	public void setWakeTime(long wakeTime) {
		if (wakeTime <= 0) {
			update(r -> r.dissoc(K_WAKE));
		} else {
			update(r -> {
				ACell v = r.get(K_WAKE);
				long existing = (v instanceof CVMLong l) ? l.longValue() : 0;
				long effective = (existing > 0) ? Math.min(existing, wakeTime) : wakeTime;
				return r.assoc(K_WAKE, CVMLong.create(effective));
			});
		}
	}

	// ========== CAS operations ==========

	/** Atomic CAS: SUSPENDED → SLEEPING, clear error. Returns true if resumed. */
	public boolean tryResume() {
		AMap<AString, ACell> before = getAndUpdate(r ->
			SUSPENDED.equals(RT.ensureString(r.get(K_STATUS)))
				? r.assoc(K_STATUS, SLEEPING).dissoc(K_ERROR) : r);
		return SUSPENDED.equals(RT.ensureString(before.get(K_STATUS)));
	}

	/** Sets SUSPENDED status with error message. */
	public void suspend(AString error) {
		update(r -> r.assoc(K_ERROR, error).assoc(K_STATUS, SUSPENDED));
	}

	/** Sets SLEEPING status and clears wake. */
	public void sleep() {
		update(r -> r.assoc(K_STATUS, SLEEPING).dissoc(K_WAKE));
	}

	/**
	 * Returns a future that completes when this agent reaches SLEEPING.
	 *
	 * <p>Implemented by polling the agent record (10ms intervals). Used by
	 * tests to wait for the run loop to quiesce — no need for explicit
	 * polling loops in test code. Production code should react to inbox or
	 * task deliveries rather than poll agent status.</p>
	 *
	 * <p>The future never completes exceptionally; callers should apply a
	 * timeout via {@code .get(timeout, unit)}.</p>
	 */
	public java.util.concurrent.CompletableFuture<Void> awaitSleeping() {
		java.util.concurrent.CompletableFuture<Void> cf = new java.util.concurrent.CompletableFuture<>();
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			while (!cf.isDone() && !SLEEPING.equals(getStatus())) {
				try { Thread.sleep(10); } catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			cf.complete(null);
		});
		return cf;
	}

	/**
	 * Merges config and/or state fields into the existing agent record.
	 *
	 * <p>Incoming maps are shallow-merged into the existing values so that
	 * updating a single field (e.g. model) does not wipe sibling fields
	 * (e.g. caps, tools, outputs). Top-level keys in the incoming map
	 * override the corresponding keys in the existing map; keys not
	 * present in the incoming map are preserved.</p>
	 */
	@SuppressWarnings("unchecked")
	public void updateConfigAndState(AMap<AString, ACell> config, ACell state) {
		update(r -> {
			AMap<AString, ACell> u = r;
			if (config != null) {
				AMap<AString, ACell> existing = (AMap<AString, ACell>) r.get(K_CONFIG);
				u = u.assoc(K_CONFIG, merge(existing, config));
			}
			if (state instanceof AMap) {
				AMap<AString, ACell> existing = (AMap<AString, ACell>) r.get(K_STATE);
				AMap<AString, ACell> incoming = (AMap<AString, ACell>) state;
				AMap<AString, ACell> merged = merge(existing, incoming);
				// Deep-merge state.config so updating one field (e.g. model)
				// doesn't wipe siblings (e.g. caps, tools, outputs)
				if (existing != null && incoming.containsKey(K_CONFIG)
						&& existing.get(K_CONFIG) instanceof AMap
						&& incoming.get(K_CONFIG) instanceof AMap) {
					merged = merged.assoc(K_CONFIG, merge(
						(AMap<AString, ACell>) existing.get(K_CONFIG),
						(AMap<AString, ACell>) incoming.get(K_CONFIG)));
				}
				u = u.assoc(K_STATE, merged);
			} else if (state != null) {
				u = u.assoc(K_STATE, state);
			}
			return u;
		});
	}

	/** Shallow-merge: incoming keys override existing, existing keys preserved. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> merge(AMap<AString, ACell> existing, AMap<AString, ACell> incoming) {
		if (existing == null) return incoming;
		AMap<AString, ACell> result = existing;
		for (var entry : incoming.entrySet()) {
			result = result.assoc((AString) entry.getKey(), entry.getValue());
		}
		return result;
	}

	// ========== Run loop merge ==========

	/**
	 * Atomically merges run loop results into the agent record.
	 *
	 * <p>Reconciles concurrent modifications: preserves messages and tasks
	 * added during the transition. Determines whether new work arrived
	 * and sets status to RUNNING or SLEEPING accordingly.</p>
	 *
	 * @return The new record (check status to determine if loop should continue)
	 */
	public AMap<AString, ACell> mergeRunResult(
			ACell newState, long processedMsgCount,
			Index<Blob, ACell> presentedTasks,
			AMap<AString, ACell> taskResults,
			AMap<AString, ACell> timelineEntry) {
		return update(r -> {
			// Preserve messages added during transition
			AVector<ACell> currentInbox = extractInbox(r);
			AVector<ACell> remainingInbox = Vectors.empty();
			for (long i = processedMsgCount; i < currentInbox.count(); i++) {
				remainingInbox = remainingInbox.conj(currentInbox.get(i));
			}

			// Remove completed tasks, detect new ones
			Index<Blob, ACell> currentTasks = extractTasks(r);
			Index<Blob, ACell> remainingTasks = removeCompletedTasks(currentTasks, taskResults);

			boolean hasNew = shouldWakeFromRecord(r)
				|| remainingInbox.count() > 0
				|| hasNewTasksNotIn(remainingTasks, presentedTasks);

			AVector<ACell> timeline = extractTimeline(r);

			return r
				.assoc(K_STATE, newState)
				.assoc(K_TASKS, remainingTasks)
				.assoc(K_INBOX, remainingInbox)
				.assoc(K_TIMELINE, timeline.conj(timelineEntry))
				.assoc(K_STATUS, hasNew ? RUNNING : SLEEPING)
				.dissoc(K_ERROR)
				.dissoc(K_WAKE);
		});
	}

	// ========== Private helpers ==========

	@SuppressWarnings("unchecked")
	private static AVector<ACell> extractInbox(AMap<AString, ACell> r) {
		ACell v = r.get(K_INBOX);
		return (v instanceof AVector) ? (AVector<ACell>) v : Vectors.empty();
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> extractTasks(AMap<AString, ACell> r) {
		ACell v = r.get(K_TASKS);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> extractPending(AMap<AString, ACell> r) {
		ACell v = r.get(K_PENDING);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	@SuppressWarnings("unchecked")
	private static AVector<ACell> extractTimeline(AMap<AString, ACell> r) {
		ACell v = r.get(K_TIMELINE);
		return (v instanceof AVector) ? (AVector<ACell>) v : Vectors.empty();
	}

	private static boolean shouldWakeFromRecord(AMap<AString, ACell> r) {
		ACell v = r.get(K_WAKE);
		if (!(v instanceof CVMLong l)) return false;
		return l.longValue() > 0 && Utils.getCurrentTimestamp() >= l.longValue();
	}

	private static Index<Blob, ACell> removeCompletedTasks(
			Index<Blob, ACell> tasks, AMap<AString, ACell> taskResults) {
		if (taskResults == null || tasks == null) return (tasks != null) ? tasks : Index.none();
		Index<Blob, ACell> remaining = tasks;
		for (var entry : tasks.entrySet()) {
			AString hex = Strings.create(entry.getKey().toHexString());
			if (taskResults.get(hex) != null) remaining = remaining.dissoc(entry.getKey());
		}
		return remaining;
	}

	private static boolean hasNewTasksNotIn(Index<Blob, ACell> current, Index<Blob, ACell> presented) {
		if (current == null || current.count() == 0) return false;
		if (presented == null) return current.count() > 0;
		for (var entry : current.entrySet()) {
			if (presented.get(entry.getKey()) == null) return true;
		}
		return false;
	}
}
