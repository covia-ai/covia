package covia.venue.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.api.model.ErrorResponse;
import covia.venue.api.model.InvokeRequest;
import covia.venue.api.model.InvokeResult;
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
	
	public static final Logger log=LoggerFactory.getLogger(CoviaAPI.class);

	private static final String ROUTE = "/api/v1/";

	public static final String INVOKE = "invokeOperation";

	public static final String GET_ASSET = "getAsset";
	public static final String GET_ASSETS = "getAssets";

	public static final String ADD_ASSET="addAsset";

	private static final String GET_CONTENT = "getContent";

	private static final String PUT_CONTENT = "putContent";
	public static final String CANCEL_JOB = "cancelJob";	
	public static final String DELETE_JOB = "deleteJob";

	private static final String GET_JOB = "getJob";

	public static final AString SERVICE_TYPE = Strings.intern("Covia.API.v1");	
	public static final AString STATS_FIELD = Strings.intern("stats");	
	
	private final SseServer sseServer;
	
	public CoviaAPI(Engine venue) {
		super(venue);
		this.sseServer=new SseServer(venue);
	}

	public void addRoutes(Javalin javalin) {
		javalin.get(ROUTE+"status", this::getStatus);
		javalin.get(ROUTE+"assets/{id}", this::getAsset); // note {} doesn't match slashes, <> does
		javalin.get(ROUTE+"assets/{id}/content", this::getContent);
		javalin.put(ROUTE+"assets/{id}/content", this::putContent);

		javalin.get(ROUTE+"assets", this::getAssets);
		javalin.post(ROUTE+"assets", this::addAsset);
		javalin.post(ROUTE+"invoke", this::invokeOperation);
		javalin.get(ROUTE+"jobs/<id>", this::getJobStatus);
		javalin.put(ROUTE+"jobs/<id>/cancel", this::cancelJob);
		javalin.put(ROUTE+"jobs/<id>/delete", this::deleteJob);
		javalin.sse(ROUTE+"jobs/<id>/sse", sseServer.registerSSE);
		javalin.get(ROUTE+"jobs", this::getJobs);
		
		// DIDs
		javalin.get("/.well-known/did.json", this::getDIDDocument);
		javalin.get("/a/{id}/did.json", this::getAssetDIDDocument);
	}
	 
	@OpenApi(path = ROUTE + "status", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a quick Covia status report", 
			operationId = "status")	
	protected void getStatus(Context ctx) { 
		AMap<AString,ACell> result=engine.getStatus();
		
		// Add the external base URL
		result=result.assoc(Fields.URL,RT.cvm(getExternalBaseUrl(ctx,null)));

		// Add the external base URL
		result=result.assoc(STATS_FIELD,engine.getStats());

		
		buildResult(ctx,200,result);
	}


	
	@OpenApi(path = ROUTE + "assets", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a list of Covia assets.", 
			operationId = CoviaAPI.GET_ASSETS,
			queryParams = {
		            @OpenApiParam(
		                name = "offset",
		                type = Long.class,
		                description = "The starting index of the assets to retrieve (0-based). Defaults to 0 if not specified.",
		                required = false,
		                example = "0"
		            ),
		            @OpenApiParam(
		                name = "limit",
		                type = Long.class,
		                description = "The maximum number of assets to return. Must be non-negative and not exceed 1000. Defaults to all remaining assets if not specified.",
		                required = false,
		                example = "100"
		            )
		        },
		        responses = {
		            @OpenApiResponse(
		                status = "200",
		                description = "A JSON array of asset IDs as hexadecimal strings.",
		                content = {
		                    @OpenApiContent(
		                        type = "application/json",
		                        from = String[].class
		                    )
		                }
		            ),
		            @OpenApiResponse(
		                status = "400",
		                description = "Bad request due to invalid offset, negative limit, or too many assets requested (exceeding 1000).",
		                content = {
		                    @OpenApiContent(
		                        type = "application/json",
		                        from = ErrorResponse.class
		                    )
		                }
		            )
		        })
	protected void getAssets(Context ctx) { 
		long offset=-1;
		long limit=-1;
		Map<Object,Object> result=new HashMap<>();

		try {
			String off = ctx.queryParam("offset");
			if (off!=null) offset=Long.parseLong(off);
			String lim = ctx.queryParam("limit");
			if (lim!=null) {
				limit=Long.parseLong(lim);
				if (limit<0) throw new BadRequestResponse("Negative limit");
			}
		} catch (NumberFormatException | NullPointerException e) {
			buildError(ctx,400,"Invalid offset or limit");
			return;
		}

		
		
		AMap<ABlob, AVector<?>> allAssets = engine.getAssets();  
		long n=allAssets.count();
		result.put(Fields.TOTAL, n);
		
		long start=Math.max(0, offset);
		long end=(limit<0)?n:start+limit;
		if (end-start>1000) throw new BadRequestResponse("Too many assets requested: "+(end-start));
		result.put(Fields.OFFSET, start);
		result.put(Fields.LIMIT, end-start);
		ArrayList<Object> assetsList=new ArrayList<>();
		end=Math.min(end, n);
		for (long i=start; i<end; i++) {
			AString s=(allAssets.entryAt(i).getKey().toCVMHexString());
			assetsList.add(s);
		}
		result.put(Fields.ITEMS, assetsList);
		
		buildResult(ctx,result);
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
		try {
			AString meta=Strings.fromStream(ctx.bodyInputStream());
			Hash id=engine.storeAsset(meta,null);
			buildResult(ctx,201,id.toHexString());
			ctx.header("Location",ROUTE+"assets/"+id.toHexString());
		} catch (ClassCastException | IOException | ParseException e) {
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
							description = "Asset ID, as a hex string.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") })	
	protected void getAsset(Context ctx) { 
		String id=ctx.pathParam("id");
		Hash assetID=Hash.parse(id);
		if (assetID==null) throw new BadRequestResponse("Invalid asset ID: " + id);
		
		AString meta=engine.getMetadata(assetID);
		if (meta==null) {
			buildError(ctx,404,"Asset not found: "+id);
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
			queryParams = {
					@OpenApiParam(
							name = "inline", 
							description = "Add if the Content-Disposition should be set to inline", 
							required = false, 
							type = String.class, 
							example = "true")
			},
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Asset ID, as a hex string.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") },
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Content returned")
					})	
	protected void getContent(Context ctx) { 
		String id=ctx.pathParam("id");
		Hash assetID=Hash.parse(ctx.pathParam("id"));
		
		AMap<AString,ACell> meta=engine.getMetaValue(assetID);
		if (meta==null) {
			buildError(ctx,404,"Asset not found: "+assetID);		
			return;
		}
		
		if (!meta.containsKey(Fields.CONTENT)) {
			buildError(ctx,404,"Asset metadata does not specify any content object: "+id);
			return;
		}
		
		try {
			ACell contentMeta=meta.get(Fields.CONTENT);
			InputStream is = engine.getContentStream(meta);
			if (is==null) {
				buildError(ctx,404,"Asset did not have any content available: "+id);
				return;
			}
			ACell contentType=RT.getIn(contentMeta,Fields.CONTENT_TYPE);
			if (contentType instanceof AString ct) {
				ctx.contentType(ct.toString());
			} 
			if (ctx.queryParam("inline")!=null) {
				ctx.header("Content-Disposition","inline");
			}
			
			ACell fileName=RT.getIn(contentMeta,Fields.FILE_NAME); 
			if (fileName instanceof AString ct) {
				ctx.header("filename",ct.toString());
			} 


			ctx.result(is);
			ctx.status(200);
		} catch (IOException e) {
			ctx.status(500);
		}
	}
	
	@OpenApi(path = ROUTE + "assets/{id}/content", 
			methods = HttpMethod.PUT, 
			tags = { "Covia"},
			summary = "Put the content of a Covia asset. This must match the content-hash stored in the asset.", 
			operationId = CoviaAPI.PUT_CONTENT,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Asset ID, as a hex string.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") },
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Content stored")
					})	
	protected void putContent(Context ctx) { 
		String idString=ctx.pathParam("id");
		Hash assetID=Hash.parse(idString);
		
		AMap<AString,ACell> meta=engine.getMetaValue(assetID);
		if (meta==null) {
			buildError(ctx,404,"Asset not found: "+idString);		
			return;
		}
		
		if (!meta.containsKey(Fields.CONTENT)) {
			buildError(ctx,404,"Asset metadata does not specifiy any content object: "+assetID);
			return;
		}
		
		try {
			InputStream is=ctx.bodyInputStream();
			Hash contentHash= engine.putContent(meta,is);
			buildResult(ctx,200,contentHash);
			
		} catch (IllegalArgumentException e) {
			this.buildError(ctx, 400, "Cannot PUT asset content: "+e.getMessage());
		} catch (IOException | OutOfMemoryError e) {
			this.buildError(ctx, 500,"Storage error trying to PUT asset content: "+e.getMessage());
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
							description = "Operation invoked, with a job status record returned. Job ID can be any string, but by convention 32 characters hex.", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = InvokeResult.class) })
					})	
	protected void invokeOperation(Context ctx) { 
		ACell req=JSON.parseJSON5(ctx.body());
		
		AString op=RT.ensureString(RT.getIn(req, "operation"));
		if (op==null) {
			this.buildError(ctx, 400, "Invoke request requires an 'operation' parameter as a String");
			return;
		}
		ACell input=RT.getIn(req, "input");
		
		try {
			Job job=engine.invokeOperation(op,input);
			if (job==null) {
				buildError(ctx,404,"Operation does not exist");
				return;
			}
			
			this.buildResult(ctx, 201, job.getData());
			ctx.header("Location",ROUTE+"jobs/"+op.toHexString());
		} catch (IllegalArgumentException | IllegalStateException e) {
			this.buildError(ctx, 400, "Error invoking operation: "+e.getClass().getSimpleName()+":"+e.getMessage());
			e.printStackTrace();
			return;
		} catch (Exception e) {
			this.buildError(ctx, 500, "Unexpected failure invoking operation: "+e);
			log.warn("Unexpected exception handling client invoke",e);
		}
	}
	
	@OpenApi(path = ROUTE + "jobs/{id}", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get the current Covia job status.", 
			operationId = CoviaAPI.GET_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void getJobStatus(Context ctx) { 
		AString id=RT.ensureString(Strings.create(ctx.pathParam("id")));
		if (id==null) {
			buildError(ctx,400,"Job request requires a job ID as a valid hex string");
			return;
		}
		
		ACell status=engine.getJobData(id);
		if (status==null) {
			buildError(ctx,404,"Job not found: "+id);
			return;
		}

		buildResult(ctx,200,status);
	}
	
	@OpenApi(path = ROUTE + "jobs/{id}/cancel", 
			methods = HttpMethod.PUT, 
			tags = { "Covia"},
			summary = "Cancels a job.", 
			operationId = CoviaAPI.CANCEL_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void cancelJob(Context ctx) { 
		AString id=RT.ensureString(Strings.create(ctx.pathParam("id")));
		if (id==null) {
			buildError(ctx,400,"Job cancellation request requires a job ID as a valid hex string");
			return;
		}
		
		AMap<AString, ACell> status = engine.cancelJob(id);
		ctx.status((status==null)?404:200);
	}
	
	@OpenApi(path = ROUTE + "jobs/{id}/delete", 
			methods = HttpMethod.PUT, 
			tags = { "Covia"},
			summary = "Cancels a job.", 
			operationId = CoviaAPI.DELETE_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void deleteJob(Context ctx) { 
		AString id=RT.ensureString(Strings.create(ctx.pathParam("id")));
		if (id==null) {
			buildError(ctx,400,"Job cancellation request requires a job ID as a valid hex string");
			return;
		}
		
		boolean deleted=engine.deleteJob(id);
		ctx.status(deleted?200:404);
	}
	
	@OpenApi(path = ROUTE + "jobs", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get Covia jobs.")	
	protected void getJobs(Context ctx) { 
		List<AString> jobs = engine.getJobs();
		buildResult(ctx,jobs);
	}

	@OpenApi(path = "/.well-known/did.json", 
			methods = HttpMethod.GET, 
			tags = { "DID"},
			summary = "Get the DID document for this venue", 
			operationId = "getDIDDocument",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "DID document returned")
					})	
	protected void getDIDDocument(Context ctx) { 
		// Create a complete DID document structure
		AMap<AString, ACell> didDocument = engine.getDIDDocument(getExternalBaseUrl(ctx,ROUTE));
		
		// Set content type to application/did+json
		ctx.header("Content-Type", "application/did+json");
		
		// Return the DID document
		buildResult(ctx, 200, didDocument);
	}
	
	@OpenApi(path = "/a/{id}/did.json", 
			methods = HttpMethod.GET, 
			tags = { "DID"},
			summary = "Get the DID document for an asset", 
			operationId = "getAssetDIDDocument",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "DID document returned")
					})	
	protected void getAssetDIDDocument(Context ctx) { 
		String id=ctx.pathParam("id");
	
		AString baseDID=engine.getDIDString();
		AString did=baseDID.append(Strings.create("/a/"+id));
		
		AMap<AString, ACell> didDocument = Maps.of(
				"@context", "https://www.w3.org/ns/did/v1",
				Fields.ID,did);

		// Set content type to application/did+json
		ctx.header("Content-Type", "application/did+json");
		
		// Return the DID document
		buildResult(ctx, 200, didDocument);
	}
}
