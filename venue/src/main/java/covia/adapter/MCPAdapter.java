package covia.adapter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.JobFailedException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

public class MCPAdapter extends AAdapter {
	
	public static final Logger log=LoggerFactory.getLogger(MCPAdapter.class);

	
	public  Hash TOOL_CALL;
	public  Hash TOOLS_LIST;

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
		TOOLS_LIST=installAsset("/adapters/mcp/toolList.json");
		
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		String[] opSpec = operation.split(":");
		if (opSpec.length<2) {
			throw new IllegalArgumentException("Insufficient specification for MCP operation: "+operation);
		}
		
		String feature=opSpec[1];
		if (feature.equals("tools")) {
			
			String function=opSpec[2];
			if (function.equals("call")) {
				// Standard MCP tool call
				return CompletableFuture.supplyAsync(() -> {
					try {
						// Remote tool name is from input if provided
						AString remoteToolName=RT.getIn(input, Fields.TOOL_NAME);
						
						// Extract operation name from "mcp:tools:call:operationName" format
						if ((remoteToolName==null)&&(opSpec.length>=3)) {
							remoteToolName=Strings.create(opSpec[3]);
						}

						// Fallback: see if "remoteToolName" is specified in operation
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
						
						// Get API access token, if provided
						AString token=RT.getIn(input, Fields.TOKEN);
						String accessToken=(token==null)?null:token.toString();
						
						// Make the MCP tool call
						return callMCPTool(serverUrl, remoteToolName.toString(), input, accessToken);
						
					} catch (Exception e) {
						throw new JobFailedException(e);
					}
				});
				
			} else if (function.equals("list")) {
				// List available MCP tools
				return CompletableFuture.supplyAsync(() -> {
					try {
						// Get MCP server URL from metadata or input
						AString serverUrl = getServerUrl(meta, input);
						if (serverUrl == null) {
							throw new JobFailedException("No server URL provided in input (or asset metadata fallback)");
						}
						
						// Get API access token, if provided
						AString token = RT.getIn(input, Fields.TOKEN);
						String accessToken = (token == null) ? null : token.toString();
						
						// List the MCP tools
						return listMCPTools(serverUrl, accessToken);
						
					} catch (Exception e) {
						throw new JobFailedException(e);
					}
				});
			} else {
				throw new UnsupportedOperationException("Unsupported tools function: "+operation);
			}
		} else {
			throw new UnsupportedOperationException("Unsupported MCP feature: "+operation);
		}
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
	 * Connect to an MCP server via a base URL
	 * @param baseURL
	 * @return McpSyncClient instance
	 * @throws Exception
	 */
	public McpSyncClient connect(String baseURL, String accessToken) throws Exception {
		McpClientTransport transport= HttpClientStreamableHttpTransport.builder(baseURL+"/mcp")
					.customizeRequest(b->{
						if ((accessToken!=null)&&(!accessToken.isEmpty())) {
							b.header("Authorization", "Bearer "+accessToken);
						}
					})
					.build();
		McpSyncClient mcp=McpClient.sync(transport)
					.requestTimeout(Duration.ofSeconds(10))
					.build();
		@SuppressWarnings("unused")
		InitializeResult ir=mcp.initialize();
		return mcp;
	}
	
	/**
	 * Makes an MCP tool call to the specified server
	 * @param accessToken 
	 */
	public ACell callMCPTool(AString serverUrl, String toolName, ACell input, String accessToken) throws Exception {
		McpSyncClient client = connect(serverUrl.toString(),accessToken);
		
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
			.arguments((Map<String,Object>)JSON.json(toolParams))
			.build();

		CallToolResult response=client.callTool(request);
		System.out.println("MCPAdapter response: "+response);
		
		return RT.cvm(response.structuredContent());
	}
	
	/**
	 * Converts MCP JsonSchema to Convex format
	 * @param jsonSchema The MCP JsonSchema object
	 * @return ACell representing the JSON schema in Convex format
	 */
	private ACell getInputSchema(JsonSchema jsonSchema) {
		try {
			// Build the schema map from the record fields
			AMap<AString, ACell> schemaMap = Maps.empty();
			
			// Add type if present
			if (jsonSchema.type() != null) {
				schemaMap = schemaMap.assoc(Strings.create("type"), Strings.create(jsonSchema.type()));
			}
			
			// Add properties if present
			if (jsonSchema.properties() != null && !jsonSchema.properties().isEmpty()) {
				AMap<AString, ACell> propertiesMap = Maps.empty();
				for (Map.Entry<String, Object> entry : jsonSchema.properties().entrySet()) {
					ACell value = convertToConvex(entry.getValue());
					propertiesMap = propertiesMap.assoc(Strings.create(entry.getKey()), value);
				}
				schemaMap = schemaMap.assoc(Strings.create("properties"), propertiesMap);
			}
			
			// Add required fields if present
			if (jsonSchema.required() != null && !jsonSchema.required().isEmpty()) {
				AVector<AString> requiredVector = Vectors.empty();
				for (String required : jsonSchema.required()) {
					requiredVector = requiredVector.conj(Strings.create(required));
				}
				schemaMap = schemaMap.assoc(Strings.create("required"), requiredVector);
			}
			
			// Add additionalProperties if present
			if (jsonSchema.additionalProperties() != null) {
				schemaMap = schemaMap.assoc(Strings.create("additionalProperties"), RT.cvm(jsonSchema.additionalProperties()));
			}
			
			// Add $defs if present
			if (jsonSchema.defs() != null && !jsonSchema.defs().isEmpty()) {
				AMap<AString, ACell> defsMap = Maps.empty();
				for (Map.Entry<String, Object> entry : jsonSchema.defs().entrySet()) {
					ACell value = convertToConvex(entry.getValue());
					defsMap = defsMap.assoc(Strings.create(entry.getKey()), value);
				}
				schemaMap = schemaMap.assoc(Strings.create("$defs"), defsMap);
			}
			
			// Add definitions if present (legacy field)
			if (jsonSchema.definitions() != null && !jsonSchema.definitions().isEmpty()) {
				AMap<AString, ACell> definitionsMap = Maps.empty();
				for (Map.Entry<String, Object> entry : jsonSchema.definitions().entrySet()) {
					ACell value = convertToConvex(entry.getValue());
					definitionsMap = definitionsMap.assoc(Strings.create(entry.getKey()), value);
				}
				schemaMap = schemaMap.assoc(Strings.create("definitions"), definitionsMap);
			}
			
			return schemaMap;
			
		} catch (Exception e) {
			// If conversion fails, return a basic schema structure
			log.warn("Failed to convert JsonSchema to Convex format: " + e.getMessage());
			return Maps.of(
				"type", "object",
				"description", "Input parameters for the tool"
			);
		}
	}
	
	/**
	 * Helper method to convert JsonSchema objects to Convex format
	 * @param obj The Java object to convert
	 * @return ACell representation of the object
	 */
	private ACell convertToConvex(Object obj) {
		if (obj == null) {
			return null;
		} else if (obj instanceof String) {
			return Strings.create((String) obj);
		} else if (obj instanceof Boolean) {
			return RT.cvm((Boolean) obj);
		} else if (obj instanceof Number) {
			return RT.cvm((Number) obj);
		} else if (obj instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) obj;
			AMap<AString, ACell> result = Maps.empty();
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				ACell value = convertToConvex(entry.getValue());
				result = result.assoc(Strings.create(entry.getKey()), value);
			}
			return result;
		} else if (obj instanceof List) {
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) obj;
			AVector<ACell> result = Vectors.empty();
			for (Object item : list) {
				ACell value = convertToConvex(item);
				result = result.conj(value);
			}
			return result;
		} else {
			// For other types, convert to string
			return Strings.create(obj.toString());
		}
	}
	
	/**
	 * Lists available MCP tools from the specified server
	 * @param serverUrl The MCP server URL
	 * @param accessToken Optional access token for authentication
	 * @return ACell containing the list of tools
	 * @throws Exception if the operation fails
	 */
	public ACell listMCPTools(AString serverUrl, String accessToken) throws Exception {
		McpSyncClient client = connect(serverUrl.toString(), accessToken);
		
		try {
			// Get the list of tools from the MCP server
			ListToolsResult result = client.listTools();
			List<Tool> tools = result.tools();
			
			// Convert the tools to Covia format
			AVector<AMap<AString, ACell>> toolsVector = Vectors.empty();
			
			for (Tool tool : tools) {
				ACell inputSchema = getInputSchema(tool.inputSchema());
				AMap<AString, ACell> toolMap = Maps.of(
					Fields.NAME, Strings.create(tool.name()),
					Fields.DESCRIPTION, Strings.create(tool.description()),
					Fields.INPUT_SCHEMA, RT.cvm(inputSchema)
				);
				toolsVector = toolsVector.conj(toolMap);
			}
			
			// Return the tools in a structured format
			return Maps.of(
				"tools", toolsVector,
				Fields.TOTAL, AInteger.create(tools.size())
			);
			
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
	
}
