package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeHost;
import covia.exception.ConfigurationException;
import covia.exception.ConflictException;
import covia.lattice.Covia;

/**
 * A lattice application runs the venues mounted in it: each brings its key
 * pair, the application holds its working copy and builds its engine, and
 * unmounting a venue signs its copy and closes its engine.
 */
public class ApplicationMountTest {

	private static final Config DEFAULT = new Config(null);

	private static SignedData<?> record(CoviaApplication application, AccountKey venue) {
		ACell record = RT.getIn(application.cursor().get(), Covia.GRID, Covia.VENUES, venue);
		return (record instanceof SignedData<?> signed) ? signed : null;
	}

	private static void edit(VenueState venue, String name) {
		venue.assets().store(Strings.create("{\"name\":\"" + name + "\"}"), null);
	}

	@Test
	public void eachMountedVenueSignsItsOwnRecord() {
		CoviaApplication application = TestEngine.application();
		AKeyPair a = AKeyPair.generate();
		AKeyPair b = AKeyPair.generate();
		application.mount(a, DEFAULT);
		application.mount(b, DEFAULT);
		edit(application.venue(a.getAccountKey()), "A");
		edit(application.venue(b.getAccountKey()), "B");

		application.sync();
		for (AKeyPair venue : new AKeyPair[] {a, b}) {
			SignedData<?> signed = record(application, venue.getAccountKey());
			assertNotNull(signed, "each mounted venue publishes its own record");
			assertEquals(venue.getAccountKey(), signed.getAccountKey(), "signed by that venue's key");
			assertTrue(signed.checkSignature());
		}
	}

	@Test
	public void aVenueMountsOnceAtATime() {
		CoviaApplication application = TestEngine.application();
		AKeyPair key = AKeyPair.generate();
		Engine engine = application.mount(key, DEFAULT);
		assertSame(engine, application.engine(key.getAccountKey()));

		ConflictException refused = assertThrows(ConflictException.class, () -> application.mount(key, DEFAULT));
		assertEquals(String.format(CoviaApplication.ALREADY_MOUNTED, key.getAccountKey()), refused.getMessage());
		assertSame(engine, application.engine(key.getAccountKey()), "the mounted venue is untouched");
	}

	@Test
	public void unmountingSignsTheVenueAndForgetsIt() {
		CoviaApplication application = TestEngine.application();
		AKeyPair key = AKeyPair.generate();
		AccountKey venueKey = key.getAccountKey();
		application.mount(key, DEFAULT);
		VenueState first = application.venue(venueKey);
		edit(first, "Unsigned until unmounted");
		assertNull(record(application, venueKey), "an edit stays in the venue's copy");

		application.unmount(venueKey);
		SignedData<?> signed = record(application, venueKey);
		assertNotNull(signed, "unmounting signs the venue's copy into its record");
		assertEquals(first.get(), signed.getValue());
		ConfigurationException refused = assertThrows(ConfigurationException.class, () -> application.venue(venueKey));
		assertEquals(String.format(CoviaApplication.NOT_MOUNTED, venueKey), refused.getMessage());
		assertThrows(ConfigurationException.class, () -> application.engine(venueKey));

		application.mount(key, DEFAULT);
		assertNotSame(first, application.venue(venueKey), "a new mount has a new copy");
		assertEquals(signed.getValue(), application.venue(venueKey).get(), "drafted from the record");
	}

	@Test
	public void closingAnEngineUnmountsItsVenueSoItCanRunAgain() throws Exception {
		CoviaApplication application = TestEngine.application();
		AKeyPair key = AKeyPair.generate();
		AString did = Strings.create("did:test:mount-restart");
		Engine first = application.mount(key, DEFAULT).start();
		first.getVenueState().users().ensure(did);
		first.close();
		assertThrows(ConfigurationException.class, () -> application.venue(key.getAccountKey()));

		Engine second = application.mount(key, DEFAULT).start();
		try {
			assertNotSame(first, second);
			assertNotNull(second.getVenueState().users().user(did), "the second run sees the first run's state");
		} finally {
			second.close();
		}
	}

	@Test
	public void closingTheApplicationUnmountsEveryVenueAndLeavesTheHostOpen() throws Exception {
		MemoryStore store = new MemoryStore();
		LatticeHost<Index<convex.core.data.Keyword, ACell>> host = LatticeHost.create(Index.none(), store);
		CoviaApplication application = TestEngine.application(host);
		AKeyPair running = AKeyPair.generate();
		AKeyPair inert = AKeyPair.generate();
		application.mount(running, DEFAULT).start();
		application.mount(inert, DEFAULT);

		application.close();
		assertThrows(ConfigurationException.class, () -> application.venue(running.getAccountKey()));
		assertThrows(ConfigurationException.class, () -> application.venue(inert.getAccountKey()));
		host.sync();
		assertNotNull(store.getRootData(), "the host stays open for whoever opened it");
		host.close();
	}

	@Test
	public void anApplicationWithoutVenuesRunsNone() {
		CoviaApplication application = TestEngine.application();
		AccountKey venueKey = AKeyPair.generate().getAccountKey();
		ConfigurationException refused = assertThrows(ConfigurationException.class, () -> application.engine(venueKey));
		assertEquals(String.format(CoviaApplication.NOT_MOUNTED, venueKey), refused.getMessage());
	}
}
