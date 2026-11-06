package covia.venue.migration;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;
import covia.venue.OutputRules;
import covia.venue.SecretStore;

/**
 * Venue version 2 to 3: the venue keeps an entry for each unfinished job in
 * {@code :jobs} ({@link JobIndex}), and a restart recovers jobs from those
 * entries alone. Each unfinished job record of a live account gets one naming
 * its owner, and what the record or the venue kept for the venue's own use
 * moves into it:
 *
 * <ul>
 *   <li>Proofs a record kept for work that continues become the entry's
 *       caller context.</li>
 *   <li>A remote job's connection, which version 2 kept encrypted in the
 *       venue user's secret {@code remote-job-<id>} named by the record's
 *       {@code delegation.authRef}, becomes the entry's adapter data, and the
 *       definition pinned with it gives the entry's output rules. Only the
 *       fields that say how to reach the remote job again are carried: never
 *       a sign-in credential, and nothing but its mode for a hop that signed in
 *       as its caller, so recovery ends that job with the remote job's
 *       address.</li>
 *   <li>The fields an external agent's job kept, {@code agentOwner},
 *       {@code agentInteraction} and {@code agentBackendJob}, become the
 *       entry's adapter data.</li>
 *   <li>A request's pending {@code outputPath} write kept its requester's
 *       context on the worker agent's task as {@code outputContext}: its
 *       proofs and execution scopes become the request job's caller context,
 *       and the task keeps neither {@code outputContext} nor
 *       {@code outputPath}, which the request's input holds.</li>
 * </ul>
 *
 * <p>Every {@code remote-job-*} secret then goes, those left by a crash
 * included. Any other job's entry keeps no output rules: they are what its
 * operation declared when it was admitted, and a step cannot resolve an
 * operation. So a job admitted before the upgrade hides no further secret
 * fields and has no output schema checked when it finishes.</p>
 */
public final class JobEntries implements MigrationStep {

	static final AString DELEGATION = Strings.intern("delegation");
	static final AString AUTH_REF = Strings.intern("authRef");
	static final AString OPERATION_METADATA = Strings.intern("operationMetadata");
	static final String REMOTE_JOB = "remote-job-";
	static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");
	static final AString CALLER = Strings.intern("caller");
	/** The fields of a kept connection that say how to reach a remote job again; never a credential. */
	static final AString[] CONNECTION = {
		AUTHENTICATE_AS, Strings.intern("outboundAny"), Fields.VENUE, Fields.UCANS,
		Strings.intern("location"), Fields.NAME, Strings.intern("bearer"), Strings.intern("secret")};
	static final AString TASKS = Strings.intern("tasks");
	static final AString OUTPUT_CONTEXT = Strings.intern("outputContext");
	/** What a pending write's context keeps beyond its caller and owner, which the request's record names. */
	static final AString[] CALLER_CONTEXT = {Fields.PROOFS, Fields.AGENT_ID, Fields.SESSION_ID, Fields.JOB_ID, Fields.TASK_ID};
	static final AString[] EXTERNAL_AGENT = {
		Strings.intern("agentOwner"), Strings.intern("agentInteraction"), Strings.intern("agentBackendJob")};

	private final SecretStore.Keys keys;

	/** @param keys The venue's secret store keys, which read the kept connections */
	public JobEntries(SecretStore.Keys keys) {
		this.keys = Objects.requireNonNull(keys);
	}

	@Override
	public ACell apply(ACell venue) {
		if (!(venue instanceof AMap<?, ?> value)) return venue;
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) value;
		if (!(record.get(Covia.USER_DATA) instanceof AMap<?, ?> found)) return venue;
		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> users = (AMap<ACell, ACell>) found;
		AMap<AString, ACell> secrets = venueSecrets(record);
		Index<Blob, ACell> entries = index(record.get(Covia.JOBS));
		Index<Blob, ACell> before = entries;
		for (var user : found.entrySet()) {
			if (!(user.getKey() instanceof AString did)) continue;
			if (!(user.getValue() instanceof AMap<?, ?> state) || state.get(Covia.K_DELETED) != null) continue;
			if (!(state.get(Namespace.J) instanceof Index<?, ?> stored)) continue;
			Index<Blob, ACell> jobs = index(stored);
			Index<Blob, ACell> converted = jobs;
			for (long i = 0, n = jobs.count(); i < n; i++) {
				var job = jobs.entryAt(i);
				Blob id = job.getKey();
				if (entries.containsKey(id) || !(job.getValue() instanceof AMap<?, ?> m)) continue;
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> jobRecord = (AMap<AString, ACell>) m;
				AMap<AString, ACell> current = Job.currentState(jobRecord);
				if (current == null || Job.isFinished(current)) continue;
				AMap<AString, ACell> entry = Maps.of(JobIndex.OWNER, did);
				AMap<AString, ACell> rest = jobRecord;

				ACell proofs = rest.get(Fields.PROOFS);
				if (proofs != null) {
					entry = entry.assoc(JobIndex.CONTEXT, Maps.of(Fields.PROOFS, proofs));
					rest = rest.dissoc(Fields.PROOFS);
				}

				AMap<AString, ACell> delegation = RT.ensureMap(rest.get(DELEGATION));
				AString ref = (delegation == null) ? null : RT.ensureString(delegation.get(AUTH_REF));
				if (ref != null) {
					AMap<AString, ACell> kept = kept(secrets, ref);
					if (kept != null) {
						entry = entry.assoc(JobIndex.ADAPTER_DATA, connection(kept));
						AMap<AString, ACell> rules = OutputRules.of(RT.ensureMap(kept.get(OPERATION_METADATA))).toCell();
						if (rules != null) entry = entry.assoc(JobIndex.RULES, rules);
					}
					rest = rest.assoc(DELEGATION, delegation.dissoc(AUTH_REF));
				}

				AMap<AString, ACell> agentData = Maps.empty();
				for (AString field : EXTERNAL_AGENT) {
					ACell v = rest.get(field);
					if (v == null) continue;
					agentData = agentData.assoc(field, v);
					rest = rest.dissoc(field);
				}
				if (!agentData.isEmpty()) entry = entry.assoc(JobIndex.ADAPTER_DATA, agentData);

				entries = entries.assoc(id, entry);
				if (rest != jobRecord) converted = converted.assoc(id, rest);
			}
			if (converted != jobs) users = users.assoc(did, assoc(state, Namespace.J, converted));
		}
		for (var user : users.entrySet()) {
			if (!(user.getValue() instanceof AMap<?, ?> state) || !(state.get(Namespace.G) instanceof AMap<?, ?> agents)) continue;
			ACell converted = agents;
			for (var agent : agents.entrySet()) {
				if (!(RT.getIn(agent.getValue(), TASKS) instanceof Index<?, ?> stored)) continue;
				Index<Blob, ACell> tasks = index(stored);
				Index<Blob, ACell> kept = tasks;
				for (long i = 0, n = tasks.count(); i < n; i++) {
					var task = tasks.entryAt(i);
					if (!(task.getValue() instanceof AMap<?, ?> m)) continue;
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> row = (AMap<AString, ACell>) m;
					if (!row.containsKey(OUTPUT_CONTEXT) && !row.containsKey(Fields.OUTPUT_PATH)) continue;
					AMap<AString, ACell> context = callerContext(RT.ensureMap(row.get(OUTPUT_CONTEXT)));
					AMap<AString, ACell> entry = RT.ensureMap(entries.get(task.getKey()));
					if (entry != null && context != null && !entry.containsKey(JobIndex.CONTEXT)) {
						entries = entries.assoc(task.getKey(), entry.assoc(JobIndex.CONTEXT, context));
					}
					kept = kept.assoc(task.getKey(), row.dissoc(OUTPUT_CONTEXT).dissoc(Fields.OUTPUT_PATH));
				}
				if (kept != tasks) converted = assoc((AMap<?, ?>) converted, agent.getKey(), assoc((AMap<?, ?>) agent.getValue(), TASKS, kept));
			}
			if (converted != agents) users = users.assoc(user.getKey(), assoc(state, Namespace.G, converted));
		}
		AMap<AString, ACell> remaining = withoutRemoteJobs(secrets);
		if (remaining != secrets) {
			AString host = RT.ensureString(record.get(Covia.DID));
			users = users.assoc(host, assoc(RT.ensureMap(users.get(host)), Namespace.S, remaining));
		}
		if (entries == before && users == found) return venue;
		return record.assoc(Covia.USER_DATA, users).assoc(Covia.JOBS, entries);
	}

	/** A pending write's proofs and execution scopes, or null when it kept none. */
	private static AMap<AString, ACell> callerContext(AMap<AString, ACell> snapshot) {
		if (snapshot == null) return null;
		AMap<AString, ACell> context = Maps.empty();
		for (AString field : CALLER_CONTEXT) {
			ACell v = snapshot.get(field);
			if (v != null) context = context.assoc(field, v);
		}
		return context.isEmpty() ? null : context;
	}

	/** The venue user's secrets, or null when it has none. */
	private static AMap<AString, ACell> venueSecrets(AMap<ACell, ACell> venue) {
		AString host = RT.ensureString(venue.get(Covia.DID));
		return (host == null) ? null : RT.ensureMap(RT.getIn(venue, Covia.USER_DATA, host, Namespace.S));
	}

	/** What version 2 kept for a remote job, decrypted; null when its secret is gone. */
	private AMap<AString, ACell> kept(AMap<AString, ACell> secrets, AString ref) {
		ACell sealed = (secrets == null) ? null : secrets.get(ref);
		AString plaintext = SecretStore.unseal(sealed, keys);
		return (plaintext == null) ? null : RT.ensureMap(JSON.parse(plaintext));
	}

	/** The fields of a kept connection that reach the remote job again; only the mode for a caller-signed hop. */
	static AMap<AString, ACell> connection(AMap<AString, ACell> kept) {
		if (CALLER.equals(kept.get(AUTHENTICATE_AS))) return Maps.of(AUTHENTICATE_AS, CALLER);
		AMap<AString, ACell> connection = Maps.empty();
		for (AString field : CONNECTION) {
			ACell v = kept.get(field);
			if (v != null) connection = connection.assoc(field, v);
		}
		return connection;
	}

	/** {@code secrets} without the remote jobs' records; the same map when it holds none. */
	private static AMap<AString, ACell> withoutRemoteJobs(AMap<AString, ACell> secrets) {
		if (secrets == null) return null;
		AMap<AString, ACell> remaining = secrets;
		for (AString name : secrets.keySet()) {
			if (name.toString().startsWith(REMOTE_JOB)) remaining = remaining.dissoc(name);
		}
		return remaining;
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> index(ACell jobs) {
		return (jobs instanceof Index<?, ?> index) ? (Index<Blob, ACell>) index : Index.none();
	}

	@SuppressWarnings("unchecked")
	private static ACell assoc(AMap<?, ?> map, ACell key, ACell value) {
		AMap<ACell, ACell> m = (map == null) ? Maps.empty() : (AMap<ACell, ACell>) map;
		return m.assoc(key, value);
	}
}
