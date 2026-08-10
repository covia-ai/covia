package covia.venue.api;

import static covia.venue.server.VenueRouteFeature.COVIA_MCP;

import static convex.restapi.mcp.McpProtocol.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.json.schema.JsonSchema;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
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
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.SseServer;
import io.javalin.config.RoutesConfig;
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
	 * Authenticated principal that created each MCP session. A session ID is
	 * routing state, not a bearer credential: subsequent GET/DELETE requests
	 * must still be made by the same caller that initialized the session.
	 */
	private final ConcurrentHashMap<String, AString> sessionOwners = new ConcurrentHashMap<>();

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
		java.util.Set.of("covia", "grid", "asset", "secret", "agent", "skills");

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

	/** Effective MCP transport authentication policy. Discovery stays public. */
	private final boolean authRequired;

	/** Optional allowlist applied after bearer authentication. */
	private final java.util.Set<String> allowedDids;

	/**
	 * Registry of MCP-exposed tools: sanitised MCP tool name → op reference path
	 * (e.g. {@code "v/ops/json/merge"}). Listing walks this map; tool calls
	 * route through it via {@code engine.jobs().runOperation(opRef, ...)}.
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
		this.authRequired = engine().config().isMCPAuthRequired();
		this.allowedDids = engine().config().getMCPAllowedDids();
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
	public void addRoutes(RoutesConfig routes) {
		// Keep the MCP library's transport behaviour while owning discovery so
		// Covia can publish its authentication policy. Well-known metadata is a
		// public bootstrap surface; authentication applies only to /mcp.
		routes.before("/mcp", this::validateMcpOrigin);
		routes.post("/mcp", this::handlePost, COVIA_MCP);
		routes.get("/.well-known/mcp", this::handleWellKnown);
		routes.get("/.well-known/oauth-protected-resource/mcp",
			this::handleProtectedResourceMetadata);

		// SSE session routes
		routes.get("/mcp", this::handleMcpGet, COVIA_MCP);
		routes.delete("/mcp", this::handleMcpDelete, COVIA_MCP);
	}

	private void validateMcpOrigin(Context ctx) {
		String origin = ctx.header("Origin");
		if (origin != null && !isOriginAllowed(origin)) {
			throw new io.javalin.http.ForbiddenResponse("Forbidden: invalid origin");
		}
	}

	/**
	 * Public MCP server discovery. The base fields preserve the Convex MCP
	 * library's discovery shape. Covia-specific authentication information is
	 * namespaced in {@code _meta}, following MCP's extension convention.
	 */
	private void handleWellKnown(Context ctx) {
		String resource = mcpResource(ctx);
		AMap<AString, ACell> authentication = coviaAuthenticationMetadata(ctx);
		AMap<AString, ACell> result = Maps.of(
			"mcp_version", "1.0",
			"server_url", resource,
			"description", getServerInfo().get(Strings.create("title")),
			"endpoint", Maps.of("path", "/mcp", "transport", "streamable-http"),
			"_meta", Maps.of("ai.covia/authentication", authentication)
		);
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.print(result).toString());
	}

	/**
	 * RFC 9728 protected-resource metadata for the path-specific MCP resource.
	 *
	 * <p>Covia bearer tokens are DID/UCAN JWTs rather than OAuth access tokens,
	 * so no fictional OAuth authorisation server is advertised. RFC 9728 permits
	 * additional metadata and requires clients to ignore unknown members; the
	 * Covia profile is therefore carried in a namespaced {@code _meta} member.
	 * A future OAuth bridge can add the standard {@code authorization_servers}
	 * member without changing this endpoint.</p>
	 */
	private void handleProtectedResourceMetadata(Context ctx) {
		AMap<AString, ACell> result = Maps.of(
			"resource", mcpResource(ctx),
			"resource_name", engine().getName(),
			"bearer_methods_supported", Vectors.of("header"),
			"_meta", Maps.of("ai.covia/authentication", coviaAuthenticationMetadata(ctx))
		);
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.print(result).toString());
	}

	private AMap<AString, ACell> coviaAuthenticationMetadata(Context ctx) {
		return Maps.of(
			"required", authRequired,
			"scheme", "Bearer",
			"bearer_token_profiles",
				Vectors.of("ucan-jwt", "self-issued-did-jwt", "venue-session-jwt"),
			"caller_identity", "DID",
			"did_allowlist", !allowedDids.isEmpty(),
			"protected_resource_metadata", protectedResourceMetadata(ctx)
		);
	}

	private static String mcpResource(Context ctx) {
		return ACoviaAPI.getExternalBaseUrl(ctx, null) + "/mcp";
	}

	private static String protectedResourceMetadata(Context ctx) {
		return ACoviaAPI.getExternalBaseUrl(ctx, null)
			+ "/.well-known/oauth-protected-resource/mcp";
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

	/**
	 * Coerces JSON-string arguments to their declared object/array shape.
	 *
	 * <p>Some MCP clients (notably Claude) serialise nested object or array
	 * arguments as JSON strings rather than the structured types declared
	 * in the tool's input schema. For each top-level argument whose schema
	 * declares a single {@code type} of {@code "object"} or {@code "array"},
	 * if the supplied value is an {@link AString} that parses as JSON of the
	 * matching shape, replace it with the parsed value.
	 *
	 * <p>Multi-type schemas (e.g. {@code grid_run.input}'s {@code ["null",
	 * "boolean", "object", ...]}) are left untouched — string is a valid
	 * member of that type set, so coercing would change semantics. The
	 * defensive parse for those values lives in {@code GridAdapter.invokeRun}.
	 *
	 * <p>Returns the original arguments cell unchanged if no coercion is
	 * needed, the schema is unavailable, or anything fails.
	 */
	@SuppressWarnings("unchecked")
	private ACell coerceJsonStringArgs(ACell arguments, AString opRef) {
		if (!(arguments instanceof AMap<?, ?>)) return arguments;
		AMap<AString, ACell> argMap = (AMap<AString, ACell>) arguments;
		AMap<AString, ACell> meta;
		try {
			meta = engine().resolveAsset(opRef).meta();
		} catch (Exception e) {
			return arguments;
		}
		if (meta == null) return arguments;

		AMap<AString, ACell> properties = RT.ensureMap(RT.getIn(meta, Fields.OPERATION, Fields.INPUT, Fields.PROPERTIES));
		if (properties == null) return arguments;

		AMap<AString, ACell> result = argMap;
		long n = argMap.count();
		for (long i = 0; i < n; i++) {
			MapEntry<AString, ACell> entry = (MapEntry<AString, ACell>) argMap.entryAt(i);
			ACell value = entry.getValue();
			if (!(value instanceof AString s)) continue;

			AMap<AString, ACell> propSchema = RT.ensureMap(properties.get(entry.getKey()));
			if (propSchema == null) continue;

			ACell typeCell = propSchema.get(Fields.TYPE);
			if (!(typeCell instanceof AString typeStr)) continue;

			String type = typeStr.toString();
			if (!"object".equals(type) && !"array".equals(type)) continue;

			ACell parsed = tryParseJson(s);
			if (parsed == null) continue;
			if ("object".equals(type) && !(parsed instanceof AMap)) continue;
			if ("array".equals(type) && !(parsed instanceof AVector)) continue;

			result = result.assoc(entry.getKey(), parsed);
		}
		return result;
	}

	private static ACell tryParseJson(AString s) {
		String str = s.toString();
		if (str.isEmpty()) return null;
		char c = str.charAt(0);
		if (c != '{' && c != '[') return null;
		try {
			return JSON.parse(str);
		} catch (Exception e) {
			return null;
		}
	}

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
				RequestContext rctx = AuthMiddleware.callerContext(ctx);

				// Attach transport UCAN authority — proofs are additive cross-user
				// grants — from the `ucans` tool argument and an Authorization
				// bearer UCAN.
				AVector<ACell> ucans = RT.getIn(arguments, Fields.UCANS);
				AString bearer = (ctx != null) ? ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR) : null;
				rctx = AuthMiddleware.withTransportAuth(rctx, bearer, ucans, engine().getDIDString(), engine().didVerifier());
				// `ucans` is transport authority, not operation input. Never let
				// raw proof JWTs enter durable Job records, adapter logs, or agent
				// timelines merely because MCP carries them inside tool arguments.
				if (ucans != null && arguments instanceof AMap<?, ?> map) {
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> argMap = (AMap<AString, ACell>) map;
					arguments = argMap.dissoc(Fields.UCANS);
				}

				if (engine().config().isFixMcpStrings()) {
					arguments = coerceJsonStringArgs(arguments, opRef);
				}

				ACell result = engine().jobs().runOperation(opRef, arguments, rctx)
					.get(TOOL_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
				// Scalar (non-map) results render as an MCP text result in the
				// protocol layer (Convex 0.8.9) — no covia-side wrapping needed.
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
			if (ctx != null) {
				AString caller = AuthMiddleware.getCallerDID(ctx);
				if (caller != null) sessionOwners.put(session.id, caller);
				ctx.header(HEADER_SESSION_ID, session.id);
			}
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
		if (session == null || !sessionOwnedByCaller(ctx, sessionId)) {
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
		if (!sessions.containsKey(sessionId) || !sessionOwnedByCaller(ctx, sessionId)) {
			ctx.status(404);
			return;
		}
		McpSession session = sessions.remove(sessionId);
		if (session == null) {
			ctx.status(404);
			return;
		}
		sessionOwners.remove(sessionId);
		session.close();
		ctx.status(200);
	}

	/** True only when the current authenticated/public principal owns a session. */
	private boolean sessionOwnedByCaller(Context ctx, String sessionId) {
		if (sessionId == null) return false;
		AString owner = sessionOwners.get(sessionId);
		AString caller = AuthMiddleware.getCallerDID(ctx);
		return owner != null && owner.equals(caller);
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
	AMap<AString, ACell> buildToolEntry(AString toolName, AMap<AString, ACell> meta) {
		AMap<AString, ACell> op = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
		if (op == null) return null;
		// MCP requires inputSchema on every tool; an op declaring none takes
		// an unconstrained object. Declared schemas pass through as written —
		// testToolSchemasValid enforces that authors declare type: object.
		AMap<AString, ACell> declaredInput = RT.ensureMap(RT.getIn(op, Fields.INPUT));
		AMap<AString, ACell> inputSchema = (declaredInput != null)
			? prepareSchema(declaredInput)
			: Maps.of(Fields.TYPE, Fields.OBJECT);
		AMap<AString, ACell> entry = Maps.of(
			Fields.NAME, toolName,
			Fields.TITLE, RT.getIn(meta, Fields.NAME),
			Fields.DESCRIPTION, RT.getIn(meta, Fields.DESCRIPTION),
			Fields.INPUT_SCHEMA, inputSchema
		);
		// A declared outputSchema obliges every result to carry conforming
		// structuredContent, which MCP permits only for JSON objects (spec
		// 2025-06-18) — so only genuinely object-typed declared outputs are
		// advertised; scalar and undeclared outputs make no schema claim and
		// arrive as text content.
		AMap<AString, ACell> declaredOutput = RT.ensureMap(RT.getIn(op, Fields.OUTPUT));
		if (declaredOutput != null && Fields.OBJECT.equals(declaredOutput.get(Fields.TYPE))) {
			entry = entry.assoc(Fields.OUTPUT_SCHEMA, prepareSchema(declaredOutput));
		}
		// MCP annotations are advisory client hints (read-only, destructive,
		// idempotent, open-world and title). Native and bridged operations store
		// them under mcp.annotations; expose them verbatim so clients can apply
		// confirmation UX without treating the hints as authorisation.
		AMap<AString, ACell> annotations =
			RT.ensureMap(RT.getIn(meta, Strings.intern("mcp"), Strings.intern("annotations")));
		if (annotations != null && annotations.count() > 0) {
			entry = entry.assoc(Strings.intern("annotations"), annotations);
		}
		return entry;
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

	/** Prepare a declared schema for MCP exposure: strip covia-internal keys
	 *  and warn on structurally invalid schemas. Never invents or rewrites
	 *  fields — what the op declares is what clients see; conformance of the
	 *  declarations is test-enforced, not silently patched. */
	private AMap<AString, ACell> prepareSchema(AMap<AString, ACell> schema) {
		schema = stripKeys(schema);
		String err = JsonSchema.checkSchema(schema);
		if (err != null) {
			log.warn("Invalid MCP tool schema: {}", err);
		}
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
