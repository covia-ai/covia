package covia.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;
import covia.venue.TestOps;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;
import covia.adapter.HTTPAdapter;
import covia.test.IntegrationTest;

public class HTTPTest {

	// ====================================================================
	// Basic operations — GET against the venue's own HTTP server
	// ====================================================================

	@Test public void testHTTPGet() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia=TestServer.COVIA;

		Job result=covia.invokeSync("v/ops/http/get", Maps.of("url",TestServer.BASE_URL), 10_000);
		assertTrue(result.isComplete());

		assertEquals(200,RT.ensureLong(RT.getIn(result.getOutput(),"status")).longValue());
	}

	@Test public void testHTTPGetInvalidEndpoint() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// Test HTTP GET to an invalid endpoint - should return 404
		// Uses explicit timeout because the adapter makes a re-entrant HTTP call
		// back to the same server, which can stall under thread contention.
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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
		Job result = covia.invokeSync("v/ops/http/post", Maps.of(
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
		Job result = covia.invokeSync("v/ops/http/post", Maps.of(
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

	@Test public void testHTTPMethodViaField() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Use http:get but override method to POST via the method field. A
		// dedicated echo server keeps this assertion independent of contention,
		// rate limiting, and route policy on the shared test venue.
		HttpServer echo = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		echo.createContext("/method", exchange -> {
			byte[] body = exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
		});
		echo.start();
		try {
			Job result = covia.invokeSync("v/ops/http/get", Maps.of(
				"url", "http://localhost:" + echo.getAddress().getPort() + "/method",
				"method", "POST",
				"body", Maps.of("test", "data")
			), 10_000);

			assertTrue(result.isComplete(), () -> "Method override failed: "
				+ result.getErrorMessage());
			assertEquals(200L, RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue());
			assertEquals("POST", RT.getIn(result.getOutput(), "body").toString());
		} finally {
			echo.stop(0);
		}
	}

	@Test public void testSecretHeadersResolveOverrideAndStayOutOfJobRecords() throws Exception {
		AString caller = Strings.create("did:test:http:secret-headers:" + System.nanoTime());
		User user = TestServer.ENGINE.getVenueState().users().ensure(caller);
		byte[] key = SecretStore.deriveKey(TestServer.ENGINE.getKeyPair());
		user.secrets().store("API_KEY", "resolved-api-key", key);
		user.secrets().store("BASIC_AUTH", "Basic dXNlcjp0b2tlbg==", key);

		AtomicReference<String> received = new AtomicReference<>();
		HttpServer echo = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		echo.createContext("/headers", exchange -> {
			received.set(exchange.getRequestHeaders().getFirst("X-API-Key") + "|"
				+ exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
		});
		echo.start();
		try {
			ACell input = Maps.of(
				Fields.URL, "http://localhost:" + echo.getAddress().getPort() + "/headers",
				Fields.HEADERS, Maps.of("X-API-Key", "literal-must-lose"),
				Fields.SECRET_HEADERS, Maps.of(
					"X-API-Key", "s/API_KEY",
					"Authorization", "s/BASIC_AUTH"));
			Job job = TestServer.ENGINE.jobs().invokeOperation(
				"v/ops/http/get", input, RequestContext.of(caller));
			ACell output = job.awaitResult(5000);

			assertEquals("ok", RT.getIn(output, Fields.BODY).toString());
			assertEquals("resolved-api-key|Basic dXNlcjp0b2tlbg==", received.get());
			assertEquals(Fields.HIDDEN,
				RT.getIn(job.getData(), Fields.INPUT, Fields.SECRET_HEADERS));
			String durable = job.getData().toString();
			assertFalse(durable.contains("resolved-api-key"), durable);
			assertFalse(durable.contains("dXNlcjp0b2tlbg"), durable);
			assertFalse(durable.contains("s/API_KEY"), durable);
		} finally {
			echo.stop(0);
		}
	}

	@Test public void testBearerSecretConflictsWithAuthorizationSecretHeader() {
		AString caller = Strings.create("did:test:http:secret-conflict:" + System.nanoTime());
		User user = TestServer.ENGINE.getVenueState().users().ensure(caller);
		byte[] key = SecretStore.deriveKey(TestServer.ENGINE.getKeyPair());
		user.secrets().store("AUTH", "complete-header", key);
		user.secrets().store("TOKEN", "bearer-token", key);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> TestServer.ENGINE.jobs().invokeOperation("v/ops/http/get", Maps.of(
				Fields.URL, TestServer.BASE_URL + "/api/v1/status",
				Fields.SECRET_HEADERS, Maps.of("Authorization", "s/AUTH"),
				Fields.BEARER_SECRET, "s/TOKEN"), RequestContext.of(caller)));
		assertTrue(error.getMessage().contains("either secretHeaders or bearerSecret"),
			error.getMessage());
	}

	@Test public void testHTTPGetAndPostDeclareSecretHeadersForRedaction() {
		RequestContext ctx = RequestContext.of(Strings.create(
			"did:test:http:secret-schema:" + System.nanoTime()));
		for (String ref : new String[] {"v/ops/http/get", "v/ops/http/post"}) {
			ACell operation = TestServer.ENGINE.resolvePath(Strings.create(ref), ctx);
			AVector<ACell> secretFields = RT.ensureVector(
				RT.getIn(operation, Fields.OPERATION, "secretFields"));
			assertNotNull(secretFields, ref);
			assertTrue(secretFields.contains(Fields.SECRET_HEADERS), ref);
		}
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
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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
			covia.invokeSync("v/ops/http/get", Maps.of(), 10_000);
		}, "Missing URL should cause an error");
	}

	@Test public void testInvalidURLFormat() {
		VenueHTTP covia = TestServer.COVIA;

		// Malformed URL — URISyntaxException, server returns 500
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("v/ops/http/get", Maps.of(
				"url", "not a valid url at all %%% {}"
			), 10_000);
		}, "Invalid URL format should cause an error");
	}

	@Test public void testURLWithNoHost() {
		VenueHTTP covia = TestServer.COVIA;

		// URL with scheme but no host — validateURL rejects it, server returns 400
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("v/ops/http/get", Maps.of(
				"url", "http:///path/only"
			), 10_000);
		}, "URL with no host should cause an error");
	}

	@Test public void testEmptyURLString() {
		VenueHTTP covia = TestServer.COVIA;

		// Empty URL string — validateURL rejects it, server returns 400
		assertThrows(ExecutionException.class, () -> {
			covia.invokeSync("v/ops/http/get", Maps.of("url", ""), 10_000);
		}, "Empty URL string should cause an error");
	}

	// ====================================================================
	// HTTP error responses — adapter should complete with the status code
	// ====================================================================

	@Test public void testHTTP404Response() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;

		// Request a path that does not exist on the venue
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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
		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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

		Job result = covia.invokeSync("v/ops/http/get", Maps.of(
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

	@IntegrationTest
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

	@IntegrationTest
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
			Job fallbackResult = covia.invokeSync("v/ops/http/get", Maps.of(
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

	@Test public void testHTTPWithQueryParams() throws Exception {
		VenueHTTP covia = TestServer.COVIA;

		// Deterministic local echo server, replacing the previous external
		// httpbin.org dependency. That dependency flaked: invokeSync used the
		// default 5s poll and, when the public endpoint was slow, raised a
		// ResponseException (poll timeout) that escaped the TimeoutException-only
		// catch. The echo server reflects the raw query string in the body so we
		// can still assert the queryParams reached the destination intact.
		// "localhost" is on the venue's SSRF allowlist (see TestServer).
		HttpServer echo = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		echo.createContext("/get", exchange -> {
			String query = exchange.getRequestURI().getRawQuery();
			byte[] body = ("echo " + (query == null ? "" : query)).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
		});
		echo.start();
		try {
			int port = echo.getAddress().getPort();
			Job result = covia.invokeSync("v/ops/http/get", Maps.of(
				"url", "http://localhost:" + port + "/get",
				"queryParams", Maps.of(
					"param1", "value1",
					"param2", "value2",
					"test", "query parameters"
				),
				"headers", Maps.of("User-Agent", "Covia-Test/1.0")
			), 10_000);

			assertTrue(result.isComplete(), "HTTP GET with query params should complete");
			assertEquals(200, RT.ensureLong(RT.getIn(result.getOutput(), "status")).longValue(),
				"Request with query params should return 200 status");

			// The echoed body must reflect every query parameter — proof they
			// were appended to the outbound URL and reached the destination.
			String bodyStr = RT.getIn(result.getOutput(), "body").toString();
			assertTrue(bodyStr.contains("param1=value1"), "Response should echo param1=value1, got: " + bodyStr);
			assertTrue(bodyStr.contains("param2=value2"), "Response should echo param2=value2, got: " + bodyStr);
			assertTrue(bodyStr.contains("test=query+parameters") || bodyStr.contains("test=query%20parameters"),
				"Response should echo the url-encoded 'test' param, got: " + bodyStr);
		} finally {
			echo.stop(0);
		}
	}

	// ====================================================================
	// Default User-Agent (#422) and bounded, guarded redirects (#423)
	// ====================================================================

	private static HttpServer localServer() throws java.io.IOException {
		return HttpServer.create(new InetSocketAddress("localhost", 0), 0);
	}

	private static String base(HttpServer server) {
		return "http://localhost:" + server.getAddress().getPort();
	}

	private static void respond(com.sun.net.httpserver.HttpExchange x, int code, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		x.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
	}

	private static void redirect(com.sun.net.httpserver.HttpExchange x, int code, String location) throws java.io.IOException {
		x.getResponseHeaders().add("Location", location);
		x.sendResponseHeaders(code, -1);
		x.close();
	}

	private static String body(Job job) {
		assertTrue(job.isComplete(), String.valueOf(job.getErrorMessage()));
		return RT.getIn(job.getOutput(), "body").toString();
	}

	/** Runs a GET through the shared engine and returns the failure message —
	 *  whether the request was refused before it left (a synchronous
	 *  IllegalArgumentException) or failed once in flight (a FAILED job). */
	private static String failureOf(ACell input) {
		Job job;
		try {
			job = TestServer.ENGINE.jobs().invokeOperation("v/ops/http/get", input,
				RequestContext.of(Strings.create("did:test:http:redirects")));
		} catch (RuntimeException refused) {
			return String.valueOf(refused.getMessage());
		}
		try { job.awaitResult(10_000); } catch (Exception expected) { /* reported below */ }
		assertEquals(Status.FAILED, job.getStatus(), "expected a failure, got status " + job.getStatus());
		return String.valueOf(job.getErrorMessage());
	}

	@Test public void testDefaultUserAgentUnlessSupplied() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		HttpServer echo = localServer();
		echo.createContext("/ua", x -> respond(x, 200, String.valueOf(x.getRequestHeaders().getFirst("User-Agent"))));
		echo.start();
		try {
			// No headers at all: the venue's own descriptive User-Agent, not the JDK's.
			String sent = body(covia.invokeSync("v/ops/http/get", Maps.of("url", base(echo) + "/ua"), 10_000));
			assertTrue(sent.startsWith("Covia/") && sent.contains("+https://covia.ai"), sent);
			assertEquals(HTTPAdapter.defaultUserAgent(), sent);
			// Other headers but no User-Agent: still added.
			sent = body(covia.invokeSync("v/ops/http/get", Maps.of(
				"url", base(echo) + "/ua", "headers", Maps.of("Accept", "application/json")), 10_000));
			assertEquals(HTTPAdapter.defaultUserAgent(), sent);
			// An explicit caller value wins, whatever its case.
			sent = body(covia.invokeSync("v/ops/http/get", Maps.of(
				"url", base(echo) + "/ua", "headers", Maps.of("user-agent", "Brightside/2.0")), 10_000));
			assertEquals("Brightside/2.0", sent);
		} finally {
			echo.stop(0);
		}
	}

	@Test public void testJsonBodyDefaultsContentTypeUnlessSupplied() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		HttpServer echo = localServer();
		echo.createContext("/content-type", x -> respond(x, 200,
			String.valueOf(x.getRequestHeaders().getFirst("Content-Type"))));
		echo.start();
		try {
			ACell body = Maps.of("message", "hello");
			assertEquals("application/json", body(covia.invokeSync("v/ops/http/post", Maps.of(
				"url", base(echo) + "/content-type", "body", body), 10_000)));
			assertEquals("application/problem+json", body(covia.invokeSync("v/ops/http/post", Maps.of(
				"url", base(echo) + "/content-type", "body", body,
				"headers", Maps.of("content-type", "application/problem+json")), 10_000)));
		} finally {
			echo.stop(0);
		}
	}

	@Test public void testFollowsRedirectsWithProvenance() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		HttpServer s = localServer();
		String root = base(s);
		s.createContext("/start", x -> redirect(x, 302, "/second"));          // relative Location
		s.createContext("/second", x -> redirect(x, 301, root + "/final"));   // absolute Location
		s.createContext("/final", x -> respond(x, 200, "done"));
		s.start();
		try {
			Job r = covia.invokeSync("v/ops/http/get", Maps.of("url", root + "/start"), 10_000);
			assertEquals("done", body(r));
			assertEquals(200, RT.ensureLong(RT.getIn(r.getOutput(), "status")).longValue());
			assertEquals(root + "/final", RT.getIn(r.getOutput(), "url").toString());
			AVector<ACell> hops = RT.ensureVector(RT.getIn(r.getOutput(), "redirects"));
			assertNotNull(hops, "the hops taken are reported");
			assertEquals(2, hops.count());
			assertEquals(302, RT.ensureLong(RT.getIn(hops.get(0), "status")).longValue());
			assertEquals(root + "/start", RT.getIn(hops.get(0), "from").toString());
			assertEquals(root + "/second", RT.getIn(hops.get(0), "to").toString());
			assertEquals(301, RT.ensureLong(RT.getIn(hops.get(1), "status")).longValue());
			assertEquals(root + "/final", RT.getIn(hops.get(1), "to").toString());

			// A direct response has a url and no redirects.
			Job direct = covia.invokeSync("v/ops/http/get", Maps.of("url", root + "/final"), 10_000);
			assertEquals(root + "/final", RT.getIn(direct.getOutput(), "url").toString());
			assertTrue(RT.getIn(direct.getOutput(), "redirects") == null);

			// Opting out returns the redirect itself.
			Job raw = covia.invokeSync("v/ops/http/get", Maps.of("url", root + "/start", "followRedirects", false), 10_000);
			assertTrue(raw.isComplete(), String.valueOf(raw.getErrorMessage()));
			assertEquals(302, RT.ensureLong(RT.getIn(raw.getOutput(), "status")).longValue());
			assertTrue(RT.getIn(raw.getOutput(), "redirects") == null);
			assertEquals(root + "/start", RT.getIn(raw.getOutput(), "url").toString());
		} finally {
			s.stop(0);
		}
	}

	@Test public void testRedirectMethodSemantics() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		HttpServer s = localServer();
		s.createContext("/see-other", x -> redirect(x, 303, "/method"));
		s.createContext("/temporary", x -> redirect(x, 307, "/method"));
		s.createContext("/moved", x -> redirect(x, 301, "/method"));
		s.createContext("/method", x -> {
			String received = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			respond(x, 200, x.getRequestMethod() + ":" + received.replaceAll("\\s", "")
				+ ":" + x.getRequestHeaders().getFirst("Content-Type"));
		});
		s.start();
		try {
			ACell payload = Maps.of("k", "v");
			// 303: GET, no body, no stale Content-Type.
			assertEquals("GET::null", body(covia.invokeSync("v/ops/http/post", Maps.of(
				"url", base(s) + "/see-other", "body", payload,
				"headers", Maps.of("Content-Type", "application/json")), 10_000)));
			// 301 on a POST: GET, as browsers do.
			assertEquals("GET::null", body(covia.invokeSync("v/ops/http/post", Maps.of(
				"url", base(s) + "/moved", "body", payload,
				"headers", Maps.of("Content-Type", "application/json")), 10_000)));
			// 307: method and body preserved.
			assertEquals("POST:{\"k\":\"v\"}:application/json", body(covia.invokeSync("v/ops/http/post", Maps.of(
				"url", base(s) + "/temporary", "body", payload,
				"headers", Maps.of("Content-Type", "application/json")), 10_000)));
		} finally {
			s.stop(0);
		}
	}

	@Test public void testRedirectAcrossOriginsDropsCredentials() throws Exception {
		AString caller = Strings.create("did:test:http:redirect-credentials:" + System.nanoTime());
		User user = TestServer.ENGINE.getVenueState().users().ensure(caller);
		byte[] key = SecretStore.deriveKey(TestServer.ENGINE.getKeyPair());
		user.secrets().store("API_KEY", "resolved-api-key", key);
		user.secrets().store("TOKEN", "bearer-token", key);

		HttpServer a = localServer();
		HttpServer b = localServer();   // same host, different port: a different origin
		com.sun.net.httpserver.HttpHandler echo = x -> respond(x, 200,
			x.getRequestHeaders().getFirst("Authorization") + "|"
			+ x.getRequestHeaders().getFirst("X-API-Key") + "|"
			+ x.getRequestHeaders().getFirst("Cookie") + "|"
			+ x.getRequestHeaders().getFirst("X-Trace") + "|"
			+ (x.getRequestHeaders().getFirst("User-Agent") != null));
		String bRoot = base(b);
		a.createContext("/away", x -> redirect(x, 302, bRoot + "/land"));
		a.createContext("/home", x -> redirect(x, 302, "/land"));
		a.createContext("/land", echo);
		b.createContext("/land", echo);
		a.start();
		b.start();
		try {
			ACell headers = Maps.of("X-Trace", "keep", "Cookie", "session=1");
			ACell secrets = Maps.of("X-API-Key", "s/API_KEY");

			// Same origin: every credential survives the hop.
			Job same = TestServer.ENGINE.jobs().invokeOperation("v/ops/http/get", Maps.of(
				Fields.URL, base(a) + "/home", Fields.HEADERS, headers,
				Fields.SECRET_HEADERS, secrets, Fields.BEARER_SECRET, "s/TOKEN"),
				RequestContext.of(caller));
			same.awaitResult(10_000);
			assertEquals("Bearer bearer-token|resolved-api-key|session=1|keep|true", body(same));

			// Cross origin: Authorization, Cookie and every secret header are
			// dropped; ordinary headers and the User-Agent still travel.
			Job away = TestServer.ENGINE.jobs().invokeOperation("v/ops/http/get", Maps.of(
				Fields.URL, base(a) + "/away", Fields.HEADERS, headers,
				Fields.SECRET_HEADERS, secrets, Fields.BEARER_SECRET, "s/TOKEN"),
				RequestContext.of(caller));
			away.awaitResult(10_000);
			assertEquals("null|null|null|keep|true", body(away));
			assertEquals(bRoot + "/land", RT.getIn(away.getOutput(), "url").toString());

			// A literal Authorization header is a credential too.
			Job literal = TestServer.ENGINE.jobs().invokeOperation("v/ops/http/get", Maps.of(
				Fields.URL, base(a) + "/away", Fields.HEADERS, Maps.of("Authorization", "Basic literal")),
				RequestContext.of(caller));
			literal.awaitResult(10_000);
			assertTrue(body(literal).startsWith("null|"), body(literal));
		} finally {
			a.stop(0);
			b.stop(0);
		}
	}

	@Test public void testRedirectLoopLimitAndPrivateTargetsAreRefused() throws Exception {
		HttpServer s = localServer();
		s.createContext("/loop", x -> redirect(x, 302, "/loop"));
		s.createContext("/deep", x -> redirect(x, 302, x.getRequestURI().getPath() + "/x"));
		s.createContext("/meta", x -> redirect(x, 302, "http://169.254.169.254/latest/meta-data/"));
		s.createContext("/broken", x -> redirect(x, 302, "http://exa mple.com/"));
		s.start();
		try {
			String loop = failureOf(Maps.of("url", base(s) + "/loop"));
			assertTrue(loop.contains("Redirect loop"), loop);
			assertTrue(loop.contains("/loop -> " + base(s) + "/loop"), loop);

			String deep = failureOf(Maps.of("url", base(s) + "/deep"));
			assertTrue(deep.contains("Too many redirects (limit " + HTTPAdapter.DEFAULT_MAX_REDIRECTS + ")"), deep);

			// A redirect into the metadata service is refused by the same guard
			// as a direct request would be — and the chain is named.
			String meta = failureOf(Maps.of("url", base(s) + "/meta"));
			assertTrue(meta.contains("Redirect refused") && meta.contains("private/internal"), meta);
			assertTrue(meta.contains("169.254.169.254"), meta);

			String broken = failureOf(Maps.of("url", base(s) + "/broken"));
			assertTrue(broken.contains("malformed Location"), broken);
		} finally {
			s.stop(0);
		}
	}

	@Test public void testConfigureUserAgentListsAndRedirectCap() {
		HTTPAdapter adapter = new HTTPAdapter();
		assertEquals(HTTPAdapter.defaultUserAgent(), adapter.getUserAgent());
		assertEquals(HTTPAdapter.DEFAULT_MAX_REDIRECTS, adapter.getMaxRedirects());

		assertTrue(adapter.configure(Maps.of(
			"userAgent", "MyApp/1.0 (+https://example.com)",
			"maxRedirects", 2,
			"allowedHosts", Vectors.of("LOCALHOST"),
			"blockedHosts", Vectors.of("blocked.example")), false));
		assertEquals("MyApp/1.0 (+https://example.com)", adapter.getUserAgent());
		assertEquals(2, adapter.getMaxRedirects());
		// Configured lists drive the guard: allowed skips the loopback refusal,
		// blocked is refused before any resolution.
		adapter.requireSafeUrl("http://localhost:1/");
		IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
			() -> adapter.requireSafeUrl("https://Blocked.example/x"));
		assertTrue(blocked.getMessage().contains("blocked"), blocked.getMessage());
		// Published for discovery.
		assertEquals("MyApp/1.0 (+https://example.com)", RT.getIn(adapter.info(), "userAgent").toString());
		assertEquals(Vectors.of(Strings.create("localhost")), RT.getIn(adapter.info(), "allowedHosts"));

		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("maxRedirects", 99), false));
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("maxRedirects", -1), false));
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("userAgent", "  "), false));
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("allowedHosts", "not-a-list"), false));
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("blockedHosts", Vectors.of(1)), false));

		// Reconfiguring with nothing restores the defaults.
		assertTrue(adapter.configure(Maps.empty(), false));
		assertEquals(HTTPAdapter.defaultUserAgent(), adapter.getUserAgent());
		assertEquals(HTTPAdapter.DEFAULT_MAX_REDIRECTS, adapter.getMaxRedirects());
		assertThrows(IllegalArgumentException.class, () -> adapter.requireSafeUrl("http://localhost:1/"));
	}

	@Test public void testSameOriginRule() throws Exception {
		assertTrue(HTTPAdapter.sameOrigin(new java.net.URI("https://a.example/x"), new java.net.URI("https://A.EXAMPLE:443/y")));
		assertTrue(HTTPAdapter.sameOrigin(new java.net.URI("http://a.example/x"), new java.net.URI("http://a.example:80/")));
		assertFalse(HTTPAdapter.sameOrigin(new java.net.URI("http://a.example/x"), new java.net.URI("https://a.example/x")));
		assertFalse(HTTPAdapter.sameOrigin(new java.net.URI("http://a.example/x"), new java.net.URI("http://a.example:8080/x")));
		assertFalse(HTTPAdapter.sameOrigin(new java.net.URI("http://a.example/x"), new java.net.URI("http://b.example/x")));
		assertTrue(HTTPAdapter.isRedirect(308) && HTTPAdapter.isRedirect(303));
		assertFalse(HTTPAdapter.isRedirect(304) || HTTPAdapter.isRedirect(200));
	}

	@Test public void testHeaderAndQueryValuesMayBeNumbersOrBooleans() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		HttpServer echo = localServer();
		echo.createContext("/q", x -> respond(x, 200,
			x.getRequestURI().getRawQuery() + "|" + x.getRequestHeaders().getFirst("X-Count")));
		echo.start();
		try {
			// A skill example passes count: 10 as a number; that must not be a
			// ClassCastException before the request even leaves the venue.
			String sent = body(covia.invokeSync("v/ops/http/get", Maps.of(
				"url", base(echo) + "/q",
				"queryParams", Maps.of("count", 10, "q", "two words", "exact", true),
				"headers", Maps.of("X-Count", 7)), 10_000));
			String query = sent.substring(0, sent.indexOf('|'));
			assertTrue(query.contains("count=10"), sent);
			assertTrue(query.contains("q=two+words"), "values are encoded once, by the venue: " + sent);
			assertTrue(query.contains("exact=true"), sent);
			assertEquals("7", sent.substring(sent.indexOf('|') + 1));

			// Structured values are refused with the field named, not cast.
			String refused = failureOf(Maps.of("url", base(echo) + "/q",
				"queryParams", Maps.of("filter", Maps.of("nested", "no"))));
			assertTrue(refused.contains("queryParams.filter must be a string, number or boolean"), refused);
		} finally {
			echo.stop(0);
		}
	}
}
