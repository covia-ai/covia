package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.lattice.ACoviaComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.WorkspaceRoot;
import convex.lattice.cursor.StampedCursor;

/**
 * Per-user domain state, with atomic mutations and compound transactions.
 *
 * <p>Wraps a lattice cursor at {@code :user-data → <did>} within the
 * venue state. Provides typed accessors for per-user data. Created by
 * {@link Users#get(AString)} (returns null if the user doesn't exist) or
 * {@link Users#ensure(AString)} (creates if needed).</p>
 *
 * <p>The per-user lattice uses short AString-compatible keys from
 * {@link Namespace} for JSON compliance:</p>
 * <ul>
 *   <li>{@code "j"} — user's jobs ({@link Jobs})</li>
 *   <li>{@code "g"} — user's agents</li>
 *   <li>{@code "s"} — user's encrypted credentials</li>
 *   <li>{@code "w"} — user's workspace data ({@link covia.lattice.Covia#WORKSPACE_RECORD})</li>
 *   <li>{@code "o"} — user's operations ({@link covia.lattice.Covia#WORKSPACE_RECORD})</li>
 *   <li>{@code "h"} — HITL requests ({@link Inbox}, in a {@link covia.lattice.Covia#WORKSPACE_RECORD})</li>
 *   <li>{@code "a"} — content-addressed assets</li>
 * </ul>
 * Merge is whole-value at the venue {@code :value}; these namespaces are
 * navigation structure only.
 */
public class User extends ACoviaComponent<ACell> implements TransactionalComponent<ACell, User> {
	@Override
	public User withCursor(AForkableCursor<ACell> draft) {
		return new User(context, draft, did);
	}

	private final VenueContext context;
	private final AForkableCursor<ACell> edits;

	@Override
	public AForkableCursor<ACell> cursor() { return edits; }

	public VenueContext getContext() { return context; }

	private final AString did;

	/** Framework-internal navigation for trusted venue-owned workspace state. */
	ACell readInternalPath(ACell[] keys) {
		return workspaceRoot().read(keys);
	}

	/** Framework-internal atomic write; authority is possession of this user cursor. */
	void writeInternalPath(ACell[] keys, ACell value) {
		workspaceRoot().write(keys, value);
	}

	/** Framework-internal atomic delete; authority is possession of this user cursor. */
	boolean deleteInternalPath(ACell[] keys) {
		return workspaceRoot().delete(keys);
	}

	User(VenueContext context, AForkableCursor<ACell> cursor, AString did) {
		super(cursor);
		this.context = context;
		this.did = did;
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampUserMeta);
	}

	/** Caller-visible workspace namespaces with this user's write policy. */
	public WorkspaceRoot workspaceRoot() {
		return new WorkspaceRoot(cursor(), getContext(), true);
	}

	/** Internal namespace-resolution bridge; domain callers use agent and job methods. */
	public AForkableCursor<ACell> recordCursor(ACell namespace, ACell id) {
		if (Namespace.G.equals(namespace)) return new AgentState(context, agentStorage(id), (AString) id).cursor();
		if (Namespace.J.equals(namespace)) return jobs().get((Blob) id).cursor();
		throw new IllegalArgumentException("Not an agent or job namespace: " + namespace);
	}

	private AForkableCursor<ACell> agentStorage(ACell id) {
		return convex.lattice.LatticeComponent.resolve(cursor(), Covia.USER_RECORD, Namespace.G, id);
	}

	/** Gets the user's DID string. */
	public AString getDID() {
		return did;
	}

	/**
	 * Gets the user's asset store (per-user content-addressed assets).
	 *
	 * @return AssetStore wrapping the user's "a" cursor
	 */
	@SuppressWarnings("unchecked")
	public AssetStore assets() {
		return new AssetStore(cursor().path(Namespace.A));
	}

	/**
	 * Gets the user's secret store (per-user encrypted credentials).
	 *
	 * @return SecretStore wrapping the user's "s" cursor
	 */
	public SecretStore secrets() {
		return new SecretStore(context, cursor().path(Namespace.S));
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
		AForkableCursor<ACell> c = agentStorage(agentId);
		if (c.get() == null) return null;
		return new AgentState(context, c, agentId);
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
		AForkableCursor<ACell> c = agentStorage(agentId);
		AgentState state = new AgentState(context, c, agentId);
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
		return createAgent(agentId, config, initialState, null);
	}

	public AgentState createAgent(AString agentId, AMap<AString, ACell> config,
			ACell initialState, AMap<AString, ACell> definition) {
		AForkableCursor<ACell> c = agentStorage(agentId);
		AgentState state = new AgentState(context, c, agentId);
		return state.initialiseIfAbsent(config, initialState, definition) ? state : null;
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
		AForkableCursor<ACell> c = agentStorage(agentId);
		AgentState fork = new AgentState(context, c, agentId);
		return fork.initialiseFromForkIfAbsent(config, state, timeline) ? fork : null;
	}

	/**
	 * Removes an agent record entirely from the lattice.
	 *
	 * @param agentId Agent identifier to remove
	 */
	@SuppressWarnings("unchecked")
	public void removeAgent(AString agentId) {
		// Remove the key itself, as covia:delete does: writing null through the
		// agent's own path would leave g/<id> listed, holding nothing.
		cursor().path(Namespace.G).updateAndGet(agents -> (agents instanceof AMap<?, ?> map)
			? ((AMap<AString, ACell>) map).dissoc(agentId)
			: agents);
	}

	/**
	 * Gets all agents as a map for iteration.
	 *
	 * @return Map of agent ID to agent state, or null if none
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getAgents() {
		ACell value = cursor().path(Namespace.G).get();
		return (value instanceof AMap) ? (AMap<AString, ACell>) value : null;
	}

	/**
	 * Gets the raw per-user state value.
	 *
	 * @return User state, or null if uninitialised
	 */
	public ACell get() {
		return cursor().get();
	}

	/** This user's HITL inbox ({@code h/}). */
	public Inbox inbox() {
		return new Inbox(workspaceRoot().child(Namespace.H));
	}

	/** This user's jobs ({@code j/}), bound to this user or transaction draft. */
	public Jobs jobs() {
		return new Jobs(context, convex.lattice.LatticeComponent.resolve(cursor(), Covia.USER_RECORD, Namespace.J));
	}

	@Override
	public ACoviaComponent<?> resolve(ACell[] keys, int from) {
		if (from < keys.length && Namespace.H.equals(keys[from])) return inbox().resolve(keys, from + 1);
		if (from < keys.length && Namespace.J.equals(keys[from])) return jobs().resolve(keys, from + 1);
		return super.resolve(keys, from);
	}

}
