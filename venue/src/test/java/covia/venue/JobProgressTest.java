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
 * Progress is what a running Job's work is doing now, held in the venue's
 * record of it as current state: replaced in place, never a step in the
 * Job's history, and the last one kept when the Job finishes.
 */
public class JobProgressTest {

	private final Engine engine = TestEngine.ENGINE;

	@Test
	public void progressIsTheRecordsCurrentStateNotItsHistory(TestInfo info) {
		AString did = TestEngine.uniqueDID(info);
		RequestContext ctx = RequestContext.of(did);
		Job job = engine.jobs().invokeOperation("v/test/ops/progress", Maps.of("hold", true), ctx);
		TestEngine.awaitCondition(() -> "step 2".equals(Job.progressMessage(job.getData().get(Fields.PROGRESS))),
			5_000, () -> "no progress: " + job.getData());

		AMap<AString, ACell> recorded = record(did, job);
		assertNotNull(recorded, "the Job is durable");
		assertEquals("step 2", Job.progressMessage(recorded.get(Fields.PROGRESS)), "its record holds the progress");
		assertNull(recorded.get(Strings.intern("prev")), "no earlier state is embedded");
		AMap<AString, ACell> running = engine.jobs().getJobData(job.getID(), ctx);
		assertEquals("step 2", Job.progressMessage(running.get(Fields.PROGRESS)), "a reader of the running Job sees it");

		// A lifecycle change carries the progress on with the rest of the record.
		job.update(d -> d.assoc(Fields.OUTPUT, Strings.create("partial")));
		recorded = record(did, job);
		assertEquals(Strings.create("partial"), recorded.get(Fields.OUTPUT));
		assertEquals("step 2", Job.progressMessage(recorded.get(Fields.PROGRESS)));

		job.completeWith(Strings.create("finished"));
		assertEquals("step 2", Job.progressMessage(record(did, job).get(Fields.PROGRESS)),
			"the finished record keeps the last progress");
	}

	private AMap<AString, ACell> record(AString did, Job job) {
		return engine.getVenueState().users().get(did).getJob(job.getID());
	}
}
