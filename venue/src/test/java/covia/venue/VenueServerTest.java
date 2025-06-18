package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import convex.java.HTTPClients;
import covia.client.Covia;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.TestAdapter;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueServerTest {
	
	private static final int PORT=8080;
	private static final String BASE_URL="http://localhost:"+PORT;
	
	private VenueServer venueServer;
	private Venue venue;
	private Covia covia;
	
	@BeforeAll
	public void setupServer() throws Exception {
		venue=Venue.createTemp();
		venue.registerAdapter(new TestAdapter());
		Venue.addDemoAssets(venue);
		venueServer=VenueServer.create(null);
		venueServer.start(PORT);
		covia = Covia.create(URI.create(BASE_URL));
	}
	
	@Test public void testAddAsset() throws InterruptedException, ExecutionException {
		// Create a test asset
		ACell meta = Maps.of(
			Keyword.intern("name"), Strings.create("Test Asset"),
			Keyword.intern("description"), Strings.create("A test asset")
		);
		
		// Add the asset
		Hash id=venue.storeAsset(meta, null);
		assertNotNull(id);
		
		// Verify the asset was added
		AString metaString=venue.getMetadata(id);
		assertNotNull(metaString);
		assertTrue(metaString.toString().contains("Test Asset"));
	}
	
	/**
	 * Test for presence of Covia API docs
	 */
	@Test public void testAPIDoc() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.GET, new URI("http://localhost:"+PORT+"/openapi-plugin/openapi-covia-v1.json?v=covia-v1"));
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
		// Get the random operation ID from the demo assets
		Hash randomOpID = null;
		for (var entry : venue.getAssets().entrySet()) {
			ACell meta = RT.getIn(entry.getValue(), 0);
			if (meta != null) {
				ACell name = RT.getIn(meta, "name");
				if (name != null && name.toString().contains("Random numbers")) {
					randomOpID = Hash.parse(entry.getKey().toHexString());
					break;
				}
			}
		}
		assertNotNull(randomOpID, "Should find random operation asset");
		
		// Create input for random operation
		ACell input = Maps.of(
			Keyword.intern("length"), 32L
		);
		
		// Invoke the operation via the client
		String opID = randomOpID.toHexString();
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
	
	@AfterAll
	public void cleanup() {
		if (venueServer!=null) {
			venueServer.close();
		}
	}
}
