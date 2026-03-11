package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.ALatticeCursor;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * Cursor wrapper for a single user's state within a venue.
 *
 * <p>Wraps a lattice cursor at {@code :user-data → <did>} within the
 * venue state. Provides typed accessors for per-user data. Created by
 * {@link VenueState#user(AString)} (returns null if the user doesn't
 * exist) or {@link VenueState#ensureUser(AString)} (creates if needed).</p>
 *
 * <p>Follows the same lattice app wrapper pattern as {@link AssetStore}
 * and {@link JobStore}. The per-user lattice uses short AString-compatible
 * keys from {@link Namespace} for JSON compliance:</p>
 * <ul>
 *   <li>{@code "j"} — user's job references (IndexLattice + LWW)</li>
 *   <li>{@code "g"} — user's agents (MapLattice + AGENT)</li>
 *   <li>{@code "s"} — user's encrypted credentials (MapLattice + LWW)</li>
 * </ul>
 */
public class User extends ALatticeComponent<ACell> {

	private final AString did;

	User(ALatticeCursor<ACell> cursor, AString did) {
		super(cursor);
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
	 * Gets the user's job store (per-user job references, LWW merge).
	 *
	 * @return JobStore wrapping the user's "j" cursor
	 */
	public JobStore jobs() {
		return new JobStore(cursor.path(Namespace.J));
	}

	/**
	 * Gets the user's secret store (per-user encrypted credentials).
	 *
	 * @return SecretStore wrapping the user's "s" cursor
	 */
	public SecretStore secrets() {
		return new SecretStore(cursor.path(Namespace.S));
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
		return new AgentState(c, agentId);
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
		AgentState state = new AgentState(c, agentId);
		if (!state.exists()) state.initialise(config, initialState);
		return state;
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

}
