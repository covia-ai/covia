package covia.venue;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Supplier;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.Maps;
import convex.core.data.AString;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;

/**
 * Shared {@link Engine} singleton for venue unit tests that don't need their
 * own isolated persistence or restart cycle.
 *
 * <p>Tests previously created a fresh Engine per test method via
 * {@code @BeforeEach}, which calls {@code Engine.createTemp(null) +
 * Engine.addDemoAssets(engine)}. This is expensive: registering all 18
 * adapters and reading ~100 JSON resources adds ~10-15ms per test, which
 * accumulates to seconds across hundreds of tests.</p>
 *
 * <p>Use {@link #ENGINE} from {@code @BeforeEach} (or directly as a static
 * field) and isolate per-test state via {@link #uniqueDID(String)} — each
 * test gets its own user namespace, so writes don't collide.</p>
 *
 * <p><b>When NOT to use:</b> tests that exercise persistence/restart.
 * A restart in memory takes {@link #restartable}; a venue over a store or host
 * of the test's own takes an {@link #application}, which is the one place a
 * test's venue gets its clock. {@code VenueServer.launch} is for tests over HTTP.
 * Examples: {@link EnginePersistenceTest}, {@link VenueServerPersistenceTest},
 * {@link VenueRestartTest}, {@link DLFSPersistenceTest},
	 * {@link EngineSchedulerRebuildTest}. Tests that need bespoke engine
	 * config (e.g. {@code FileAdapterTest} with custom {@code @TempDir}
	 * jail roots, or {@code OAuthTest}'s inline {@code createTemp} for
	 * auth-config variants), tests that change the adapters others see, and
	 * tests that write adapter state under a name no adapter owns also belong
	 * in this category: such a name leaves every later user deletion on the
	 * venue pending that adapter. A class that needs its own venue for one of
	 * these shares one across its tests unless a test needs it fresh.</p>
 *
 * <p><b>Per-test isolation pattern:</b></p>
 * <pre>{@code
 * private static final Engine engine = TestEngine.ENGINE;
 * private AString did;
 * private RequestContext ctx;
 *
 * @BeforeEach
 * public void setup(TestInfo info) {
 *     did = TestEngine.uniqueDID(info);
 *     ctx = RequestContext.of(did);
 * }
 * }</pre>
 */
public class TestEngine {

	/**
	 * Shared Engine instance for venue unit tests. Lazily initialised on
	 * first access. Static — survives the JVM, no per-test setup cost. Its
	 * clock is held at the time it starts and no update timer runs, so nothing
	 * falls due by itself. Tests share it, so none moves it: a test that moves
	 * time takes a venue of its own ({@link Engine#createTemp(DrivenClock, convex.core.data.AMap)}).
	 */
	public static final Engine ENGINE;

	/**
	 * The shared engine's store: a temp Etch store rather than memory, so
	 * online GC can be exercised on it under the suite's own concurrent load
	 * ({@code SharedEngineOnlineGcTest}, covia#452). A {@link TestTemp} store,
	 * so GC's successor files are cleaned up with it.
	 */
	public static final convex.etch.EtchStore STORE;
	private static volatile convex.etch.EtchStore collected;

	/**
	 * The shared engine's fictitious public hostname, which lets it host managed
	 * ({@code did:web}) users. Its own identity stays the key-derived did:key.
	 */
	public static final String HOSTNAME = "shared.test.covia.example";

	static {
		CoviaApplication application;
		try {
			STORE = TestTemp.etch("covia-test");
			convex.core.crypto.AKeyPair keyPair = convex.core.crypto.AKeyPair.generate();
			application = application(STORE);
			ENGINE = application.mount(keyPair, new Config(Maps.of(
				Config.HOSTNAME, Strings.create(HOSTNAME),
				Config.USERS, Maps.of(Config.AUTO_CREATE, true)))).start();
		} catch (java.io.IOException e) {
			throw new ExceptionInInitializerError(e);
		}
		Engine.addDemoAssets(ENGINE);
		// This class opened the host, so it runs the host's workers, as a venue
		// server does.
		var host = application.host();
		host.autoSync(covia.venue.server.VenueServer.SYNC_INTERVAL, application::publish);
		host.autoFlush(covia.venue.server.VenueServer.FLUSH_INTERVAL);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			application.close();
			try {
				host.close();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			STORE.close();
			convex.etch.EtchStore successor = collected;
			if (successor != null) successor.close();
		}, "test-engine-shutdown"));
	}

	/**
	 * Writes a text file in the shared engine's temp file root ({@code tmp}, the
	 * root a venue with no configured roots has), making its directories. A
	 * class keeps its files in a directory named after itself, so they meet no
	 * other class's; running the class again overwrites them.
	 */
	public static void writeTempFile(String path, String text) {
		writeTempFile(path, "content", text);
	}

	/** Writes a binary file in the shared engine's temp file root; see {@link #writeTempFile(String, String)}. */
	public static void writeTempFile(String path, byte[] bytes) {
		writeTempFile(path, "bytes", java.util.Base64.getEncoder().encodeToString(bytes));
	}

	private static void writeTempFile(String path, String field, String value) {
		int dir = path.lastIndexOf('/');
		if (dir > 0) {
			ENGINE.jobs().invokeOperation("v/ops/file/mkdir",
				Maps.of("root", "tmp", "path", path.substring(0, dir), "parents", true),
				ENGINE.venueContext()).awaitResult(5000);
		}
		ENGINE.jobs().invokeOperation("v/ops/file/write",
			Maps.of("root", "tmp", "path", path, field, value),
			ENGINE.venueContext()).awaitResult(5000);
	}

	/** Parks the successor of an online GC cycle on {@link #STORE} so it is closed cleanly at exit. */
	public static void adoptCollected(convex.etch.EtchStore successor) {
		collected = successor;
	}

	/** Which run of the tests this JVM is on; see {@link #nextRun()}. */
	private static final java.util.concurrent.atomic.AtomicInteger RUN = new java.util.concurrent.atomic.AtomicInteger(1);

	/**
	 * Starts another run of the tests in this JVM: from now on
	 * {@link #uniqueDID} gives every test a DID no earlier run used, so a
	 * class can run again on the shared engine without meeting the users,
	 * agents and sessions its last run left there. A harness that loops test
	 * classes in one JVM calls this between runs, while no test is running.
	 */
	public static void nextRun() {
		RUN.incrementAndGet();
	}

	/**
	 * Generates a unique user DID per test method based on the test class
	 * and method name. Use this in {@code @BeforeEach} so each test sees a
	 * fresh user namespace within the shared Engine.
	 *
	 * <p>The returned DID is deterministic for a given test name within a
	 * run — useful for debugging — but unique across all tests in the suite,
	 * and across runs (see {@link #nextRun()}).</p>
	 *
	 * @param info JUnit's TestInfo (inject via @BeforeEach parameter)
	 * @return A DID string like {@code "did:key:z6Mk-test-MyClass-myMethod"}
	 */
	public static AString uniqueDID(org.junit.jupiter.api.TestInfo info) {
		String className = info.getTestClass()
			.map(c -> c.getSimpleName()).orElse("Unknown");
		String methodName = info.getTestMethod()
			.map(m -> m.getName()).orElse("unknown");
		return uniqueDID(className + "-" + methodName);
	}

	/**
	 * Generates a unique DID from an arbitrary discriminator string.
	 * Use when {@link org.junit.jupiter.api.TestInfo} isn't available
	 * (e.g. in helper methods called from multiple tests). The first run
	 * gives {@code "did:key:z6Mk-test-<discriminator>"}; a later one appends
	 * {@code "-run<n>"}.
	 */
	public static AString uniqueDID(String discriminator) {
		int run = RUN.get();
		return Strings.create("did:key:z6Mk-test-" + discriminator + (run > 1 ? "-run" + run : ""));
	}

	/**
	 * A managed ({@code did:web}) user of the shared engine that no earlier run
	 * used; the counterpart of {@link #uniqueDID(String)} for a test that needs a
	 * user the venue hosts. The discriminator must be a valid username.
	 */
	public static AString uniqueManagedDID(String discriminator) {
		int run = RUN.get();
		return ENGINE.managedUserDID(Strings.create(discriminator + (run > 1 ? "-run" + run : "")));
	}

	// ========== Venues of a test's own ==========

	/** The clock of a venue a test makes for itself: held at the current time, so nothing fires by itself. */
	private static DrivenClock heldClock() {
		return DrivenClock.at(Instant.now());
	}

	/** An in-memory application of a test's own, on a held clock, with no venue mounted. */
	public static CoviaApplication application() {
		return application(heldClock());
	}

	/** As {@link #application()}, on this clock. */
	public static CoviaApplication application(VenueClock clock) {
		return application(LatticeHost.create(new MemoryStore()), clock);
	}

	/** As {@link #application()}, with this key pair's venue mounted in the default configuration. */
	public static CoviaApplication application(AKeyPair key) {
		CoviaApplication application = application();
		application.mount(key, new Config(null));
		return application;
	}

	/** An application of a test's own over this store, on a held clock, with no venue mounted. */
	public static CoviaApplication application(AStore store) throws IOException {
		return application(store, heldClock());
	}

	/** As {@link #application(AStore)}, on this clock. */
	public static CoviaApplication application(AStore store, VenueClock clock) throws IOException {
		return application(LatticeHost.open(store), clock);
	}

	/** As {@link #application(AStore)}, with this key pair's venue mounted in the default configuration. */
	public static CoviaApplication application(AStore store, AKeyPair key) throws IOException {
		CoviaApplication application = application(store);
		application.mount(key, new Config(null));
		return application;
	}

	/** An application of a test's own on this host, on a held clock, with no venue mounted. */
	public static CoviaApplication application(LatticeHost<Index<Keyword, ACell>> host) {
		return application(host, heldClock());
	}

	/** As {@link #application(LatticeHost)}, on this clock: every other form comes here. */
	public static CoviaApplication application(LatticeHost<Index<Keyword, ACell>> host, VenueClock clock) {
		return host.addApplication(new CoviaApplication(host, clock));
	}

	/** As {@link #application(LatticeHost)}, with this key pair's venue mounted in the default configuration. */
	public static CoviaApplication application(LatticeHost<Index<Keyword, ACell>> host, AKeyPair key) {
		CoviaApplication application = application(host);
		application.mount(key, new Config(null));
		return application;
	}

	/** A venue of a test's own that it can stop and start again; see {@link Restartable}. */
	public static Restartable restartable(AMap<AString, ACell> config) {
		return new Restartable(Cursors.root(Index.<Keyword, ACell>none()), AKeyPair.generate(), config);
	}

	/**
	 * One venue a test runs, stops and runs again over the same lattice state
	 * and key, as across a restart. Each run is a new application on the same
	 * held clock, which the test moves if time must pass ({@link #clock()}).
	 */
	public static final class Restartable {
		private final Root<Index<Keyword, ACell>> state;
		private final AKeyPair key;
		private final AMap<AString, ACell> config;
		private final DrivenClock clock = heldClock();

		private Restartable(Root<Index<Keyword, ACell>> state, AKeyPair key, AMap<AString, ACell> config) {
			this.state = state;
			this.key = key;
			this.config = config;
		}

		/** Mounts the venue and starts it; close the engine before the next start. */
		public Engine start() throws IOException {
			return mount().start();
		}

		/** As {@link #start()}, with different configuration for this run. */
		public Engine start(AMap<AString, ACell> runConfig) throws IOException {
			return mount(runConfig).start();
		}

		/** Mounts the venue without starting it, for a test that launches or prepares it itself. */
		public Engine mount() {
			return mount(config);
		}

		/** As {@link #mount()}, with different configuration for this run. */
		public Engine mount(AMap<AString, ACell> runConfig) {
			return application(new LatticeHost<>(state, null), clock).mount(key, runConfig);
		}

		/** The venue as its state stands now, as a crash would leave it: this one's later writes do not reach it. */
		public Restartable crashed() {
			return new Restartable(Cursors.root(state.get()), key, config);
		}

		/** The clock every run is on. */
		public DrivenClock clock() {
			return clock;
		}

		/** The venue's key pair. */
		public AKeyPair key() {
			return key;
		}

		/** The lattice state the runs share. */
		public Root<Index<Keyword, ACell>> state() {
			return state;
		}
	}

	/**
	 * Waits until {@code job} satisfies {@code condition}, checked now and on
	 * each update of the Job ({@link covia.grid.Job#subscribe}), so the test
	 * waits on the Job's own signal rather than polling it.
	 *
	 * @param timeoutMs bound that only a Job that never changes reaches
	 * @param timeoutMessage diagnostic constructed only on timeout/interruption
	 * @throws AssertionError if interrupted or the deadline expires
	 */
	public static void awaitJob(covia.grid.Job job, java.util.function.Predicate<covia.grid.Job> condition,
			long timeoutMs, Supplier<String> timeoutMessage) {
		java.util.concurrent.CompletableFuture<Void> met = new java.util.concurrent.CompletableFuture<>();
		java.util.function.Consumer<covia.grid.Job> listener = j -> { if (condition.test(j)) met.complete(null); };
		job.subscribe(listener);
		try {
			listener.accept(job);
			met.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException e) {
			throw new AssertionError(timeoutMessage.get() + " (status=" + job.getStatus() + ")");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting: " + timeoutMessage.get(), e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new AssertionError(e.getCause());
		} finally {
			job.unsubscribe(listener);
		}
	}
}
