package covia.adapter;



import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import covia.api.Fields;
import covia.exception.JobFailedException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;

public class MCPAdapter extends AAdapter {
	
	public static final Logger log=LoggerFactory.getLogger(MCPAdapter.class);

	
	public  Hash TOOL_CALL;

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
		TOOL_CALL=installAsset("/adapters/mcp/toolCall.json");
		
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				// Extract operation name from "mcp:operationName" format
				String[] opSpec = operation.split(":");
				
				AString remoteToolName=RT.getIn(input, Fields.TOOL_NAME);
				if (remoteToolName==null) {
					remoteToolName=RT.getIn(meta, Fields.OPERATION,Fields.REMOTE_TOOL_NAME);
				}
				if (remoteToolName==null) {
					throw new JobFailedException("No remote tool name provided (either in input or operation metadata)");
				}
				
				// Get MCP server URL from metadata or input
				AString serverUrl =getServerUrl(meta, input);
				if (serverUrl == null) {
					throw new JobFailedException("No server URL provided in input (or asset metadata fallback)");
				}
				
				// Make the MCP tool call
				return callMCPTool(serverUrl, remoteToolName.toString(), input);
				
			} catch (Exception e) {
				throw new JobFailedException(e);
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
	private AString getServerUrl(ACell meta, ACell input) {
		// First try to get from input parameters
		if (input instanceof AMap) {
			ACell serverUrlCell = RT.getIn(input, Fields.SERVER);
			if (serverUrlCell instanceof AString url) {
				return url;
			}
		}
		
		// Then try to get from metadata
		if (meta instanceof AMap) {
			AMap<AString, ACell> metaMap = (AMap<AString, ACell>) meta;
			ACell serverUrlCell = metaMap.get(Strings.create("server"));
			if (serverUrlCell instanceof AString url) {
				return url;
			}
		}
		
		return null;
	}
	
	/**
	 * Makes an MCP tool call to the specified server
	 */
	public ACell callMCPTool(AString serverUrl, String toolName, ACell input) throws Exception {
		McpSyncClient client = connect(serverUrl.toString());
		
		try {
			AMap<AString,ACell> toolArgs=RT.ensureMap(input);
			
			// Make the tool call using the MCP client
			// Note: The actual method name may vary depending on the MCP client library version
			// This is a placeholder - we'll need to check the actual API
			ACell result = makeToolCall(client, toolName, toolArgs);
			
			return result;
			
		} finally {
			// Close the client connection
			try {
				client.close();
			} catch (Exception e) {
				// Log but don't fail the operation
				log.warn("Warning: Failed to close MCP client: " + e.getMessage());
			}
		}
	}
	
	/**
	 * Makes a tool call using the MCP client
	 */
	private ACell makeToolCall(McpSyncClient client, String toolName, AMap<AString,ACell> toolParams) throws Exception {

		@SuppressWarnings("unchecked")
		CallToolRequest request = CallToolRequest.builder()
			.name(toolName)
			.arguments((Map<String,Object>)JSONUtils.json(toolParams))
			.build();

		CallToolResult response=client.callTool(request);
		System.out.println("MCP response: "+response);
		System.out.println("MCP content: "+response.content());
		System.out.println("MCP structuredContent: "+response.structuredContent());
		
		return RT.cvm(response.structuredContent());
	}
	
}
