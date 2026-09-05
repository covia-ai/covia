package covia.venue;

import java.util.Objects;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import covia.adapter.CoviaAdapter;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * Cursor wrapper for a single user's state within a venue.
 *
 * <p>Wraps a lattice cursor at {@code :user-data → <did>} within the
 * venue state. Provides typed accessors for per-user data. Created by
 * {@link Users#get(AString)} (returns null if the user doesn't exist) or
 * {@link Users#ensure(AString)} (creates if needed).</p>
 *
 * <p>Follows the same lattice app wrapper pattern as {@link AssetStore}.
 * The per-user lattice uses short AString-compatible keys from
 * {@link Namespace} for JSON compliance:</p>
 * <ul>
 *   <li>{@code "j"} — user's job references</li>
 *   <li>{@code "g"} — user's agents</li>
 *   <li>{@code "s"} — user's encrypted credentials</li>
 *   <li>{@code "w"} — user's workspace data ({@link covia.lattice.WrapperLattice})</li>
 *   <li>{@code "o"} — user's operations ({@link covia.lattice.WrapperLattice})</li>
 *   <li>{@code "h"} — HITL requests ({@link covia.lattice.WrapperLattice})</li>
 *   <li>{@code "a"} — content-addressed assets</li>
 * </ul>
 * Merge is whole-value at the venue {@code :value}; these namespaces are
 * navigation structure only.
 */
public class User extends ALatticeComponent<ACell> {

	private final AString did;

	/** Framework-internal navigation for trusted venue-owned workspace state. */
	ACell readInternalPath(ACell[] keys) {
		return CoviaAdapter.readPath(cursor, keys);
	}

	/** Framework-internal atomic write; authority is possession of this user cursor. */
	void writeInternalPath(ACell[] keys, ACell value) {
		CoviaAdapter.writePathToCursor(cursor, keys, value);
	}

	/** Framework-internal atomic delete; authority is possession of this user cursor. */
	boolean deleteInternalPath(ACell[] keys) {
		return CoviaAdapter.deletePathFromCursor(cursor, keys);
	}

	User(ALatticeComponent<?> parent, ALatticeCursor<ACell> cursor, AString did) {
		super(parent, cursor);
		this.did = did;
	}

	/**
	 * Gets the user's DID string.
	 *
	 * @return User DID
	 */
	public AString getDID() {
		return did;
	}

	/**
	 * Gets all of this user's jobs as an Index keyed by Job ID. Never null.
	 *
	 * @return Index of job records, empty if the user has no jobs
	 */
	@SuppressWarnings("unchecked")
	public Index<Blob, ACell> getJobs() {
		ACell value = cursor.path(Namespace.J).get();
		return (value instanceof Index) ? (Index<Blob, ACell>) value : Index.none();
	}

	/**
	 * Gets a single job record from this user's job index.
	 *
	 * @param jobID Job ID
	 * @return Job record map, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getJob(Blob jobID) {
		ACell record = getJobs().get(jobID);
		return (record instanceof AMap) ? (AMap<AString, ACell>) record : null;
	}

	/**
	 * Removes a job record from this user's job index, if present. The
	 * removal is durable: the venue merge is whole-value LWW, so the
	 * post-delete snapshot wins and the record is not resurrected on sync.
	 *
	 * @param jobID Job ID
	 * @return true if the row existed and was removed
	 */
	@SuppressWarnings("unchecked")
	public boolean removeJob(Blob jobID) {
		ACell old = cursor.path(Namespace.J).getAndUpdate(jobs -> {
			if (!(jobs instanceof Index)) return jobs;
			return ((Index<Blob, ACell>) jobs).dissoc(jobID);
		});
		return old instanceof Index<?, ?> index && index.containsKey(jobID);
	}

	/**
	 * Persists a job record to this user's job index. Preserves the {@code temp}
	 * field from any existing record at the same ID (goal-scoped scratch).
	 *
	 * @param jobID Job ID (16-byte Blob: timestamp + counter + random)
	 * @param record Job status record map
	 */
	@SuppressWarnings("unchecked")
	public void persistJob(Blob jobID, AMap<AString, ACell> record) {
		final AMap<AString, ACell> rec = record;
		cursor.path(Namespace.J).updateAndGet(jobs -> {
			Index<Blob, ACell> idx = (jobs instanceof Index)
				? (Index<Blob, ACell>) jobs
				: Index.none();
			AMap<AString, ACell> merged = rec;
			ACell existing = idx.get(jobID);
			if (existing instanceof AMap<?,?> existingMap) {
				AMap<AString, ACell> existingRecord = (AMap<AString, ACell>) existingMap;
				if (existingRecord.containsKey(K_TEMP)) {
					merged = merged.assoc(K_TEMP, existingRecord.get(K_TEMP));
				}
			}
			return idx.assoc(jobID, merged);
		});
	}

	/**
	 * Atomically updates an existing job without ever recreating a deleted row.
	 *
	 * @return true when the job existed at the mutation point
	 */
	@SuppressWarnings("unchecked")
	public boolean updateJobIfPresent(Blob jobID,
			UnaryOperator<AMap<AString, ACell>> updater) {
		ACell old = cursor.path(Namespace.J).getAndUpdate(jobs -> {
			if (!(jobs instanceof Index<?, ?>)) return jobs;
			Index<Blob, ACell> idx = (Index<Blob, ACell>) jobs;
			ACell value = idx.get(jobID);
			if (!(value instanceof AMap<?, ?> map)) return jobs;
			AMap<AString, ACell> record = (AMap<AString, ACell>) map;
			AMap<AString, ACell> updated = updater.apply(record);
			return Objects.equals(record, updated) ? jobs : idx.assoc(jobID, updated);
		});
		return old instanceof Index<?, ?> index && index.get(jobID) instanceof AMap<?, ?>;
	}

	private static final AString K_TEMP = Strings.intern("temp");

	/**
	 * Gets the user's asset store (per-user content-addressed assets).
	 *
	 * @return AssetStore wrapping the user's "a" cursor
	 */
	@SuppressWarnings("unchecked")
	public AssetStore assets() {
		return new AssetStore(this, cursor.path(Namespace.A));
	}

	/**
	 * Gets the user's secret store (per-user encrypted credentials).
	 *
	 * @return SecretStore wrapping the user's "s" cursor
	 */
	public SecretStore secrets() {
		return new SecretStore(this, cursor.path(Namespace.S));
	}

	/**
	 * Gets a specific agent's state, or null if the agent doesn't exist.
	 *
	 * @param agentId Agent identifier
	 * @return AgentState wrapper, or null if not initialised
	 */
	public AgentState agent(String agentId) {
		return agent(Strings.create(agentId));
	}

	public AgentState agent(AString agentId) {
		ALatticeCursor<ACell> c = cursor.path(Namespace.G, agentId);
		if (c.get() == null) return null;
		return new AgentState(this, c, agentId);
	}

	/**
	 * Gets a specific agent's state, creating and initialising it if needed.
	 *
	 * @param agentId Agent identifier
	 * @param config Optional framework configuration map, may be null
	 * @param initialState Optional initial state for the transition function, may be null
	 * @return AgentState wrapper (never null)
	 */
	public AgentState ensureAgent(String agentId, AMap<AString, ACell> config, ACell initialState) {
		return ensureAgent(Strings.create(agentId), config, initialState);
	}

	public AgentState ensureAgent(AString agentId, AMap<AString, ACell> config, ACell initialState) {
		ALatticeCursor<ACell> c = cursor.path(Namespace.G, agentId);
		AgentState state = new AgentState(this, c, agentId);
		state.initialiseIfAbsent(config, initialState);
		return state;
	}

	/**
	 * Exclusively creates an agent record in one atomic cursor update.
	 *
	 * @return the new agent, or null if the id already existed
	 */
	public AgentState createAgent(AString agentId, AMap<AString, ACell> config,
			ACell initialState) {
		ALatticeCursor<ACell> c = cursor.path(Namespace.G, agentId);
		AgentState state = new AgentState(this, c, agentId);
		return state.initialiseIfAbsent(config, initialState) ? state : null;
	}

	/**
	 * Creates a new agent as a fork of another. Copies config and state;
	 * timeline is copied only if {@code timeline} is non-null. Tasks, pending,
	 * and inbox are fresh; status is SLEEPING.
	 *
	 * @param agentId Agent identifier for the fork (must not already exist)
	 * @param config Config map to use (typically merged source+override)
	 * @param state Initial state (typically source state)
	 * @param timeline Optional timeline to copy, or null for empty
	 * @return the new AgentState wrapper, or null if the id already existed
	 */
	public AgentState forkAgent(AString agentId, AMap<AString, ACell> config,
			ACell state, convex.core.data.AVector<ACell> timeline) {
		ALatticeCursor<ACell> c = cursor.path(Namespace.G, agentId);
		AgentState fork = new AgentState(this, c, agentId);
		return fork.initialiseFromForkIfAbsent(config, state, timeline) ? fork : null;
	}

	/**
	 * Removes an agent record entirely from the lattice.
	 *
	 * @param agentId Agent identifier to remove
	 */
	public void removeAgent(AString agentId) {
		cursor.path(Namespace.G, agentId).set(null);
	}

	/**
	 * Gets all agents as a map for iteration.
	 *
	 * @return Map of agent ID to agent state, or null if none
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getAgents() {
		ACell value = cursor.path(Namespace.G).get();
		return (value instanceof AMap) ? (AMap<AString, ACell>) value : null;
	}

	/**
	 * Gets the raw per-user state value.
	 *
	 * @return User state, or null if uninitialised
	 */
	public ACell get() {
		return cursor.get();
	}

	// ========== HITL inbox (h/ namespace, COG-16) ==========

	/**
	 * Gets this user's HITL request records ({@code h/} inbox) keyed by
	 * request id. Never null.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getHitlRequests() {
		ACell v = Covia.child(cursor, Namespace.H).get();
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : convex.core.data.Maps.empty();
	}

	/**
	 * Gets a single HITL request record from this user's inbox.
	 *
	 * @param id Request id (job id hex)
	 * @return Record map, or null if absent
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getHitlRequest(AString id) {
		ACell v = getHitlRequests().get(id);
		return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
	}

	/**
	 * Writes a HITL request record into this user's inbox. Venue-mediated —
	 * records are created and resolved only by the framework (the {@code h/}
	 * namespace is not writable via {@code covia:write}).
	 *
	 * @param id Request id (job id hex)
	 * @param record Request record map
	 */
	@SuppressWarnings("unchecked")
	public void putHitlRequest(AString id, AMap<AString, ACell> record) {
		Covia.child(cursor, Namespace.H).updateAndGet(data -> {
			AMap<AString, ACell> m = (data instanceof AMap)
				? (AMap<AString, ACell>) data
				: convex.core.data.Maps.empty();
			return m.assoc(id, record);
		});
	}

}
