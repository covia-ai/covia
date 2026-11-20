package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Path;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSRegion;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;
import convex.lattice.Regions;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Root;
import convex.lattice.fs.DLFSNode;
import convex.lattice.fs.DLFileSystem;
import convex.lattice.fs.DLPath;
import covia.exception.ConfigurationException;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;

/**
 * Transactions over the whole application ({@link CoviaApplication#transact}):
 * a venue's change and a user's drive change reach the host root in one update
 * or not at all, and the venue's working copy stays its record's only writer.
 *
 * <p>Isolated because a venue that finds another writer reports it at ERROR,
 * which these tests capture on the application's logger.</p>
 */
@Isolated
class ApplicationTransactionTest {
	private static final AKeyPair VENUE = AKeyPair.createSeeded(65911);
	private static final AKeyPair OTHER_VENUE = AKeyPair.createSeeded(65913);
	private static final AKeyPair USER_KEY = AKeyPair.createSeeded(65912);
	private static final AString USER = TestEngine.uniqueDID("application-transaction");
	private static final Path PENDING = CoviaPath.parse("pending");
	private static final Path RECORD = CoviaPath.parse("record");
	private static final Path MEANWHILE = CoviaPath.parse("meanwhile");
	private static final String DRIVE = "home";

	private LatticeHost<Index<Keyword, ACell>> host;
	/** Stepped between steps, so no two edits or syncs share a timestamp. */
	private DrivenClock clock;
	private CoviaApplication application;
	private VenueState copy;
	private Logger applicationLog;
	private ListAppender<ILoggingEvent> errors;
	private Level previousLevel;

	@BeforeEach
	void setUp() {
		host = LatticeHost.create(new MemoryStore());
		clock = DrivenClock.at(Instant.now());
		application = TestEngine.application(host, clock);
		application.mount(VENUE, new Config(null));
		copy = application.venue(VENUE.getAccountKey());
		copy.users().ensure(USER);
		application.publish();

		applicationLog = (Logger) LoggerFactory.getLogger(CoviaApplication.class);
		errors = new ListAppender<>();
		errors.list = new CopyOnWriteArrayList<>();
		errors.start();
		previousLevel = applicationLog.getLevel();
		applicationLog.addAppender(errors);
		applicationLog.setAdditive(false);
		applicationLog.setLevel(Level.ERROR);
	}

	@AfterEach
	void tearDown() {
		applicationLog.setLevel(previousLevel);
		applicationLog.setAdditive(true);
		applicationLog.detachAppender(errors);
		application.close();
	}

	private void tick() {
		clock.advance(1000);
	}

	private static void write(DLFSDrive drive, String path, String text) {
		try {
			Files.writeString(drive.fileSystem().getPath(path), text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void writeFile(DLFSDrives drives, String path, String text) {
		try (DLFSDrive drive = drives.getOrCreateDrive(DRIVE)) {
			write(drive, path, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The user's drives in a root value, for reading. */
	private DLFSDrives drives(Index<Keyword, ACell> root) {
		DLFSRegion region = new Regions(new Root<>(root)).create(Covia.DLFS,
			(AForkableCursor<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> c)
				-> DLFSRegion.connect(c, null, null, clock));
		return DLFSRegion.connect(region.cursor(), null, USER_KEY::signData, clock).drives(USER_KEY.getAccountKey());
	}

	/** A file's text in a root value, or null if the drive or file is absent. */
	private String file(Index<Keyword, ACell> root, String path) throws IOException {
		DLFSDrive drive = drives(root).drive(DRIVE);
		if (drive == null) return null;
		try (drive) {
			var file = drive.fileSystem().getPath(path);
			return Files.exists(file) ? Files.readString(file) : null;
		}
	}

	/** A venue's view of a root value. */
	private VenueState venueIn(Index<Keyword, ACell> root, AKeyPair venue) {
		return VenueState.fromRoot(new Root<>(root), venue.getAccountKey(), venue::signData, copy.getContext());
	}

	/** A user's workspace value at a venue in a root value. */
	private ACell value(Index<Keyword, ACell> root, AKeyPair venue, Path path) {
		return venueIn(root, venue).users().user(USER).workspace().read(path);
	}

	private ACell value(Index<Keyword, ACell> root, Path path) {
		return value(root, VENUE, path);
	}

	private ACell copyValue(Path path) {
		return copy.users().user(USER).workspace().read(path);
	}

	private Index<Keyword, ACell> stored() throws IOException {
		return host.store().getRootData();
	}

	/** Waits until a thread is parked, as one waiting for a claim is, or has finished. */
	private static void awaitParked(Thread thread) {
		while (thread.isAlive() && thread.getState() != Thread.State.WAITING) Thread.onSpinWait();
	}

	private void assertNoOtherWriterReported() {
		List<ILoggingEvent> reported = List.copyOf(errors.list);
		assertTrue(reported.isEmpty(), "no other writer reported: " + reported);
		assertFalse(application.engine(VENUE.getAccountKey()).isClosing(), "the venue is still running");
	}

	/** Writes the user's workspace value in the draft's venue. */
	private static void record(CoviaApplication.Draft draft, Path path, String value) {
		draft.venue(VENUE.getAccountKey()).users().user(USER).workspace().write(path, Strings.create(value));
	}

	@Test
	void aVenueChangeAndADriveChangeCommitInOneUpdate() throws IOException {
		copy.users().user(USER).workspace().write(PENDING, Strings.create("pending"));
		tick();
		AtomicReference<Index<Keyword, ACell>> before = new AtomicReference<>();

		String result = application.transact(draft -> {
			before.set(host.cursor().get());
			record(draft, RECORD, "record");
			tick();
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
			// A save meanwhile holds neither
			host.sync();
			try {
				assertNull(value(stored(), RECORD), "a save during the transaction has no venue change");
				assertNull(file(stored(), "/a.txt"), "nor the file");
				assertEquals(Strings.create("pending"), value(stored(), PENDING), "the copy's earlier edit was synced first");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return "done";
		});

		assertEquals("done", result);
		Index<Keyword, ACell> committed = host.cursor().get();
		assertTrue(Covia.venueTimestamp(venueIn(committed, VENUE).get()) > Covia.venueTimestamp(venueIn(before.get(), VENUE).get()),
			"the transaction stamped the record later");
		assertEquals(Strings.create("record"), value(committed, RECORD), "the venue change is in the root");
		assertEquals("file", file(committed, "/a.txt"), "and so is the file");
		assertEquals(Strings.create("record"), copyValue(RECORD), "the copy took the committed record");
		assertEquals(Strings.create("pending"), copyValue(PENDING), "and kept its earlier edit");

		tick();
		application.publish();
		assertEquals(Strings.create("record"), value(stored(), RECORD));
		assertEquals("file", file(stored(), "/a.txt"));
		assertTrue(application.venues().get(VENUE.getAccountKey()).checkSignature(VENUE.getAccountKey()),
			"the venue's record is signed by its key");
		assertTrue(application.dlfs().get(USER_KEY.getAccountKey()).checkSignature(USER_KEY.getAccountKey()),
			"the user's drives by the user's key");
		assertNoOtherWriterReported();
	}

	@Test
	void nothingLandsBeforeTheCommit() {
		application.execute(draft -> {
			Index<Keyword, ACell> before = host.cursor().get();
			record(draft, RECORD, "record");
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
			draft.venue(VENUE.getAccountKey()).sync();
			assertEquals(before, host.cursor().get(), "the root is untouched until the transaction commits");
		});
		assertEquals(Strings.create("record"), value(host.cursor().get(), RECORD));
		assertNoOtherWriterReported();
	}

	@Test
	void aFailedTransactionAppliesNothing() throws IOException {
		copy.users().user(USER).workspace().write(PENDING, Strings.create("pending"));
		tick();
		AtomicReference<Index<Keyword, ACell>> before = new AtomicReference<>();

		assertThrows(ArithmeticException.class, () -> application.execute(draft -> {
			before.set(host.cursor().get());
			record(draft, RECORD, "record");
			tick();
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
			throw new ArithmeticException();
		}));

		assertEquals(before.get(), host.cursor().get(), "the root holds only the copy's earlier sync");
		assertNull(file(host.cursor().get(), "/a.txt"));
		assertNull(copyValue(RECORD), "the copy has no part of it");
		assertEquals(Strings.create("pending"), copyValue(PENDING));

		tick();
		application.publish();
		assertNull(value(stored(), RECORD));
		assertNoOtherWriterReported();
	}

	@Test
	void aVenueWriteMadeMeanwhileWaitsAndSurvives() throws Exception {
		Thread[] writer = new Thread[1];
		application.execute(draft -> {
			writer[0] = Thread.ofPlatform().start(() ->
				copy.users().user(USER).workspace().write(MEANWHILE, Strings.create("meanwhile")));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive(), "a write to the copy waits for its claim");
			record(draft, RECORD, "record");
			tick();
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
		});
		writer[0].join();

		assertEquals(Strings.create("meanwhile"), copyValue(MEANWHILE), "the write landed on the copy");
		assertEquals(Strings.create("record"), copyValue(RECORD), "on top of the committed record");
		tick();
		application.publish();
		assertEquals(Strings.create("meanwhile"), value(stored(), MEANWHILE));
		assertEquals(Strings.create("record"), value(stored(), RECORD));
		assertEquals("file", file(stored(), "/a.txt"));
		assertNoOtherWriterReported();
	}

	@Test
	void aLiveWriteToTheSameDriveWaitsAndBothSurvive() throws Exception {
		Engine engine = application.engine(VENUE.getAccountKey());
		Thread[] writer = new Thread[1];
		application.execute(draft -> {
			writer[0] = Thread.ofPlatform().start(() -> writeFile(engine.getDLFSDrives(USER_KEY), "/b.txt", "live"));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive(), "a live drive write waits for the root's claim");
			record(draft, RECORD, "record");
			tick();
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
		});
		writer[0].join();

		Index<Keyword, ACell> root = host.cursor().get();
		assertEquals("file", file(root, "/a.txt"), "the transaction's file");
		assertEquals("live", file(root, "/b.txt"), "and the live write, on top of it");
		assertEquals(Strings.create("record"), value(root, RECORD));
		assertNoOtherWriterReported();
	}

	@Test
	void aPublishWaitsForARunningTransaction() throws Exception {
		Thread[] publisher = new Thread[1];
		application.execute(draft -> {
			publisher[0] = Thread.ofPlatform().start(application::publish);
			awaitParked(publisher[0]);
			assertTrue(publisher[0].isAlive(), "the publish waits for the venue's claim");
			record(draft, RECORD, "record");
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
		});
		publisher[0].join();
		assertEquals(Strings.create("record"), value(stored(), RECORD), "the publish then saved the commit");
		assertEquals("file", file(stored(), "/a.txt"));
		assertNoOtherWriterReported();
	}

	@Test
	void metadataAMoveAndADeleteCommitWithTheVenueChange() throws Exception {
		writeFile(application.engine(VENUE.getAccountKey()).getDLFSDrives(USER_KEY), "/a.txt", "first");
		tick();
		application.execute(draft -> {
			try (DLFSDrive drive = draft.drives(USER_KEY).getOrCreateDrive(DRIVE)) {
				DLFileSystem fs = drive.fileSystem();
				DLPath at = fs.getPath("/a.txt");
				fs.updateNode(at, DLFSNode.withMetadata(fs.getNode(at), Strings.create("meta"),
					application.venueContext().currentTimestamp()));
				Files.move(at, fs.getPath("/b.txt"));
				write(drive, "/c.txt", "gone");
				Files.delete(fs.getPath("/c.txt"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			record(draft, RECORD, "record");
		});

		Index<Keyword, ACell> root = host.cursor().get();
		assertNull(file(root, "/a.txt"), "moved away");
		assertEquals("first", file(root, "/b.txt"));
		assertNull(file(root, "/c.txt"), "created and deleted");
		try (DLFSDrive drive = drives(root).drive(DRIVE)) {
			DLFileSystem fs = drive.fileSystem();
			assertEquals(Strings.create("meta"), DLFSNode.getMetadata(fs.getNode(fs.getPath("/b.txt"))),
				"the metadata moved with the file");
		}
		assertEquals(Strings.create("record"), value(root, RECORD));
		assertNoOtherWriterReported();
	}

	@Test
	void everyMountedVenueTakesPart() throws IOException {
		application.mount(OTHER_VENUE, new Config(null));
		VenueState other = application.venue(OTHER_VENUE.getAccountKey());
		other.users().ensure(USER);
		application.publish();
		tick();

		application.execute(draft -> {
			record(draft, RECORD, "first");
			draft.venue(OTHER_VENUE.getAccountKey()).users().user(USER).workspace().write(RECORD, Strings.create("second"));
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
		});

		Index<Keyword, ACell> root = host.cursor().get();
		assertEquals(Strings.create("first"), value(root, VENUE, RECORD));
		assertEquals(Strings.create("second"), value(root, OTHER_VENUE, RECORD));
		assertEquals("file", file(root, "/a.txt"));
		assertEquals(Strings.create("second"), other.users().user(USER).workspace().read(RECORD), "each copy took its record");
		assertTrue(application.venues().get(OTHER_VENUE.getAccountKey()).checkSignature(OTHER_VENUE.getAccountKey()));
		assertNoOtherWriterReported();
	}

	@Test
	void aMountedVenueWithNoStateYetTakesPart() {
		application.mount(OTHER_VENUE, new Config(null));
		application.execute(draft -> record(draft, RECORD, "record"));
		assertEquals(Strings.create("record"), value(host.cursor().get(), RECORD));
		assertNoOtherWriterReported();
	}

	@Test
	void aVenueThatIsNotMountedIsRefused() {
		AKeyPair absent = AKeyPair.createSeeded(65914);
		var refusal = assertThrows(ConfigurationException.class,
			() -> application.execute(draft -> draft.venue(absent.getAccountKey())));
		assertEquals(String.format(CoviaApplication.NOT_MOUNTED, absent.getAccountKey()), refusal.getMessage());
	}

	@Test
	void aTransactionInsideACallbackIsRefused() throws IOException {
		application.execute(draft -> {
			var refusal = assertThrows(IllegalStateException.class,
				() -> application.execute(inner -> record(inner, MEANWHILE, "inner")));
			assertEquals(CoviaApplication.NESTED_TRANSACTION, refusal.getMessage());
			record(draft, RECORD, "record");
		});
		assertNull(value(host.cursor().get(), MEANWHILE), "the inner transaction did nothing");
		assertEquals(Strings.create("record"), value(host.cursor().get(), RECORD), "the outer one committed");
		assertNoOtherWriterReported();
	}

	@Test
	void aCallbackChangingTheVenueOutsideTheDraftIsRefused() throws IOException {
		var refusal = assertThrows(IllegalStateException.class, () -> application.execute(draft -> {
			record(draft, RECORD, "record");
			writeFile(draft.drives(USER_KEY), "/a.txt", "file");
			copy.users().user(USER).workspace().write(MEANWHILE, Strings.create("live"));
		}));
		assertEquals(String.format(CoviaApplication.CHANGED_OUTSIDE_DRAFT, VENUE.getAccountKey()), refusal.getMessage());
		assertNull(value(host.cursor().get(), RECORD), "nothing from the draft applied");
		assertNull(file(host.cursor().get(), "/a.txt"));

		tick();
		application.publish();
		assertEquals(Strings.create("live"), value(stored(), MEANWHILE), "the live change publishes as any other");
		assertNoOtherWriterReported();
	}

	@Test
	void aDraftCannotBeUsedAfterItsTransaction() {
		AtomicReference<CoviaApplication.Draft> kept = new AtomicReference<>();
		application.execute(kept::set);
		var refusal = assertThrows(IllegalStateException.class, () -> kept.get().venue(VENUE.getAccountKey()));
		assertEquals(CoviaApplication.DRAFT_FINISHED, refusal.getMessage());
		assertThrows(IllegalStateException.class, () -> kept.get().drives(USER_KEY));
	}

	@Test
	void aCommitIsSavedOnlyByTheHostsSync() throws Exception {
		File file = TestTemp.etchFile("application-transaction");
		try (EtchStore store = EtchStore.create(file)) {
			CoviaApplication stored = TestEngine.application(store, VENUE);
			stored.venue(VENUE.getAccountKey()).users().ensure(USER);
			stored.publish();
			stored.host().flush();
			stored.execute(draft -> {
				draft.venue(VENUE.getAccountKey()).users().user(USER).workspace().write(RECORD, Strings.create("record"));
				writeFile(draft.drives(USER_KEY), "/a.txt", "file");
			});
			// The process stops here: nothing has synced the host since the commit
		}
		try (EtchStore store = EtchStore.create(file)) {
			Index<Keyword, ACell> root = store.getRootData();
			assertNull(value(root, RECORD), "an unsynced commit is not in the store");
			assertNull(file(root, "/a.txt"));

			CoviaApplication reopened = TestEngine.application(store, VENUE);
			reopened.execute(draft -> {
				draft.venue(VENUE.getAccountKey()).users().user(USER).workspace().write(RECORD, Strings.create("record"));
				writeFile(draft.drives(USER_KEY), "/a.txt", "file");
			});
			reopened.publish();
			reopened.host().flush();
			reopened.close();
		}
		try (EtchStore store = EtchStore.create(file)) {
			Index<Keyword, ACell> root = store.getRootData();
			assertEquals(Strings.create("record"), value(root, RECORD), "a synced commit is");
			assertEquals("file", file(root, "/a.txt"));
		}
	}
}
