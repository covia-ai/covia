package covia.venue.api;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.server.SseServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * This class implements an MCP server on top of a Covia Venue, as an additional API
 */
public class MCP extends ACoviaAPI {
	
	public static final Logger log=LoggerFactory.getLogger(MCP.class);
	
	final AMap<AString,ACell> SERVER_INFO;

	protected final SseServer sseServer;
	
	protected final AString  SERVER_URL_FIELD=Strings.intern("server_url");


	private boolean LOG_MCP=false;

	
	public MCP(Engine engine, AMap<AString, ACell> mcpConfig) {
		super(engine);
		this.sseServer=new SseServer(engine);
		// See: https://zazencodes.com/blog/mcp-server-naming-conventions
		AMap<AString,ACell> serverInfo = RT.getIn(mcpConfig, "serverInfo");
		
		if (serverInfo==null) serverInfo=Maps.of(
			"name", "covia-grid-mcp",
			"title", engine.getName(),
			"version", Utils.getVersion()
		);
		SERVER_INFO=serverInfo;
	}

	
	public void addRoutes(Javalin javalin) {
//		if (LOG_MCP) {
//			javalin.before("/mcp", ctx->{
//				System.out.println("MCP request: "+ctx.headerMap());
//			});
//		};
		
		javalin.post("/mcp", this::postMCP);
		javalin.get("/mcp", this::getMCP);
		javalin.get("/.well-known/mcp", this::getMCPWellKnown);
	} 
	
	@OpenApi(path = "/mcp", 
			methods = HttpMethod.POST, 
			tags = { "MCP"},
			summary = "Handle MCP JSON-RPC requests", 
			requestBody = @OpenApiRequestBody(
					description = "JSON-RPC request",
					content= @OpenApiContent(
							type = "application/json" ,
							from = Object.class,
							exampleObjects = {
								@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
								@OpenApiExampleProperty(name = "method", value = "initialize"),
								@OpenApiExampleProperty(name = "params", value="{}"),
								@OpenApiExampleProperty(name = "id", value = "1")
							}
					)),
			operationId = "mcpServer",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "JSON-RPC response", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = Object.class,
										exampleObjects = {
											@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
											@OpenApiExampleProperty(name = "result", value="{}"),
											@OpenApiExampleProperty(name = "id", value = "1")
										}
										) })
					})	
	protected void postMCP(Context ctx) { 
		ctx.header("Content-type", ContentTypes.JSON);
		
		try {
			// Parse JSON-RPC request. Might throw ParseException
			ACell req=JSONReader.read(ctx.bodyInputStream());
			if (LOG_MCP) {
				System.out.println("REQ:"+req);
			}

			if (req instanceof AMap) {
				// Simple JSON response
				AMap<AString, ACell> resp = createResponse(req);
				buildResult(ctx,resp);
			} else if (req instanceof AVector requests){
				// Batch response
				@SuppressWarnings("unchecked")
				List<AMap<AString, ACell>> responses= Utils.map(requests, this::createResponse);
				buildResult(ctx,responses);
			} else {
				buildResult(ctx,protocolError(-32600,"Request must be single request object or batch array"));
			}
		} catch (ParseException | ClassCastException | NullPointerException | IOException e) {
			buildResult(ctx,protocolError(-32600,"Invalid JSON request"));
		} 
	}

	private AMap<AString, ACell> createResponse(ACell request) {
		ACell id=RT.getIn(request,Fields.ID);
		AMap<AString, ACell> response;
		try {
			AString methodAS=(AString) RT.getIn(request,Fields.METHOD);
			String method=methodAS.toString().trim();
			
			if (method.equals("tools/list")) {
				response=listTools();
			} else if (method.equals("tools/call")) {
				response=toolCall(RT.getIn(request, Fields.PARAMS));
			} else if (method.equals("initialize")) {
				response=protocolResult(Maps.of(
						"protocolVersion", "2025-03-26",
						"capabilities",Maps.of("tools",Maps.empty()),
						"serverInfo",SERVER_INFO
				));
			} else if (method.equals("notifications/initialized")) {
				response=protocolResult(Maps.of());
			} else if (method.equals("ping")) {
				response=protocolResult(Maps.empty());
			} else {
				response=protocolError(-32601,"Method not found: "+method);
			}
		} catch (Exception e) {
			response= protocolError(-32600,"Invalid request for ID "+id);
		}
		
		// Finally restore ID if needed
		if (id!=null) {
			response=response.assoc(Fields.ID, id);
		}
		if (LOG_MCP) {
			System.out.println("RES:"+response);
		}
		return response;
	}
	
	/**
	 * Function to execute a tool call request received from a remote client
	 * @param methodAS
	 * @param in
	 * @return
	 */
	private AMap<AString, ACell> toolCall(AMap<AString,ACell> params) {
		try {
			AString toolName=RT.getIn(params, Fields.NAME);
			Hash opID=findTool(toolName);
			ACell arguments=RT.getIn(params, Fields.ARGUMENTS);
			if (opID!=null) {
					Job job=engine.invokeOperation(opID, arguments);
					ACell result=job.awaitResult();
					return protocolResult(Maps.of(
								Fields.CONTENT,Vectors.of(Maps.of(Fields.TYPE,Fields.TEXT,Fields.TEXT,JSON.toAString(result))),
								Fields.STRUCTURED_CONTENT,result
							));
			} else {
				return protocolError(-32602, "Unknown tool: "+toolName);
			}
		} catch (Exception e) {
			return protocolToolError(e.getMessage());
		}
	}


	private Hash findTool(AString methodAS) {
		// Iterate through all registered adapters
		for (String adapterName : engine.getAdapterNames()) {
			try {
				var adapter = engine.getAdapter(adapterName);
				if (adapter == null) continue;
				
				// Get tools from this specific adapter
				Index<Hash, AString> adapterTools = adapter.getInstalledAssets();
				long n=adapterTools.count();
				for (long i=0; i<n; i++) {
					Hash h=adapterTools.entryAt(i).getKey();
					AMap<AString,ACell> meta=engine.getMetaValue(h);
					if (methodAS.equals(RT.getIn(meta, Fields.OPERATION, Fields.TOOL_NAME))) {
						return h;
					}
				}
				
			} catch (Exception e) {
				log.warn("Error processing adapter " + adapterName, e);
				// ignore this adapter
			}
		}
		return null; // not found
	}


	/**
	 * Construct a 'successful' JSON-RPC result response
	 * @param result Result value
	 * @return
	 */
	private AMap<AString, ACell> protocolResult(AHashMap<ACell, ACell> result) {
		return BASE_RESPONSE.assoc(Fields.RESULT, result);
	}
	
	/**
	 * Construct a 'successful' JSON-RPC tool result response
	 * @param result Result value
	 * @return
	 */
	private AMap<AString, ACell> protocolToolError(String message) {
		return BASE_RESPONSE.assoc(
				Fields.RESULT, Maps.of(
						Fields.IS_ERROR,true,
						Fields.CONTENT, Vectors.of(Maps.of(
								Fields.TYPE,Fields.TEXT,
								Fields.TEXT,message
								))
					));
	}

	private static final AMap<AString, ACell> BASE_RESPONSE=Maps.of("jsonrpc", "2.0");
	
	/**
	 * Construct a JSON-RPC error response
	 * @param rpcErrorCode JSON-RPC error code, see: https://www.jsonrpc.org/specification#error_object
	 * @param errorMEssage Error message string
	 * @return
	 */
	private AMap<AString, ACell> protocolError(int rpcErrorCode, String errorMessage) {
		return BASE_RESPONSE.assoc(Fields.ERROR, Maps.of(
				"code",rpcErrorCode,
				"message",errorMessage
		));
	}

	private AMap<AString, ACell> listTools() {
		AVector<AMap<AString,ACell>> toolsVector=Vectors.empty();
		
		// Iterate through all registered adapters
		for (String adapterName : engine.getAdapterNames()) {
			try {
				var adapter = engine.getAdapter(adapterName);
				if (adapter == null) continue;
				
				// Get tools from this specific adapter
				AVector<AMap<AString,ACell>> adapterTools = listTools(adapter);
				toolsVector = toolsVector.concat(adapterTools);
			} catch (Exception e) {
				log.warn("Error processing adapter " + adapterName, e);
				// ignore this adapter
			}
		}
		
		return protocolResult(Maps.of("tools",toolsVector));
	}
	
	/**
	 * Get MCP tools from a specific adapter's installed assets
	 * @param adapter The adapter to get tools from
	 * @return Vector of MCP tools provided by this adapter
	 */
	public AVector<AMap<AString,ACell>> listTools(AAdapter adapter) {
		AVector<AMap<AString,ACell>> toolsVector = Vectors.empty();
		
		try {
			// Get installed assets for this adapter
			Index<Hash, AString> installedAssets = adapter.getInstalledAssets();
			int n = installedAssets.size();
			
			for (int i = 0; i < n; i++) {
				try {
					MapEntry<Hash, AString> me = installedAssets.entryAt(i);
					AString metaString = me.getValue();
					
					// Parse the metadata string to get the structured metadata
					AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
					AMap<AString, ACell> mcpTool = checkTool(meta);
					if (mcpTool != null) {
						toolsVector = toolsVector.conj(mcpTool);
					}
				} catch (Exception e) {
					log.warn("Error processing asset from adapter " + adapter.getName(), e);
					// ignore this asset
				}
			}
		} catch (Exception e) {
			log.warn("Error getting installed assets from adapter " + adapter.getName(), e);
		}
		
		return toolsVector;
	}
	


	private AMap<AString,ACell> checkTool(AMap<AString, ACell> meta) {
		AMap<AString,ACell> op=RT.getIn(meta,Fields.OPERATION);
		if (op==null) return null;
		AString toolName=RT.ensureString(op.get(Fields.TOOL_NAME));
		
		if (toolName==null) return null;
		
		AMap<AString,ACell> result= Maps.of(
				Fields.NAME,toolName,
				Fields.TITLE,RT.getIn(meta,Fields.NAME),
				Fields.DESCRIPTION,RT.getIn(meta,Fields.DESCRIPTION),
				Fields.INPUT_SCHEMA,RT.getIn(op, Fields.INPUT)
		);		
		
		
		
		return result;
	}

	@OpenApi(path = "/mcp", 
			methods = HttpMethod.GET, 
			tags = { "MCP"},
			summary = "Get MCP SSE Stream", 
			operationId = "mcpWellKnown")	
	protected void getMCP(Context ctx) { 
		ctx.status(405); // not allowed. MUST return this if SSE not supported
	}
	
	@SuppressWarnings("unchecked")
	private AMap<AString,ACell> WELL_KNOWN=(AMap<AString, ACell>) JSON.parse("""
			{	
			"mcp_version": "1.0",
			"server_url": "http:localhost:8080/mcp",
			"description": "MCP server for Covia Venue",
			"tools_endpoint": "/mcp",
			"endpoint": {"path":"/mcp","transport":"streamable-http"},
			"auth": {
				"type": "oauth2",
				"authorization_endpoint": null
			}	
		}
""");
	
	@OpenApi(path = "/.well-known/mcp", 
			methods = HttpMethod.GET, 
			tags = { "MCP"},
			summary = "Get MCP server capabilities", 
			operationId = "mcpWellKnown")	
	protected void getMCPWellKnown(Context ctx) { 
		AMap<AString,ACell> result=WELL_KNOWN;
		AString mcpURL=Strings.create(getExternalBaseUrl(ctx, "mcp"));
		result=result.assoc(SERVER_URL_FIELD,mcpURL);
		buildResult(ctx,result);
	}

}
