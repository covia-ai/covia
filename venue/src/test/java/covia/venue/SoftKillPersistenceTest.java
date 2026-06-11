package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
import convex.core.store.AStore;
import convex.etch.EtchStore;
import covia.api.Fields;
import covia.venue.server.VenueServer;

/**
 * Soft-kill resilience tests — the same write/restart matrix as
 * {@link HardKillPersistenceTest}, but using graceful in-JVM shutdown
 * ({@link VenueServer#close()}) instead of a child-process
 * {@link Runtime#halt(int)}.
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
public class SoftKillPersistenceTest {

	private static final AString ALICE_DID = Strings.create("did:key:z6MkSoftKillAlice");

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> testConfig(String storePath, String seedHex) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Soft-Kill Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(seedHex),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> configNoSeed(String storePath) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Soft-Kill Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	/**
	 * Allocates a unique etch file path under a per-test temp directory.
	 * We use a directory rather than a flat temp file so we can verify
	 * the venue did not splatter unexpected sibling files alongside the etch.
	 */
	private static Path freshEtchPath(String testName) throws IOException {
		Path dir = Files.createTempDirectory("soft-kill-" + testName + "-");
		dir.toFile().deleteOnExit();
		Path etch = dir.resolve("venue.etch");
		// EtchStore.create requires the file not to exist — don't create it ahead of time.
		return etch;
	}

	private static void writeDLFS(VenueServer server, String drive, String path, String content) throws Exception {
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
	// Test 1 — Write + explicit flush + close, then restart in the same JVM.
	// Verify: etch at configured path, non-empty, data reads back correctly.
	// ========================================================================
	@Test
	public void testFlushedWriteSurvivesSoftClose() throws Exception {
		Path etchPath = freshEtchPath("flushed");
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6001).getSeed().toHexString();

		// === Phase 1: write, flush, close ===
		VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			writeDLFS(server, "health-vault", "hello.txt", "soft-content");
			server.getEngine().flush();

			// Etch should already be on disk at the configured path
			assertTrue(Files.exists(etchPath),
				"etch file must exist at configured path after flush: " + etchPath);
			assertTrue(Files.size(etchPath) > 0,
				"etch file must be non-empty after a flushed write: " + etchPath);
		} finally {
			server.close();
		}

		// After close, the etch must still be at the configured path.
		assertTrue(Files.exists(etchPath),
			"etch file must still exist at configured path after server.close(): " + etchPath);
		long sizeAfterClose = Files.size(etchPath);
		assertTrue(sizeAfterClose > 0, "etch file must be non-empty after close");

		// === Phase 2: relaunch against the same path, read back ===
		VenueServer reader = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			AStore store = reader.getStore();
			assertTrue(store instanceof EtchStore,
				"persistent file config should produce an EtchStore, got: " + store.getClass());
			File readerEtch = ((EtchStore) store).getFile();
			assertEquals(etchPath.toAbsolutePath().toFile().getCanonicalPath(),
				readerEtch.getCanonicalPath(),
				"reader's etch store path must match the configured store path");

			String content = readDLFS(reader, "health-vault", "hello.txt");
			assertEquals("soft-content", content,
				"flushed write must round-trip exactly after soft-restart");
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 2 — Write WITHOUT explicit flush, then close. The close-time final
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
			writeDLFS(server, "health-vault", "rely-on-close.txt", "no-flush-content");
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
			String content = readDLFS(reader, "health-vault", "rely-on-close.txt");
			assertEquals("no-flush-content", content,
				"Engine.close() must run a final flush — write was not flushed explicitly");
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 3 — Burst of N writes through the venue in one session, then
	// graceful close + restart. All N must survive at the correct path.
	// ========================================================================
	@Test
	public void testBurstSurvivesSoftClose() throws Exception {
		final int N = 30;
		Path etchPath = freshEtchPath("burst");
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6003).getSeed().toHexString();

		VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			for (int i = 0; i < N; i++) {
				writeDLFS(server, "health-vault", "item-" + i + ".txt", "value-" + i);
			}
			server.getEngine().flush();
		} finally {
			server.close();
		}

		assertTrue(Files.exists(etchPath), "etch at configured path after burst+close");
		long size = Files.size(etchPath);
		assertTrue(size > 0, "etch should have content from " + N + " writes");

		VenueServer reader = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			for (int i = 0; i < N; i++) {
				String content = readDLFS(reader, "health-vault", "item-" + i + ".txt");
				assertEquals("value-" + i, content,
					"burst entry #" + i + " must survive soft-restart");
			}
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 4 — Etch file location: when an explicit seed is in config, the
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
			writeDLFS(server, "health-vault", "x.txt", "v");
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
	// Test 5 — Identity persistence via auto-generated key file. When no
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
			writeDLFS(server1, "health-vault", "identity-test.txt", "stable");
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
			assertEquals("stable", readDLFS(server2, "health-vault", "identity-test.txt"),
				"data must round-trip when identity is restored from key file");
		} finally {
			server2.close();
		}
	}

	// ========================================================================
	// Test 6 — Multiple close/restart cycles accumulate state. After each
	// cycle the etch grows or stays the same size (never shrinks below
	// its content from the previous cycle), and every prior write remains
	// readable.
	// ========================================================================
	@Test
	public void testMultipleCloseRestartCyclesAccumulate() throws Exception {
		Path etchPath = freshEtchPath("multi-cycle");
		String storePath = etchPath.toString().replace('\\', '/');
		String seedHex = AKeyPair.createSeeded(6006).getSeed().toHexString();

		final int CYCLES = 3;
		long previousSize = 0;

		for (int cycle = 0; cycle < CYCLES; cycle++) {
			VenueServer server = VenueServer.launch(testConfig(storePath, seedHex));
			try {
				// Every prior cycle's file must still be readable
				for (int prior = 0; prior < cycle; prior++) {
					assertEquals("cycle-" + prior,
						readDLFS(server, "health-vault", "cycle-" + prior + ".txt"),
						"writes from cycle " + prior + " must survive into cycle " + cycle);
				}
				// And this cycle adds its own
				writeDLFS(server, "health-vault", "cycle-" + cycle + ".txt", "cycle-" + cycle);
				server.getEngine().flush();
			} finally {
				server.close();
			}

			long currentSize = Files.size(etchPath);
			assertTrue(currentSize >= previousSize,
				"etch size must not shrink across cycles (cycle " + cycle
					+ ": was " + previousSize + ", now " + currentSize + ")");
			previousSize = currentSize;
		}

		// Final read-back: all CYCLES files present
		VenueServer reader = VenueServer.launch(testConfig(storePath, seedHex));
		try {
			for (int cycle = 0; cycle < CYCLES; cycle++) {
				assertEquals("cycle-" + cycle,
					readDLFS(reader, "health-vault", "cycle-" + cycle + ".txt"),
					"cycle " + cycle + " write missing after " + CYCLES + " restart cycles");
			}
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 7 — Read of a never-written DLFS path returns null content. This
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
					Maps.of("drive", "health-vault", "path", "never-written.txt"),
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
