package covia.venue.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(ToolPage.class);

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
        // --- 404 cases: input doesn't identify a viewable tool page ---
        Hash hash;
        try {
            hash = Hash.parse(assetHash);
        } catch (IllegalArgumentException e) {
            // Wrong-length blob etc. — treat the same as unparseable.
            hash = null;
        }
        if (hash == null) {
            notFound(ctx, "Malformed asset hash: " + assetHash);
            return;
        }

        AString metaString = engine.getMetadata(hash);
        if (metaString == null) {
            notFound(ctx, "No asset for hash " + assetHash);
            return;
        }

        // --- From here, the asset exists. Anything inconsistent below is a 500. ---
        // Unexpected runtime failures (JSON.parse throwing, NPEs in rendering, etc.)
        // propagate to the global app.exception handler in VenueServer, which logs
        // a stack trace and returns 500. We only translate the cases we can name.

        AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
        if (meta == null) {
            throw new IllegalStateException(
                "Stored metadata for asset " + assetHash + " is not a JSON map");
        }

        AMap<AString, ACell> operation = RT.ensureMap(meta.get(Fields.OPERATION));
        if (operation == null) {
            // Asset exists but isn't an operation — no tool page applies.
            notFound(ctx, "Asset is not an operation: " + assetHash);
            return;
        }

        // Required: ADAPTER. Missing or unregistered = broken asset/config drift.
        AString opAdapter = RT.ensureString(operation.get(Fields.ADAPTER));
        if (opAdapter == null) {
            throw new IllegalStateException(
                "Operation " + assetHash + " has no '" + Fields.ADAPTER + "' field");
        }
        String adapterPrefix = opAdapter.toString().split(":")[0];
        AAdapter adapter = engine.getAdapter(adapterPrefix);
        if (adapter == null) {
            throw new IllegalStateException(
                "Operation " + assetHash + " references unregistered adapter: " + adapterPrefix);
        }
        String adapterName = adapter.getName();

        // Cosmetic: tolerate missing name/description/toolName with fallbacks.
        AString toolName = RT.ensureString(operation.get(Fields.TOOL_NAME));
        AString nameField = RT.ensureString(meta.get(Fields.NAME));
        AString descField = RT.ensureString(meta.get(Fields.DESCRIPTION));
        String displayName = (toolName != null) ? toolName.toString()
                           : (nameField != null) ? nameField.toString()
                           : assetHash;
        String assetName = (nameField != null) ? nameField.toString() : "(unnamed)";
        String description = (descField != null) ? descField.toString() : "";
        String toolNameStr = (toolName != null) ? toolName.toString() : displayName;

        String mcpURL = CoviaAPI.getExternalBaseUrl(ctx, "/mcp");

        // Generate the page
        standardPage(ctx, html(
            Layout.makeHeader("MCP Tool: " + displayName),
            body(
                h1("Tool Details: " + displayName),

                Layout.styledBox(
                    h4("Basic Information"),
                    table(
                        tr(td("Tool Name:"), td(toolName != null ? toolName.toString() : "N/A")),
                        tr(td("Asset Name:"), td(assetName)),
                        tr(td("Description:"), td(description)),
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
                                "    \"name\": \"" + toolNameStr + "\",\n" +
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
                                "      \"name\": \"" + toolNameStr + "\",\n" +
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
                                "        \"name\": \"" + toolNameStr + "\",\n" +
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
                                "        name: '" + toolNameStr + "',\n" +
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
                    p(a("Back to adapter details").withHref("/adapters/" + adapterName)),
                    p(a("Back to all adapters").withHref("/adapters")),
                    p(a("Back to index").withHref("/index.html"))
                )
            )
        ));
    }

    /**
     * Render a 404 directly on the current response (no redirect).
     * Plain-text body keeps the original URL in the address bar and avoids
     * the second-roundtrip the redirect-to-/404.html pattern incurs.
     */
    private void notFound(Context ctx, String reason) {
        log.debug("Tool detail 404 on {}: {}", ctx.path(), reason);
        ctx.status(404);
        ctx.contentType("text/plain");
        ctx.result("404 Not Found: " + reason);
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
        AMap<AString, ACell> properties = RT.ensureMap(schema.get(Fields.PROPERTIES));
        if (properties == null || properties.isEmpty()) {
            // If no properties, show the schema type
            AString type = RT.ensureString(schema.get(Fields.TYPE));
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
                    
                    AString type = RT.ensureString(fieldSchema.get(Fields.TYPE));
                    AString description = RT.ensureString(fieldSchema.get(Fields.DESCRIPTION));

                    // Check if field is required
                    AVector<AString> required = RT.ensureVector(schema.get(Fields.REQUIRED));
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
