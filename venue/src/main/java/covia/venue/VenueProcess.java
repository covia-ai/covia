package covia.venue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.Hashing;
import covia.grid.Job;
import covia.venue.server.VenueServer;

/** Process-level graceful restart and executable-jar handoff for MainVenue. */
public final class VenueProcess {

	private static final Logger log = LoggerFactory.getLogger(VenueProcess.class);
	private static final String MAIN_CLASS = MainVenue.class.getName();
	private static final long DEFAULT_ACK_DELAY_MILLIS = 500;
	private static final long HELPER_READY_TIMEOUT_MILLIS = 5_000;

	/** Accepted restart plan returned before the requesting Job completes. */
	public record RestartPlan(Path successorJar, Path fallbackJar,
			long startupTimeoutMillis, String successorSha256, String fallbackSha256) {}

	@FunctionalInterface
	interface HelperLauncher {
		VenueRelauncher.Child launch(Path javaExecutable, Path currentJar,
			Path specFile) throws IOException;
	}

	@FunctionalInterface
	interface ExitHandler {
		void exit(int status);
	}

	private final Path currentJar;
	private final Path javaExecutable;
	private final List<String> jvmArgs;
	private final List<String> mainArgs;
	private final HelperLauncher helperLauncher;
	private final ExitHandler exitHandler;
	private final long acknowledgementDelayMillis;
	private final List<Runnable> closeActions = new CopyOnWriteArrayList<>();
	private final AtomicBoolean armed = new AtomicBoolean(false);
	private final AtomicBoolean restartRequested = new AtomicBoolean(false);

	VenueProcess(Path currentJar, Path javaExecutable, List<String> jvmArgs,
			List<String> mainArgs, HelperLauncher helperLauncher,
			ExitHandler exitHandler, long acknowledgementDelayMillis) {
		this.currentJar = currentJar;
		this.javaExecutable = javaExecutable;
		this.jvmArgs = List.copyOf(jvmArgs);
		this.mainArgs = List.copyOf(mainArgs);
		this.helperLauncher = helperLauncher;
		this.exitHandler = exitHandler;
		this.acknowledgementDelayMillis = acknowledgementDelayMillis;
	}

	/** Creates process control for the currently running executable venue jar. */
	public static VenueProcess create(String[] args) {
		Path jar = locateCurrentJar();
		Path javaExecutable = locateJavaExecutable();
		List<String> jvm = ManagementFactory.getRuntimeMXBean().getInputArguments();
		return new VenueProcess(jar, javaExecutable, jvm,
			List.copyOf(java.util.Arrays.asList(args)),
			VenueProcess::launchHelper, System::exit, DEFAULT_ACK_DELAY_MILLIS);
	}

	/** Installs process control on a venue and includes it in process shutdown. */
	public void manage(VenueServer server) {
		if (armed.get()) throw new IllegalStateException("Cannot add a venue after process control is armed");
		server.getEngine().setProcessControl(this);
		closeActions.add(server::close);
	}

	/** Test/embedder seam for resources owned by the process lifecycle. */
	void addCloseAction(Runnable closeAction) {
		if (armed.get()) throw new IllegalStateException("Cannot add resources after process control is armed");
		closeActions.add(java.util.Objects.requireNonNull(closeAction));
	}

	/** Enables restart requests after all venues in the process have launched. */
	public void arm() {
		armed.set(true);
	}

	/**
	 * Validates and schedules a restart after the requesting Job has committed
	 * its successful result. Only one process restart may be pending.
	 */
	public RestartPlan requestRestart(String successor, String sha256,
			long startupTimeoutMillis, Job job) {
		if (!armed.get()) throw new IllegalStateException("Venue process startup is not complete");
		if (currentJar == null) {
			throw new IllegalStateException(
				"Process restart requires MainVenue to be running from an executable jar");
		}
		if (job == null) throw new IllegalStateException("Process restart requires a Job context");
		if (startupTimeoutMillis < 1_000 || startupTimeoutMillis > 300_000) {
			throw new IllegalArgumentException("startupTimeout must be between 1000 and 300000 ms");
		}

		Path target = (successor == null || successor.isBlank())
			? currentJar : Path.of(successor).toAbsolutePath().normalize();
		validateVenueJar(currentJar);
		validateVenueJar(target);
		String targetDigest = sha256(target);
		if (sha256 != null && !sha256.isBlank()
				&& !targetDigest.equalsIgnoreCase(sha256)) {
			throw new IllegalArgumentException("Venue jar integrity check failed for " + target
				+ ": expected sha256 " + sha256 + ", got " + targetDigest);
		}
		String fallbackDigest = target.equals(currentJar) ? targetDigest : sha256(currentJar);
		if (!restartRequested.compareAndSet(false, true)) {
			throw new IllegalStateException("A venue process restart is already pending");
		}

		RestartPlan plan = new RestartPlan(target, currentJar, startupTimeoutMillis,
			targetDigest, fallbackDigest);
		job.future().whenComplete((ignored, failure) -> {
			if (failure != null) {
				restartRequested.set(false);
				log.error("Restart request Job failed before handoff", failure);
				return;
			}
			Thread.ofPlatform().name("covia-process-handoff").daemon(false)
				.start(() -> handoff(plan));
		});
		return plan;
	}

	private void handoff(RestartPlan plan) {
		Path specFile = null;
		VenueRelauncher.Child helper = null;
		boolean helperAccepted = false;
		try {
			if (acknowledgementDelayMillis > 0) Thread.sleep(acknowledgementDelayMillis);
			Path dir = Files.createTempDirectory("covia-handoff-");
			Path helperReady = dir.resolve("helper.ready");
			specFile = dir.resolve("handoff.properties");
			VenueRelauncher.LaunchSpec spec = new VenueRelauncher.LaunchSpec(
				javaExecutable, jvmArgs, mainArgs, plan.successorJar(), plan.fallbackJar(),
				plan.successorSha256(), plan.fallbackSha256(), ProcessHandle.current().pid(),
				plan.startupTimeoutMillis(), helperReady);
			VenueRelauncher.writeSpec(specFile, spec);

			helper = helperLauncher.launch(
				javaExecutable, currentJar, specFile);
			if (!awaitReady(helperReady, helper, HELPER_READY_TIMEOUT_MILLIS)) {
				stop(helper);
				restartRequested.set(false);
				log.error("Relaunch helper did not become ready; venue remains running");
				return;
			}
			helperAccepted = true;

			log.info("Relaunch helper pid {} ready; closing {} venue(s)",
				helper.pid(), closeActions.size());
			for (int i = closeActions.size() - 1; i >= 0; i--) {
				try {
					closeActions.get(i).run();
				} catch (Throwable t) {
					log.warn("Venue close during process handoff failed", t);
				}
			}
			exitHandler.exit(0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			restartRequested.set(false);
			log.error("Venue process handoff interrupted; venue remains running", e);
		} catch (Throwable t) {
			restartRequested.set(false);
			log.error("Venue process handoff failed; venue remains running", t);
		} finally {
			if (!helperAccepted) {
				if (helper != null) stop(helper);
				if (specFile != null) VenueRelauncher.cleanup(specFile);
			}
		}
	}

	private static VenueRelauncher.Child launchHelper(Path javaExecutable,
			Path currentJar, Path specFile) throws IOException {
		Process process = new ProcessBuilder(
			javaExecutable.toString(), "-cp", currentJar.toString(),
			VenueRelauncher.class.getName(), specFile.toString())
			.inheritIO()
			.start();
		return new VenueRelauncher.Child() {
			@Override public long pid() { return process.pid(); }
			@Override public boolean isAlive() { return process.isAlive(); }
			@Override public boolean waitFor(long timeoutMillis) throws InterruptedException {
				return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
			}
			@Override public void destroy() { process.destroy(); }
			@Override public void destroyForcibly() { process.destroyForcibly(); }
		};
	}

	private static boolean awaitReady(Path marker, VenueRelauncher.Child child,
			long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (System.nanoTime() < deadline) {
			try {
				if (Files.isRegularFile(marker)
						&& Long.toString(child.pid()).equals(Files.readString(marker).trim())
						&& child.isAlive()) return true;
			} catch (IOException ignored) {
				// Marker may be between create and write; retry until the deadline.
			}
			if (!child.isAlive()) return false;
			Thread.sleep(50);
		}
		return false;
	}

	private static void stop(VenueRelauncher.Child child) {
		if (!child.isAlive()) return;
		child.destroy();
		try {
			if (!child.waitFor(2_000)) child.destroyForcibly();
		} catch (InterruptedException e) {
			child.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static void validateVenueJar(Path jar) {
		if (jar == null || !Files.isRegularFile(jar)) {
			throw new IllegalArgumentException("Venue jar not found: " + jar);
		}
		try (JarFile jf = new JarFile(jar.toFile())) {
			String main = (jf.getManifest() != null)
				? jf.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS)
				: null;
			if (!MAIN_CLASS.equals(main)) {
				throw new IllegalArgumentException(
					"Jar is not an executable Covia venue (Main-Class must be " + MAIN_CLASS + "): " + jar);
			}
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot read venue jar: " + jar, e);
		}
	}

	private static String sha256(Path jar) {
		try {
			return Hashing.sha256(Files.readAllBytes(jar)).toHexString();
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot hash venue jar: " + jar, e);
		}
	}

	private static Path locateCurrentJar() {
		try {
			URI location = MainVenue.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			Path path = Path.of(location).toAbsolutePath().normalize();
			return Files.isRegularFile(path) ? path : null;
		} catch (Exception e) {
			log.warn("Cannot locate the current venue jar; process restart will be unavailable", e);
			return null;
		}
	}

	private static Path locateJavaExecutable() {
		String executable = System.getProperty("os.name", "").startsWith("Windows")
			? "java.exe" : "java";
		Path path = Path.of(System.getProperty("java.home"), "bin", executable);
		return Files.isRegularFile(path) ? path.toAbsolutePath().normalize() : Path.of(executable);
	}
}
