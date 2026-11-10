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

/** The {@link CurrentJobRecords} part of venue step 1 → 2 as a function of the venue value. */
class CurrentJobRecordsTest {
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString BOB = Strings.create("did:test:bob");
	private static final AString PREV = Strings.create("prev");
	private static final Blob DONE = Blob.fromHex("01");
	private static final Blob RUNNING = Blob.fromHex("02");
	private static final Blob CURRENT = Blob.fromHex("03");
	private static final MigrationStep STEP = new CurrentJobRecords();

	private static AMap<AString, ACell> job(Blob id, AString status) {
		return Maps.of(Fields.ID, id, Fields.STATUS, status, Fields.CALLER, ALICE,
			Fields.OUTPUT, Maps.of(PREV, "a payload field of the same name"));
	}

	/** The record with its earlier states chained under {@code prev}, as a venue before 0.10.0 wrote it. */
	private static AMap<AString, ACell> chained(AMap<AString, ACell> job) {
		return job.assoc(PREV, Maps.of(Fields.STATUS, Status.STARTED, PREV, Maps.of(Fields.STATUS, Status.PENDING)));
	}

	private static ACell venue(AMap<AString, ACell> alices, AMap<AString, ACell> bobs) {
		Index<Blob, ACell> alice = Index.<Blob, ACell>none().assoc(DONE, alices).assoc(CURRENT, job(CURRENT, Status.COMPLETE));
		Index<Blob, ACell> bob = Index.<Blob, ACell>none().assoc(RUNNING, bobs);
		return Index.<Keyword, ACell>none()
			.assoc(Covia.USER_DATA, Maps.of(ALICE, Maps.of(Namespace.J, alice), BOB, Maps.of(Namespace.J, bob),
				Strings.create("did:test:no-jobs"), Maps.empty()))
			.assoc(Covia.JOBS, Index.of(RUNNING, Maps.of(JobIndex.OWNER, BOB)));
	}

	@Test void everyJobOfEveryAccountLosesOnlyItsChain() {
		AMap<AString, ACell> done = job(DONE, Status.COMPLETE);
		AMap<AString, ACell> running = job(RUNNING, Status.STARTED);
		ACell migrated = STEP.apply(venue(chained(done), chained(running)));
		assertEquals(venue(done, running), migrated);
		assertEquals(Maps.of(PREV, "a payload field of the same name"),
			RT.getIn(migrated, Covia.USER_DATA, ALICE, Namespace.J, DONE, Fields.OUTPUT));
	}

	@Test void aCurrentVenueComesBackAsTheSameCell() {
		ACell current = venue(job(DONE, Status.COMPLETE), job(RUNNING, Status.STARTED));
		assertSame(current, STEP.apply(current));
		ACell migrated = STEP.apply(venue(chained(job(DONE, Status.COMPLETE)), job(RUNNING, Status.STARTED)));
		assertSame(migrated, STEP.apply(migrated));
		ACell empty = Index.<Keyword, ACell>none();
		assertSame(empty, STEP.apply(empty));
	}
}
