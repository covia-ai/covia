package covia.adapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
import covia.venue.RequestContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

public class MCPAdapter extends AAdapter {

	public static final Logger log=LoggerFactory.getLogger(MCPAdapter.class);

	/** Persistent client sessions keyed by "serverUrl|token" */
	private final ConcurrentHashMap<String, McpClientSession> clientSessions = new ConcurrentHashMap<>();

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
		TOOL_CALL  = installAsset("mcp/tools-call", "/adapters/mcp/toolCall.json");
		TOOLS_LIST = installAsset("mcp/tools-list", "/adapters/mcp/toolList.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// getSubOperation returns everything after "mcp:", e.g. "tools:call" or "tools:list"
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			throw new IllegalArgumentException("Insufficient specification for MCP operation");
		}

		String[] subParts = subOp.split(":");
		String feature = subParts[0];

		if (feature.equals("tools")) {
			if (subParts.length < 2) {
				throw new IllegalArgumentException("MCP tools operation requires function (call/list)");
			}
			String function = subParts[1];

			if (function.equals("call")) {
				// Standard MCP tool call
				return CompletableFuture.supplyAsync(() -> {
					try {
						// Remote tool name is from input if provided
						AString remoteToolName=RT.getIn(input, Fields.TOOL_NAME);

						// Extract operation name from "mcp:tools:call:operationName" format
						if ((remoteToolName==null)&&(subParts.length>=3)) {
							remoteToolName=Strings.create(subParts[2]);
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

						AMap<AString,ACell> toolArguments=RT.getIn(input, Fields.ARGUMENTS);
						if (toolArguments == null) {
							throw new JobFailedException("Tool call requires arguments as a JSON object");
						}

						// Get API access token, if provided
						AString token=RT.getIn(input, Fields.TOKEN);
						String accessToken=(token==null)?null:token.toString();

						// Make the MCP tool call
						return callMCPTool(serverUrl, remoteToolName.toString(), toolArguments, accessToken);

					} catch (Exception e) {
						throw new JobFailedException(e);
					}
				}, VIRTUAL_EXECUTOR);

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
				}, VIRTUAL_EXECUTOR);
			} else {
				throw new UnsupportedOperationException("Unsupported tools function: " + function);
			}
		} else {
			throw new UnsupportedOperationException("Unsupported MCP feature: " + feature);
		}
	}

	/**
	 * Extracts the MCP server URL from metadata or input parameters.
	 */
	private AString getServerUrl(AMap<AString, ACell> meta, ACell input) {
		// First try to get from input parameters
		AString url = RT.ensureString(RT.getIn(input, Fields.SERVER));
		if (url != null) return url;

		// Then check metadata
		if (meta != null) {
			url = RT.ensureString(meta.get(Fields.SERVER));
			if (url != null) return url;
		}

		return null;
	}
	
	/**
	 * Get or create a persistent session for the given server URL and token.
	 */
	private McpClientSession getOrConnect(String serverUrl, String accessToken) {
		String key = serverUrl + "|" + (accessToken != null ? accessToken : "");
		return clientSessions.computeIfAbsent(key, k -> new McpClientSession(serverUrl, accessToken));
	}

	/**
	 * Connect to an MCP server via a base URL. Returns a connected client
	 * from the session pool.
	 * @param baseURL Server base URL
	 * @param accessToken Optional bearer token
	 * @return McpSyncClient instance (session-managed)
	 * @throws Exception on connection failure
	 */
	public McpSyncClient connect(String baseURL, String accessToken) throws Exception {
		return getOrConnect(baseURL, accessToken).getClient();
	}

	/**
	 * Makes an MCP tool call to the specified server using a persistent session.
	 * @param serverUrl MCP server URL
	 * @param toolName Tool name to call
	 * @param input Tool arguments
	 * @param accessToken Optional access token
	 */
	public ACell callMCPTool(AString serverUrl, String toolName, ACell input, String accessToken) throws Exception {
		McpClientSession session = getOrConnect(serverUrl.toString(), accessToken);
		try {
			McpSyncClient client = session.getClient();
			AMap<AString,ACell> toolArgs = RT.ensureMap(input);
			return makeToolCall(client, toolName, toolArgs);
		} catch (Exception e) {
			session.invalidate();
			throw e;
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
		// System.out.println("MCPAdapter response: "+response);
		
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
				schemaMap = schemaMap.assoc(Fields.TYPE, Strings.create(jsonSchema.type()));
			}

			// Add properties if present
			if (jsonSchema.properties() != null && !jsonSchema.properties().isEmpty()) {
				AMap<AString, ACell> propertiesMap = Maps.empty();
				for (Map.Entry<String, Object> entry : jsonSchema.properties().entrySet()) {
					ACell value = convertToConvex(entry.getValue());
					propertiesMap = propertiesMap.assoc(Strings.create(entry.getKey()), value);
				}
				schemaMap = schemaMap.assoc(Fields.PROPERTIES, propertiesMap);
			}

			// Add required fields if present
			if (jsonSchema.required() != null && !jsonSchema.required().isEmpty()) {
				AVector<AString> requiredVector = Vectors.empty();
				for (String required : jsonSchema.required()) {
					requiredVector = requiredVector.conj(Strings.create(required));
				}
				schemaMap = schemaMap.assoc(Fields.REQUIRED, requiredVector);
			}

			// Add additionalProperties if present
			if (jsonSchema.additionalProperties() != null) {
				schemaMap = schemaMap.assoc(Fields.ADDITIONAL_PROPERTIES, RT.cvm(jsonSchema.additionalProperties()));
			}

			// Add $defs if present
			if (jsonSchema.defs() != null && !jsonSchema.defs().isEmpty()) {
				AMap<AString, ACell> defsMap = Maps.empty();
				for (Map.Entry<String, Object> entry : jsonSchema.defs().entrySet()) {
					ACell value = convertToConvex(entry.getValue());
					defsMap = defsMap.assoc(Strings.create(entry.getKey()), value);
				}
				schemaMap = schemaMap.assoc(Fields.DEFS, defsMap);
			}

			// Add definitions if present (legacy field)
			if (jsonSchema.definitions() != null && !jsonSchema.definitions().isEmpty()) {
				AMap<AString, ACell> definitionsMap = Maps.empty();
				for (Map.Entry<String, Object> entry : jsonSchema.definitions().entrySet()) {
					ACell value = convertToConvex(entry.getValue());
					definitionsMap = definitionsMap.assoc(Strings.create(entry.getKey()), value);
				}
				schemaMap = schemaMap.assoc(Fields.DEFINITIONS, definitionsMap);
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
	 * Lists available MCP tools from the specified server using a persistent session.
	 * @param serverUrl The MCP server URL
	 * @param accessToken Optional access token for authentication
	 * @return ACell containing the list of tools
	 * @throws Exception if the operation fails
	 */
	public ACell listMCPTools(AString serverUrl, String accessToken) throws Exception {
		McpClientSession session = getOrConnect(serverUrl.toString(), accessToken);
		try {
			McpSyncClient client = session.getClient();
			ListToolsResult result = client.listTools();
			List<Tool> tools = result.tools();

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

			return Maps.of(
				"tools", toolsVector,
				Fields.TOTAL, AInteger.create(tools.size())
			);
		} catch (Exception e) {
			session.invalidate();
			throw e;
		}
	}

	/**
	 * Close all persistent client sessions. Should be called during shutdown.
	 */
	public void close() {
		for (McpClientSession session : clientSessions.values()) {
			session.close();
		}
		clientSessions.clear();
	}
	
}
