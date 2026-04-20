package covia.venue;

import java.util.Objects;
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
import covia.api.Fields;

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
	private static final AString K_TIMELINE = Strings.intern("timeline");
	private static final AString K_ERROR    = Strings.intern("error");

	// Session record field keys (scoped within a single session map)
	private static final AString K_C        = Strings.intern("c");
	private static final AString K_FRAMES   = Strings.intern("frames");
	private static final AString K_META     = Strings.intern("meta");
	private static final AString K_PARTIES  = Strings.intern("parties");
	private static final AString K_CREATED  = Strings.intern("created");
	private static final AString K_TURNS    = Strings.intern("turns");

	// Frame record field keys (entries in session.frames). See GOAL_TREE.md.
	private static final AString K_DESCRIPTION  = Strings.intern("description");
	private static final AString K_CONVERSATION = Strings.intern("conversation");

	// Turn record field keys (entries in session.history). See venue/CLAUDE.local.md
	// "Sessions S3 — Per-session history (turn shape contract)" for full spec.
	public static final AString K_ROLE      = Strings.intern("role");
	public static final AString K_CONTENT   = Strings.intern("content");
	public static final AString K_SOURCE    = Strings.intern("source");
	/** Turn timestamp field name. Same interned value as the private K_TS
	 *  (lattice version stamp) but conceptually distinct: this is wall-clock
	 *  millis at turn mint time. Exposed for run-loop turn construction. */
	public static final AString K_TURN_TS   = Strings.intern("ts");

	// Role values
	public static final AString ROLE_USER      = Strings.intern("user");
	public static final AString ROLE_ASSISTANT = Strings.intern("assistant");
	public static final AString ROLE_SYSTEM    = Strings.intern("system");

	// Source values
	public static final AString SOURCE_TRANSITION = Strings.intern("transition");
	public static final AString SOURCE_REQUEST    = Strings.intern("request");
	public static final AString SOURCE_CHAT       = Strings.intern("chat");
	public static final AString SOURCE_MESSAGE    = Strings.intern("message");

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
	public static final AString KEY_TIMELINE = K_TIMELINE;
	public static final AString KEY_ERROR    = K_ERROR;
	/** Session-record `frames` key — vector of frame maps. First entry is the
	 *  root frame; subsequent entries are pushed by {@code subgoal}. See
	 *  {@code venue/docs/GOAL_TREE.md}. */
	public static final AString KEY_FRAMES   = K_FRAMES;
	/** Frame record `description` key. */
	public static final AString KEY_DESCRIPTION  = K_DESCRIPTION;
	/** Frame record `conversation` key — vector of turn envelopes and/or
	 *  compacted segments. */
	public static final AString KEY_CONVERSATION = K_CONVERSATION;

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
			K_TIMELINE, Vectors.empty());
		if (config != null) record = record.assoc(K_CONFIG, config);
		if (initialState != null) record = record.assoc(K_STATE, initialState);
		putRecord(record);
	}

	/**
	 * Initialises an agent record as a fork of another agent. Copies config
	 * and state, optionally copies timeline. Tasks, sessions, and pending
	 * are fresh; status is SLEEPING. Does nothing if this agent already
	 * exists.
	 */
	public void initialiseFromFork(AMap<AString, ACell> config, ACell state, AVector<ACell> timeline) {
		if (exists()) return;
		AMap<AString, ACell> record = Maps.of(
			K_STATUS, SLEEPING,
			K_TASKS, Index.none(),
			K_SESSIONS, Index.none(),
			K_PENDING, Index.none(),
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
	 */
	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getSessions() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return Index.none();
		ACell v = r.get(K_SESSIONS);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	/**
	 * Returns the session record for the given sid, or null if absent.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getSession(Blob sid) {
		Index<Blob, ACell> sessions = getSessions();
		ACell v = sessions.get(sid);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/**
	 * Ensures a session record exists at the given sid. If absent, creates
	 * a fresh session: {c: {}, pending: [], frames: [rootFrame],
	 * meta: {created, turns, parties}}. The root frame is empty
	 * ({@code {description: "", conversation: []}}) — adapters fill its
	 * description on first use. If {@code caller} is non-null and the session
	 * is new, it is recorded as the first party. Returns the session record
	 * (existing or freshly created).
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> ensureSession(Blob sid, AString caller) {
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			if (sessions.get(sid) != null) return r;
			AMap<AString, ACell> meta = Maps.of(
				K_CREATED, CVMLong.create(Utils.getCurrentTimestamp()),
				K_TURNS,   CVMLong.create(0),
				K_PARTIES, (caller != null) ? Vectors.of(caller) : Vectors.empty());
			AMap<AString, ACell> rootFrame = Maps.of(
				K_DESCRIPTION,  Strings.EMPTY,
				K_CONVERSATION, Vectors.empty());
			AMap<AString, ACell> session = Maps.of(
				K_C,       Maps.empty(),
				K_PENDING, Vectors.empty(),
				K_FRAMES,  Vectors.of(rootFrame),
				K_META,    meta);
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
		return getSession(sid);
	}

	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getPending() {
		AMap<AString, ACell> r = getRecord();
		if (r == null) return Index.none();
		ACell v = r.get(K_PENDING);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	/**
	 * Returns the per-session pending message vector (S3b). Distinct from
	 * the agent-level {@code pending} Index of in-flight Job snapshots —
	 * same {@code AString} field name at a different path. This vector
	 * holds messages awaiting consumption by the next transition for the
	 * given session.
	 *
	 * <p>Returns an empty vector if the session is missing or has no
	 * pending entries.</p>
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getSessionPending(Blob sid) {
		AMap<AString, ACell> session = getSession(sid);
		if (session == null) return Vectors.empty();
		ACell v = session.get(K_PENDING);
		return (v instanceof AVector) ? (AVector<ACell>) v : Vectors.empty();
	}

	/**
	 * Atomically appends an envelope to {@code sessions[sid].pending} (S3b).
	 * No-op if the session is missing — callers should ensureSession first.
	 */
	@SuppressWarnings("unchecked")
	public void appendSessionPending(Blob sid, ACell envelope) {
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			AVector<ACell> pending = (session.get(K_PENDING) instanceof AVector pv)
				? (AVector<ACell>) pv : Vectors.empty();
			session = session.assoc(K_PENDING, pending.conj(envelope));
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
	}

	/**
	 * Returns true if any session has a non-empty pending vector.
	 */
	@SuppressWarnings("unchecked")
	public boolean hasSessionPending() {
		Index<Blob, ACell> sessions = getSessions();
		if (sessions == null || sessions.count() == 0) return false;
		for (var entry : sessions.entrySet()) {
			ACell sv = entry.getValue();
			if (sv instanceof AMap m) {
				ACell pv = m.get(K_PENDING);
				if (pv instanceof AVector v && v.count() > 0) return true;
			}
		}
		return false;
	}

	/**
	 * Returns the sid (Blob) of the first session with non-empty pending,
	 * or null if no session has pending messages.
	 */
	@SuppressWarnings("unchecked")
	public Blob pickSessionWithPending() {
		Index<Blob, ACell> sessions = getSessions();
		if (sessions == null || sessions.count() == 0) return null;
		for (var entry : sessions.entrySet()) {
			ACell sv = entry.getValue();
			if (sv instanceof AMap m) {
				ACell pv = m.get(K_PENDING);
				if (pv instanceof AVector v && v.count() > 0) return entry.getKey();
			}
		}
		return null;
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

	public void addTask(Blob taskId, ACell taskData) {
		update(r -> r.assoc(K_TASKS, extractTasks(r).assoc(taskId, taskData)));
	}

	public void removeTask(Blob taskId) {
		update(r -> r.assoc(K_TASKS, extractTasks(r).dissoc(taskId)));
	}

	public void addPending(Blob jobId, ACell snapshot) {
		update(r -> r.assoc(K_PENDING, extractPending(r).assoc(jobId, snapshot)));
	}

	/**
	 * Single-writer helper for per-thread wake scheduling (B8.8). Writes
	 * {@code wakeTime} on the named session or task record, then registers
	 * (or cancels) a fire in the {@link AgentScheduler}. See
	 * {@code venue/docs/SCHEDULER.md §7.5}.
	 *
	 * <p>Replace-semantics: the new {@code wakeTime} overwrites any prior
	 * value. Pass {@code wakeTime <= 0} to clear the wake and cancel the
	 * scheduler fire. A missing session / task is a no-op (no lattice
	 * write, no scheduler change) — callers should ensure the record
	 * exists first.</p>
	 *
	 * <p>Order: lattice write first, then scheduler. A crash between
	 * the two is recovered by the boot rebuild, which restores the
	 * scheduler index from the authoritative lattice state.</p>
	 *
	 * <p>Per-ref concurrency is zero under the virtual-thread-per-agent
	 * model (one cycle per agent serialises all in-cycle writers). Boot
	 * rebuild runs before any cycle starts. No per-ref lock needed.</p>
	 *
	 * @param scheduler Scheduler to install / cancel the fire on
	 * @param userDid   Agent owner's DID (for {@link AgentScheduler.ThreadRef})
	 * @param kind      SESSION or TASK
	 * @param threadId  Session or task id (Blob)
	 * @param wakeTime  Absolute wall-clock millis, or {@code <= 0} to clear
	 */
	@SuppressWarnings("unchecked")
	public void setThreadWakeTime(AgentScheduler scheduler, AString userDid,
			AgentScheduler.ThreadKind kind, Blob threadId, long wakeTime) {
		Objects.requireNonNull(scheduler, "scheduler");
		Objects.requireNonNull(userDid, "userDid");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(threadId, "threadId");

		// Single pre-read so we can skip the write if the target record
		// is missing. We don't retry on a race — callers ensure existence.
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;

		boolean present;
		if (kind == AgentScheduler.ThreadKind.SESSION) {
			Index<Blob, ACell> sessions = (record.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			present = sessions.get(threadId) instanceof AMap;
		} else {
			Index<Blob, ACell> tasks = (record.get(K_TASKS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			present = tasks.get(threadId) instanceof AMap;
		}
		if (!present) return;

		AString key = (kind == AgentScheduler.ThreadKind.SESSION) ? K_SESSIONS : K_TASKS;
		update(r -> {
			Index<Blob, ACell> idx = (r.get(key) instanceof Index i)
				? (Index<Blob, ACell>) i : Index.none();
			ACell existing = idx.get(threadId);
			if (!(existing instanceof AMap)) return r;
			AMap<AString, ACell> rec = (AMap<AString, ACell>) existing;
			AMap<AString, ACell> updated = (wakeTime > 0)
				? rec.assoc(Fields.WAKE_TIME, CVMLong.create(wakeTime))
				: rec.dissoc(Fields.WAKE_TIME);
			return r.assoc(key, idx.assoc(threadId, updated));
		});

		AgentScheduler.ThreadRef ref =
			new AgentScheduler.ThreadRef(userDid, agentId, kind, threadId);
		if (wakeTime > 0) {
			scheduler.schedule(ref, wakeTime);
		} else {
			scheduler.cancel(ref);
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

	/**
	 * Sets SLEEPING status, unless the agent has been externally SUSPENDED
	 * or TERMINATED — in which case the status is preserved.
	 */
	public void sleep() {
		update(r -> {
			AString cur = RT.ensureString(r.get(K_STATUS));
			if (SUSPENDED.equals(cur) || TERMINATED.equals(cur)) {
				return r;
			}
			return r.assoc(K_STATUS, SLEEPING);
		});
	}

	/**
	 * Returns a future that completes when this agent reaches SLEEPING.
	 *
	 * <p>Implemented by polling the agent record (10ms intervals). Used by
	 * tests to wait for the run loop to quiesce — no need for explicit
	 * polling loops in test code. Production code should react to session
	 * pending or task deliveries rather than poll agent status.</p>
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
	 * <p>Reconciles concurrent modifications: preserves tasks added during
	 * the transition. Determines whether new work arrived (session pending
	 * messages, new tasks, or wake flag) and sets status to RUNNING or
	 * SLEEPING accordingly.</p>
	 *
	 * @return The new record (check status to determine if loop should continue)
	 */
	public AMap<AString, ACell> mergeRunResult(
			ACell newState,
			AString consumedSession,
			Index<Blob, ACell> presentedTasks,
			AMap<AString, ACell> taskResults,
			AMap<AString, ACell> timelineEntry) {
		return mergeRunResult(newState, consumedSession,
			presentedTasks, taskResults, timelineEntry, null, null, 0, null);
	}

	/**
	 * Back-compat 8-arg overload — no adapter-owned frames. Delegates to the
	 * 9-arg form with {@code newFrames = null}.
	 */
	public AMap<AString, ACell> mergeRunResult(
			ACell newState,
			AString consumedSession,
			Index<Blob, ACell> presentedTasks,
			AMap<AString, ACell> taskResults,
			AMap<AString, ACell> timelineEntry,
			Blob historySid,
			AVector<ACell> turnsToAppend,
			long presentedSessionPendingCount) {
		return mergeRunResult(newState, consumedSession, presentedTasks,
			taskResults, timelineEntry, historySid, turnsToAppend,
			presentedSessionPendingCount, null);
	}

	/**
	 * Atomic merge with session history append + session pending drain.
	 *
	 * <p>When {@code historySid != null}:
	 * <ul>
	 *   <li>If {@code turnsToAppend} is non-empty, turns are appended to
	 *       {@code sessions[historySid].history} and {@code meta.turns} is
	 *       bumped.</li>
	 *   <li>The first {@code presentedSessionPendingCount} entries of
	 *       {@code sessions[historySid].pending} are dropped — the run loop
	 *       snapshots the count pre-transition and passes it here so that
	 *       messages arriving during the transition (the tail) are preserved
	 *       for the next cycle.</li>
	 * </ul>
	 * All performed inside the same CAS as the timeline / state writes.</p>
	 *
	 * <p>This atomic-update guarantee matches the deferred-completion
	 * ordering invariant: an external observer never sees a cycle that
	 * wrote the timeline but not the history / pending drain.</p>
	 *
	 * <p>When {@code newFrames} is non-null, the picked session's
	 * {@code frames} vector is replaced wholesale before any turn append —
	 * the adapter owns its own frame-stack changes (subgoal pushes/pops,
	 * tool turns, compactions). Any {@code turnsToAppend} are still applied
	 * to {@code frames[0].conversation} on top, which is how concurrent
	 * intake (drained pending envelopes → user turns at root) co-exists
	 * with adapter-owned stack emission.</p>
	 *
	 * @return The new record (check status to determine if loop should continue)
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> mergeRunResult(
			ACell newState,
			AString consumedSession,
			Index<Blob, ACell> presentedTasks,
			AMap<AString, ACell> taskResults,
			AMap<AString, ACell> timelineEntry,
			Blob historySid,
			AVector<ACell> turnsToAppend,
			long presentedSessionPendingCount,
			AVector<ACell> newFrames) {
		return update(r -> {
			// Remove completed tasks, detect new ones
			Index<Blob, ACell> currentTasks = extractTasks(r);
			Index<Blob, ACell> remainingTasks = removeCompletedTasks(currentTasks, taskResults);

			AVector<ACell> timeline = extractTimeline(r);

			AMap<AString, ACell> updated = r
				.assoc(K_STATE, newState)
				.assoc(K_TASKS, remainingTasks)
				.assoc(K_TIMELINE, timeline.conj(timelineEntry))
				.dissoc(K_ERROR);

			// Atomic frames[0].conversation append + session.pending drain for
			// the picked session. Both touch the same session record so we
			// fold them into one assoc.
			boolean hasTurns = turnsToAppend != null && turnsToAppend.count() > 0;
			boolean hasDrain = presentedSessionPendingCount > 0;
			boolean hasNewFrames = newFrames != null;
			if (historySid != null && (hasTurns || hasDrain || hasNewFrames)) {
				Index<Blob, ACell> sessions = (updated.get(K_SESSIONS) instanceof Index idx)
					? (Index<Blob, ACell>) idx : Index.none();
				ACell sv = sessions.get(historySid);
				if (sv instanceof AMap) {
					AMap<AString, ACell> session = (AMap<AString, ACell>) sv;

					// Adapter-owned frame stack: replace wholesale before any
					// turn append. Turn append below still lands on frames[0]
					// so concurrent-intake user turns end up at root.
					if (hasNewFrames) {
						session = session.assoc(K_FRAMES, newFrames);
					}

					if (hasTurns) {
						// Append into frames[0].conversation. Defensive: if
						// the session was created before frames were added,
						// initialise a root frame here.
						AVector<ACell> frames = (session.get(K_FRAMES) instanceof AVector fv)
							? (AVector<ACell>) fv : Vectors.empty();
						AMap<AString, ACell> rootFrame;
						if (frames.count() == 0) {
							rootFrame = Maps.of(
								K_DESCRIPTION,  Strings.EMPTY,
								K_CONVERSATION, Vectors.empty());
							frames = Vectors.of(rootFrame);
						} else {
							rootFrame = (AMap<AString, ACell>) frames.get(0);
						}
						AVector<ACell> rootConv = (rootFrame.get(K_CONVERSATION) instanceof AVector cv)
							? (AVector<ACell>) cv : Vectors.empty();
						for (long i = 0; i < turnsToAppend.count(); i++) {
							rootConv = rootConv.conj(turnsToAppend.get(i));
						}
						rootFrame = rootFrame.assoc(K_CONVERSATION, rootConv);
						frames = frames.assoc(0, rootFrame);
						session = session.assoc(K_FRAMES, frames);

						// Bump session.meta.turns by the number appended
						if (session.get(K_META) instanceof AMap) {
							AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(K_META);
							long current = (meta.get(K_TURNS) instanceof CVMLong cl)
								? cl.longValue() : 0;
							meta = meta.assoc(K_TURNS,
								CVMLong.create(current + turnsToAppend.count()));
							session = session.assoc(K_META, meta);
						}
					}

					if (hasDrain) {
						// Drop the first N entries (presented prefix). Tail
						// (entries that arrived during transition) is preserved.
						AVector<ACell> pending = (session.get(K_PENDING) instanceof AVector pv)
							? (AVector<ACell>) pv : Vectors.empty();
						long drop = Math.min(presentedSessionPendingCount, pending.count());
						AVector<ACell> remaining = Vectors.empty();
						for (long i = drop; i < pending.count(); i++) {
							remaining = remaining.conj(pending.get(i));
						}
						session = session.assoc(K_PENDING, remaining);
					}

					updated = updated.assoc(K_SESSIONS, sessions.assoc(historySid, session));
				}
			}

			// Check whether any session still has pending messages after
			// the drain, or new tasks arrived, or the wake flag is set.
			boolean hasSessionPendingInRecord = false;
			ACell sessionsCell = updated.get(K_SESSIONS);
			if (sessionsCell instanceof Index idx) {
				for (var entry : ((Index<Blob, ACell>) idx).entrySet()) {
					if (entry.getValue() instanceof AMap m) {
						ACell pv = m.get(K_PENDING);
						if (pv instanceof AVector v && v.count() > 0) {
							hasSessionPendingInRecord = true;
							break;
						}
					}
				}
			}

			boolean hasNew = hasSessionPendingInRecord
				|| hasNewTasksNotIn(remainingTasks, presentedTasks);

			// Preserve externally-set SUSPENDED/TERMINATED — these are signals
			// from outside the run loop (e.g. handleSuspend, handleTerminate)
			// and must not be overwritten here. The CAS-retry inside
			// updateAndGet guarantees we see the latest status.
			AString currentStatus = RT.ensureString(r.get(K_STATUS));
			if (!SUSPENDED.equals(currentStatus) && !TERMINATED.equals(currentStatus)) {
				updated = updated.assoc(K_STATUS, hasNew ? RUNNING : SLEEPING);
			}

			return updated;
		});
	}

	// ========== Private helpers ==========

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
