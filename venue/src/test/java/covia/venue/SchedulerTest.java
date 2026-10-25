package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;

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

	private static AMap<AString, ACell> everyHour() {
		return Maps.of(ScheduleState.K_EVERY, CVMLong.create(HOUR));
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> asMap(ACell c) { return (AMap<AString, ACell>) c; }

	/** The caller's single listed event (these tests keep exactly one). */
	private static AMap<AString, ACell> only(AVector<ACell> list) {
		assertEquals(1, list.count(), "expected exactly one scheduled event: " + list);
		return asMap(list.get(0));
	}

	// ----------------------------------------------------------- fire via trigger

	@Test
	@SuppressWarnings("unchecked")
	public void testScheduleRetainsExtensibleRecordShape() {
		Blob first = sched.schedule(s("v/test/ops/echo"), s("one"), ctx, future());
		var cursor = engine.getVenueState().schedule().cursor();
		AMap<AString, ACell> record = assertInstanceOf(AMap.class, cursor.get());
		assertInstanceOf(CVMLong.class, record.get(Fields.UPDATED));
		assertInstanceOf(Index.class, record.get(ScheduleState.K_EVENTS));

		// An events mutation must update the field in place, not rebuild the
		// scheduler record and discard future sibling metadata.
		AString marker = Strings.create("test-marker-" + did);
		cursor.updateAndGet(value -> ((AMap<AString, ACell>) value)
			.assoc(marker, Strings.create("keep")));
		Blob second = sched.schedule(s("v/test/ops/echo"), s("two"), ctx, future() + 1);
		assertEquals(Strings.create("keep"),
			((AMap<AString, ACell>) cursor.get()).get(marker));

		assertTrue(sched.cancel(first, ctx));
		assertTrue(sched.cancel(second, ctx));
		cursor.updateAndGet(value -> ((AMap<AString, ACell>) value).dissoc(marker));
	}

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
			long t = ((CVMLong) rec.get(ScheduleState.K_TIME)).longValue();
			assertTrue(t >= prev, "list must be time-ordered (head = soonest due)");
			prev = t;
		}

		// Cleanup (shared engine).
		for (long i = 0; i < list.count(); i++) {
			@SuppressWarnings("unchecked")
			var rec = (convex.core.data.AMap<AString, ACell>) list.get(i);
			sched.cancel((Blob) rec.get(ScheduleState.K_HANDLE), ctx);
		}
		sched.cancel((Blob) ((convex.core.data.AMap<?, ?>) sched.list(other).get(0))
			.get(ScheduleState.K_HANDLE), other);
	}

	// --------------------------------------------- describing events (#583)

	/** The stored record for {@code handle}, read straight from the events index. */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> stored(Blob handle) {
		AMap<AString, ACell> record = asMap(engine.getVenueState().schedule().cursor().get());
		return asMap(((Index<Blob, ACell>) record.get(ScheduleState.K_EVENTS)).get(handle));
	}

	@Test
	public void testListSaysWhoScheduledAndWhen() {
		long before = System.currentTimeMillis();
		Blob mine = sched.schedule(s("v/test/ops/echo"), s("mine"), ctx, future());
		long after = System.currentTimeMillis();
		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertEquals(did, rec.get(ScheduleState.K_BY), "a user's own event names the user");
		long created = ((CVMLong) rec.get(ScheduleState.K_CREATED)).longValue();
		assertTrue(created >= before && created <= after, "created is the scheduling time");
		assertTrue(sched.cancel(mine, ctx));

		// An agent schedules into its owner's account, and is named as the caller.
		RequestContext agent = RequestContext.ofAgent(did, s("helper"));
		Blob queued = sched.schedule(s("v/test/ops/echo"), s("theirs"), agent, future());
		rec = only(sched.list(ctx));
		assertEquals(agent.getCallerDID(), rec.get(ScheduleState.K_BY), "an agent's event names the agent");
		assertNotEquals(did, rec.get(ScheduleState.K_BY));
		assertEquals(did, stored(queued).get(ScheduleState.K_OWNER), "the owner still owns it");
		assertTrue(sched.cancel(queued, ctx));
	}

	@Test
	public void testListNeverShowsCapturedAuthority() {
		AVector<ACell> caps = Vectors.of(Maps.of(s("with"), s("w/allowed"), s("can"), s("crud/read")));
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("x"), ctx.withCaps(caps), future());
		assertEquals(caps, stored(handle).get(ScheduleState.K_CAPS), "caps are captured for replay");

		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertFalse(rec.containsKey(ScheduleState.K_CAPS), "caps are authority, not description");
		assertFalse(rec.containsKey(ScheduleState.K_PROOFS), "proofs are authority, not description");
		assertTrue(sched.cancel(handle, ctx));
	}

	/** Listed input hides what a job record would hide; the stored input, which firing replays, keeps it. */
	@Test
	public void testListRedactsDeclaredSecretFields() {
		AMap<AString, ACell> input = Maps.of(s("seed"), s("raw-seed-value"), s("source"), s("(+ 1 2)"));
		Blob handle = sched.schedule(s("v/ops/convex/transact"), input, ctx, future());

		ACell listed = only(sched.list(ctx)).get(ScheduleState.K_INPUT);
		assertEquals(Fields.HIDDEN, asMap(listed).get(s("seed")), "secretFields are hidden");
		assertEquals(s("(+ 1 2)"), asMap(listed).get(s("source")), "other fields are shown");
		assertEquals(input, stored(handle).get(ScheduleState.K_INPUT), "the stored input is untouched");
		assertTrue(sched.cancel(handle, ctx));

		// An undeclared input is listed as given.
		Blob plain = sched.schedule(s("v/test/ops/echo"), input, ctx, future());
		assertEquals(input, only(sched.list(ctx)).get(ScheduleState.K_INPUT));
		assertTrue(sched.cancel(plain, ctx));
	}

	/** With no definition, the secret fields are unknown, so the input is withheld. */
	@Test
	public void testListWithholdsInputOfUnresolvableOp() {
		Blob handle = sched.schedule(s("v/no/such/op"), Maps.of(s("token"), s("raw")), ctx, future());
		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertFalse(rec.containsKey(ScheduleState.K_INPUT), "input withheld: " + rec);
		assertEquals(s("v/no/such/op"), rec.get(ScheduleState.K_OP), "the event itself still lists");
		assertTrue(sched.cancel(handle, ctx));
	}

	@Test
	public void testDescriptionIsStoredAndListed() {
		AString text = s("Check Moltbook and summarise anything new");
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("x"), ctx, future(), null, null, text);
		assertEquals(text, only(sched.list(ctx)).get(ScheduleState.K_DESCRIPTION));
		assertTrue(sched.cancel(handle, ctx));

		Blob bare = sched.schedule(s("v/test/ops/echo"), s("x"), ctx, future());
		assertFalse(only(sched.list(ctx)).containsKey(ScheduleState.K_DESCRIPTION), "optional");
		assertTrue(sched.cancel(bare, ctx));
	}

	@Test
	public void testValidateDescription() {
		assertNull(Scheduler.validateDescription(null));
		AString longest = s("d".repeat(Scheduler.MAX_DESCRIPTION_CHARS));
		assertEquals(longest, Scheduler.validateDescription(longest));

		IllegalArgumentException notString = assertThrows(IllegalArgumentException.class,
			() -> Scheduler.validateDescription(CVMLong.create(42)));
		assertEquals(Scheduler.DESCRIPTION_NOT_STRING, notString.getMessage());

		AString tooLong = s("d".repeat(Scheduler.MAX_DESCRIPTION_CHARS + 1));
		IllegalArgumentException overLong = assertThrows(IllegalArgumentException.class,
			() -> sched.schedule(s("v/test/ops/echo"), s("x"), ctx, future(), null, null, tooLong));
		assertEquals(Scheduler.DESCRIPTION_TOO_LONG, overLong.getMessage());
		assertTrue(sched.list(ctx).isEmpty(), "a refused event is not scheduled");
	}

	/** An event stored before by, description and created were recorded still lists, without them. */
	@Test
	public void testLegacyEventListsWithoutNewFields() {
		long time = future();
		byte[] bs = new byte[16];
		Utils.writeLong(bs, 0, time);
		Utils.writeLong(bs, 8, ThreadLocalRandom.current().nextLong());
		Blob key = Blob.wrap(bs);
		AMap<AString, ACell> legacy = Maps.of(
			ScheduleState.K_OP, s("v/test/ops/echo"),
			ScheduleState.K_OWNER, did,
			ScheduleState.K_TIME, CVMLong.create(time),
			ScheduleState.K_INPUT, s("legacy"));
		engine.getVenueState().schedule().cursor().path(ScheduleState.K_EVENTS).updateAndGet(cur -> {
			@SuppressWarnings("unchecked")
			Index<Blob, ACell> events = (cur instanceof Index) ? (Index<Blob, ACell>) cur : Index.none();
			return events.assoc(key, legacy);
		});

		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertEquals(key, rec.get(ScheduleState.K_HANDLE));
		assertEquals(s("legacy"), rec.get(ScheduleState.K_INPUT));
		assertFalse(rec.containsKey(ScheduleState.K_BY), "by: " + rec);
		assertFalse(rec.containsKey(ScheduleState.K_DESCRIPTION), "description: " + rec);
		assertFalse(rec.containsKey(ScheduleState.K_CREATED), "created: " + rec);
		assertTrue(sched.cancel(key, ctx));
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

		TestEngine.awaitCondition(() -> sched.list(ctx).isEmpty(), 5_000,
			() -> "immediately-due event remained scheduled: " + sched.list(ctx));
		assertTrue(sched.list(ctx).isEmpty(),
			"the alarm should fire and remove an immediately-due event");
	}

	// --------------------------------------------------------------- recurrence

	@Test
	public void testNextTimeKeepsPhaseAndSkipsMissedSlots() {
		AMap<AString, ACell> every = Maps.of(ScheduleState.K_EVERY, CVMLong.create(10));
		assertEquals(10, Scheduler.nextTime(every, 0, 5), "normal advance: time + every");
		assertEquals(20, Scheduler.nextTime(every, 0, 10), "the slot at now has just fired");
		assertEquals(30, Scheduler.nextTime(every, 0, 25), "missed slots 10 and 20 are skipped, phase kept");
		assertEquals(1010, Scheduler.nextTime(every, 1000, 1004), "phase anchored at the original time");
	}

	@Test
	public void testValidateRepeatAcceptsOnlyEvery() {
		assertEquals(everyHour(), Scheduler.validateRepeat(everyHour()));
		assertThrows(IllegalArgumentException.class, () ->
			Scheduler.validateRepeat(Maps.of(s("cron"), s("0 9 * * 1-5"))),
			"unknown recurrence forms are rejected, not guessed at");
		assertThrows(IllegalArgumentException.class, () ->
			Scheduler.validateRepeat(Maps.of(ScheduleState.K_EVERY, CVMLong.create(HOUR), s("cron"), s("x"))),
			"extra keys are rejected");
		assertThrows(IllegalArgumentException.class, () ->
			Scheduler.validateRepeat(Maps.of(ScheduleState.K_EVERY, CVMLong.create(Scheduler.MIN_REPEAT_MS - 1))),
			"below the floor");
		assertThrows(IllegalArgumentException.class, () ->
			Scheduler.validateRepeat(Maps.of(ScheduleState.K_EVERY, s("3600000"))),
			"every must be an integer");
	}

	/**
	 * An immediately-due recurring event fires, then re-inserts itself one
	 * interval on under the same id — so the handle the caller holds still
	 * cancels it, although the index key has moved with the time prefix.
	 */
	@Test
	public void testRecurringEventReinsertsAndKeepsHandle() throws Exception {
		long start = System.currentTimeMillis();
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("tick"), ctx, start, everyHour(), null, s("hourly tick"));

		TestEngine.awaitCondition(
			() -> !sched.list(ctx).isEmpty() && only(sched.list(ctx)).get(ScheduleState.K_LAST_FIRED) != null,
			5_000, () -> "recurring event did not fire: " + sched.list(ctx));

		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertEquals(CVMLong.create(start + HOUR), rec.get(ScheduleState.K_TIME),
			"re-inserted one interval on from its original slot");
		assertEquals(everyHour(), rec.get(ScheduleState.K_REPEAT));
		assertNotEquals(handle, rec.get(ScheduleState.K_HANDLE), "the index key moves with the time prefix");
		assertTrue(((CVMLong) rec.get(ScheduleState.K_LAST_FIRED)).longValue() >= start);
		assertEquals(did, rec.get(ScheduleState.K_BY), "attribution moves with the event");
		assertEquals(s("hourly tick"), rec.get(ScheduleState.K_DESCRIPTION));
		assertInstanceOf(CVMLong.class, rec.get(ScheduleState.K_CREATED), "created moves with the event");

		assertTrue(sched.cancel(handle, ctx), "the original handle must still cancel the moved event");
		assertTrue(sched.list(ctx).isEmpty());
	}

	@Test
	public void testTriggerRecurringKeepsSchedule() throws Exception {
		long t = future();
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("x"), ctx, t, everyHour(), null);

		assertEquals(s("x"), sched.trigger(handle, ctx).get(5, TimeUnit.SECONDS));

		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertEquals(handle, rec.get(ScheduleState.K_HANDLE), "time unchanged, so the key is unchanged");
		assertEquals(CVMLong.create(t), rec.get(ScheduleState.K_TIME), "an early trigger does not disturb the schedule");
		assertTrue(rec.get(ScheduleState.K_LAST_FIRED) instanceof CVMLong);
		assertTrue(sched.cancel(handle, ctx));
	}

	// ----------------------------------------------------------------- tracking

	/**
	 * A tracked fire is a durable Job in the owner's history; the recurring
	 * record carries only its ID. The outcome is read from the Job — the
	 * scheduler stores no status of its own.
	 */
	@Test
	public void testTrackedFireRecordsLastJob() throws Exception {
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("tracked"), ctx, future(), everyHour(), Boolean.TRUE);
		assertEquals(CVMBool.TRUE, only(sched.list(ctx)).get(ScheduleState.K_TRACK));

		CompletableFuture<ACell> fired = sched.trigger(handle, ctx);

		// The claim and the Job ID are one write: lastJob is visible as soon as
		// trigger returns, before the Job has necessarily finished.
		AMap<AString, ACell> rec = only(sched.list(ctx));
		Blob jobID = (Blob) rec.get(ScheduleState.K_LAST_JOB);
		assertNotNull(jobID, "lastJob must be committed with the claim, not after: " + rec);
		Job job = engine.jobs().getJob(jobID, ctx);
		assertNotNull(job, "the tracked fire's Job must be in the owner's history");

		assertEquals(s("tracked"), fired.get(5, TimeUnit.SECONDS));
		assertEquals(Status.COMPLETE, job.getStatus());
		assertTrue(sched.cancel(handle, ctx));
	}

	/**
	 * A tracked fire whose Job cannot even be prepared (here: an unresolvable
	 * operation) is still claimed — the schedule advances exactly as an
	 * untracked failure would — and the error reaches the caller. No half
	 * state: no Job, no lastJob, event still scheduled.
	 */
	@Test
	public void testTrackedPrepareFailureStillClaims() {
		Blob handle = sched.schedule(s("v/no/such/op"), s("x"), ctx, future(), everyHour(), Boolean.TRUE);

		ExecutionException ex = assertThrows(ExecutionException.class,
			() -> sched.trigger(handle, ctx).get(5, TimeUnit.SECONDS));
		assertTrue(ex.getCause() instanceof IllegalArgumentException, String.valueOf(ex.getCause()));

		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertTrue(rec.get(ScheduleState.K_LAST_FIRED) instanceof CVMLong, "the fire was claimed");
		assertNull(rec.get(ScheduleState.K_LAST_JOB), "no Job was created, so none is recorded");
		assertTrue(sched.cancel(handle, ctx));
	}

	@Test
	public void testUntrackedFireLeavesNoJob() throws Exception {
		Blob handle = sched.schedule(s("v/test/ops/echo"), s("quiet"), ctx, future(), everyHour(), Boolean.FALSE);
		long before = engine.jobs().getJobs(ctx).count();

		assertEquals(s("quiet"), sched.trigger(handle, ctx).get(5, TimeUnit.SECONDS));

		AMap<AString, ACell> rec = only(sched.list(ctx));
		assertEquals(CVMBool.FALSE, rec.get(ScheduleState.K_TRACK));
		assertTrue(rec.get(ScheduleState.K_LAST_FIRED) instanceof CVMLong, "lastFired is always kept — it is cheap");
		assertNull(rec.get(ScheduleState.K_LAST_JOB), "no durable Job, so nothing to point at");
		assertEquals(before, engine.jobs().getJobs(ctx).count(), "an untracked fire leaves no Job behind");
		assertTrue(sched.cancel(handle, ctx));
	}

	/** Resolution order: operator force → caller's explicit choice → venue default. */
	@Test
	public void testVenueDefaultAppliesOnlyWhenCallerSilent() {
		Engine eng = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SCHEDULER, Maps.of(Config.TRACK_JOBS, CVMBool.TRUE)));
		try {
			Scheduler ds = eng.gridScheduler();
			RequestContext silent = RequestContext.of(s("did:test:scheduler-silent"));
			RequestContext optOut = RequestContext.of(s("did:test:scheduler-optout"));
			ds.schedule(s("v/test/ops/echo"), s("a"), silent, future(), null, null);
			ds.schedule(s("v/test/ops/echo"), s("b"), optOut, future(), null, Boolean.FALSE);
			assertEquals(CVMBool.TRUE, only(ds.list(silent)).get(ScheduleState.K_TRACK),
				"no preference stated → venue default (trackJobs: true)");
			assertEquals(CVMBool.FALSE, only(ds.list(optOut)).get(ScheduleState.K_TRACK),
				"an explicit choice beats the venue default");
		} finally {
			eng.close();
		}
	}

	/** Operator force: an event that asked NOT to be tracked is tracked anyway. */
	@Test
	public void testOperatorForceOverridesCallerChoice() throws Exception {
		Engine eng = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SCHEDULER, Maps.of(Config.FORCE_TRACK_JOBS, CVMBool.TRUE)));
		try {
			Engine.addDemoAssets(eng);   // v/test/ops/echo
			Scheduler fs = eng.gridScheduler();
			RequestContext fctx = RequestContext.of(s("did:test:scheduler-forced"));
			Blob handle = fs.schedule(s("v/test/ops/echo"), s("forced"), fctx, future(), everyHour(), Boolean.FALSE);
			assertEquals(CVMBool.TRUE, only(fs.list(fctx)).get(ScheduleState.K_TRACK),
				"effective tracking reflects the operator force");

			fs.trigger(handle, fctx).get(5, TimeUnit.SECONDS);
			Blob jobID = (Blob) only(fs.list(fctx)).get(ScheduleState.K_LAST_JOB);
			assertNotNull(jobID, "forced tracking did not record a Job: " + fs.list(fctx));
			assertNotNull(eng.jobs().getJob(jobID, fctx));
		} finally {
			eng.close();
		}
	}
}
