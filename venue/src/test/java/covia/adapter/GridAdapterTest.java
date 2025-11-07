package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;

class GridAdapterTest {

	@Test
	void runLocalOperation() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:run", Maps.of(
				Fields.OPERATION, "jvm:stringConcat",
				Fields.INPUT, Maps.of(
					"first", "Hello",
					"second", "Grid"
				)));

		assertNotNull(job, "Job should not be null");
		assertEquals(Status.COMPLETE, job.getStatus(), "Local grid run should complete successfully");
		assertEquals("HelloGrid", RT.getIn(job.getOutput(), "result").toString());
	}

	@Test
	void runRemoteOperation() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:run", Maps.of(
				Fields.VENUE, TestServer.BASE_URL,
				Fields.OPERATION, "jvm:stringConcat",
				Fields.INPUT, Maps.of(
					"first", "Remote",
					"second", "Run"
				)));

		assertNotNull(job, "Job should not be null");
		assertEquals(Status.COMPLETE, job.getStatus(), "Remote grid run should complete successfully");
		assertEquals("RemoteRun", RT.getIn(job.getOutput(), "result").toString());
	}

	@Test
	void invokeLocalOperation() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:invoke", Maps.of(
				Fields.OPERATION, "jvm:stringConcat",
				Fields.INPUT, Maps.of(
					"first", "Hello",
					"second", "Async"
				)));

		assertNotNull(job, "Job should not be null");
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals(Status.COMPLETE, RT.ensureString(RT.getIn(job.getOutput(), Fields.JOB_STATUS_FIELD)));
	}

	@Test
	void invokeRemoteOperation() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:invoke", Maps.of(
				Fields.VENUE, TestServer.BASE_URL,
				Fields.OPERATION, "jvm:stringConcat",
				Fields.INPUT, Maps.of(
					"first", "Async",
					"second", "Remote"
				)));

		assertNotNull(job, "Job should not be null");
		assertEquals(Status.COMPLETE, job.getStatus());
		assertNotNull(RT.getIn(job.getOutput(), Fields.ID), "Job output should include a job ID");
	}
}

