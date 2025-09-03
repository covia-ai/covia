package covia.venue.server;

import static j2html.TagCreator.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import j2html.tags.Text;
import j2html.tags.specialized.*;
import convex.core.util.Utils;
import covia.venue.Engine;
import covia.venue.api.CoviaAPI;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.ContainerTag;
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
		javalin.get("/llms.txt",this::llmsTxt);
		javalin.get("/sitemap.xml",this::siteMap);

	}
	
	private void indexPage(Context ctx) {
		standardPage(ctx,html(
				makeHeader("Covia AI: Decentralised AI grid venue server"),
				body(
					h1("Covia AI: Decentralised AI grid"),
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
				meta().attr("description","Covia.ai offers decentralized AI solutions powered by Convex Lattice DLT for secure, scalable applications."),
				meta().attr("keywords","AI, agent, grid, orchestration, decentralised"),
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
		sa("OpenAPI documentation","Swagger API" ,"/swagger"),
		sa("Covia Documentation","Covia Docs" ,"https://docs.covia.ai"),
		sa("Convex Documentation","Convex Docs" ,"https://docs.convex.world"),
		sa("General information","Covia Website", "https://covia.ai"),
		sa("Community chat","Covia Discord", "https://discord.gg/fywdrKd8QT"),
		sa("Open source development","Covia Dev", "https://github.com/covia-ai")
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
	
	protected void siteMap(Context ctx) { 
		DomContent content= tag("urlset").with(
				tag("url").with(
						tag("loc").withText(CoviaAPI.getExternalBaseUrl(ctx, "")+"/"),
						tag("lastmod").withText(new SimpleDateFormat("yyyy-MM-dd").format(new Date()))
				)
		).attr("xmlns", "http://www.sitemaps.org/schemas/sitemap/0.9");
				
		ctx.contentType("application/xml");
		ctx.result("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+content.render());
		ctx.status(200);
	}
	
	protected void llmsTxt(Context ctx) { 
		StringBuilder sb=new StringBuilder();
		sb.append("# Covia Grid Venue Server\n");
		sb.append("\n");
		sb.append("> Covia Grid venues provide pluggable access to AI agents and orchestration capabilities.\n");
		sb.append("\n");
		sb.append("Name : "+engine.getName()+"\n");
		sb.append("Web Address : "+CoviaAPI.getExternalBaseUrl(ctx, "")+"\n");
		sb.append("\n");
		sb.append("## Links\n");
		sb.append("\n");
		for (String[] ss: LINKS) {
			sb.append("- ["+ss[1]+"]("+ss[2]+"): "+ss[0]+"\n");
			
		}
		
		
		ctx.result(sb.toString());
		ctx.contentType("text/plain");
		ctx.status(200);
	}
}
