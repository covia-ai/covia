package covia.venue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import convex.etch.EtchStore;

/**
 * Temporary files for tests, above all Etch stores. A test that needs an Etch
 * store of its own gets it from {@link #etch}, or from {@link #etchFile} when
 * a venue opens the store itself from {@code Config.STORE}.
 *
 * <p>Give a test its own store only when it tests the store or a venue's life
 * on one: restart, recovery, GC, the root, identity, a release's store. Any
 * other test uses the shared {@link TestEngine} (Etch-backed, so Etch is still
 * exercised under the suite's load), an in-memory engine, or a server on
 * {@code Config.STORE "memory"}. A server with no store configured opens a
 * fresh temp Etch store, so a test server always names its store or adopts
 * one from here, as the shared {@link TestServer} does.
 *
 * <p>Not a JUnit {@code @TempDir}: on Java 21 (no FFM mapper) Etch cannot
 * unmap on close, so on Windows a closed store's file stays pinned until its
 * buffers are garbage-collected, and {@code @TempDir}'s mandatory cleanup
 * fails the test. Not {@code EtchStore.createTemp} either: it leaves the file
 * in the system temp folder to {@code deleteOnExit}, which fails the same way
 * on Windows, never runs for a killed JVM, and never sees the files GC writes
 * beside a store.
 *
 * <p>Everything lives under one directory per test JVM,
 * {@code <java.io.tmpdir>/covia-tests/run-<pid>-<start>}. The JVM deletes it
 * at exit as far as it can. Whatever survives, a still-mapped file or a killed
 * JVM's run, is removed by the next test JVM, which deletes the run
 * directories of processes no longer running.
 */
public final class TestTemp {

	private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "covia-tests");
	private static final AtomicLong DIRS = new AtomicLong();
	private static final Path RUN = startRun();

	private TestTemp() {}

	/**
	 * A new Etch store, in a directory of its own. Reopen it by path with
	 * {@code EtchStore.create(store.getFile())}; files written beside it, such
	 * as GC's successors, backups and restores, go in the same directory.
	 */
	public static EtchStore etch(String name) {
		try {
			return EtchStore.create(etchFile(name));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The path of a new Etch store, not yet created, in a directory of its own. */
	public static File etchFile(String name) {
		return dir(name).resolve(name + ".etch").toFile();
	}

	/** A new directory, for a test's files other than a single store. */
	public static Path dir(String name) {
		try {
			return Files.createDirectory(RUN.resolve(name + "-" + DIRS.incrementAndGet()));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Best-effort recursive delete, now rather than at exit. */
	public static void delete(Path dir) {
		if (dir == null || !Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// A still-mapped store on Windows: the next run removes it.
				}
			});
		} catch (IOException ignored) {
			// nothing more to do
		}
	}

	private static Path startRun() {
		try {
			Files.createDirectories(ROOT);
			try (DirectoryStream<Path> runs = Files.newDirectoryStream(ROOT, "run-*")) {
				for (Path run : runs) {
					if (!running(run.getFileName().toString())) delete(run);
				}
			}
			Path run = Files.createDirectory(ROOT.resolve(runName(ProcessHandle.current())));
			Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(run), "TestTemp-cleanup"));
			return run;
		} catch (IOException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static String runName(ProcessHandle process) {
		return "run-" + process.pid() + "-" + startMillis(process);
	}

	private static long startMillis(ProcessHandle process) {
		return process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
	}

	/** Whether the JVM that made a run directory is still running; a name not ours counts as running. */
	private static boolean running(String runName) {
		String[] parts = runName.split("-");
		if (parts.length != 3) return true;
		try {
			long pid = Long.parseLong(parts[1]);
			long start = Long.parseLong(parts[2]);
			return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
				.map(p -> startMillis(p) == start).orElse(false);
		} catch (NumberFormatException e) {
			return true;
		}
	}
}
