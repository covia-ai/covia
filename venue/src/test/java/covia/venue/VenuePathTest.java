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
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.NoSuchPathException;
import covia.exception.AuthException;
import covia.exception.NotFoundException;
import covia.lattice.ACoviaComponent;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;
import covia.lattice.Entry;
import covia.lattice.Namespace;

/** A path from the venue starts with its owner's DID and reaches that user's data. */
public class VenuePathTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString AGENT = Strings.intern("venue-path-agent");
	private static final Path NOTES =CoviaPath.parse("notes");

	private VenueState venue;
	private AString did;

	@BeforeEach
	public void setup(TestInfo info) {
		venue = engine.getVenueState();
		did = TestEngine.uniqueDID(info);
	}

	private ACoviaComponent<?> resolve(String path) {
		return venue.resolve(CoviaPath.parse(did + "/" + path));
	}

	/** The refusal for a path below the user that names no place. */
	private String noSuchPath(String path) {
		return ACoviaComponent.noSuchPath(assertThrows(NoSuchPathException.class, () -> resolve(path), path));
	}

	@Test
	public void testAPathFromTheVenueReachesItsOwnersData() {
		User user = venue.users().ensure(did);
		user.workspace().write(NOTES, CVMLong.ONE);
		assertEquals(CVMLong.ONE, resolve("w/notes").read());

		assertTrue(Entry.of(resolve("w/notes")).write(CVMLong.create(2)));
		assertEquals(CVMLong.create(2), user.workspace().read(NOTES), "the change is in the user's workspace");

		AgentState agent = user.ensureAgent(AGENT, Maps.empty(), null);
		agent.workspace().write(NOTES, CVMLong.ONE);
		assertEquals(CVMLong.ONE, resolve("g/" + AGENT + "/n/notes").read());
	}

	@Test
	public void testADIDWithNoUserReadsAsAbsentAndTakesNoChange() {
		ACoviaComponent<?> notes = resolve("w/notes");
		assertNull(notes.read());
		assertFalse(notes.exists());
		assertEquals(VenueState.UNKNOWN_USER,
			assertThrows(NotFoundException.class, () -> Entry.of(notes).write(CVMLong.ONE)).getMessage());
		assertNull(venue.users().get(did), "resolving and the refused change create no record");
	}

	@Test
	public void testADeletedAccountIsNotRead() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		venue.deleteUser(did, did);

		assertNull(resolve("w/notes").read());
		assertFalse(resolve("w/notes").exists());
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, "/" + did + "/" + Covia.K_DELETED, "/" + did + "/" + Covia.K_DELETED),
			noSuchPath(Covia.K_DELETED.toString()), "the tombstone is not user data");

		assertEquals(Users.ACCOUNT_DELETED,
			assertThrows(AuthException.class, () -> Entry.of(resolve("w/notes")).write(CVMLong.create(2))).getMessage());
		assertNotNull(venue.users().tombstone(did));
		assertNull(RT.getIn(venue.users().cursor().get(), did, Namespace.W), "nothing is written into the tombstone");
	}

	@Test
	public void testAPathResolvedBeforeAnAccountIsReplacedReachesNoLaterAccount() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		ACoviaComponent<?> notes = resolve("w/notes");
		venue.deleteUser(did, did);
		venue.users().finishDeletion(did);
		assertTrue(venue.users().create(did));

		assertEquals(Users.ACCOUNT_DELETED, assertThrows(AuthException.class, notes::read).getMessage());
		assertEquals(Users.ACCOUNT_DELETED,
			assertThrows(AuthException.class, () -> Entry.of(notes).write(CVMLong.create(2))).getMessage());
		assertNull(venue.users().get(did).workspace().read(NOTES), "the new account is untouched");
	}

	@Test
	public void testTheVenuesBookkeepingIsNoUserData() {
		venue.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
		for (String slot : new String[] {"meta", "generation", "deleting", "deleted", "n/notes", "foo"}) {
			String first = slot.split("/")[0];
			assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, "/" + did + "/" + slot, "/" + did + "/" + first),
				noSuchPath(slot), slot);
		}
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> record = (AMap<AString, ACell>) resolve("").read();
		assertNotNull(record.get(Namespace.W));
		assertFalse(record.containsKey(Covia.K_META), "the whole record leaves out the venue's bookkeeping");
	}

	@Test
	public void testAFirstSegmentThatIsNotADIDNamesNoUser() {
		NoSuchPathException e = assertThrows(NoSuchPathException.class,
			() -> venue.resolve(CoviaPath.parse("not-a-did/w/notes")));
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, "/not-a-did/w/notes", "/not-a-did"),
			ACoviaComponent.noSuchPath(e));
	}

	@Test
	public void testAWalkFromADraftStaysInTheDraft() {
		venue.users().ensure(did);
		venue.execute(draft -> {
			assertFalse(Entry.of(draft.resolve(CoviaPath.parse(did + "/w/notes"))).write(CVMLong.ONE), "nothing was there");
			assertNull(resolve("w/notes").read(), "the live venue does not see the draft");
		});
		assertEquals(CVMLong.ONE, resolve("w/notes").read());
	}

	@Test
	public void testAPositionSeesAnAccountItsTransactionCreates() {
		venue.execute(draft -> {
			ACoviaComponent<?> notes = draft.resolve(CoviaPath.parse(did + "/w/notes"));
			assertFalse(notes.exists(), "there is no account yet");
			draft.users().ensure(did).workspace().write(NOTES, CVMLong.ONE);
			assertTrue(notes.exists(), "the position sees the account the draft created");
			assertEquals(CVMLong.ONE, notes.read());
		});
		assertEquals(CVMLong.ONE, resolve("w/notes").read());
	}
}
