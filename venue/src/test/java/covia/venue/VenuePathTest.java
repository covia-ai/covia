package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.exception.AuthException;
import covia.lattice.ACoviaComponent;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Position;

/** A path from the venue starts with its owner's DID and reaches that user's data. */
public class VenuePathTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString AGENT = Strings.intern("venue-path-agent");
	private static final CoviaPath NOTES = CoviaPath.parse("notes");

	private VenueState venue;
	private AString did;

	@BeforeEach
	public void setup(TestInfo info) {
		venue = engine.getVenueState();
		did = TestEngine.uniqueDID(info);
	}

	private Position resolve(String path) {
		return venue.resolve(CoviaPath.parse(did + "/" + path));
	}

	@Test
	public void testAPathFromTheVenueReachesItsOwnersData() {
		User user = venue.users().ensure(did);
		user.workspace().write(NOTES, CVMLong.ONE);
		assertEquals(CVMLong.ONE, resolve("w/notes").read());

		assertTrue(resolve("w/notes").entry().write(CVMLong.create(2)));
		assertEquals(CVMLong.create(2), user.workspace().read(NOTES), "the change is in the user's workspace");

		AgentState agent = user.ensureAgent(AGENT, Maps.empty(), null);
		agent.workspace().write(NOTES, CVMLong.ONE);
		assertEquals(CVMLong.ONE, resolve("g/" + AGENT + "/n/notes").read());
	}

	@Test
	public void testADIDWithNoUserReadsAsAbsentAndIsReadOnly() {
		Position position = resolve("w/notes");
		assertNull(position.read());
		assertFalse(position.contains());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, position::entry).getMessage());
		assertNull(venue.users().get(did), "resolving creates no record");
	}

	@Test
	public void testADeletedAccountIsNotRead() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		venue.deleteUser(did, did);

		assertNull(resolve("w/notes").read());
		assertNull(resolve(Covia.K_DELETED.toString()).read(), "the tombstone is not user data");
		assertFalse(resolve(Covia.K_DELETED.toString()).contains());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, resolve("w/notes")::entry).getMessage());
	}

	@Test
	public void testAWalkFromADraftStaysInTheDraft() {
		venue.users().ensure(did);
		venue.transact(draft -> {
			assertFalse(draft.resolve(CoviaPath.parse(did + "/w/notes")).entry().write(CVMLong.ONE), "nothing was there");
			assertNull(resolve("w/notes").read(), "the live venue does not see the draft");
		});
		assertEquals(CVMLong.ONE, resolve("w/notes").read());
	}
}
