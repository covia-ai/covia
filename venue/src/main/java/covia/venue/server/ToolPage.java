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
                    
                    Layout.styledBox(
                        h4("MCP Tool Usage Examples"),
                        p("This tool can be called via the MCP (Model Context Protocol) endpoint. Here are examples of how to use it:"),
                        
                        div(
                            h5("JSON-RPC Call Example:"),
                            pre(code("{\n" +
                                "  \"jsonrpc\": \"2.0\",\n" +
                                "  \"id\": 1,\n" +
                                "  \"method\": \"tools/call\",\n" +
                                "  \"params\": {\n" +
                                "    \"name\": \"" + (toolName != null ? toolName.toString() : name.toString()) + "\",\n" +
                                "    \"arguments\": {\n" +
                                "      \"input\": \"your input here\"\n" +
                                "    }\n" +
                                "  }\n" +
                                "}"))
                        ),
                        
                        div(
                            h5("cURL Example:"),
                            pre(code("curl -X POST http://localhost:8080/mcp \\\\\n" +
                                "  -H \"Content-Type: application/json\" \\\\\n" +
                                "  -d '{\n" +
                                "    \"jsonrpc\": \"2.0\",\n" +
                                "    \"id\": 1,\n" +
                                "    \"method\": \"tools/call\",\n" +
                                "    \"params\": {\n" +
                                "      \"name\": \"" + (toolName != null ? toolName.toString() : name.toString()) + "\",\n" +
                                "      \"arguments\": {\n" +
                                "        \"input\": \"your input here\"\n" +
                                "      }\n" +
                                "    }\n" +
                                "  }'"))
                        ),
                        
                        div(
                            h5("Python Example:"),
                            pre(code("import requests\n" +
                                "import json\n\n" +
                                "url = \"http://localhost:8080/mcp\"\n" +
                                "payload = {\n" +
                                "    \"jsonrpc\": \"2.0\",\n" +
                                "    \"id\": 1,\n" +
                                "    \"method\": \"tools/call\",\n" +
                                "    \"params\": {\n" +
                                "        \"name\": \"" + (toolName != null ? toolName.toString() : name.toString()) + "\",\n" +
                                "        \"arguments\": {\n" +
                                "            \"input\": \"your input here\"\n" +
                                "        }\n" +
                                "    }\n" +
                                "}\n\n" +
                                "response = requests.post(url, json=payload)\n" +
                                "result = response.json()\n" +
                                "print(result)"))
                        ),
                        
                        div(
                            h5("JavaScript/Node.js Example:"),
                            pre(code("const fetch = require('node-fetch');\n\n" +
                                "const url = 'http://localhost:8080/mcp';\n" +
                                "const payload = {\n" +
                                "    jsonrpc: '2.0',\n" +
                                "    id: 1,\n" +
                                "    method: 'tools/call',\n" +
                                "    params: {\n" +
                                "        name: '" + (toolName != null ? toolName.toString() : name.toString()) + "',\n" +
                                "        arguments: {\n" +
                                "            input: 'your input here'\n" +
                                "        }\n" +
                                "    }\n" +
                                "};\n\n" +
                                "fetch(url, {\n" +
                                "    method: 'POST',\n" +
                                "    headers: {\n" +
                                "        'Content-Type': 'application/json'\n" +
                                "    },\n" +
                                "    body: JSON.stringify(payload)\n" +
                                "})\n" +
                                ".then(response => response.json())\n" +
                                ".then(data => console.log(data));"))
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
