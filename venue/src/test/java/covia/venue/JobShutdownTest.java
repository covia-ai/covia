package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
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
import convex.lattice.cursor.Root;
import covia.adapter.AAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Venue shutdown and the Jobs in flight at the time (JOBS.md § Shutdown):
 * in-flight work gets a grace window to finish — no longer than it needs —
 * after which each remaining Job's adapter suspends it. Bounded in-process
 * work is cancelled; a pausable Job is paused and comes back live at the next
 * boot through {@code recoverJob}. In-process engines only: no venue is
 * launched.
 */
public class JobShutdownTest {
	private static final AString HOLD = Strings.create("v/test/ops/hold");
	private static final AString NEVER = Strings.create("v/test/ops/never");

	/** A grace window no test outlasts: a close that waited it out would never return. */
	private static final long GRACE_FOREVER = TimeUnit.DAYS.toMillis(1);

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

	/** In-process work that runs until the test releases {@code gate}. */
	private static Job held(Engine e, RequestContext ctx, TestAdapter.TestGate gate, String gateName) throws Exception {
		Job job = e.jobs().invokeOperation(HOLD, Maps.of("gate", gateName), ctx);
		assertTrue(gate.awaitEntered(10, TimeUnit.SECONDS), "the work is running");
		awaitStatus(job, Status.STARTED);
		return job;
	}

	/** Closes the engine on its own thread, so the test can act while close runs. */
	private static CompletableFuture<Void> closeAsync(Engine e) {
		return CompletableFuture.runAsync(e::close, r -> Thread.ofVirtual().start(r));
	}

	private static void awaitStatus(Job job, AString status) {
		TestEngine.awaitJob(job, j -> status.equals(j.getStatus()), 10_000, () -> "job did not reach " + status);
	}

	private static String error(Job job) {
		AString e = RT.ensureString(job.getData().get(Fields.ERROR));
		return (e != null) ? e.toString() : null;
	}

	@Test
	public void shutdownWaitsOnlyAsLongAsWorkNeeds() throws Exception {
		Engine e = engine(GRACE_FOREVER);
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceShutdownQuick"));
		String gateName = "shutdown-quick-" + System.identityHashCode(e);
		Job quick;
		CompletableFuture<Void> closed;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			quick = held(e, alice, gate, gateName);
			closed = closeAsync(e);
			gate.release();
			// With a grace window of a day, close returns only because the work finished.
			closed.get(10, TimeUnit.SECONDS);
		}
		assertEquals(Status.COMPLETE, quick.getStatus(),
			"work finishing inside the grace window keeps its real outcome");
	}

	@Test
	public void inFlightWorkIsCancelledWhenGraceExpires() throws Exception {
		Engine e = engine(100);
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceShutdownSlow"));
		String gateName = "shutdown-slow-" + System.identityHashCode(e);
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			Job slow = held(e, alice, gate, gateName);
			// The work never finishes on its own, so close returns only once the grace expires.
			closeAsync(e).get(10, TimeUnit.SECONDS);
			assertEquals(Status.CANCELLED, slow.getStatus(), "bounded in-process work is cancelled at shutdown");
			assertEquals(AAdapter.VENUE_SHUT_DOWN, error(slow), "the cancellation names its reason");
		}
	}

	@Test
	public void pausableJobIsPausedAndRestoredAtBoot() throws Exception {
		AMap<AString, ACell> config = config(0);
		Root<Index<Keyword, ACell>> cursor = Cursors.root(convex.core.data.Index.<convex.core.data.Keyword, ACell>none());
		AKeyPair keyPair = AKeyPair.generate();
		RequestContext alice = RequestContext.of(Strings.create("did:key:zAliceShutdownParked"));

		Engine first = covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(keyPair, config).start();
		Engine.addDemoAssets(first);
		Job parked = first.jobs().invokeOperation(NEVER, Maps.empty(), alice); // registers a pause hook
		Job slow;
		String gateName = "shutdown-parked-" + System.identityHashCode(first);
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			slow = held(first, alice, gate, gateName);
			awaitStatus(parked, Status.STARTED);
			first.syncState();
			first.close();
		}
		assertEquals(Status.PAUSED, parked.getStatus(), "a pausable job is suspended, not cancelled");
		assertEquals(Status.CANCELLED, slow.getStatus(), "in-process work is cancelled with zero grace");

		Engine second = covia.venue.CoviaApplication.connect(new convex.lattice.LatticeHost<>(cursor, null)).mount(keyPair, config).start();
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
