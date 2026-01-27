package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
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
		assertEquals(Status.COMPLETE, RT.ensureString(RT.getIn(job.getOutput(), Fields.STATUS)));
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

	@Test
	void jobStatusLocal() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:invoke", Maps.of(
				Fields.OPERATION, "jvm:stringConcat",
				Fields.INPUT, Maps.of("first", "status", "second", "check")));
		AString jobId = RT.ensureString(RT.getIn(job.getOutput(), Fields.ID));
		assertNotNull(jobId);

		Job statusJob = covia.invokeSync("grid:jobStatus", Maps.of(Fields.ID, jobId));
		assertEquals(Status.COMPLETE, statusJob.getStatus());
		assertEquals(Status.COMPLETE, RT.getIn(statusJob.getOutput(), Fields.STATUS));
	}

	@Test
	void jobResultLocal() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:invoke", Maps.of(
				Fields.OPERATION, "jvm:stringConcat",
				Fields.INPUT, Maps.of("first", "result", "second", "wait")));
		AString jobId = RT.ensureString(RT.getIn(job.getOutput(), Fields.ID));

		Job resultJob = covia.invokeSync("grid:jobResult", Maps.of(Fields.ID, jobId));
		assertEquals(Status.COMPLETE, resultJob.getStatus());
		assertEquals("resultwait", RT.getIn(resultJob.getOutput(), Fields.RESULT).toString());
	}

	@Test
	void jobResultLocalFailure() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job failJob = covia.invokeSync("grid:invoke", Maps.of(
				Fields.OPERATION, "jvm:alwaysFail"));
		AString jobId = RT.ensureString(RT.getIn(failJob.getOutput(), Fields.ID));

		Job resultJob = covia.invokeSync("grid:jobResult", Maps.of(Fields.ID, jobId));
		assertEquals(Status.FAILED, resultJob.getStatus());
		assertNotNull(resultJob.getErrorMessage());
	}

	/**
	 * Test that grid:run properly propagates failures from the underlying operation
	 * This tests the fix for the race condition where async failures weren't completing the future
	 */
	@Test
	void runLocalOperationFailure() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("grid:run", Maps.of(
				Fields.OPERATION, "test:error",
				Fields.INPUT, Maps.of("message", "Test failure via grid:run")));

		assertNotNull(job, "Job should not be null");
		assertEquals(Status.FAILED, job.getStatus(), "grid:run should report failure from underlying operation");
	}
}

