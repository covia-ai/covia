package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
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
 * <p>Wraps a lattice cursor at {@code :user-data → <did> → "g" → <agentId>}.
 * The agent's value is a single atomic LWW map — every write replaces the
 * entire map with a new {@code ts}. There are no child lattices.</p>
 *
 * <p>See {@code AGENT_LOOP.md} for the agent record structure and transitions.</p>
 */
public class AgentState extends ALatticeComponent<ACell> {

	// Agent record field keys
	private static final AString K_TS       = Strings.intern("ts");
	private static final AString K_STATUS   = Strings.intern("status");
	private static final AString K_CONFIG   = Strings.intern("config");
	private static final AString K_STATE    = Strings.intern("state");
	private static final AString K_TASKS    = Strings.intern("tasks");
	private static final AString K_PENDING  = Strings.intern("pending");
	private static final AString K_INBOX    = Strings.intern("inbox");
	private static final AString K_TIMELINE = Strings.intern("timeline");
	private static final AString K_CAPS     = Strings.intern("caps");
	private static final AString K_ERROR    = Strings.intern("error");

	// Status constants
	public static final AString SLEEPING   = Strings.intern("SLEEPING");
	public static final AString RUNNING    = Strings.intern("RUNNING");
	public static final AString SUSPENDED  = Strings.intern("SUSPENDED");
	public static final AString TERMINATED = Strings.intern("TERMINATED");

	private final AString agentId;

	AgentState(ALatticeCursor<ACell> cursor, AString agentId) {
		super(cursor);
		this.agentId = agentId;
	}

	/**
	 * Gets the agent's ID string.
	 */
	public AString getAgentId() {
		return agentId;
	}

	/**
	 * Checks whether this agent has been initialised in the lattice.
	 */
	public boolean exists() {
		return cursor.get() != null;
	}

	// ========== Atomic record access ==========

	/**
	 * Gets the full agent record as a map.
	 *
	 * @return Agent record, or null if not initialised
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getRecord() {
		ACell v = cursor.get();
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/**
	 * Atomically replaces the entire agent record. Sets {@code ts} to now.
	 *
	 * @param record The new agent record
	 */
	public void putRecord(AMap<AString, ACell> record) {
		record = record.assoc(K_TS, CVMLong.create(Utils.getCurrentTimestamp()));
		cursor.set(record);
	}

	// ========== Initialisation ==========

	/**
	 * Initialises this agent with the given config and optional initial state.
	 *
	 * @param config Optional framework configuration map, may be null
	 * @param initialState Optional initial state for the transition function, may be null
	 */
	public void initialise(AMap<AString, ACell> config, ACell initialState) {
		if (exists()) return;
		AMap<AString, ACell> record = Maps.of(
			K_STATUS, SLEEPING,
			K_TASKS, Vectors.empty(),
			K_PENDING, Vectors.empty(),
			K_INBOX, Vectors.empty(),
			K_TIMELINE, Vectors.empty()
		);
		if (config != null) {
			record = record.assoc(K_CONFIG, config);
		}
		if (initialState != null) {
			record = record.assoc(K_STATE, initialState);
		}
		putRecord(record);
	}

	// ========== Convenience field accessors ==========

	/**
	 * Gets the agent's current status.
	 */
	public AString getStatus() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return null;
		return RT.ensureString(record.get(K_STATUS));
	}

	/**
	 * Gets the agent's configuration map.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getConfig() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return null;
		ACell v = record.get(K_CONFIG);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/**
	 * Gets the agent's user-defined state (opaque to framework).
	 */
	public ACell getState() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return null;
		return record.get(K_STATE);
	}

	/**
	 * Gets the agent's inbox as a vector.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getInbox() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return null;
		ACell v = record.get(K_INBOX);
		return (v instanceof AVector) ? (AVector<ACell>) v : null;
	}

	/**
	 * Gets the agent's timeline as a vector.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getTimeline() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return null;
		ACell v = record.get(K_TIMELINE);
		return (v instanceof AVector) ? (AVector<ACell>) v : null;
	}

	/**
	 * Gets the agent's last error.
	 */
	public AString getError() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return null;
		return RT.ensureString(record.get(K_ERROR));
	}

	/**
	 * Gets the agent's ts (last write timestamp).
	 */
	public long getTs() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return 0;
		ACell v = record.get(K_TS);
		return (v instanceof CVMLong l) ? l.longValue() : 0;
	}

	// ========== Atomic record mutations ==========

	/**
	 * Sets the agent's status. Atomic read-modify-write.
	 */
	public void setStatus(AString status) {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;
		putRecord(record.assoc(K_STATUS, status));
	}

	/**
	 * Sets the agent's error. Atomic read-modify-write.
	 */
	public void setError(AString error) {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;
		putRecord(record.assoc(K_ERROR, error));
	}

	/**
	 * Clears the agent's error. Atomic read-modify-write.
	 */
	public void clearError() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;
		putRecord(record.dissoc(K_ERROR));
	}

	/**
	 * Sets the agent's configuration. Atomic read-modify-write.
	 */
	public void setConfig(AMap<AString, ACell> config) {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;
		putRecord(record.assoc(K_CONFIG, config));
	}

	/**
	 * Appends a message to the agent's inbox. Atomic read-modify-write.
	 */
	public void deliverMessage(ACell message) {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;
		AVector<ACell> inbox = getInbox();
		if (inbox == null) inbox = Vectors.empty();
		putRecord(record.assoc(K_INBOX, inbox.conj(message)));
	}

	/**
	 * Gets the agent's inbound task Job IDs.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getTasks() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return Vectors.empty();
		ACell v = record.get(K_TASKS);
		return (v instanceof AVector) ? (AVector<ACell>) v : Vectors.empty();
	}

	/**
	 * Gets the agent's outbound pending Job IDs.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getPending() {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return Vectors.empty();
		ACell v = record.get(K_PENDING);
		return (v instanceof AVector) ? (AVector<ACell>) v : Vectors.empty();
	}

	/**
	 * Adds a task Job ID to the agent's tasks. Atomic read-modify-write.
	 *
	 * <p><b>Invariant:</b> callers must ensure that {@code jobId} corresponds to
	 * a real Job in STARTED state awaiting completion. Only
	 * {@code AgentAdapter.handleRequest} should call this in production.</p>
	 */
	public void addTask(ACell jobId) {
		AMap<AString, ACell> record = getRecord();
		if (record == null) return;
		AVector<ACell> tasks = getTasks();
		putRecord(record.assoc(K_TASKS, tasks.conj(jobId)));
	}

	// ========== Key constants for external use ==========

	public static final AString KEY_TS       = Strings.intern("ts");
	public static final AString KEY_STATUS   = Strings.intern("status");
	public static final AString KEY_CONFIG   = Strings.intern("config");
	public static final AString KEY_STATE    = Strings.intern("state");
	public static final AString KEY_TASKS    = Strings.intern("tasks");
	public static final AString KEY_PENDING  = Strings.intern("pending");
	public static final AString KEY_INBOX    = Strings.intern("inbox");
	public static final AString KEY_TIMELINE = Strings.intern("timeline");
	public static final AString KEY_CAPS     = Strings.intern("caps");
	public static final AString KEY_ERROR    = Strings.intern("error");
}
