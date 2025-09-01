package covia.venue.server;

import static j2html.TagCreator.*;

import java.util.List;

import j2html.tags.Text;
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
					aside(makeLinks()).withStyle("float: right"),
					p("Version: "+Utils.getVersion()),
					p("Name: "+engine.getConfig().get(Fields.NAME)),
					p(new Text("DID: "),code(engine.getDID().toString())),

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
	
	static final List<String[]> LINKS = List.of(
		sa("OpenAPI documentation: ","Swagger API" ,"/swagger"),
		sa("Covia Documentation: ","Covia Docs" ,"https://docs.covia.ai"),
		sa("Convex Documentation: ","Convex Docs" ,"https://docs.convex.world"),
		sa("General information at the ","Covia Website", "https://covia.ai"),
		sa("Community chat at the ","Covia Discord", "https://discord.com/invite/xfYGq4CT7v"),
		sa("Join the open source development: ","Covia Dev", "https://github.com/covia-ai")
	);
	
	private DomContent makeLinks() {
		return article(
			h4("Useful links: "),
			each(LINKS,a->{
				return div(join(a[0],a(a[1]).withHref(a[2])));
			})
		); //.withClass("grid");
	}

	// Silly helper function
	private static String[] sa(String... strings) {
		return strings;
	}
}
