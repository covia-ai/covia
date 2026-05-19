package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

/**
 * Hard-kill resilience tests for the venue persistence stack.
 *
 * <p>These tests verify that data written through the engine survives an
 * abrupt process termination (the equivalent of {@code kill -9}), not just
 * a graceful {@link VenueServer#close()} call. Each test:</p>
 *
 * <ol>
 *   <li>Spawns a {@link HardKillTestChild} as a separate JVM pointing at a
 *       temp Etch store + fixed seed.</li>
 *   <li>Drives the child via stdin/stdout protocol — writes, optional flush,
 *       then HALT (={@link Runtime#halt(int)}, no shutdown hooks).</li>
 *   <li>Restarts a venue in the same JVM against the same store and seed
 *       and asserts the writes are readable.</li>
 * </ol>
 *
 * <p>See {@code venue/docs/PERSISTENCE.md} §7 for the test plan these
 * implement.</p>
 */
public class HardKillPersistenceTest {

	private static final AString ALICE_DID = HardKillTestChild.ALICE_DID;
	private static final String PROTO = "PROTO";
	private static final long PROTO_TIMEOUT_MS = 30_000;

	/**
	 * Holds the spawned child Process plus reader/writer wrappers and a
	 * helper to read protocol lines (ignoring non-protocol stdout from
	 * logback etc.).
	 */
	private static final class Child implements AutoCloseable {
		final Process process;
		final BufferedReader stdout;
		final PrintWriter stdin;
		final Thread stderrPump;

		Child(Process p) {
			this.process = p;
			this.stdout = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
			this.stdin = new PrintWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8), true);
			// Drain stderr so the child can't block on a full pipe.
			this.stderrPump = new Thread(() -> {
				try (BufferedReader r = new BufferedReader(
						new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
					String l;
					while ((l = r.readLine()) != null) {
						// echo to test stderr — visible in surefire output when -Dtest fails
						System.err.println("[child] " + l);
					}
				} catch (IOException ignored) { /* child died */ }
			}, "hardkill-child-stderr");
			this.stderrPump.setDaemon(true);
			this.stderrPump.start();
		}

		/** Sends a command line and returns the next PROTO response payload (after the "PROTO " prefix). */
		String send(String command) throws Exception {
			stdin.println(command);
			if (stdin.checkError()) throw new IOException("child stdin closed");
			return readProto();
		}

		/** Reads stdout lines until one starts with "PROTO ". Returns the suffix. */
		String readProto() throws Exception {
			long deadline = System.currentTimeMillis() + PROTO_TIMEOUT_MS;
			AtomicReference<String> result = new AtomicReference<>();
			while (System.currentTimeMillis() < deadline) {
				if (stdout.ready() || !process.isAlive()) {
					String line = stdout.readLine();
					if (line == null) {
						throw new IOException("child stdout closed (exit=" + process.exitValue() + ")");
					}
					if (line.startsWith(PROTO + " ")) {
						return line.substring(PROTO.length() + 1);
					}
					// Non-protocol line — log output. Ignore.
				} else {
					Thread.sleep(10);
				}
			}
			throw new IllegalStateException("Timed out waiting for PROTO response");
		}

		@Override public void close() {
			try { stdin.close(); } catch (Exception ignored) {}
			try { process.destroyForcibly(); } catch (Exception ignored) {}
		}
	}

	private static Child spawn(String storePath, String seedHex) throws IOException {
		String javaHome = System.getProperty("java.home");
		String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
		String classpath = System.getProperty("java.class.path");

		ProcessBuilder pb = new ProcessBuilder(
			javaBin,
			"-cp", classpath,
			HardKillTestChild.class.getName(),
			storePath, seedHex
		);
		// Separate stderr so logback output doesn't pollute the protocol channel.
		pb.redirectErrorStream(false);
		return new Child(pb.start());
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> readerConfig(String storePath, String seedHex) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Hard-Kill Reader Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(seedHex),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	private static File newTempEtch() throws IOException {
		File f = File.createTempFile("hardkill-", ".etch");
		f.delete();
		f.deleteOnExit();
		return f;
	}

	/** Reads "drive:path" via the DLFS adapter and returns the content string, or null. */
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
	// Test 1 — Write + flush, then SIGKILL. After restart, the write MUST be
	// readable. This is the strongest guarantee engine.flush() advertises.
	// ========================================================================
	@Test
	public void testFlushedWriteSurvivesHardKill() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(5001);
		String seedHex = venueKey.getSeed().toHexString();

		try (Child child = spawn(storePath, seedHex)) {
			String ready = child.readProto();
			assertTrue(ready.startsWith("READY "), "child should announce READY: got '" + ready + "'");

			assertEquals("OK", child.send("WRITE health-vault hello.txt persistent-content"));

			// Hard-kill — Runtime.halt(137) bypasses shutdown hooks.
			child.stdin.println("HALT");
			child.stdin.flush();
			boolean exited = child.process.waitFor(15, TimeUnit.SECONDS);
			assertTrue(exited, "child should exit after HALT");
			assertEquals(137, child.process.exitValue(), "expected exit code 137 from Runtime.halt");
		}

		// Restart against the same store + seed and read it back.
		VenueServer reader = VenueServer.launch(readerConfig(storePath, seedHex));
		try {
			String content = readDLFS(reader, "health-vault", "hello.txt");
			assertNotNull(content, "DLFS file must survive hard-kill when engine.flush() was called before HALT");
			assertEquals("persistent-content", content);
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 2 — Write WITHOUT flush, wait for several sweep cycles, then HALT.
	// The sweep daemon should have synced and propagated the write to disk
	// well within the wait window (100ms sweep interval + propagator drain).
	// ========================================================================
	@Test
	public void testSweptWriteSurvivesHardKill() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(5002);
		String seedHex = venueKey.getSeed().toHexString();

		try (Child child = spawn(storePath, seedHex)) {
			String ready = child.readProto();
			assertTrue(ready.startsWith("READY "), "child should announce READY: got '" + ready + "'");

			assertEquals("OK", child.send("WRITE_NOFLUSH health-vault sweep.txt swept-content"));
			// Give the background sweep + propagator generous time.
			// Sweep interval is 100ms; propagator drain is microseconds-to-ms.
			assertEquals("OK", child.send("SLEEP 2000"));

			child.stdin.println("HALT");
			child.stdin.flush();
			boolean exited = child.process.waitFor(15, TimeUnit.SECONDS);
			assertTrue(exited, "child should exit after HALT");
		}

		VenueServer reader = VenueServer.launch(readerConfig(storePath, seedHex));
		try {
			String content = readDLFS(reader, "health-vault", "sweep.txt");
			assertNotNull(content,
				"DLFS file must survive hard-kill after background sweep has had 2s to run — "
				+ "if null, either the sweep isn't actually propagating or the propagator's "
				+ "queue takes longer than 2s to drain");
			assertEquals("swept-content", content);
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 3 — Burst of flushed writes, then HALT. All N writes must survive.
	// Exercises the coalescing path and rules out lost-update races between
	// sequential flushes.
	// ========================================================================
	@Test
	public void testBurstOfFlushedWritesSurvivesHardKill() throws Exception {
		final int N = 25;
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(5003);
		String seedHex = venueKey.getSeed().toHexString();

		try (Child child = spawn(storePath, seedHex)) {
			String ready = child.readProto();
			assertTrue(ready.startsWith("READY "), "child should announce READY: got '" + ready + "'");

			for (int i = 0; i < N; i++) {
				assertEquals("OK", child.send("WRITE health-vault burst-" + i + ".txt value-" + i),
					"write #" + i + " should succeed");
			}

			child.stdin.println("HALT");
			child.stdin.flush();
			boolean exited = child.process.waitFor(15, TimeUnit.SECONDS);
			assertTrue(exited, "child should exit after HALT");
		}

		VenueServer reader = VenueServer.launch(readerConfig(storePath, seedHex));
		try {
			for (int i = 0; i < N; i++) {
				String content = readDLFS(reader, "health-vault", "burst-" + i + ".txt");
				assertEquals("value-" + i, content,
					"burst write #" + i + " must survive hard-kill");
			}
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 4 — Diagnostic: an UNFLUSHED write with NO sleep before HALT may
	// or may not survive depending on timing. This test does NOT assert
	// durability; it asserts that the venue at least restarts cleanly even
	// if the write was lost mid-flight. The expected lower bound on the
	// venue's behaviour is "restart works, no etch corruption".
	// ========================================================================
	@Test
	public void testUnflushedImmediateHaltDoesNotCorruptStore() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(5004);
		String seedHex = venueKey.getSeed().toHexString();

		try (Child child = spawn(storePath, seedHex)) {
			String ready = child.readProto();
			assertTrue(ready.startsWith("READY "), "child should announce READY: got '" + ready + "'");

			// Single unflushed write, then immediate halt — race against the sweep.
			assertEquals("OK", child.send("WRITE_NOFLUSH health-vault race.txt maybe-survives"));

			child.stdin.println("HALT");
			child.stdin.flush();
			boolean exited = child.process.waitFor(15, TimeUnit.SECONDS);
			assertTrue(exited, "child should exit after HALT");
		}

		// Restart must succeed even if the unflushed write was lost.
		// (We do NOT assert the file is readable — that's a race.)
		VenueServer reader = VenueServer.launch(readerConfig(storePath, seedHex));
		try {
			// A second write after restart must work — proves the etch is uncorrupted.
			reader.getEngine().jobs().invokeOperation(
				"v/ops/dlfs/write",
				Maps.of("drive", "health-vault", "path", "after-restart.txt", "content", "ok"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			reader.getEngine().flush();
			assertEquals("ok", readDLFS(reader, "health-vault", "after-restart.txt"),
				"writes after restart must still work — etch should not be corrupted by an aborted write");
		} catch (Exception e) {
			fail("Venue failed to restart after a hard-killed unflushed write: " + e.getMessage());
		} finally {
			reader.close();
		}
	}

	// ========================================================================
	// Test 5 — Survives a second hard-kill cycle. Confirms persistence is
	// idempotent and additive across multiple crash/restart cycles, not just
	// a one-shot save/restore.
	// ========================================================================
	@Test
	public void testTwoConsecutiveHardKillCyclesAccumulate() throws Exception {
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(5005);
		String seedHex = venueKey.getSeed().toHexString();

		// Cycle 1
		try (Child child = spawn(storePath, seedHex)) {
			assertTrue(child.readProto().startsWith("READY "));
			assertEquals("OK", child.send("WRITE health-vault cycle1.txt first"));
			child.stdin.println("HALT");
			child.stdin.flush();
			assertTrue(child.process.waitFor(15, TimeUnit.SECONDS));
		}

		// Cycle 2 — add a second file, hard-kill again
		try (Child child = spawn(storePath, seedHex)) {
			assertTrue(child.readProto().startsWith("READY "));
			assertEquals("OK", child.send("WRITE health-vault cycle2.txt second"));
			child.stdin.println("HALT");
			child.stdin.flush();
			assertTrue(child.process.waitFor(15, TimeUnit.SECONDS));
		}

		// Read back — both writes from both cycles must survive
		VenueServer reader = VenueServer.launch(readerConfig(storePath, seedHex));
		try {
			assertEquals("first", readDLFS(reader, "health-vault", "cycle1.txt"),
				"write from cycle 1 must survive both hard-kills");
			assertEquals("second", readDLFS(reader, "health-vault", "cycle2.txt"),
				"write from cycle 2 must survive its hard-kill");
		} finally {
			reader.close();
		}
	}
}
