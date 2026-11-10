package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.grid.hitl.Hitl;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

/** A user's HITL inbox and its requests, on their own and reached by path through the user. */
public class InboxTest {

	private static final CVMLong NOW = CVMLong.create(100);
	private static final AString ID = Strings.create("0a0b");
	private static final AString CALLER = Strings.create("did:key:asker");

	private static User user() {
		return new User(VenueContext.create(() -> NOW), Cursors.<ACell>root(Maps.empty()),
			Strings.create("did:key:inbox"), null);
	}

	private static AMap<AString, ACell> request(Long expires) {
		return HitlRequest.record(ID, CALLER, null, null, null, Strings.create("Approve?"), null,
			Vectors.empty(), 50, expires);
	}

	private static ACell read(ALatticeComponent<?> component) {
		return component.cursor().getOrDefault(null);
	}

	@Test
	public void testOpenAddsARequestOnceAndStampsTheRegion() {
		User user = user();
		Inbox inbox = user.inbox();
		assertEquals(0, inbox.getAll().count());
		assertNull(inbox.request(ID).getRecord());

		assertTrue(inbox.open(request(null)));
		HitlRequest request = inbox.request(ID);
		assertTrue(request.isOpen());
		assertEquals(CALLER, request.getRecord().get(Hitl.CALLER));
		assertEquals(NOW, RT.getIn(user.get(), Namespace.H, Fields.UPDATED));

		assertTrue(request.resolve(Hitl.REJECTED, null));
		assertFalse(inbox.open(request(null)), "an id already in the inbox is never opened again");
		assertEquals(Hitl.REJECTED, inbox.request(ID).status());
	}

	@Test
	public void testOpenRefusesARecordThatIsNotANewRequest() {
		Inbox inbox = user().inbox();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> inbox.open(request(null).assoc(Hitl.STATUS, Hitl.ANSWERED)));
		assertTrue(e.getMessage().startsWith(Inbox.NOT_A_NEW_REQUEST), e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> inbox.open(request(null).dissoc(Hitl.ID)));
		assertEquals(0, inbox.getAll().count());
	}

	@Test
	public void testARequestResolvesOnce() {
		Inbox inbox = user().inbox();
		inbox.open(request(null));
		HitlRequest request = inbox.request(ID);
		AMap<AString, ACell> response = Maps.of(Hitl.OUTCOME, Hitl.ANSWER);

		assertTrue(request.resolve(Hitl.ANSWERED, response));
		assertFalse(request.resolve(Hitl.EXPIRED, null), "a resolved request stays as it was resolved");
		assertEquals(Hitl.ANSWERED, request.status());
		assertEquals(response, request.getRecord().get(Hitl.RESPONSE));
		assertFalse(request.isOpen());
	}

	@Test
	public void testResolveRefusesAStatusThatIsNotAResolution() {
		Inbox inbox = user().inbox();
		inbox.open(request(null));
		for (AString status : new AString[] {Hitl.OPEN, Strings.create("pending")}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> inbox.request(ID).resolve(status, null));
			assertTrue(e.getMessage().startsWith(HitlRequest.NOT_A_RESOLUTION), e.getMessage());
		}
		assertTrue(inbox.request(ID).isOpen());
	}

	@Test
	public void testAMissingRequestIsNotResolved() {
		HitlRequest missing = user().inbox().request(ID);
		assertNull(missing.status());
		assertFalse(missing.resolve(Hitl.CANCELLED, null));
		assertNull(missing.getRecord(), "resolving creates nothing");
	}

	@Test
	public void testExpiry() {
		Inbox inbox = user().inbox();
		inbox.open(request(200L));
		HitlRequest request = inbox.request(ID);
		assertFalse(request.isExpired(200));
		assertTrue(request.isExpired(201));

		Inbox other = user().inbox();
		other.open(request(null));
		assertFalse(other.request(ID).isExpired(Long.MAX_VALUE), "a request without an expiry never expires");
	}

	@Test
	public void testPathThroughTheUserReachesTheInbox() {
		User user = user();
		user.inbox().open(request(null));

		assertEquals(user.inbox().getAll(), read(user.resolve(CoviaPath.segments(Namespace.H))));
		assertEquals(Hitl.OPEN, RT.getIn(read(user.resolve(CoviaPath.segments(Namespace.H, ID))), Hitl.STATUS));
	}
}
