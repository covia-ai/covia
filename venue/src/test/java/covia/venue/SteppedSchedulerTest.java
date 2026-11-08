package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import covia.adapter.HITLAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.exception.ShuttingDownException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.hitl.Hitl;

/**
 * The scheduler on a clock the test drives (GRID_SCHEDULER.md, Updates):
 * nothing fires until the application is updated, and an update starts what
 * is due by its time, in index order and at most {@code scheduler.maxConcurrency}
 * at once. HITL timeouts expire through the same schedule. The last test runs
 * the update timer on the system clock.
 */
@Execution(ExecutionMode.SAME_THREAD) // the tests share the venue's clock and move it
class SteppedSchedulerTest {

	/** A time no system clock reads while this test runs. */
	private static final long START = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli();
	private static final long HOUR = 3_600_000L;
	private static final AString HOLD_START = Strings.create("v/test/ops/holdstart");
	private static final AString ECHO = Strings.create("v/test/ops/echo");

	/** The venues' clock. Only ever moves forward, so tests may share it. */
	private static final AtomicLong CLOCK = new AtomicLong(START);

	/** A venue with the default {@code scheduler.maxConcurrency}, 2, and no update timer. */
	private static CoviaApplication application;
	private static Engine engine;

	@BeforeAll
	static void launch() throws IOException {
		application = CoviaApplication.create(policy());
		engine = application.mount(AKeyPair.generate(), config(null)).launch();
	}

	@AfterAll
	static void close() {
		application.close();
	}

	private static VenueContext policy() {
		return VenueContext.create(() -> CVMLong.create(CLOCK.get()));
	}

	/** Auto-created users, and {@code scheduler.maxConcurrency} when not null. */
	private static Config config(Integer maxConcurrency) {
		AMap<AString, ACell> config = Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		if (maxConcurrency != null) {
			config = config.assoc(Config.SCHEDULER, Maps.of(Config.MAX_CONCURRENCY, CVMLong.create(maxConcurrency)));
		}
		return new Config(config);
	}

	private static RequestContext user(Engine venue, TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		venue.getVenueState().users().ensure(did);
		return RequestContext.of(did);
	}

	private static AMap<AString, ACell> gate(String name) {
		return Maps.of("gate", name);
	}

	private static void entered(TestAdapter.TestGate gate, String what) throws InterruptedException {
		assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), what);
	}

	/** Moves the clock on by {@code ms} and updates the application. */
	private static CompletableFuture<Void> step(CoviaApplication app, long ms) {
		CLOCK.addAndGet(ms);
		return app.update();
	}

	@Test
	void nothingStartsUntilTheApplicationIsUpdated(TestInfo info) throws Exception {
		RequestContext ctx = user(engine, info);
		Scheduler scheduler = engine.gridScheduler();
		String name = "stepped-due-" + ctx.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(name)) {
			scheduler.schedule(HOLD_START, gate(name), ctx, CLOCK.get());
			assertFalse(gate.awaitEntered(0, TimeUnit.SECONDS), "no update has run, so nothing has started");
			assertEquals(1, scheduler.list(ctx).count(), "a due event waits in the schedule until an update");

			CompletableFuture<Void> update = application.update();
			entered(gate, "the update starts the due event");
			assertTrue(scheduler.list(ctx).isEmpty(), "a started one-shot event has left the schedule");
			assertFalse(update.isDone(), "the update completes once its events' invocations have returned");
			gate.release();
			update.get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void dueEventsStartInIndexOrderTwoAtOnce(TestInfo info) throws Exception {
		RequestContext ctx = user(engine, info);
		Scheduler scheduler = engine.gridScheduler();
		String prefix = "stepped-two-" + ctx.getUserDID() + "-";
		long t = CLOCK.get();
		try (TestAdapter.TestGate a = TestAdapter.createGate(prefix + "a");
				TestAdapter.TestGate b = TestAdapter.createGate(prefix + "b");
				TestAdapter.TestGate c = TestAdapter.createGate(prefix + "c")) {
			// Scheduled out of order: the index orders them by time.
			scheduler.schedule(HOLD_START, gate(prefix + "c"), ctx, t + 3);
			scheduler.schedule(HOLD_START, gate(prefix + "a"), ctx, t + 1);
			scheduler.schedule(HOLD_START, gate(prefix + "b"), ctx, t + 2);
			Blob later = scheduler.schedule(ECHO, Strings.create("later"), ctx, t + 100);

			CompletableFuture<Void> update = step(application, 10);
			entered(a, "the first due event starts");
			entered(b, "the second due event starts alongside it");
			assertEquals(2, scheduler.list(ctx).count(),
				"the third waits in the schedule, with the event not yet due, while two are starting");
			a.release();
			entered(c, "a place frees when an invocation returns, and the third starts");
			assertFalse(update.isDone());
			b.release();
			c.release();
			update.get(10, TimeUnit.SECONDS);
			AVector<ACell> left = scheduler.list(ctx);
			assertEquals(1, left.count(), "the event not yet due is untouched: " + left);
			assertTrue(scheduler.cancel(later, ctx));
		}
	}

	@Test
	void aLimitOfOneStartsDueEventsOneAfterAnother(TestInfo info) throws Exception {
		CoviaApplication single = CoviaApplication.create(policy());
		try {
			Engine venue = single.mount(AKeyPair.generate(), config(1)).launch();
			RequestContext ctx = user(venue, info);
			Scheduler scheduler = venue.gridScheduler();
			String prefix = "stepped-one-" + ctx.getUserDID() + "-";
			long t = CLOCK.get();
			try (TestAdapter.TestGate a = TestAdapter.createGate(prefix + "a");
					TestAdapter.TestGate b = TestAdapter.createGate(prefix + "b")) {
				scheduler.schedule(HOLD_START, gate(prefix + "b"), ctx, t + 2);
				scheduler.schedule(HOLD_START, gate(prefix + "a"), ctx, t + 1);

				CompletableFuture<Void> update = step(single, 10);
				entered(a, "the earlier event starts first");
				assertEquals(1, scheduler.list(ctx).count(), "the later one waits for the only place");
				a.release();
				entered(b, "then the later one");
				b.release();
				update.get(10, TimeUnit.SECONDS);
				assertTrue(scheduler.list(ctx).isEmpty());

				// A closing venue starts nothing more: an update waiting on it fails.
				try (TestAdapter.TestGate held = TestAdapter.createGate(prefix + "held")) {
					scheduler.schedule(HOLD_START, gate(prefix + "held"), ctx, CLOCK.get());
					scheduler.schedule(ECHO, Strings.create("never started"), ctx, CLOCK.get());
					CompletableFuture<Void> waiting = single.update();
					entered(held, "the first event takes the only place");
					scheduler.shutdown();
					ExecutionException failed = assertThrows(ExecutionException.class,
						() -> waiting.get(10, TimeUnit.SECONDS));
					assertInstanceOf(ShuttingDownException.class, failed.getCause());
				}
			}
		} finally {
			single.close();
		}
	}

	@Test
	void aStepAtTheSameTimeStartsAnEventAddedSinceWithAPastTime(TestInfo info) throws Exception {
		RequestContext ctx = user(engine, info);
		Scheduler scheduler = engine.gridScheduler();
		application.update().get(10, TimeUnit.SECONDS);
		String name = "stepped-past-" + ctx.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(name)) {
			scheduler.schedule(HOLD_START, gate(name), ctx, CLOCK.get() - 1_000);
			CompletableFuture<Void> update = step(application, 0);
			entered(gate, "a step that leaves the time unchanged still starts what is due");
			gate.release();
			update.get(10, TimeUnit.SECONDS);
			assertTrue(scheduler.list(ctx).isEmpty());
		}
	}

	@Test
	void aRecurringEventMovesToItsNextSlotSkippingMissedOnes(TestInfo info) throws Exception {
		RequestContext ctx = user(engine, info);
		Scheduler scheduler = engine.gridScheduler();
		long t = CLOCK.get();
		AMap<AString, ACell> everySecond = Maps.of(Schedule.K_EVERY, CVMLong.create(1_000));
		Blob handle = scheduler.schedule(ECHO, Strings.create("tick"), ctx, t + 1_000, everySecond, true);

		step(application, 3_500).get(10, TimeUnit.SECONDS);
		AVector<ACell> listed = scheduler.list(ctx);
		assertEquals(1, listed.count());
		ACell rec = listed.get(0);
		assertEquals(CVMLong.create(t + 4_000), RT.getIn(rec, Schedule.K_TIME),
			"the next slot after the update's time, on the event's phase");
		assertEquals(CVMLong.create(t + 3_500), RT.getIn(rec, Schedule.K_LAST_FIRED),
			"fired at the update's time");
		assertEquals(1, engine.getVenueState().users().user(ctx.getUserDID()).jobs().getAll().count(),
			"the slots it missed were skipped, not replayed");
		assertTrue(scheduler.cancel(handle, ctx));
	}

	@Test
	void anEventDueWhileTheVenueIsClosingStaysForItsNextStart(TestInfo info) throws Exception {
		AtomicBoolean closing = new AtomicBoolean();
		CoviaApplication closable = CoviaApplication.create(policy());
		try {
			Engine venue = closable.mount(AKeyPair.generate(), config(null), (cfg, app, key) -> new Engine(cfg, app, key) {
				@Override public boolean isClosing() { return closing.get() || super.isClosing(); }
			}).launch();
			RequestContext ctx = user(venue, info);
			Scheduler scheduler = venue.gridScheduler();
			scheduler.schedule(ECHO, Strings.create("due"), ctx, CLOCK.get());
			closing.set(true);
			closable.update().get(10, TimeUnit.SECONDS);
			assertEquals(1, scheduler.list(ctx).count(), "a closing venue neither starts nor consumes it");
			assertEquals(Long.MAX_VALUE, closable.nextDue(), "and reports nothing due");
			closing.set(false);
			closable.update().get(10, TimeUnit.SECONDS);
			assertTrue(scheduler.list(ctx).isEmpty());
		} finally {
			closable.close();
		}
	}

	/** The handles of the venue's scheduled events that expire {@code request}. */
	private static List<Blob> expiries(Engine venue, Job request) {
		AString id = Strings.create(request.getID().toHexString());
		List<Blob> handles = new ArrayList<>();
		for (ACell item : venue.gridScheduler().list(venue.venueContext())) {
			if (id.equals(RT.getIn(item, Schedule.K_INPUT, Hitl.ID))) handles.add((Blob) RT.getIn(item, Schedule.K_HANDLE));
		}
		return handles;
	}

	@Test
	void aHitlTimeoutExpiresTheRequestAtTheDrivenTime(TestInfo info) throws Exception {
		RequestContext ctx = user(engine, info);
		AString agentId = Strings.create("asker");
		Agent agent = engine.getVenueState().users().user(ctx.getUserDID()).ensureAgent(agentId, Maps.empty(), null);
		Job expiring = engine.jobs().invokeOperation("v/ops/hitl/request", Hitl.request("Expires")
			.ask(Hitl.approval("ok", "OK?")).timeout(60).build(), RequestContext.ofAgent(ctx.getUserDID(), agentId));
		Job answered = engine.jobs().invokeOperation("v/ops/hitl/request", Hitl.request("Answered")
			.ask(Hitl.approval("ok", "OK?")).timeout(60).build(), ctx);
		Job cancelled = engine.jobs().invokeOperation("v/ops/hitl/request", Hitl.request("Cancelled")
			.ask(Hitl.approval("ok", "OK?")).timeout(60).build(), ctx);
		assertEquals(Status.INPUT_REQUIRED, expiring.getStatus());
		assertTrue(agent.getPending().containsKey(expiring.getID()));
		assertEquals(1, expiries(engine, expiring).size(), "asking schedules its expiry");
		String answeredId = answered.getID().toHexString();
		engine.jobs().invokeOperation("v/ops/hitl/respond", Hitl.answer(answeredId).answer("ok", true).build(), ctx)
			.awaitResult(10_000);
		assertEquals(Status.COMPLETE, answered.getStatus());
		assertEquals(0, expiries(engine, answered).size(), "answering takes the expiry with it");
		engine.jobs().cancelJob(cancelled.getID(), ctx);
		assertEquals(0, expiries(engine, cancelled).size(), "cancelling takes the expiry with it");

		step(application, 59_999).get(10, TimeUnit.SECONDS);
		assertEquals(Status.INPUT_REQUIRED, expiring.getStatus(), "still open a moment before its time");

		step(application, 1).get(10, TimeUnit.SECONDS);
		TestEngine.awaitJob(expiring, Job::isFinished, 10_000, () -> "the request expires at its time");
		assertEquals(Status.FAILED, expiring.getStatus());
		assertEquals(Strings.create(HITLAdapter.REQUEST_EXPIRED), expiring.getData().get(Fields.ERROR));
		User owner = engine.getVenueState().users().user(ctx.getUserDID());
		assertEquals(Hitl.EXPIRED, owner.inbox().get(Strings.create(expiring.getID().toHexString())).get(Hitl.STATUS));
		assertFalse(agent.getPending().containsKey(expiring.getID()));
		assertEquals(Hitl.ANSWERED, owner.inbox().get(Strings.create(answeredId)).get(Hitl.STATUS),
			"the expiry of an answered request leaves it as it is");
		assertEquals(Status.COMPLETE, answered.getStatus());
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/hitl/respond",
			Hitl.answer(expiring.getID().toHexString()).answer("ok", true).build(), ctx).awaitResult(10_000),
			"an expired request cannot be answered");

		// Only the venue expires requests.
		Job asked = engine.jobs().invokeOperation(HITLAdapter.EXPIRE_OP.toString(),
			HITLAdapter.expiryInput(ctx.getUserDID(), Strings.create(answeredId)), ctx);
		TestEngine.awaitJob(asked, Job::isFinished, 10_000, () -> "a user's expiry is refused");
		assertEquals(Status.FAILED, asked.getStatus());
		assertTrue(asked.getErrorMessage().contains(HITLAdapter.EXPIRE_VENUE_ONLY), asked.getErrorMessage());
	}

	@Test
	void aHitlTimeoutSurvivesARestart(TestInfo info) throws Exception {
		MemoryStore store = new MemoryStore();
		AKeyPair key = AKeyPair.generate();
		AString did = TestEngine.uniqueDID(info);
		Blob kept;
		Blob lost;
		CoviaApplication first = CoviaApplication.open(store, policy());
		try {
			Engine venue = first.mount(key, config(null)).launch();
			venue.getVenueState().users().ensure(did);
			RequestContext ctx = RequestContext.of(did);
			kept = venue.jobs().invokeOperation("v/ops/hitl/request", Hitl.request("Outlives a restart")
				.ask(Hitl.approval("ok", "OK?")).timeout(60).build(), ctx).getID();
			Job gone = venue.jobs().invokeOperation("v/ops/hitl/request", Hitl.request("Loses its alarm")
				.ask(Hitl.approval("ok", "OK?")).timeout(60).build(), ctx);
			lost = gone.getID();
			// As when an event fired but the venue stopped before the request expired
			assertTrue(venue.gridScheduler().cancel(expiries(venue, gone).get(0), venue.venueContext()));
		} finally {
			first.close();
		}
		CLOCK.addAndGet(60_000); // both fall due while the venue is down
		CoviaApplication second = CoviaApplication.open(store, policy());
		try {
			Engine venue = second.mount(key, config(null)).launch();
			Job keptJob = venue.jobs().getJob(kept);
			Job lostJob = venue.jobs().getJob(lost);
			assertEquals(Status.INPUT_REQUIRED, keptJob.getStatus(), "recovered, still waiting");
			assertEquals(1, expiries(venue, keptJob).size(), "recovery leaves an expiry it finds");
			assertEquals(1, expiries(venue, lostJob).size(), "recovery restores a lost expiry from the request");
			second.update().get(10, TimeUnit.SECONDS);
			for (Job job : List.of(keptJob, lostJob)) {
				TestEngine.awaitJob(job, Job::isFinished, 10_000, () -> "an overdue request expires at the first update");
				assertEquals(Status.FAILED, job.getStatus());
				assertEquals(Hitl.EXPIRED, venue.getVenueState().users().user(did).inbox()
					.get(Strings.create(job.getID().toHexString())).get(Hitl.STATUS));
			}
		} finally {
			second.close();
		}
	}

	@Test
	void theTimerStartsADueEventAndWakesForAnEarlierOne(TestInfo info) throws Exception {
		CoviaApplication timed = CoviaApplication.create();
		try {
			Engine venue = timed.mount(AKeyPair.generate(), config(null)).launch();
			AutoUpdate timer = timed.autoUpdate();
			assertEquals(CoviaApplication.AUTO_UPDATING,
				assertThrows(IllegalStateException.class, timed::autoUpdate).getMessage());
			RequestContext ctx = user(venue, info);
			Scheduler scheduler = venue.gridScheduler();
			String name = "timer-due-" + ctx.getUserDID();
			try (TestAdapter.TestGate gate = TestAdapter.createGate(name)) {
				// The timer waits an hour for this one...
				Blob later = scheduler.schedule(ECHO, Strings.create("later"), ctx, System.currentTimeMillis() + HOUR);
				// ...and is woken for one due now.
				scheduler.schedule(HOLD_START, gate(name), ctx, System.currentTimeMillis());
				entered(gate, "the timer starts the event due now");
				gate.release();
				assertTrue(scheduler.cancel(later, ctx));
			}
			assertFalse(timer.failure().toCompletableFuture().isDone(), "the timer reported no failure");
			timer.close();
			timed.autoUpdate().close(); // closing one lets another start
		} finally {
			timed.close();
		}
	}
}
