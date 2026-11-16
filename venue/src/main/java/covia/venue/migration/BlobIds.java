package covia.venue.migration;

import static covia.venue.migration.Records.convertAt;
import static covia.venue.migration.Records.indexValues;
import static covia.venue.migration.Records.mapValues;
import static covia.venue.migration.Records.vectorValues;

import java.util.function.UnaryOperator;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.HITLAdapter;
import covia.api.Fields;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.Agent;
import covia.venue.JobIndex;
import covia.venue.JobManager;
import covia.venue.Task;

/**
 * Part of venue version 2 to 3: the job, task and session ids the venue
 * stores are {@link Blob}s, where earlier venues wrote them as hex text. In
 * each agent's record ({@code g/}):
 *
 * <ul>
 *   <li>a task's {@code sessionId}, the session of the cycle claim
 *       ({@code inCycle}), an outbound wait's {@code sessionId} and
 *       {@code taskId}, and a resolved task's {@code sessionId};</li>
 *   <li>in each timeline entry, its {@code sessionId}, the {@code jobId} of
 *       each task and pending job it shows, a pending job's snapshot ids, the
 *       envelopes of its messages, and its {@code taskResults}, which become
 *       an index keyed by task;</li>
 *   <li>in each session, the {@code jobId} of its queued envelopes and of the
 *       turns and compacted segments of its frames.</li>
 * </ul>
 *
 * <p>In each job record ({@code j/}), a mirror's {@code mirrorOf}, and the
 * {@code sessionId} of a job the agent adapter ran. A finished job's
 * {@code output} is what its caller was given and is left as it was.</p>
 *
 * <p>A HITL inbox ({@code h/}) becomes an index of its requests keyed by job
 * id, from a map keyed by its hex, and each request's {@code id} and
 * {@code sessionId} are Blobs. So are the {@code sessionId} in a job's entry
 * in {@code :jobs}, which a HITL request keeps there, and the request
 * {@code id} a scheduled expiry ({@link HITLAdapter#EXPIRE_OP}) is given.</p>
 *
 * <p>A message envelope, queued on a session or on a task, also loses the
 * {@code sessionId} and {@code taskId} it repeated: where it is queued says
 * both. What the model was shown (turn content, a timeline entry's
 * inferences) is left as it was written, and so is an id another system gave
 * (an external agent's own session id). An id that is neither a Blob nor hex
 * text is refused.</p>
 *
 * <p>{@link #userState} converts one user's state, so {@code user:import}
 * brings an older export to the same shape.</p>
 */
public final class BlobIds implements MigrationStep {

	/** Refusal: a stored id field holds something that is not an id. */
	public static final String NOT_AN_ID = "A stored id is neither a blob nor hex text: ";

	private static final AString[] FRAME_ENTRIES = {Fields.HISTORY, Agent.KEY_CONVERSATION, Fields.ITEMS};

	/** The adapter a job record names when the agent adapter ran it. */
	static final AString AGENT_ADAPTER = Strings.intern("agent");

	/** On a mirror job's record, the call it follows. */
	static final AString MIRROR_OF = JobManager.MIRROR_OF;

	/** The scheduled events in the {@code :schedule} region. */
	static final AString EVENTS = Strings.intern("events");

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?>)) return venue;
		ACell result = convertAt(venue, Covia.USER_DATA, users -> (users instanceof AMap<?, ?> byDID)
			? mapValues(byDID, BlobIds::userState) : users);
		result = convertAt(result, Covia.JOBS, index(entry -> convertAt(entry, JobIndex.ADAPTER_DATA,
			data -> ids(data, Fields.SESSION_ID))));
		return convertAt(result, Covia.SCHEDULE, schedule -> convertAt(schedule, EVENTS, index(BlobIds::event)));
	}

	/** One user's state with the ids it stores as Blobs; the same cell when nothing changes. */
	public static ACell userState(ACell state) {
		ACell result = convertAt(state, Namespace.G, agents -> (agents instanceof AMap<?, ?> byId)
			? mapValues(byId, BlobIds::agent) : agents);
		result = convertAt(result, Namespace.J, index(BlobIds::job));
		return convertAt(result, Namespace.H, inbox -> convertAt(inbox, Covia.WORKSPACE_DATA, BlobIds::requests));
	}

	/** A HITL inbox's requests: an index keyed by job id, from a map keyed by its hex. */
	static ACell requests(ACell requests) {
		if (requests instanceof Index<?, ?> index) return indexValues(index, request -> ids(request, Hitl.ID, Fields.SESSION_ID));
		if (!(requests instanceof AMap<?, ?> byHex)) throw new IllegalStateException(NOT_AN_ID + requests);
		Index<Blob, ACell> index = Index.none();
		for (var entry : byHex.entrySet()) {
			index = index.assoc(blob(entry.getKey()), ids(entry.getValue(), Hitl.ID, Fields.SESSION_ID));
		}
		return index;
	}

	/** A scheduled event: a HITL request's expiry names the request. */
	static ACell event(ACell event) {
		if (!HITLAdapter.EXPIRE_OP.equals(RT.getIn(event, Fields.OP))) return event;
		return convertAt(event, Fields.INPUT, input -> ids(input, Hitl.ID));
	}

	/**
	 * A job record: the call a mirror follows, and the session of an agent
	 * job. Another adapter's job may hold another system's session id under
	 * the same name, as it was given, so only the agent adapter's is an id.
	 */
	static ACell job(ACell record) {
		ACell result = ids(record, MIRROR_OF);
		return AGENT_ADAPTER.equals(RT.getIn(record, Fields.ADAPTER)) ? ids(result, Fields.SESSION_ID) : result;
	}

	static ACell agent(ACell agent) {
		ACell result = convertAt(agent, Agent.KEY_TASKS, index(BlobIds::task));
		result = convertAt(result, Agent.KEY_IN_CYCLE, claim -> ids(claim, Fields.SESSION_ID));
		result = convertAt(result, Agent.KEY_PENDING, index(wait -> ids(wait, Fields.SESSION_ID, Fields.TASK_ID)));
		result = convertAt(result, Agent.KEY_RESOLVED, index(completion -> ids(completion, Fields.SESSION_ID)));
		result = convertAt(result, Agent.KEY_TIMELINE, vector(BlobIds::timelineEntry));
		return convertAt(result, Agent.KEY_SESSIONS, index(BlobIds::session));
	}

	static ACell task(ACell row) {
		return convertAt(ids(row, Fields.SESSION_ID), Task.KEY_CONTINUATIONS, vector(BlobIds::envelope));
	}

	static ACell timelineEntry(ACell entry) {
		ACell result = ids(entry, Fields.SESSION_ID);
		result = convertAt(result, Fields.TASKS, vector(task -> ids(task, Fields.JOB_ID)));
		result = convertAt(result, Fields.PENDING, vector(job ->
			convertAt(ids(job, Fields.JOB_ID), Fields.SNAPSHOT, wait -> ids(wait, Fields.SESSION_ID, Fields.TASK_ID))));
		result = convertAt(result, Fields.MESSAGES, vector(BlobIds::envelope));
		return convertAt(result, Fields.TASK_RESULTS, BlobIds::taskResults);
	}

	/** A timeline entry's task results: an index keyed by task, from a map keyed by hex. */
	static ACell taskResults(ACell results) {
		if (results instanceof Index<?, ?>) return results;
		if (!(results instanceof AMap<?, ?> byHex)) throw new IllegalStateException(NOT_AN_ID + results);
		Index<Blob, ACell> index = Index.none();
		for (var entry : byHex.entrySet()) index = index.assoc(blob(entry.getKey()), entry.getValue());
		return index;
	}

	static ACell session(ACell session) {
		ACell result = convertAt(session, Agent.KEY_PENDING, vector(BlobIds::envelope));
		return convertAt(result, Agent.KEY_FRAMES, vector(BlobIds::frame));
	}

	/** A queued message: its chat job as a Blob, and no copy of where it is queued. */
	static ACell envelope(ACell envelope) {
		ACell result = ids(envelope, Fields.JOB_ID);
		return (result instanceof AMap<?, ?> map) ? map.dissoc(Fields.SESSION_ID).dissoc(Fields.TASK_ID) : result;
	}

	static ACell frame(ACell frame) {
		ACell result = frame;
		for (AString key : FRAME_ENTRIES) result = convertAt(result, key, vector(BlobIds::frameEntry));
		return result;
	}

	/** A turn, or a compacted segment with the entries it archives. */
	static ACell frameEntry(ACell entry) {
		return convertAt(ids(entry, Fields.JOB_ID), Fields.ITEMS, vector(BlobIds::frameEntry));
	}

	/** {@code record} with the ids at {@code keys} as Blobs. */
	static ACell ids(ACell record, AString... keys) {
		ACell result = record;
		for (AString key : keys) result = convertAt(result, key, BlobIds::id);
		return result;
	}

	/** An id as a Blob: the same cell when it is one. */
	static ACell id(ACell cell) {
		return (cell instanceof ABlob) ? cell : blob(cell);
	}

	private static Blob blob(ACell cell) {
		Blob id = (cell instanceof AString text) ? Blob.parse(text) : null;
		if (id == null || id.count() == 0) throw new IllegalStateException(NOT_AN_ID + cell);
		return id;
	}

	private static UnaryOperator<ACell> index(UnaryOperator<ACell> convert) {
		return value -> (value instanceof Index<?, ?> index) ? indexValues(index, convert) : value;
	}

	private static UnaryOperator<ACell> vector(UnaryOperator<ACell> convert) {
		return value -> (value instanceof AVector<?> vector) ? vectorValues(vector, convert) : value;
	}
}
