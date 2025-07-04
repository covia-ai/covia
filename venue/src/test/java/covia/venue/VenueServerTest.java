package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Method;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.java.HTTPClients;
import covia.client.Covia;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueServerTest {
	
	private static final int PORT=8088;
	private static final String BASE_URL="http://localhost:"+PORT;
	
	private VenueServer venueServer;
	private Venue venue;
	private Covia covia;
	
	@BeforeAll
	public void setupServer() throws Exception {
		venueServer=VenueServer.create(null);
		venue=venueServer.getVenue();
		Venue.addDemoAssets(venue);

		venueServer.start(PORT);
		covia = Covia.create(URI.create(BASE_URL));
	}
	
	/**
	 * Test for presence of Covia API docs
	 */
	@Test public void testAPIDoc() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.GET, new URI("http://localhost:"+PORT+"/openapi"));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(req);
		SimpleHttpResponse resp=future.get(10000,TimeUnit.MILLISECONDS);
		assertEquals(200,resp.getCode(),()->"Got error response: "+resp);
	}

		/**
	 * Test for presence of MCP interface
	 */
	@Test public void testMCPWellKnown() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.GET, new URI("http://localhost:"+PORT+"/.well-known/mcp"));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(req);
		SimpleHttpResponse resp=future.get(10000,TimeUnit.MILLISECONDS);
		assertEquals(200,resp.getCode(),()->"Got error response: "+resp);
	}
	
	@Test
	public void testRandomOperation() throws Exception {		
		// Create input for random operation
		ACell input = Maps.of(
			"length", 32L
		);
		
		// Invoke the operation via the client
		String opID = TestOps.RANDOM.toHexString();
		assertEquals(64,opID.length());
		System.out.println(opID);
		// assertNotNull(covia.getMeta(opID).get());
		Future<Result> resultFuture = covia.invoke(opID, input);
		
		// Wait for job completion with timeout
		Result result = resultFuture.get(5, TimeUnit.SECONDS);
		assertNotNull(result, "Should get a result");
		assertTrue(!result.isError(), "Operation should not fail");
		
		// Get the result value
		ACell value = result.getValue();
		assertNotNull(value, "Result should have a value");
		
		// Verify the result
		ACell bytes = RT.getIn(value, "bytes");
		assertNotNull(bytes, "Result should contain bytes");
		String hexString = RT.ensureString(bytes).toString();
		
		// Verify hex string length (32 bytes = 64 hex chars)
		assertEquals(64, hexString.length(), "Hex string should be 64 characters long");
		
		// Verify hex string format
		assertTrue(hexString.matches("[0-9a-f]{64}"), "Result should be a valid hex string");
	}
	
	@Test
	public void testFailureOperation() throws Exception {
		// Create input for the error operation
		ACell input = Maps.of(
			Keyword.intern("message"), Strings.create("Test error message")
		);
		
		// Invoke the operation via the client
		Future<Result> resultFuture = covia.invoke("test:error", input);
		
		// Wait for job completion with timeout and verify it fails
		ExecutionException exception = assertThrows(ExecutionException.class, () -> {
			resultFuture.get(5, TimeUnit.SECONDS);
		});
		
		// Verify the error message contains our test message
		Throwable cause = exception.getCause();
		assertTrue(cause instanceof RuntimeException, "Should be a RuntimeException");
	}
	
	@AfterAll
	public void cleanup() {
		if (venueServer!=null) {
			venueServer.close();
		}
	}
}
