package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import convex.lattice.ALatticeComponent;
import convex.lattice.NoSuchPathException;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

/** A user's jobs, on their own and reached by path through the user. */
public class JobsTest {

	private static final CVMLong NOW = CVMLong.create(100);
	private static final Blob ID = Blob.fromHex("61106110611061106110611061106110");
	private static final AString HEX = Strings.create(ID.toHexString());

	private static User user() {
		return user(() -> NOW);
	}

	private static User user(Supplier<CVMLong> clock) {
		return new User(VenueContext.create(clock), Cursors.<ACell>root(Maps.empty()),
			Strings.create("did:key:jobs"));
	}

	private static ACell read(ALatticeComponent<?> component) {
		return component.cursor().getOrDefault(null);
	}

	@Test
	public void testCreateAdmitsAJobOnce() {
		Jobs jobs = user().jobs();
		assertEquals(0, jobs.size());
		assertNull(jobs.job(ID));
		assertNull(jobs.getRecord(ID));

		JobState job = jobs.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.STARTED));
		assertEquals(Status.STARTED, job.getRecord().get(Fields.STATUS));
		assertNull(jobs.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.FAILED)));
		assertEquals(1, jobs.size());
		assertEquals(Status.STARTED, jobs.getRecord(ID).get(Fields.STATUS));
		assertEquals(job.getRecord(), jobs.job(ID).getRecord());

		assertNotNull(jobs.remove(ID));
		assertNull(jobs.remove(ID));
		assertNull(jobs.job(ID));
		assertNull(job.getRecord(), "a job removed while held reads as null");
		assertNull(job.update(record -> record.assoc(Fields.STATUS, Status.COMPLETE)),
			"an update to a removed job must not recreate it");
		assertEquals(0, jobs.size());
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
			assertNotNull(draft.remove(other));
			assertNull(jobs.job(ID), "the draft is private until it commits");
		});
		assertEquals(Vectors.of(message), jobs.getRecord(ID).get(Fields.HISTORY));
		assertNull(jobs.job(other));
		assertEquals(CVMLong.create(200), RT.getIn(user.get(), Covia.K_META, Fields.UPDATED),
			"the commit stamps the user like any other write");
	}

	@Test
	public void testPathThroughTheUserReadsJobRecords() {
		User user = user();
		AString answer = Strings.create("answer");
		AMap<AString, ACell> output = Maps.of(answer, 42L);
		AMap<AString, ACell> record = Maps.of(Fields.ID, ID, Fields.STATUS, Status.COMPLETE,
			Fields.OUTPUT, output);
		user.cursor().path(Namespace.J).set(Index.<Blob, ACell>none().assoc(ID, record));

		assertEquals(record, user.jobs().getAll().get(ID));
		assertEquals(user.jobs().getAll(), read(user.resolve(CoviaPath.segments(Namespace.J))));
		assertEquals(record, read(user.resolve(CoviaPath.segments(Namespace.J, HEX))));
		assertEquals(output, read(user.resolve(CoviaPath.segments(Namespace.J, HEX, Fields.OUTPUT))));
		assertEquals(CVMLong.create(42), read(user.resolve(CoviaPath.segments(Namespace.J, HEX, Fields.OUTPUT, answer))));
		assertNull(read(user.resolve(CoviaPath.segments(Namespace.J, HEX, Fields.ERROR))));
		assertThrows(NoSuchPathException.class,
			() -> user.resolve(CoviaPath.segments(Namespace.J, Strings.create("not-a-job"))),
			"a job id that is not hex names no job");
		assertNull(read(user.resolve(CoviaPath.segments(Namespace.J, Strings.create("0ffe"), Fields.STATUS))),
			"an id that names no job reads as null");
	}
}
