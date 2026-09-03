package covia.venue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Small process handoff helper used by {@link VenueProcess}.
 *
 * <p>The running venue starts this class from its current jar, closes every
 * venue and exits. The helper waits for that JVM to be gone before starting
 * the successor, so the old shutdown hooks never overlap a replacement using
 * the same port or store. A successor signals readiness only after
 * {@link MainVenue} has launched every configured venue. If it exits or times
 * out before then, the helper starts the previous jar as a fallback.</p>
 */
public final class VenueRelauncher {

	static final String READY_FILE_ENV = "COVIA_HANDOFF_READY_FILE";
	private static final String VERSION = "1";
	private static final long POLL_MILLIS = 100;
	private static final long STOP_TIMEOUT_MILLIS = 5_000;

	private VenueRelauncher() {}

	record LaunchSpec(Path javaExecutable, List<String> jvmArgs,
			List<String> mainArgs, Path successorJar, Path fallbackJar,
			String successorSha256, String fallbackSha256, long parentPid,
			long startupTimeoutMillis, Path helperReadyFile) {}

	interface Child {
		long pid();
		boolean isAlive();
		boolean waitFor(long timeoutMillis) throws InterruptedException;
		void destroy();
		void destroyForcibly();
	}

	@FunctionalInterface
	interface Launcher {
		Child launch(LaunchSpec spec, Path jar, Path readyFile) throws IOException;
	}

	private static final class ProcessChild implements Child {
		private final Process process;

		ProcessChild(Process process) {
			this.process = process;
		}

		@Override public long pid() { return process.pid(); }
		@Override public boolean isAlive() { return process.isAlive(); }
		@Override public boolean waitFor(long timeoutMillis) throws InterruptedException {
			return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
		}
		@Override public void destroy() { process.destroy(); }
		@Override public void destroyForcibly() { process.destroyForcibly(); }
	}

	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Usage: VenueRelauncher <handoff.properties>");
			System.exit(64);
			return;
		}
		Path specFile = Path.of(args[0]).toAbsolutePath().normalize();
		int exit;
		try {
			LaunchSpec spec = readSpec(specFile);
			signalReady(spec.helperReadyFile());
			waitForParent(spec.parentPid());
			exit = run(spec, System.err::println);
		} catch (Throwable t) {
			System.err.println("Covia relaunch helper failed: " + t);
			t.printStackTrace(System.err);
			exit = 70;
		} finally {
			cleanup(specFile);
		}
		System.exit(exit);
	}

	/** Signals a predecessor that this MainVenue process has fully started. */
	static void signalMainVenueReady() {
		String path = System.getenv(READY_FILE_ENV);
		if (path == null || path.isBlank()) return;
		try {
			signalReady(Path.of(path));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot signal venue readiness: " + path, e);
		}
	}

	/**
	 * Runs the handoff, reporting progress and failures to {@code report}. The
	 * helper is normally its own process with nothing but stderr to speak to,
	 * so {@link #main} reports there; in-process callers (tests) supply a sink
	 * and assert on it instead of writing failure-shaped lines to the console.
	 */
	static int run(LaunchSpec spec, Launcher launcher, Consumer<String> report) throws IOException {
		Path dir = spec.helperReadyFile().getParent();
		Path successorReady = dir.resolve("successor.ready");
		if (startAndAwait(spec, spec.successorJar(), spec.successorSha256(),
				successorReady, launcher, report)) return 0;

		report.accept("Covia successor failed to become ready; starting fallback jar: "
			+ spec.fallbackJar());
		Path fallbackReady = dir.resolve("fallback.ready");
		if (startAndAwait(spec, spec.fallbackJar(), spec.fallbackSha256(),
				fallbackReady, launcher, report)) return 0;

		report.accept("Covia fallback failed to become ready: " + spec.fallbackJar());
		return 70;
	}

	/** Runs the real ProcessBuilder handoff; package-visible for the forked-JVM test. */
	static int run(LaunchSpec spec, Consumer<String> report) throws IOException {
		return run(spec, VenueRelauncher::launch, report);
	}

	private static boolean startAndAwait(LaunchSpec spec, Path jar, String expectedSha256,
			Path readyFile,
			Launcher launcher, Consumer<String> report) throws IOException {
		Files.deleteIfExists(readyFile);
		if (!verifySha256(jar, expectedSha256, report)) {
			report.accept("Venue jar changed after restart was accepted; refusing to execute: " + jar);
			return false;
		}
		Child child;
		try {
			child = launcher.launch(spec, jar, readyFile);
		} catch (IOException e) {
			report.accept("Cannot start venue jar " + jar + ": " + e);
			return false;
		}
		report.accept("Started venue jar " + jar + " as pid " + child.pid());

		long deadline = System.nanoTime()
			+ TimeUnit.MILLISECONDS.toNanos(spec.startupTimeoutMillis());
		try {
			while (System.nanoTime() < deadline) {
				if (readyFrom(readyFile, child.pid()) && child.isAlive()) return true;
				if (!child.isAlive()) return false;
				Thread.sleep(POLL_MILLIS);
			}
			report.accept("Venue startup timed out after "
				+ spec.startupTimeoutMillis() + " ms: " + jar);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			if (!readyFrom(readyFile, child.pid()) || !child.isAlive()) stop(child);
		}
	}

	private static Child launch(LaunchSpec spec, Path jar, Path readyFile) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(spec.javaExecutable().toString());
		command.addAll(spec.jvmArgs());
		command.add("-jar");
		command.add(jar.toString());
		command.addAll(spec.mainArgs());
		ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
		builder.environment().put(READY_FILE_ENV, readyFile.toString());
		return new ProcessChild(builder.start());
	}

	private static void stop(Child child) {
		if (!child.isAlive()) return;
		child.destroy();
		try {
			if (!child.waitFor(STOP_TIMEOUT_MILLIS)) child.destroyForcibly();
		} catch (InterruptedException e) {
			child.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static boolean readyFrom(Path readyFile, long pid) {
		try {
			return Files.isRegularFile(readyFile)
				&& Long.toString(pid).equals(Files.readString(readyFile).trim());
		} catch (IOException e) {
			return false;
		}
	}

	private static void waitForParent(long pid) {
		ProcessHandle.of(pid).ifPresent(parent -> {
			if (!parent.isAlive()) return;
			try {
				parent.onExit().get();
			} catch (Exception e) {
				throw new IllegalStateException("Interrupted while waiting for old venue pid " + pid, e);
			}
		});
	}

	static void writeSpec(Path file, LaunchSpec spec) throws IOException {
		Properties p = new Properties();
		p.setProperty("version", VERSION);
		p.setProperty("java", spec.javaExecutable().toString());
		p.setProperty("successor", spec.successorJar().toString());
		p.setProperty("fallback", spec.fallbackJar().toString());
		p.setProperty("successorSha256", spec.successorSha256());
		p.setProperty("fallbackSha256", spec.fallbackSha256());
		p.setProperty("parentPid", Long.toString(spec.parentPid()));
		p.setProperty("startupTimeoutMillis", Long.toString(spec.startupTimeoutMillis()));
		p.setProperty("helperReady", spec.helperReadyFile().toString());
		putList(p, "jvmArg", spec.jvmArgs());
		putList(p, "mainArg", spec.mainArgs());
		try (OutputStream out = Files.newOutputStream(file,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			p.store(out, "Covia process handoff");
		}
		restrictOwnerAccess(file);
	}

	static LaunchSpec readSpec(Path file) throws IOException {
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		}
		if (!VERSION.equals(p.getProperty("version"))) {
			throw new IOException("Unsupported handoff version: " + p.getProperty("version"));
		}
		return new LaunchSpec(
			Path.of(required(p, "java")),
			getList(p, "jvmArg"),
			getList(p, "mainArg"),
			Path.of(required(p, "successor")),
			Path.of(required(p, "fallback")),
			required(p, "successorSha256"),
			required(p, "fallbackSha256"),
			Long.parseLong(required(p, "parentPid")),
			Long.parseLong(required(p, "startupTimeoutMillis")),
			Path.of(required(p, "helperReady")));
	}

	private static void putList(Properties p, String key, List<String> values) {
		p.setProperty(key + ".count", Integer.toString(values.size()));
		for (int i = 0; i < values.size(); i++) p.setProperty(key + "." + i, values.get(i));
	}

	private static List<String> getList(Properties p, String key) throws IOException {
		int count = Integer.parseInt(required(p, key + ".count"));
		List<String> result = new ArrayList<>(count);
		for (int i = 0; i < count; i++) result.add(required(p, key + "." + i));
		return List.copyOf(result);
	}

	private static String required(Properties p, String key) throws IOException {
		String value = p.getProperty(key);
		if (value == null) throw new IOException("Missing handoff property: " + key);
		return value;
	}

	private static void signalReady(Path file) throws IOException {
		Path parent = file.toAbsolutePath().normalize().getParent();
		if (parent != null) Files.createDirectories(parent);
		Files.writeString(file, Long.toString(ProcessHandle.current().pid()),
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	private static boolean verifySha256(Path jar, String expected, Consumer<String> report) {
		try (InputStream in = Files.newInputStream(jar)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			for (int n; (n = in.read(buffer)) >= 0;) {
				if (n > 0) digest.update(buffer, 0, n);
			}
			return java.util.HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected);
		} catch (Exception e) {
			report.accept("Cannot verify venue jar " + jar + ": " + e);
			return false;
		}
	}

	private static void restrictOwnerAccess(Path file) {
		try {
			Files.setPosixFilePermissions(file,
				java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Windows ACLs and other providers use their normal temp-directory policy.
		}
	}

	static void cleanup(Path specFile) {
		Path dir = specFile.getParent();
		try { Files.deleteIfExists(dir.resolve("helper.ready")); } catch (IOException ignored) {}
		try { Files.deleteIfExists(dir.resolve("successor.ready")); } catch (IOException ignored) {}
		try { Files.deleteIfExists(dir.resolve("fallback.ready")); } catch (IOException ignored) {}
		try { Files.deleteIfExists(specFile); } catch (IOException ignored) {}
		try { Files.deleteIfExists(dir); } catch (IOException ignored) {}
	}
}
