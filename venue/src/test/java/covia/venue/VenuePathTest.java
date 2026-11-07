package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.lattice.ACoviaComponent;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;
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

	private static String noSuchPath(Position position) {
		assertNotNull(position.reasonInvalid(), "the position names no place");
		return position.reasonInvalid();
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
	public void testADIDWithNoUserReadsAsAbsentAndTakesNoChange() {
		Position position = resolve("w/notes");
		assertTrue(position.isValid());
		assertNull(position.read());
		assertFalse(position.exists());
		assertEquals(VenueState.UNKNOWN_USER,
			assertThrows(NotFoundException.class, () -> position.entry().write(CVMLong.ONE)).getMessage());
		assertNull(venue.users().get(did), "resolving and the refused change create no record");
	}

	@Test
	public void testADeletedAccountIsNotRead() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		venue.deleteUser(did, did);

		assertNull(resolve("w/notes").read());
		assertFalse(resolve("w/notes").exists());
		Position tombstone = resolve(Covia.K_DELETED.toString());
		assertFalse(tombstone.isValid(), "the tombstone is not user data");
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, "/" + did + "/" + Covia.K_DELETED, "/" + did + "/" + Covia.K_DELETED),
			assertThrows(InvalidInputException.class, tombstone::read).getMessage());

		assertEquals(Users.ACCOUNT_DELETED,
			assertThrows(AuthException.class, () -> resolve("w/notes").entry().write(CVMLong.create(2))).getMessage());
		assertNotNull(venue.users().tombstone(did));
		assertNull(RT.getIn(venue.users().cursor().get(), did, Namespace.W), "nothing is written into the tombstone");
	}

	@Test
	public void testAPathResolvedBeforeAnAccountIsReplacedReachesNoLaterAccount() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		Position notes = resolve("w/notes");
		venue.deleteUser(did, did);
		venue.users().finishDeletion(did);
		assertTrue(venue.users().create(did));

		assertEquals(Users.ACCOUNT_DELETED, assertThrows(AuthException.class, notes::read).getMessage());
		assertEquals(Users.ACCOUNT_DELETED,
			assertThrows(AuthException.class, () -> notes.entry().write(CVMLong.create(2))).getMessage());
		assertNull(venue.users().get(did).workspace().read(NOTES), "the new account is untouched");
	}

	@Test
	public void testTheVenuesBookkeepingIsNoUserData() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		for (String slot : new String[] {"meta", "generation", "deleting", "deleted", "n/notes", "foo"}) {
			Position position = resolve(slot);
			assertFalse(position.isValid(), slot);
			assertEquals(noSuchPath(position), assertThrows(InvalidInputException.class, position::read).getMessage());
			assertEquals(noSuchPath(position), assertThrows(InvalidInputException.class, position::exists).getMessage());
		}
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> record = (AMap<AString, ACell>) resolve("").read();
		assertNotNull(record.get(Namespace.W));
		assertFalse(record.containsKey(Covia.K_META), "the whole record leaves out the venue's bookkeeping");
	}

	@Test
	public void testAFirstSegmentThatIsNotADIDNamesNoUser() {
		Position position = venue.resolve(CoviaPath.parse("not-a-did/w/notes"));
		assertFalse(position.isValid());
		assertEquals(noSuchPath(position), assertThrows(InvalidInputException.class, position::read).getMessage());
	}

	@Test
	public void testAWalkFromADraftStaysInTheDraft() {
		venue.users().ensure(did);
		venue.execute(draft -> {
			assertFalse(draft.resolve(CoviaPath.parse(did + "/w/notes")).entry().write(CVMLong.ONE), "nothing was there");
			assertNull(resolve("w/notes").read(), "the live venue does not see the draft");
		});
		assertEquals(CVMLong.ONE, resolve("w/notes").read());
	}

	@Test
	public void testAPositionSeesAnAccountItsTransactionCreates() {
		venue.execute(draft -> {
			Position notes = draft.resolve(CoviaPath.parse(did + "/w/notes"));
			assertFalse(notes.exists(), "there is no account yet");
			draft.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
			assertTrue(notes.exists(), "the position sees the account the draft created");
			assertEquals(CVMLong.ONE, notes.read());
		});
		assertEquals(CVMLong.ONE, resolve("w/notes").read());
	}
}
