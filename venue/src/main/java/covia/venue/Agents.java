package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.lattice.MapComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;

/**
 * A user's agents ({@code g}), keyed by agent id. {@link #agent} gives one
 * agent, which has its own operations ({@link Agent}).
 *
 * <p>A deleted agent stays here, its whole record kept with status
 * {@link Agent#TERMINATED}, for audit and to keep its id taken, until it is
 * removed ({@link #remove}). Whether an agent is deleted is the agent's own
 * answer ({@link Agent#isTerminated}).</p>
 *
 * <p>The collection has no policy of its own: the user's covers it. Only venue
 * code writes {@code g}. An agent is made with {@link #create} or
 * {@link #createFork}: a {@code put} of a raw record bypasses the agent's own
 * initialisation.</p>
 *
 * <p>A removal is durable: the venue merge is whole-value LWW, so the
 * snapshot without the agent wins by timestamp and the agent is not
 * resurrected on sync.</p>
 */
public final class Agents extends MapComponent<AString, ACell>
		implements TransactionalComponent<AHashMap<AString, ACell>, Agents> {

	private final VenueContext context;
	private final AString owner;

	/**
	 * @param owner DID of the user whose agents these are, or null outside any user
	 */
	Agents(VenueContext context, AForkableCursor<AHashMap<AString, ACell>> cursor, AString owner) {
		super(cursor);
		this.context = context;
		this.owner = owner;
	}

	@Override
	public Agents rebind(AForkableCursor<AHashMap<AString, ACell>> cursor) {
		return new Agents(context, cursor, owner);
	}

	/** Every agent's record by id, deleted agents included; empty when there are none. */
	public AHashMap<AString, ACell> all() {
		AHashMap<AString, ACell> agents = get();
		return (agents == null) ? Maps.empty() : agents;
	}

	/** The agent under {@code id}, deleted or not, or null when it has no record. */
	public Agent agent(AString id) {
		AForkableCursor<ACell> record = path(id);
		if (record.getOrDefault(null) == null) return null;
		return new Agent(context, record, owner, id);
	}

	/**
	 * The agent under {@code id} whether or not its record exists, for a path
	 * that names it: a missing agent reads as absent, and its workspaces refuse
	 * changes. Domain callers use {@link #agent}, which is null for a missing
	 * agent.
	 */
	public Agent at(AString id) {
		return new Agent(context, path(id), owner, id);
	}

	/**
	 * Creates an agent in one atomic update, under {@code controller}: the
	 * principal whose authority its harness runs under and the access token
	 * narrowing it.
	 *
	 * @param config Framework configuration, or null for none
	 * @param state Initial state for the transition function, or null for none
	 * @param definition The definition an external agent is created with, or null for an agent the venue runs
	 * @param controller Null for the agent's owner
	 * @return the new agent, or null when {@code id} is taken, by a deleted agent too
	 */
	public Agent create(AString id, AMap<AString, ACell> config, ACell state,
			AMap<AString, ACell> definition, Engine.Source controller) {
		Agent agent = at(id);
		return agent.initialiseIfAbsent(config, state, definition, null, controller) ? agent : null;
	}

	/**
	 * Creates an agent as a fork of another, from the config and state given.
	 * Its timeline is {@code timeline}, or empty when that is null; its tasks,
	 * sessions and pending messages start empty, and it is SLEEPING.
	 *
	 * @param controller As for {@link #create}
	 * @return the new agent, or null when {@code id} is taken, by a deleted agent too
	 */
	public Agent createFork(AString id, AMap<AString, ACell> config, ACell state,
			AVector<ACell> timeline, Engine.Source controller) {
		Agent fork = at(id);
		return fork.initialiseIfAbsent(config, state, null, timeline, controller) ? fork : null;
	}
}
