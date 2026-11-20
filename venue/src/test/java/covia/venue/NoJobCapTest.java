package covia.venue;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.grid.Job;

/**
 * The venue sets no limit on how many jobs a user has
 * ({@code venue/docs/JOB_EXECUTION.md}). A config that still sets
 * {@code rateLimit.maxConcurrentJobsPerUser} starts, under strict
 * configuration too, and the key has no effect.
 */
public class NoJobCapTest {

	private static final AString NEVER = Strings.create("v/test/ops/never");

	private static Engine engine;

	/**
	 * Rate limiting on, the old key at one job, no wait. Its shutdown grace is
	 * zero: the jobs the test leaves running never finish.
	 */
	@BeforeAll
	static void start() {
		engine = Engine.createTemp(Maps.of(
			Config.STRICT_CONFIG, true,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0L),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				Config.MAX_CONCURRENT_JOBS_PER_USER, 1L,
				Strings.create("blockMs"), 0L)));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void close() {
		engine.close();
	}

	@Test
	public void testUserStartsJobsBeyondTheRemovedCap() {
		RequestContext alice = RequestContext.of(TestEngine.uniqueDID("NoJobCap-alice"));
		for (int i = 0; i < 3; i++) {
			Job job = engine.jobs().invokeOperation(NEVER, Maps.empty(), alice);
			assertFalse(job.isFinished(), "every job the user starts is admitted and running");
		}
	}
}
