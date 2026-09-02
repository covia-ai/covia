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
	/** Legacy pre-frame loads slot. Read only by the cycle-start migration. */
	private static final AString K_LOADS = Strings.intern("loads");
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

	// Session record field keys (scoped within a single session map)
	private static final AString K_C        = Strings.intern("c");
	private static final AString K_FRAMES   = Strings.intern("frames");
	/** Session attempt epoch: present while a transition owns this session
	 *  (set by {@link #beginSessionCycle}, cleared by {@link #mergeRunResult}).
	 *  Serves as a write fence for {@link #updateSessionFrames} (a
	 *  cancelled cycle's zombie thread cannot write once a new epoch claims the
	 *  session). A stale epoch after restart is abandoned executor state; a
	 *  later attempt may supersede it but boot never wakes solely for it. */
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

	AgentState(ALatticeComponent<?> parent, ALatticeCursor<ACell> cursor,
			AString agentId) {
		super(parent, cursor);
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
	 * Ensures a session exists, seeding the root frame's loads when it is
	 * minted here. {@code initialLoads} applies only on creation — an existing
	 * session's loads are never touched by this method.
	 */
	public AMap<AString, ACell> ensureSession(Blob sid, AString caller,
			AMap<AString, ACell> initialLoads) {
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			if (sessions.get(sid) != null) return r;
			long created = Utils.getCurrentTimestamp();
			AMap<AString, ACell> meta = Maps.of(
				K_CREATED, CVMLong.create(created),
				Fields.UPDATED, CVMLong.create(created),
				K_TURNS,   CVMLong.create(0),
				K_PARTIES, (caller != null) ? Vectors.of(caller) : Vectors.empty());
			AMap<AString, ACell> rootFrame = Maps.of(
				K_DESCRIPTION,  Strings.EMPTY,
				K_CONVERSATION, Vectors.empty());
			if (initialLoads != null && initialLoads.count() > 0) {
				rootFrame = rootFrame.assoc(K_LOADS, initialLoads);
			}
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
	 * Atomically claims a session for a transition cycle: sets the
	 * {@code inCycle} epoch, appends the cycle's input turns to
	 * {@code frames[0].conversation} (bumping {@code meta.turns}), and drains
	 * the first {@code drainCount} entries of {@code session.pending} — all in
	 * ONE CAS, so an envelope is removed from pending only in the write that
	 * lands its user turn (crash between the two is impossible).
	 *
	 * <p>A stale {@code inCycle} left by an abandoned attempt is overwritten.
	 * The epoch is a write fence, not a checkpoint: claiming it starts a fresh
	 * attempt and does not restore the former executor.</p>
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
			AMap<AString, ACell> session = migrateLegacySessionLoads(
				(AMap<AString, ACell>) sv);

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
	 * Moves the former {@code session.loads} tier into the root frame. Existing
	 * root-frame entries retain their old inner-scope precedence, including nil
	 * masks. Flattening the effective value preserves behaviour while giving
	 * both LLM runtimes one durable frame shape. Pure and safe under CAS retry.
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> migrateLegacySessionLoads(
			AMap<AString, ACell> session) {
		if (!session.containsKey(K_LOADS)) return session;
		AMap<AString, ACell> legacy = (session.get(K_LOADS) instanceof AMap lm)
			? (AMap<AString, ACell>) lm : Maps.empty();
		AVector<ACell> frames = (session.get(K_FRAMES) instanceof AVector fv)
			? (AVector<ACell>) fv : Vectors.empty();
		AMap<AString, ACell> root;
		if (frames.isEmpty() || !(frames.get(0) instanceof AMap)) {
			root = Maps.of(
				K_DESCRIPTION, Strings.EMPTY,
				K_CONVERSATION, Vectors.empty());
			frames = Vectors.of(root);
		} else {
			root = (AMap<AString, ACell>) frames.get(0);
		}
		AMap<AString, ACell> frameLoads = (root.get(K_LOADS) instanceof AMap fm)
			? (AMap<AString, ACell>) fm : Maps.empty();
		AMap<AString, ACell> merged = mergeLoadTiers(legacy, frameLoads);
		root = merged.isEmpty() ? root.dissoc(K_LOADS) : root.assoc(K_LOADS, merged);
		return session.assoc(K_FRAMES, frames.assoc(0, root)).dissoc(K_LOADS);
	}

	/** Applies load tiers outer-to-inner; nil entries mask an outer value. */
	@SafeVarargs
	private static AMap<AString, ACell> mergeLoadTiers(AMap<AString, ACell>... tiers) {
		AMap<AString, ACell> result = Maps.empty();
		for (AMap<AString, ACell> tier : tiers) {
			for (var entry : tier.entrySet()) {
				result = (entry.getValue() == null)
					? result.dissoc(entry.getKey())
					: result.assoc(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	/**
	 * Atomically presents input after a cycle has claimed and repaired its
	 * frames. The epoch fence prevents an interrupted runner from draining
	 * pending messages after a newer cycle has superseded it.
	 */
	@SuppressWarnings("unchecked")
	public boolean presentSessionCycleInput(Blob sid, ACell expectedEpoch,
			AVector<ACell> turns, long drainCount) {
		java.util.concurrent.atomic.AtomicBoolean applied =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			applied.set(false);
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			if (!Objects.equals(expectedEpoch, session.get(K_IN_CYCLE))) return r;

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
	 * (cancelled and superseded, agent deleted and recreated, session claimed by
	 * a fresh cycle) gets {@code false} and must stop — its view of the
	 * frames is no longer authoritative.
	 *
	 * <p>{@code fn} must be pure: the CAS may re-apply it on contention.</p>
	 *
	 * @param expectedEpoch the claiming cycle's epoch (from
	 *        {@link #beginSessionCycle}); null skips the fence for coordinated
	 *        initialisation and tests. Owner mutations should use
	 *        {@link #updateQuiescentSessionFrames}.
	 * @return true if the update applied; false if the record/session is
	 *         missing or the epoch fence rejected the write
	 */
	@SuppressWarnings("unchecked")
	public boolean updateSessionFrames(Blob sid, ACell expectedEpoch, UnaryOperator<AVector<ACell>> fn) {
		return updateSessionFrames(sid, expectedEpoch, false, fn);
	}

	/** Applies an owner-side frame mutation only while the session is idle. */
	public boolean updateQuiescentSessionFrames(Blob sid, UnaryOperator<AVector<ACell>> fn) {
		return updateSessionFrames(sid, null, true, fn);
	}

	@SuppressWarnings("unchecked")
	private boolean updateSessionFrames(Blob sid, ACell expectedEpoch,
			boolean requireQuiescent, UnaryOperator<AVector<ACell>> fn) {
		java.util.concurrent.atomic.AtomicBoolean applied =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			applied.set(false);
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			if (requireQuiescent && session.get(K_IN_CYCLE) != null) return r;
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
			AVector<ACell> beforeRoot = rootConversation(frames);
			AVector<ACell> afterRoot = rootConversation(updatedFrames);
			if (afterRoot.count() > beforeRoot.count()) {
				session = bumpTurnMeta(session,
					(AVector<ACell>) afterRoot.slice(beforeRoot.count(), afterRoot.count()));
			}
			applied.set(true);
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
		return applied.get();
	}

	/** Appends one root-frame conversation turn under the active cycle fence,
	 * updating session turn/timestamp metadata atomically with the frame. */
	@SuppressWarnings("unchecked")
	public boolean appendSessionRootTurn(Blob sid, ACell expectedEpoch, ACell turn) {
		java.util.concurrent.atomic.AtomicBoolean applied =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			applied.set(false);
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			if (expectedEpoch != null && !expectedEpoch.equals(session.get(K_IN_CYCLE))) return r;
			session = appendTurnsToRoot(session, Vectors.of(turn));
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
		return bumpTurnMeta(session, turnsToAppend);
	}

	/** Updates root-turn count and timestamp metadata for an appended suffix. */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> bumpTurnMeta(AMap<AString, ACell> session,
			AVector<ACell> appended) {
		if (session.get(K_META) instanceof AMap) {
			AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(K_META);
			long current = (meta.get(K_TURNS) instanceof CVMLong cl) ? cl.longValue() : 0;
			meta = meta.assoc(K_TURNS, CVMLong.create(current + appended.count()));
			long updated = (meta.get(Fields.UPDATED) instanceof CVMLong cl) ? cl.longValue() : 0;
			for (long i = 0; i < appended.count(); i++) {
				ACell ts = RT.getIn(appended.get(i), K_TURN_TS);
				if (ts instanceof CVMLong cl) updated = Math.max(updated, cl.longValue());
			}
			if (updated > 0) meta = meta.assoc(Fields.UPDATED, CVMLong.create(updated));
			session = session.assoc(K_META, meta);
		}
		return session;
	}

	/** Root conversation or an empty vector for an absent/malformed root. */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> rootConversation(AVector<ACell> frames) {
		if (frames == null || frames.isEmpty() || !(frames.get(0) instanceof AMap root)) {
			return Vectors.empty();
		}
		ACell conversation = ((AMap<AString, ACell>) root).get(K_CONVERSATION);
		return (conversation instanceof AVector cv)
			? (AVector<ACell>) cv : Vectors.empty();
	}

	/**
	 * Adds a cycle's measured token counts — {@code input}, {@code output},
	 * {@code total} and the cache counts when reported — into the session's
	 * {@code meta.tokens} running totals (#217). Pure —
	 * safe under CAS retry. A session without a meta map (shouldn't happen —
	 * sessions mint meta at creation) is left untouched rather than grown a
	 * partial one.
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> bumpMetaTokens(AMap<AString, ACell> session,
			AMap<AString, ACell> cycleTokens) {
		if (!(session.get(K_META) instanceof AMap)) return session;
		AMap<AString, ACell> meta = (AMap<AString, ACell>) session.get(K_META);
		AMap<AString, ACell> totals = (meta.get(Fields.TOKENS) instanceof AMap tm)
			? (AMap<AString, ACell>) tm : Maps.empty();
		for (AString k : new AString[] {Fields.INPUT, Fields.OUTPUT, Fields.TOTAL,
				Fields.CACHE_READ, Fields.CACHE_WRITE}) {
			long add = (cycleTokens.get(k) instanceof CVMLong cl) ? cl.longValue() : 0;
			if (add == 0) continue;
			long current = (totals.get(k) instanceof CVMLong cl) ? cl.longValue() : 0;
			totals = totals.assoc(k, CVMLong.create(current + add));
		}
		if (totals.count() == 0) return session;
		return session.assoc(K_META, meta.assoc(Fields.TOKENS, totals));
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
	 * Sets (or clears, when {@code title} is null) the free-form human-facing
	 * title in a session's {@code meta} — the field documented but never
	 * implemented in the "suggested" shape at AGENT_SESSIONS.md §4.3. A
	 * no-op if the session doesn't exist (caller should check first if it
	 * needs to distinguish "not found" from "renamed").
	 *
	 * @return true if the session existed and was updated, false otherwise
	 */
	@SuppressWarnings("unchecked")
	public boolean setSessionTitle(Blob sid, AString title) {
		java.util.concurrent.atomic.AtomicBoolean found =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		update(r -> {
			// updateAndGet may retry the callback after contention. Reset the
			// side-channel so the return value describes the winning attempt.
			found.set(false);
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sessionCell = sessions.get(sid);
			if (!(sessionCell instanceof AMap)) return r;
			found.set(true);
			AMap<AString, ACell> session = (AMap<AString, ACell>) sessionCell;
			AMap<AString, ACell> meta = (session.get(K_META) instanceof AMap m)
				? (AMap<AString, ACell>) m : Maps.empty();
			meta = (title != null) ? meta.assoc(Fields.TITLE, title) : meta.dissoc(Fields.TITLE);
			session = session.assoc(K_META, meta);
			return r.assoc(K_SESSIONS, sessions.assoc(sid, session));
		});
		return found.get();
	}

	/** Removes a queued chat envelope by its external Job id. */
	@SuppressWarnings("unchecked")
	public void removeSessionPendingJob(Blob sid, Blob jobId) {
		AString jobIdHex = Strings.create(jobId.toHexString());
		update(r -> {
			Index<Blob, ACell> sessions = (r.get(K_SESSIONS) instanceof Index idx)
				? (Index<Blob, ACell>) idx : Index.none();
			ACell sv = sessions.get(sid);
			if (!(sv instanceof AMap<?, ?>)) return r;
			AMap<AString, ACell> session = (AMap<AString, ACell>) sv;
			AVector<ACell> pending = (session.get(K_PENDING) instanceof AVector<?> v)
				? (AVector<ACell>) v : Vectors.empty();
			AVector<ACell> kept = Vectors.empty();
			for (long i = 0; i < pending.count(); i++) {
				ACell envelope = pending.get(i);
				if (!jobIdHex.equals(RT.getIn(envelope, Fields.JOB_ID))) {
					kept = kept.conj(envelope);
				}
			}
			if (kept.count() == pending.count()) return r;
			return r.assoc(K_SESSIONS,
				sessions.assoc(sid, session.assoc(K_PENDING, kept)));
		});
	}

	/**
	 * Whether a session record has durable queued work. {@code inCycle} is an
	 * executor fence, never a wake signal or a resume checkpoint.
	 */
	static boolean sessionHasWork(AMap<AString, ACell> session) {
		ACell pv = session.get(K_PENDING);
		return pv instanceof AVector<?> v && v.count() > 0;
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
			// Non-forcing (run only if work), non-blocking (fire-and-forget), and
			// explicitly untracked: a wake is machinery, not user work — the run
			// loop it starts records its own tasks. Only the operator's
			// forceTrackJobs makes wakes durable (GRID_SCHEDULER.md §7).
			Blob handle = scheduler.schedule(TRIGGER_OP,
				Maps.of(Fields.AGENT_ID, agentId,
					Fields.FORCE, CVMBool.FALSE,
					Fields.WAIT, CVMBool.FALSE),
				octx, earliest, null, Boolean.FALSE);
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
	 * Atomically merges run loop results into the agent record.
	 *
	 * <p>Reconciles concurrent modifications and preserves tasks added during
	 * the transition. Executor status is deliberately absent from this merge:
	 * the launcher writes {@code RUNNING} once and clears it on final exit.</p>
	 */

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
	 * to {@code frames[0].conversation} on top, which is how concurrent
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
		return update(r -> {
			// Remove completed tasks, detect new ones
			Index<Blob, ACell> currentTasks = extractTasks(r);
			Index<Blob, ACell> remainingTasks = removeCompletedTasks(currentTasks, taskResults);

			AVector<ACell> timeline = extractTimeline(r);

			AMap<AString, ACell> updated = r
				.assoc(K_STATE, applyStateChange(r.get(K_STATE), snapshotState, newState))
				.assoc(K_TASKS, remainingTasks)
				.assoc(K_TIMELINE, timeline.conj(timelineEntry))
				.dissoc(K_ERROR);

			// Atomic frames[0].conversation append + session.pending drain +
			// inCycle clear for the picked session.
			// All touch the same session record so we fold them into one assoc.
			boolean hasTurns = turnsToAppend != null && turnsToAppend.count() > 0;
			boolean hasDrain = presentedSessionPendingCount > 0;
			boolean hasNewFrames = newFrames != null;
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

					if (hasTurns) {
						session = appendTurnsToRoot(session, turnsToAppend);
					}

					if (hasDrain) {
						session = drainPendingPrefix(session, presentedSessionPendingCount);
					}

					// Session running token totals (#217): the cycle's measured
					// usage — the sum over its recorded inferences — added to
					// meta.tokens in the same CAS as the entry.
					if (cycleTokens != null) {
						session = bumpMetaTokens(session, cycleTokens);
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

}
