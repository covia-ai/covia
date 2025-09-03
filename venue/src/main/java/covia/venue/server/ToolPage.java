package covia.venue.server;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import io.javalin.http.Context;
import j2html.tags.DomContent;

import static j2html.TagCreator.*;

/**
 * Generates individual tool detail pages for MCP tools
 */
public class ToolPage {
    
    private final Engine engine;
    
    public ToolPage(Engine engine) {
        this.engine = engine;
    }
    
    /**
     * Generates a tool detail page for a specific asset hash
     * @param ctx The Javalin context
     * @param assetHash The hash of the asset to display
     */
    public void toolDetailPage(Context ctx, String assetHash) {
        try {
            // Parse the asset hash
            Hash hash = Hash.parse(assetHash);
            
            // Get the asset metadata
            AString metaString = engine.getMetadata(hash);
            if (metaString == null) {
                ctx.redirect("/404.html");
                return;
            }
            
            // Parse the metadata
            AMap<AString, ACell> meta = RT.ensureMap(convex.core.util.JSONUtils.parse(metaString));
            
            // Get the operation information
            AMap<AString, ACell> operation = RT.ensureMap(meta.get(Fields.OPERATION));
            if (operation == null) {
                ctx.redirect("/404.html");
                return;
            }
            
            // Extract tool information
            AString toolName = RT.ensureString(operation.get(Fields.MCP_TOOLNAME));
            AString adapterName = RT.ensureString(operation.get(Strings.create("adapter")));
            AString description = RT.ensureString(meta.get(Fields.DESCRIPTION));
            AString name = RT.ensureString(meta.get(Fields.NAME));
            
            // Get the adapter
            AAdapter adapter = engine.getAdapter(adapterName.toString());
            
            // Generate the page
            standardPage(ctx, html(
                Layout.makeHeader("Tool: " + (toolName != null ? toolName.toString() : name.toString())),
                body(
                    h1("Tool Details: " + (toolName != null ? toolName.toString() : name.toString())),
                    
                    Layout.styledBox(
                        h4("Basic Information"),
                        table(
                            tr(td("Tool Name:"), td(toolName != null ? toolName.toString() : "N/A")),
                            tr(td("Asset Name:"), td(name.toString())),
                            tr(td("Description:"), td(description.toString())),
                            tr(td("Adapter:"), td(
                                adapter != null ? 
                                a(adapterName.toString()).withHref("/adapters/" + adapterName.toString()) :
                                span(adapterName.toString())
                            )),
                            tr(td("Asset Hash:"), td(code(assetHash)))
                        )
                    ),
                    
                    div(
                        h4("Operation Details"),
                        table(
                            tr(td("Adapter Operation:"), td(adapterName.toString())),
                            tr(td("Input Schema:"), td(pre(code(JSONUtils.toJSONPretty(operation).toString()))))
                        )
                    ),
                    
                    div(
                        h4("Asset Metadata"),
                        pre(code(metaString.toString()))
                    ),
                    
                    div(
                        h4("Navigation"),
                        p(a("Back to adapter details").withHref("/adapters/" + adapterName.toString())),
                        p(a("Back to all adapters").withHref("/adapters")),
                        p(a("Back to index").withHref("/index.html"))
                    )
                )
            ));
            
        } catch (Exception e) {
            // If there's an error, redirect to 404
            ctx.redirect("/404.html");
        }
    }
    

    
    /**
     * Renders a standard page
     * @param ctx The Javalin context
     * @param content The page content
     */
    private void standardPage(Context ctx, DomContent content) {
        ctx.result(content.render());
        ctx.header("Content-Type", "text/html");
        ctx.status(200);
    }
}
