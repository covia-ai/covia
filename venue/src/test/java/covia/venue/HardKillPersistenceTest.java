package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	/**
	 * Reads "drive:path" via the DLFS adapter and returns the content string, or null.
	 *
	 * <p>A freshly-launched reader venue may still be mounting its DLFS drives
	 * when the first read lands; under parallel-suite load that surfaced as a
	 * transient read failure ({@code JobFailedException} on the path). A flushed
	 * write is durable, so the value becomes readable shortly after launch —
	 * poll briefly rather than failing on the race. This does not mask a real
	 * durability regression: a genuinely-lost write never appears, so the loop
	 * gives up at the deadline and surfaces the last error (or null).</p>
	 */
	private static String readDLFS(VenueServer server, String drive, String path) throws Exception {
		long deadline = System.currentTimeMillis() + 15_000;
		Exception last = null;
		while (true) {
			try {
				ACell result = server.getEngine().jobs().invokeOperation(
					"v/ops/dlfs/read",
					Maps.of("drive", drive, "path", path),
					RequestContext.of(ALICE_DID)
				).awaitResult(5000);
				if (result != null) {
					ACell c = RT.getIn(result, "content");
					if (c != null) return RT.ensureString(c).toString();
				}
				// null result/content — drive may not be mounted yet; retry.
			} catch (Exception e) {
				last = e; // transient (e.g. drive not mounted yet) — retry until deadline
			}
			if (System.currentTimeMillis() >= deadline) {
				if (last != null) throw last;
				return null;
			}
			Thread.sleep(100);
		}
	}

	// ========================================================================
	// One comprehensive hard-kill resilience test driving TWO child JVMs in
	// sequence (peak one child alive at a time, vs the previous five parallel
	// methods that spawned up to six concurrent child JVMs — a JVM-spawn storm
	// that both dominated the class wall-clock and was a prime source of the
	// full-suite flakiness in #151).
	//
	// Every durability property the old five tests covered is preserved, and
	// the accumulation across two crash cycles is now exercised against a LARGE
	// cycle-1 dataset — making this a strictly stronger probe for any
	// re-persist / merge bug that drops earlier-cycle data:
	//
	//   Cycle 1 (child A), then SIGKILL:
	//     • flushed write           → must survive (the core flush() guarantee)
	//     • burst of flushed writes → all must survive (coalescing / no lost update)
	//     • unflushed + sweep wait  → must survive (background sweep durability)
	//     • unflushed + immediate   → may or may not survive (corruption probe only)
	//   Cycle 2 (child B), then SIGKILL:
	//     • restore loads the whole cycle-1 dataset (proves no corruption), adds
	//       one more flushed write, hard-kills again (accumulation across cycles)
	//   Reader (in-process restart): every flushed/swept file from BOTH cycles
	//     must be readable, and a fresh write must still work (etch uncorrupted).
	// ========================================================================
	@Test
	public void testHardKillResilienceAcrossCycles() throws Exception {
		final int BURST = 25;
		File etchFile = newTempEtch();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		AKeyPair venueKey = AKeyPair.createSeeded(5000);
		String seedHex = venueKey.getSeed().toHexString();

		// ---- Cycle 1: write a large mixed dataset, then hard-kill ----
		try (Child child = spawn(storePath, seedHex)) {
			String ready = child.readProto();
			assertTrue(ready.startsWith("READY "), "child should announce READY: got '" + ready + "'");

			// Flushed write — the strongest guarantee flush() advertises.
			assertEquals("OK", child.send("WRITE health-vault hello.txt persistent-content"));

			// Burst of flushed writes — coalescing path, no lost-update between flushes.
			for (int i = 0; i < BURST; i++) {
				assertEquals("OK", child.send("WRITE health-vault burst-" + i + ".txt value-" + i),
					"burst write #" + i + " should succeed");
			}

			// Unflushed write, then wait out several sweep cycles (100ms interval)
			// so the background sweep + propagator persist it before the kill.
			assertEquals("OK", child.send("WRITE_NOFLUSH health-vault sweep.txt swept-content"));
			assertEquals("OK", child.send("SLEEP 2000"));

			// Unflushed write with NO sleep before the kill — races the sweep, so
			// survival is not asserted; this is the corruption probe (cycle 2's
			// restore below proves the store is not corrupted by an aborted write).
			assertEquals("OK", child.send("WRITE_NOFLUSH health-vault race.txt maybe-survives"));

			child.stdin.println("HALT");
			child.stdin.flush();
			boolean exited = child.process.waitFor(15, TimeUnit.SECONDS);
			assertTrue(exited, "cycle-1 child should exit after HALT");
			assertEquals(137, child.process.exitValue(), "expected exit code 137 from Runtime.halt");
		}

		// ---- Cycle 2: restore the cycle-1 dataset, add one write, hard-kill ----
		try (Child child = spawn(storePath, seedHex)) {
			String ready = child.readProto();
			assertTrue(ready.startsWith("READY "), "cycle-2 child should announce READY: got '" + ready + "'");

			// A successful flushed write here proves the restore loaded the prior
			// store cleanly (no corruption from cycle 1's aborted race.txt write).
			assertEquals("OK", child.send("WRITE health-vault cycle2.txt second"));

			child.stdin.println("HALT");
			child.stdin.flush();
			boolean exited = child.process.waitFor(15, TimeUnit.SECONDS);
			assertTrue(exited, "cycle-2 child should exit after HALT");
		}

		// ---- Reader: every durable file from BOTH cycles must survive ----
		VenueServer reader = VenueServer.launch(readerConfig(storePath, seedHex));
		try {
			assertEquals("persistent-content", readDLFS(reader, "health-vault", "hello.txt"),
				"flushed write must survive both hard-kills");
			for (int i = 0; i < BURST; i++) {
				assertEquals("value-" + i, readDLFS(reader, "health-vault", "burst-" + i + ".txt"),
					"burst write #" + i + " must survive both hard-kills");
			}
			assertEquals("swept-content", readDLFS(reader, "health-vault", "sweep.txt"),
				"swept (unflushed) write must survive — sweep had 2s to propagate before the kill");
			assertEquals("second", readDLFS(reader, "health-vault", "cycle2.txt"),
				"cycle-2 write must survive its hard-kill (accumulation across cycles)");

			// A fresh write after restart must work — proves the etch is uncorrupted
			// (subsumes the old standalone corruption test).
			reader.getEngine().jobs().invokeOperation(
				"v/ops/dlfs/write",
				Maps.of("drive", "health-vault", "path", "after-restart.txt", "content", "ok"),
				RequestContext.of(ALICE_DID)
			).awaitResult(5000);
			reader.getEngine().flush();
			assertEquals("ok", readDLFS(reader, "health-vault", "after-restart.txt"),
				"writes after restart must still work — etch must not be corrupted");
		} finally {
			reader.close();
		}
	}
}
