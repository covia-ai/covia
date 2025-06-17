package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import convex.java.HTTPClients;
import covia.client.Covia;
 
@TestInstance(Lifecycle.PER_CLASS)
public class VenueServerTest {
	static final int PORT=8088;
	
	private VenueServer venueServer;

	@BeforeAll
	public void setupServer() {
		venueServer=VenueServer.create(null);
		venueServer.start(PORT);
	}
	
	@Test public void testAddAsset() throws InterruptedException, ExecutionException {
		Covia covia=Covia.create(URI.create("http://localhost:"+PORT));
		Future<Result> r=covia.addAsset("{}");
		
		Result result=r.get();
		assertFalse(result.isError(),()->"Bad Result: "+result);
		assertEquals("0x44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",result.getValue().toString());
		
		Future<String> r2=covia.getMeta("0x44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
		assertEquals("{}",r2.get());
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
	
	@AfterAll
	public void shutDown() {
		venueServer.close();
	}
}
