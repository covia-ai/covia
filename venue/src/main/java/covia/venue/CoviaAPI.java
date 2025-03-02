package covia.venue;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.html;
import static j2html.TagCreator.p;

import covia.api.impl.Ops;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import j2html.tags.DomContent;

public class CoviaAPI extends ACoviaAPI {

	private static final String ROUTE = "/api/v1/";


	@Override
	public void addRoutes(Javalin javalin) {
		javalin.get(ROUTE+"status", this::getStatus);

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
			ctx.result("404 Not found: "+ctx.path());
		}
		ctx.status(404);
	}
	
	@OpenApi(path = ROUTE + "asset", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a quick Covia status report", 
			operationId = Ops.ADD_ASSET)	
	protected void addAsset(Context ctx) { 
		
		ctx.result("Asset Added");
		ctx.status(200);
	}
}
