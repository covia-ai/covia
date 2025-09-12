package covia.venue.server;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.api.CoviaAPI;
import io.javalin.http.Context;
import j2html.tags.DomContent;
import j2html.tags.Text;

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
            AMap<AString, ACell> meta = RT.ensureMap(convex.core.util.JSON.parse(metaString));
            
            // Get the operation information
            AMap<AString, ACell> operation = RT.ensureMap(meta.get(Fields.OPERATION));
            if (operation == null) {
                ctx.redirect("/404.html");
                return;
            }
            
            // Extract tool information
            AString toolName = RT.ensureString(operation.get(Fields.TOOL_NAME));
            AString opAdapter = RT.ensureString(operation.get(Strings.create("adapter")));
            AString description = RT.ensureString(meta.get(Fields.DESCRIPTION));
            AString name = RT.ensureString(meta.get(Fields.NAME));
            
            // Get the adapter
            AAdapter adapter = engine.getAdapter(opAdapter.toString().split(":")[0]);
            String adapterName=adapter.getName();
            
            String mcpURL=CoviaAPI.getExternalBaseUrl(ctx, "/mcp");
            
            // Generate the page
            standardPage(ctx, html(
                Layout.makeHeader("MCP Tool: " + (toolName != null ? toolName.toString() : name.toString())),
                body(
                    h1("Tool Details: " + (toolName != null ? toolName.toString() : name.toString())),
                    
                    Layout.styledBox(
                        h4("Basic Information"),
                        table(
                            tr(td("Tool Name:"), td(toolName != null ? toolName.toString() : "N/A")),
                            tr(td("Asset Name:"), td(name.toString())),
                            tr(td("Description:"), td(description.toString())),
                            tr(td("Adapter:"), td(
                                a(adapterName).withHref("/adapters/" + adapterName)
                            )),
                            tr(td("Asset Hash:"), td(code(assetHash)))
                        )
                    ),
                    
                    Layout.styledBox(
                        div(
                            div(
                                h5("Input"),
                                schemaTable(RT.ensureMap(operation.get(Fields.INPUT)))
                            ).withStyle("width: 48%; float: left; margin-right: 2%;"),
                            div(
                                h5("Output"),
                                schemaTable(RT.ensureMap(operation.get(Fields.OUTPUT)))
                            ).withStyle("width: 48%; float: left; margin-left: 2%;"),
                            div().withStyle("clear: both;") // Clearfix
                        )
                    ),
                    
                    Layout.styledBox(
                        h4("MCP Tool Usage Examples"),
                        p("This tool can be called via the MCP (Model Context Protocol) endpoint. Here are examples of how to use it:"),
                        
                        div(
                            h5("JSON-RPC Call Example:"),
                            p(code("POST"),text(" to "),code(mcpURL)),
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
                            pre(code("curl -X POST "+mcpURL+" \\\\\n" +
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
                                "url = \""+mcpURL+"\"\n" +
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
                                "const url = '"+mcpURL+"';\n" +
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
                    
                    Layout.styledBox(
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
        	System.err.println(e);
            ctx.redirect("/404.html");
        }
    }
    
    /**
     * Creates a table from a JSON schema showing field types and descriptions
     * @param schema The schema map to parse
     * @return DomContent containing the schema table
     */
    private DomContent schemaTable(AMap<AString, ACell> schema) {
        if (schema == null || schema.isEmpty()) {
            return div(p(i("No schema specified")));
        }
        
        // Check if it's a JSON Schema with properties
        AMap<AString, ACell> properties = RT.ensureMap(schema.get(Strings.create("properties")));
        if (properties == null || properties.isEmpty()) {
            // If no properties, show the schema type
            AString type = RT.ensureString(schema.get(Strings.create("type")));
            return div(
                p("Type: " + (type != null ? type.toString() : "object")),
                p("Schema: " + code(JSON.printPretty(schema).toString()))
            );
        }
        
        // Build table for properties
        return table(
            thead(
                tr(
                    th("Property"),
                    th("Type"),
                    th("Description")
                )
            ),
            tbody(
                each(properties, entry -> {
                    AString fieldName = entry.getKey();
                    AMap<AString, ACell> fieldSchema = RT.ensureMap(entry.getValue());
                    
                    AString type = RT.ensureString(fieldSchema.get(Strings.create("type")));
                    AString description = RT.ensureString(fieldSchema.get(Strings.create("description")));
                    
                    // Check if field is required
                    AVector<AString> required = RT.ensureVector(schema.get(Strings.create("required")));
                    boolean isRequired = required != null && required.contains(fieldName);
                    
                    return tr(
                        td(code(fieldName.toString()),isRequired?new Text("*"):null),
                        td(type != null ? type.toString() : "any"),
                        td(description != null ? description.toString() : "-")
                    );
                })
            )
        ).withClass("table");
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
