package covia.venue;

import static covia.venue.SessionState.*;

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
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.lattice.Covia;
import covia.api.Fields;

/**
 * Agent state with named atomic mutations and compound transactions.
 *
 * <p>Named mutations encapsulate the record structure. Single-record edits use
 * atomic cursor updates; changes spanning sessions and agent state compose
 * through upstream transactions, without external locking or I/O.</p>
 */
public class AgentState extends ALatticeComponent<ACell> implements TransactionalComponent<ACell, AgentState> {
	@Override
	public AgentState withCursor(AForkableCursor<ACell> draft) {
		return new AgentState(context, draft, agentId);
	}

	private final LatticeContext context;
	private final AForkableCursor<ACell> edits;

	@Override
	public AForkableCursor<ACell> cursor() { return edits; }

	public LatticeContext getContext() { return context; }

	public static final AString KEY_AGENT = Strings.intern("agent");
	public static final AString KEY_EXTERNAL = Strings.intern("external");
	/** Present only for a deliberate stop; faults use {@link #KEY_ERROR}. */
	public static final AString KEY_SUSPENSION = Strings.intern("suspension");
	public static final AString SUSPENSION_BY = Strings.intern("by");
	public static final int MAX_SUSPEND_REASON_LENGTH = 512;
	public static final String SUSPENDED_BY = "Suspended by %s";
	public static final String INVALID_SESSION_FRAMES = "Session frames must be a vector";

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
	/** Handle of this agent's single pending {@code agent:wake} event in the
	 *  venue grid scheduler, or absent when no wake is armed. */
	private static final AString K_WAKE_HANDLE = Strings.intern("wakeHandle");
	/** Monotonic task-row revision. A continuation increments it so a transition
	 *  that started against an older view cannot complete the task concurrently. */
	private static final AString K_TASK_REVISION = Strings.intern("revision");
	/** A2A message IDs already accepted for a task, retained on the task row so
	 *  retries are idempotent even after the session pending entry is drained. */
	private static final AString K_CONTINUATION_IDS = Strings.intern("continuationIds");
	/** Ordered continuation envelopes queued behind the task's original input. */
	private static final AString K_CONTINUATIONS = Strings.intern("continuations");
	/** Number of original/continuation inputs already claimed by run cycles. */
	private static final AString K_PRESENTED_INPUTS = Strings.intern("presentedInputs");

	/** Operation the scheduler fires to wake this agent — a non-forcing,
	 *  non-blocking {@code agent:trigger}. Package-visible so scheduler tests
	 *  assert against the same constant the production wake uses. */
	static final AString TRIGGER_OP = Strings.intern("v/ops/agent/trigger");

	// Turn record field keys (entries in session root frame history, and
	// in child frame conversations when the goal-tree adapter pushes them).
	// See GOAL_TREE.md §Conversation Structure for the full spec.
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
	/** Framework-recorded tool-failure diagnostic turn (#211). */
	public static final AString SOURCE_TOOL       = Strings.intern("tool");

	// Status constants
	public static final AString SLEEPING   = Strings.intern("SLEEPING");
	/** Persisted executor marker; live ownership is verified by AgentAdapter. */
	public static final AString RUNNING    = Strings.intern("RUNNING");
	public static final AString SUSPENDED  = Strings.intern("SUSPENDED");
	public static final AString TERMINATED = Strings.intern("TERMINATED");

	// Public key constants (for transition input/output field names)
	public static final AString KEY_STATE    = K_STATE;
	public static final AString KEY_STATUS   = K_STATUS;
	public static final AString KEY_CONFIG   = K_CONFIG;
	public static final AString KEY_TASKS    = K_TASKS;
	public static final AString KEY_SESSIONS = K_SESSIONS;
	/** Latest generated result per instruction, separate from conversation frames. */
	public static final AString KEY_SUMMARIES = Strings.intern("summaries");
	public static final AString KEY_PENDING  = K_PENDING;
	public static final AString KEY_TIMELINE = K_TIMELINE;
	public static final AString KEY_ERROR    = K_ERROR;
	/** Session-record `frames` key — vector of frame maps. First entry is the
	 *  root frame; subsequent entries are pushed by {@code subgoal}. See
	 *  {@code venue/docs/GOAL_TREE.md}. */
	public static final AString KEY_FRAMES   = K_FRAMES;
	public static final AString KEY_IN_CYCLE = K_IN_CYCLE;
	/** Frame record `description` key. */
	public static final AString KEY_DESCRIPTION  = K_DESCRIPTION;
	/** Legacy frame record {@code conversation} key. New frames use versioned
	 *  history; readers needing the equivalent nested conversation should use
	 *  {@link covia.adapter.agent.GoalTreeContext#archivedConversation(AMap)}
	 *  instead of this key. */
	public static final AString KEY_CONVERSATION = K_CONVERSATION;

	/** Identity of a schedulable thread within an agent — a session or a task.
	 *  Selects which index {@link #setThreadWakeTime} writes the wakeTime into. */
	public enum ThreadKind { SESSION, TASK }
	/** Result of an atomic session intake mutation. */
	public enum SessionIntake { APPLIED, MISSING, LOADS_ON_EXISTING }

	private final AString agentId;

	AgentState(LatticeContext context, AForkableCursor<ACell> cursor,
			AString agentId) {
		super(cursor);
		this.context = context;
		this.agentId = agentId;
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampAgent);
	}

	public AString getAgentId() { return agentId; }

	public boolean exists() { return cursor().get() != null; }

	// ========== Record access ==========

	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getRecord() {
		ACell v = cursor().get();
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/** Replaces the entire record. Use for initialisation only. */
	public void putRecord(AMap<AString, ACell> record) {
		// Preserve the previous version floor when replacing the whole record;
		// the editing cursor alone supplies the new write timestamp.
		cursor().updateAndGet(current -> record.assoc(K_TS, RT.getIn(current, K_TS)));
	}

	/**
	 * Clock for event provenance. Record version stamps belong to the editing cursor.
	 */
	private CVMLong now() {
		return getContext().currentTimestamp();
	}

	// ========== Atomic update (private) ==========

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> update(UnaryOperator<AMap<AString, ACell>> fn) {
		ACell result = cursor().updateAndGet(current ->
			current instanceof AMap ? fn.apply((AMap<AString, ACell>) current) : current);
		return (result instanceof AMap) ? (AMap<AString, ACell>) result : null;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> getAndUpdate(UnaryOperator<AMap<AString, ACell>> fn) {
		ACell old = cursor().getAndUpdate(current ->
			current instanceof AMap ? fn.apply((AMap<AString, ACell>) current) : current);
		return (old instanceof AMap) ? (AMap<AString, ACell>) old : null;
	}

	// ========== Initialisation ==========

	public void initialise(AMap<AString, ACell> config, ACell initialState) {
		initialiseIfAbsent(config, initialState);
	}

	/** Atomically initialises this record, returning false if it already exists. */
	boolean initialiseIfAbsent(AMap<AString, ACell> config, ACell initialState) {
		return initialiseIfAbsent(config, initialState, null);
	}

	boolean initialiseIfAbsent(AMap<AString, ACell> config, ACell initialState, AMap<AString, ACell> definition) {
		AMap<AString, ACell> record = Maps.of(
			K_STATUS, SLEEPING,
			K_TASKS, Index.none(),
			K_SESSIONS, Index.none(),
			K_PENDING, Index.none(),
			K_TIMELINE, Vectors.empty());
		if (config != null) record = record.assoc(K_CONFIG, config);
		if (initialState != null) record = record.assoc(K_STATE, initialState);
		if (definition != null) record = record.assoc(KEY_AGENT, definition);
		AMap<AString, ACell> initial = record;
		return cursor().getAndUpdate(current -> (current == null) ? initial : current) == null;
	}

	/**
	 * Initialises an agent record as a fork of another agent. Copies config
	 * and state, optionally copies timeline. Tasks, sessions, and pending
	 * are fresh; status is SLEEPING. Does nothing if this agent already
	 * exists.
	 */
	public void initialiseFromFork(AMap<AString, ACell> config, ACell state, AVector<ACell> timeline) {
		initialiseFromForkIfAbsent(config, state, timeline);
	}

	/** Atomically initialises a fork record, returning false if it already exists. */
	boolean initialiseFromForkIfAbsent(AMap<AString, ACell> config, ACell state,
			AVector<ACell> timeline) {
		AMap<AString, ACell> record = Maps.of(
			K_STATUS, SLEEPING,
			K_TASKS, Index.none(),
			K_SESSIONS, Index.none(),
			K_PENDING, Index.none(),
			K_TIMELINE, (timeline != null) ? timeline : Vectors.empty());
		if (config != null) record = record.assoc(K_CONFIG, config);
		if (state != null) record = record.assoc(K_STATE, state);
		AMap<AString, ACell> initial = record;
		return cursor().getAndUpdate(current -> (current == null) ? initial : current) == null;
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
	public AMap<AString, ACell> getSession(Blob sid) { return session(sid).getRecord(); }

	/** Session view bound to this agent (including when this agent is a draft).
	 * Missing sessions read as null; mutations never create or resurrect them. */
	public SessionState session(Blob sid) {
		return new SessionState(getContext(), cursor().path(K_SESSIONS, sid), sid);
	}
	/**
	 * Saves a result under its exact instruction, even if the conversation has
	 * advanced during generation. The stored turn count describes the snapshot
	 * covered; a subsequent request can refresh it. A result covering at least
	 * as many turns wins over a late publication. Independent instructions merge.
	 * A deleted/replaced session or stopped agent returns null.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> saveSessionSummary(Blob sid, AMap<AString, ACell> expectedSession,
			AString instruction, AMap<AString, ACell> summary) {
		var result = new java.util.concurrent.atomic.AtomicReference<AMap<AString, ACell>>();
		transact(draft -> {
			result.set(null);
			if (!draft.exists() || SUSPENDED.equals(draft.getStatus()) || TERMINATED.equals(draft.getStatus())) return;
			result.set(draft.session(sid).saveSummary(expectedSession, instruction, summary));
		});
		return result.get();
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
		return ensureSession(sid, caller, null);
	}

	/**
	 * Ensures a session exists, seeding the root frame's loads when it is
	 * minted here. {@code initialLoads} applies only on creation — an existing
	 * session's loads are never touched by this method.
	 */
	public AMap<AString, ACell> ensureSession(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads) {
		final CVMLong created = now();
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			if (sessions.get(sid) != null) return r;
			AMap<AString, ACell> session = newSession(caller, initialLoads, created);
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
		return getSession(sid);
	}

	/** Claim one external turn per session. A retained claim after restart prevents blind replay. */
	@SuppressWarnings("unchecked")
	public boolean claimExternalTurn(Blob sid, Blob jobId, AString caller, ACell content, boolean create) {
		boolean[] accepted = {false};
		transact(draft -> {
			accepted[0] = false;
			if (!draft.exists() || TERMINATED.equals(draft.getStatus()) || SUSPENDED.equals(draft.getStatus())) return;
			if (create) draft.ensureSession(sid, caller);
			accepted[0] = draft.session(sid).claimExternalTurn(jobId, content);
		});
		return accepted[0];
	}

	/**
	 * Atomically creates/reuses a session and adds a task to it. This prevents
	 * agent removal or competing session creation from landing between the two
	 * record changes.
	 */
	public SessionIntake addTaskInSession(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads, Blob taskId, ACell taskData) {
		return intakeSession(sid, caller, initialLoads, true, draft ->
			draft.update(record -> record.assoc(K_TASKS, extractTasks(record).assoc(taskId, taskData))));
	}

	/** Atomically creates/reuses a session and appends one pending envelope. */
	public SessionIntake appendSessionPending(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads, ACell envelope, boolean createIfMissing) {
		return intakeSession(sid, caller, initialLoads, createIfMissing,
			draft -> draft.session(sid).appendPending(envelope));
	}

	/** Session admission and the associated agent change observe one atomic snapshot. */
	private SessionIntake intakeSession(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads, boolean create,
			java.util.function.Consumer<AgentState> change) {
		SessionIntake[] result = {SessionIntake.MISSING};
		transact(draft -> {
			result[0] = SessionIntake.MISSING;
			if (!draft.exists()) return;
			ACell raw = draft.getSessions().get(sid);
			if (raw != null && initialLoads != null) {
				result[0] = SessionIntake.LOADS_ON_EXISTING;
				return;
			}
			if (raw == null) {
				if (!create) return;
				raw = draft.ensureSession(sid, caller, initialLoads);
			}
			if (!(raw instanceof AMap<?, ?>)) return;
			change.accept(draft);
			result[0] = SessionIntake.APPLIED;
		});
		return result[0];
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
	public AVector<ACell> getSessionPending(Blob sid) { return session(sid).pending(); }
	/**
	 * Atomically appends an envelope to {@code sessions[sid].pending} (S3b).
	 * No-op if the session is missing — callers should ensureSession first.
	 */
	@SuppressWarnings("unchecked")
	public void appendSessionPending(Blob sid, ACell envelope) {
		session(sid).appendPending(envelope);
	}

	/** Reads {@code sessions[sid].inCycle}, or null when absent. */
	public ACell getSessionCycleEpoch(Blob sid) { return session(sid).cycleEpoch(); }
	/**
	 * Settles a session's cycle claim without a merge — used when an
	 * interrupted cycle is administratively stopped (operator suspend): the
	 * claim is released so the session does not register as crashed work,
	 * and any zombie writes from the stopped cycle are fenced out.
	 */
	public void clearSessionCycle(Blob sid) {
		if (sid != null) session(sid).clearCycle();
	}
	/**
	 * Removes the session record at {@code sid} (no-op if absent). The
	 * removal is durable: the venue merge is whole-value LWW, so the
	 * dissoc'd snapshot wins by timestamp and the session is not
	 * resurrected on sync. A concurrent {@code mergeRunResult} on the same
	 * session is safe either way — its session write is guarded by an
	 * {@code instanceof AMap} check and skips a vanished session.
	 */
	@SuppressWarnings("unchecked")
	public void removeSession(Blob sid) {
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			return r.assoc(K_SESSIONS, sessions.dissoc(sid));
		});
	}

	/**
	 * Returns true if any session has pending messages.
	 */
	public boolean hasSessionPending() {
		Index<Blob, ACell> sessions = getSessions();
		if (sessions == null || sessions.count() == 0) return false;
		for (var entry : sessions.entrySet()) {
			ACell sv = entry.getValue();
			if (sv instanceof AMap m && sessionHasWork(m)) return true;
		}
		return false;
	}

	/**
	 * Returns the sid (Blob) of the first session with outstanding work
	 * (pending messages), or null if none.
	 */
	public Blob pickSessionWithPending() {
		Index<Blob, ACell> sessions = getSessions();
		if (sessions == null || sessions.count() == 0) return null;
		for (var entry : sessions.entrySet()) {
			ACell sv = entry.getValue();
			if (sv instanceof AMap m && sessionHasWork(m)) return entry.getKey();
		}
		return null;
	}

	public AString getError() {
		AMap<AString, ACell> r = getRecord();
		return (r != null) ? RT.ensureString(r.get(K_ERROR)) : null;
	}

	/** One description for the stop event, cancelled cycle and waiting callers. */
	public static AString suspensionDetail(AMap<AString, ACell> record) {
		if (record == null) return null;
		ACell suspension = record.get(KEY_SUSPENSION);
		AString by = RT.ensureString(RT.getIn(suspension, SUSPENSION_BY));
		if (by == null) return null;
		AString reason = RT.ensureString(RT.getIn(suspension, Fields.REASON));
		return Strings.create(SUSPENDED_BY.formatted(by)
			+ (reason == null || reason.isEmpty() ? "" : ": " + reason));
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

	/** Returns the continuation revision of a task row (legacy rows are zero). */
	public static long taskRevision(ACell taskData) {
		if (taskData instanceof AMap<?, ?> map
				&& map.get(K_TASK_REVISION) instanceof CVMLong revision) {
			return revision.longValue();
		}
		return 0L;
	}

	/** Whether a task row still has original/continuation input not yet claimed. */
	@SuppressWarnings("unchecked")
	public static boolean hasUnpresentedTaskInputs(ACell taskData) {
		if (!(taskData instanceof AMap<?, ?> map)) return false;
		long presented = (map.get(K_PRESENTED_INPUTS) instanceof CVMLong count)
			? count.longValue() : 0L;
		long continuations = (map.get(K_CONTINUATIONS) instanceof AVector<?> v)
			? v.count() : 0L;
		return presented < 1L + continuations;
	}

	/**
	 * Atomically accepts a message for an existing task and queues it on that
	 * task row. Completion or cancellation that removes the task first makes
	 * this return false. The run loop claims each queued input once and presents
	 * it as {@code newInput}; the task's session supplies the conversation state.
	 * Repeated non-null message IDs are accepted idempotently without appending
	 * a second continuation or incrementing the revision.
	 */
	@SuppressWarnings("unchecked")
	public boolean appendTaskContinuation(Blob taskId, Blob sid, ACell envelope,
			AString messageId) {
		java.util.concurrent.atomic.AtomicBoolean accepted =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			accepted.set(false);
			Index<Blob, ACell> tasks = extractTasks(r);
			ACell rawTask = tasks.get(taskId);
			if (!(rawTask instanceof AMap<?, ?>)) return r;
			AMap<AString, ACell> task = (AMap<AString, ACell>) rawTask;
			AString taskSid = RT.ensureString(task.get(Fields.SESSION_ID));
			if (taskSid == null || !sid.toHexString().equals(taskSid.toString())) return r;

			AVector<ACell> ids = (task.get(K_CONTINUATION_IDS) instanceof AVector<?> v)
				? (AVector<ACell>) v : Vectors.empty();
			if (messageId != null) {
				for (long i = 0; i < ids.count(); i++) {
					if (messageId.equals(ids.get(i))) {
						accepted.set(true);
						return r;
					}
				}
			}

			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index<?, ?> idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell rawSession = sessions.get(sid);
			if (!(rawSession instanceof AMap<?, ?>)) return r;
			AVector<ACell> continuations = (task.get(K_CONTINUATIONS) instanceof AVector<?> v)
				? (AVector<ACell>) v : Vectors.empty();

			long revision = taskRevision(task) + 1;
			task = task
				.assoc(K_TASK_REVISION, CVMLong.create(revision))
				.assoc(K_CONTINUATIONS, continuations.conj(envelope));
			if (messageId != null) task = task.assoc(K_CONTINUATION_IDS, ids.conj(messageId));
			accepted.set(true);
			return r.assoc(K_TASKS, tasks.assoc(taskId, task));
		});
		return accepted.get();
	}

	/**
	 * Claims the next input for a task revision exactly once. Input zero is the
	 * original {@code input}; later inputs are continuation envelopes' messages.
	 * Returns null when all inputs were already presented or the revision raced.
	 */
	@SuppressWarnings("unchecked")
	public ACell claimTaskInput(Blob taskId, long expectedRevision) {
		java.util.concurrent.atomic.AtomicReference<ACell> claimed =
			new java.util.concurrent.atomic.AtomicReference<>();
		update(r -> {
			claimed.set(null);
			Index<Blob, ACell> tasks = extractTasks(r);
			ACell rawTask = tasks.get(taskId);
			if (!(rawTask instanceof AMap<?, ?>) || taskRevision(rawTask) != expectedRevision) return r;
			AMap<AString, ACell> task = (AMap<AString, ACell>) rawTask;
			long presented = (task.get(K_PRESENTED_INPUTS) instanceof CVMLong count)
				? count.longValue() : 0L;
			ACell input;
			if (presented == 0) {
				input = task.get(Fields.INPUT);
			} else {
				AVector<ACell> continuations =
					(task.get(K_CONTINUATIONS) instanceof AVector<?> v)
						? (AVector<ACell>) v : Vectors.empty();
				long index = presented - 1;
				if (index >= continuations.count()) return r;
				ACell envelope = continuations.get(index);
				input = RT.getIn(envelope, Fields.MESSAGE);
			}
			claimed.set(input);
			task = task.assoc(K_PRESENTED_INPUTS, CVMLong.create(presented + 1));
			return r.assoc(K_TASKS, tasks.assoc(taskId, task));
		});
		return claimed.get();
	}

	/**
	 * Atomically claims a task by removing and returning its row.
	 *
	 * <p>Exactly one concurrent completion, failure, or cancellation can receive
	 * a non-null result. Later attempts observe the task as absent and fail
	 * immediately.</p>
	 */
	public ACell takeTask(Blob taskId) {
		AMap<AString, ACell> before = getAndUpdate(r -> {
			Index<Blob, ACell> tasks = extractTasks(r);
			if (tasks.get(taskId) == null) return r;
			return r.assoc(K_TASKS, tasks.dissoc(taskId));
		});
		return (before == null) ? null : extractTasks(before).get(taskId);
	}

	/**
	 * Atomically claims a task only if it still has the revision presented to
	 * the current transition cycle. A continuation arriving mid-cycle bumps the
	 * revision and therefore leaves the task queued for a fresh cycle.
	 */
	public ACell takeTask(Blob taskId, long expectedRevision) {
		AMap<AString, ACell> before = getAndUpdate(r -> {
			Index<Blob, ACell> tasks = extractTasks(r);
			ACell task = tasks.get(taskId);
			if (task == null || taskRevision(task) != expectedRevision
					|| hasUnpresentedTaskInputs(task)) return r;
			return r.assoc(K_TASKS, tasks.dissoc(taskId));
		});
		if (before == null) return null;
		ACell task = extractTasks(before).get(taskId);
		return (task != null && taskRevision(task) == expectedRevision
			&& !hasUnpresentedTaskInputs(task)) ? task : null;
	}

	public void addPending(Blob jobId, ACell snapshot) {
		update(r -> r.assoc(K_PENDING, extractPending(r).assoc(jobId, snapshot)));
	}

	/** Removes an outbound wait whose result has been handled by its delivery path. */
	public void removePending(Blob jobId) {
		update(r -> r.assoc(K_PENDING, extractPending(r).dissoc(jobId)));
	}

	/**
	 * Changes only this agent's thread wake intent. Safe on live or draft state.
	 * Returns false for a missing thread. Runtime callers use
	 * {@link Scheduler#setThreadWakeTime} to commit the intent and schedule together.
	 */
	@SuppressWarnings("unchecked")
	public boolean setThreadWakeTime(ThreadKind kind, Blob threadId, long wakeTime) {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(threadId, "threadId");
		AString key = (kind == ThreadKind.SESSION) ? K_SESSIONS : K_TASKS;
		AMap<AString, ACell> before = getAndUpdate(r -> {
			if (!(r.get(key) instanceof Index<?, ?>)) return r;
			Index<Blob, ACell> idx = (Index<Blob, ACell>) r.get(key);
			if (!(idx.get(threadId) instanceof AMap<?, ?>)) return r;
			AMap<AString, ACell> rec = (AMap<AString, ACell>) idx.get(threadId);
			AMap<AString, ACell> updated = (wakeTime > 0)
				? rec.assoc(Fields.WAKE_TIME, CVMLong.create(wakeTime))
				: rec.dissoc(Fields.WAKE_TIME);
			return r.assoc(key, idx.assoc(threadId, updated));
		});
		return RT.getIn(before, key, threadId) instanceof AMap<?, ?>;
	}

	Blob wakeHandle() {
		ACell handle = RT.getIn(getRecord(), K_WAKE_HANDLE);
		return handle instanceof Blob b ? b : null;
	}

	void setWakeHandle(Blob handle) {
		update(r -> handle == null ? r.dissoc(K_WAKE_HANDLE) : r.assoc(K_WAKE_HANDLE, handle));
	}

	long earliestWake() {
		AMap<AString, ACell> record = getRecord();
		return record == null ? 0 : earliestWake(record);
	}

	/** Earliest positive {@code wakeTime} across all sessions and tasks, or 0 if none. */
	private static long earliestWake(AMap<AString, ACell> record) {
		long min = minWakeIn(record.get(K_SESSIONS), Long.MAX_VALUE);
		min = minWakeIn(record.get(K_TASKS), min);
		return (min == Long.MAX_VALUE) ? 0L : min;
	}

	@SuppressWarnings("unchecked")
	private static long minWakeIn(ACell idxCell, long min) {
		if (!(idxCell instanceof Index)) return min;
		Index<Blob, ACell> idx = (Index<Blob, ACell>) idxCell;
		long cnt = idx.count();
		for (long i = 0; i < cnt; i++) {
			ACell rec = idx.entryAt(i).getValue();
			if (!(rec instanceof AMap)) continue;
			ACell wt = ((AMap<AString, ACell>) rec).get(Fields.WAKE_TIME);
			if (wt instanceof CVMLong l && l.longValue() > 0) {
				min = Math.min(min, l.longValue());
			}
		}
		return min;
	}

	// ========== CAS operations ==========

	/** Atomic CAS: SUSPENDED → SLEEPING, clear fault and deliberate stop. */
	public boolean tryResume() {
		AMap<AString, ACell> before = getAndUpdate(r ->
			SUSPENDED.equals(RT.ensureString(r.get(K_STATUS)))
				? r.assoc(K_STATUS, SLEEPING).dissoc(K_ERROR).dissoc(KEY_SUSPENSION) : r);
		return SUSPENDED.equals(RT.ensureString(before.get(K_STATUS)));
	}

	/**
	 * Atomic CAS: SLEEPING (or a stale RUNNING marker) → RUNNING. Returns false
	 * when the agent is suspended or terminated, so an administrative stop that
	 * landed after the caller's status read is never overwritten.
	 */
	public boolean tryRun() {
		AMap<AString, ACell> before = getAndUpdate(r -> {
			AString cur = RT.ensureString(r.get(K_STATUS));
			return (SLEEPING.equals(cur) || RUNNING.equals(cur)) ? r.assoc(K_STATUS, RUNNING) : r;
		});
		AString was = RT.ensureString(before.get(K_STATUS));
		return SLEEPING.equals(was) || RUNNING.equals(was);
	}

	/**
	 * Records a deliberate stop atomically. Repeated stops replace its provenance.
	 * Returns the committed record, or null if deletion won the race.
	 */
	public AMap<AString, ACell> suspend(AString by, AString reason) {
		Objects.requireNonNull(by);
		AMap<AString, ACell> stop = Maps.of(SUSPENSION_BY, by, K_TS, now());
		if (reason != null) {
			String text = reason.toString();
			stop = stop.assoc(Fields.REASON, Strings.create(
				text.substring(0, Math.min(text.length(), MAX_SUSPEND_REASON_LENGTH))));
		}
		AMap<AString, ACell> suspension = stop;
		AMap<AString, ACell> stopped = update(r -> TERMINATED.equals(r.get(K_STATUS)) ? r
			: r.assoc(K_STATUS, SUSPENDED).assoc(KEY_SUSPENSION, suspension).dissoc(K_ERROR));
		return stopped != null && SUSPENDED.equals(stopped.get(K_STATUS)) ? stopped : null;
	}

	/** Sets a fault suspension without overwriting a deliberate stop racing the failure. */
	public void suspend(AString error) {
		update(r -> faultSuspension(r, error));
	}

	private static AMap<AString, ACell> faultSuspension(AMap<AString, ACell> r, AString error) {
		if (r.containsKey(KEY_SUSPENSION) || TERMINATED.equals(r.get(K_STATUS))) return r;
		return r.assoc(K_ERROR, error).assoc(K_STATUS, SUSPENDED);
	}

	/**
	 * Suspends the agent with an error and drops the task queue, in one CAS.
	 * Used when a transition fails: callers of the queued tasks have already
	 * been notified via {@code failAllPendingForAgent}, so leaving stale tasks
	 * around would only cause the same failure to replay on the next resume.
	 * Operator can fix the underlying issue and {@code agent_resume} to a
	 * clean state; callers re-submit if they want to retry.
	 */
	public void suspendAndDrain(AString error) {
		update(r -> faultSuspension(r, error).assoc(K_TASKS, Index.none()));
	}

	/**
	 * Records an administratively cancelled cycle, drains its queued tasks and
	 * releases the session claim in one write. Never re-suspends a resumed agent.
	 * Returns the previous record for notifying exactly the callers we drained.
	 */
	public AMap<AString, ACell> finishSuspendedCycle(AMap<AString, ACell> entry, Blob sid) {
		var before = new java.util.concurrent.atomic.AtomicReference<AMap<AString, ACell>>();
		transact(draft -> {
			before.set(null);
			AMap<AString, ACell> r = draft.getRecord();
			if (r == null || !SUSPENDED.equals(r.get(K_STATUS))) return;
			before.set(r);
			AMap<AString, ACell> stopped = entry;
			ACell suspension = r.get(KEY_SUSPENSION);
			if (suspension != null) stopped = stopped.assoc(KEY_SUSPENSION, suspension)
				.assoc(Fields.RESULT, suspensionDetail(r));
			AMap<AString, ACell> recorded = stopped;
			draft.clearSessionCycle(sid);
			draft.update(current -> current.assoc(K_TASKS, Index.none())
				.assoc(K_TIMELINE, extractTimeline(current).conj(recorded)));
		});
		return before.get();
	}

	/**
	 * Clears the persisted executor marker after a clean exit or at startup.
	 * Administrative stop/terminal states always win.
	 */
	public void sleep() {
		update(r -> {
			AString cur = RT.ensureString(r.get(K_STATUS));
			if (SUSPENDED.equals(cur) || TERMINATED.equals(cur)) return r;
			return r.assoc(K_STATUS, SLEEPING);
		});
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
				// No state.config special case: config's single home is
				// record.config (#144); the adapter rejects state.config input.
				u = u.assoc(K_STATE, merge(existing, incoming));
			} else if (state != null) {
				u = u.assoc(K_STATE, state);
			}
			return u;
		});
	}

	/**
	 * A transition's state change, applied to the record's <em>current</em>
	 * state. Every writer of the record is a function serialised by the
	 * cursor's CAS; a transition read its state minutes ago and must not
	 * overwrite what arrived meanwhile, so its result is applied as the
	 * difference from the snapshot it fired with. Per key of the result:
	 * <ul>
	 *   <li>changed or added by the transition (differs from the snapshot) →
	 *       the transition's value;</li>
	 *   <li>present in the snapshot, absent from the result → removed (the
	 *       transition dropped it);</li>
	 *   <li>untouched by the transition → whatever the record holds now, so an
	 *       external update survives.</li>
	 * </ul>
	 * A null result is no change. When any of the three is not a map the
	 * result replaces the state wholesale.
	 */
	@SuppressWarnings("unchecked")
	public static ACell applyStateChange(ACell current, ACell snapshot, ACell returned) {
		if (returned == null) return current;
		if (!(returned instanceof AMap) || !(snapshot instanceof AMap) || !(current instanceof AMap)) {
			return returned;
		}
		AMap<ACell, ACell> snap = (AMap<ACell, ACell>) snapshot;
		AMap<ACell, ACell> ret = (AMap<ACell, ACell>) returned;
		AMap<ACell, ACell> out = (AMap<ACell, ACell>) current;
		for (java.util.Map.Entry<ACell, ACell> e : ret.entrySet()) {
			ACell k = e.getKey();
			if (!snap.containsKey(k) || !java.util.Objects.equals(snap.get(k), e.getValue())) {
				out = out.assoc(k, e.getValue());
			}
		}
		for (ACell k : snap.keySet()) {
			if (!ret.containsKey(k)) out = out.dissoc(k);
		}
		return out;
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
	 * Atomic merge with frame-conversation append + session pending drain.
	 *
	 * <p>When {@code historySid != null}:
	 * <ul>
	 *   <li>If {@code turnsToAppend} is non-empty, turns are appended to
	 *       the root frame history of {@code sessions[historySid]} and
	 *       {@code meta.turns} is bumped. (The root frame is minted lazily
	 *       here if the session has no frames yet.)</li>
	 *   <li>The first {@code presentedSessionPendingCount} entries of
	 *       {@code sessions[historySid].pending} are dropped — the run loop
	 *       snapshots the count pre-transition and passes it here so that
	 *       messages arriving during the transition (the tail) are preserved
	 *       for the next cycle.</li>
	 * </ul>
	 * All performed inside the same CAS as the timeline / state writes.
	 * {@code cycleTokens}, when non-null, is added to the picked session's
	 * {@code meta.tokens} in that CAS.</p>
	 *
	 * <p>This atomic-update guarantee matches the deferred-completion
	 * ordering invariant: an external observer never sees a cycle that
	 * wrote the timeline but not the frame conversation / pending drain.</p>
	 *
	 * <p>When {@code newFrames} is non-null, the picked session's
	 * {@code frames} vector is replaced wholesale before any turn append —
	 * the adapter owns its own frame-stack changes (subgoal pushes/pops,
	 * tool turns, compactions). Any {@code turnsToAppend} are still applied
	 * to the root frame history on top, which is how concurrent
	 * intake (drained pending envelopes → user turns at root) co-exists
	 * with adapter-owned stack emission.</p>
	 *
	 * <p>{@code state} is merged, not replaced: the transition's result is
	 * applied as its <em>change</em> against {@code snapshotState} — the value
	 * it fired with — onto whatever the record holds now
	 * ({@link #applyStateChange}). An {@code agent:update} that landed while
	 * the transition ran therefore survives on every key the transition did
	 * not touch, and the transition wins a genuine same-key conflict. A null
	 * result means the transition made no state change.</p>
	 *
	 * @return The new record (check status to determine if loop should continue)
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> mergeRunResult(
			ACell snapshotState,
			ACell newState,
			AMap<AString, ACell> taskResults,
			AMap<AString, ACell> timelineEntry,
			Blob historySid,
			AVector<ACell> turnsToAppend,
			long presentedSessionPendingCount,
			AVector<ACell> newFrames,
			AMap<AString, ACell> cycleTokens) {
		var result = new java.util.concurrent.atomic.AtomicReference<AMap<AString, ACell>>();
		transact(draft -> {
			draft.update(r -> r
				.assoc(K_STATE, applyStateChange(r.get(K_STATE), snapshotState, newState))
				.assoc(K_TASKS, removeCompletedTasks(extractTasks(r), taskResults))
				.assoc(K_TIMELINE, extractTimeline(r).conj(timelineEntry))
				.dissoc(K_ERROR));
			if (historySid != null) draft.session(historySid).finishCycle(
				newFrames, turnsToAppend, presentedSessionPendingCount, cycleTokens);
			result.set(draft.getRecord());
		});
		return result.get();
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

}
