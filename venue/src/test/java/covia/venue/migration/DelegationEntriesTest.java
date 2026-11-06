package covia.venue.migration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;
import covia.venue.RemoteJobs;

/** Venue step 3 → 4 as a function of the venue value. */
class DelegationEntriesTest {
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final Blob RUNNING = Blob.fromHex("01");
	private static final Blob UNKNOWN = Blob.fromHex("02");
	private static final Blob DONE = Blob.fromHex("03");
	private static final Blob LOCAL = Blob.fromHex("04");
	private static final MigrationStep STEP = new DelegationEntries();
	private static final AString TARGET = Strings.create("https://peer.test");
	private static final AMap<AString, ACell> CONNECTION = Maps.of("authenticateAs", "venue",
		Fields.VENUE, "did:test:peer", Fields.UCANS, Vectors.of(Strings.create("grant")));

	private static AMap<AString, ACell> job(Blob id, AString status, AMap<AString, ACell> delegation) {
		AMap<AString, ACell> job = Maps.of(Fields.ID, id, Fields.STATUS, status, Fields.CALLER, ALICE);
		return (delegation == null) ? job : job.assoc(RemoteJobs.DELEGATION, delegation);
	}

	/**
	 * A version 3 venue: a remote job being followed, one whose acceptance was
	 * lost, a finished one, and a local job; the unfinished ones have entries.
	 */
	private static ACell version3() {
		Index<Blob, ACell> jobs = Index.<Blob, ACell>none()
			.assoc(RUNNING, job(RUNNING, Status.STARTED, Maps.of("protocol", "covia", "target", TARGET,
				"ownsRemote", CVMBool.TRUE, "remoteId", "r1", "remoteStatus", Status.STARTED,
				"observation", "retrying", "observationErrorType", "HttpTimeoutException")))
			.assoc(UNKNOWN, job(UNKNOWN, Status.STARTED, Maps.of("protocol", "a2a", "target", TARGET,
				"observation", "acceptance-unknown")))
			.assoc(DONE, job(DONE, Status.COMPLETE, Maps.of("protocol", "covia", "target", TARGET,
				"ownsRemote", CVMBool.TRUE, "remoteId", "r3", "remoteStatus", Status.COMPLETE, "observation", "healthy")))
			.assoc(LOCAL, job(LOCAL, Status.STARTED, null));
		Index<Blob, ACell> entries = Index.<Blob, ACell>none()
			.assoc(RUNNING, Maps.of(JobIndex.OWNER, ALICE, JobIndex.ADAPTER_DATA, CONNECTION))
			.assoc(UNKNOWN, Maps.of(JobIndex.OWNER, ALICE))
			.assoc(LOCAL, Maps.of(JobIndex.OWNER, ALICE));
		return Index.<Keyword, ACell>none()
			.assoc(Covia.USER_DATA, Maps.of(ALICE, Maps.of(Namespace.J, jobs)))
			.assoc(Covia.JOBS, entries);
	}

	private static AMap<AString, ACell> record(ACell venue, Blob id) {
		return RT.ensureMap(RT.getIn(venue, Covia.USER_DATA, ALICE, Namespace.J, id));
	}

	private static AMap<AString, ACell> entry(ACell venue, Blob id) {
		return RT.ensureMap(RT.getIn(venue, Covia.JOBS, id));
	}

	@Test void aRecordKeepsOnlyWhatItsCallerCanFollow() {
		ACell before = version3();
		ACell migrated = STEP.apply(before);
		assertEquals(job(RUNNING, Status.STARTED, Maps.of("remoteId", "r1", "remoteStatus", Status.STARTED)),
			record(migrated, RUNNING));
		assertEquals(job(DONE, Status.COMPLETE, Maps.of("remoteId", "r3", "remoteStatus", Status.COMPLETE)),
			record(migrated, DONE), "a finished job's record too");
		assertEquals(job(UNKNOWN, Status.STARTED, null), record(migrated, UNKNOWN),
			"a record left with nothing to follow shows no delegation");
		assertEquals(record(before, LOCAL), record(migrated, LOCAL));
	}

	@Test void howTheVenueFollowsAnUnfinishedJobJoinsItsEntry() {
		ACell migrated = STEP.apply(version3());
		assertEquals(Maps.of(JobIndex.OWNER, ALICE, JobIndex.ADAPTER_DATA, CONNECTION.assoc(RemoteJobs.PROTOCOL, Strings.create("covia"))
				.assoc(RemoteJobs.TARGET, TARGET).assoc(RemoteJobs.OWNS_REMOTE, CVMBool.TRUE)),
			entry(migrated, RUNNING), "beside its connection; its health is left behind");
		assertEquals(Maps.of(JobIndex.OWNER, ALICE, JobIndex.ADAPTER_DATA, Maps.of(RemoteJobs.PROTOCOL, "a2a",
				RemoteJobs.TARGET, TARGET)), entry(migrated, UNKNOWN));
		assertEquals(Maps.of(JobIndex.OWNER, ALICE), entry(migrated, LOCAL));
		assertNull(entry(migrated, DONE), "a finished job has no entry");
	}

	@Test void aCurrentVenueComesBackAsTheSameCell() {
		ACell migrated = STEP.apply(version3());
		assertSame(migrated, STEP.apply(migrated));
		ACell empty = Index.<Keyword, ACell>none();
		assertSame(empty, STEP.apply(empty));
	}
}
