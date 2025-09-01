package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Method;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.java.HTTPClients;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

@TestInstance(Lifecycle.PER_CLASS)
public class MCPTest {
	/**
	 * Toggle to enable / disable MCP tests
	 */
	public static final boolean TEST_MCP=true;
	
	static final int PORT=TestServer.PORT;
	static final String BASE_URL=TestServer.BASE_URL;
	
	Engine venue;
	
	McpSyncClient mcp;
	
	@SuppressWarnings("unused")
	@BeforeAll
	public void setupServer() throws Exception {
		venue=TestServer.ENGINE;
		assumeTrue(TEST_MCP);

		try {
			McpClientTransport transport= HttpClientStreamableHttpTransport .builder(BASE_URL+"/mcp")
					.build();
			mcp=McpClient.sync(transport)
					.requestTimeout(Duration.ofSeconds(3))
					.build();
			InitializeResult ir=mcp.initialize();
		} catch (Throwable t) {
			System.err.println("MCP initialisation failure: "+t);
			t.printStackTrace();
			assumeTrue(false,"Aborted due to MCP initialisation failure");
		}
	}
	
	// TODO: test MCP tools
	
	@Test public void testPing() {
		mcp.ping();
	}
	
	@Test public void testToolsList() {
		ListToolsResult lr=mcp.listTools();
		List<Tool> tools = lr.tools();
		assertEquals(0,tools.size());
	}

	/**
	 * Test for presence of MCP interface
	 */
	@Test public void testMCPWellKnown() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		SimpleHttpRequest req=SimpleHttpRequest.create(Method.GET, new URI(BASE_URL+"/.well-known/mcp"));
		CompletableFuture<SimpleHttpResponse> future=HTTPClients.execute(req);
		SimpleHttpResponse resp=future.get(10000,TimeUnit.MILLISECONDS);
		assertEquals(200,resp.getCode(),()->"Got error response: "+resp);
	}
}
