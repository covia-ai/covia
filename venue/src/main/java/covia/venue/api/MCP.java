package covia.venue.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import covia.api.Fields;
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
	
	public MCP(Engine venue) {
		super(venue);
		this.sseServer=new SseServer(venue);
		// See: https://zazencodes.com/blog/mcp-server-naming-conventions
		SERVER_INFO=Maps.of(
			"name", "covia-grid-mcp",
			"title", venue.getName(),
			"version", Utils.getVersion()
		);
	}

	private boolean LOG_MCP=true;
	
	public void addRoutes(Javalin javalin) {
		if (LOG_MCP) {
			javalin.before("/mcp", ctx->{
				System.out.println("MCP request: "+ctx.headerMap());
			});
		};
		
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
								@OpenApiExampleProperty(name = "params", value = "{}"),
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
										from = Object.class) })
					})	
	protected void postMCP(Context ctx) { 
		if (LOG_MCP) {
			System.out.println(ctx);
		}
		ctx.header("Content-type", ContentTypes.JSON);
		
		try {
			// Parse JSON-RPC request
			String requestBody = ctx.body();
			if (requestBody == null || requestBody.isEmpty()) {
				ctx.status(200);
				ctx.result("{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32700, \"message\": \"Invalid Request\"}, \"id\": null}");
				return;
			}
			
			AMap<AString,ACell> req=RT.ensureMap( JSONUtils.parse(requestBody));
			
			ACell id=req.getIn(Fields.ID);
			AString methodAS=(AString) req.getIn(Fields.METHOD);
			String method=methodAS.toString().trim();
			
			AMap<AString,ACell> resp;
			if (method.equals("tools/list")) {
				resp=listTools();
			} else if (method.equals("tools/call")) {
				resp=listTools();
			} else if (method.equals("initialize")) {
				resp=protocolResult(Maps.of(
						"protocolVersion", "2025-03-26",
						"capabilities",Maps.of("tools",Maps.empty()),
						"serverInfo",SERVER_INFO
				));
			} else if (method.equals("ping")) {
				resp=protocolResult(Maps.empty());
			} else {
				resp=protocolError(-32601,"Method not found");
			}

			if (id!=null) {
				resp=resp.assoc(Fields.ID, id);
			}

			// For now, return a simple JSON response
			buildResult(ctx,resp);
		} catch (ClassCastException | NullPointerException e) {
			buildResult(ctx,protocolError(-32600,"Invalid JSON request"));
		} 
	}
	
	private AMap<AString, ACell> protocolResult(AHashMap<ACell, ACell> result) {
		return BASE_RESPONSE.assoc(Fields.RESULT, result);
	}

	private static final AMap<AString, ACell> BASE_RESPONSE=Maps.of("jsonrpc", "2.0");
	
	private AMap<AString, ACell> protocolError(int rpcErrorCode, String errorMessage) {
		return BASE_RESPONSE.assoc(Fields.ERROR, Maps.of(
				"code",rpcErrorCode,
				"message",errorMessage
		));
	}

	private AMap<AString, ACell> listTools() {
		AVector<AMap<AString,ACell>> toolsVector=Vectors.empty();
		
		AMap<ABlob, AVector<?>> assets = venue.getAssets();
		int n=assets.size();
		for (int i=0; i<n; i++) try {
			MapEntry<ABlob, AVector<?>> me=assets.entryAt(i);
			@SuppressWarnings("unchecked")
			AMap<AString,ACell> meta=(AMap<AString,ACell>)me.getValue().get(Engine.POS_META);
			AMap<AString,ACell> mcpTool=checkTool(meta);
			if (mcpTool!=null) {
				toolsVector=toolsVector.conj(mcpTool);
			}
		} catch (Exception e) {
			log.warn("Error in asset entry",e);
			// ignore this one.
		}
		
		return Maps.of(
				"jsonrpc", "2.0",
				"result",Maps.of(
					"tools",toolsVector
				)
		);
	}

	private AMap<AString,ACell> checkTool(AMap<AString, ACell> meta) {
		AMap<AString,ACell> op=RT.getIn(meta,Fields.OPERATION);
		if (op==null) return null;
		AString toolName=RT.ensureString(op.get(Fields.MCP_TOOLNAME));
		
		if (toolName==null) return null;
		
		AMap<AString,ACell> result= Maps.of(
				Fields.NAME,toolName,
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
	
	@OpenApi(path = "/.well-known/mcp", 
			methods = HttpMethod.GET, 
			tags = { "MCP"},
			summary = "Get MCP server capabilities", 
			operationId = "mcpWellKnown")	
	protected void getMCPWellKnown(Context ctx) { 
		buildResult(ctx,JSONReader.read("""
				{	
					"mcp_version": "1.0",
					"server_url": "http:localhost:8080/mcp",
					"description": "MCP server for Covia Venue",
					"tools_endpoint": "/mcp",
					"endpoint": {"path":"/map","transport":"streamable-http"},
					"auth": {
						"type": "oauth2",
						"authorization_endpoint": null
					}	
				}
		"""));
	}

}
