package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.Status;
import covia.lattice.Namespace;

/** A user's jobs, on their own and reached by path through the user. */
public class JobsTest {

	private static final CVMLong NOW = CVMLong.create(100);
	private static final Blob ID = Blob.fromHex("61106110611061106110611061106110");
	private static final AString HEX = Strings.create(ID.toHexString());
	private static final AString PREV = Strings.intern("prev");

	private static User user() {
		return new User(VenueContext.create(() -> NOW), Cursors.<ACell>root(Maps.empty()),
			Strings.create("did:key:jobs"));
	}

	@Test
	public void testCreateAdmitsAJobOnce() {
		Jobs jobs = user().jobs();
		assertEquals(0, jobs.count());
		assertNull(jobs.get(ID).getRecord());

		assertTrue(jobs.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.STARTED)));
		assertFalse(jobs.create(ID, Maps.of(Fields.ID, ID, Fields.STATUS, Status.FAILED)));
		assertEquals(1, jobs.count());
		assertEquals(Status.STARTED, jobs.get(ID).getRecord().get(Fields.STATUS));

		assertTrue(jobs.remove(ID));
		assertFalse(jobs.remove(ID));
		assertNull(jobs.get(ID).getRecord());
		assertNull(jobs.get(ID).update(job -> job.assoc(Fields.STATUS, Status.COMPLETE)),
			"an update to a removed job must not recreate it");
		assertEquals(0, jobs.count());
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
		assertEquals(user.jobs().getAll(), user.resolve(new ACell[] {Namespace.J}, 0).read());
		assertEquals(current, user.resolve(new ACell[] {Namespace.J, HEX}, 0).read());
		assertEquals(current, user.resolve(new ACell[] {Namespace.J, ID}, 0).read());
		assertEquals(output, user.resolve(new ACell[] {Namespace.J, HEX, Fields.OUTPUT}, 0).read());
		assertEquals(output.get(PREV), user.resolve(new ACell[] {Namespace.J, HEX, Fields.OUTPUT, PREV}, 0).read());
		assertNull(user.resolve(new ACell[] {Namespace.J, HEX, PREV}, 0).read());
		assertNull(user.resolve(new ACell[] {Namespace.J, Strings.create("not-a-job")}, 0).read());
	}
}
