package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.hitl.Hitl;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

/** A user's HITL inbox, on its own and reached by path through the user. */
public class InboxTest {

	private static final CVMLong NOW = CVMLong.create(100);
	private static final AString ID = Strings.create("req-1");

	private static User user() {
		return new User(VenueContext.create(() -> NOW), Cursors.<ACell>root(Maps.empty()),
			Strings.create("did:key:inbox"));
	}

	@Test
	public void testPutReplacesTheRecordAndStampsTheRegion() {
		User user = user();
		Inbox inbox = user.inbox();
		assertEquals(0, inbox.getAll().count());
		assertNull(inbox.get(ID));

		inbox.put(ID, Maps.of(Hitl.STATUS, Hitl.OPEN));
		inbox.put(ID, Maps.of(Hitl.STATUS, Hitl.ANSWERED));
		assertEquals(1, inbox.getAll().count());
		assertEquals(Hitl.ANSWERED, inbox.get(ID).get(Hitl.STATUS));
		assertEquals(NOW, RT.getIn(user.get(), Namespace.H, Fields.UPDATED));
	}

	@Test
	public void testPathThroughTheUserReachesTheInbox() {
		User user = user();
		user.inbox().put(ID, Maps.of(Hitl.STATUS, Hitl.OPEN));

		assertEquals(user.inbox().getAll(), user.locate(CoviaPath.segments(Namespace.H)).read());
		assertEquals(Hitl.OPEN, RT.getIn(user.locate(CoviaPath.segments(Namespace.H, ID)).read(), Hitl.STATUS));
	}
}
