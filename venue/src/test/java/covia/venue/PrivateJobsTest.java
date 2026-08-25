package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
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
 * Legacy in-process memory-only Job support and the transport migration rule:
 * public invoke always records, while result-only callers use run.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class PrivateJobsTest {

	private Engine engine;
	private AString ALICE;

	@BeforeAll
	public void setup() {
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
		Engine.addDemoAssets(engine);
		ALICE = Strings.create("did:key:zPrivateJobsTestAlice");
	}

	@AfterAll
	public void teardown() {
		engine.close();
	}

	private long jobCount() {
		User u = engine.getVenueState().users().get(ALICE);
		if (u == null) return 0;
		Index<Blob, ACell> jobs = u.getJobs();
		return (jobs == null) ? 0 : jobs.count();
	}

	@Test
	public void testReadOnlyRunIsNeverPersisted() throws Exception {
		long before = jobCount();

		ACell result = engine.jobs().runOperation(
			Strings.create("v/test/ops/echo"),
			Maps.of(Strings.create("value"), Strings.create("secret payload")),
			RequestContext.of(ALICE)).get();
		assertNotNull(result, "run must serve its caller normally");

		assertEquals(before, jobCount(), "read-only run must leave no durable record");
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
	public void testUnclassifiedRunPersists() throws Exception {
		long before = jobCount();
		engine.jobs().runOperation(Maps.of(Fields.OPERATION,
			Maps.of(Fields.ADAPTER, Strings.create("test:echo"))),
			Maps.of(Strings.create("value"), Strings.create("x")),
			RequestContext.of(ALICE)).get();
		assertEquals(before + 1, jobCount());
	}

	@Test
	public void testJobRequiredRunPersists() throws Exception {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "private-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					Strings.create("llmOperation"), Strings.create("v/test/ops/llm"))),
			RequestContext.of(ALICE)).awaitResult(5000);
		long before = jobCount();

		ACell result = engine.jobs().runOperation(
			Strings.create("v/ops/agent/chat"),
			Maps.of(Fields.AGENT_ID, "private-agent",
				Fields.MESSAGE, Strings.create("confidential question")),
			RequestContext.of(ALICE)).get();
		assertNotNull(RT.getIn(result, Fields.RESPONSE), "chat answered: " + result);

		assertEquals(before + 1, jobCount(),
			"operation.internal=false must keep lifecycle-bearing run durable");
	}

	/** The legacy REST wire field is rejected: invoke always means durable. */
	@Test
	public void testRestInvokePrivateFieldRejected() throws Exception {
		java.net.http.HttpClient http = TestHTTP.CLIENT;
		String body = "{\"operation\": \"v/test/ops/echo\", \"input\": {\"value\": \"wire secret\"}, "
			+ "\"private\": true, \"wait\": true}";
		java.net.http.HttpResponse<String> resp = http.send(
			java.net.http.HttpRequest.newBuilder(java.net.URI.create(TestServer.BASE_URL + "/api/v1/invoke"))
				.header("Content-Type", "application/json")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		assertEquals(400, resp.statusCode(), resp.body());
		assertTrue(resp.body().contains("use /api/v1/run"), resp.body());
	}
}
