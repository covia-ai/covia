package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.exception.AuthException;

/**
 * Tests for the per-venue grid {@link Scheduler}. Firing is exercised
 * deterministically via {@link Scheduler#trigger} (fire-by-handle) — no
 * wall-clock sleeps — except one test that lets the alarm fire an
 * immediately-due event. Owner isolation makes these safe under the shared
 * parallel engine. See {@code venue/docs/GRID_SCHEDULER.md}.
 */
public class SchedulerTest {

	private final Engine engine = TestEngine.ENGINE;
	private Scheduler sched;
	private AString did;
	private RequestContext ctx;

	/** Comfortably beyond any test run, so the alarm never fires these mid-test. */
	private static final long HOUR = 3_600_000L;

	private static AString s(String x) { return Strings.create(x); }

	@BeforeEach
	public void setup(TestInfo info) {
		sched = engine.gridScheduler();
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	private long future() { return System.currentTimeMillis() + HOUR; }

	// ----------------------------------------------------------- fire via trigger

	@Test
	public void testTriggerRunsOperation() throws Exception {
		ACell input = Maps.of(s("hello"), s("world"));
		Blob handle = sched.schedule(s("v/test/ops/echo"), input, ctx, future());

		// trigger fires now, ahead of time, returning the op's result.
		ACell result = sched.trigger(handle, ctx).get(5, TimeUnit.SECONDS);
		assertEquals(input, result, "echo should return its input");

		// Event consumed — gone from the owner's list.
		assertTrue(sched.list(ctx).isEmpty(), "triggered event should be removed");
	}

	@Test
	public void testCancelRemovesEvent() {
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("x"), ctx, future());
		assertEquals(1, sched.list(ctx).count());

		assertTrue(sched.cancel(handle, ctx), "cancel of a present event returns true");
		assertTrue(sched.list(ctx).isEmpty());
		assertFalse(sched.cancel(handle, ctx), "cancel of a missing event returns false");

		// A cancelled handle can no longer be triggered.
		CompletableFuture<ACell> f = sched.trigger(handle, ctx);
		assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
	}

	// ------------------------------------------------------- listing & ordering

	@Test
	public void testListIsOwnerScopedAndTimeOrdered() {
		long base = future();
		sched.schedule(s("v/test/ops/echo"), s("c"), ctx, base + 2000);
		sched.schedule(s("v/test/ops/echo"), s("a"), ctx, base);
		sched.schedule(s("v/test/ops/echo"), s("b"), ctx, base + 1000);

		// A different owner's event must not appear in this caller's list.
		RequestContext other = RequestContext.of(s("did:test:scheduler-other"));
		sched.schedule(s("v/test/ops/echo"), s("z"), other, base + 500);

		AVector<ACell> list = sched.list(ctx);
		assertEquals(3, list.count(), "only the caller's own events");

		long prev = Long.MIN_VALUE;
		for (long i = 0; i < list.count(); i++) {
			@SuppressWarnings("unchecked")
			var rec = (convex.core.data.AMap<AString, ACell>) list.get(i);
			long t = ((CVMLong) rec.get(Scheduler.K_TIME)).longValue();
			assertTrue(t >= prev, "list must be time-ordered (head = soonest due)");
			prev = t;
		}

		// Cleanup (shared engine).
		for (long i = 0; i < list.count(); i++) {
			@SuppressWarnings("unchecked")
			var rec = (convex.core.data.AMap<AString, ACell>) list.get(i);
			sched.cancel((Blob) rec.get(Scheduler.K_HANDLE), ctx);
		}
		sched.cancel((Blob) ((convex.core.data.AMap<?, ?>) sched.list(other).get(0))
			.get(Scheduler.K_HANDLE), other);
	}

	// ----------------------------------------------------------------- ownership

	@Test
	public void testOwnershipEnforced() {
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("x"), ctx, future());
		RequestContext intruder = RequestContext.of(s("did:test:scheduler-intruder"));

		// Cancel by a non-owner is rejected and leaves the event in place.
		assertThrows(AuthException.class, () -> sched.cancel(handle, intruder));
		// Trigger by a non-owner fails the returned future.
		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> sched.trigger(handle, intruder).get(5, TimeUnit.SECONDS));
		assertTrue(ex.getCause() instanceof AuthException);

		// Still present and cancellable by its real owner.
		assertTrue(sched.cancel(handle, ctx));
	}

	// ------------------------------------------- captured-authority (no escalation)

	/**
	 * The owner's caps are captured at schedule time and re-enforced at fire
	 * time: a deferred op the caps do not cover is denied — exactly as it would
	 * be on the immediate {@code invokeOperation} path. Guards against the
	 * scheduler firing under more authority than the owner held.
	 */
	@Test
	public void testCapsReplayDeniesUncoveredOp() {
		AVector<ACell> caps = Vectors.of(Maps.of(
			s("with"), s("w/allowed"),
			s("can"),  s("crud/read")));
		RequestContext capCtx = RequestContext.of(did).withCaps(caps);

		Blob handle = sched.schedule(s("v/ops/covia/read"),
			Maps.of(s("path"), s("w/forbidden/x")), capCtx, future());

		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> sched.trigger(handle, capCtx).get(5, TimeUnit.SECONDS));
		assertTrue(ex.getCause().getMessage().startsWith("Capability denied:"),
			"captured caps must be enforced at fire time; got: " + ex.getCause().getMessage());
	}

	@Test
	public void testCapsReplayAllowsCoveredOp() throws Exception {
		AVector<ACell> caps = Vectors.of(Maps.of(
			s("with"), s("w/allowed"),
			s("can"),  s("crud/read")));
		RequestContext capCtx = RequestContext.of(did).withCaps(caps);

		Blob handle = sched.schedule(s("v/ops/covia/read"),
			Maps.of(s("path"), s("w/allowed/nothing")), capCtx, future());

		ACell result = sched.trigger(handle, capCtx).get(5, TimeUnit.SECONDS);
		assertTrue(result != null, "a caps-covered deferred op must run, not be denied");
	}

	// ------------------------------------------------------------- alarm firing

	/**
	 * An immediately-due event is fired by the alarm without any manual trigger.
	 * Polls the owner's list (generous timeout) — robust under the shared,
	 * parallel timer thread since the event is the test's own.
	 */
	@Test
	public void testAlarmFiresImmediatelyDueEvent() throws Exception {
		sched.schedule(s("v/test/ops/echo"), s("tick"), ctx, System.currentTimeMillis());

		long deadline = System.currentTimeMillis() + 5_000;
		while (!sched.list(ctx).isEmpty() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertTrue(sched.list(ctx).isEmpty(),
			"the alarm should fire and remove an immediately-due event");
	}
}
