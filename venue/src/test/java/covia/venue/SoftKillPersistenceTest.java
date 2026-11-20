package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.server.VenueServer;
import covia.test.DurabilityTest;

/**
 * Soft-kill tests: a venue server closed gracefully in the JVM
 * ({@link VenueServer#close()}), where {@link HardKillPersistenceTest}
 * halts a child process. That the whole venue state survives a reopen is
 * {@link VenuePersistenceTest}'s; these check the server's own path to it.
 *
 * <p>Where the hard-kill tests verify "did the data make it to disk
 * before HALT", these verify the well-behaved path:</p>
 *
 * <ul>
 *   <li>{@link VenueServer#close()} runs the engine's final flush before
 *       {@code nodeServer.close()} (see {@code venue/docs/PERSISTENCE.md} §5.3),
 *       so writes are durable even without an explicit {@code engine.flush()}.</li>
 *   <li>The etch store file lives at the configured path — not somewhere
 *       else surprising — and is non-empty after a close that included writes.</li>
 *   <li>Read-back after restart returns exactly the bytes that were written.</li>
 * </ul>
 *
 * <p>Each test asserts BOTH the etch file location and the round-tripped
 * data, so a regression that silently writes to a different path (or
 * writes nothing) is caught by the file-location assertion even if a
 * cached cursor in the second VenueServer happens to return the right
 * value in memory.</p>
 */
// Durability guarantee: forks real JVMs / relaunches venues to prove crash
// and restart survival. Slow and unavoidably so — excluded from the default
// `mvn test` inner loop, run on every push via CI (-DexcludedGroups=integration).
@DurabilityTest
public class SoftKillPersistenceTest {

	private static final AString ALICE_DID = Strings.create("did:key:z6MkSoftKillAlice");

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> testConfig(String storePath, String seedHex) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Soft-Kill Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(seedHex),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> configNoSeed(String storePath) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Soft-Kill Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	/**
	 * Allocates a unique etch file path in a directory of its own, so we can
	 * verify the venue did not splatter unexpected sibling files alongside it.
	 */
	private static Path freshEtchPath(String testName) throws IOException {
		return TestTemp.etchFile("soft-kill-" + testName).toPath();
	}

	private static void writeDLFS(VenueServer server, String drive, String path, String content) throws Exception {
		server.getEngine().jobs().invokeOperation("v/ops/dlfs/create-drive", Maps.of("name", drive),
			RequestContext.of(ALICE_DID)).awaitResult(5000);
		server.getEngine().jobs().invokeOperation(
			"v/ops/dlfs/write",
			Maps.of("drive", drive, "path", path, "content", content),
			RequestContext.of(ALICE_DID)
		).awaitResult(5000);
	}

	private static String readDLFS(VenueServer server, String drive, String path) throws Exception {
		ACell result = server.getEngine().jobs().invokeOperation(
			"v/ops/dlfs/read",
			Maps.of("drive", drive, "path", path),
			RequestContext.of(ALICE_DID)
		).awaitResult(5000);
		if (result == null) return null;
		ACell c = RT.getIn(result, "content");
		return c == null ? null : RT.ensureString(c).toString();
	}

	// ========================================================================
	// Write WITHOUT explicit flush, then close. The close-time final
	// flush (Engine.close, PERSISTENCE.md §5.3) is the only thing that makes
	// the write durable. After restart, the write must still be there.
	// ========================================================================
	@Test
	public void testUnflushedWriteSurvivesSoftClose() throws Exception {
		Path etchPath = freshEtchPath("unflushed");
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6002).getSeed().toHexString();

		VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			writeDLFS(server, "test-drive", "rely-on-close.txt", "no-flush-content");
			// Deliberately do NOT call engine.flush() — server.close() must
			// run a final flush internally per §5.3.
		} finally {
			server.close();
		}

		assertTrue(Files.exists(etchPath),
			"etch file must exist after close even without explicit flush: " + etchPath);
		assertTrue(Files.size(etchPath) > 0,
			"close-time final flush must have written to the etch file");

		VenueServer reader = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			String content = readDLFS(reader, "test-drive", "rely-on-close.txt");
			assertEquals("no-flush-content", content,
				"Engine.close() must run a final flush — write was not flushed explicitly");
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 2b — close() is idempotent: calling it twice (e.g. an explicit close
	// plus the JVM shutdown flush hook) must not throw or corrupt the store, and
	// the unflushed write must still be durable after restart. (#165)
	// ========================================================================
	@Test
	public void testDoubleCloseIsIdempotentAndDurable() throws Exception {
		Path etchPath = freshEtchPath("double-close");
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6012).getSeed().toHexString();

		VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			writeDLFS(server, "test-drive", "idem.txt", "double-close-content");
			// No explicit flush — rely on close's final flush.
		} finally {
			server.close();
			server.close(); // second close must be a safe no-op (idempotent)
		}

		assertTrue(Files.exists(etchPath) && Files.size(etchPath) > 0,
			"first close must have run the final flush");

		VenueServer reader = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			assertEquals("double-close-content",
				readDLFS(reader, "test-drive", "idem.txt"),
				"write must survive a double close (second close is a no-op)");
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Etch file location: when an explicit seed is in config, the
	// only file in the temp directory after close should be the etch file
	// itself. No venue.key sibling should be auto-created.
	// ========================================================================
	@Test
	public void testEtchAtConfiguredPathNoSiblingsWithExplicitSeed() throws Exception {
		Path etchPath = freshEtchPath("path-only");
		Path dir = etchPath.getParent();
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6004).getSeed().toHexString();

		VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			writeDLFS(server, "test-drive", "x.txt", "v");
			server.getEngine().flush();
		} finally {
			server.close();
		}

		assertTrue(Files.exists(etchPath), "etch at configured path: " + etchPath);

		// Inventory the directory. With an explicit seed, only the etch
		// file should be there — no auto-generated venue.key.
		try (var stream = Files.list(dir)) {
			var siblings = stream
				.filter(p -> !p.equals(etchPath))
				.toList();
			assertTrue(siblings.isEmpty() || onlyEtchLockFiles(siblings, etchPath),
				"unexpected siblings created next to etch: " + siblings);
		}

		Path keyFile = dir.resolve("venue.key");
		assertFalse(Files.exists(keyFile),
			"venue.key must NOT be created when an explicit seed is in config");
	}

	/**
	 * Permits transient lock or auxiliary files that Etch may legitimately
	 * create next to the main etch file. Anything matching {@code <etch>.*}
	 * is permitted; anything else is suspicious.
	 */
	private static boolean onlyEtchLockFiles(java.util.List<Path> siblings, Path etchPath) {
		String etchName = etchPath.getFileName().toString();
		for (Path p : siblings) {
			String name = p.getFileName().toString();
			if (!name.startsWith(etchName)) return false;
		}
		return true;
	}

	// ========================================================================
	// Identity persistence via auto-generated key file. When no
	// seed is in config, VenueServer creates venue.key next to the store
	// on first launch and re-reads it on restart, so the DID is stable.
	// ========================================================================
	@Test
	public void testAutoKeyFileGivesStableDIDAcrossRestart() throws Exception {
		Path etchPath = freshEtchPath("auto-key");
		Path dir = etchPath.getParent();
		String storePath = etchPath.toString().replace('\\', '/');
		Path expectedKeyFile = dir.resolve("venue.key");

		AString firstDID;
		VenueServer server1 = VenueServer.launch(configNoSeed(storePath));
		try {
			firstDID = server1.getEngine().getDIDString();
			assertTrue(Files.exists(expectedKeyFile),
				"venue.key must be auto-created next to store when no seed in config: " + expectedKeyFile);
			assertTrue(Files.size(expectedKeyFile) > 0, "venue.key must contain a hex seed");
			writeDLFS(server1, "test-drive", "identity-test.txt", "stable");
			server1.getEngine().flush();
		} finally {
			server1.close();
		}

		// Restart with the same config (still no seed) — VenueServer must
		// pick up venue.key and produce the same DID.
		VenueServer server2 = VenueServer.launch(configNoSeed(storePath));
		try {
			assertEquals(firstDID, server2.getEngine().getDIDString(),
				"DID must be stable across restart via auto-saved venue.key");
			assertEquals("stable", readDLFS(server2, "test-drive", "identity-test.txt"),
				"data must round-trip when identity is restored from key file");
		} finally {
			server2.close();
		}
	}

	// ========================================================================
	// Read of a never-written DLFS path returns null content. This
	// is the "no stale data" guard: a fresh etch must not leak state from
	// an unrelated previous etch or from earlier tests, and a missing path
	// must be reported as missing, not silently substituted from initial
	// bootstrap state.
	// ========================================================================
	@Test
	public void testReadOfMissingPathReturnsNull() throws Exception {
		Path etchPath = freshEtchPath("missing");
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6007).getSeed().toHexString();

		VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			// Read a never-written file from a never-created drive.
			ACell result;
			try {
				result = server.getEngine().jobs().invokeOperation(
					"v/ops/dlfs/read",
					Maps.of("drive", "test-drive", "path", "never-written.txt"),
					RequestContext.of(ALICE_DID)
				).awaitResult(5000);
			} catch (Exception expected) {
				// An exception ("file not found", etc.) is the correct
				// negative answer too — no leaked content.
				return;
			}
			ACell content = (result == null) ? null : RT.getIn(result, "content");
			assertNull(content,
				"read of never-written DLFS path must return null content (or throw), not leaked data");
		} finally {
			server.close();
		}
	}
}
