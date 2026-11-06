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
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.venue.JobIndex;

/** Venue step 2 → 3 as a function of the venue value. */
class JobEntriesTest {
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString GONE = Strings.create("did:test:gone");
	private static final Blob RUNNING = Blob.fromHex("01");
	private static final Blob PAUSED = Blob.fromHex("02");
	private static final Blob DONE = Blob.fromHex("03");
	private static final Blob ERASED = Blob.fromHex("04");
	private static final MigrationStep STEP = new JobEntries();

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
}
