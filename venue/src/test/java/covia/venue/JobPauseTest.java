package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.NotSupportedException;
import covia.grid.Job;
import covia.grid.Status;

/** Pausing and resuming a job asks the adapter that runs it. */
public class JobPauseTest {

	private static final String SUSPENDS = "suspends";
	private static final String RESTARTS = "restarts";

	/** An adapter whose jobs say in their input what it can do for them, recording what it is asked. */
	private static final class PauseProbe extends AAdapter {
		final ConcurrentHashMap<Blob, AString> pausedAt = new ConcurrentHashMap<>();
		final ConcurrentHashMap<Blob, AString> resumedAt = new ConcurrentHashMap<>();

		private static boolean says(Job job, String ability) {
			return CVMBool.TRUE.equals(RT.getIn(job.getData(), Fields.INPUT, ability));
		}

		@Override public String getName() { return "pause-probe"; }
		@Override public String getDescription() { return "Records what pausing a job asks of its adapter"; }
		@Override public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			job.start(() -> {});
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return new CompletableFuture<>();
		}
		@Override public boolean canPause(Job job) { return says(job, SUSPENDS); }
		@Override public void pause(Job job) { assertNull(pausedAt.put(job.getID(), job.getStatus()), "pause runs once"); }
		@Override public boolean canResume(Job job) { return says(job, RESTARTS); }
		@Override public void resume(Job job) { assertNull(resumedAt.put(job.getID(), job.getStatus()), "resume runs once"); }
	}

	private static final PauseProbe PROBE = new PauseProbe();
	static { TestEngine.ENGINE.registerAdapter(PROBE); }

	private final Engine engine = TestEngine.ENGINE;

	private Job started(AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, "pause-probe:wait")), input, engine.venueContext());
		TestEngine.awaitJob(job, j -> Status.STARTED.equals(j.getStatus()), 5000, () -> "the job did not start");
		return job;
	}

	@Test
	public void testTheAdapterActsOnceEachChangeHasCommitted() {
		Job job = started(Maps.of(SUSPENDS, true, RESTARTS, true));
		assertTrue(job.isPausable());

		job.pause();
		assertEquals(Status.PAUSED, job.getStatus());
		assertEquals(Status.PAUSED, PROBE.pausedAt.get(job.getID()), "it suspends a job already paused");

		job.resume();
		assertEquals(Status.STARTED, job.getStatus());
		assertEquals(Status.STARTED, PROBE.resumedAt.get(job.getID()), "it restarts a job already started");
		job.cancel();
	}

	@Test
	public void testAJobItsAdapterCannotSuspendRefusesToPause() {
		Job job = started(Maps.of(RESTARTS, true));
		assertFalse(job.isPausable());

		assertThrows(NotSupportedException.class, job::pause);

		assertEquals(Status.STARTED, job.getStatus(), "a status flip while work continues is not a pause");
		assertNull(PROBE.pausedAt.get(job.getID()));
		job.cancel();
	}

	@Test
	public void testAJobItsAdapterCannotRestartRefusesToResume() {
		Job job = started(Maps.of(SUSPENDS, true));
		job.pause();

		assertThrows(NotSupportedException.class, job::resume);

		assertEquals(Status.PAUSED, job.getStatus());
		assertNull(PROBE.resumedAt.get(job.getID()));
		job.cancel();
	}
}
