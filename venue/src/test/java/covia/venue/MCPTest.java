package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import covia.venue.server.VenueServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

@TestInstance(Lifecycle.PER_CLASS)
public class MCPTest {
	
	private static final int PORT=8089;
	private static final String BASE_URL="http://localhost:"+PORT;
	
	private VenueServer venueServer;
	private Venue venue;
	
	McpSyncClient mcp;
	
	@BeforeAll
	public void setupServer() throws Exception {
		venueServer=VenueServer.create(null);
		venue=venueServer.getVenue();
		Venue.addDemoAssets(venue);

		venueServer.start(PORT);
		
		McpClientTransport transport=new HttpClientSseClientTransport(BASE_URL+"/mcp");
		mcp=McpClient.sync(transport).build();
	}
	
	// TODO: test MCP tools

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
	public void cleanup() {
		if (venueServer!=null) {
			venueServer.close();
		}
	}
}
