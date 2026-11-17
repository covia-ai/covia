package covia.venue.migration;

import static covia.venue.migration.Records.assoc;
import static covia.venue.migration.Records.convertAt;

import java.util.HashSet;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.Agent;

/**
 * Part of venue version 2 to 3: a record of a call names one principal, its
 * {@code caller}, and no {@code owner}. A record the venue keeps in an account
 * leaves out a caller that is that account; a scheduled event, kept venue-wide,
 * always names its caller, whose account it belongs to.
 *
 * <p>Version 2 wrote {@code owner} only when it differed from the caller: the
 * caller was then one of the owner's agents, or an {@code admin/sudo}
 * delegate whose work acted for the owner. The agent's records keep the agent
 * as their caller. The delegate's work never passed {@code admin/sudo} as it
 * is now, a call into the user, and an upgrade never widens what work may do,
 * so the delegate's unfinished work ends, saying why, and its finished records
 * keep naming the delegate, since who acted is a fact:</p>
 *
 * <ul>
 *   <li><b>Job</b> ({@code j/}): an unfinished one fails with
 *       {@link #DELEGATE_WORK_ENDED}; its entry in {@code :jobs} goes, and so
 *       does the task it is to any agent of the account.</li>
 *   <li><b>Approval request</b> ({@code h/}): an open one is cancelled. It is
 *       kept in the inbox of whom it asked, so the test is against its own
 *       {@code owner}, never the inbox, and other users' requests keep their
 *       caller.</li>
 *   <li><b>Scheduled event</b> ({@code :schedule}): it goes, with its entry in
 *       the {@code byActor} index.</li>
 * </ul>
 *
 * <p>{@link #userState} converts one user's state, so {@code user:import}
 * brings an older export to the same shape.</p>
 */
public final class OneCaller implements MigrationStep {

	/** Why work a delegate left ends at the upgrade. */
	public static final String DELEGATE_WORK_ENDED = "Ended by the venue upgrade: an admin/sudo delegate left this work "
		+ "before admin/sudo acted as the user, and an upgrade never carries work on with more authority than it had";

	static final AString EVENTS = Strings.intern("events");
	static final AString BY_ACTOR = Strings.intern("byActor");
	static final AString TASKS = Agent.KEY_TASKS;

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?>)) return venue;
		Set<Blob> ended = new HashSet<>();
		ACell result = convertAt(venue, Covia.USER_DATA, users -> (users instanceof AMap<?, ?> byDID)
			? users(byDID, ended) : users);
		if (!ended.isEmpty()) {
			result = convertAt(result, Covia.JOBS, entries -> (entries instanceof Index<?, ?> index)
				? without(index, ended) : entries);
		}
		return convertAt(result, Covia.SCHEDULE, OneCaller::schedule);
	}

	@SuppressWarnings("unchecked")
	private static ACell users(AMap<?, ?> byDID, Set<Blob> ended) {
		AMap<ACell, ACell> result = (AMap<ACell, ACell>) byDID;
		for (var entry : ((AMap<ACell, ACell>) byDID).entrySet()) {
			AString did = RT.ensureString(entry.getKey());
			ACell converted = convert(entry.getValue(), did, ended);
			if (converted != entry.getValue()) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	/**
	 * One user's state in the current shape: its jobs and approval requests
	 * name one caller, left out when it is {@code account}, and a delegate's
	 * unfinished work there has ended. The same cell when nothing changes.
	 *
	 * @param account the account the state is kept in
	 */
	public static ACell userState(ACell state, AString account) {
		return convert(state, account, new HashSet<>());
	}

	private static ACell convert(ACell state, AString account, Set<Blob> ended) {
		if (account == null || !(state instanceof AMap<?, ?>)) return state;
		Set<Blob> here = new HashSet<>();
		ACell result = convertAt(state, Namespace.J, jobs -> (jobs instanceof Index<?, ?> index)
			? jobs(index, account, here) : jobs);
		result = convertAt(result, Namespace.H, inbox -> convertAt(inbox, Covia.WORKSPACE_DATA,
			requests -> (requests instanceof Index<?, ?> index) ? requests(index, account) : requests));
		if (!here.isEmpty()) {
			result = convertAt(result, Namespace.G, agents -> (agents instanceof AMap<?, ?> byId)
				? Records.mapValues(byId, agent -> convertAt(agent, TASKS,
					tasks -> (tasks instanceof Index<?, ?> index) ? without(index, here) : tasks))
				: agents);
			ended.addAll(here);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static ACell jobs(Index<?, ?> index, AString account, Set<Blob> ended) {
		Index<Blob, ACell> result = (Index<Blob, ACell>) index;
		for (long i = 0, n = index.count(); i < n; i++) {
			var entry = ((Index<Blob, ACell>) index).entryAt(i);
			AMap<AString, ACell> job = stringMap(entry.getValue());
			if (job == null) continue;
			AMap<AString, ACell> converted = job;
			if (isDelegates(job) && job.get(Fields.STATUS) instanceof AString && !Job.isFinished(job)) {
				converted = converted.assoc(Fields.STATUS, Status.FAILED)
					.assoc(Fields.ERROR, Strings.create(DELEGATE_WORK_ENDED));
				ended.add(entry.getKey());
			}
			converted = oneCaller(converted, account);
			if (!converted.equals(job)) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static ACell requests(Index<?, ?> index, AString account) {
		Index<Blob, ACell> result = (Index<Blob, ACell>) index;
		for (long i = 0, n = index.count(); i < n; i++) {
			var entry = ((Index<Blob, ACell>) index).entryAt(i);
			AMap<AString, ACell> request = stringMap(entry.getValue());
			if (request == null) continue;
			AMap<AString, ACell> converted = request;
			if (isDelegates(request) && Hitl.OPEN.equals(request.get(Hitl.STATUS))) {
				converted = converted.assoc(Hitl.STATUS, Hitl.CANCELLED);
			}
			converted = oneCaller(converted, account);
			if (!converted.equals(request)) result = result.assoc(entry.getKey(), converted);
		}
		return result;
	}

	/** The schedule with each event naming only its caller, and the events delegates left gone. */
	@SuppressWarnings("unchecked")
	private static ACell schedule(ACell schedule) {
		if (!(schedule instanceof AMap<?, ?> s) || !(s.get(EVENTS) instanceof Index<?, ?> index)) return schedule;
		Index<Blob, ACell> events = (Index<Blob, ACell>) index;
		ACell byActor = s.get(BY_ACTOR);
		for (long i = index.count() - 1; i >= 0; i--) {
			var entry = ((Index<Blob, ACell>) index).entryAt(i);
			AMap<AString, ACell> event = stringMap(entry.getValue());
			if (event == null || !event.containsKey(Fields.OWNER)) continue;
			if (isDelegates(event)) {
				events = events.dissoc(entry.getKey());
				byActor = unindexed(byActor, RT.ensureString(event.get(Fields.CALLER)), entry.getKey());
			} else {
				events = events.assoc(entry.getKey(), event.dissoc(Fields.OWNER));
			}
		}
		if (events == index) return schedule;
		ACell result = assoc(schedule, EVENTS, events);
		return (byActor == null) ? result : assoc(result, BY_ACTOR, byActor);
	}

	/** {@code byActor} without {@code key} under {@code caller}, and without the caller once it has none. */
	@SuppressWarnings("unchecked")
	private static ACell unindexed(ACell byActor, AString caller, Blob key) {
		if (caller == null || !(byActor instanceof AMap<?, ?> m) || !(m.get(caller) instanceof ASet<?> keys)) return byActor;
		ASet<Blob> rest = ((ASet<Blob>) keys).exclude(key);
		AMap<AString, ACell> index = (AMap<AString, ACell>) m;
		return rest.isEmpty() ? index.dissoc(caller) : index.assoc(caller, rest);
	}

	/**
	 * Whether a version 2 record is work an {@code admin/sudo} delegate left:
	 * it names an {@code owner}, so its caller differed, and that caller is not
	 * one of the owner's agents.
	 */
	static boolean isDelegates(AMap<AString, ACell> record) {
		AString owner = RT.ensureString(record.get(Fields.OWNER));
		AString caller = RT.ensureString(record.get(Fields.CALLER));
		if (owner == null || caller == null || owner.equals(caller)) return false;
		AString agentId = Principals.agentIdOf(caller);
		return agentId == null || !caller.equals(Principals.agentDID(owner, agentId));
	}

	/** {@code record} with no {@code owner}, and no caller when that is {@code account}. */
	static AMap<AString, ACell> oneCaller(AMap<AString, ACell> record, AString account) {
		AMap<AString, ACell> result = record.dissoc(Fields.OWNER);
		return account.equals(result.get(Fields.CALLER)) ? result.dissoc(Fields.CALLER) : result;
	}

	@SuppressWarnings("unchecked")
	private static ACell without(Index<?, ?> index, Set<Blob> keys) {
		Index<Blob, ACell> result = (Index<Blob, ACell>) index;
		for (Blob key : keys) result = result.dissoc(key);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> stringMap(ACell cell) {
		return (cell instanceof AMap<?, ?> m) ? (AMap<AString, ACell>) m : null;
	}
}
