package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.NoSuchPathException;
import covia.api.Fields;
import covia.lattice.Namespace;

/** An agent's sessions and the operations a session has of its own. */
public class SessionsTest {

	private static final Blob SESSION = Blob.fromHex("bb02");
	private static final Blob OTHER_SESSION = Blob.fromHex("bb03");
	private static final AString CALLER = Strings.create("did:key:caller");
	private static final AMap<AString, ACell> LOADS = Maps.of("w/notes", Maps.empty());

	private static final AString AGENT = Strings.intern("sessions-agent");

	private User user;
	private Agent agent;
	private Sessions sessions;
	private Session session;

	@BeforeEach
	public void setup(TestInfo info) {
		user = TestEngine.ENGINE.getVenueState().users().ensure(TestEngine.uniqueDID(info));
		agent = user.ensureAgent(AGENT, Maps.empty(), null);
		sessions = agent.sessions();
		session = sessions.session(SESSION);
	}

	private static ACell queued(long at) {
		return Maps.of(Fields.MESSAGE, "m", Fields.QUEUED, CVMLong.create(at));
	}

	@Test
	public void testCreateMakesASessionOnce() {
		assertTrue(sessions.create(SESSION, CALLER, LOADS));
		AMap<AString, ACell> created = session.getRecord();
		assertEquals(Vectors.of(CALLER), RT.getIn(created, "meta", "parties"), "the caller is its first party");
		assertEquals(LOADS, RT.getIn(created, "frames", 0, "loads"), "the loads seed its root frame");

		assertFalse(sessions.create(SESSION, Strings.create("did:key:other"), null));
		assertEquals(created, session.getRecord(), "a second create changes nothing");
	}

	@Test
	public void testASessionThatDoesNotExistAcceptsNothing() {
		assertNull(session.getRecord());
		assertFalse(session.setWakeTime(5));
		assertFalse(session.appendPending(queued(1)));
		assertNull(session.getRecord(), "nothing may bring it into being");
	}

	@Test
	public void testAWakeTimeIsSetAndZeroClearsIt() {
		sessions.create(SESSION, null, null);
		assertTrue(session.setWakeTime(500));
		assertEquals(CVMLong.create(500), session.getRecord().get(Fields.WAKE_TIME));
		assertTrue(session.setWakeTime(0));
		assertNull(session.getRecord().get(Fields.WAKE_TIME));
	}

	@Test
	public void testRemoveTakesTheSessionAway() {
		sessions.create(SESSION, null, null);
		sessions.create(OTHER_SESSION, null, null);
		sessions.remove(SESSION);
		assertNull(session.getRecord());
		assertNotNull(sessions.session(OTHER_SESSION).getRecord());
		sessions.remove(SESSION);
		assertEquals(1, sessions.all().count(), "removing a missing session changes nothing");
	}

	@Test
	public void testTheOldestWaitingMessageDecidesWhichSessionIsNext() {
		sessions.create(SESSION, null, null);
		sessions.create(OTHER_SESSION, null, null);
		assertFalse(sessions.hasPending());
		assertNull(sessions.oldestWithPending());

		session.appendPending(queued(20));
		sessions.session(OTHER_SESSION).appendPending(queued(10));
		assertTrue(sessions.hasPending());
		assertEquals(OTHER_SESSION, sessions.oldestWithPending());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testAChangeThroughTheSessionsStampsTheAgent() {
		user.cursor().path(Namespace.G, AGENT).updateAndGet(current ->
			((AMap<AString, ACell>) current).assoc(Strings.intern("ts"), CVMLong.ZERO));
		assertEquals(0, agent.getTs());
		sessions.create(SESSION, null, null);
		assertTrue(agent.getTs() > 0);
	}

	@Test
	public void testASessionAddressesItselfAndItsFields() {
		sessions.create(SESSION, null, null);
		AString[] toMeta = {Strings.create(SESSION.toHexString()), Strings.create("meta")};
		assertEquals(session.getRecord().get(Strings.create("meta")), sessions.resolve(toMeta, 0, 2).cursor().get());
		assertEquals(session.getRecord(), sessions.resolve(toMeta, 0, 1).cursor().get());
		assertSame(sessions, sessions.resolve(toMeta, 0, 0));
	}

	@Test
	public void testAnIdThatIsNotHexNamesNothing() {
		AString[] path = {Strings.create("not-hex")};
		assertThrows(NoSuchPathException.class, () -> sessions.resolve(path, 0, 1));
	}
}
