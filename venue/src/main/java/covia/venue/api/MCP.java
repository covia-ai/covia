package covia.venue.api;

import static convex.restapi.mcp.McpProtocol.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.ucan.UCANValidator;
import convex.core.json.schema.JsonSchema;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpServer;
import convex.restapi.mcp.McpSession;
import convex.restapi.mcp.SseConnection;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.SseServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

/**
 * MCP server on top of a Covia Venue.
 *
 * <p>Extends {@link McpServer} for protocol handling (POST dispatch, batching,
 * notifications, .well-known) and overrides tool listing and execution to
 * use the venue's dynamic adapter/asset registry.</p>
 *
 * <p>Adds SSE session handling for server-to-client job notifications.</p>
 */
public class MCP extends McpServer {

	public static final Logger log = LoggerFactory.getLogger(MCP.class);

	private final Venue venue;
	protected final SseServer sseServer;

	/** Default timeout for MCP tool calls (120 seconds) */
	private static final long TOOL_CALL_TIMEOUT_MS = 120_000;

	/** Active MCP sessions, keyed by session ID */
	private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();

	public MCP(Venue venue, AMap<AString, ACell> mcpConfig) {
		super(buildServerInfo(venue, mcpConfig));
		this.venue = venue;
		this.sseServer = new SseServer(engine());

		// Register MCP SSE job notification broadcaster
		engine().jobs().addJobUpdateListener(this::broadcastJobNotification);
	}

	private static AMap<AString, ACell> buildServerInfo(Venue venue, AMap<AString, ACell> mcpConfig) {
		AMap<AString, ACell> serverInfo = RT.getIn(mcpConfig, "serverInfo");
		if (serverInfo == null) {
			Engine engine = ((LocalVenue) venue).getEngine();
			serverInfo = Maps.of(
				"name", "covia-grid-mcp",
				"title", engine.getName(),
				"version", Utils.getVersion()
			);
		}
		return serverInfo;
	}

	protected Engine engine() {
		return ((LocalVenue) venue).getEngine();
	}

	// ==================== Route registration ====================

	@Override
	public void addRoutes(Javalin app) {
		// McpServer registers POST /mcp and GET /.well-known/mcp
		super.addRoutes(app);

		// SSE session routes
		app.get("/mcp", this::handleMcpGet);
		app.delete("/mcp", this::handleMcpDelete);
	}

	// ==================== Tool listing (dynamic, from adapters) ====================

	@Override
	protected AMap<AString, ACell> listTools() {
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

		return Maps.of("tools", toolsVector);
	}

	/**
	 * Get MCP tools from a specific adapter's installed assets.
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

	// ==================== Tool execution (job-based) ====================

	@Override
	protected AMap<AString, ACell> toolCall(ACell paramsCell) {
		AMap<AString, ACell> params = RT.ensureMap(paramsCell);
		if (params == null) return protocolError(-32602, "params must be an object");

		try {
			AString toolName = RT.getIn(params, Fields.NAME);
			Hash opID = findTool(toolName);
			ACell arguments = RT.getIn(params, Fields.ARGUMENTS);
			if (opID != null) {
				Context ctx = McpServer.getCurrentContext();
				AString callerDID = (ctx != null) ? AuthMiddleware.getCallerDID(ctx) : null;
				RequestContext rctx = RequestContext.of(callerDID);

				// Extract UCAN proofs from tool arguments (JWT strings)
				AVector<ACell> ucans = RT.getIn(arguments, Fields.UCANS);
				AVector<ACell> proofs = UCANValidator.parseTransportUCANs(ucans);
				if (proofs != null) rctx = rctx.withProofs(proofs);

				Job job = engine().jobs().invokeOperation(opID.toCVMHexString(), arguments, rctx);
				ACell result = job.awaitResult(TOOL_CALL_TIMEOUT_MS);
				if (result == null && !job.isComplete()) {
					return toolError("Tool call timed out after " + (TOOL_CALL_TIMEOUT_MS / 1000)
						+ "s. Job ID: " + job.getID().toHexString());
				}
				return toolSuccess(result);
			} else {
				return protocolError(-32602, "Unknown tool: " + toolName);
			}
		} catch (Exception e) {
			return toolError(e.getMessage());
		}
	}

	// ==================== Session creation on initialize ====================

	@Override
	protected AMap<AString, ACell> createResponse(AMap<?, ?> request) {
		AMap<AString, ACell> response = super.createResponse(request);
		// Create session on successful initialize
		String method = McpProtocol.getMethodName(request);
		if ("initialize".equals(method) && response.containsKey(FIELD_RESULT)) {
			McpSession session = new McpSession(UUID.randomUUID().toString());
			sessions.put(session.id, session);
			Context ctx = McpServer.getCurrentContext();
			if (ctx != null) ctx.header(HEADER_SESSION_ID, session.id);
		}
		return response;
	}

	// ==================== Initialize (version negotiation) ====================

	private static final String LATEST_PROTOCOL_VERSION = "2025-06-18";

	@Override
	protected AMap<AString, ACell> buildInitializeResult(ACell params) {
		return Maps.of(
			"protocolVersion", negotiateProtocolVersion(params),
			"capabilities", Maps.of("tools", Maps.empty()),
			"serverInfo", getServerInfo()
		);
	}

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

	// ==================== SSE sessions ====================

	/**
	 * GET /mcp — Open SSE stream for server-to-client messages.
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

	// ==================== Job notifications ====================

	private void broadcastJobNotification(Job job) {
		if (sessions.isEmpty()) return;
		var jobId = job.getID();
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

	// ==================== Tool metadata helpers ====================

	private Hash findTool(AString toolName) {
		AString sanitisedName = sanitiseToolName(toolName);
		for (String adapterName : engine().getAdapterNames()) {
			try {
				var adapter = engine().getAdapter(adapterName);
				if (adapter == null) continue;

				Index<Hash, AString> adapterTools = adapter.getInstalledAssets();
				long n = adapterTools.count();
				for (long i = 0; i < n; i++) {
					Hash h = adapterTools.entryAt(i).getKey();
					AMap<AString, ACell> meta = engine().getMetaValue(h);
					AString opAdapter = sanitiseToolName(RT.getIn(meta, Fields.OPERATION, Fields.ADAPTER));
					if (sanitisedName.equals(opAdapter)) {
						return h;
					}
					AString opToolName = sanitiseToolName(RT.getIn(meta, Fields.OPERATION, Fields.TOOL_NAME));
					if (sanitisedName.equals(opToolName)) {
						return h;
					}
				}
			} catch (Exception e) {
				log.warn("Error processing adapter " + adapterName, e);
			}
		}
		return null;
	}

	static AString sanitiseToolName(AString name) {
		if (name == null) return null;
		String s = name.toString();
		String sanitised = s.replace(':', '_').replace('/', '_');
		return Strings.create(sanitised);
	}

	private AMap<AString, ACell> checkTool(AMap<AString, ACell> meta) {
		AMap<AString, ACell> op = RT.getIn(meta, Fields.OPERATION);
		if (op == null) return null;

		AString toolName = RT.ensureString(op.get(Fields.TOOL_NAME));
		if (toolName == null) {
			toolName = sanitiseToolName(RT.ensureString(op.get(Fields.ADAPTER)));
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

	/** Keys to strip from schemas before exposing via MCP */
	private static final AString SECRET_KEY = Strings.intern("secret");
	private static final AString SECRET_FIELDS_KEY = Strings.intern("secretFields");

	private AMap<AString, ACell> ensureSchema(AMap<AString, ACell> schema) {
		if (schema == null) schema = Maps.empty();
		// Strip Covia-specific non-standard keys recursively before exposing to MCP clients
		schema = stripKeys(schema);
		// Validate schema structure — log warning for bad schemas
		String err = JsonSchema.checkSchema(schema);
		if (err != null) {
			log.warn("Invalid MCP tool schema: {}", err);
		}
		schema = schema.assoc(Fields.TYPE, Fields.OBJECT);
		return schema;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> stripKeys(AMap<AString, ACell> schema) {
		schema = schema.dissoc(SECRET_KEY).dissoc(SECRET_FIELDS_KEY);
		ACell propsCell = schema.get(Fields.PROPERTIES);
		if (propsCell instanceof AMap<?,?> props) {
			AMap<AString, ACell> cleanProps = (AMap<AString, ACell>) props;
			long n = props.count();
			for (long i = 0; i < n; i++) {
				var entry = (MapEntry<AString, ACell>) props.entryAt(i);
				if (entry.getValue() instanceof AMap<?,?> propSchema) {
					AMap<AString, ACell> cleaned = stripKeys((AMap<AString, ACell>) propSchema);
					if (cleaned != propSchema) cleanProps = cleanProps.assoc(entry.getKey(), cleaned);
				}
			}
			if (cleanProps != props) schema = schema.assoc(Fields.PROPERTIES, cleanProps);
		}
		ACell itemsCell = schema.get(Fields.ITEMS);
		if (itemsCell instanceof AMap<?,?> itemsMap) {
			AMap<AString, ACell> cleaned = stripKeys((AMap<AString, ACell>) itemsMap);
			if (cleaned != itemsMap) schema = schema.assoc(Fields.ITEMS, cleaned);
		}
		return schema;
	}
}
