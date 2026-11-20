package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.dlfs.DLFSDrive;
import covia.adapter.DLFSAdapter;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.lattice.CoviaPath;

/**
 * A user's transaction at a venue ({@link Engine#transact}): their workspace
 * and their DLFS drives change together or not at all.
 */
class EngineTransactionTest {
	private static final AString USER = TestEngine.uniqueDID("engine-transaction");
	private static final RequestContext CTX = RequestContext.of(USER);
	private static final Path RECORD = CoviaPath.parse("record");
	private static final String DRIVE = "home";

	private CoviaApplication application;
	private Engine engine;
	private DLFSAdapter dlfs;

	@BeforeEach
	void setUp() throws IOException {
		application = TestEngine.application();
		engine = application.mount(AKeyPair.generate(), new Config(null)).start();
		Engine.addDemoAssets(engine);
		dlfs = (DLFSAdapter) engine.getAdapter("dlfs");
		engine.getVenueState().users().ensure(USER);
	}

	@AfterEach
	void tearDown() {
		application.close();
	}

	private ACell workspaceValue() {
		return engine.getVenueState().users().user(USER).workspace().read(RECORD);
	}

	/** A file's text in the user's live drive, or null if the drive or file is absent. */
	private String file(String path) throws IOException {
		var drive = dlfs.getDriveForIdentity(USER.toString(), DRIVE);
		if (drive == null) return null;
		var file = drive.getPath(path);
		return Files.exists(file) ? Files.readString(file) : null;
	}

	@Test
	void aWorkspaceValueAndAFileCommitTogether() throws IOException {
		String result = engine.transact(CTX, tx -> {
			assertEquals(USER, tx.userDID());
			tx.workspace().write(RECORD, Strings.create("record"));
			Files.writeString(tx.drive(DRIVE).fileSystem().getPath("/a.txt"), "file");
			return "done";
		});
		assertEquals("done", result);
		assertEquals(Strings.create("record"), workspaceValue());
		assertEquals("file", file("/a.txt"));
	}

	@Test
	void aUserWithNoDlfsKeyIsGivenOneFirst() throws IOException {
		assertEquals(0, dlfs.storageSize(USER), "no key and no drives yet");
		engine.transact(CTX, tx -> Files.writeString(tx.drive(DRIVE).fileSystem().getPath("/a.txt"), "file"));
		assertEquals("file", file("/a.txt"));
	}

	@Test
	void anIOExceptionAppliesNothing() throws IOException {
		IOException failure = new IOException();
		IOException thrown = assertThrows(IOException.class, () -> engine.transact(CTX, tx -> {
			tx.workspace().write(RECORD, Strings.create("record"));
			Files.writeString(tx.drive(DRIVE).fileSystem().getPath("/a.txt"), "file");
			throw failure;
		}));
		assertSame(failure, thrown, "the callback's own exception");
		assertNull(workspaceValue());
		assertNull(file("/a.txt"));
	}

	@Test
	void existingDriveFindsOnlyADriveThatExists() throws IOException {
		engine.transact(CTX, tx -> {
			assertNull(tx.existingDrive(DRIVE), "absent");
			tx.drive(DRIVE);
			assertNotNull(tx.existingDrive(DRIVE), "created in this transaction");
			return null;
		});
		engine.transact(CTX, tx -> {
			assertNotNull(tx.existingDrive(DRIVE), "and committed");
			return null;
		});
	}

	@Test
	void anInvalidDriveNameIsRefused() {
		var refusal = assertThrows(InvalidInputException.class, () -> engine.transact(CTX, tx -> tx.drive("no/such")));
		assertEquals(DLFSAdapter.INVALID_DRIVE_NAME, refusal.getMessage());
	}

	@Test
	void anUnregisteredUserIsRefused() {
		RequestContext stranger = RequestContext.of(TestEngine.uniqueDID("engine-transaction-stranger"));
		var refusal = assertThrows(NotFoundException.class, () -> engine.transact(stranger, tx -> null));
		assertEquals(VenueState.UNKNOWN_USER, refusal.getMessage());
	}

	@Test
	void aContextWithNoUserIsRefused() {
		var refusal = assertThrows(AuthException.class, () -> engine.transact(RequestContext.ANONYMOUS, tx -> null));
		assertEquals(Engine.TRANSACTION_NEEDS_USER, refusal.getMessage());
	}

	@Test
	void aVenueWithNoDlfsStillChangesTheWorkspace() throws IOException {
		CoviaApplication bare = TestEngine.application();
		try {
			Engine noDlfs = bare.mount(AKeyPair.generate(), Maps.empty()).start();
			assertNull(noDlfs.getAdapter("dlfs"), "this venue runs no DLFS adapter");
			noDlfs.getVenueState().users().ensure(USER);
			var refusal = assertThrows(covia.exception.UnavailableException.class, () -> noDlfs.transact(CTX, tx -> {
				tx.workspace().write(RECORD, Strings.create("record"));
				return tx.drive(DRIVE);
			}));
			assertEquals(Engine.NO_DLFS, refusal.getMessage());
			assertNull(noDlfs.getVenueState().users().user(USER).workspace().read(RECORD), "the refused transaction applied nothing");
			noDlfs.transact(CTX, tx -> tx.workspace().write(RECORD, Strings.create("record")));
			assertEquals(Strings.create("record"), noDlfs.getVenueState().users().user(USER).workspace().read(RECORD));
		} finally {
			bare.close();
		}
	}

	@Test
	void nothingIsReachableAfterTheCallbackReturns() throws IOException {
		AtomicReference<UserTransaction> keptTransaction = new AtomicReference<>();
		AtomicReference<DLFSDrive> keptDrive = new AtomicReference<>();
		engine.transact(CTX, tx -> {
			keptTransaction.set(tx);
			keptDrive.set(tx.drive(DRIVE));
			return null;
		});
		var refusal = assertThrows(IllegalStateException.class, () -> keptTransaction.get().workspace());
		assertEquals(CoviaApplication.DRAFT_FINISHED, refusal.getMessage());
		assertThrows(IllegalStateException.class, () -> keptTransaction.get().drive(DRIVE));
		assertThrows(ClosedFileSystemException.class,
			() -> Files.writeString(keptDrive.get().fileSystem().getPath("/late.txt"), "late"));
	}
}
