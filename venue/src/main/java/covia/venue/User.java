package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.lattice.ACoviaComponent;
import covia.lattice.AccessPolicy;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;
import covia.lattice.Position;
import covia.lattice.ValuePath;
import covia.lattice.Workspace;
import convex.lattice.cursor.StampedCursor;

/**
 * Per-user domain state, with atomic mutations and compound transactions.
 *
 * <p>Wraps a lattice cursor at {@code :user-data → <did>} within the
 * venue state. Provides typed accessors for per-user data. Created by
 * {@link Users#get(AString)} (returns null if the user doesn't exist),
 * {@link Users#ensure(AString)} (creates if needed) or, for a path,
 * {@link Users#at(AString)} (whether or not the account is live).</p>
 *
 * <p>The per-user lattice uses short AString-compatible keys from
 * {@link Namespace} for JSON compliance:</p>
 * <ul>
 *   <li>{@code "j"} — user's jobs ({@link Jobs})</li>
 *   <li>{@code "g"} — user's agents ({@link AgentState})</li>
 *   <li>{@code "s"} — user's encrypted credentials</li>
 *   <li>{@code "w"} — user's workspace data ({@link Workspace}, in a {@link covia.lattice.Covia#WORKSPACE_RECORD})</li>
 *   <li>{@code "o"} — user's operations ({@link Workspace}, in a {@link covia.lattice.Covia#WORKSPACE_RECORD})</li>
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

	/** Who may use this user's data: whoever holds authority over {@code <did>/<path>}. */
	private final AccessPolicy policy;

	/** Framework-internal read of a path in this user's record. */
	ACell readInternalPath(CoviaPath path) {
		return resolve(path).read();
	}

	/**
	 * Framework-internal atomic write to a workspace path, creating the levels
	 * above it; authority is possession of this user cursor.
	 */
	void writeInternalPath(CoviaPath path, ACell value) {
		resolve(path).entry().transact(draft -> {
			draft.createLevels();
			draft.write(value);
		});
	}

	/** Framework-internal atomic delete of a workspace path; authority is possession of this user cursor. */
	boolean deleteInternalPath(CoviaPath path) {
		return resolve(path).entry().delete();
	}

	User(VenueContext context, AForkableCursor<ACell> cursor, AString did) {
		super(cursor);
		this.context = context;
		this.did = did;
		this.policy = (did == null) ? null : AccessPolicy.principal(did);
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampUserMeta);
	}

	/** This user's workspace ({@code w}). */
	public Workspace workspace() {
		return new Workspace(wrapped(Namespace.W));
	}

	/**
	 * Storage size, in bytes, of the data this user and their agents write as
	 * they choose: the workspace ({@code w}) and each agent's own workspace
	 * ({@code n}). It is measured as stored, so a value held in two places
	 * counts twice. Session and job scratch are not counted.
	 *
	 * @return Bytes, zero for a user with no workspace data
	 */
	public long workspaceSize() {
		long size = storedSize(cursor().path(Namespace.W).get());
		AMap<AString, ACell> agents = getAgents();
		if (agents != null) {
			for (AString agentId : agents.keySet()) {
				AgentState agent = agent(agentId);
				if (agent != null) size += storedSize(agent.workspace().cursor().get());
			}
		}
		return size;
	}

	private static long storedSize(ACell value) {
		return (value == null) ? 0 : Cells.storageSize(value);
	}

	/** This user's operation definitions ({@code o}), held as a workspace. */
	public Workspace operations() {
		return new Workspace(wrapped(Namespace.O));
	}

	/** The data of a namespace held in an {@code {updated, data}} record, which a change stamps. */
	private AForkableCursor<ACell> wrapped(AString namespace) {
		return StampedCursor.create(cursor().withDefault(Maps.empty()).path(namespace),
				context::currentTimestamp, Covia::stampUpdated)
			.withDefault(Maps.empty()).path(Covia.WORKSPACE_DATA);
	}

	/**
	 * The agent at {@code agentId}, whether or not its record exists, for a path
	 * that names it: a missing agent reads as absent, and its workspaces refuse
	 * changes. Domain callers use {@link #agent}, which is null for a missing
	 * agent.
	 */
	public AgentState agentAt(AString agentId) {
		return new AgentState(context, agentStorage(agentId), did, agentId);
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
		return new AgentState(context, c, did, agentId);
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
		AgentState state = new AgentState(context, c, did, agentId);
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
		AgentState state = new AgentState(context, c, did, agentId);
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
		AgentState fork = new AgentState(context, c, did, agentId);
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
		return new Inbox(wrapped(Namespace.H));
	}

	/** This user's jobs ({@code j/}), bound to this user or transaction draft. */
	public Jobs jobs() {
		return new Jobs(context, convex.lattice.LatticeComponent.resolve(cursor(), Covia.USER_RECORD, Namespace.J), did);
	}

	/**
	 * A user's namespaces are the names in its record's lattice
	 * ({@link Covia#USER_RECORD}), each resolved by its component, and
	 * {@code g/<id>} by that agent whether or not it exists. Any other name,
	 * the venue's own bookkeeping in the record among them, names no data.
	 * The user's policy covers whatever no component below claims: a request
	 * needs authority over {@code <did>/<path>}.
	 */
	@Override
	public Position resolve(CoviaPath path) {
		Position position = within(path);
		return (did == null) ? position : position.ownedBy(policy, path);
	}

	private Position within(CoviaPath path) {
		if (path.isEmpty()) return super.resolve(path);
		AString segment = path.first();
		if (Covia.USER_RECORD.path(segment) == null) return Position.invalid(path);
		if (Namespace.W.equals(segment)) return workspace().resolve(path.rest());
		if (Namespace.O.equals(segment)) return operations().resolve(path.rest());
		if (Namespace.H.equals(segment)) return inbox().resolve(path.rest());
		if (Namespace.J.equals(segment)) return jobs().resolve(path.rest());
		if (Namespace.S.equals(segment)) return secrets().resolve(path.rest());
		if (Namespace.A.equals(segment)) return assets().resolve(path.rest());
		if (Namespace.G.equals(segment) && path.length() > 1) return agentAt(path.get(1)).resolve(path.rest(2));
		return super.resolve(path);
	}

	/** The user's data as callers see it, its jobs included, without the venue's bookkeeping. */
	@Override
	public ACell read() {
		AMap<AString, ACell> record = Users.content(cursor().get());
		if (record == null) return null;
		if (record.containsKey(Namespace.J)) record = record.assoc(Namespace.J, jobs().read());
		return record;
	}

	/** Data no namespace component claims is read from the record as stored. */
	@Override
	public ACell read(CoviaPath path) {
		return path.isEmpty() ? read() : ValuePath.get(cursor().get(), path);
	}

	@Override
	public boolean contains(CoviaPath path) {
		return ValuePath.contains(cursor().get(), path);
	}

}
