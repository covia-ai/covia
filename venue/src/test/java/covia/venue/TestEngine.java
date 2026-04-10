package covia.venue;

import convex.core.data.Strings;
import convex.core.data.AString;

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
 * <p><b>When NOT to use:</b> tests that exercise persistence/restart
 * (require their own EtchStore) — those should construct their own Engine
 * via the appropriate Engine constructor or {@code VenueServer.launch}.
 * Examples: {@link EnginePersistenceTest}, {@link VenueServerPersistenceTest},
 * {@link VenueRestartTest}, {@link DLFSPersistenceTest}.</p>
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
	 * first access. Static — survives the JVM, no per-test setup cost.
	 */
	public static final Engine ENGINE;

	static {
		ENGINE = Engine.createTemp(null);
		Engine.addDemoAssets(ENGINE);
	}

	/**
	 * Generates a unique user DID per test method based on the test class
	 * and method name. Use this in {@code @BeforeEach} so each test sees a
	 * fresh user namespace within the shared Engine.
	 *
	 * <p>The returned DID is deterministic for a given test name — useful
	 * for debugging — but unique across all tests in the suite.</p>
	 *
	 * @param info JUnit's TestInfo (inject via @BeforeEach parameter)
	 * @return A DID string like {@code "did:key:z6Mk-test-MyClass-myMethod"}
	 */
	public static AString uniqueDID(org.junit.jupiter.api.TestInfo info) {
		String className = info.getTestClass()
			.map(c -> c.getSimpleName()).orElse("Unknown");
		String methodName = info.getTestMethod()
			.map(m -> m.getName()).orElse("unknown");
		return Strings.create("did:key:z6Mk-test-" + className + "-" + methodName);
	}

	/**
	 * Generates a unique DID from an arbitrary discriminator string.
	 * Use when {@link org.junit.jupiter.api.TestInfo} isn't available
	 * (e.g. in helper methods called from multiple tests).
	 */
	public static AString uniqueDID(String discriminator) {
		return Strings.create("did:key:z6Mk-test-" + discriminator);
	}
}
