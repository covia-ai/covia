package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
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
		assertEquals(5000L, CoviaAPI.parseWaitMs("5000", null));
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs("999999999", null), "clamped to the cap");
		assertEquals(0L, CoviaAPI.parseWaitMs("false", null));
		assertEquals(0L, CoviaAPI.parseWaitMs("-5", null), "non-positive means asynchronous");
		assertEquals(0L, CoviaAPI.parseWaitMs("soon", null), "garbage means asynchronous");
		assertEquals(0L, CoviaAPI.parseWaitMs(null, null));
	}

	@Test
	public void testBodyFieldForms() {
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs(null, CVMBool.TRUE));
		assertEquals(0L, CoviaAPI.parseWaitMs(null, CVMBool.FALSE));
		assertEquals(3000L, CoviaAPI.parseWaitMs(null, CVMLong.create(3000)));
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs(null, CVMLong.create(Long.MAX_VALUE)), "clamped");
		// JSON-over-HTTP tolerance: boolean/number arriving as strings
		assertEquals(CoviaAPI.MAX_WAIT_MS, CoviaAPI.parseWaitMs(null, Strings.create("true")));
		assertEquals(2500L, CoviaAPI.parseWaitMs(null, Strings.create("2500")));
		assertEquals(0L, CoviaAPI.parseWaitMs(null, Strings.create("whenever")));
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
}
