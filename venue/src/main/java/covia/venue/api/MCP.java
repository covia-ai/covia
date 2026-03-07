package covia.venue.api;

import static convex.restapi.mcp.McpProtocol.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpSession;
import convex.restapi.mcp.SseConnection;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.SseServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * MCP server on top of a Covia Venue, supporting Streamable HTTP transport
 * with SSE notifications.
 *
 * <p>Uses shared MCP infrastructure from {@code convex-restapi} for protocol
 * handling, sessions, and SSE.</p>
 */
public class MCP extends ACoviaAPI {

	public static final Logger log = LoggerFactory.getLogger(MCP.class);

	final AMap<AString, ACell> SERVER_INFO;

	protected final SseServer sseServer;

	protected final AString SERVER_URL_FIELD = Strings.intern("server_url");

	private boolean LOG_MCP = false;

	/** Active MCP sessions, keyed by session ID */
	private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();

	/** ThreadLocal to make the current Javalin Context available during tool handling */
	static final ThreadLocal<Context> currentContext = new ThreadLocal<>();

	public MCP(Venue venue, AMap<AString, ACell> mcpConfig) {
		super(venue);
		this.sseServer = new SseServer(engine());
		// See: https://zazencodes.com/blog/mcp-server-naming-conventions
		AMap<AString, ACell> serverInfo = RT.getIn(mcpConfig, "serverInfo");

		if (serverInfo == null) serverInfo = Maps.of(
			"name", "covia-grid-mcp",
			"title", engine().getName(),
			"version", Utils.getVersion()
		);
		SERVER_INFO = serverInfo;

		// Register MCP SSE job notification broadcaster
		engine().jobs().addJobUpdateListener(this::broadcastJobNotification);
	}

	/**
	 * Broadcast a job update as an MCP SSE notification to all sessions.
	 */
	private void broadcastJobNotification(Job job) {
		if (sessions.isEmpty()) return;
		Blob jobId = job.getID();
		if (jobId == null) return;

		AMap<AString, ACell> notification = Maps.of(
			"jsonrpc", "2.0",
			"method", "notifications/jobUpdate",
			"params", Maps.of(
				"jobId", jobId,
				"status", job.getStatus()
			)
		);

		String data = JSON.print(notification).toString();
		for (McpSession session : sessions.values()) {
			for (SseConnection conn : session.sseConnections) {
				try {
					conn.sendEvent("message", data);
				} catch (Exception e) {
					log.debug("Failed to send MCP SSE notification", e);
				}
			}
		}
	}


	public void addRoutes(Javalin javalin) {
		javalin.post("/mcp", this::postMCP);
		javalin.get("/mcp", this::handleMcpGet);
		javalin.delete("/mcp", this::handleMcpDelete);
		javalin.get("/.well-known/mcp", this::getMCPWellKnown);
	}

	// ===== POST /mcp — JSON-RPC requests =====

	@OpenApi(path = "/mcp",
			methods = HttpMethod.POST,
			tags = { "MCP"},
			summary = "Handle MCP JSON-RPC requests",
			requestBody = @OpenApiRequestBody(
					description = "JSON-RPC request",
					content= @OpenApiContent(
							type = "application/json" ,
							from = Object.class,
							exampleObjects = {
								@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
								@OpenApiExampleProperty(name = "method", value = "initialize"),
								@OpenApiExampleProperty(name = "params", value="{}"),
								@OpenApiExampleProperty(name = "id", value = "1")
							}
					)),
			operationId = "mcpServer",
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "JSON-RPC response",
							content = {
								@OpenApiContent(
										type = "application/json",
										from = Object.class,
										exampleObjects = {
											@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
											@OpenApiExampleProperty(name = "result", value="{}"),
											@OpenApiExampleProperty(name = "id", value = "1")
										}
										) })
					})
	protected void postMCP(Context ctx) {
		currentContext.set(ctx);
		try {
			boolean useSSE = acceptsEventStream(ctx);
			ACell req = JSONReader.read(ctx.bodyInputStream());
			if (LOG_MCP) {
				System.out.println("REQ:" + req);
			}

			AString callerDID = AuthMiddleware.getCallerDID(ctx);

			if (req instanceof AMap<?, ?> map) {
				if (isNotification(map)) {
					processNotification(map);
					ctx.status(202).contentType(ContentTypes.JSON);
					return;
				}

				AMap<AString, ACell> resp = createResponse(req, callerDID);

				// Create session on successful initialize
				String method = getMethodName(map);
				if ("initialize".equals(method) && resp.containsKey(FIELD_RESULT)) {
					McpSession session = new McpSession(UUID.randomUUID().toString());
					sessions.put(session.id, session);
					ctx.header(HEADER_SESSION_ID, session.id);
				}

				sendResponse(ctx, resp, useSSE);
			} else if (req instanceof AVector<?> requests) {
				long n = requests.count();
				if (n == 0) {
					sendResponse(ctx, protocolError(-32600, "Invalid batch request (empty)"), useSSE);
					return;
				}
				AVector<AMap<AString, ACell>> responses = Vectors.empty();
				for (long i = 0; i < n; i++) {
					ACell entry = requests.get(i);
					if (entry instanceof AMap<?, ?> batchMap) {
						if (isNotification(batchMap)) {
							processNotification(batchMap);
						} else {
							responses = responses.conj(createResponse(entry, callerDID));
						}
					} else {
						responses = responses.conj(protocolError(-32600, "Invalid Request"));
					}
				}
				if (responses.isEmpty()) {
					ctx.status(202).contentType(ContentTypes.JSON);
				} else if (useSSE) {
					sendSseBatchResponse(ctx, responses);
				} else {
					buildResult(ctx, responses);
				}
			} else {
				sendResponse(ctx, protocolError(-32600, "Request must be single request object or batch array"), useSSE);
			}
		} catch (ParseException | ClassCastException | NullPointerException | IOException e) {
			ctx.contentType(ContentTypes.JSON);
			buildResult(ctx, protocolError(-32600, "Invalid JSON request"));
		} catch (Exception e) {
			log.warn("Unexpected error handling MCP request", e);
			ctx.contentType(ContentTypes.JSON);
			buildResult(ctx, protocolError(-32603, "Internal error"));
		} finally {
			currentContext.remove();
		}
	}

	/**
	 * Process a notification message (no response expected).
	 */
	private void processNotification(AMap<?, ?> request) {
		String method = getMethodName(request);
		if (method == null) return;
		switch (method) {
			case "notifications/initialized", "notifications/cancelled" -> { /* acknowledged */ }
			default -> log.debug("Unrecognised MCP notification: {}", method);
		}
	}

	private AMap<AString, ACell> createResponse(ACell request, AString callerDID) {
		ACell id = RT.getIn(request, Fields.ID);
		AMap<AString, ACell> response;
		try {
			AString methodAS = (AString) RT.getIn(request, Fields.METHOD);
			String method = methodAS.toString().trim();

			ACell params = RT.getIn(request, Fields.PARAMS);

			response = switch (method) {
				case "initialize" -> protocolResult(Maps.of(
					"protocolVersion", negotiateProtocolVersion(params),
					"capabilities", Maps.of("tools", Maps.empty()),
					"serverInfo", SERVER_INFO
				));
				case "tools/list" -> listToolsResult();
				case "tools/call" -> toolCall(RT.getIn(request, Fields.PARAMS), callerDID);
				case "notifications/initialized", "ping" -> protocolResult(Maps.empty());
				default -> protocolError(-32601, "Method not found: " + method);
			};
		} catch (Exception e) {
			response = protocolError(-32600, "Invalid request for ID " + id);
		}

		return maybeAttachId(response, id);
	}

	private static final String LATEST_PROTOCOL_VERSION = "2025-06-18";

	/**
	 * Negotiate MCP protocol version. Returns the minimum of the client's requested
	 * version and the server's latest supported version, so older clients get a
	 * version they understand.
	 */
	private static String negotiateProtocolVersion(ACell params) {
		ACell clientVersion = RT.getIn(params, "protocolVersion");
		if (clientVersion instanceof AString cv) {
			String requested = cv.toString();
			if (requested.compareTo(LATEST_PROTOCOL_VERSION) < 0) {
				return requested;
			}
		}
		return LATEST_PROTOCOL_VERSION;
	}

	/**
	 * Send a JSON-RPC response as either SSE or JSON, depending on client preference.
	 */
	private void sendResponse(Context ctx, ACell response, boolean useSSE) {
		if (useSSE) {
			McpProtocol.sendResponse(ctx, response, true);
		} else {
			buildResult(ctx, response);
		}
	}

	// ===== GET /mcp — SSE stream =====

	/**
	 * GET /mcp — Open SSE stream for server-to-client messages.
	 * Requires a valid session (Mcp-Session-Id header) and Accept: text/event-stream.
	 */
	private void handleMcpGet(Context ctx) {
		String accept = ctx.header("Accept");
		if (accept == null || !accept.contains("text/event-stream")) {
			ctx.status(405);
			return;
		}

		String sessionId = ctx.header(HEADER_SESSION_ID);
		McpSession session = (sessionId != null) ? sessions.get(sessionId) : null;
		if (session == null) {
			ctx.status(400);
			return;
		}

		try {
			HttpServletResponse res = ctx.res();
			res.setContentType("text/event-stream");
			res.setCharacterEncoding("UTF-8");
			res.setHeader("Cache-Control", "no-cache");
			res.setHeader("X-Accel-Buffering", "no");
			res.flushBuffer();

			PrintWriter writer = res.getWriter();
			SseConnection conn = new SseConnection(writer);
			session.sseConnections.add(conn);
			try {
				// Keep-alive loop — blocks virtual thread until client disconnects
				while (!conn.isClosed()) {
					writer.write(": keepalive\n\n");
					writer.flush();
					if (writer.checkError()) break;
					Thread.sleep(SSE_KEEPALIVE_MS);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				conn.close();
				session.sseConnections.remove(conn);
				if (session.sseConnections.isEmpty()) {
					session.clearWatches();
				}
			}
		} catch (IOException e) {
			log.debug("SSE connection setup failed", e);
		}
	}

	// ===== DELETE /mcp — Session termination =====

	/**
	 * DELETE /mcp — Terminate an MCP session.
	 */
	private void handleMcpDelete(Context ctx) {
		String sessionId = ctx.header(HEADER_SESSION_ID);
		if (sessionId == null) {
			ctx.status(400);
			return;
		}
		McpSession session = sessions.remove(sessionId);
		if (session == null) {
			ctx.status(404);
			return;
		}
		session.close();
		ctx.status(200);
	}

	// ===== Tool handling =====

	/**
	 * Execute a tool call request received from a remote client
	 */
	private AMap<AString, ACell> toolCall(AMap<AString, ACell> params, AString callerDID) {
		try {
			AString toolName = RT.getIn(params, Fields.NAME);
			Hash opID = findTool(toolName);
			ACell arguments = RT.getIn(params, Fields.ARGUMENTS);
			if (opID != null) {
				Job job = engine().jobs().invokeOperation(opID.toCVMHexString(), arguments, RequestContext.of(callerDID));
				ACell result = job.awaitResult();
				return toolSuccess(result);
			} else {
				return protocolError(-32602, "Unknown tool: " + toolName);
			}
		} catch (Exception e) {
			return toolError(e.getMessage());
		}
	}

	private Hash findTool(AString toolName) {
		for (String adapterName : engine().getAdapterNames()) {
			try {
				var adapter = engine().getAdapter(adapterName);
				if (adapter == null) continue;

				Index<Hash, AString> adapterTools = adapter.getInstalledAssets();
				long n = adapterTools.count();
				for (long i = 0; i < n; i++) {
					Hash h = adapterTools.entryAt(i).getKey();
					AMap<AString, ACell> meta = engine().getMetaValue(h);
					AString opAdapter = RT.getIn(meta, Fields.OPERATION, Fields.ADAPTER);
					if (toolName.equals(opAdapter)) {
						return h;
					}
					AString opToolName = RT.getIn(meta, Fields.OPERATION, Fields.TOOL_NAME);
					if (toolName.equals(opToolName)) {
						return h;
					}
				}
			} catch (Exception e) {
				log.warn("Error processing adapter " + adapterName, e);
			}
		}
		return null;
	}

	// ===== Tool listing =====

	private AMap<AString, ACell> listToolsResult() {
		AVector<AMap<AString, ACell>> toolsVector = Vectors.empty();

		for (String adapterName : engine().getAdapterNames()) {
			try {
				var adapter = engine().getAdapter(adapterName);
				if (adapter == null) continue;

				Index<Hash, AString> installedAssets = adapter.getInstalledAssets();
				int n = installedAssets.size();

				for (int i = 0; i < n; i++) {
					try {
						MapEntry<Hash, AString> me = installedAssets.entryAt(i);
						AString metaString = me.getValue();
						AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
						AMap<AString, ACell> mcpTool = checkTool(meta);
						if (mcpTool != null) {
							toolsVector = toolsVector.conj(mcpTool);
						}
					} catch (Exception e) {
						log.warn("Error processing asset from adapter " + adapter.getName(), e);
					}
				}
			} catch (Exception e) {
				log.warn("Error processing adapter " + adapterName, e);
			}
		}

		return protocolResult(Maps.of("tools", toolsVector));
	}

	/**
	 * Get MCP tools from a specific adapter's installed assets.
	 * @param adapter The adapter to get tools from
	 * @return Vector of MCP tool metadata provided by this adapter
	 */
	public AVector<AMap<AString, ACell>> listTools(covia.adapter.AAdapter adapter) {
		AVector<AMap<AString, ACell>> toolsVector = Vectors.empty();
		try {
			Index<Hash, AString> installedAssets = adapter.getInstalledAssets();
			int n = installedAssets.size();
			for (int i = 0; i < n; i++) {
				try {
					MapEntry<Hash, AString> me = installedAssets.entryAt(i);
					AString metaString = me.getValue();
					AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
					AMap<AString, ACell> mcpTool = checkTool(meta);
					if (mcpTool != null) {
						toolsVector = toolsVector.conj(mcpTool);
					}
				} catch (Exception e) {
					log.warn("Error processing asset from adapter " + adapter.getName(), e);
				}
			}
		} catch (Exception e) {
			log.warn("Error getting installed assets from adapter " + adapter.getName(), e);
		}
		return toolsVector;
	}

	private AMap<AString, ACell> checkTool(AMap<AString, ACell> meta) {
		AMap<AString, ACell> op = RT.getIn(meta, Fields.OPERATION);
		if (op == null) return null;

		AString toolName = RT.ensureString(op.get(Fields.ADAPTER));
		if (toolName == null) {
			toolName = RT.ensureString(op.get(Fields.TOOL_NAME));
		}
		if (toolName == null) return null;

		AMap<AString, ACell> inputSchema = ensureSchema(RT.getIn(op, Fields.INPUT));
		AMap<AString, ACell> outputSchema = ensureSchema(RT.getIn(op, Fields.OUTPUT));

		return Maps.of(
			Fields.NAME, toolName,
			Fields.TITLE, RT.getIn(meta, Fields.NAME),
			Fields.DESCRIPTION, RT.getIn(meta, Fields.DESCRIPTION),
			Fields.INPUT_SCHEMA, inputSchema,
			Fields.OUTPUT_SCHEMA, outputSchema
		);
	}

	private AMap<AString, ACell> ensureSchema(AMap<AString, ACell> schema) {
		if (schema == null) schema = Maps.empty();
		schema = sanitiseSchema(schema);
		schema = schema.assoc(Fields.TYPE, Fields.OBJECT);
		return schema;
	}

	/** Valid JSON Schema draft 2020-12 type values */
	private static final Set<String> VALID_SCHEMA_TYPES = Set.of(
		"null", "boolean", "object", "array", "number", "string", "integer"
	);

	/** Non-standard keys to strip from schemas before exposing via MCP */
	private static final AString SECRET_KEY = Strings.intern("secret");
	private static final AString SECRET_FIELDS_KEY = Strings.intern("secretFields");

	/**
	 * Recursively sanitise a JSON Schema to ensure it conforms to draft 2020-12.
	 * Removes invalid type values (e.g. "any") and non-standard extension keys
	 * that strict validators (like the Anthropic API) may reject.
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> sanitiseSchema(AMap<AString, ACell> schema) {
		if (schema == null) return Maps.empty();

		// Remove invalid type values
		ACell typeVal = schema.get(Fields.TYPE);
		if (typeVal instanceof AString ts && !VALID_SCHEMA_TYPES.contains(ts.toString())) {
			schema = schema.dissoc(Fields.TYPE);
		}

		// Remove non-standard keys
		schema = schema.dissoc(SECRET_KEY);
		schema = schema.dissoc(SECRET_FIELDS_KEY);

		// Recurse into properties
		ACell propsCell = schema.get(Fields.PROPERTIES);
		if (propsCell instanceof AMap<?,?> props) {
			AMap<AString, ACell> cleanProps = (AMap<AString, ACell>) props;
			long n = props.count();
			for (long i = 0; i < n; i++) {
				MapEntry<AString, ACell> entry = (MapEntry<AString, ACell>) props.entryAt(i);
				if (entry.getValue() instanceof AMap<?,?> propSchema) {
					AMap<AString, ACell> cleaned = sanitiseSchema((AMap<AString, ACell>) propSchema);
					if (cleaned != propSchema) {
						cleanProps = cleanProps.assoc(entry.getKey(), cleaned);
					}
				}
			}
			if (cleanProps != props) {
				schema = schema.assoc(Fields.PROPERTIES, cleanProps);
			}
		}

		// Recurse into items (for array schemas)
		ACell itemsCell = schema.get(Fields.ITEMS);
		if (itemsCell instanceof AMap<?,?> itemsMap) {
			AMap<AString, ACell> cleaned = sanitiseSchema((AMap<AString, ACell>) itemsMap);
			if (cleaned != itemsMap) {
				schema = schema.assoc(Fields.ITEMS, cleaned);
			}
		}

		return schema;
	}

	// ===== Well-known endpoint =====

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> WELL_KNOWN = (AMap<AString, ACell>) JSON.parse("""
		{
			"mcp_version": "1.0",
			"server_url": "http:localhost:8080/mcp",
			"description": "MCP server for Covia Venue",
			"tools_endpoint": "/mcp",
			"endpoint": {"path":"/mcp","transport":"streamable-http"}
		}
""");

	@OpenApi(path = "/.well-known/mcp",
			methods = HttpMethod.GET,
			tags = { "MCP"},
			summary = "Get MCP server capabilities",
			operationId = "mcpWellKnown")
	protected void getMCPWellKnown(Context ctx) {
		AMap<AString, ACell> result = WELL_KNOWN;
		AString mcpURL = Strings.create(getExternalBaseUrl(ctx, "mcp"));
		result = result.assoc(SERVER_URL_FIELD, mcpURL);
		buildResult(ctx, result);
	}

}
