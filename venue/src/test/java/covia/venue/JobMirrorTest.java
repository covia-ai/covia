package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import convex.lattice.cursor.Root;
import covia.adapter.AAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.exception.NotSupportedException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Mirrors ({@link JobManager#mirror}): a recorded job that follows an
 * unrecorded call still running, so a caller who stops waiting has a job to
 * look up that outlasts a restart. {@code v/test/ops/hold} runs until the test
 * releases its gate, so nothing waits on a clock.
 */
public class JobMirrorTest {
	private static final AString HOLD = Strings.create("v/test/ops/hold");

	private final Engine engine = TestEngine.ENGINE;

	/** An unrecorded call to hold that is running at the gate. */
	private static Job heldCall(Engine e, RequestContext ctx, TestAdapter.TestGate gate, AMap<AString, ACell> input)
			throws Exception {
		AtomicReference<Job> call = new AtomicReference<>();
		e.jobs().invokeInternal(HOLD, input, ctx, call::set);
		assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the call is running");
		assertFalse(call.get().isRecorded(), "an internal call is unrecorded");
		return call.get();
	}

	/** Why a job did not complete, whatever its terminal status. */
	private static String error(Job job) {
		AString e = RT.ensureString(job.getData().get(Fields.ERROR));
		return (e != null) ? e.toString() : null;
	}

	private static void awaitFinished(Job job) {
		TestEngine.awaitJob(job, Job::isFinished, 10_000, () -> "job did not finish");
	}

	@Test
	public void aMirrorLooksLikeTheCallAndTakesItsOutcome(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-outcome-" + ctx.getUserDID();
		Job mirror;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job call = heldCall(engine, ctx, gate, Maps.of("gate", gateName, Fields.RESULT, "done-late"));
			mirror = engine.jobs().mirror(call);

			assertTrue(mirror.isRecorded());
			AMap<AString, ACell> record = engine.jobs().getJobData(mirror.getID(), ctx);
			assertNotNull(record, "the mirror can be looked up by its owner");
			assertEquals(Status.STARTED, record.get(Fields.STATUS));
			assertEquals(Strings.create(call.getID().toHexString()), record.get(JobManager.MIRROR_OF));
			for (AString field : new AString[] {Fields.OP, Fields.INPUT, Fields.CALLER, Fields.ADAPTER, Fields.CREATED}) {
				assertEquals(call.getData().get(field), record.get(field), "the mirror copies the call's " + field);
			}

			gate.release();
			awaitFinished(mirror);
		}
		AMap<AString, ACell> record = engine.jobs().getJobData(mirror.getID(), ctx);
		assertEquals(Status.COMPLETE, record.get(Fields.STATUS));
		assertEquals(Strings.create("done-late"), record.get(Fields.OUTPUT), "the mirror takes the call's output");
	}

	@Test
	public void aMirrorTakesTheCallsFailure(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-failure-" + ctx.getUserDID();
		Job mirror;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job call = heldCall(engine, ctx, gate, Maps.of("gate", gateName, Fields.ERROR, "broke-late"));
			mirror = engine.jobs().mirror(call);
			gate.release();
			awaitFinished(mirror);
		}
		AMap<AString, ACell> record = engine.jobs().getJobData(mirror.getID(), ctx);
		assertEquals(Status.FAILED, record.get(Fields.STATUS));
		assertEquals(Strings.create("broke-late"), record.get(Fields.ERROR));
	}

	@Test
	public void aCallThatEndsFirstSettlesItsMirrorAtOnce(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-ended-" + ctx.getUserDID();
		Job call;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			call = heldCall(engine, ctx, gate, Maps.of("gate", gateName, Fields.RESULT, "already"));
			gate.release();
			awaitFinished(call);
		}
		Job mirror = engine.jobs().mirror(call);
		awaitFinished(mirror);
		AMap<AString, ACell> record = engine.jobs().getJobData(mirror.getID(), ctx);
		assertEquals(Status.COMPLETE, record.get(Fields.STATUS));
		assertEquals(Strings.create("already"), record.get(Fields.OUTPUT));
	}

	@Test
	public void cancellingTheMirrorCancelsTheCall(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-cancel-" + ctx.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job call = heldCall(engine, ctx, gate, Maps.of("gate", gateName));
			Job mirror = engine.jobs().mirror(call);
			engine.jobs().cancelJob(mirror.getID(), ctx, "no longer needed");
			awaitFinished(call);
			assertEquals(Status.CANCELLED, mirror.getStatus());
			assertEquals(Status.CANCELLED, call.getStatus(), "the call is cancelled with its mirror");
			assertEquals("no longer needed", error(call), "with the mirror's reason");
		}
	}

	@Test
	public void cancellingTheCallSettlesTheMirror(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-call-cancel-" + ctx.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job call = heldCall(engine, ctx, gate, Maps.of("gate", gateName));
			Job mirror = engine.jobs().mirror(call);
			call.cancel("caller gave up");
			awaitFinished(mirror);
			assertEquals(Status.CANCELLED, mirror.getStatus());
			assertEquals("caller gave up", error(mirror));
		}
	}

	@Test
	public void aRecordedCallIsItsOwnJob(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-recorded-" + ctx.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job call = engine.jobs().invokeOperation(HOLD, Maps.of("gate", gateName), ctx);
			assertTrue(call.isRecorded());
			assertSame(call, engine.jobs().mirror(call), "a recorded call needs no mirror");
		}
	}

	@Test
	public void noAdapterRunsAMirror(TestInfo info) throws Exception {
		RequestContext ctx = RequestContext.of(TestEngine.uniqueDID(info));
		String gateName = "mirror-verbs-" + ctx.getUserDID();
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job mirror = engine.jobs().mirror(heldCall(engine, ctx, gate, Maps.of("gate", gateName)));
			NotSupportedException message = assertThrows(NotSupportedException.class,
				() -> engine.jobs().deliverMessage(mirror.getID(), Maps.of("text", "hello"), ctx));
			assertTrue(message.getMessage().startsWith(JobManager.JOB_NO_MESSAGES), message.getMessage());
			assertThrows(NotSupportedException.class, () -> engine.jobs().pauseJob(mirror.getID(), ctx));
		}
	}

	@Test
	public void aRestartFailsAMirrorAndShutdownCancelsIt() throws Exception {
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, 0));
		Root<Index<Keyword, ACell>> cursor = Cursors.root(Index.<Keyword, ACell>none());
		AKeyPair keyPair = AKeyPair.generate();
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceMirrorRestart"));

		Engine first = new Engine(config, CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)), keyPair).start();
		Engine.addDemoAssets(first);
		String gateName = "mirror-restart-" + System.identityHashCode(first);
		Job call;
		Job mirror;
		Root<Index<Keyword, ACell>> crashed;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			call = heldCall(first, alice, gate, Maps.of("gate", gateName));
			mirror = first.jobs().mirror(call);
			first.syncState();
			// The store as a crash would leave it, with the mirror still running
			crashed = Cursors.root(cursor.get());
			first.close();
		}
		assertEquals(Status.CANCELLED, mirror.getStatus(), "shutdown cancels a mirror by the default path");
		assertEquals(AAdapter.VENUE_SHUT_DOWN, error(mirror));
		assertEquals(Status.CANCELLED, call.getStatus(), "and with it the call");

		Engine second = new Engine(config, CoviaApplication.connect(new convex.lattice.LatticeHost<>(crashed, null)), keyPair).start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			AMap<AString, ACell> recovered = second.jobs().getJobData(mirror.getID(), alice);
			assertEquals(Status.FAILED, recovered.get(Fields.STATUS),
				"a restart fails a mirror whose call was lost, as for any interrupted job");
			assertNull(second.jobs().getJob(mirror.getID()), "nothing of it stays live");
		} finally {
			second.close();
		}
	}
}
