package covia.venue.api;

import java.io.InputStream;
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
import covia.venue.Venue;
import covia.venue.model.InvokeRequest;
import covia.venue.model.InvokeResult;
import covia.venue.server.SseServer;
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

public class CoviaAPI extends ACoviaAPI {

	private static final String ROUTE = "/api/v1/";

	public static final String INVOKE = "invokeOperation";

	public static final String GET_ASSET = "getAsset";

	public static final String ADD_ASSET="addAsset";

	private static final String GET_CONTENT = "getContent";

	private static final String PUT_CONTENT = "putContent";

	
	private final SseServer sseServer;
	
	public CoviaAPI(Venue venue) {
		super(venue);
		this.sseServer=new SseServer(venue);
	}

	public void addRoutes(Javalin javalin) {
		javalin.get(ROUTE+"status", this::getStatus);
		javalin.get(ROUTE+"assets/<id>", this::getAsset);
		javalin.get(ROUTE+"assets/<id>/content", this::getContent);
		javalin.put(ROUTE+"assets/<id>/content", this::putContent);
		javalin.get(ROUTE+"assets", this::getAssets);
		javalin.post(ROUTE+"assets", this::addAsset);
		javalin.post(ROUTE+"invoke", this::invokeOperation);
		javalin.get(ROUTE+"jobs/<id>", this::getJobStatus);
		javalin.sse(ROUTE+"jobs/<id>/sse", sseServer.registerSSE);
		javalin.get(ROUTE+"jobs", this::getJobs);
	}
	
	@OpenApi(path = ROUTE + "status", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a quick Covia status report", 
			operationId = "status")	
	protected void getStatus(Context ctx) { 
		jsonResult(ctx,"{\"status\":\"OK\"}");
	}

	
	private void jsonResult(Context ctx,List<?> jsonList) {
		jsonResult(ctx,JSONUtils.toString(jsonList));
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

		jsonResult(ctx,sb.toString());
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
	
	@OpenApi(path = ROUTE + "assets/{id}/content", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get the content of a Covia data asset", 
			operationId = CoviaAPI.GET_CONTENT,
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Content returned")
					})	
	protected void getContent(Context ctx) { 
		String id=ctx.pathParam("id");
		Hash assetID=Hash.parse(ctx.pathParam("id"));
		
		InputStream is=venue.getContentStream(assetID);
		if (is==null) {
			ctx.status(404);
			ctx.result("Asset not found: "+id);
			return;
		}

		ctx.result(is);
		ctx.status(200);
	}
	
	@OpenApi(path = ROUTE + "assets/{id}/content", 
			methods = HttpMethod.PUT, 
			tags = { "Covia"},
			summary = "Put the content of a Covia asset. This must match the content-hash stored in the asset.", 
			operationId = CoviaAPI.PUT_CONTENT,
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Content stored")
					})	
	protected void putContent(Context ctx) { 
		String idString=ctx.pathParam("id");
		Hash assetID=Hash.parse(idString);
		
		AMap<AString,ACell> meta=venue.getMetaValue(assetID);
		if (meta==null) {
			ctx.status(404);
			ctx.result("Asset not found: "+idString);		
			return;
		}
		
		

		ctx.status(200);
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

		jsonResult(ctx,jobs);
	}
}
