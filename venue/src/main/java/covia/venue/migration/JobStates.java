package covia.venue.migration;

import static covia.venue.migration.Records.assoc;
import static covia.venue.migration.Records.convertAt;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;

/**
 * Part of venue version 2 to 3: work the venue carries on keeps one state,
 * the frame it acts in, stored whole, and the grants its request presented
 * (AUTHORITY.md, A job's state). It runs after {@link OneCaller}, so a job
 * record that names no caller is its account's.
 *
 * <ul>
 *   <li><b>Unfinished job</b> ({@code :jobs}): its entry's {@code context}
 *       becomes its state. Its frame's authority is the job's caller, and its
 *       grants and execution scopes are those the version 2 context kept. A
 *       job whose adapter kept no context keeps none, so its work is never
 *       carried on as its caller after the upgrade, as it was not before.</li>
 *   <li><b>Scheduled event</b> ({@code :schedule}): its {@code proofs} and
 *       {@code authority} become its {@code context}, whose frame's authority
 *       is the event's caller.</li>
 * </ul>
 *
 * <p>Version 2 also kept, as {@code authority}, the controller an agent's
 * work acted with before an agent's authority was worked out from its record.
 * Version 2 no longer read it, so it is not carried: the work acts as its
 * recorded caller, as it already did. Access tokens and callables did not
 * exist in a version 2 store.</p>
 */
public final class JobStates implements MigrationStep {

	static final AString EVENTS = Strings.intern("events");
	/** The execution scopes a version 2 context kept, which the state keeps as they were. */
	static final AString[] SCOPES = {Fields.AGENT_ID, Fields.SESSION_ID, Fields.JOB_ID, Fields.TASK_ID};

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> record)) return venue;
		ACell users = record.get(Covia.USER_DATA);
		ACell result = convertAt(venue, Covia.JOBS, entries -> (entries instanceof Index<?, ?> index)
			? jobs(index, users) : entries);
		return convertAt(result, Covia.SCHEDULE, JobStates::schedule);
	}

	/** Each unfinished job's entry with its state. */
	@SuppressWarnings("unchecked")
	private static ACell jobs(Index<?, ?> index, ACell users) {
		Index<Blob, ACell> result = (Index<Blob, ACell>) index;
		for (long i = 0, n = index.count(); i < n; i++) {
			var e = ((Index<Blob, ACell>) index).entryAt(i);
			AMap<AString, ACell> entry = stringMap(e.getValue());
			if (entry == null) continue;
			AMap<AString, ACell> context = RT.ensureMap(entry.get(JobIndex.CONTEXT));
			if (context == null || context.containsKey(Fields.FRAME)) continue;
			AString account = RT.ensureString(entry.get(JobIndex.OWNER));
			AMap<AString, ACell> job = (account == null) ? null
				: RT.ensureMap(RT.getIn(users, account, Namespace.J, e.getKey()));
			if (job == null) continue;
			AString caller = RT.ensureString(job.get(Fields.CALLER));
			AMap<AString, ACell> state = state((caller == null) ? account : caller, context.get(Fields.PROOFS));
			for (AString scope : SCOPES) {
				ACell value = context.get(scope);
				if (value != null) state = state.assoc(scope, value);
			}
			result = result.assoc(e.getKey(), entry.assoc(JobIndex.CONTEXT, state));
		}
		return result;
	}

	/** The schedule with each event's proofs and authority as its state. */
	@SuppressWarnings("unchecked")
	private static ACell schedule(ACell schedule) {
		if (!(schedule instanceof AMap<?, ?> s) || !(s.get(EVENTS) instanceof Index<?, ?> index)) return schedule;
		Index<Blob, ACell> events = (Index<Blob, ACell>) index;
		for (long i = 0, n = index.count(); i < n; i++) {
			var e = ((Index<Blob, ACell>) index).entryAt(i);
			AMap<AString, ACell> event = stringMap(e.getValue());
			if (event == null || event.containsKey(Fields.CONTEXT)) continue;
			AString caller = RT.ensureString(event.get(Fields.CALLER));
			if (caller == null) continue;
			AMap<AString, ACell> converted = event.dissoc(Fields.PROOFS).dissoc(Fields.AUTHORITY)
				.assoc(Fields.CONTEXT, state(caller, event.get(Fields.PROOFS)));
			events = events.assoc(e.getKey(), converted);
		}
		return (events == index) ? schedule : assoc(schedule, EVENTS, events);
	}

	/** The state of {@code authority}'s own frame, presenting {@code proofs} when there are any. */
	private static AMap<AString, ACell> state(AString authority, ACell proofs) {
		AMap<AString, ACell> state = Maps.of(Fields.FRAME, Maps.of(Fields.AUTHORITY, authority));
		return (proofs instanceof AVector<?> v && !v.isEmpty()) ? state.assoc(Fields.PROOFS, proofs) : state;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> stringMap(ACell cell) {
		return (cell instanceof AMap<?, ?> m) ? (AMap<AString, ACell>) m : null;
	}
}
