package covia.venue.server;

import static j2html.TagCreator.a;
import static j2html.TagCreator.article;
import static j2html.TagCreator.aside;
import static j2html.TagCreator.body;
import static j2html.TagCreator.code;
import static j2html.TagCreator.div;
import static j2html.TagCreator.each;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.h4;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.join;
import static j2html.TagCreator.link;
import static j2html.TagCreator.meta;
import static j2html.TagCreator.p;
import static j2html.TagCreator.table;
import static j2html.TagCreator.tbody;
import static j2html.TagCreator.td;
import static j2html.TagCreator.th;
import static j2html.TagCreator.thead;
import static j2html.TagCreator.tr;
import static j2html.TagCreator.tag;
import static j2html.TagCreator.title;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import convex.core.util.Utils;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.api.CoviaAPI;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.DomContent;
import j2html.tags.Text;

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
		javalin.get("/adapters", this::adaptersPage);
		javalin.get("/adapters/{name}", this::adapterDetailPage);
		javalin.get("/llms.txt",this::llmsTxt);
		javalin.get("/sitemap.xml",this::siteMap);

	}
	
	private void indexPage(Context ctx) {
		standardPage(ctx,html(
				makeHeader("Covia AI: Decentralised AI grid"),
				body(
					h1("Covia AI: Decentralised AI grid"),
					aside(makeLinks()).withStyle("float: right"),
					p("Version: "+Utils.getVersion()),
					p("Name: "+engine.getConfig().get(Fields.NAME)),
					p(new Text("DID: "),code(engine.getDID().toString())),
					p(
						new Text("Registered adapters: "+engine.getAdapterNames().size()+" ("),
						a("view details").withHref("/adapters"),
						new Text(")")
					),

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
	
	public void adaptersPage(Context ctx) {
		standardPage(ctx,html(
				makeHeader("Covia Adapters"),
				body(
					h1("Registered Adapters"),
					p("The following adapters are currently registered in this Covia venue:"),
					buildAdaptersTable(),
					p(a("Back to index").withHref("/index.html"))
				)
			)
		);
	}
	
	public void adapterDetailPage(Context ctx) {
		String adapterName = ctx.pathParam("name");
		var adapter = engine.getAdapter(adapterName);
		
		if (adapter == null) {
			// Adapter not found, redirect to 404
			ctx.redirect("/404.html");
			return;
		}
		
		standardPage(ctx,html(
				makeHeader("Adapter: " + adapterName),
				body(
					h1("Adapter Details: " + adapterName),
					div(
						h4("Description"),
						p(adapter.getDescription())
					),
					div(
						h4("Basic Information"),
						table(
							tr(td("Name:"), td(adapterName)),
							tr(td("Class:"), td(adapter.getClass().getName())),
							tr(td("Status:"), td("Active"))
						).withClass("table")
					),
					div(
						h4("Navigation"),
						p(a("Back to all adapters").withHref("/adapters")),
						p(a("Back to index").withHref("/index.html"))
					)
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
		sa("Registered adapters","Adapters" ,"/adapters"),
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
						tag("lastmod").withText(new SimpleDateFormat("yyyy-MM-dd").format(new Date())),
						tag("priority").withText("1.0")
				),
				tag("url").with(
						tag("loc").withText(CoviaAPI.getExternalBaseUrl(ctx, "")+"/swagger"),
						tag("lastmod").withText(new SimpleDateFormat("yyyy-MM-dd").format(new Date())),
						tag("priority").withText("0.7")
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
	
	/**
	 * Builds an HTML table displaying all adapters in the current Engine
	 * @return DomContent containing the HTML table
	 */
	private DomContent buildAdaptersTable() {
		return table(
			thead(
				tr(
					th("Adapter Name"),
					th("Class"),
					th("Description"),
					th("Status")
				)
			),
			tbody(
				each(engine.getAdapterNames(), adapterName -> {
					var adapter = engine.getAdapter(adapterName);
					return tr(
						td(a(adapterName).withHref("/adapters/" + adapterName)),
						td(adapter.getClass().getSimpleName()),
						td(adapter.getDescription()),
						td("Active")
					);
				})
			)
		).withClass("table");
	}
}
