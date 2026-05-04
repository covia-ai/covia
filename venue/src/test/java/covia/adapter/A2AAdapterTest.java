package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.RequestContext;
import covia.venue.TestServer;
import covia.venue.api.A2ACodec;

/**
 * Tests the outbound A2A adapter by pointing it at our own server's /a2a
 * endpoint — a full self-loop through the A2A protocol. If either side's
 * wire format drifts from the spec, these tests break.
 */
class A2AAdapterTest {

	// ==================== getAgentCard ====================

	@Test
	void getAgentCard_returnsValidCard() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		Job job = covia.invokeSync("v/ops/a2a/agent-card", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL)));

		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		ACell output = job.getOutput();
		assertTrue(output instanceof AMap);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> card = (AMap<AString, ACell>) output;
		assertNotNull(card.get(Strings.create("name")));
		assertNotNull(card.get(Strings.create("supportedInterfaces")));
		assertNotNull(card.get(Strings.create("version")));
	}

	// ==================== send — mirror to INPUT_REQUIRED ====================

	@Test
	void send_mirrorsInputRequiredFromRemoteChatOp() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		// Async invoke + server-side poll; INPUT_REQUIRED is non-terminal so
		// invokeSync would block forever.
		Job seed = covia.invoke("v/ops/a2a/send", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.MESSAGE, coviaMessageRecord("hello from test"))).get();

		AMap<AString, ACell> data = awaitStable(seed.getID());
		assertEquals(Status.INPUT_REQUIRED, RT.ensureString(data.get(Fields.STATUS)),
				"Remote test:chat op goes INPUT_REQUIRED; adapter must mirror it onto the local Job");

		AString remoteTaskId = RT.ensureString(data.get(Fields.REMOTE_TASK_ID));
		assertNotNull(remoteTaskId, "Adapter must persist remoteTaskId on the local Job");

		ACell output = data.get(Fields.OUTPUT);
		assertTrue(output instanceof AMap);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> task = (AMap<AString, ACell>) output;
		assertEquals(remoteTaskId, RT.ensureString(task.get(Strings.create("id"))));
	}

	// ==================== getTask — fetch existing remote task ====================

	@Test
	void getTask_roundTripsRemoteTaskViaLocalAdapter() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job seed = covia.invoke("v/ops/a2a/send", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.MESSAGE, coviaMessageRecord("seed task"))).get();
		AMap<AString, ACell> data = awaitStable(seed.getID());
		String remoteTaskId = RT.ensureString(data.get(Fields.REMOTE_TASK_ID)).toString();

		// Act: fetch it back via a2a:get-task (this one does reach COMPLETE)
		Job getJob = covia.invokeSync("v/ops/a2a/get-task", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.ID, Strings.create(remoteTaskId)));

		assertEquals(Status.COMPLETE, getJob.getStatus(), getJob.getErrorMessage());
		ACell output = getJob.getOutput();
		assertTrue(output instanceof AMap);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> task = (AMap<AString, ACell>) output;
		assertEquals(remoteTaskId, RT.ensureString(task.get(Strings.create("id"))).toString());
	}

	@Test
	void getTask_unknownIdSurfacesRemoteError() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job job = covia.invokeSync("v/ops/a2a/get-task", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.ID, Strings.create("000000000000000000000000deadbeef")));

		// Remote returns JSON-RPC error (TaskNotFound); adapter surfaces it as
		// a failed Job with null output.
		assertEquals(Status.FAILED, job.getStatus());
		assertNotNull(job.getErrorMessage());
		assertTrue(job.getErrorMessage().toLowerCase().contains("task"),
				"Error should mention Task: " + job.getErrorMessage());
	}

	// ==================== cancel — terminate remote running task ====================

	@Test
	void cancel_transitionsRemoteToCanceled() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		Job seed = covia.invoke("v/ops/a2a/send", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.MESSAGE, coviaMessageRecord("cancel me"))).get();
		AMap<AString, ACell> data = awaitStable(seed.getID());
		String remoteTaskId = RT.ensureString(data.get(Fields.REMOTE_TASK_ID)).toString();

		// Act: cancel
		Job cancelJob = covia.invokeSync("v/ops/a2a/cancel", Maps.of(
				Fields.URL, Strings.create(TestServer.BASE_URL),
				Fields.ID, Strings.create(remoteTaskId)));

		// INPUT_REQUIRED is an interrupted (non-terminal) state per A2A spec,
		// so cancellation is valid. Either we get the canceled Task or — if the
		// server treats it as terminal — we get a TaskNotCancelable error.
		if (cancelJob.getStatus() == Status.FAILED) {
			assertTrue(cancelJob.getErrorMessage().toLowerCase().contains("cancel"),
					"Expected TaskNotCancelable, got: " + cancelJob.getErrorMessage());
			return;
		}
		assertEquals(Status.COMPLETE, cancelJob.getStatus(), cancelJob.getErrorMessage());
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> task = (AMap<AString, ACell>) cancelJob.getOutput();
		AMap<?, ?> status = (AMap<?, ?>) task.get(Strings.create("status"));
		assertEquals("TASK_STATE_CANCELED",
				RT.ensureString(status.get(Strings.create("state"))).toString());
	}

	// ==================== url validation ====================

	@Test
	void getAgentCard_missingUrlFailsJob() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		Job job = covia.invokeSync("v/ops/a2a/agent-card", Maps.of());
		assertEquals(Status.FAILED, job.getStatus());
		assertNotNull(job.getErrorMessage());
	}

	// ==================== helpers ====================

	/**
	 * Build a Covia-shaped message record matching what A2ACodec.toMessageRecord
	 * produces — {role, parts: [{type:"text", text:...}], messageId?}.
	 */
	private static AMap<AString, ACell> coviaMessageRecord(String text) {
		return Maps.of(
				A2ACodec.ROLE, Strings.create("user"),
				A2ACodec.PARTS, Vectors.of(Maps.of(
						Fields.TYPE, Strings.intern("text"),
						Fields.TEXT, Strings.create(text))),
				A2ACodec.MESSAGE_ID, Strings.create("msg-" + System.nanoTime()));
	}

	/**
	 * Poll the local engine directly (bypassing HTTP) until the job leaves the
	 * PENDING/STARTED transient states. Used by tests that expect a stable but
	 * non-terminal outcome (e.g. INPUT_REQUIRED). Up to 5s.
	 */
	private static AMap<AString, ACell> awaitStable(Blob jobId) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		AMap<AString, ACell> data = null;
		while (System.currentTimeMillis() < deadline) {
			// Direct active-cache lookup bypasses ownership — fine for test
			// observation; we're just waiting for status to settle.
			data = TestServer.ENGINE.jobs().getJobData(jobId);
			if (data != null) {
				AString status = RT.ensureString(data.get(Fields.STATUS));
				if (status != null && !Status.PENDING.equals(status) && !Status.STARTED.equals(status)) {
					return data;
				}
			}
			Thread.sleep(50);
		}
		throw new AssertionError("Job never left PENDING/STARTED: "
				+ (data == null ? "null" : data.get(Fields.STATUS)));
	}
}
