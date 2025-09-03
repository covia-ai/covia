package covia.venue.server;

import static j2html.TagCreator.a;
import static j2html.TagCreator.article;
import static j2html.TagCreator.body;
import static j2html.TagCreator.code;
import static j2html.TagCreator.div;
import static j2html.TagCreator.each;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.h4;
import static j2html.TagCreator.h5;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.join;
import static j2html.TagCreator.li;
import static j2html.TagCreator.link;
import static j2html.TagCreator.meta;
import static j2html.TagCreator.p;
import static j2html.TagCreator.pre;
import static j2html.TagCreator.table;
import static j2html.TagCreator.tbody;
import static j2html.TagCreator.td;
import static j2html.TagCreator.th;
import static j2html.TagCreator.thead;
import static j2html.TagCreator.tr;
import static j2html.TagCreator.ul;
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
		javalin.get("/config", this::configPage);
		javalin.get("/adapters", this::adaptersPage);
		javalin.get("/adapters/{name}", this::adapterDetailPage);
		javalin.get("/llms.txt",this::llmsTxt);
		javalin.get("/sitemap.xml",this::siteMap);

	}
	
	private void indexPage(Context ctx) {
		String BASE_URL=CoviaAPI.getExternalBaseUrl(ctx, "");
		standardPage(ctx,html(
				makeHeader("Covia AI: Decentralised AI grid"),
				body(
					h1("Covia AI: Decentralised AI grid"),
					div(
						div(
							// Main content area (70% width)
							styledBox(div(
								h4("Venue Overview"),
								p("Version: "+Utils.getVersion()),
								p("Name: "+engine.getConfig().get(Fields.NAME)),
								p(new Text("DID: "),code(engine.getDID().toString())),
								p(
									new Text("Registered adapters: "+engine.getAdapterNames().size()+" ("),
									a("view details").withHref("/adapters"),
									new Text(")")
								)
							)),
							
							// Show MCP information only if MCP is configured
							buildMCPContent(BASE_URL),

							p("Covia Venue server - summary page (generated "+new Date()+")")
						).withStyle("width: 70%; float: left; padding-right: 1rem;"),
						
						div(
							// Links sidebar (30% width)
							makeLinks()
						).withStyle("width: 30%; float: right;"),
						
						div().withStyle("clear: both;") // Clearfix
					)
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
	
	public void configPage(Context ctx) {
		standardPage(ctx,html(
				makeHeader("Engine Configuration"),
				body(
					h1("Engine Configuration"),
					p("Current configuration for this Covia venue engine:"),
					div(
						h4("Configuration Details"),
						pre(code(engine.getConfig().toString())).withStyle("background-color: #f5f5f5; padding: 1rem; border-radius: 4px; overflow-x: auto;")
					),
					div(
						h4("Navigation"),
						p(a("Back to index").withHref("/index.html")),
						p(a("View adapters").withHref("/adapters"))
					)
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
	
	static final List<String[]> BASE_LINKS = List.of(
		sa("OpenAPI documentation","Swagger API" ,"/swagger"),
		sa("Registered adapters","Adapters" ,"/adapters"),
		sa("Engine configuration","Config" ,"/config"),
		sa("Covia Documentation","Covia Docs" ,"https://docs.covia.ai"),
		sa("Convex Documentation","Convex Docs" ,"https://docs.convex.world"),
		sa("General information","Covia Website", "https://covia.ai"),
		sa("Community chat","Covia Discord", "https://discord.gg/fywdrKd8QT"),
		sa("Open source development","Covia Dev", "https://github.com/covia-ai")
	);

	private DomContent makeLinks() {
		// Build links list conditionally including MCP if available
		List<String[]> links = new java.util.ArrayList<>(BASE_LINKS);
		
		return article(
			h4("Useful links: "),
			each(links,a->{
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
		// Build links list conditionally including MCP if available
		List<String[]> links = new java.util.ArrayList<>(BASE_LINKS);

		
		for (String[] ss: links) {
			sb.append("- ["+ss[1]+"]("+ss[2]+"): "+ss[0]+"\n");
			
		}
		
		
		ctx.result(sb.toString());
		ctx.contentType("text/plain");
		ctx.status(200);
	}
	
	/**
	 * Creates a styled box component with basic inline styling
	 * @param content The content to wrap in the styled box
	 * @return DomContent containing the styled box
	 */
	private DomContent styledBox(DomContent content) {
		return div(content).withStyle(
			"border: 1px solid #ddd; " +
			"border-radius: 8px; " +
			"padding: 1.5rem; " +
			"margin: 1rem 0; " +
			"background-color: #091929; " +
			"box-shadow: 0 2px 4px rgba(0,0,0,0.1);"
		);
	}
	
	/**
	 * Builds MCP server information content if MCP is configured
	 * @param baseUrl The base URL for the server
	 * @return DomContent containing MCP information or empty div if not configured
	 */
	private DomContent buildMCPContent(String baseUrl) {
		if (engine.getConfig().get(Fields.MCP) == null) {
			return styledBox(div(p("MCP not available")));
		}
		
		return styledBox(div(
			h4("MCP Server Information"),
			p("This venue provides Model Context Protocol (MCP) server capabilities for AI agent integration."),
			div(
				h5("Available MCP Endpoints:"),
				ul(
					li(new Text("MCP JSON-RPC Endpoint: "), code(baseUrl + "/mcp")),
					li(new Text("MCP Server Discovery: "), code(baseUrl + "/.well-known/mcp"))
				)
			),
			div(
				h5("MCP Integration:"),
				p("You can connect AI agents to this grid venue using the Model Context Protocol. The MCP server exposes operations from registered adapters as tools that can be called by compatible AI systems."),
				p(
					new Text("For more information about MCP, visit: "),
					a("Model Context Protocol").withHref("https://modelcontextprotocol.io").withTarget("_blank")
				)
			)
		));
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
