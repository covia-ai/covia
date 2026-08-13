package covia.venue;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import convex.core.data.Strings;
import convex.core.data.Maps;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.ACell;

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
 * {@link VenueRestartTest}, {@link DLFSPersistenceTest},
	 * {@link EngineSchedulerRebuildTest}. Tests that need bespoke engine
	 * config (e.g. {@code FileAdapterTest} with custom {@code @TempDir}
	 * jail roots, or {@code OAuthTest}'s inline {@code createTemp} for
	 * auth-config variants), and tests that install permanent engine
	 * callbacks (e.g. {@code DLFSAdapterTest}'s {@code onSync} hook),
	 * also belong in this category.</p>
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

	private static final long POLL_INTERVAL_MS = 5;

	/**
	 * Shared Engine instance for venue unit tests. Lazily initialised on
	 * first access. Static — survives the JVM, no per-test setup cost.
	 */
	public static final Engine ENGINE;

	static {
		ENGINE = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
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

	/**
	 * Waits for an asynchronous post-condition without relying on a blind sleep.
	 * The condition is checked immediately and then at a short fixed interval.
	 * Interruptions fail the test instead of being mistaken for success.
	 *
	 * @param condition condition that becomes true on success
	 * @param timeoutMs maximum wait in milliseconds
	 * @param timeoutMessage diagnostic constructed only on timeout/interruption
	 * @throws AssertionError if interrupted or the deadline expires
	 */
	public static void awaitCondition(BooleanSupplier condition, long timeoutMs,
			Supplier<String> timeoutMessage) {
		if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must be non-negative");
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		do {
			if (condition.getAsBoolean()) return;
			if (System.nanoTime() >= deadline) break;
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting: " + timeoutMessage.get(), e);
			}
		} while (true);
		throw new AssertionError(timeoutMessage.get());
	}

	/** Waits until an agent reaches the requested persisted status. */
	public static void awaitAgentStatus(AgentState agent, AString expected, long timeoutMs) {
		awaitCondition(() -> expected.equals(agent.getStatus()), timeoutMs,
			() -> "Agent did not reach " + expected + " within " + timeoutMs
				+ "ms (status=" + agent.getStatus() + ")");
	}

	/**
	 * Polls until the agent is idle (status {@code SLEEPING} with no
	 * session pending and no tasks) or the timeout elapses. Tests should
	 * wait on task/chat/request Jobs for results; this helper is for cases
	 * that drive the agent via a fallback trigger + direct lattice writes
	 * and need a quiescence signal before making assertions.
	 *
	 * @throws AssertionError if the agent is not idle within the timeout.
	 */
	public static void awaitAgentIdle(AgentState agent, long timeoutMs) {
		awaitCondition(() -> {
			AString status = agent.getStatus();
			Index<Blob, ACell> tasks = agent.getTasks();
			return AgentState.SLEEPING.equals(status)
				&& !agent.hasSessionPending()
				&& (tasks == null || tasks.count() == 0);
		}, timeoutMs, () -> "Agent did not become idle within "
			+ timeoutMs + "ms (status=" + agent.getStatus()
			+ ", hasSessionPending=" + agent.hasSessionPending()
			+ ", tasks=" + (agent.getTasks() == null ? 0 : agent.getTasks().count()) + ")");
	}

	/**
	 * Polls until the agent's timeline has at least {@code minCount} entries
	 * or the timeout elapses. Use this when asserting on the output of a
	 * cycle whose transition may yield on an async op — the merge (and
	 * therefore the timeline write) happens on resume, not at trigger
	 * return. Timeline count only grows, so "at least N" is a safe
	 * quiescence signal.
	 *
	 * @throws AssertionError if the timeline does not reach {@code minCount}
	 *                        within the timeout.
	 */
	public static void awaitTimelineCount(AgentState agent, int minCount, long timeoutMs) {
		awaitCondition(() -> {
			convex.core.data.AVector<?> tl = agent.getTimeline();
			return tl != null && tl.count() >= minCount;
		}, timeoutMs, () -> {
			convex.core.data.AVector<?> tl = agent.getTimeline();
			return "Agent timeline did not reach " + minCount
			+ " entries within " + timeoutMs + "ms (current="
			+ (tl == null ? 0 : tl.count()) + ", status=" + agent.getStatus() + ")";
		});
	}
}
