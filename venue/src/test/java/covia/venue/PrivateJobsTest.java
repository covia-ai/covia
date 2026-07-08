package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import covia.api.Fields;
import covia.grid.Job;
import convex.core.lang.RT;

/**
 * Private (memory-only) jobs (#192): invoked with {@code private: true},
 * never persisted — no record in the caller's job index, no lattice write,
 * gone on completion (standard terminal eviction) and on restart. Requires
 * {@code enablePrivateJobs}; a private request against a venue without it is
 * an error, never a silent downgrade to a persisted job.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class PrivateJobsTest {

	private Engine engine;
	private AString ALICE;

	@BeforeAll
	public void setup() {
		engine = Engine.createTemp(Maps.of(Config.ENABLE_PRIVATE_JOBS, CVMBool.TRUE));
		Engine.addDemoAssets(engine);
		ALICE = Strings.create("did:key:zPrivateJobsTestAlice");
	}

	private long jobCount() {
		User u = engine.getVenueState().users().get(ALICE);
		if (u == null) return 0;
		Index<Blob, ACell> jobs = u.getJobs();
		return (jobs == null) ? 0 : jobs.count();
	}

	@Test
	public void testPrivateJobIsNeverPersisted() {
		long before = jobCount();

		Job job = engine.jobs().invokeOperation(
			Strings.create("v/test/ops/echo"),
			Maps.of(Strings.create("value"), Strings.create("secret payload")),
			RequestContext.of(ALICE), true);
		ACell result = job.awaitResult(5000);
		assertNotNull(result, "private job must serve its caller normally");

		assertEquals(before, jobCount(), "private job must leave no record in the job index");

		// Completed and evicted: the job is immediately forgotten — a late
		// poll finds nothing (the accepted trade-off: use wait to collect).
		assertNull(engine.jobs().getJobData(job.getID(), RequestContext.of(ALICE)),
			"a completed private job is not retrievable");
	}

	@Test
	public void testNormalJobStillPersists() {
		long before = jobCount();
		Job job = engine.jobs().invokeOperation(
			Strings.create("v/test/ops/echo"),
			Maps.of(Strings.create("value"), Strings.create("public payload")),
			RequestContext.of(ALICE));
		job.awaitResult(5000);
		assertEquals(before + 1, jobCount(), "non-private jobs persist as always");
	}

	@Test
	public void testPrivateRequiresVenueOptIn() {
		// The shared TestEngine has no enablePrivateJobs — a private request
		// must fail loudly, never silently downgrade to a persisted job.
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> TestEngine.ENGINE.jobs().invokeOperation(
				Strings.create("v/test/ops/echo"),
				Maps.of(Strings.create("value"), Strings.create("x")),
				RequestContext.of(ALICE), true));
		assertTrue(ex.getMessage().contains("enablePrivateJobs"), ex.getMessage());
	}

	@Test
	public void testPrivateAgentChatLeavesNoJobRecords() {
		// A private conversation is just agent intake invoked private (#192):
		// the chat Job is the interaction's only job, and sub-invocations
		// (transition, L3, tools) create no jobs anyway (#85).
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "private-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					Strings.create("llmOperation"), Strings.create("v/test/ops/llm"))),
			RequestContext.of(ALICE)).awaitResult(5000);
		long before = jobCount();

		Job chat = engine.jobs().invokeOperation(
			Strings.create("v/ops/agent/chat"),
			Maps.of(Fields.AGENT_ID, "private-agent",
				Fields.MESSAGE, Strings.create("confidential question")),
			RequestContext.of(ALICE), true);
		ACell result = chat.awaitResult(5000);
		assertNotNull(RT.getIn(result, Fields.RESPONSE), "chat answered: " + result);

		assertEquals(before, jobCount(),
			"a private conversation must leave no job records");
	}

	/** The REST wire field: {@code private: true} in the invoke body. */
	@Test
	public void testRestInvokePrivateField() throws Exception {
		java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
		String body = "{\"operation\": \"v/test/ops/echo\", \"input\": {\"value\": \"wire secret\"}, "
			+ "\"private\": true, \"wait\": true}";
		java.net.http.HttpResponse<String> resp = http.send(
			java.net.http.HttpRequest.newBuilder(java.net.URI.create(TestServer.BASE_URL + "/api/v1/invoke"))
				.header("Content-Type", "application/json")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(), resp.body());
		assertTrue(resp.body().contains("wire secret"), "wait returns the finished record: " + resp.body());

		// The job never reached the public caller's job index.
		AString publicDid = Strings.create(TestServer.ENGINE.getDIDString() + ":public");
		User u = TestServer.ENGINE.getVenueState().users().get(publicDid);
		if (u != null && u.getJobs() != null) {
			for (var e : u.getJobs().entrySet()) {
				assertFalse(String.valueOf(e.getValue()).contains("wire secret"),
					"private job content must not be persisted");
			}
		}
	}
}
