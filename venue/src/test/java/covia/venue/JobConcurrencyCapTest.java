package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.AuthException;
import covia.exception.RateLimitException;
import covia.grid.Job;

/**
 * Concurrent-job cap: per-caller admission control on top-level invokes.
 *
 * <p>These are deterministic mechanism tests, not timing simulations. Slots are
 * held by never-completing jobs (so a caller stays exactly at the cap), and the
 * bounded wait is set to {@code blockMs = 0} so an over-cap invoke sheds
 * immediately — no sleeps, no wall-clock assertions, no load dependence.
 * Releasing a slot (cancel) is synchronous (VenueJob evicts on the terminal
 * transition), so a freed slot is observable on the very next invoke.</p>
 */
public class JobConcurrencyCapTest {

	private static final AString NEVER = Strings.create("v/test/ops/never");

	/** Cap of one, for the tests that only need to be at the cap. The cap is per caller, so each uses its own. */
	private static Engine capOfOne;

	@BeforeAll
	static void startCapOfOne() {
		capOfOne = capEngine(1);
	}

	@AfterAll
	static void closeCapOfOne() {
		capOfOne.close();
	}

	/**
	 * Engine with the cap enabled and zero block time (immediate shed). Its
	 * shutdown grace is zero: the jobs left holding slots never finish, so a
	 * grace window would only be waited out.
	 */
	private static Engine capEngine(int cap) {
		Engine e = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0L),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				Strings.create("maxConcurrentJobsPerUser"), (long) cap,
				Strings.create("blockMs"), 0L)));
		Engine.addDemoAssets(e);
		return e;
	}

	private static RequestContext caller(String did) {
		return RequestContext.of(Strings.create(did));
	}

	private static Job never(Engine e, RequestContext ctx) {
		return e.jobs().invokeOperation(NEVER, Maps.empty(), ctx);
	}

	@Test
	public void testAtCapSheds() {
		Engine e = capEngine(2);
		try {
			RequestContext alice = caller("did:key:zAliceCap");

			assertNotNull(never(e, alice));
			assertNotNull(never(e, alice));           // cap (2) now full

			RateLimitException ex = assertThrows(RateLimitException.class, () -> never(e, alice),
				"an invoke over the cap must shed with RateLimitException");
			assertTrue(ex.getRetryAfterSeconds() >= 1, "429 must carry a positive Retry-After");
		} finally {
			e.close();
		}
	}

	@Test
	public void testCancelFreesSlot() {
		Engine e = capOfOne;
		RequestContext alice = caller("did:key:zAliceFree");

		Job j1 = never(e, alice);
		assertThrows(RateLimitException.class, () -> never(e, alice)); // at cap

		e.jobs().cancelJob(j1.getID(), alice); // terminal → synchronously releases the permit
		assertNotNull(never(e, alice), "cancelling a job must free a concurrency slot");
	}

	@Test
	public void testRefusedInvokeFreesSlot() {
		Engine e = capOfOne;
		AString did = Strings.create("did:key:zAliceRefused");
		RequestContext alice = RequestContext.of(did);
		e.getVenueState().users().ensure(did);
		e.deleteUser(e.venueContext(), did);

		assertThrows(AuthException.class, () -> never(e, alice));

		e.getVenueState().users().create(did);
		assertNotNull(never(e, alice), "a refused invoke must give back its slot");
	}

	@Test
	public void testSubJobsExempt() {
		Engine e = capOfOne;
		RequestContext alice = caller("did:key:zAliceSub");

		Job j1 = never(e, alice); // top-level fills the cap
		// A sub-invoke carries a parent jobId — exempt from the cap, admitted
		// even though the caller is at the limit.
		Job sub = never(e, alice.withJobId(j1.getID()));
		assertNotNull(sub, "sub-jobs (parent jobId set) must bypass the cap");
	}

	@Test
	public void testOtherCallerIndependent() {
		Engine e = capOfOne;
		never(e, caller("did:key:zAliceIso")); // Alice at cap
		assertNotNull(never(e, caller("did:key:zBobIso")),
			"one caller's saturation must not affect another");
	}

	@Test
	public void testCapZeroDisablesLimit() {
		Engine e = capEngine(0);                    // 0 → cap disabled
		try {
			RequestContext alice = caller("did:key:zAliceUnlimited");
			for (int i = 0; i < 5; i++) assertNotNull(never(e, alice));
		} finally {
			e.close();
		}
	}

	@Test
	public void testRecoveredPausedJobsConsumePermits() throws Exception {
		convex.core.data.AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				Strings.create("maxConcurrentJobsPerUser"), 1L,
				Strings.create("blockMs"), 0L));
		TestEngine.Restartable venue = TestEngine.restartable(config);
		RequestContext alice = caller("did:key:zAliceRecoveredCap");
		Job paused;

		Engine first = venue.start();
		Engine.addDemoAssets(first);
		paused = never(first, alice);
		first.jobs().pauseJob(paused.getID(), alice);
		first.syncState();
		first.close();

		Engine second = venue.start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			assertThrows(RateLimitException.class, () -> never(second, alice),
				"a restored non-terminal job must still occupy the caller's slot");
			second.jobs().cancelJob(paused.getID(), alice);
			assertNotNull(never(second, alice), "finishing the restored job must release its slot");
		} finally {
			second.close();
		}
	}
}
