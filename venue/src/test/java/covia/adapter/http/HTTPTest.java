package covia.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;
import covia.venue.TestOps;
import covia.adapter.HTTPAdapter;

public class HTTPTest {

	// ====================================================================
	// Basic operations — GET against the venue's own HTTP server
	// ====================================================================

	@Test public void testHTTPGet() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia=TestServer.COVIA;

		Job result=covia.invokeSync("http:get", Maps.of("url",TestServer.BASE_URL), 10_000);
		assertTrue(result.isComplete());

		assertEquals(200,RT.ensureLong(RT.getIn(result.getOutput(),"status")).longValue());
	}

	@Test public void testHTTPGetInvalidEndpoint() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// Test HTTP GET to an invalid endpoint - should return 404
		// Uses explicit timeout because the adapter makes a re-entrant HTTP call
		// back to the same server, which can stall under thread contention.
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/invalid-doc",
			"headers", Maps.of("User-Agent", "Covia-Test/1.0")
		), 10_000);

		assertTrue(result.isComplete(), "HTTP GET to invalid endpoint should complete");

		// Verify we get a 404 status
		long statusCode = RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue();
		assertEquals(404, statusCode, "Invalid endpoint should return 404 status");

		// Verify we have a response body (even if it's an error page)
		Object body = RT.getIn(result.getOutput(), "body");
		assertNotNull(body, "Should have body in output");
		assertTrue(body.toString().length() > 0, "Response body should not be empty");
	}

	@Test public void testHTTPGetStatus() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// GET the venue's /status endpoint — always available
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status"
		), 10_000);

		assertTrue(result.isComplete(), "GET /status should complete");
		long statusCode = RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue();
		assertEquals(200, statusCode, "/status should return 200");

		// Response body should be JSON containing venue info
		String body = RT.getIn(result.getOutput(), "body").toString();
		assertTrue(body.contains("status"), "Status response should contain 'status' field");
	}

	@Test public void testHTTPGetWithQueryParamsLocal() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// Use the venue's own /api/v1/status endpoint with query params
		// The query params won't affect the response, but we verify they
		// are properly appended to the URL (no exception, request succeeds)
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status",
			"queryParams", Maps.of(
				"param1", "value1",
				"param2", "hello world"
			)
		), 10_000);

		assertTrue(result.isComplete(), "GET with query params should complete");
		long statusCode = RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue();
		assertEquals(200, statusCode, "Status endpoint should return 200 even with extra query params");
	}

	@Test public void testHTTPGetQueryParamsAppendedToExistingQuery() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// URL already has a query string; queryParams should be appended with &
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status?existing=true",
			"queryParams", Maps.of("extra", "param")
		), 10_000);

		assertTrue(result.isComplete(), "GET with merged query params should complete");
		assertEquals(200, RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue());
	}

	// ====================================================================
	// POST against the venue's own server
	// ====================================================================

	@Test public void testHTTPPost() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// POST to the venue's invoke endpoint with an invalid body — we expect
		// the server to respond (likely 400 or 422) rather than the adapter failing.
		Job result = covia.invokeSync("http:post", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/invoke",
			"headers", Maps.of("Content-Type", "application/json"),
			"body", Maps.of("dummy", "payload")
		), 10_000);

		assertTrue(result.isComplete(), "POST should complete (adapter completes for any HTTP response)");
		long statusCode = RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue();
		// The server will reject the malformed invoke, but the adapter should still return the response
		assertTrue(statusCode >= 200, "Should get a real HTTP status code");
		assertNotNull(RT.getIn(result.getOutput(), "body"), "Should have response body");
		assertNotNull(RT.getIn(result.getOutput(), "headers"), "Should have response headers");
	}

	@Test public void testHTTPPostWithHeaders() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// POST with custom headers
		Job result = covia.invokeSync("http:post", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status",
			"headers", Maps.of(
				"Content-Type", "application/json",
				"X-Custom-Header", "test-value",
				"User-Agent", "Covia-HTTPTest/1.0"
			),
			"body", Maps.of("test", "data")
		), 10_000);

		assertTrue(result.isComplete(), "POST with custom headers should complete");
		// /status may not accept POST, but the adapter should still return the HTTP response
		assertNotNull(RT.getIn(result.getOutput(), "status"), "Should have status code");
	}

	@Test public void testHTTPMethodViaField() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// Use http:get but override method to POST via the method field
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status",
			"method", "POST",
			"body", Maps.of("test", "data")
		), 10_000);

		assertTrue(result.isComplete(), "Method override should work");
		assertNotNull(RT.getIn(result.getOutput(), "status"), "Should have status code");
	}

	// ====================================================================
	// SSRF protection — unit tests on the adapter directly
	// ====================================================================

	@Test public void testSSRFBlocksLoopbackByDefault() {
		HTTPAdapter adapter = new HTTPAdapter();

		// localhost should be blocked by default (resolves to loopback)
		assertThrows(IllegalArgumentException.class, () -> {
			// Access the private validateURL via reflection, or invoke the adapter
			// which calls validateURL internally. We test via invokeFuture which
			// wraps the exception.
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://localhost:8080/secret"
			)).join();
		}, "Requests to localhost should be blocked by SSRF protection");
	}

	@Test public void testSSRFBlocks127001() {
		HTTPAdapter adapter = new HTTPAdapter();

		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://127.0.0.1:8080/secret"
			)).join();
		}, "Requests to 127.0.0.1 should be blocked by SSRF protection");
	}

	@Test public void testSSRFBlocksPrivateNetworkIPs() {
		HTTPAdapter adapter = new HTTPAdapter();

		// 10.x.x.x — site-local
		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://10.0.0.1/internal"
			)).join();
		}, "Requests to 10.0.0.1 should be blocked");

		// 192.168.x.x — site-local
		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://192.168.1.1/admin"
			)).join();
		}, "Requests to 192.168.1.1 should be blocked");

		// 172.16.x.x — site-local
		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://172.16.0.1/internal"
			)).join();
		}, "Requests to 172.16.0.1 should be blocked");
	}

	@Test public void testSSRFAllowListBypassesCheck() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// TestServer already calls addAllowedHost("localhost"), so requests to
		// localhost should succeed through the venue
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status"
		), 10_000);

		assertTrue(result.isComplete(), "Allowlisted localhost should bypass SSRF checks");
		assertEquals(200, RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue());
	}

	@Test public void testSSRFBlockListOverridesAllowList() {
		HTTPAdapter adapter = new HTTPAdapter();
		adapter.addAllowedHost("evil.internal");
		adapter.addBlockedHost("evil.internal");

		// Block list should win even when allow list contains the host
		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://evil.internal/secret"
			)).join();
		}, "Block list should override allow list");
	}

	@Test public void testSSRFBlocksNonHTTPSchemes() {
		HTTPAdapter adapter = new HTTPAdapter();
		// example.com resolves to a public IP, so the IP check passes,
		// but the scheme check should still reject ftp://
		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "ftp://example.com/file"
			)).join();
		}, "FTP scheme should be blocked");
	}

	@Test public void testSSRFBlocksUnresolvableHost() {
		HTTPAdapter adapter = new HTTPAdapter();

		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", "http://this-host-definitely-does-not-exist-xyz123.invalid/path"
			)).join();
		}, "Unresolvable hosts should be rejected");
	}

	// ====================================================================
	// Error handling — missing/invalid parameters
	// ====================================================================

	@Test public void testMissingURL() {
		VenueHTTP covia = TestServer.COVIA;

		// No URL provided — adapter NPEs on url.toString(), server returns 500
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("http:get", Maps.of(), 10_000);
		}, "Missing URL should cause an error");
	}

	@Test public void testInvalidURLFormat() {
		VenueHTTP covia = TestServer.COVIA;

		// Malformed URL — URISyntaxException, server returns 500
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("http:get", Maps.of(
				"url", "not a valid url at all %%% {}"
			), 10_000);
		}, "Invalid URL format should cause an error");
	}

	@Test public void testURLWithNoHost() {
		VenueHTTP covia = TestServer.COVIA;

		// URL with scheme but no host — validateURL rejects it, server returns 400
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("http:get", Maps.of(
				"url", "http:///path/only"
			), 10_000);
		}, "URL with no host should cause an error");
	}

	@Test public void testEmptyURLString() {
		VenueHTTP covia = TestServer.COVIA;

		// Empty URL string — validateURL rejects it, server returns 400
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("http:get", Maps.of("url", ""), 10_000);
		}, "Empty URL string should cause an error");
	}

	// ====================================================================
	// HTTP error responses — adapter should complete with the status code
	// ====================================================================

	@Test public void testHTTP404Response() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// Request a path that does not exist on the venue
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/this/path/does/not/exist"
		), 10_000);

		// The adapter should COMPLETE (not FAIL) because it got an HTTP response
		assertTrue(result.isComplete(), "404 response should still complete the job");
		assertEquals(404, RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue());
		assertNotNull(RT.getIn(result.getOutput(), "body"), "Should include error body");
		assertNotNull(RT.getIn(result.getOutput(), "headers"), "Should include response headers");
	}

	@Test public void testHTTP405Response() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// DELETE on a GET-only endpoint — expect 405 Method Not Allowed (or 404)
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status",
			"method", "DELETE"
		), 10_000);

		assertTrue(result.isComplete(), "Server rejection should still complete the job");
		long statusCode = RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue();
		assertTrue(statusCode >= 400, "Should get a 4xx response, got " + statusCode);
	}

	// ====================================================================
	// Response structure validation
	// ====================================================================

	@Test public void testResponseContainsAllFields() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/status"
		), 10_000);

		assertTrue(result.isComplete());
		ACell output = result.getOutput();

		// status, body, headers should all be present
		assertNotNull(RT.getIn(output, "status"), "Output must contain 'status'");
		assertNotNull(RT.getIn(output, "body"), "Output must contain 'body'");
		assertNotNull(RT.getIn(output, "headers"), "Output must contain 'headers'");

		// status should be a long
		assertTrue(RT.getIn(output, "status") instanceof convex.core.data.prim.CVMLong,
			"Status should be a CVMLong");
	}

	// ====================================================================
	// Adapter unit tests (no server required)
	// ====================================================================

	@Test public void testHTTPAdapterInstall() {
		// Test that HTTPAdapter can be instantiated and has the correct name
		HTTPAdapter adapter = new HTTPAdapter();
		assertEquals("http", adapter.getName(), "HTTPAdapter should have name 'http'");

		// Test that the adapter can be installed (this will be called by the venue)
		assertTrue(adapter instanceof covia.adapter.AAdapter, "HTTPAdapter should extend AAdapter");
	}

	@Test public void testHTTPAdapterDescription() {
		HTTPAdapter adapter = new HTTPAdapter();
		String desc = adapter.getDescription();
		assertNotNull(desc, "Adapter should have a description");
		assertTrue(desc.length() > 20, "Description should be non-trivial");
		assertTrue(desc.toLowerCase().contains("http"), "Description should mention HTTP");
	}

	@Test public void testHTTPAdapterUnsupportedMethod() {
		HTTPAdapter adapter = new HTTPAdapter();

		// TRACE is not supported — should get IllegalArgumentException
		assertThrows(RuntimeException.class, () -> {
			adapter.invokeFuture(null, Maps.of(), Maps.of(
				"url", TestServer.BASE_URL + "/api/v1/status",
				"method", "TRACE"
			)).join();
		}, "Unsupported HTTP method should throw");
	}

	@Test public void testHTTPAdapterSupportedMethods() {
		HTTPAdapter adapter = new HTTPAdapter();
		// Allow localhost so SSRF checks pass
		adapter.addAllowedHost("localhost");

		// These methods should not throw during request construction
		// (they may fail at the network level, but construction should succeed)
		for (String method : new String[]{"GET", "POST", "PUT", "DELETE", "PATCH"}) {
			// Just verify invokeFuture doesn't throw synchronously
			try {
				adapter.invokeFuture(null, Maps.of(), Maps.of(
					"url", TestServer.BASE_URL + "/api/v1/status",
					"method", method
				));
			} catch (RuntimeException e) {
				// Acceptable if it's a network-level issue, not a method validation issue
				assertTrue(!e.getMessage().contains("Unsupported HTTP method"),
					"Method " + method + " should be supported but got: " + e.getMessage());
			}
		}
	}

	// ====================================================================
	// External-dependent tests (retained from original, may be flaky)
	// ====================================================================

	@Test public void testGoogleSearch() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia=TestServer.COVIA;

		// Test the Google search orchestration using the orchestrator adapter
		Job result=covia.invokeAndWait(TestOps.GOOGLESEARCH, Maps.of(
			"query", "artificial intelligence"
		));

		assertEquals(Status.COMPLETE, result.getStatus(), "Google search orchestration should complete successfully");

		// Verify the orchestration output structure
		Object query = RT.getIn(result.getOutput(), "query");
		assertTrue(query != null, "Should have query in output");
		assertEquals("artificial intelligence", query.toString(), "Query should match input");

		Object encodedQuery = RT.getIn(result.getOutput(), "encoded_query");
		assertTrue(encodedQuery != null, "Should have encoded_query in output");
		assertEquals("artificial+intelligence", encodedQuery.toString(), "Query should be properly URL encoded");

		Object searchUrl = RT.getIn(result.getOutput(), "search_url");
		assertTrue(searchUrl != null, "Should have search_url in output");
		assertTrue(searchUrl.toString().startsWith("https://www.google.com/search?q="), "Search URL should start with Google search base");
		assertTrue(searchUrl.toString().contains("artificial+intelligence"), "Search URL should contain encoded query");

		// Verify HTTP response details
		Object status = RT.getIn(result.getOutput(), "status");
		assertTrue(status != null, "Should have status in output");
		long statusCode = RT.ensureLong((convex.core.data.ACell)status).longValue();
		if (statusCode==200) {

			Object body = RT.getIn(result.getOutput(), "body");
			assertTrue(body != null, "Should have body in output");
			String bodyStr = body.toString();
			assertTrue(bodyStr.length() > 10, "Response body should contain content");

			Object headers = RT.getIn(result.getOutput(), "headers");
			assertTrue(headers != null, "Should have headers in output");
		} else {
			// assertTrue(statusCode == 429 || statusCode >= 500, "Status was "+statusCode);

		}
	}

	@Test public void testGoogleSearchWithFallback() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia=TestServer.COVIA;

		// Test Google search orchestration with a different query
		Job result=covia.invokeAndWait(TestOps.GOOGLESEARCH, Maps.of(
			"query", "machine learning"
		));

		if (result.getStatus() == Status.COMPLETE) {
			// Google search orchestration succeeded - verify the response
			Object status = RT.getIn(result.getOutput(), "status");
			assertTrue(status != null, "Should have status in output");
			long statusCode = RT.ensureLong((convex.core.data.ACell)status).longValue();
			assertTrue(statusCode == 200 || statusCode == 429 || statusCode == 302 || statusCode >= 500,
					"Status should be 200, 429, 302 or 5xx");

			if (statusCode==200) {
				Object body = RT.getIn(result.getOutput(), "body");
				assertTrue(body != null, "Should have body in output");
				String bodyStr = body.toString();
				assertTrue(bodyStr.length() > 100, "Google search response should contain substantial content");

				// Verify orchestration output structure
				Object query = RT.getIn(result.getOutput(), "query");
				assertEquals("machine learning", query.toString(), "Query should match input");

				Object encodedQuery = RT.getIn(result.getOutput(), "encoded_query");
				assertEquals("machine+learning", encodedQuery.toString(), "Query should be properly URL encoded");

				Object searchUrl = RT.getIn(result.getOutput(), "search_url");

				assertTrue(searchUrl.toString().contains("machine+learning"), "Search URL should contain encoded query");
			}
		} else if (result.getStatus() == Status.FAILED) {
			// Google search failed - log the error and run a fallback test
			String error = result.getErrorMessage();
			System.out.println("Google search orchestration failed (this is expected in some environments): " + error);

			// Run a fallback test to ensure HTTP adapter is working
			Job fallbackResult = covia.invokeSync("http:get", Maps.of(
				"url", "https://httpbin.org/status/200",
				"headers", Maps.of("User-Agent", "Covia-Test/1.0")
			));

			assertEquals(Status.COMPLETE, fallbackResult.getStatus(), "Fallback HTTP test should succeed");
			assertEquals(200, RT.ensureLong(RT.getIn(fallbackResult.getOutput(), "status")).longValue());

			System.out.println("Fallback HTTP test passed - HTTP adapter is working correctly");
		} else {
			// Unexpected status
			assertEquals(Status.COMPLETE, result.getStatus(), "Job should either complete or fail");
		}
	}

	@Test public void testHTTPWithQueryParams() throws InterruptedException, ExecutionException {
		VenueHTTP covia = TestServer.COVIA;

		// Test HTTP GET with query parameters using httpbin.org
		try {
			Job result = covia.invokeSync("http:get", Maps.of(
				"url", "https://httpbin.org/get",
				"queryParams", Maps.of(
					"param1", "value1",
					"param2", "value2",
					"test", "query parameters"
				),
				"headers", Maps.of("User-Agent", "Covia-Test/1.0")
			));

			assertTrue(result.isComplete(), "HTTP GET with query params should complete");

			// Verify we get a 200 status
			Object status = RT.getIn(result.getOutput(), "status");
			assertTrue(status != null, "Should have status in output");
			long statusCode = RT.ensureLong((convex.core.data.ACell)status).longValue();
			assumeTrue(statusCode==200); // skip test if failed
			assertEquals(200, statusCode, "Request with query params should return 200 status");

			// Verify we have a response body
			Object body = RT.getIn(result.getOutput(), "body");
			assertTrue(body != null, "Should have body in output");
			String bodyStr = body.toString();
			assertTrue(bodyStr.length() > 0, "Response body should contain content");

			// Verify the response contains our query parameters
			assertTrue(bodyStr.contains("param1"), "Response should contain param1");
			assertTrue(bodyStr.contains("value1"), "Response should contain value1");
			assertTrue(bodyStr.contains("param2"), "Response should contain param2");
			assertTrue(bodyStr.contains("value2"), "Response should contain value2");
			assertTrue(bodyStr.contains("test"), "Response should contain test");
			assertTrue(bodyStr.contains("query parameters"), "Response should contain 'query parameters'");
		} catch (TimeoutException te) {
			// ignore
		}
	}
}
