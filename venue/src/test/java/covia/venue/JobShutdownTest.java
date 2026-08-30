package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

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
import convex.lattice.cursor.RootLatticeCursor;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;

/**
 * Venue shutdown and the Jobs in flight at the time (JOBS.md § Shutdown):
 * in-flight work gets a grace window to finish — no longer than it needs —
 * after which each remaining Job's adapter suspends it. Bounded in-process
 * work is cancelled; a pausable Job is paused and comes back live at the next
 * boot through {@code recoverJob}. In-process engines only: no venue is
 * launched.
 */
public class JobShutdownTest {
	private static final AString DELAY = Strings.create("v/test/ops/delay");
	private static final AString NEVER = Strings.create("v/test/ops/never");
	private static final AString ECHO = Strings.create("v/test/ops/echo");

	private static AMap<AString, ACell> config(long graceMs) {
		return Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.SHUTDOWN, Maps.of(Config.GRACE_MS, graceMs));
	}

	private static Engine engine(long graceMs) {
		Engine e = Engine.createTemp(config(graceMs));
		Engine.addDemoAssets(e);
		return e;
	}

	private static Job delay(Engine e, RequestContext ctx, long ms) {
		return e.jobs().invokeOperation(DELAY, Maps.of(
			Fields.OPERATION, ECHO, Fields.DELAY, ms, Fields.INPUT, Maps.empty()), ctx);
	}

	private static long closeTimed(Engine e) {
		long started = System.nanoTime();
		e.close();
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
	}

	private static void awaitStatus(Job job, AString status) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!status.equals(job.getStatus()) && System.nanoTime() < deadline) Thread.sleep(5);
		assertEquals(status, job.getStatus());
	}

	private static String error(Job job) {
		AString e = RT.ensureString(job.getData().get(Fields.ERROR));
		return (e != null) ? e.toString() : null;
	}

	@Test
	public void shutdownWaitsOnlyAsLongAsWorkNeeds() throws Exception {
		Engine e = engine(10_000);
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceShutdownQuick"));
		Job quick = delay(e, alice, 150);
		long closeMs = closeTimed(e);
		assertEquals(Status.COMPLETE, quick.getStatus(),
			"work finishing inside the grace window keeps its real outcome");
		assertTrue(closeMs < 5000,
			"close returned when the work finished, not after the full grace: " + closeMs + " ms");
	}

	@Test
	public void inFlightWorkIsCancelledWhenGraceExpires() throws Exception {
		Engine e = engine(100);
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceShutdownSlow"));
		Job slow = delay(e, alice, 60_000);
		awaitStatus(slow, Status.STARTED);
		long closeMs = closeTimed(e);
		assertEquals(Status.CANCELLED, slow.getStatus(), "bounded in-process work is cancelled at shutdown");
		assertEquals(AAdapter.VENUE_SHUT_DOWN, error(slow), "the cancellation names its reason");
		assertTrue(closeMs < 5000, "close did not wait out the 60 s delay: " + closeMs + " ms");
	}

	@Test
	public void pausableJobIsPausedAndRestoredAtBoot() throws Exception {
		AMap<AString, ACell> config = config(0);
		RootLatticeCursor<Index<Keyword, ACell>> cursor = Cursors.createLattice(Covia.ROOT);
		AKeyPair keyPair = AKeyPair.generate();
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceShutdownParked"));

		Engine first = new Engine(config, cursor, keyPair).start();
		Engine.addDemoAssets(first);
		Job parked = first.jobs().invokeOperation(NEVER, Maps.empty(), alice); // registers a pause hook
		Job slow = delay(first, alice, 60_000);
		awaitStatus(parked, Status.STARTED);
		awaitStatus(slow, Status.STARTED);
		first.syncState();
		first.close();
		assertEquals(Status.PAUSED, parked.getStatus(), "a pausable job is suspended, not cancelled");
		assertEquals(Status.CANCELLED, slow.getStatus(), "in-process work is cancelled with zero grace");

		Engine second = new Engine(config, cursor, keyPair).start();
		try {
			Engine.addDemoAssets(second);
			second.jobs().recoverJobs();
			AMap<AString, ACell> restored = second.jobs().getJobData(parked.getID(), alice);
			assertEquals(Status.PAUSED, restored.get(Fields.STATUS),
				"a suspended job is restored live at boot, not stabilised");
			AMap<AString, ACell> cancelled = second.jobs().getJobData(slow.getID(), alice);
			assertEquals(Status.CANCELLED, cancelled.get(Fields.STATUS));
		} finally {
			second.close();
		}
	}
}
