package covia.venue;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.html;
import static j2html.TagCreator.p;

import java.util.List;

import convex.api.ContentTypes;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import covia.venue.model.InvokeRequest;
import covia.venue.model.InvokeResult;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import j2html.tags.DomContent;

public class CoviaAPI extends ACoviaAPI {

	private static final String ROUTE = "/api/v1/";

	private final Venue venue;

	public static final String INVOKE = "invokeOperation";

	public static final String GET_ASSET = "getAsset";

	public static final String ADD_ASSET="addAsset";
	
	private final SseServer sseServer;
	
	public CoviaAPI(Venue venue) {
		this.venue=venue;
		this.sseServer=new SseServer(venue);
	}

	public void addRoutes(Javalin javalin) {
		javalin.get(ROUTE+"status", this::getStatus);
		javalin.get(ROUTE+"assets/<id>", this::getAsset);
		javalin.get(ROUTE+"assets", this::getAssets);
		javalin.post(ROUTE+"assets", this::addAsset);
		javalin.post(ROUTE+"invoke", this::invokeOperation);
		javalin.get(ROUTE+"jobs/<id>", this::getJobStatus);
		javalin.sse(ROUTE+"jobs/<id>/sse", sseServer.registerSSE);
		javalin.get(ROUTE+"jobs", this::getJobs);
		javalin.post("/mcp", this::postMCP);
		javalin.get("/.well-known/mcp", this::getMCPWellKnown);
	}
	
	@OpenApi(path = ROUTE + "status", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a quick Covia status report", 
			operationId = "status")	
	protected void getStatus(Context ctx) { 
		String type=ctx.header("Accept");
		if ((type!=null)&&type.contains("html")) {
			ctx.header("Content-Type", "text/html");	
			DomContent content= html(
				makeHeader("404: Not Found: "+ctx.path()),
				body(
					h1("404: not found: "+ctx.path()),
					p("This is not the page you are looking for."),
					a("Go back to index").withHref("/index.html")			
				)
			);
			ctx.result(content.render());
		} else {
			ctx.result("{\"status\":\"OK\"}");
			ctx.status(200);
			return;
		}
		ctx.status(404);
	}
	
	@OpenApi(path = ROUTE + "assets/{id}", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get Covia asset metadata gievn an asset ID.", 
			operationId = CoviaAPI.GET_ASSET,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Asset ID, equal to the SHA256 hash of the asset metadata.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") })	
	protected void getAsset(Context ctx) { 
		String id=ctx.pathParam("id");
		Hash assetID=Hash.parse(ctx.pathParam("id"));
		
		AString meta=venue.getMetadata(assetID);
		if (meta==null) {
			ctx.status(404);
			ctx.result("Asset not found: "+id);
			return;
		}

		ctx.result(meta.toString());
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "assets", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a list of Covia assets.", 
			operationId = CoviaAPI.GET_ASSET)
	protected void getAssets(Context ctx) { 
		long offset=-1;
		long limit=-1;
		try {
			String off = ctx.queryParam("offset");
			if (off!=null) offset=Long.parseLong(off);
			String lim = ctx.queryParam("limit");
			if (lim!=null) {
				limit=Long.parseLong(lim);
				if (limit<0) throw new BadRequestResponse("Negative limit");
			}
		} catch (NumberFormatException | NullPointerException e) {
			throw new BadRequestResponse("Invalid offset or limit");
		}

		AMap<ABlob, AVector<?>> allAssets = venue.getAssets();  
		long n=allAssets.count();
		long start=Math.max(0, offset);
		long end=(limit<0)?n:start+limit;
		if (end-start>1000) throw new BadRequestResponse("Too many assets requested: "+(end-start));
		StringBuilder sb=new StringBuilder();
		sb.append("[");
		for (long i=start; i<end; i++) {
			if (i>start) sb.append(",\n");
			sb.append('"');
			sb.append(allAssets.entryAt(i).getKey().toHexString());
			sb.append('"');
		}
		sb.append("]");

		ctx.result(sb.toString());
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "assets", 
			methods = HttpMethod.POST, 
			tags = { "Covia"},
			summary = "Add a Covia asset", 
			requestBody = @OpenApiRequestBody(
					description = "Asset metadata",
					content= @OpenApiContent(
							type = "application/json" ,
							from = Object.class
					)),
			operationId = CoviaAPI.ADD_ASSET,
			responses = {
					@OpenApiResponse(
							status = "201", 
							description = "Account metadata registered", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = String.class) })
					})	
	protected void addAsset(Context ctx) { 
		ACell body=null;
		
		String meta=ctx.body();
		try {
			Hash id=venue.storeAsset(meta, body);
			ctx.header("Content-type", ContentTypes.JSON);
			ctx.header("Location",ROUTE+"assets/"+id.toHexString());
			ctx.result("\""+id.toString()+"\"");
			
			ctx.status(201);
		} catch (ClassCastException | ParseException e) {
			throw new BadRequestResponse("Unable to parse asset metadata: "+e.getMessage());
		}
		
	}
	
	@OpenApi(path = ROUTE + "invoke", 
			methods = HttpMethod.POST, 
			tags = { "Covia"},
			summary = "Invoke a Covia operation", 
			requestBody = @OpenApiRequestBody(
					description = "Invoke request",
					content= @OpenApiContent(
							type = "application/json" ,
							from = InvokeRequest.class,
							exampleObjects = {
								@OpenApiExampleProperty(name = "operation", value = "random"),
								@OpenApiExampleProperty(name = "input", 
										objects = {
												@OpenApiExampleProperty(name = "length", value = "8")
										})
							}
					)),
			operationId = CoviaAPI.INVOKE,
			responses = {
					@OpenApiResponse(
							status = "201", 
							description = "Operation invoked", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = InvokeResult.class) })
					})	
	protected void invokeOperation(Context ctx) { 
		ACell req=JSONUtils.parseJSON5(ctx.body());
		
		AString op=RT.ensureString(RT.getIn(req, "operation"));
		if (op==null) {
			throw new BadRequestResponse("Invoke request requires an 'operation' parameter as a String");
		}
		ACell input=RT.getIn(req, "input");
		
		try {
			ACell invokeResult=venue.invokeOperation(op,input);
			if (invokeResult==null) {
				ctx.result("Operation does not exist");
				ctx.status(404);
				return;
			}
			
			ctx.header("Content-type", ContentTypes.JSON);
			ctx.header("Location",ROUTE+"jobs/"+op.toHexString());
			ctx.result(JSONUtils.toString(invokeResult));
			
			ctx.status(201);
		} catch (IllegalArgumentException e) {
			throw new BadRequestResponse(e.getMessage());
		}
	}
	
	@OpenApi(path = ROUTE + "jobs/{id}", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get the current Covia job status.", 
			operationId = CoviaAPI.GET_ASSET,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void getJobStatus(Context ctx) { 
		String pathID=ctx.pathParam("id");
		if (pathID==null) {
			throw new BadRequestResponse("Job request requires a job ID");
		}
		AString id=RT.ensureString(Strings.create(pathID));
		if (id==null) {
			throw new BadRequestResponse("Job request requires a job ID as a valid hex string");
		}
		
		ACell status=venue.getJobStatus(id);
		if (status==null) {
			ctx.status(404);
			ctx.result("Job not found: "+id);
			return;
		}

		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(JSONUtils.toString(status));
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "jobs", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get Covia jobs.")	
	protected void getJobs(Context ctx) { 
		
		List<AString> jobs = venue.getJobs();

		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result(JSONUtils.toString(jobs));
		ctx.status(200);
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
		ctx.result("{\"jsonrpc\": \"2.0\", \"result\": {\"status\": \"active\", \"version\": \"1.0.0\"}, \"id\": 1}");
		ctx.status(200);
	}
	
	@OpenApi(path = "/.well-known/mcp", 
			methods = HttpMethod.GET, 
			tags = { "MCP"},
			summary = "Get MCP server capabilities", 
			operationId = "mcpWellKnown")	
	protected void getMCPWellKnown(Context ctx) { 
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.result("""
				{	
					"mcp_version": "1.0",
					"server_url": "http:localhost:8080/mcp",
					"description": "MCP server for Covia Venue",
					"tools_endpoint": "http:localhost:8080/mcp",
					"auth": {
						"type": "oauth2",
						"authorization_endpoint": null
					}	
				}
		""");
		ctx.status(200);
	}
	


}
