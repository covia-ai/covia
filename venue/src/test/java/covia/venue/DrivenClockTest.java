package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.hitl.Hitl;
import covia.lattice.CoviaPath;

/**
 * A venue on a clock the test drives (EMBEDDING.md, Time): what the venue and
 * its adapters write is stamped with the driven time, held still or stepped,
 * never the system clock's.
 */
@Execution(ExecutionMode.SAME_THREAD) // the tests share the venue's clock and move it
class DrivenClockTest {

	/** A time no system clock reads while this test runs. */
	private static final long START = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
	private static final long MINUTE = 60_000;

	/** The venue's clock. Only ever moves forward, so tests may share it. */
	private static final AtomicLong CLOCK = new AtomicLong(START);
	private static Engine engine;

	@BeforeAll
	static void launch() throws IOException {
		CoviaApplication application = CoviaApplication.create(
			VenueContext.create(() -> CVMLong.create(CLOCK.get())));
		engine = application.mount(AKeyPair.generate(),
			new Config(Maps.of(Config.USERS, Maps.of(Config.AUTO_CREATE, true)))).launch();
	}

	@AfterAll
	static void close() {
		engine.close();
	}

	private static AString s(String text) {
		return Strings.create(text);
	}

	private static long time(ACell value, Object... path) {
		ACell v = RT.getIn(value, path);
		assertInstanceOf(CVMLong.class, v, "no time at " + java.util.Arrays.toString(path) + " in " + value);
		return ((CVMLong) v).longValue();
	}

	@Test
	void theVenueStartsAndReportsAtTheDrivenTime() {
		assertEquals(START, time(engine.resolvePath(s("v/info/started"), engine.venueContext())),
			"the venue started before any test moved the clock");
		assertEquals(CLOCK.get(), engine.currentTimestamp().longValue());
		assertEquals(CLOCK.get(), time(engine.getStatus(), Fields.TS), "the status reports the venue's time");
	}

	@Test
	void writesWhileTheClockStandsStillShareItsTime(TestInfo info) {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		long now = CLOCK.addAndGet(MINUTE);

		Job first = engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("n", 1), ctx);
		first.awaitResult(5000);
		Job second = engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("n", 2), ctx);
		second.awaitResult(5000);
		for (Job job : new Job[] {first, second}) {
			AMap<AString, ACell> data = engine.jobs().getJobData(job.getID(), ctx);
			assertEquals(now, time(data, Fields.CREATED), "a job is created at the venue's time");
			assertEquals(now, time(data, Fields.UPDATED), "and finishes at it, the clock unmoved");
		}
		assertTrue(first.getID().compareTo(second.getID()) < 0, "job IDs still increase at one time");

		User user = engine.getVenueState().users().user(ctx.getUserDID());
		assertEquals(now, time(user.get(), "meta", "created"));
		assertEquals(now, time(user.get(), "meta", "updated"));
	}

	@Test
	void tokensAreJudgedByTheVenueClock() {
		AKeyPair alice = AKeyPair.generate();
		AKeyPair bob = AKeyPair.generate();
		AString bobDID = UCAN.toDIDKey(bob.getAccountKey());
		AVector<ACell> read = Vectors.of(Capability.create(
			Strings.create(UCAN.toDIDKey(alice.getAccountKey()) + "/w/"), Capability.CRUD_READ));
		long venueSeconds = CLOCK.get() / 1000;

		// Long expired by the system clock, still in date on the venue's.
		AString inDate = UCAN.create(alice, bob.getAccountKey(), venueSeconds + 3600, read, Vectors.empty()).toJWT(alice);
		AString expired = UCAN.create(alice, bob.getAccountKey(), venueSeconds - 1, read, Vectors.empty()).toJWT(alice);
		assertEquals(1, engine.withTransportGrants(RequestContext.of(bobDID), Vectors.of(inDate)).getProofs().count());
		assertNull(engine.withTransportGrants(RequestContext.of(bobDID), Vectors.of(expired)).getProofs(),
			"a token expired by the venue's clock grants nothing");
	}

	@Test
	void adaptersStampWithTheVenueClock(TestInfo info) {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		long now = CLOCK.addAndGet(MINUTE);

		ACell scheduled = engine.jobs().invokeOperation("v/ops/scheduler/schedule", Maps.of(
			s("operation"), s("v/test/ops/echo"), s("input"), Maps.empty(),
			s("after"), CVMLong.create(MINUTE)), ctx).awaitResult(5000);
		assertEquals(now + MINUTE, time(scheduled, "time"), "an event falls due by the venue's clock");
		ACell listed = engine.jobs().invokeOperation("v/ops/scheduler/list", Maps.empty(), ctx).awaitResult(5000);
		AVector<?> events = (AVector<?>) RT.getIn(listed, "events");
		assertEquals(1, events.count());
		assertEquals(now, time(events.get(0), Fields.CREATED));

		ACell remembered = engine.jobs().invokeOperation("v/ops/memory", Maps.of(
			s("command"), s("remember"), s("text"), s("noted")), ctx).awaitResult(5000);
		assertNotNull(remembered);
		ACell memory = engine.getVenueState().users().user(ctx.getUserDID())
			.workspace().read(CoviaPath.parse("memory"));
		assertEquals(now, time(memory, 0L, "ts"), "a memory is stamped by the venue's clock");

		Job asked = engine.jobs().invokeOperation("v/ops/hitl/request",
			Hitl.request("check").ask(Hitl.text("a", "Anything?")).timeout(3600).build(), ctx);
		AString id = Strings.create(asked.getID().toHexString());
		AMap<AString, ACell> record = engine.getVenueState().users().user(ctx.getUserDID()).inbox().get(id);
		assertEquals(now, time(record, Hitl.CREATED));
		assertEquals(now + 3_600_000, time(record, Hitl.EXPIRES), "a request expires by the venue's clock");
	}
}
