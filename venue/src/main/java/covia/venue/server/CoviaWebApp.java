package covia.venue.server;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.link;
import static j2html.TagCreator.p;
import static j2html.TagCreator.title;

import convex.core.util.Utils;
import covia.venue.Engine;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.DomContent;
import covia.api.Fields;

public class CoviaWebApp  {

	protected Engine engine;

	public CoviaWebApp(Engine engine) {
		this.engine=engine;
	}

	public void addRoutes(Javalin javalin) {
		javalin.get("/index.html", this::indexPage);
		javalin.get("/", this::indexPage);
		javalin.get("/404.html", this::missingPage);
		javalin.get("/status", this::statusPage);
	}
	
	private void indexPage(Context ctx) {
		standardPage(ctx,html(
				makeHeader("Covia Venue Server"),
				body(
					h1("Covia Venue Server"),
					p("Version: "+Utils.getVersion()),
					p("Name: "+engine.getConfig().get(Fields.NAME)),
					p(a("Swagger API").withHref("swagger")),

					p("This is the default web page for a Covia Venue server running the REST API")
				)
			)
		);
	}
	
	protected void missingPage(Context ctx) { 
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
	
	private DomContent makeHeader(String title) {
		return head(
				title(title),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}

	public void statusPage(Context ctx) {
		standardPage(ctx,html(
				makeHeader("Covia Status"),
				body(
					h1("Covia Venue Status"),
					p("Version: "+Utils.getVersion()),
					p("This is a covia status page. All looks OK.")
				)
			)
		);
	}

	private void standardPage(Context ctx,DomContent content) {
		ctx.result(content.render());
		ctx.header("Content-Type", "text/html");
		ctx.status(200);
	}
}
