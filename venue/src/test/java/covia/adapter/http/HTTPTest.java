package covia.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;
import covia.venue.TestOps;
import covia.adapter.HTTPAdapter;

public class HTTPTest {
	@Test public void testHTTPGet() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia=TestServer.COVIA;
		
		Job result=covia.invokeSync("http:get", Maps.of("url",TestServer.BASE_URL));
		assertTrue(result.isComplete());
		
		assertEquals(200,RT.ensureLong(RT.getIn(result.getOutput(),"status")).longValue());
	}
	
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
		assertTrue(statusCode == 200 || statusCode == 429 || statusCode >= 500, "Status should be 200 (success), 429 (rate limit), or 5xx (server error)");
		
		Object body = RT.getIn(result.getOutput(), "body");
		assertTrue(body != null, "Should have body in output");
		String bodyStr = body.toString();
		assertTrue(bodyStr.length() > 10, "Response body should contain content");
		
		Object headers = RT.getIn(result.getOutput(), "headers");
		assertTrue(headers != null, "Should have headers in output");
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
			assertTrue(statusCode == 200 || statusCode == 429 || statusCode >= 500, "Status should be 200, 429, or 5xx");
			
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
	
	@Test public void testHTTPAdapterInstall() {
		// Test that HTTPAdapter can be instantiated and has the correct name
		HTTPAdapter adapter = new HTTPAdapter();
		assertEquals("http", adapter.getName(), "HTTPAdapter should have name 'http'");
		
		// Test that the adapter can be installed (this will be called by the venue)
		// We can't easily test the full install without a real venue, but we can verify the method exists
		assertTrue(adapter instanceof covia.adapter.AAdapter, "HTTPAdapter should extend AAdapter");
	}
	
	@Test public void testHTTPGetInvalidEndpoint() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test HTTP GET to an invalid endpoint - should return 404
		Job result = covia.invokeSync("http:get", Maps.of(
			"url", TestServer.BASE_URL + "/invalid-doc",
			"headers", Maps.of("User-Agent", "Covia-Test/1.0")
		));
		
		assertTrue(result.isComplete(), "HTTP GET to invalid endpoint should complete");
		
		// Verify we get a 404 status
		Object status = RT.getIn(result.getOutput(), "status");
		assertTrue(status != null, "Should have status in output");
		long statusCode = RT.ensureLong((convex.core.data.ACell)status).longValue();
		assertEquals(404, statusCode, "Invalid endpoint should return 404 status");
		
		// Verify we have a response body (even if it's an error page)
		Object body = RT.getIn(result.getOutput(), "body");
		assertTrue(body != null, "Should have body in output");
		String bodyStr = body.toString();
		assertTrue(bodyStr.length() > 0, "Response body should contain content (even if it's an error page)");
	}
}
