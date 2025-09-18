package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import covia.adapter.MCPAdapter;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.api.MCP;
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
	
	// Shared HttpClient for all tests in this class
	private static final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	
	MCP mcpApi;
	
	@SuppressWarnings("unused")
	@BeforeAll
	public void setupServer() throws Exception {
		venue=TestServer.ENGINE;
		mcpApi = new MCP(venue, Maps.empty());
		assumeTrue(TEST_MCP);

		try {
			McpClientTransport transport= HttpClientStreamableHttpTransport.builder(BASE_URL+"/mcp")
					.build();
			mcp=McpClient.sync(transport)
					.requestTimeout(Duration.ofSeconds(10))
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
		assertTrue(tools.size()>0);
	}

	/**
	 * Test for presence of MCP interface
	 */
	@Test public void testMCPWellKnown() throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL+"/.well-known/mcp"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();
		
		CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> resp = future.get(10000, TimeUnit.MILLISECONDS);
		assertEquals(200, resp.statusCode(), ()->"Got error response: "+resp);
	}
	
	/**
	 * Test that listTools returns specific MCP tools from TestAdapter
	 */
	@Test public void testListToolsFromTestAdapter() {
		// Get the test adapter
		TestAdapter testAdapter = (TestAdapter) venue.getAdapter("test");
		assertTrue(testAdapter != null, "TestAdapter should be registered");
		
		// Use reflection to access the private listTools(AAdapter) method
		try {
			AVector<AMap<AString, ACell>> tools =mcpApi.listTools(testAdapter);
			
			// Verify we got some tools
			assertTrue(tools.size() > 0, "TestAdapter should provide at least one MCP tool");
			
			// Look for the "echo" tool specifically
			boolean foundEchoTool = false;
			for (int i = 0; i < tools.size(); i++) {
				AMap<AString, ACell> tool = tools.get(i);
				AString name = RT.ensureString(tool.get(Strings.create("name")));
				if ("echo".equals(name.toString())) {
					foundEchoTool = true;
					
					// Verify the tool has the expected structure
					assertTrue(tool.containsKey(Strings.create("name")), "Tool should have a name");
					assertTrue(tool.containsKey(Strings.create("description")), "Tool should have a description");
					assertTrue(tool.containsKey(Strings.create("inputSchema")), "Tool should have an inputSchema");
					
					// Verify the name is "echo"
					assertEquals("echo", name.toString(), "Tool name should be 'echo'");
					
					break;
				}
			}
			
			assertTrue(foundEchoTool, "Should find the 'echo' tool from TestAdapter");
			
		} catch (Exception e) {
			throw new RuntimeException("Failed to test listTools method", e);
		}
	}
	
	@Test public void testToolCall() throws Exception {
		MCPAdapter mcpAdapter=(MCPAdapter) venue.getAdapter("mcp");
		AMap<AString,ACell> input=Maps.of("foo",2);
		
		ACell result=mcpAdapter.callMCPTool(Strings.create(BASE_URL), "echo",input,null);
		assertEquals(input,result);
	}
	
	@Test public void testProxyToolCall() {
		Venue client = Grid.connect(BASE_URL);
		MCPAdapter mcpAdapter=(MCPAdapter) venue.getAdapter("mcp");
		assertNotNull(mcpAdapter);
		
		Job job=client.invoke(mcpAdapter.TOOL_CALL, 
				Maps.of(
					Fields.SERVER,BASE_URL,
					Fields.TOOL_NAME,"echo",
					Fields.ARGUMENTS,Maps.of(1,2))).join();
		
		AMap<AInteger,AInteger> result=job.awaitResult();
		System.out.println(result);
	}
}
