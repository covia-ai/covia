package covia.venue.migration;

import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Attribution;
import covia.grid.Principals;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.AgentState;

/**
 * Venue version 1 to 2: a record of a call names the principal that made it
 * as {@code caller} and the namespace it belongs to as {@code owner}, written
 * only when that differs ({@link Attribution}). Each older shape is told apart
 * from the current one by a field only it has:
 *
 * <ul>
 *   <li><b>Job</b> ({@code j/}): {@code actor} present means
 *       {@code caller := actor} and {@code owner :=} the old {@code caller}.</li>
 *   <li><b>Scheduled event</b> ({@code :schedule}): no {@code caller} means
 *       {@code caller := actor}, else the old {@code owner}; {@code by} is
 *       dropped. The {@code byActor} index already keys each event a principal
 *       made for another namespace by that principal, its new {@code caller}.</li>
 *   <li><b>HITL request</b> ({@code h/}): {@code from} present means
 *       {@code caller := from} and {@code owner := requesterUser}, else the
 *       namespace a record naming only its caller had ({@link #legacyNamespace}).</li>
 *   <li><b>Agent output snapshot</b> ({@code outputContext} on an agent's
 *       task): {@code user} becomes {@code owner}. One from before 0.10.0 names
 *       only its caller, and takes the same legacy namespace.</li>
 * </ul>
 *
 * <p>{@link #userState} converts one user's state, so {@code user:import} brings
 * an older export to the same shape.</p>
 */
public final class CallerAndOwner implements MigrationStep {

	static final AString ACTOR = Strings.intern("actor");
	static final AString BY = Strings.intern("by");
	static final AString FROM = Strings.intern("from");
	static final AString REQUESTER_USER = Strings.intern("requesterUser");
	static final AString USER = Strings.intern("user");
	static final AString EVENTS = Strings.intern("events");
	static final AString TASKS = AgentState.KEY_TASKS;
	static final AString OUTPUT_CONTEXT = Strings.intern("outputContext");

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> value)) return venue;
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) value;
		UnaryOperator<AString> legacy = caller -> legacyNamespace(venue, caller);
		ACell users = record.get(Covia.USER_DATA);
		if (users instanceof AMap<?, ?> byDID) {
			ACell converted = mapValues(byDID, user -> userState(user, legacy));
			if (converted != users) record = record.assoc(Covia.USER_DATA, converted);
		}
		ACell schedule = record.get(Covia.SCHEDULE);
		if (schedule instanceof AMap<?, ?> s) {
			ACell events = RT.getIn(s, EVENTS);
			ACell converted = (events instanceof Index<?, ?> index) ? indexValues(index, CallerAndOwner::event) : events;
			if (converted != events) record = record.assoc(Covia.SCHEDULE, assoc(s, EVENTS, converted));
		}
		return record;
	}

	/**
	 * One user's state in the current shape: its jobs, HITL requests and agents'
	 * output snapshots. The same cell when nothing changes.
	 *
	 * @param legacy the namespace of a record that names only its caller
	 */
	public static ACell userState(ACell state, UnaryOperator<AString> legacy) {
		if (!(state instanceof AMap<?, ?> user)) return state;
		ACell result = user;
		ACell jobs = user.get(Namespace.J);
		if (jobs instanceof Index<?, ?> index) {
			ACell converted = indexValues(index, CallerAndOwner::job);
			if (converted != jobs) result = assoc(result, Namespace.J, converted);
		}
		ACell inbox = user.get(Namespace.H);
		ACell items = RT.getIn(inbox, Covia.WORKSPACE_DATA);
		if (items instanceof AMap<?, ?> byId) {
			ACell converted = mapValues(byId, item -> hitlRequest(item, legacy));
			if (converted != items) result = assoc(result, Namespace.H, assoc(inbox, Covia.WORKSPACE_DATA, converted));
		}
		ACell agents = user.get(Namespace.G);
		if (agents instanceof AMap<?, ?> byId) {
			ACell converted = mapValues(byId, agent -> agentTasks(agent, legacy));
			if (converted != agents) result = assoc(result, Namespace.G, converted);
		}
		return result;
	}

	/** A job record: {@code actor} present means it acted for the old {@code caller}. */
	static ACell job(ACell cell) {
		AMap<AString, ACell> job = stringMap(cell);
		if (job == null || !job.containsKey(ACTOR)) return cell;
		AString actor = RT.ensureString(job.get(ACTOR));
		AString owner = RT.ensureString(job.get(Fields.CALLER));
		return Attribution.attribute(job.dissoc(ACTOR).dissoc(Fields.CALLER), actor, owner);
	}

	/** A scheduled event: no {@code caller} means it names its owner, and its actor when not the owner. */
	static ACell event(ACell cell) {
		AMap<AString, ACell> event = stringMap(cell);
		if (event == null || event.containsKey(Fields.CALLER)) return cell;
		AString owner = RT.ensureString(event.get(Fields.OWNER));
		AString actor = RT.ensureString(event.get(ACTOR));
		AMap<AString, ACell> rest = event.dissoc(ACTOR).dissoc(BY).dissoc(Fields.OWNER);
		return Attribution.attribute(rest, (actor != null) ? actor : owner, owner);
	}

	/** A HITL request: {@code from} present means it names its asker that way. */
	static ACell hitlRequest(ACell cell, UnaryOperator<AString> legacy) {
		AMap<AString, ACell> item = stringMap(cell);
		if (item == null || !item.containsKey(FROM)) return cell;
		AString caller = RT.ensureString(item.get(FROM));
		AString owner = RT.ensureString(item.get(REQUESTER_USER));
		if (owner == null && caller != null) owner = legacy.apply(caller);
		return Attribution.attribute(item.dissoc(FROM).dissoc(REQUESTER_USER), caller, owner);
	}

	/** An agent record with each task's output snapshot in the current shape. */
	private static ACell agentTasks(ACell agent, UnaryOperator<AString> legacy) {
		ACell tasks = RT.getIn(agent, TASKS);
		if (!(tasks instanceof Index<?, ?> index)) return agent;
		ACell converted = indexValues(index, task -> {
			ACell snapshot = RT.getIn(task, OUTPUT_CONTEXT);
			ACell current = outputContext(snapshot, legacy);
			return (current == snapshot) ? task : assoc(task, OUTPUT_CONTEXT, current);
		});
		return (converted == tasks) ? agent : assoc(agent, TASKS, converted);
	}

	/**
	 * An output snapshot: {@code user} present means it names its namespace that
	 * way; neither {@code user} nor {@code owner} means it is from before 0.10.0
	 * and names only its caller.
	 */
	static ACell outputContext(ACell cell, UnaryOperator<AString> legacy) {
		AMap<AString, ACell> snapshot = stringMap(cell);
		if (snapshot == null || snapshot.containsKey(Fields.OWNER)) return cell;
		AString caller = RT.ensureString(snapshot.get(Fields.CALLER));
		if (caller == null) return cell;
		AString owner = snapshot.containsKey(USER) ? RT.ensureString(snapshot.get(USER)) : legacy.apply(caller);
		AMap<AString, ACell> current = Attribution.attribute(snapshot.dissoc(USER).dissoc(Fields.CALLER), caller, owner);
		return current.equals(snapshot) ? cell : current;
	}

	/**
	 * The namespace of a record that names only its caller, as those written
	 * before 0.10.0 do: its owner's when the caller is one of this venue's
	 * agents that still exists, else the caller's own. A name alone never puts
	 * a principal in another namespace.
	 *
	 * @param venue the venue value whose users' agents decide it
	 */
	public static AString legacyNamespace(ACell venue, AString caller) {
		AString owner = Principals.userOf(caller);
		AString agentId = Principals.agentIdOf(caller);
		if (agentId == null || !caller.equals(Principals.agentDID(owner, agentId))) return caller;
		ACell agent = RT.getIn(venue, Covia.USER_DATA, owner, Namespace.G, agentId);
		if (!(agent instanceof AMap<?, ?>)) return caller;
		return AgentState.TERMINATED.equals(RT.getIn(agent, AgentState.KEY_STATUS)) ? caller : owner;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> stringMap(ACell cell) {
		return (cell instanceof AMap<?, ?> m) ? (AMap<AString, ACell>) m : null;
	}

	@SuppressWarnings("unchecked")
	private static ACell assoc(ACell map, ACell key, ACell value) {
		return ((AMap<ACell, ACell>) map).assoc(key, value);
	}

	/** {@code map} with each value converted; the same cell when none changes. */
	@SuppressWarnings("unchecked")
	private static ACell mapValues(AMap<?, ?> map, UnaryOperator<ACell> convert) {
		AMap<ACell, ACell> result = (AMap<ACell, ACell>) map;
		for (var entry : ((AMap<ACell, ACell>) map).entrySet()) {
			ACell converted = convert.apply(entry.getValue());
			if (converted != entry.getValue()) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	/** {@code index} with each value converted; the same cell when none changes. */
	@SuppressWarnings("unchecked")
	private static ACell indexValues(Index<?, ?> index, UnaryOperator<ACell> convert) {
		Index<Blob, ACell> result = (Index<Blob, ACell>) index;
		for (long i = 0, n = index.count(); i < n; i++) {
			var entry = ((Index<Blob, ACell>) index).entryAt(i);
			ACell converted = convert.apply(entry.getValue());
			if (converted != entry.getValue()) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}
}
