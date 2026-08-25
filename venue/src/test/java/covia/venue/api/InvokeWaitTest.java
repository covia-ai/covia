package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.TestServer;

/**
 * The {@code /invoke} transport {@code wait} parameter (#140 follow-up):
 * boolean or integer milliseconds, following the same convention as the
 * op-level {@code wait} on {@code agent:request}/{@code agent:trigger} —
 * absent/false = asynchronous (201 + job record to poll), {@code true} = the
 * full 120s window, integer = that many ms clamped to the cap. On completion
 * within the window the finished record returns with 200.
 */
public class InvokeWaitTest {

	// ========== parseWaitMs — parameter convention ==========

	@Test
	public void testQueryParamForms() {
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs("true", null));
		assertEquals(0L, CoviaAPI.parseWaitMs("false", null));
		assertEquals(5000L, CoviaAPI.parseWaitMs("5000", null));
		assertEquals(0L, CoviaAPI.parseWaitMs("0", null), "explicit zero = asynchronous");
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs("999999999", null), "clamped to the cap");
		assertEquals(0L, CoviaAPI.parseWaitMs(null, null));
	}

	@Test
	public void testBodyFieldForms() {
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs(null, CVMBool.TRUE));
		assertEquals(0L, CoviaAPI.parseWaitMs(null, CVMBool.FALSE));
		assertEquals(3000L, CoviaAPI.parseWaitMs(null, CVMLong.create(3000)));
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs(null, CVMLong.create(Long.MAX_VALUE)), "clamped");
		// Boolean/number arriving as strings (JSON-over-HTTP clients)
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs(null, Strings.create("true")));
		assertEquals(2500L, CoviaAPI.parseWaitMs(null, Strings.create("2500")));
	}

	@Test
	public void testMalformedWaitRejected() {
		// Bad inputs are rejected, never silently treated as absent.
		assertThrows(IllegalArgumentException.class, () -> CoviaAPI.parseWaitMs("soon", null));
		assertThrows(IllegalArgumentException.class, () -> CoviaAPI.parseWaitMs("-5", null));
		assertThrows(IllegalArgumentException.class, () -> CoviaAPI.parseWaitMs(null, Strings.create("whenever")));
		assertThrows(IllegalArgumentException.class, () -> CoviaAPI.parseWaitMs(null, CVMLong.create(-1)));
		assertThrows(IllegalArgumentException.class, () -> CoviaAPI.parseWaitMs(null, convex.core.data.Maps.of("ms", CVMLong.create(5))));
	}

	@Test
	public void testCompletionAfterExpiredWaitStaysAsynchronous() {
		Job job = Job.create(Maps.of(Fields.STATUS, Status.STARTED));

		boolean completedWithinWait = CoviaAPI.awaitCompletion(job, 1);
		job.completeWith(Maps.of("late", true));

		assertTrue(job.isComplete(), "the job may complete after the wait expires");
		assertFalse(completedWithinWait,
			"a late completion must not retroactively turn an expired wait into HTTP 200");
	}

	// ========== End-to-end over the real transport ==========

	private static HttpResponse<String> invoke(String query, String body) throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/invoke" + query))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> run(String body) throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(URI.create(TestServer.BASE_URL + "/api/v1/run"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	public void testRunReturnsRawOperationResult() throws Exception {
		HttpResponse<String> resp = run(
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"answer\":42}}");
		assertEquals(200, resp.statusCode());
		ACell body = JSON.parse(resp.body());
		assertEquals(CVMLong.create(42), RT.getIn(body, "answer"));
		assertEquals(null, RT.getIn(body, "status"),
			"run returns the operation output, not a Job record");
	}

	@Test
	public void testIntegerWaitReturnsFinishedRecord() throws Exception {
		HttpResponse<String> resp = invoke("?wait=5000",
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"m\":1}}");
		assertEquals(200, resp.statusCode(), "completed within the window → finished record");
		assertEquals("COMPLETE", RT.getIn(JSON.parse(resp.body()), "status").toString());
	}

	@Test
	public void testBodyIntegerWaitReturnsFinishedRecord() throws Exception {
		HttpResponse<String> resp = invoke("",
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"m\":2},\"wait\":5000}");
		assertEquals(200, resp.statusCode());
		assertEquals("COMPLETE", RT.getIn(JSON.parse(resp.body()), "status").toString());
	}

	@Test
	public void testNoWaitStaysAsynchronous() throws Exception {
		HttpResponse<String> resp = invoke("",
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"m\":3}}");
		assertEquals(201, resp.statusCode(), "default contract: job record to poll");
	}

	@Test
	public void testShortWaitOnSlowJobReturnsCurrentRecord() throws Exception {
		// A wait window shorter than the job: graceful degradation to the
		// asynchronous contract — current record, 201, caller keeps polling.
		HttpResponse<String> resp = invoke("?wait=100",
			"{\"operation\":\"v/test/ops/delay\",\"input\":"
			+ "{\"delay\":3000,\"operation\":\"v/test/ops/echo\",\"input\":{\"m\":4}}}");
		assertEquals(201, resp.statusCode(), "timeout inside the window degrades to poll");
	}

	@Test
	public void testGarbageWaitRejectedBeforeInvocation() throws Exception {
		// A malformed wait is a 400 — and rejected BEFORE the operation is
		// invoked, so no orphaned job is created for a bad request.
		HttpResponse<String> resp = invoke("?wait=soon",
			"{\"operation\":\"v/test/ops/echo\",\"input\":{\"m\":5}}");
		assertEquals(400, resp.statusCode(), "malformed wait must reject, not silently ignore");
	}

	@Test
	public void testMalformedBodyRejected400() throws Exception {
		// A body that is not valid JSON is the caller's error: 400 with the
		// parse cause — never the generic 500 (#89 sweep).
		HttpResponse<String> resp = invoke("", "{not json!!");
		assertEquals(400, resp.statusCode(), "malformed request body must be a 400, got: " + resp.body());
		assertTrue(resp.body().contains("not valid JSON"),
			"the error must say the body failed to parse, got: " + resp.body());
	}
}
