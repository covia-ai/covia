package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Job;

/**
 * The same scenarios on both kinds of clock a venue runs on (EMBEDDING.md,
 * Time): a driven clock, where nothing starts until the test updates the
 * application, and the system clock, where the update timer starts what is due.
 * Only how due events get started differs. Moving a driven clock is
 * {@link SteppedSchedulerTest}.
 */
class BothClocksTest {

	private static final long HOUR = 3_600_000L;
	private static final AString HOLD = Strings.create("v/test/ops/hold");
	private static final AMap<AString, ACell> EVERY_HOUR = Maps.of(Schedule.K_EVERY, CVMLong.create(HOUR));

	private static Engine driven;
	private static Engine realTime;

	@BeforeAll
	static void launch() {
		AMap<AString, ACell> config = Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		driven = Engine.createTemp(DrivenClock.at(Instant.parse("2022-01-01T00:00:00Z")), config);
		Engine.addDemoAssets(driven);
		realTime = Engine.createRealTime(config);
		Engine.addDemoAssets(realTime);
	}

	@AfterAll
	static void close() {
		driven.close();
		realTime.close();
	}

	@Nested
	class OnADrivenClock extends Scenarios {
		@Override Engine engine() { return driven; }
		@Override void startDue() { driven.application().update(); }
	}

	@Nested
	class OnTheSystemClock extends Scenarios {
		@Override Engine engine() { return realTime; }
		@Override void startDue() { /* the update timer does it */ }
	}

	abstract static class Scenarios {

		abstract Engine engine();

		/** Has what is due started: the only step that differs between the clocks. */
		abstract void startDue();

		private long now() {
			return engine().currentTimestamp().longValue();
		}

		private RequestContext user(TestInfo info) {
			AString did = TestEngine.uniqueDID(info);
			engine().getVenueState().users().ensure(did);
			return RequestContext.of(did);
		}

		@Test
		void aJobIsStampedByTheVenuesClock(TestInfo info) {
			RequestContext ctx = user(info);
			long before = now();
			Job job = engine().jobs().invokeOperation("v/test/ops/echo", Maps.of("n", 1), ctx);
			job.awaitResult(5000);
			long after = now();
			long created = ((CVMLong) engine().jobs().getJobData(job.getID(), ctx).get(Fields.CREATED)).longValue();
			assertTrue(created >= before && created <= after, "created at the venue's time");
		}

		/**
		 * The operation waits at a test gate: an update claims an event before
		 * it dispatches the fire, so once the gate is reached the event has
		 * left the owner's list.
		 */
		@Test
		void aDueEventStarts(TestInfo info) throws Exception {
			RequestContext ctx = user(info);
			Scheduler scheduler = engine().gridScheduler();
			String name = "both-due-" + ctx.getUserDID();
			try (TestAdapter.TestGate gate = TestAdapter.createGate(name)) {
				scheduler.schedule(HOLD, Maps.of("gate", name), ctx, now());
				startDue();
				assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the due event starts");
				assertTrue(scheduler.list(ctx).isEmpty(), "and has left the schedule");
			}
		}

		/**
		 * A recurring event re-inserts itself one interval on under the same
		 * id, so the handle the caller holds still cancels it, although the
		 * index key has moved with the time prefix.
		 */
		@Test
		void aRecurringEventMovesToItsNextSlot(TestInfo info) throws Exception {
			RequestContext ctx = user(info);
			Scheduler scheduler = engine().gridScheduler();
			String name = "both-recurring-" + ctx.getUserDID();
			long start = now();
			try (TestAdapter.TestGate gate = TestAdapter.createGate(name)) {
				Blob handle = scheduler.schedule(HOLD, Maps.of("gate", name), ctx, start, EVERY_HOUR, null,
					Strings.create("hourly tick"));
				startDue();
				assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the recurring event starts");

				AVector<ACell> listed = scheduler.list(ctx);
				assertEquals(1, listed.count());
				ACell rec = listed.get(0);
				assertEquals(CVMLong.create(start + HOUR), RT.getIn(rec, Schedule.K_TIME),
					"re-inserted one interval on from its original slot");
				assertEquals(EVERY_HOUR, RT.getIn(rec, Schedule.K_REPEAT));
				assertNotEquals(handle, RT.getIn(rec, Schedule.K_HANDLE), "the index key moves with the time prefix");
				assertTrue(((CVMLong) RT.getIn(rec, Schedule.K_LAST_FIRED)).longValue() >= start);
				assertEquals(ctx.getUserDID(), RT.getIn(rec, Schedule.K_CALLER), "attribution moves with the event");
				assertEquals(Strings.create("hourly tick"), RT.getIn(rec, Schedule.K_DESCRIPTION));
				assertInstanceOf(CVMLong.class, RT.getIn(rec, Schedule.K_CREATED), "created moves with the event");

				assertTrue(scheduler.cancel(handle, ctx), "the handle first given still cancels the moved event");
				assertTrue(scheduler.list(ctx).isEmpty());
			}
		}

		@Test
		void aParkedTaskRunsWhenItsWakeTimeComes(TestInfo info) throws Exception {
			RequestContext owner = user(info);
			long later = now() + HOUR;
			engine().jobs().invokeOperation("v/ops/agent/create", Maps.of(
				Fields.AGENT_ID, "wake-yield",
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/wakeresponse"),
				Agent.KEY_STATE, Maps.of(Fields.WAKE_TIME, CVMLong.create(later))), owner).awaitResult(5000);
			Agent agent = engine().getVenueState().users().user(owner.getUserDID()).agent(Strings.create("wake-yield"));

			try (AgentWatch watch = AgentWatch.open(engine(), owner.getUserDID(), "wake-yield")) {
				Job job = engine().jobs().invokeOperation("v/ops/agent/request", Maps.of(
					Fields.AGENT_ID, "wake-yield", Fields.INPUT, Maps.of("ask", "come back later")), owner);
				watch.awaitRest(1);
				Blob taskId = job.getID();
				ACell row = agent.getTasks().get(taskId);
				assertTrue(Task.isParked(row), "a yield with a wake time parks the task");
				assertEquals(CVMLong.create(later), RT.getIn(row, Fields.WAKE_TIME));
				assertFalse(job.isFinished(), "the caller's job waits with its task");

				// Before its wake time a parked task is not work, so a plain kick runs nothing.
				engine().jobs().invokeOperation("v/ops/agent/trigger",
					Maps.of(Fields.AGENT_ID, "wake-yield", Fields.FORCE, false), owner).awaitResult(5000);
				assertEquals(1, agent.getTimeline().count());

				// A forced kick runs a cycle, but not on the parked task.
				engine().jobs().invokeOperation("v/ops/agent/trigger",
					Maps.of(Fields.AGENT_ID, "wake-yield"), owner).awaitResult(10_000);
				assertEquals(2, agent.getTimeline().count());
				assertNull(RT.getIn(agent.getTimeline().get(1), Fields.TASKS),
					"a forced kick does not present a parked task");
				assertTrue(Task.isParked(agent.getTasks().get(taskId)));

				// Its wake time comes, the scheduler fires, and the task runs.
				engine().gridScheduler().setThreadWakeTime(owner.getUserDID(), agent,
					Agent.ThreadKind.TASK, taskId, now() - 1);
				startDue();
				watch.awaitRest(3);
				assertNotNull(RT.getIn(agent.getTimeline().get(2), Fields.TASKS),
					"the task's own wake presents it again");
				assertTrue(Task.isParked(agent.getTasks().get(taskId)),
					"it asked for a later wake again, so it is parked again");
				assertFalse(job.isFinished());
			}
		}
	}
}
