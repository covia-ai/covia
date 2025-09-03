package covia.adapter;



import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import covia.grid.Status;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;

public class MCPAdapter extends AAdapter {
	
	@Override
	public String getName() {
		return "mcp";
	}
	
	@Override
	public String getDescription() {
		return "A Model Context Protocol (MCP) adapter that enables seamless integration with MCP-compatible AI models and tools. " +
			   "Provides standardised communication protocols for AI agents to interact with external systems and services. " +
			   "Essential for building sophisticated AI workflows and connecting with modern AI development ecosystems.";
	}
	
	@Override
	protected void installAssets() {
		installAsset("/adapters/mcp/toolCall.json");
		
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				// Extract operation name from "mcp:operationName" format
				String operationName = operation.contains(":") ? operation.split(":")[1] : operation;
				
				// Get MCP server URL from metadata or input
				String serverUrl = getServerUrl(meta, input);
				if (serverUrl == null) {
					return Maps.of(
						"status", Status.FAILED,
						"message", Strings.create("MCP server URL not provided")
					);
				}
				
				// Make the MCP tool call
				return callMCPTool(serverUrl, operationName, input);
				
			} catch (Exception e) {
				return Maps.of(
					"status", Status.FAILED,
					"message", Strings.create("MCP tool call failed: " + e.getMessage())
				);
			}
		});
	}
	
	public McpSyncClient connect(String baseURL) throws Exception {
		McpClientTransport transport= HttpClientStreamableHttpTransport.builder(baseURL+"/mcp")
					.build();
		McpSyncClient mcp=McpClient.sync(transport)
					.requestTimeout(Duration.ofSeconds(10))
					.build();
		@SuppressWarnings("unused")
		InitializeResult ir=mcp.initialize();
		return mcp;
	}
	
	/**
	 * Extracts the MCP server URL from metadata or input parameters
	 */
	@SuppressWarnings("unchecked")
	private String getServerUrl(ACell meta, ACell input) {
		// First try to get from input parameters
		if (input instanceof AMap) {
			AMap<AString, ACell> inputMap = (AMap<AString, ACell>) input;
			ACell serverUrlCell = inputMap.get(Strings.create("serverUrl"));
			if (serverUrlCell instanceof AString) {
				return serverUrlCell.toString();
			}
		}
		
		// Then try to get from metadata
		if (meta instanceof AMap) {
			AMap<AString, ACell> metaMap = (AMap<AString, ACell>) meta;
			ACell serverUrlCell = metaMap.get(Strings.create("serverUrl"));
			if (serverUrlCell instanceof AString) {
				return serverUrlCell.toString();
			}
		}
		
		return null;
	}
	
	/**
	 * Makes an MCP tool call to the specified server
	 */
	private ACell callMCPTool(String serverUrl, String toolName, ACell input) throws Exception {
		McpSyncClient client = connect(serverUrl);
		
		try {
			// Convert input to MCP tool call parameters
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> inputMap = input instanceof AMap ? (AMap<AString, ACell>) input : Maps.empty();
			
			// Remove serverUrl from input parameters if present
			AMap<AString, ACell> toolParams = inputMap.dissoc(Strings.create("serverUrl"));
			
			// Make the tool call using the MCP client
			// Note: The actual method name may vary depending on the MCP client library version
			// This is a placeholder - we'll need to check the actual API
			ACell result = makeToolCall(client, toolName, toolParams);
			
			// Convert result to Covia format
			return Maps.of(
				"status", Status.COMPLETE,
				"result", result 
			);
			
		} finally {
			// Close the client connection
			try {
				client.close();
			} catch (Exception e) {
				// Log but don't fail the operation
				System.err.println("Warning: Failed to close MCP client: " + e.getMessage());
			}
		}
	}
	
	/**
	 * Makes a tool call using the MCP client
	 */
	private ACell makeToolCall(McpSyncClient client, String toolName, AMap<AString, ACell> toolParams) throws Exception {

		@SuppressWarnings("unchecked")
		CallToolRequest request = CallToolRequest.builder()
			.name(toolName)
			.arguments((Map<String,Object>)JSONUtils.json(toolParams))
			.build();

		CallToolResult response=client.callTool(request);
		
		return RT.cvm(response.structuredContent());
	}
	
}
