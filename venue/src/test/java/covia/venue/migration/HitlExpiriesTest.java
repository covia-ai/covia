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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.HITLAdapter;
import covia.api.Fields;
import covia.grid.hitl.Hitl;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/** The {@link HitlExpiries} part of venue step 1 → 2 as a function of the venue value. */
class HitlExpiriesTest {
	private static final AString VENUE = Strings.create("did:test:venue");
	private static final AString ALICE = Strings.create("did:test:alice");
	private static final AString TIMED = Strings.create("0101010101010101a1a1a1a1a1a1a1a1");
	private static final AString UNTIMED = Strings.create("0202020202020202b2b2b2b2b2b2b2b2");
	private static final AString ANSWERED = Strings.create("0303030303030303c3c3c3c3c3c3c3c3");
	private static final Blob OTHER_EVENT = Blob.fromHex("00000000000000ff0000000000000001");
	private static final MigrationStep STEP = new HitlExpiries();

	private static AMap<AString, ACell> request(AString id, AString status, Long expires) {
		AMap<AString, ACell> request = Maps.of(Hitl.ID, id, Fields.CALLER, ALICE, Hitl.STATUS, status,
			Hitl.CREATED, CVMLong.create(100));
		return (expires == null) ? request : request.assoc(Hitl.EXPIRES, CVMLong.create(expires));
	}

	private static ACell venue(ACell did, Index<Blob, ACell> events) {
		AMap<AString, ACell> inbox = Maps.of(TIMED, request(TIMED, Hitl.OPEN, 5_000L),
			UNTIMED, request(UNTIMED, Hitl.OPEN, null),
			ANSWERED, request(ANSWERED, Hitl.ANSWERED, 5_000L));
		Index<Keyword, ACell> venue = Index.<Keyword, ACell>none()
			.assoc(Covia.USER_DATA, Maps.of(ALICE, Maps.of(Namespace.H, Maps.of(Covia.WORKSPACE_DATA, inbox))))
			.assoc(Covia.SCHEDULE, Maps.of(HitlExpiries.EVENTS, events));
		return (did == null) ? venue : venue.assoc(Covia.DID, did);
	}

	private static Index<Blob, ACell> others() {
		return Index.of(OTHER_EVENT, Maps.of(HitlExpiries.OP, "v/test/ops/echo", Fields.CALLER, ALICE,
			HitlExpiries.TIME, CVMLong.create(255)));
	}

	@Test void anOpenTimedRequestGetsTheExpiryTheVenueWouldSchedule() {
		ACell migrated = STEP.apply(venue(VENUE, others()));
		Index<Blob, ACell> events = events(migrated);
		assertEquals(2, events.count(), "one expiry beside the event already there: " + events);
		Blob key = HitlExpiries.key(5_000, Blob.parse(TIMED.toString()));
		assertEquals("0000000000001388a1a1a1a1a1a1a1a1", key.toHexString(),
			"keyed by its time, then the end of the request's ID");
		ACell expiry = events.get(key);
		assertEquals(HITLAdapter.EXPIRE_OP, RT.getIn(expiry, HitlExpiries.OP));
		assertEquals(CVMLong.create(5_000), RT.getIn(expiry, HitlExpiries.TIME));
		assertEquals(VENUE, RT.getIn(expiry, Fields.CALLER));
		assertNull(RT.getIn(expiry, Fields.OWNER), "the venue's own event");
		assertEquals(HITLAdapter.expiryInput(ALICE, TIMED), RT.getIn(expiry, HitlExpiries.INPUT));
		assertEquals(CVMLong.create(100), RT.getIn(expiry, Fields.CREATED), "made when the request was");
		assertEquals(others().get(OTHER_EVENT), events.get(OTHER_EVENT));
	}

	@Test void convertingAgainChangesNothing() {
		ACell migrated = STEP.apply(venue(VENUE, others()));
		assertSame(migrated, STEP.apply(migrated));
	}

	@Test void aVenueWithNoTimedOpenRequestComesBackAsTheSameCell() {
		ACell venue = Index.<Keyword, ACell>none().assoc(Covia.DID, VENUE)
			.assoc(Covia.USER_DATA, Maps.of(ALICE, Maps.of(Namespace.H, Maps.of(Covia.WORKSPACE_DATA,
				Maps.of(UNTIMED, request(UNTIMED, Hitl.OPEN, null), ANSWERED, request(ANSWERED, Hitl.ANSWERED, 5_000L))))));
		assertSame(venue, STEP.apply(venue));
	}

	@Test void aVenueWithNoScheduleGetsOne() {
		@SuppressWarnings("unchecked")
		Index<Keyword, ACell> venue = (Index<Keyword, ACell>) venue(VENUE, others());
		ACell migrated = STEP.apply(venue.dissoc(Covia.SCHEDULE));
		assertEquals(1, events(migrated).count());
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> events(ACell venue) {
		return (Index<Blob, ACell>) RT.getIn(venue, Covia.SCHEDULE, HitlExpiries.EVENTS);
	}

	@Test void aTimedRequestWithNoVenueDidIsRefused() {
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> STEP.apply(venue(null, others())));
		assertEquals(HitlExpiries.NO_VENUE_DID, refused.getMessage());
	}
}
