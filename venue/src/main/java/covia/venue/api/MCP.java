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

	/**
	 * Default allowlist of adapter-name groups exposed via MCP. Operations
	 * outside these groups are presumed to be venue-internal utilities (HTTP,
	 * JSON, schema, LLM providers, etc.) — useful inside orchestrations
	 * running on the venue, but external agents can supply equivalents.
	 *
	 * <p>Pass {@code mcp.includeAdapters: ["*"]} to expose everything, or
	 * an explicit list to override the default.</p>
	 */
	private static final java.util.Set<String> DEFAULT_INCLUDE_ADAPTERS =
		java.util.Set.of("covia", "grid", "asset", "secret", "agent");

	/** Wildcard token in {@code includeAdapters} meaning "expose all groups". */
	private static final String INCLUDE_ALL = "*";

	/**
	 * Adapter-name groups (the path segment under {@code v/ops/}) eligible
	 * for MCP exposure. Read from {@code mcp.includeAdapters} at construction;
	 * defaults to {@link #DEFAULT_INCLUDE_ADAPTERS}.
	 */
	private final java.util.Set<String> includedAdapters;

	/**
	 * Path prefixes whose operations are eligible for MCP exposure. Defaults
	 * to {@code ["v/ops/"]}; configurable via {@code mcp.includePathPrefixes}.
	 * Test harnesses may add {@code "v/test/ops/"} to surface test operations.
	 */
	private final java.util.List<String> includePathPrefixes;

	/**
	 * Registry of MCP-exposed tools: sanitised MCP tool name → op reference path
	 * (e.g. {@code "v/ops/json/merge"}). Listing walks this map; tool calls
	 * route through it via {@code engine.jobs().invokeOperation(opRef, ...)}.
	 *
	 * <p>Built lazily on first access — MCP is constructed before
	 * {@code addDemoAssets} populates adapter catalogs, so eager construction
	 * would always see an empty engine.</p>
	 */
	private volatile java.util.Map<AString, AString> toolRegistry;

	public MCP(Venue venue, AMap<AString, ACell> mcpConfig) {
		super(buildServerInfo(venue, mcpConfig));
		this.venue = venue;
		this.sseServer = new SseServer(engine());
		this.includedAdapters = readIncludedAdapters(mcpConfig);
		this.includePathPrefixes = readIncludePathPrefixes(mcpConfig);

		// Register MCP SSE job notification broadcaster
		engine().jobs().addJobUpdateListener(this::broadcastJobNotification);
	}

	private static java.util.List<String> readIncludePathPrefixes(AMap<AString, ACell> mcpConfig) {
		if (mcpConfig != null) {
			ACell raw = mcpConfig.get(Strings.create("includePathPrefixes"));
			if (raw instanceof AVector<?> vec && vec.count() > 0) {
				java.util.List<String> list = new java.util.ArrayList<>();
				for (long i = 0; i < vec.count(); i++) {
					ACell entry = vec.get(i);
					if (entry instanceof AString s) list.add(s.toString());
				}
				return java.util.Collections.unmodifiableList(list);
			}
		}
		return java.util.List.of("v/ops/");
	}

	/** Returns the tool registry, building it on first access. */
	private java.util.Map<AString, AString> registry() {
		java.util.Map<AString, AString> r = toolRegistry;
		if (r != null) return r;
		synchronized (this) {
			if (toolRegistry == null) {
				toolRegistry = buildToolRegistry(engine(), includePathPrefixes, includedAdapters);
			}
			return toolRegistry;
		}
	}

	private static java.util.Set<String> readIncludedAdapters(AMap<AString, ACell> mcpConfig) {
		if (mcpConfig != null) {
			ACell raw = mcpConfig.get(Strings.create("includeAdapters"));
			if (raw instanceof AVector<?> vec && vec.count() > 0) {
				java.util.Set<String> set = new java.util.HashSet<>();
				for (long i = 0; i < vec.count(); i++) {
					ACell entry = vec.get(i);
					if (entry instanceof AString s) set.add(s.toString());
				}
				return java.util.Collections.unmodifiableSet(set);
			}
		}
		return DEFAULT_INCLUDE_ADAPTERS;
	}

	/**
	 * Build the MCP tool registry by walking all adapters' catalog entries.
	 * Includes only paths matching one of {@code includePathPrefixes} (default
	 * {@code v/ops/}). The first segment after the matched prefix is the
	 * adapter-name group, filtered against {@code includedAdapters}.
	 */
	private static java.util.Map<AString, AString> buildToolRegistry(
			Engine engine,
			java.util.List<String> includePathPrefixes,
			java.util.Set<String> includedAdapters) {
		boolean includeAll = includedAdapters.contains(INCLUDE_ALL);
		java.util.LinkedHashMap<AString, AString> reg = new java.util.LinkedHashMap<>();
		for (String adapterName : engine.getAdapterNames()) {
			var adapter = engine.getAdapter(adapterName);
			if (adapter == null) continue;
			for (var entry : adapter.pendingCatalogEntries.entrySet()) {
				String path = entry.getKey();
				String matched = matchingPrefix(path, includePathPrefixes);
				if (matched == null) continue;
				// Path shape: <prefix><group>/<op...>; group is the allowlist key
				String tail = path.substring(matched.length());
				int slash = tail.indexOf('/');
				String group = (slash >= 0) ? tail.substring(0, slash) : tail;
				if (!includeAll && !includedAdapters.contains(group)) continue;

				Hash hash = entry.getValue();
				AString metaString = adapter.getInstalledAssets().get(hash);
				if (metaString == null) continue;
				try {
					AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
					AString toolName = mcpToolNameFor(meta);
					if (toolName == null) continue;
					if (reg.putIfAbsent(toolName, Strings.create(path)) != null) {
						log.warn("Duplicate MCP tool name '{}' — keeping first registration; ignoring {}",
							toolName, path);
					}
				} catch (Exception e) {
					log.warn("Failed to register MCP tool from {}: {}", path, e.getMessage());
				}
			}
		}
		log.info("MCP tool registry built: {} tools (prefixes: {}, included groups: {})",
			reg.size(), includePathPrefixes, includeAll ? "ALL" : includedAdapters);
		return java.util.Collections.unmodifiableMap(reg);
	}

	private static String matchingPrefix(String path, java.util.List<String> prefixes) {
		for (String p : prefixes) if (path.startsWith(p)) return p;
		return null;
	}

	/**
	 * Derive the MCP tool name from operation metadata. Prefers the explicit
	 * {@code operation.toolName}, falling back to a sanitised
	 * {@code operation.adapter}. Returns null if neither is present and the
	 * operation is therefore not exposable.
	 */
	private static AString mcpToolNameFor(AMap<AString, ACell> meta) {
		AMap<AString, ACell> op = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
		if (op == null) return null;
		AString toolName = RT.ensureString(op.get(Fields.TOOL_NAME));
		if (toolName != null) return sanitiseToolName(toolName);
		return sanitiseToolName(RT.ensureString(op.get(Fields.ADAPTER)));
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
		for (var entry : registry().entrySet()) {
			AString toolName = entry.getKey();
			AString opRef = entry.getValue();
			try {
				AMap<AString, ACell> meta = engine().resolveAsset(opRef).meta();
				AMap<AString, ACell> tool = buildToolEntry(toolName, meta);
				if (tool != null) toolsVector = toolsVector.conj(tool);
			} catch (Exception e) {
				log.warn("Error resolving registered MCP tool {} ({}): {}",
					toolName, opRef, e.getMessage());
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
			AString opRef = (toolName != null) ? registry().get(sanitiseToolName(toolName)) : null;
			ACell arguments = RT.getIn(params, Fields.ARGUMENTS);
			if (opRef != null) {
				Context ctx = McpServer.getCurrentContext();
				AString callerDID = (ctx != null) ? AuthMiddleware.getCallerDID(ctx) : null;
				RequestContext rctx = RequestContext.of(callerDID);

				// Extract UCAN proofs from both transport channels:
				//   1. `ucans` in tool arguments
				//   2. `Authorization: Bearer <ucan-jwt>` stashed by AuthMiddleware
				AVector<ACell> ucans = RT.getIn(arguments, Fields.UCANS);
				AString bearer = (ctx != null) ? ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR) : null;
				AVector<ACell> proofs = UCANValidator.parseTransportUCANsWithBearer(bearer, ucans);
				if (proofs != null) rctx = rctx.withProofs(proofs);

				Job job = engine().jobs().invokeOperation(opRef, arguments, rctx);
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

	@Override
	protected String negotiateProtocolVersion(ACell params) {
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

	static AString sanitiseToolName(AString name) {
		if (name == null) return null;
		String s = name.toString();
		String sanitised = s.replace(':', '_').replace('/', '_');
		return Strings.create(sanitised);
	}

	/**
	 * Build an MCP tool entry from already-resolved tool name and op metadata.
	 * Returns null if the metadata has no map-form operation (e.g. is a
	 * string-ref template).
	 */
	private AMap<AString, ACell> buildToolEntry(AString toolName, AMap<AString, ACell> meta) {
		AMap<AString, ACell> op = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
		if (op == null) return null;
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

	/**
	 * Legacy per-adapter listing used by tests. Walks an adapter's installed
	 * assets directly (bypassing the registry) and returns MCP-shaped tool
	 * entries for any with a map-form operation.
	 */
	private AMap<AString, ACell> checkTool(AMap<AString, ACell> meta) {
		AString toolName = mcpToolNameFor(meta);
		if (toolName == null) return null;
		return buildToolEntry(toolName, meta);
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
