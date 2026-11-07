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
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.lattice.ACoviaComponent;
import covia.lattice.AccessPolicy;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.Owner;
import covia.lattice.Position;
import covia.lattice.ValuePath;
import covia.lattice.Workspace;
import covia.api.Fields;

/**
 * Agent state with named atomic mutations and compound transactions.
 *
 * <p>Named mutations encapsulate the record structure. Single-record edits use
 * atomic cursor updates; changes spanning sessions and agent state compose
 * through upstream transactions, without external locking or I/O.</p>
 */
public class AgentState extends ACoviaComponent<ACell> implements TransactionalComponent<ACell, AgentState>, Owner {
	@Override
	public AgentState withCursor(AForkableCursor<ACell> draft) {
		return new AgentState(context, draft, owner, agentId);
	}

	private final VenueContext context;
	private final AForkableCursor<ACell> edits;

	@Override
	public AForkableCursor<ACell> cursor() { return edits; }

	public VenueContext getContext() { return context; }

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
	private static final AString K_RESOLVED = Strings.intern("resolved");
	static final AString K_SESSIONS = Strings.intern("sessions");
	private static final AString K_PENDING  = Strings.intern("pending");
	private static final AString K_TIMELINE = Strings.intern("timeline");
	private static final AString K_ERROR    = Strings.intern("error");
	/** The agent's own workspace, which it reaches as {@code n/}. */
	private static final AString K_N        = Strings.intern("n");
	/** Handle of this agent's single pending {@code agent:trigger} wake event in the
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
	/** Set on a task row that yielded while something it waits on is outstanding:
	 *  it runs again only when something new arrives for it ({@link #isTaskReady}). */
	private static final AString K_PARKED = Strings.intern("parked");

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
	/** A tracked tool call's late outcome, delivered by the venue (#607). Only
	 *  the venue marks an envelope so; the model profile's {@code lateToolResults}
	 *  decides how the turn renders (AGENT_CONTEXT.md §5.7). */
	public static final AString SOURCE_TOOL_OUTCOME = Strings.intern("toolOutcome");
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
	/** Tasks the agent has resolved whose requests are not yet settled ({@link #resolveTask}). */
	public static final AString KEY_RESOLVED = K_RESOLVED;
	public static final AString KEY_SESSIONS = K_SESSIONS;
	/** Latest generated result per instruction, separate from conversation frames. */
	public static final AString KEY_SUMMARIES = Strings.intern("summaries");
	public static final AString KEY_PENDING  = K_PENDING;
	public static final AString KEY_TIMELINE = K_TIMELINE;
	public static final AString KEY_ERROR    = K_ERROR;
	/** Transition input: the provenance of the task input shown this cycle,
	 *  as its turn records it — {@code {caller?, authentication?, source?}}
	 *  ({@link covia.adapter.AgentAdapter#withRecordedProvenance}). */
	public static final AString KEY_INPUT_SOURCE = Strings.intern("inputSource");
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
	/** Result of an atomic session intake mutation; MISSING when the agent, or the session, is not there to take it. */
	public enum SessionIntake { APPLIED, MISSING, LOADS_ON_EXISTING }

	private final AString owner;
	private final AString agentId;

	/** Who may use this agent's data: whoever holds authority over {@code <owner>/g/<id>/<path>}. */
	private final AccessPolicy policy;

	/**
	 * @param owner DID of the user whose agent this is, or null for a record outside any user
	 * @param agentId The agent's id in its owner's {@code g/}
	 */
	AgentState(VenueContext context, AForkableCursor<ACell> cursor,
			AString owner, AString agentId) {
		super(cursor);
		this.context = context;
		this.owner = owner;
		this.agentId = agentId;
		this.policy = (owner == null) ? null : agentPolicy(owner, agentId);
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampAgent);
	}

	private static AccessPolicy agentPolicy(AString owner, AString agentId) {
		String base = owner + "/" + Namespace.G + "/" + agentId;
		return (ctx, ability, path) -> AccessPolicy.Need.authorityOver(AccessPolicy.address(base, path));
	}

	public AString getAgentId() { return agentId; }

	/** DID of the user whose agent this is. */
	public AString getOwner() { return owner; }

	// ========== Record access ==========

	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getRecord() {
		ACell v = cursor().getOrDefault(null);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/** The agent as callers see it: its record. */
	@Override
	public ACell read() {
		return getRecord();
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
	 * The tasks this agent has resolved whose requests are not yet settled, by
	 * task ID: each holds the completion its request settles with
	 * ({@link #resolveTask}).
	 */
	public Index<Blob, ACell> getResolved() {
		AMap<AString, ACell> r = getRecord();
		return (r == null) ? Index.none() : extractResolved(r);
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
		return new SessionState(getContext(), cursor().path(K_SESSIONS, sid), owner, agentId, sid);
	}

	/** The agent's own workspace ({@code n}); a change to it needs the agent. */
	public Workspace workspace() {
		return Workspace.inRecord(cursor(), K_N);
	}

	/**
	 * {@code n} is the agent's workspace and {@code sessions/<id>} a session,
	 * named by its id in hex, whether or not it exists; an id that is not hex
	 * names no session. The rest of the record is read as stored. The agent's
	 * policy covers whatever no session claims.
	 */
	@Override
	public Position locate(Path path) {
		Route route = route(path);
		if (route != null) return route.child().locate(path.slice(route.taken()));
		if (!path.isEmpty()) {
			AString segment = path.get(0);
			if (K_N.equals(segment)) return workspace().locate(path.slice(1));
			if (K_SESSIONS.equals(segment) && path.count() > 1) return Position.invalid(path.slice(1));
		}
		return super.locate(path);
	}

	/** A session ({@code sessions/<id>}), named by its id in hex, whether or not it exists. */
	@Override
	public Route route(Path path) {
		if (path.count() < 2 || !K_SESSIONS.equals(path.get(0))) return null;
		Blob sid = ValuePath.blobKey(path.get(1));
		return (sid == null) ? null : new Route(session(sid), 2);
	}

	/** Whoever holds authority over {@code <owner>/g/<id>/<path>}; none outside any user. */
	@Override
	public AccessPolicy policy() {
		return policy;
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
		return transact(draft -> {
			if (!draft.exists() || SUSPENDED.equals(draft.getStatus()) || TERMINATED.equals(draft.getStatus())) return null;
			return draft.session(sid).saveSummary(expectedSession, instruction, summary);
		});
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
		return transact(draft -> {
			if (!draft.exists() || TERMINATED.equals(draft.getStatus()) || SUSPENDED.equals(draft.getStatus())) return false;
			if (create) draft.ensureSession(sid, caller);
			return draft.session(sid).claimExternalTurn(jobId, content);
		});
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

	/**
	 * Session admission and the associated agent change observe one atomic
	 * snapshot. A terminated agent takes nothing: its work was given back when
	 * it terminated ({@link #terminate}).
	 */
	private SessionIntake intakeSession(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads, boolean create,
			java.util.function.Consumer<AgentState> change) {
		return transact(draft -> {
			if (!draft.exists() || TERMINATED.equals(draft.getStatus())) return SessionIntake.MISSING;
			ACell raw = draft.getSessions().get(sid);
			if (raw != null && initialLoads != null) return SessionIntake.LOADS_ON_EXISTING;
			if (raw == null) {
				if (!create) return SessionIntake.MISSING;
				raw = draft.ensureSession(sid, caller, initialLoads);
			}
			if (!(raw instanceof AMap<?, ?>)) return SessionIntake.MISSING;
			change.accept(draft);
			return SessionIntake.APPLIED;
		});
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

	/**
	 * Terminates the agent in one write, returning its record from just
	 * before. Intake refuses a terminated agent, so the tasks that record holds
	 * are all the work the agent will ever have to give back.
	 *
	 * @return The record before termination, or null when there is no agent
	 */
	public AMap<AString, ACell> terminate() {
		return getAndUpdate(r -> r.assoc(K_STATUS, TERMINATED));
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

	/** Removes a task, queued or resolved. */
	public void removeTask(Blob taskId) {
		update(r -> {
			AMap<AString, ACell> next = r.assoc(K_TASKS, extractTasks(r).dissoc(taskId));
			Index<Blob, ACell> resolved = extractResolved(r);
			return resolved.containsKey(taskId) ? next.assoc(K_RESOLVED, resolved.dissoc(taskId)) : next;
		});
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
	 * The input a task has not been shown yet: its original {@code input}
	 * first, then each continuation's message in turn. Null once every input
	 * has been shown.
	 */
	@SuppressWarnings("unchecked")
	public static ACell nextTaskInput(ACell taskData) {
		if (!hasUnpresentedTaskInputs(taskData)) return null;
		AMap<AString, ACell> task = (AMap<AString, ACell>) taskData;
		long presented = (task.get(K_PRESENTED_INPUTS) instanceof CVMLong count) ? count.longValue() : 0L;
		if (presented == 0) return task.get(Fields.INPUT);
		AVector<ACell> continuations = (AVector<ACell>) task.get(K_CONTINUATIONS);
		return RT.getIn(continuations.get(presented - 1), Fields.MESSAGE);
	}

	/**
	 * Who {@link #nextTaskInput} came from, for its provenance: the task row for
	 * its original input, else the continuation's envelope. Null once every
	 * input has been shown.
	 */
	@SuppressWarnings("unchecked")
	public static ACell nextTaskInputSource(ACell taskData) {
		if (!hasUnpresentedTaskInputs(taskData)) return null;
		AMap<AString, ACell> task = (AMap<AString, ACell>) taskData;
		long presented = (task.get(K_PRESENTED_INPUTS) instanceof CVMLong count) ? count.longValue() : 0L;
		if (presented == 0) return task;
		return ((AVector<ACell>) task.get(K_CONTINUATIONS)).get(presented - 1);
	}

	/** What {@link #beginCycle} took. */
	public enum CycleStart {
		/** The picked task's row changed after it was picked, so nothing was taken. */
		MOVED,
		/** The task's next input was taken; there was no session to claim. */
		NO_SESSION,
		/** The session was claimed and the cycle's input is in its conversation. */
		SESSION
	}

	/**
	 * Begins a cycle in one transaction, so what the cycle takes is recorded
	 * where it is taken. The picked task, while its row is exactly as picked,
	 * has its next input ({@link #nextTaskInput}) marked shown and is no longer
	 * parked: it is in a cycle now, so a restart before the cycle ends finds it
	 * interrupted rather than waiting. The picked session, when it exists, is
	 * claimed and given the cycle's input ({@link SessionState#beginCycle}).
	 *
	 * @param taskId the picked task, or null
	 * @param pickedTask the task's row as it was picked
	 * @param sid the picked session, or null
	 * @param epoch the cycle's claim on the session
	 * @param turns user turns for the presented messages, then the task's input
	 * @param drainCount how many presented messages leave the session's pending queue
	 */
	@SuppressWarnings("unchecked")
	public CycleStart beginCycle(Blob taskId, ACell pickedTask, Blob sid, ACell epoch,
			AVector<ACell> turns, long drainCount) {
		return transact(draft -> {
			if (taskId != null) {
				ACell current = draft.getTasks().get(taskId);
				if (current == null || !current.equals(pickedTask)) return CycleStart.MOVED;
				AMap<AString, ACell> task = ((AMap<AString, ACell>) current).dissoc(K_PARKED);
				if (hasUnpresentedTaskInputs(task)) {
					long presented = (task.get(K_PRESENTED_INPUTS) instanceof CVMLong count) ? count.longValue() : 0L;
					task = task.assoc(K_PRESENTED_INPUTS, CVMLong.create(presented + 1));
				}
				AMap<AString, ACell> claimed = task;
				draft.update(r -> r.assoc(K_TASKS, extractTasks(r).assoc(taskId, claimed)));
			}
			return (sid != null && draft.session(sid).beginCycle(epoch, turns, drainCount))
				? CycleStart.SESSION : CycleStart.NO_SESSION;
		});
	}

	/**
	 * Atomically claims a task by removing and returning its row.
	 *
	 * <p>Exactly one concurrent completion, failure, or cancellation can receive
	 * a non-null result. Later attempts observe the task as absent and fail
	 * immediately.</p>
	 */
	public ACell takeTask(Blob taskId) {
		return takeTask(taskId, -1);
	}

	/**
	 * Atomically claims a task only if it still has the revision presented to
	 * the current transition cycle. A continuation arriving mid-cycle bumps the
	 * revision and therefore leaves the task queued for a fresh cycle.
	 *
	 * @param expectedRevision the revision presented, or negative for any
	 */
	public ACell takeTask(Blob taskId, long expectedRevision) {
		return claim(taskId, expectedRevision, null);
	}

	/**
	 * Resolves a task: in one transaction its row leaves the queue, as
	 * {@link #takeTask(Blob, long)} takes it, and {@code completion} is recorded
	 * under {@link #KEY_RESOLVED}. It stays there until the task's request is
	 * settled with it, so a resolution survives a restart before then. A
	 * resolved task is no longer queued: it cannot be taken, cancelled or
	 * resolved again.
	 *
	 * @param expectedRevision the revision presented, or negative for any
	 * @param completion what the request settles with
	 * @return the row taken, or null when the task is not queued at that revision
	 */
	public ACell resolveTask(Blob taskId, long expectedRevision, AMap<AString, ACell> completion) {
		return claim(taskId, expectedRevision, java.util.Objects.requireNonNull(completion));
	}

	/** Takes a task's row at the expected revision, recording {@code completion} when given. */
	private ACell claim(Blob taskId, long expectedRevision, AMap<AString, ACell> completion) {
		AMap<AString, ACell> before = getAndUpdate(r -> {
			Index<Blob, ACell> tasks = extractTasks(r);
			if (!claimable(tasks.get(taskId), expectedRevision)) return r;
			AMap<AString, ACell> next = r.assoc(K_TASKS, tasks.dissoc(taskId));
			return (completion == null) ? next
				: next.assoc(K_RESOLVED, extractResolved(r).assoc(taskId, completion));
		});
		if (before == null) return null;
		ACell task = extractTasks(before).get(taskId);
		return claimable(task, expectedRevision) ? task : null;
	}

	private static boolean claimable(ACell task, long expectedRevision) {
		if (task == null) return false;
		return expectedRevision < 0
			|| (taskRevision(task) == expectedRevision && !hasUnpresentedTaskInputs(task));
	}

	/**
	 * Parks a task that yielded while something it waits on is outstanding, so
	 * the run loop passes it over until something new arrives for it. Does
	 * nothing if the revision moved: a continuation arrived, and the task is
	 * ready anyway.
	 */
	@SuppressWarnings("unchecked")
	public void parkTask(Blob taskId, long expectedRevision) {
		update(r -> {
			Index<Blob, ACell> tasks = extractTasks(r);
			ACell task = tasks.get(taskId);
			if (!(task instanceof AMap<?, ?>) || taskRevision(task) != expectedRevision) return r;
			return r.assoc(K_TASKS, tasks.assoc(taskId,
				((AMap<AString, ACell>) task).assoc(K_PARKED, CVMBool.TRUE)));
		});
	}

	/** Whether a task row has been parked by a yield. */
	public static boolean isParked(ACell taskData) {
		return taskData instanceof AMap<?, ?> map && CVMBool.TRUE.equals(map.get(K_PARKED));
	}

	/**
	 * Whether a task has something to run on: it has not been parked, or it
	 * has an input it has not been shown (a continuation, or the outcome of
	 * something its cycle waited on), or its wake time has come, or its
	 * session has messages waiting. A task
	 * that is not parked but has been shown its input was interrupted
	 * mid-cycle, and runs again.
	 */
	public boolean isTaskReady(ACell taskData, long now) {
		return !isParked(taskData) || hasNewWorkFor(taskData, now);
	}

	/**
	 * Whether something new is there for a task, parked or not: an input it
	 * has not been shown, a wake time that has come, or messages waiting in
	 * its session.
	 */
	@SuppressWarnings("unchecked")
	public boolean hasNewWorkFor(ACell taskData, long now) {
		if (!(taskData instanceof AMap<?, ?>)) return false;
		AMap<AString, ACell> task = (AMap<AString, ACell>) taskData;
		if (hasUnpresentedTaskInputs(task)) return true;
		if (task.get(Fields.WAKE_TIME) instanceof CVMLong wake && wake.longValue() <= now) return true;
		AString sid = RT.ensureString(task.get(Fields.SESSION_ID));
		Blob sessionId = (sid == null) ? null : Blob.parse(sid.toString());
		AMap<AString, ACell> session = (sessionId == null) ? null : getSession(sessionId);
		return session != null && SessionState.sessionHasWork(session);
	}

	/** Whether any queued task is ready to run ({@link #isTaskReady}). */
	public boolean hasReadyTask(long now) {
		for (var entry : getTasks().entrySet()) {
			if (isTaskReady(entry.getValue(), now)) return true;
		}
		return false;
	}

	/** Whether an outbound wait recorded for this task, such as an unanswered HITL ask, is open. */
	public boolean hasPendingFor(Blob taskId) {
		AString hex = Strings.create(taskId.toHexString());
		for (var entry : getPending().entrySet()) {
			if (hex.equals(RT.getIn(entry.getValue(), Fields.TASK_ID))) return true;
		}
		return false;
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
		// A deleted agent has no record to resume.
		return before != null && SUSPENDED.equals(RT.ensureString(before.get(K_STATUS)));
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
		if (before == null) return false; // a deleted agent does not run
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
	 * Records an administratively cancelled cycle and releases the session claim
	 * in one write. The queued tasks stay, the cancelled one among them, and run
	 * after resume (AGENT_LOOP.md §3.4). Never re-suspends a resumed agent.
	 * Returns the record before the write, or null when the agent is no longer
	 * suspended.
	 */
	public AMap<AString, ACell> finishSuspendedCycle(AMap<AString, ACell> entry, Blob sid) {
		return transact(draft -> {
			AMap<AString, ACell> r = draft.getRecord();
			if (r == null || !SUSPENDED.equals(r.get(K_STATUS))) return null;
			AMap<AString, ACell> stopped = entry;
			ACell suspension = r.get(KEY_SUSPENSION);
			if (suspension != null) stopped = stopped.assoc(KEY_SUSPENSION, suspension)
				.assoc(Fields.RESULT, suspensionDetail(r));
			AMap<AString, ACell> recorded = stopped;
			draft.clearSessionCycle(sid);
			draft.update(current -> current.assoc(K_TIMELINE, extractTimeline(current).conj(recorded)));
			return r;
		});
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
	 * Changes the agent's config and merges state fields into the record, in
	 * one atomic update.
	 *
	 * <p>{@code config} receives the config the record holds (null when it has
	 * none) and returns its replacement, so anything the new config depends on,
	 * such as what the caller may confer, is decided against the config it
	 * replaces. It may run again on a retry, so it must be pure, and an
	 * exception from it leaves the record unchanged. Null leaves config alone.</p>
	 *
	 * <p>Incoming state is shallow-merged into the existing state so that
	 * updating a single field does not wipe its siblings: top-level keys in the
	 * incoming map override the existing ones, and the rest are preserved.</p>
	 *
	 * @return The config the record holds after the update, or null when the
	 *         record does not exist
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> updateConfigAndState(UnaryOperator<AMap<AString, ACell>> config, ACell state) {
		AMap<AString, ACell> record = update(r -> {
			AMap<AString, ACell> u = r;
			if (config != null) {
				ACell existing = r.get(K_CONFIG);
				u = u.assoc(K_CONFIG, config.apply((existing instanceof AMap) ? (AMap<AString, ACell>) existing : null));
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
		ACell committed = (record == null) ? null : record.get(K_CONFIG);
		return (committed instanceof AMap) ? (AMap<AString, ACell>) committed : null;
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
	 * Atomic merge of a finished cycle: state, completed tasks and the
	 * timeline entry, and, when {@code historySid != null}, the end of the
	 * cycle on that session. {@code turnsToAppend} (the runtime's own turns
	 * and its reply, for a runtime that leaves the conversation to the run
	 * loop) are appended to the session's root frame and {@code meta.turns} is
	 * bumped; {@code cycleTokens}, when non-null, is added to its
	 * {@code meta.tokens}; and the cycle's claim ends. The cycle's input
	 * entered the conversation when it began ({@link #beginCycle}), so an
	 * observer never sees a cycle's timeline entry without its input.
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
			AMap<AString, ACell> cycleTokens) {
		return transact(draft -> {
			draft.update(r -> r
				.assoc(K_STATE, applyStateChange(r.get(K_STATE), snapshotState, newState))
				.assoc(K_TASKS, removeCompletedTasks(extractTasks(r), taskResults))
				.assoc(K_TIMELINE, extractTimeline(r).conj(timelineEntry))
				.dissoc(K_ERROR));
			if (historySid != null) draft.session(historySid).finishCycle(turnsToAppend, cycleTokens);
			return draft.getRecord();
		});
	}

	// ========== Private helpers ==========

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> extractTasks(AMap<AString, ACell> r) {
		ACell v = r.get(K_TASKS);
		return (v instanceof Index) ? (Index<Blob, ACell>) v : Index.none();
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> extractResolved(AMap<AString, ACell> r) {
		ACell v = r.get(K_RESOLVED);
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
