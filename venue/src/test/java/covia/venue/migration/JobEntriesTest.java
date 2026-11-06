package covia.venue.migration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;
import covia.venue.OutputRules;
import covia.venue.SecretStore;

/** Venue step 2 → 3 as a function of the venue value. */
class JobEntriesTest {
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString GONE = Strings.create("did:test:gone");
	private static final Blob RUNNING = Blob.fromHex("01");
	private static final Blob PAUSED = Blob.fromHex("02");
	private static final Blob DONE = Blob.fromHex("03");
	private static final Blob ERASED = Blob.fromHex("04");
	private static final AString HOST = Strings.create("did:test:venue");
	private static final SecretStore.Keys KEYS = SecretStore.keys(AKeyPair.generate());
	private static final MigrationStep STEP = new JobEntries(KEYS);
	private static final AString TOKEN = Strings.create("token");

	private static AMap<AString, ACell> job(Blob id, AString status) {
		return Maps.of(Fields.ID, id, Fields.STATUS, status, Fields.CALLER, ALICE);
	}

	/** A version 2 venue: one user's jobs in three states, and a deleted account's unfinished job. */
	private static ACell version2() {
		Index<Blob, ACell> jobs = Index.<Blob, ACell>none()
			.assoc(RUNNING, job(RUNNING, Status.STARTED))
			.assoc(PAUSED, job(PAUSED, Status.INPUT_REQUIRED))
			.assoc(DONE, job(DONE, Status.COMPLETE));
		AMap<AString, ACell> users = Maps.of(
			ALICE, Maps.of(Namespace.J, jobs),
			GONE, Maps.of(Covia.K_DELETED, Maps.empty(), Namespace.J, Index.of(ERASED, job(ERASED, Status.STARTED))));
		return Index.<Keyword, ACell>none().assoc(Covia.USER_DATA, users);
	}

	@Test void eachUnfinishedJobOfALiveAccountGetsAnEntryNamingItsOwner() {
		ACell migrated = STEP.apply(version2());
		Index<?, ?> entries = (Index<?, ?>) RT.getIn(migrated, Covia.JOBS);
		assertEquals(2, entries.count());
		assertEquals(Maps.of(JobIndex.OWNER, ALICE), entries.get(RUNNING));
		assertEquals(Maps.of(JobIndex.OWNER, ALICE), entries.get(PAUSED), "a paused job is unfinished");
		assertNull(entries.get(DONE), "a finished job has none");
		assertNull(entries.get(ERASED), "a deleted account's job has none");
		assertSame(migrated, STEP.apply(migrated), "migrating again changes nothing");
	}

	@Test void proofsARecordKeptMoveToItsEntry() {
		ACell proofs = Vectors.of(Maps.of("iss", "did:test:grantor"));
		ACell value = RT.assocIn(version2(), job(RUNNING, Status.STARTED).assoc(Fields.PROOFS, proofs),
			Covia.USER_DATA, ALICE, Namespace.J, RUNNING);
		ACell migrated = STEP.apply(value);
		assertEquals(Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.PROOFS, proofs)),
			RT.getIn(migrated, Covia.JOBS, RUNNING));
		assertEquals(job(RUNNING, Status.STARTED), RT.getIn(migrated, Covia.USER_DATA, ALICE, Namespace.J, RUNNING),
			"the record no longer holds them");
		assertSame(migrated, STEP.apply(migrated));
	}

	@Test void anEntryAlreadyMadeIsKept() {
		AMap<AString, ACell> kept = Maps.of(JobIndex.OWNER, ALICE, JobIndex.RULES, Maps.empty());
		ACell value = RT.assocIn(version2(), Index.of(RUNNING, kept), Covia.JOBS);
		assertEquals(kept, RT.getIn(STEP.apply(value), Covia.JOBS, RUNNING));
	}

	@Test void aVenueWithNoUnfinishedJobComesBackAsTheSameCell() {
		assertNull(STEP.apply(null), "a fresh venue");
		ACell empty = Index.<Keyword, ACell>none().assoc(Covia.DID, ALICE);
		assertSame(empty, STEP.apply(empty));
		ACell finished = Index.<Keyword, ACell>none().assoc(Covia.USER_DATA,
			Maps.of(ALICE, Maps.of(Namespace.J, Index.of(DONE, job(DONE, Status.COMPLETE)))));
		assertSame(finished, STEP.apply(finished));
	}
	/** A remote job as version 2 kept it: its record names a secret of the venue's, which holds {@code kept}. */
	private static ACell withRemoteJob(ACell venue, Blob id, AMap<AString, ACell> kept) {
		AString ref = Strings.create("remote-job-" + id.toHexString());
		AMap<AString, ACell> record = job(id, Status.STARTED).assoc(Strings.create("delegation"),
			Maps.of("protocol", "covia", "target", "https://peer.test", "remoteId", "r1", "authRef", ref));
		ACell value = RT.assocIn(venue, record, Covia.USER_DATA, ALICE, Namespace.J, id);
		value = RT.assocIn(value, HOST, Covia.DID);
		return RT.assocIn(value, SecretStore.seal(Strings.create(JSON.print(kept).toString()), KEYS),
			Covia.USER_DATA, HOST, Namespace.S, ref);
	}

	@Test void aRemoteJobsConnectionMovesToItsEntryWithoutItsSecret() {
		Blob venueHop = Blob.fromHex("10");
		Blob callerHop = Blob.fromHex("11");
		Blob a2a = Blob.fromHex("12");
		AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(Strings.create("secretFields"), Vectors.of(TOKEN)));
		ACell value = withRemoteJob(version2(), venueHop, Maps.of("authenticateAs", "venue", "venue", "did:web:peer.test",
			"ucans", Vectors.of("grant.jwt"), "operationMetadata", meta));
		// As 0.10.0 kept them: a caller's sign-in credential, and an A2A send's literal header value
		value = withRemoteJob(value, callerHop, Maps.of("authenticateAs", "caller", "bearerToken", "sign-in.jwt",
			"ucans", Vectors.of("grant.jwt")));
		value = withRemoteJob(value, a2a, Maps.of("location", "header", "name", "Authorization", "value", "Bearer literal"));
		value = RT.assocIn(value, SecretStore.seal(Strings.create("{}"), KEYS), Covia.USER_DATA, HOST, Namespace.S,
			Strings.create("remote-job-ff"));
		value = RT.assocIn(value, SecretStore.seal(Strings.create("kept"), KEYS), Covia.USER_DATA, HOST, Namespace.S,
			Strings.create("OTHER"));

		ACell migrated = STEP.apply(value);
		AMap<AString, ACell> entry = RT.ensureMap(RT.getIn(migrated, Covia.JOBS, venueHop));
		assertEquals(Maps.of("authenticateAs", "venue", "venue", "did:web:peer.test", "ucans", Vectors.of("grant.jwt")),
			entry.get(JobIndex.ADAPTER_DATA), "how to reach the remote job again");
		assertEquals(OutputRules.of(meta).toCell(), entry.get(JobIndex.RULES), "the rules of the definition pinned with it");
		assertEquals(Maps.of("authenticateAs", "caller"), RT.getIn(migrated, Covia.JOBS, callerHop, JobIndex.ADAPTER_DATA),
			"a caller-signed hop keeps its mode, never its sign-in credential or grants");
		assertEquals(Maps.of("location", "header", "name", "Authorization"),
			RT.getIn(migrated, Covia.JOBS, a2a, JobIndex.ADAPTER_DATA), "never a literal credential");
		assertNull(RT.getIn(migrated, Covia.USER_DATA, ALICE, Namespace.J, venueHop, "delegation", "authRef"));
		assertEquals(Strings.create("r1"), RT.getIn(migrated, Covia.USER_DATA, ALICE, Namespace.J, venueHop, "delegation", "remoteId"));
		AMap<AString, ACell> secrets = RT.ensureMap(RT.getIn(migrated, Covia.USER_DATA, HOST, Namespace.S));
		assertEquals(java.util.Set.of(Strings.create("OTHER")), secrets.keySet(),
			"every remote job's secret goes, one no job names included");
		assertSame(migrated, STEP.apply(migrated), "migrating again changes nothing");
	}

	@Test void anExternalAgentsFieldsMoveToItsEntry() {
		AMap<AString, ACell> fields = Maps.of("agentOwner", ALICE, "agentInteraction", "chat",
			"agentBackendJob", Blob.fromHex("20"));
		AMap<AString, ACell> record = job(RUNNING, Status.STARTED).assoc(Fields.AGENT_ID, Strings.create("helper"));
		AMap<AString, ACell> kept = record;
		for (var field : fields.entrySet()) kept = kept.assoc(field.getKey(), field.getValue());
		ACell value = RT.assocIn(version2(), kept, Covia.USER_DATA, ALICE, Namespace.J, RUNNING);
		ACell migrated = STEP.apply(value);
		assertEquals(fields, RT.getIn(migrated, Covia.JOBS, RUNNING, JobIndex.ADAPTER_DATA));
		assertEquals(record, RT.getIn(migrated, Covia.USER_DATA, ALICE, Namespace.J, RUNNING), "the record keeps what its caller sees");
	}

	@Test void aPendingWritesContextMovesFromTheWorkersTaskToTheRequestsEntry() {
		AString bob = Strings.create("did:test:bob");
		ACell proofs = Vectors.of(Maps.of("iss", "did:test:grantor"));
		Blob session = Blob.fromHex("5e");
		Blob other = Blob.fromHex("05");
		AString outputContext = Strings.create("outputContext");
		// As version 2 kept it: the requester's context and the path on the worker's task
		AMap<AString, ACell> pending = Maps.of(Fields.INPUT, "write",
			Fields.OUTPUT_PATH, "w/out",
			outputContext, Maps.of(Fields.CALLER, ALICE, Fields.OWNER, ALICE, Fields.PROOFS, proofs, Fields.SESSION_ID, session));
		AMap<AString, ACell> plain = Maps.of(Fields.INPUT, "read");
		Index<Blob, ACell> tasks = Index.<Blob, ACell>none()
			.assoc(RUNNING, pending)
			.assoc(DONE, pending)
			.assoc(other, plain);
		ACell value = RT.assocIn(version2(), Maps.of(Namespace.G, Maps.of("worker", Maps.of("tasks", tasks))),
			Covia.USER_DATA, bob);

		ACell migrated = STEP.apply(value);
		assertEquals(Maps.of(JobIndex.OWNER, ALICE, JobIndex.CONTEXT, Maps.of(Fields.PROOFS, proofs, Fields.SESSION_ID, session)),
			RT.getIn(migrated, Covia.JOBS, RUNNING), "the request's job keeps its caller's context for the write");
		assertNull(RT.getIn(migrated, Covia.JOBS, DONE), "a finished request has no entry");
		Index<?, ?> kept = (Index<?, ?>) RT.getIn(migrated, Covia.USER_DATA, bob, Namespace.G, "worker", "tasks");
		assertEquals(Maps.of(Fields.INPUT, "write"), kept.get(RUNNING), "the worker's task keeps neither");
		assertEquals(Maps.of(Fields.INPUT, "write"), kept.get(DONE));
		assertSame(plain, kept.get(other));
		assertSame(migrated, STEP.apply(migrated), "migrating again changes nothing");
	}
}
