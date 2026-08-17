package covia.venue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Temp directories for tests that must survive across venue restarts within
 * one test and therefore cannot use {@code @TempDir}'s per-test lifetime —
 * but must not survive the test JVM: every directory handed out here is
 * deleted recursively when the JVM exits (a directory's own
 * {@code deleteOnExit} only removes an EMPTY directory, which is how ~65 MB
 * etch stores were accumulating in the system temp folder on every run).
 */
public final class TestTemp {

	private static final List<Path> DIRS = new ArrayList<>();
	private static boolean hooked = false;

	private TestTemp() {}

	/** A fresh temp directory with the given prefix, deleted recursively at JVM exit. */
	public static synchronized Path dir(String prefix) throws IOException {
		Path dir = Files.createTempDirectory(prefix);
		DIRS.add(dir);
		if (!hooked) {
			hooked = true;
			Runtime.getRuntime().addShutdownHook(new Thread(TestTemp::cleanup, "TestTemp-cleanup"));
		}
		return dir;
	}

	/** Best-effort recursive delete, now rather than at exit. */
	public static void delete(Path dir) {
		if (dir == null || !Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// A still-mapped store on Windows: the exit hook retries.
				}
			});
		} catch (IOException ignored) {
			// nothing more to do
		}
	}

	private static synchronized void cleanup() {
		for (Path dir : DIRS) delete(dir);
	}
}
