package covia.venue;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.html;
import static j2html.TagCreator.p;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import covia.api.impl.Ops;
import covia.venue.model.InvokeRequest;
import covia.venue.model.InvokeResult;
import io.javalin.Javalin;
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
	
	public CoviaAPI(Venue venue) {
		this.venue=venue;
	}

	@Override
	public void addRoutes(Javalin javalin) {
		javalin.get(ROUTE+"status", this::getStatus);
		javalin.get(ROUTE+"assets/<id>", this::getAsset);
		javalin.post(ROUTE+"assets", this::addAsset);
		javalin.post(ROUTE+"invoke", this::invokeOperation);

	}
	
	@OpenApi(path = ROUTE + "status", 
			versions="covia-v1",
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
			ctx.result("404 Not found: "+ctx.path());
		}
		ctx.status(404);
	}
	
	@OpenApi(path = ROUTE + "assets/{id}", 
			versions="covia-v1",
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get Covia asset metadata", 
			operationId = Ops.GET_ASSET,
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
			versions="covia-v1",
			methods = HttpMethod.POST, 
			tags = { "Covia"},
			summary = "Add a Covia asset", 
			requestBody = @OpenApiRequestBody(
					description = "Asset metadata",
					content= @OpenApiContent(
							type = "application/json" ,
							from = Object.class
					)),
			operationId = Ops.ADD_ASSET,
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
		AString meta=Strings.create(ctx.body());
		Hash id=venue.storeAsset(meta, body);
		
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.header("Location",ROUTE+"assets/"+id.toHexString());
		ctx.result("\""+id.toString()+"\"");
		
		ctx.status(201);
	}
	
	@OpenApi(path = ROUTE + "invoke", 
			versions="covia-v1",
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
								@OpenApiExampleProperty(name = "inputs", 
										objects = {
												@OpenApiExampleProperty(name = "length", value = "8")
										})
							}
					)),
			operationId = Ops.INVOKE,
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
		ACell body=null;
		AString meta=Strings.create(ctx.body());
		Hash id=venue.storeAsset(meta, body);
		
		ctx.header("Content-type", ContentTypes.JSON);
		ctx.header("Location",ROUTE+"jobs/"+id.toHexString());
		ctx.result("\""+id.toString()+"\"");
		
		ctx.status(201);
	}
}
