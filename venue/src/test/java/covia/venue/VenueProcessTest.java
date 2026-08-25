package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.Hashing;
import convex.core.data.Maps;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

class VenueProcessTest {

	@TempDir
	Path tempDir;

	@Test
	void completedRestartJobStartsHelperThenClosesAndExits() throws Exception {
		Path current = venueJar("current.jar");
		Path successor = venueJar("successor.jar");
		List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
		CountDownLatch exited = new CountDownLatch(1);
		Path[] handoffDir = new Path[1];

		VenueProcess process = new VenueProcess(current, Path.of("java"),
			List.of("-Xmx1g"), List.of("config.json"),
			(java, jar, specFile) -> {
				events.add("helper");
				VenueRelauncher.LaunchSpec spec = VenueRelauncher.readSpec(specFile);
				handoffDir[0] = specFile.getParent();
				FakeChild helper = new FakeChild(42, true);
				Files.writeString(spec.helperReadyFile(), Long.toString(helper.pid()));
				assertEquals(current, jar);
				assertEquals(successor, spec.successorJar());
				assertEquals(current, spec.fallbackJar());
				assertEquals(List.of("-Xmx1g"), spec.jvmArgs());
				assertEquals(List.of("config.json"), spec.mainArgs());
				return helper;
			},
			status -> {
				events.add("exit:" + status);
				exited.countDown();
			}, 0);
		process.addCloseAction(() -> events.add("close"));
		process.arm();

		Job job = new Job(Maps.of(Fields.STATUS, Status.PENDING));
		VenueProcess.RestartPlan plan = process.requestRestart(
			successor.toString(), null, 10_000, job);
		assertEquals(successor, plan.successorJar());
		assertFalse(exited.await(100, TimeUnit.MILLISECONDS),
			"handoff must wait until the requesting Job has committed its result");

		job.completeWith(Maps.empty());
		assertTrue(exited.await(5, TimeUnit.SECONDS));
		assertEquals(List.of("helper", "close", "exit:0"), events);

		deleteHandoff(handoffDir[0]);
	}

	@Test
	void successorFailureStartsCurrentJarFallback() throws Exception {
		Path current = venueJar("current.jar");
		Path successor = venueJar("successor.jar");
		Path dir = Files.createDirectory(tempDir.resolve("handoff"));
		VenueRelauncher.LaunchSpec spec = new VenueRelauncher.LaunchSpec(
			Path.of("java"), List.of(), List.of(), successor, current,
			sha(successor), sha(current), Long.MAX_VALUE, 1_000, dir.resolve("helper.ready"));
		List<Path> attempts = new ArrayList<>();

		int exit = VenueRelauncher.run(spec, (ignored, jar, ready) -> {
			attempts.add(jar);
			if (jar.equals(successor)) return new FakeChild(10, false);
			FakeChild fallback = new FakeChild(11, true);
			Files.writeString(ready, Long.toString(fallback.pid()));
			return fallback;
		});

		assertEquals(0, exit);
		assertEquals(List.of(successor, current), attempts);
	}

	@Test
	void bothLaunchFailuresReturnSoftwareError() throws Exception {
		Path current = venueJar("current.jar");
		Path successor = venueJar("successor.jar");
		Path dir = Files.createDirectory(tempDir.resolve("handoff"));
		VenueRelauncher.LaunchSpec spec = new VenueRelauncher.LaunchSpec(
			Path.of("java"), List.of(), List.of(), successor, current,
			sha(successor), sha(current), Long.MAX_VALUE, 1_000, dir.resolve("helper.ready"));

		int exit = VenueRelauncher.run(spec,
			(ignored, jar, ready) -> new FakeChild(20, false));

		assertEquals(70, exit);
	}

	@Test
	void changedSuccessorIsNotExecutedAndFallsBack() throws Exception {
		Path current = venueJar("current.jar");
		Path successor = venueJar("successor.jar");
		Path dir = Files.createDirectory(tempDir.resolve("handoff"));
		VenueRelauncher.LaunchSpec spec = new VenueRelauncher.LaunchSpec(
			Path.of("java"), List.of(), List.of(), successor, current,
			sha(successor), sha(current), Long.MAX_VALUE, 1_000, dir.resolve("helper.ready"));
		Files.writeString(successor, "replaced after acceptance");
		List<Path> attempts = new ArrayList<>();

		int exit = VenueRelauncher.run(spec, (ignored, jar, ready) -> {
			attempts.add(jar);
			FakeChild fallback = new FakeChild(31, true);
			Files.writeString(ready, Long.toString(fallback.pid()));
			return fallback;
		});

		assertEquals(0, exit);
		assertEquals(List.of(current), attempts,
			"the mutated successor must be rejected before ProcessBuilder sees it");
	}

	@Test
	void realJavaProcessSignalsReadiness() throws Exception {
		Path probeJar = executableProbeJar();
		Path result = tempDir.resolve("probe.pid");
		Path dir = Files.createDirectory(tempDir.resolve("real-handoff"));
		String javaName = System.getProperty("os.name", "").startsWith("Windows")
			? "java.exe" : "java";
		Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
		VenueRelauncher.LaunchSpec spec = new VenueRelauncher.LaunchSpec(
			java, List.of(), List.of(result.toString()), probeJar, probeJar,
			sha(probeJar), sha(probeJar), Long.MAX_VALUE, 5_000,
			dir.resolve("helper.ready"));

		assertEquals(0, VenueRelauncher.run(spec));
		long pid = Long.parseLong(Files.readString(result).trim());
		ProcessHandle child = ProcessHandle.of(pid).orElseThrow();
		try {
			assertTrue(child.isAlive(), "readiness is accepted only while the child remains alive");
		} finally {
			if (child.isAlive()) child.destroyForcibly();
			child.onExit().get(5, TimeUnit.SECONDS);
		}
		awaitDelete(probeJar);
	}

	@Test
	void jarAndHashAreValidatedBeforeRestartIsAccepted() throws Exception {
		Path current = venueJar("current.jar");
		Path successor = venueJar("successor.jar");
		VenueProcess process = new VenueProcess(current, Path.of("java"),
			List.of(), List.of(), (java, jar, spec) -> new FakeChild(1, false),
			status -> {}, 0);
		process.arm();
		Job job = new Job(Maps.of(Fields.STATUS, Status.PENDING));

		assertThrows(IllegalArgumentException.class,
			() -> process.requestRestart(tempDir.resolve("missing.jar").toString(), null,
				10_000, job));
		assertThrows(IllegalArgumentException.class,
			() -> process.requestRestart(successor.toString(), "00", 10_000, job));

		String sha = Hashing.sha256(Files.readAllBytes(successor)).toHexString();
		VenueProcess.RestartPlan accepted = process.requestRestart(
			successor.toString(), sha, 10_000, job);
		assertEquals(successor, accepted.successorJar());
	}

	@Test
	void onlyOneRestartMayBePending() throws Exception {
		Path current = venueJar("current.jar");
		VenueProcess process = new VenueProcess(current, Path.of("java"),
			List.of(), List.of(), (java, jar, spec) -> new FakeChild(1, false),
			status -> {}, 0);
		process.arm();

		process.requestRestart(null, null, 10_000,
			new Job(Maps.of(Fields.STATUS, Status.PENDING)));
		assertThrows(IllegalStateException.class, () -> process.requestRestart(
			null, null, 10_000, new Job(Maps.of(Fields.STATUS, Status.PENDING))));
	}

	private Path venueJar(String name) throws Exception {
		Path jar = tempDir.resolve(name).toAbsolutePath().normalize();
		Manifest manifest = new Manifest();
		Attributes attributes = manifest.getMainAttributes();
		attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		attributes.put(Attributes.Name.MAIN_CLASS, MainVenue.class.getName());
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			// Manifest-only jar is sufficient for pre-shutdown validation tests.
		}
		return jar;
	}

	private Path executableProbeJar() throws Exception {
		Path jar = tempDir.resolve("probe.jar").toAbsolutePath().normalize();
		Manifest manifest = new Manifest();
		Attributes attributes = manifest.getMainAttributes();
		attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		attributes.put(Attributes.Name.MAIN_CLASS, RelaunchProbe.class.getName());
		String resource = "/" + RelaunchProbe.class.getName().replace('.', '/') + ".class";
		String entryName = resource.substring(1);
		try (var classBytes = VenueProcessTest.class.getResourceAsStream(resource);
				JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			if (classBytes == null) throw new IllegalStateException("Missing test class bytes: " + resource);
			out.putNextEntry(new JarEntry(entryName));
			classBytes.transferTo(out);
			out.closeEntry();
		}
		return jar;
	}

	private static String sha(Path jar) throws Exception {
		return Hashing.sha256(Files.readAllBytes(jar)).toHexString();
	}

	private static void awaitDelete(Path file) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true) {
			try {
				Files.deleteIfExists(file);
				return;
			} catch (java.nio.file.FileSystemException e) {
				if (System.nanoTime() >= deadline) throw e;
				Thread.sleep(25);
			}
		}
	}

	private static void deleteHandoff(Path dir) throws Exception {
		if (dir == null) return;
		Files.deleteIfExists(dir.resolve("helper.ready"));
		Files.deleteIfExists(dir.resolve("handoff.properties"));
		Files.deleteIfExists(dir);
	}

	private static final class FakeChild implements VenueRelauncher.Child {
		private final long pid;
		private final AtomicBoolean alive;

		FakeChild(long pid, boolean alive) {
			this.pid = pid;
			this.alive = new AtomicBoolean(alive);
		}

		@Override public long pid() { return pid; }
		@Override public boolean isAlive() { return alive.get(); }
		@Override public boolean waitFor(long timeoutMillis) { return !alive.get(); }
		@Override public void destroy() { alive.set(false); }
		@Override public void destroyForcibly() { alive.set(false); }
	}

	/** Minimal executable-JAR main used by the real ProcessBuilder test. */
	public static final class RelaunchProbe {
		public static void main(String[] args) throws Exception {
			long pid = ProcessHandle.current().pid();
			Files.writeString(Path.of(System.getenv(VenueRelauncher.READY_FILE_ENV)),
				Long.toString(pid));
			Files.writeString(Path.of(args[0]), Long.toString(pid));
			Thread.sleep(30_000);
		}
	}
}
