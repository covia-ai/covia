package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;

public class JobTest {

	@Test public void testIDParse() {
		assertEquals(Strings.create("1234"),Job.parseID("0x1234"));
		assertEquals(Strings.create("1234"),Job.parseID(Strings.create("0x1234")));
	}
	
	@Test public void testBuild() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.PENDING
		);
		Job job=Job.create(data);
		assertFalse(job.isComplete());
		assertFalse(job.isFinished());
	}
	
	@Test public void testComplete() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.COMPLETE,
			Fields.OUTPUT,1
		);
		Job job=Job.create(data);
		assertTrue(job.isComplete());
		assertTrue(job.isFinished());
	}
	
	@Test public void testFailed() {
		AMap<AString,ACell> data = Maps.of(
			Fields.ID,"123456",
			Fields.JOB_STATUS_FIELD,Status.FAILED
		);
		Job job=Job.create(data);
		assertFalse(job.isComplete());
		assertTrue(job.isFinished());
	}
}
