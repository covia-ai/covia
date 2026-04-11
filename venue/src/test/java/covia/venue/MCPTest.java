package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
		mcpApi = new MCP(new LocalVenue(venue), Maps.empty());
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
			
			// Look for the "test_echo" tool specifically (sanitised from "test:echo" for MCP compliance)
			boolean foundEchoTool = false;
			for (int i = 0; i < tools.size(); i++) {
				AMap<AString, ACell> tool = tools.get(i);
				AString name = RT.ensureString(tool.get(Fields.NAME));
				if ("test_echo".equals(name.toString())) {
					foundEchoTool = true;

					// Verify the tool has the expected structure
					assertTrue(tool.containsKey(Fields.NAME), "Tool should have a name");
					assertTrue(tool.containsKey(Fields.DESCRIPTION), "Tool should have a description");
					assertTrue(tool.containsKey(Fields.INPUT_SCHEMA), "Tool should have an inputSchema");

					// Verify the name is sanitised (colons replaced with underscores)
					assertEquals("test_echo", name.toString(), "Tool name should be 'test_echo' (MCP-sanitised)");

					break;
				}
			}

			assertTrue(foundEchoTool, "Should find the 'test_echo' tool from TestAdapter");
			
		} catch (Exception e) {
			throw new RuntimeException("Failed to test listTools method", e);
		}
	}
	
	@Test public void testToolCall() throws Exception {
		MCPAdapter mcpAdapter=(MCPAdapter) venue.getAdapter("mcp");
		AMap<AString,ACell> input=Maps.of("foo",2);

		ACell result=mcpAdapter.callMCPTool(Strings.create(BASE_URL), "test_echo",input,null);
		assertEquals(input,result);
	}
	
	@Test public void testListMCPTools() throws Exception {
		MCPAdapter mcpAdapter=(MCPAdapter) venue.getAdapter("mcp");
		
		ACell result=mcpAdapter.listMCPTools(Strings.create(BASE_URL), null);
		assertNotNull(result);
		
		// Verify the result is a map with tools and total fields
		assertTrue(result instanceof AMap, "Result should be a map");
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> resultMap = (AMap<AString, ACell>) result;
		
		// Check that we have a tools field
		ACell tools = resultMap.get(Fields.TOOLS);
		assertNotNull(tools, "Result should contain tools field");
		assertTrue(tools instanceof AVector, "Tools should be a vector");
		
		// Check that we have a total field
		ACell total = resultMap.get(Fields.TOTAL);
		assertNotNull(total, "Result should contain total field");
		assertTrue(total instanceof AInteger, "Total should be an integer");
		
		// Verify we have at least one tool (the echo tool from TestAdapter)
		@SuppressWarnings("unchecked")
		AVector<AMap<AString, ACell>> toolsVector = (AVector<AMap<AString, ACell>>) tools;
		assertTrue(toolsVector.size() > 0, "Should have at least one tool");
		
		// Check the structure of the first tool
		AMap<AString, ACell> firstTool = toolsVector.get(0);
		assertTrue(firstTool.containsKey(Fields.NAME), "Tool should have a name");
		assertTrue(firstTool.containsKey(Fields.DESCRIPTION), "Tool should have a description");
		assertTrue(firstTool.containsKey(Fields.INPUT_SCHEMA), "Tool should have an inputSchema");
	}
	
	@Test public void testProxyToolCall() {
		Venue client = Grid.connect(BASE_URL);
		MCPAdapter mcpAdapter=(MCPAdapter) venue.getAdapter("mcp");
		assertNotNull(mcpAdapter);

		AMap<AString,AInteger> arguments=Maps.of("test",2);

		Job job=client.invoke(mcpAdapter.TOOL_CALL,
				Maps.of(
					Fields.SERVER,BASE_URL,
					Fields.TOOL_NAME,"test_echo",
					Fields.ARGUMENTS,arguments)).join();

		AMap<AString,AInteger> result=job.awaitResult(5000);
		assertEquals(arguments,result);
	}
	
	@Test public void testProxyToolsList() {
		Venue client = Grid.connect(BASE_URL);
		MCPAdapter mcpAdapter=(MCPAdapter) venue.getAdapter("mcp");
		assertNotNull(mcpAdapter);

		// Test the tools:list operation through the venue interface
		Job job=client.invoke(mcpAdapter.TOOLS_LIST,
				Maps.of(Fields.SERVER, BASE_URL)).join();

		ACell result=job.awaitResult(5000);
		assertNotNull(result);

		// Verify the result structure
		assertTrue(result instanceof AMap, "Result should be a map");
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> resultMap = (AMap<AString, ACell>) result;

		// Check that we have a tools field
		ACell tools = resultMap.get(Fields.TOOLS);
		assertNotNull(tools, "Result should contain tools field");
		assertTrue(tools instanceof AVector, "Tools should be a vector");

		// Check that we have a total field
		ACell total = resultMap.get(Fields.TOTAL);
		assertNotNull(total, "Result should contain total field");
		assertTrue(total instanceof AInteger, "Total should be an integer");

	}

	// ===== Protocol-level adversarial tests =====

	/** POST raw JSON to /mcp and return the response */
	private HttpResponse<String> postMcp(String json) throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/mcp"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json))
			.timeout(Duration.ofSeconds(10))
			.build();
		return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
	}

	@Test public void testInvalidJson() throws Exception {
		HttpResponse<String> resp = postMcp("{not valid json!!!");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("error"), "Should return JSON-RPC error");
	}

	@Test public void testEmptyBody() throws Exception {
		HttpResponse<String> resp = postMcp("");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("error"), "Should return JSON-RPC error");
	}

	@Test public void testUnknownMethod() throws Exception {
		HttpResponse<String> resp = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"nonexistent/method\",\"id\":1}");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("error"), "Should return method-not-found error");
		assertTrue(resp.body().contains("-32601"), "Error code should be -32601");
	}

	@Test public void testCallUnknownTool() throws Exception {
		HttpResponse<String> resp = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"nonexistent:tool\",\"arguments\":{}},\"id\":1}");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("error"), "Should return error for unknown tool");
	}

	@Test public void testEmptyBatch() throws Exception {
		HttpResponse<String> resp = postMcp("[]");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("error"), "Should return error for empty batch");
	}

	@Test public void testBatchRequest() throws Exception {
		HttpResponse<String> resp = postMcp(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}," +
			"{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":2}]");
		assertEquals(200, resp.statusCode());
		// Batch response should be an array
		String body = resp.body().trim();
		assertTrue(body.startsWith("["), "Batch response should be an array");
	}

	@Test public void testNotificationReturns202() throws Exception {
		// A notification (no "id" field) should return 202
		HttpResponse<String> resp = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
		assertEquals(202, resp.statusCode());
	}

	@Test public void testInitializeReturnsSessionId() throws Exception {
		HttpResponse<String> resp = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1}");
		assertEquals(200, resp.statusCode());
		String sessionId = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
		assertNotNull(sessionId, "Initialize should return Mcp-Session-Id header");
		assertFalse(sessionId.isEmpty());
		assertTrue(resp.body().contains("protocolVersion"), "Should contain protocol version");
		assertTrue(resp.body().contains("serverInfo"), "Should contain server info");
	}

	@Test public void testGetSseWithoutSession() throws Exception {
		// GET /mcp without a valid session should return 400
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/mcp"))
			.header("Accept", "text/event-stream")
			.header("Mcp-Session-Id", "bogus-session-id")
			.GET()
			.timeout(Duration.ofSeconds(5))
			.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(400, resp.statusCode());
	}

	@Test public void testGetSseWithoutAcceptHeader() throws Exception {
		// GET /mcp without Accept: text/event-stream should return 405
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/mcp"))
			.header("Accept", "application/json")
			.GET()
			.timeout(Duration.ofSeconds(5))
			.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(405, resp.statusCode());
	}

	@Test public void testDeleteUnknownSession() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/mcp"))
			.header("Mcp-Session-Id", "nonexistent-session-id")
			.DELETE()
			.timeout(Duration.ofSeconds(5))
			.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(404, resp.statusCode());
	}

	@Test public void testDeleteWithoutSessionHeader() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/mcp"))
			.DELETE()
			.timeout(Duration.ofSeconds(5))
			.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(400, resp.statusCode());
	}

	@Test public void testProtocolVersionNegotiation() throws Exception {
		// Client requesting an older version should get that version back
		HttpResponse<String> resp = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1," +
			"\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("\"2024-11-05\""), "Server should negotiate down to client version");

		// Client requesting the latest version should get the latest back
		HttpResponse<String> resp2 = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":2," +
			"\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}");
		assertEquals(200, resp2.statusCode());
		assertTrue(resp2.body().contains("\"2025-06-18\""), "Server should return latest when client supports it");

		// Client requesting a future version should get the server's latest
		HttpResponse<String> resp3 = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":3," +
			"\"params\":{\"protocolVersion\":\"2099-01-01\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}");
		assertEquals(200, resp3.statusCode());
		assertTrue(resp3.body().contains("\"2025-06-18\""), "Server should cap at its latest version");

		// No params at all should return latest version
		HttpResponse<String> resp4 = postMcp(
			"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":4}");
		assertEquals(200, resp4.statusCode());
		assertTrue(resp4.body().contains("\"2025-06-18\""), "Server should return latest when no version specified");
	}

	/**
	 * Validate that all MCP tool schemas conform to JSON Schema draft 2020-12.
	 * Specifically checks that no schemas use invalid type values like "any"
	 * or contain non-standard keys like "secret" that strict validators reject.
	 */
	@Test public void testToolSchemasValid() {
		Set<String> VALID_TYPES = Set.of(
			"null", "boolean", "object", "array", "number", "string", "integer"
		);
		Set<String> NON_SCHEMA_KEYS = Set.of("secret", "secretFields");

		for (String adapterName : venue.getAdapterNames()) {
			var adapter = venue.getAdapter(adapterName);
			if (adapter == null) continue;

			AVector<AMap<AString, ACell>> tools = mcpApi.listTools(adapter);
			for (long i = 0; i < tools.count(); i++) {
				AMap<AString, ACell> tool = tools.get(i);
				AString toolName = RT.ensureString(tool.get(Fields.NAME));

				@SuppressWarnings("unchecked")
				AMap<AString, ACell> inputSchema = (AMap<AString, ACell>) tool.get(Fields.INPUT_SCHEMA);
				assertNotNull(inputSchema, "Tool " + toolName + " should have inputSchema");
				assertSchemaValid(inputSchema, toolName + ".inputSchema", VALID_TYPES, NON_SCHEMA_KEYS);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void assertSchemaValid(AMap<AString, ACell> schema, String path,
			Set<String> validTypes, Set<String> nonSchemaKeys) {
		// Check type value is valid
		ACell typeVal = schema.get(Fields.TYPE);
		if (typeVal instanceof AString ts) {
			assertTrue(validTypes.contains(ts.toString()),
				path + " has invalid type: \"" + ts + "\"");
		}

		// Check no non-standard keys
		for (String key : nonSchemaKeys) {
			assertFalse(schema.containsKey(Strings.create(key)),
				path + " contains non-standard key: \"" + key + "\"");
		}

		// Recurse into properties
		ACell propsCell = schema.get(Fields.PROPERTIES);
		if (propsCell instanceof AMap<?,?> props) {
			long n = props.count();
			for (long i = 0; i < n; i++) {
				var entry = props.entryAt(i);
				if (entry.getValue() instanceof AMap<?,?> propSchema) {
					assertSchemaValid((AMap<AString, ACell>) propSchema,
						path + ".properties." + entry.getKey(), validTypes, nonSchemaKeys);
				}
			}
		}

		// Recurse into items
		ACell itemsCell = schema.get(Fields.ITEMS);
		if (itemsCell instanceof AMap<?,?> itemsMap) {
			assertSchemaValid((AMap<AString, ACell>) itemsMap,
				path + ".items", validTypes, nonSchemaKeys);
		}
	}

	@Test public void testWellKnownStructure() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
			.uri(new URI(BASE_URL + "/.well-known/mcp"))
			.GET()
			.timeout(Duration.ofSeconds(10))
			.build();
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		String body = resp.body();
		assertTrue(body.contains("mcp_version"), "Should contain mcp_version");
		assertTrue(body.contains("streamable-http"), "Should contain transport type");
		assertTrue(body.contains("server_url"), "Should contain server_url");
	}
}
