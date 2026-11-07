package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.NoSuchPathException;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.ACoviaComponent;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

/** A user's jobs, on their own and reached by path through the user. */
public class JobsTest {

	private static final CVMLong NOW = CVMLong.create(100);
	private static final Blob ID = Blob.fromHex("61106110611061106110611061106110");
	private static final AString HEX = Strings.create(ID.toHexString());
	private static final AString PREV = Strings.intern("prev");

	private static User user() {
		return user(() -> NOW);
	}

	private static User user(Supplier<CVMLong> clock) {
		return new User(VenueContext.create(clock), Cursors.<ACell>root(Maps.empty()),
			Strings.create("did:key:jobs"));
	}

	@Test
	public void testCreateAdmitsAJobOnce() {
		Jobs jobs = user().jobs();
		assertEquals(0, jobs.count());
		assertNull(jobs.get(ID));
		assertNull(jobs.getRecord(ID));

		JobState job = jobs.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.STARTED));
		assertEquals(Status.STARTED, job.getRecord().get(Fields.STATUS));
		assertNull(jobs.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.FAILED)));
		assertEquals(1, jobs.count());
		assertEquals(Status.STARTED, jobs.getRecord(ID).get(Fields.STATUS));
		assertEquals(job.getRecord(), jobs.get(ID).getRecord());

		assertTrue(jobs.remove(ID));
		assertFalse(jobs.remove(ID));
		assertNull(jobs.get(ID));
		assertNull(job.getRecord(), "a job removed while held reads as null");
		assertNull(job.update(record -> record.assoc(Fields.STATUS, Status.COMPLETE)),
			"an update to a removed job must not recreate it");
		assertEquals(0, jobs.count());
	}

	@Test
	public void testTransactionCommitsAdmissionsRemovalsAndEditsTogether() {
		AtomicLong clock = new AtomicLong(NOW.longValue());
		User user = user(() -> CVMLong.create(clock.get()));
		Jobs jobs = user.jobs();
		Blob other = Blob.fromHex("0ffe");
		jobs.create(other, Maps.of(Fields.ID, other, Fields.STATUS, Status.STARTED));
		ACell before = user.get();
		clock.set(200);

		assertThrows(IllegalStateException.class, () -> jobs.execute(draft -> {
			draft.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.PENDING));
			draft.remove(other);
			throw new IllegalStateException("rollback");
		}));
		assertEquals(before, user.get(), "a failed transaction changes nothing");

		AMap<AString, ACell> message = Maps.of(Fields.MESSAGE_ID, "first");
		jobs.execute(draft -> {
			JobState admitted = draft.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.PENDING));
			assertTrue(admitted.appendHistory(message));
			assertTrue(draft.remove(other));
			assertNull(jobs.get(ID), "the draft is private until it commits");
		});
		assertEquals(Vectors.of(message), jobs.getRecord(ID).get(Fields.HISTORY));
		assertNull(jobs.get(other));
		assertEquals(CVMLong.create(200), RT.getIn(user.get(), Covia.K_META, Fields.UPDATED),
			"the commit stamps the user like any other write");
	}

	@Test
	public void testPathThroughTheUserReadsJobsInTheirCurrentState() {
		User user = user();
		AMap<AString, ACell> output = Maps.of(PREV, "payload field stays");
		AMap<AString, ACell> current = Maps.of(Fields.ID, ID, Fields.STATUS, Status.COMPLETE,
			Fields.OUTPUT, output);
		// A record an older build wrote, its earlier state chained under prev.
		user.cursor().path(Namespace.J).set(Index.<Blob, ACell>none()
			.assoc(ID, current.assoc(PREV, Maps.of(Fields.STATUS, Status.STARTED))));

		assertEquals(current, user.jobs().getAll().get(ID));
		assertEquals(user.jobs().getAll(), ACoviaComponent.read(user.resolve(CoviaPath.segments(Namespace.J))));
		assertEquals(current, ACoviaComponent.read(user.resolve(CoviaPath.segments(Namespace.J, HEX))));
		assertEquals(output, ACoviaComponent.read(user.resolve(CoviaPath.segments(Namespace.J, HEX, Fields.OUTPUT))));
		assertEquals(output.get(PREV), ACoviaComponent.read(user.resolve(CoviaPath.segments(Namespace.J, HEX, Fields.OUTPUT, PREV))));
		assertNull(ACoviaComponent.read(user.resolve(CoviaPath.segments(Namespace.J, HEX, PREV))));
		assertThrows(NoSuchPathException.class,
			() -> user.resolve(CoviaPath.segments(Namespace.J, Strings.create("not-a-job"))),
			"a job id that is not hex names no job");
		assertNull(ACoviaComponent.read(user.resolve(CoviaPath.segments(Namespace.J, Strings.create("0ffe"), Fields.STATUS))),
			"an id that names no job reads as null");
	}
}
