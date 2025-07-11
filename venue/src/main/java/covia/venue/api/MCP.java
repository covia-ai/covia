package covia.venue.api;

import convex.api.ContentTypes;
import covia.venue.Venue;
import covia.venue.server.SseServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

public class MCP extends ACoviaAPI {

	protected final SseServer sseServer;
	
	public MCP(Venue venue) {
		super(venue);
		this.sseServer=new SseServer(venue);
	}

	public void addRoutes(Javalin javalin) {
		javalin.post("/mcp", this::postMCP);
		javalin.get("/.well-known/mcp", this::getMCPWellKnown);
		javalin.sse("/mcp/sse", sseServer.registerSSE);
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
								@OpenApiExampleProperty(name = "method", value = "getStatus"),
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
		ctx.header("Content-type", ContentTypes.JSON);
		
		// Parse JSON-RPC request
		String requestBody = ctx.body();
		if (requestBody == null || requestBody.isEmpty()) {
			ctx.status(400);
			ctx.result("{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32600, \"message\": \"Invalid Request\"}, \"id\": null}");
			return;
		}
		
		// For now, return a simple response
		buildResult(ctx,"{\"jsonrpc\": \"2.0\", \"result\": {\"status\": \"active\", \"version\": \"1.0.0\"}, \"id\": 1}");
	}
	
	@OpenApi(path = "/.well-known/mcp", 
			methods = HttpMethod.GET, 
			tags = { "MCP"},
			summary = "Get MCP server capabilities", 
			operationId = "mcpWellKnown")	
	protected void getMCPWellKnown(Context ctx) { 
		buildResult(ctx,"""
				{	
					"mcp_version": "1.0",
					"server_url": "http:localhost:8080/mcp",
					"description": "MCP server for Covia Venue",
					"tools_endpoint": "/mcp",
					"endpoint": {"path":"/map","transport":"streamable-http"}
					"auth": {
						"type": "oauth2",
						"authorization_endpoint": null
					}	
				}
		""");
	}
	


}
