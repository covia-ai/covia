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
import convex.core.data.prim.CVMBool;
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
	/** Session-tier loads — the context scope chain's session level (#142). */
	private static final AString K_LOADS = Strings.intern("loads");
	private static final AString K_PENDING  = Strings.intern("pending");
	private static final AString K_TIMELINE = Strings.intern("timeline");
	private static final AString K_ERROR    = Strings.intern("error");
	/** Handle of this agent's single pending {@code agent:wake} event in the
	 *  venue grid scheduler, or absent when no wake is armed. */
	private static final AString K_WAKE_HANDLE = Strings.intern("wakeHandle");

	/** Operation the scheduler fires to wake this agent — a non-forcing,
	 *  non-blocking {@code agent:trigger}. Package-visible so scheduler tests
	 *  assert against the same constant the production wake uses. */
	static final AString TRIGGER_OP = Strings.intern("v/ops/agent/trigger");

	// Session record field keys (scoped within a single session map)
	private static final AString K_C        = Strings.intern("c");
	private static final AString K_FRAMES   = Strings.intern("frames");
	/** Session cycle epoch: present while a transition cycle owns this session
	 *  (set by {@link #beginSessionCycle}, cleared by {@link #mergeRunResult}).
	 *  Serves three duties: write fence for {@link #updateSessionFrames} (a
	 *  cancelled cycle's zombie thread cannot write once a new epoch claims the
	 *  session), work signal (a session mid-cycle counts as having work, so an
	 *  interrupted cycle re-runs even though its pending was already drained),
	 *  and crash detector (a stale epoch with no live loop means the merge
	 *  never ran — the resume path repairs before continuing). */
	private static final AString K_IN_CYCLE = Strings.intern("inCycle");
	private static final AString K_META     = Strings.intern("meta");
	private static final AString K_PARTIES  = Strings.intern("parties");
	private static final AString K_CREATED  = Strings.intern("created");
	private static final AString K_TURNS    = Strings.intern("turns");

	// Frame record field keys (entries in session.frames). See GOAL_TREE.md.
	private static final AString K_DESCRIPTION  = Strings.intern("description");
	private static final AString K_CONVERSATION = Strings.intern("conversation");

	// Turn record field keys (entries in session.frames[0].conversation, and
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
	public static final AString KEY_IN_CYCLE = K_IN_CYCLE;
	/** Frame record `description` key. */
	public static final AString KEY_DESCRIPTION  = K_DESCRIPTION;
	/** Frame record `conversation` key — vector of turn envelopes and/or
	 *  compacted segments. */
	public static final AString KEY_CONVERSATION = K_CONVERSATION;

	/** Identity of a schedulable thread within an agent — a session or a task.
	 *  Selects which index {@link #setThreadWakeTime} writes the wakeTime into. */
	public enum ThreadKind { SESSION, TASK }

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
		return ensureSession(sid, caller, null);
	}

	/**
	 * Ensures a session exists, seeding its session-tier loads (#142) when it
	 * is minted here. {@code initialLoads} applies only on creation — an
	 * existing session's loads are never touched by this method.
	 */
	public AMap<AString, ACell> ensureSession(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads) {
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
			if (initialLoads != null && initialLoads.count() > 0) {
				session = session.assoc(K_LOADS, initialLoads);
			}
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
	 * Atomically claims a session for a transition cycle: sets the
	 * {@code inCycle} epoch, appends the cycle's input turns to
	 * {@code frames[0].conversation} (bumping {@code meta.turns}), and drains
	 * the first {@code drainCount} entries of {@code session.pending} — all in
	 * ONE CAS, so an envelope is removed from pending only in the write that
	 * lands its user turn (crash between the two is impossible).
	 *
	 * <p>A stale {@code inCycle} left by a crashed cycle is overwritten — the
	 * run loop is the single live writer per agent, so a differing existing
	 * epoch can only be a crash remnant, and claiming it is exactly the
	 * resume case.</p>
	 *
	 * @return true if the session existed and was claimed; false if the
	 *         record or session is missing (nothing written)
	 */
	@SuppressWarnings("unchecked")
	public boolean beginSessionCycle(Blob sid, ACell epoch, AVector<ACell> turns, long drainCount) {
		java.util.concurrent.atomic.AtomicBoolean applied =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			applied.set(false);
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;

			session = session.assoc(K_IN_CYCLE, epoch);
			if (turns != null && turns.count() > 0) {
				session = appendTurnsToRoot(session, turns);
			}
			if (drainCount > 0) {
				session = drainPendingPrefix(session, drainCount);
			}
			applied.set(true);
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
		return applied.get();
	}

	/**
	 * Atomically updates {@code sessions[sid].frames}, fenced by the cycle
	 * epoch: the write applies only while {@code session.inCycle} equals
	 * {@code expectedEpoch}. A transition whose cycle has been superseded
	 * (cancelled and resumed, agent deleted and recreated, session claimed by
	 * a fresh cycle) gets {@code false} and must stop — its view of the
	 * frames is no longer authoritative.
	 *
	 * <p>{@code fn} must be pure: the CAS may re-apply it on contention.</p>
	 *
	 * @param expectedEpoch the claiming cycle's epoch (from
	 *        {@link #beginSessionCycle}); null skips the fence (test use only)
	 * @return true if the update applied; false if the record/session is
	 *         missing or the epoch fence rejected the write
	 */
	@SuppressWarnings("unchecked")
	public boolean updateSessionFrames(Blob sid, ACell expectedEpoch, UnaryOperator<AVector<ACell>> fn) {
		java.util.concurrent.atomic.AtomicBoolean applied =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			applied.set(false);
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			if (expectedEpoch != null && !expectedEpoch.equals(session.get(K_IN_CYCLE))) {
				return r;   // fence: this cycle no longer owns the session
			}
			AVector<ACell> frames = (session.get(K_FRAMES) instanceof AVector fv)
				? (AVector<ACell>) fv : Vectors.empty();
			AVector<ACell> updatedFrames = fn.apply(frames);
			if (updatedFrames == null || updatedFrames == frames) {
				applied.set(updatedFrames != null);
				return r;   // no change — skip the write (and the K_TS bump)
			}
			session = session.assoc(K_FRAMES, updatedFrames);
			applied.set(true);
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
		return applied.get();
	}

	/** Reads {@code sessions[sid].inCycle}, or null when absent. */
	public ACell getSessionCycleEpoch(Blob sid) {
		AMap<AString, ACell> session = getSession(sid);
		return (session != null) ? session.get(K_IN_CYCLE) : null;
	}

	/**
	 * Settles a session's cycle claim without a merge — used when an
	 * interrupted cycle is administratively stopped (operator suspend): the
	 * claim is released so the session does not register as crashed work,
	 * and any zombie writes from the stopped cycle are fenced out.
	 */
	@SuppressWarnings("unchecked")
	public void clearSessionCycle(Blob sid) {
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			AMap<AString, ACell> cleared = session.dissoc(K_IN_CYCLE);
			if (cleared == session) return r;
			return r.assoc(K_SESSIONS, sessions.assoc(sid, cleared));
		});
	}

	/**
	 * Appends turns to the session's {@code frames[0].conversation} and bumps
	 * {@code meta.turns}. Shared by {@link #beginSessionCycle} and
	 * {@link #mergeRunResult} so the two paths cannot drift. Defensive: mints
	 * a root frame if the session predates frames. Pure — safe under CAS retry.
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> appendTurnsToRoot(AMap<AString, ACell> session,
			AVector<ACell> turnsToAppend) {
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

		if (session.get(K_META) instanceof AMap) {
			AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(K_META);
			long current = (meta.get(K_TURNS) instanceof CVMLong cl) ? cl.longValue() : 0;
			meta = meta.assoc(K_TURNS, CVMLong.create(current + turnsToAppend.count()));
			session = session.assoc(K_META, meta);
		}
		return session;
	}

	/** Drops the first {@code drainCount} entries of {@code session.pending},
	 *  preserving the tail (entries that arrived after the snapshot). Shared
	 *  by {@link #beginSessionCycle} and {@link #mergeRunResult}. Pure. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> drainPendingPrefix(AMap<AString, ACell> session,
			long drainCount) {
		AVector<ACell> pending = (session.get(K_PENDING) instanceof AVector pv)
			? (AVector<ACell>) pv : Vectors.empty();
		long drop = Math.min(drainCount, pending.count());
		AVector<ACell> remaining = Vectors.empty();
		for (long i = drop; i < pending.count(); i++) {
			remaining = remaining.conj(pending.get(i));
		}
		return session.assoc(K_PENDING, remaining);
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
	 * Whether a session record represents outstanding work: it has pending
	 * messages, or it is mid-cycle ({@code inCycle} present — a claimed cycle
	 * whose merge has not run, i.e. live right now or interrupted by a crash;
	 * its input was already drained from pending, so the epoch is the only
	 * remaining work signal). Single shared rule for the wake gate, the
	 * session picker, and the merge's continue-vs-sleep decision.
	 */
	static boolean sessionHasWork(AMap<AString, ACell> session) {
		ACell pv = session.get(K_PENDING);
		if (pv instanceof AVector<?> v && v.count() > 0) return true;
		return session.get(K_IN_CYCLE) != null;
	}

	/**
	 * Returns true if any session has outstanding work (pending messages or
	 * an unfinished cycle — see {@link #sessionHasWork}).
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
	 * (pending messages or an unfinished cycle), or null if none.
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
	 * Single-writer helper for per-thread wake scheduling. Writes
	 * {@code wakeTime} on the named session or task record, then re-derives
	 * the agent's single scheduled wake via {@link #rescheduleWake}. See
	 * {@code venue/docs/GRID_SCHEDULER.md §8}.
	 *
	 * <p>Replace-semantics: the new {@code wakeTime} overwrites any prior
	 * value on that thread. Pass {@code wakeTime <= 0} to clear it. A missing
	 * session / task is a no-op (no lattice write, no scheduler change) —
	 * callers should ensure the record exists first.</p>
	 *
	 * <p>Per-thread {@code wakeTime} fields stay the authoritative record of
	 * "this thread wants to wake at T"; the agent holds at most one
	 * {@code agent:wake} event in the venue grid scheduler, armed at the
	 * <i>earliest</i> of them. Per-agent concurrency is zero under the
	 * virtual-thread-per-agent model (one cycle per agent serialises all
	 * in-cycle writers), so the read-then-write here needs no lock.</p>
	 *
	 * @param scheduler Venue grid scheduler to (re)arm the wake on
	 * @param ownerDID  Agent owner's DID — the wake fires under its authority
	 * @param kind      SESSION or TASK
	 * @param threadId  Session or task id (Blob)
	 * @param wakeTime  Absolute wall-clock millis, or {@code <= 0} to clear
	 */
	@SuppressWarnings("unchecked")
	public void setThreadWakeTime(Scheduler scheduler, AString ownerDID,
			ThreadKind kind, Blob threadId, long wakeTime) {
		Objects.requireNonNull(scheduler, "scheduler");
		Objects.requireNonNull(ownerDID, "ownerDID");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(threadId, "threadId");

		// Single pre-read so we can skip the write if the target record
		// is missing. We don't retry on a race — callers ensure existence.
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;

		AString key = (kind == ThreadKind.SESSION) ? K_SESSIONS : K_TASKS;
		Index<Blob, ACell> idx0 = (record.get(key) instanceof Index i)
			? (Index<Blob, ACell>) i : Index.none();
		if (!(idx0.get(threadId) instanceof AMap)) return;

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

		rescheduleWake(scheduler, ownerDID);
	}

	/**
	 * Re-derive this agent's single scheduled wake from the authoritative
	 * per-thread {@code wakeTime} fields. Cancels any previously-armed
	 * {@code agent:wake} event, then — if any thread still wants a wake —
	 * arms exactly one event at the earliest of them, recording its handle on
	 * the agent record. Clears the stored handle when no thread wants a wake.
	 *
	 * <p>Idempotent: safe to call on boot to rebuild the schedule from the
	 * lattice regardless of how the prior run ended (a cancel of a missing or
	 * already-fired handle is a no-op). See {@code venue/docs/GRID_SCHEDULER.md §8}.</p>
	 *
	 * <p>The wake is scheduled under the owner's own authority with no extra
	 * proofs or caps: the owner waking their own agent is the minimum,
	 * non-escalating authority — the same as a manual {@code agent:wake}. The
	 * work the run loop then performs still carries each task/session's own
	 * captured authority.</p>
	 *
	 * @param scheduler Venue grid scheduler to (re)arm the wake on
	 * @param ownerDID  Agent owner's DID — the wake fires under its authority
	 * @return {@code true} if a wake was armed, {@code false} if none was due
	 */
	public boolean rescheduleWake(Scheduler scheduler, AString ownerDID) {
		Objects.requireNonNull(scheduler, "scheduler");
		Objects.requireNonNull(ownerDID, "ownerDID");

		AMap<AString, ACell> record = getRecord();
		if (record == null) return false;

		long earliest = earliestWake(record);
		RequestContext octx = RequestContext.of(ownerDID);

		ACell oldHandle = record.get(K_WAKE_HANDLE);
		if (oldHandle instanceof Blob ob) {
			scheduler.cancel(ob, octx);
		}

		if (earliest > 0) {
			// Non-forcing (run only if work), non-blocking (fire-and-forget).
			Blob handle = scheduler.schedule(TRIGGER_OP,
				Maps.of(Fields.AGENT_ID, agentId,
					Fields.FORCE, CVMBool.FALSE,
					Fields.WAIT, CVMBool.FALSE),
				octx, earliest);
			update(r -> r.assoc(K_WAKE_HANDLE, handle));
			return true;
		}
		if (oldHandle != null) {
			update(r -> r.dissoc(K_WAKE_HANDLE));
		}
		return false;
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
	 * Suspends the agent with an error and drops the task queue, in one CAS.
	 * Used when a transition fails: callers of the queued tasks have already
	 * been notified via {@code failAllPendingForAgent}, so leaving stale tasks
	 * around would only cause the same failure to replay on the next resume.
	 * Operator can fix the underlying issue and {@code agent_resume} to a
	 * clean state; callers re-submit if they want to retry.
	 */
	public void suspendAndDrain(AString error) {
		update(r -> r
			.assoc(K_ERROR, error)
			.assoc(K_STATUS, SUSPENDED)
			.assoc(K_TASKS, Index.none()));
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
			presentedTasks, taskResults, timelineEntry, null, null, 0, null, null);
	}

	/**
	 * Back-compat 8-arg overload — no adapter-owned frames. Delegates to the
	 * full form with {@code newFrames = null}.
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
			presentedSessionPendingCount, null, null);
	}

	/**
	 * Atomic merge with frame-conversation append + session pending drain.
	 *
	 * <p>When {@code historySid != null}:
	 * <ul>
	 *   <li>If {@code turnsToAppend} is non-empty, turns are appended to
	 *       {@code sessions[historySid].frames[0].conversation} and
	 *       {@code meta.turns} is bumped. (The root frame is minted lazily
	 *       here if the session has no frames yet.)</li>
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
	 * wrote the timeline but not the frame conversation / pending drain.</p>
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
			AVector<ACell> newFrames,
			AMap<AString, ACell> sessionLoads) {
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

			// Atomic frames[0].conversation append + session.pending drain +
			// session-tier loads write + inCycle clear for the picked session.
			// All touch the same session record so we fold them into one assoc.
			boolean hasTurns = turnsToAppend != null && turnsToAppend.count() > 0;
			boolean hasDrain = presentedSessionPendingCount > 0;
			boolean hasNewFrames = newFrames != null;
			boolean hasLoads = sessionLoads != null;
			if (historySid != null) {
				Index<Blob, ACell> sessions = (updated.get(K_SESSIONS) instanceof Index idx)
					? (Index<Blob, ACell>) idx : Index.none();
				ACell sv = sessions.get(historySid);
				if (sv instanceof AMap) {
					AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
					AMap<AString, ACell> before = session;

					// Adapter-owned frame stack: replace wholesale before any
					// turn append. Turn append below still lands on frames[0]
					// so concurrent-intake user turns end up at root.
					if (hasNewFrames) {
						session = session.assoc(K_FRAMES, newFrames);
					}

					// Session-tier loads (#142): whole-replace with the cycle's
					// final working set (tombstones included). Single-writer per
					// tier — only the run loop writes this slot.
					if (hasLoads) {
						session = session.assoc(K_LOADS, sessionLoads);
					}

					if (hasTurns) {
						session = appendTurnsToRoot(session, turnsToAppend);
					}

					if (hasDrain) {
						session = drainPendingPrefix(session, presentedSessionPendingCount);
					}

					// The cycle is complete — release the session's inCycle
					// claim (set by beginSessionCycle) in the same CAS, so a
					// merged cycle is never mistaken for a crashed one.
					session = session.dissoc(K_IN_CYCLE);

					if (session != before) {
						updated = updated.assoc(K_SESSIONS, sessions.assoc(historySid, session));
					}
				}
			}

			// Check whether any session still has work after the drain —
			// pending messages or an unfinished cycle (same rule as the wake
			// gate, so the loop never sleeps on a session it would wake for).
			boolean hasSessionPendingInRecord = false;
			ACell sessionsCell = updated.get(K_SESSIONS);
			if (sessionsCell instanceof Index idx) {
				for (var entry : ((Index<Blob, ACell>) idx).entrySet()) {
					if (entry.getValue() instanceof AMap m && sessionHasWork(m)) {
						hasSessionPendingInRecord = true;
						break;
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
