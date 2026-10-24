package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;

/**
 * Progress is what a running Job's work is doing now: shown to whoever
 * reads the running Job, never written to the venue's record of it.
 */
public class JobProgressTest {

	private final Engine engine = TestEngine.ENGINE;

	@Test
	public void progressIsShownWhileRunningAndNeverRecorded(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext ctx = RequestContext.of(did);
		Job job = engine.jobs().invokeOperation("v/test/ops/progress", Maps.of("hold", true), ctx);
		TestEngine.awaitCondition(() -> "step 2".equals(Job.progressMessage(job.getData().get(Fields.PROGRESS))),
			5_000, () -> "no progress: " + job.getData());

		AMap<AString, ACell> running = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals("step 2", Job.progressMessage(running.get(Fields.PROGRESS)), "a reader of the running Job sees it");

		// A recorded change while the work is in flight records the change, not the progress.
		job.update(d -> d.assoc(Fields.OUTPUT, Strings.create("partial")));
		AMap<AString, ACell> recorded = record(did, job);
		assertNotNull(recorded, "the Job is durable");
		assertEquals(Strings.create("partial"), recorded.get(Fields.OUTPUT));
		assertNull(recorded.get(Fields.PROGRESS), "its record holds no progress");
		assertNull(RT.getIn(recorded, Job.PREV, Fields.PROGRESS), "nor does any earlier state");
		assertEquals("step 2", Job.progressMessage(job.getData().get(Fields.PROGRESS)), "while the work goes on");

		job.completeWith(Strings.create("finished"));
		assertNull(job.getData().get(Fields.PROGRESS), "a finished Job has none");
		assertNull(record(did, job).get(Fields.PROGRESS));
	}

	private AMap<AString, ACell> record(AString did, Job job) {
		return engine.getVenueState().users().get(did).getJob(job.getID());
	}
}
