package covia.adapter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.venue.RequestContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

public class MCPAdapter extends AAdapter {

	public static final Logger log=LoggerFactory.getLogger(MCPAdapter.class);

	/** Persistent client sessions keyed by "serverUrl|token" */
	private final ConcurrentHashMap<String, McpClientSession> clientSessions = new ConcurrentHashMap<>();

	public  Hash TOOL_CALL;
	public  Hash TOOLS_LIST;

	@Override
	public String getName() {
		return "mcp";
	}
	
	@Override
	public String getDescription() {
		return "A Model Context Protocol (MCP) adapter that enables seamless integration with MCP-compatible AI models and tools. " +
			   "Provides standardised communication protocols for AI agents to interact with external systems and services. " +
			   "Essential for building sophisticated AI workflows and connecting with modern AI development ecosystems.";
	}
	
	@Override
	protected void installAssets() {
		TOOL_CALL  = installAsset("mcp/tools-call", "/adapters/mcp/toolCall.json");
		TOOLS_LIST = installAsset("mcp/tools-list", "/adapters/mcp/toolList.json");
		installAsset("mcp/add-server",    "/adapters/mcp/addServer.json");
		installAsset("mcp/remove-server", "/adapters/mcp/removeServer.json");
		installAsset("mcp/refresh",       "/adapters/mcp/refresh.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		// getSubOperation returns everything after "mcp:", e.g. "tools:call" or "tools:list"
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			throw new IllegalArgumentException("Insufficient specification for MCP operation");
		}

		String[] subParts = subOp.split(":");
		String feature = subParts[0];

		if (feature.equals("tools")) {
			if (subParts.length < 2) {
				throw new IllegalArgumentException("MCP tools operation requires function (call/list)");
			}
			String function = subParts[1];

			if (function.equals("call")) {
				// Standard MCP tool call
				return CompletableFuture.supplyAsync(() -> {
					try {
						// Remote tool name is from input if provided
						AString remoteToolName=RT.getIn(input, Fields.TOOL_NAME);

						// Extract operation name from "mcp:tools:call:operationName" format
						if ((remoteToolName==null)&&(subParts.length>=3)) {
							remoteToolName=Strings.create(subParts[2]);
						}

						// Fallback: see if "remoteToolName" is specified in operation
						if (remoteToolName==null) {
							remoteToolName=RT.getIn(meta, Fields.OPERATION,Fields.REMOTE_TOOL_NAME);
						}

						if (remoteToolName==null) {
							throw new JobFailedException("No remote tool name provided (either in input or operation metadata)");
						}

						// Get MCP server URL from metadata or input
						AString serverUrl =getServerUrl(meta, input);
						if (serverUrl == null) {
							throw new JobFailedException("No server URL provided in input (or asset metadata fallback)");
						}

						// Bridged form (#80): when the OPERATION metadata pins the
						// remote tool, the op's declared input IS the tool's own
						// schema — the whole invocation input is the arguments.
						// The generic tools-call op keeps the wrapped
						// {server, toolName, arguments} form; explicit
						// input.arguments always wins for back-compat.
						AMap<AString,ACell> toolArguments=RT.getIn(input, Fields.ARGUMENTS);
						if (toolArguments == null
								&& RT.getIn(meta, Fields.OPERATION, Fields.REMOTE_TOOL_NAME) != null) {
							toolArguments = (input instanceof AMap)
								? (AMap<AString,ACell>) input : Maps.empty();
						}
						if (toolArguments == null) {
							throw new JobFailedException("Tool call requires arguments as a JSON object");
						}

						// Access token: explicit input wins; else the operation
						// metadata's auth reference (secret ref, possibly
						// DID-qualified) resolved at call time — never persisted
						// as a raw value in the bridged asset.
						AString token=RT.getIn(input, Fields.TOKEN);
						String accessToken=(token!=null)?token.toString()
							: resolveAuthRef(ctx, RT.ensureString(
								RT.getIn(meta, Fields.OPERATION, K_AUTH)));

						// Make the MCP tool call
						return callMCPTool(serverUrl, remoteToolName.toString(), toolArguments, accessToken);

					} catch (Exception e) {
						throw new JobFailedException(e);
					}
				}, VIRTUAL_EXECUTOR);

			} else if (function.equals("list")) {
				// List available MCP tools
				return CompletableFuture.supplyAsync(() -> {
					try {
						// Get MCP server URL from metadata or input
						AString serverUrl = getServerUrl(meta, input);
						if (serverUrl == null) {
							throw new JobFailedException("No server URL provided in input (or asset metadata fallback)");
						}

						// Get API access token, if provided
						AString token = RT.getIn(input, Fields.TOKEN);
						String accessToken = (token == null) ? null : token.toString();

						// List the MCP tools
						return listMCPTools(serverUrl, accessToken);

					} catch (Exception e) {
						throw new JobFailedException(e);
					}
				}, VIRTUAL_EXECUTOR);
			} else {
				throw new UnsupportedOperationException("Unsupported tools function: " + function);
			}
		} else if (feature.equals("server")) {
			// Server bridging (#80): register / refresh / remove external MCP
			// servers whose tools materialise as ordinary catalog operations.
			if (subParts.length < 2) {
				throw new IllegalArgumentException("MCP server operation requires function (add/remove/refresh)");
			}
			String function = subParts[1];
			return CompletableFuture.supplyAsync(() -> {
				try {
					return switch (function) {
						case "add" -> handleAddServer(ctx, input);
						case "remove" -> handleRemoveServer(ctx, input);
						case "refresh" -> handleRefresh(ctx, input);
						default -> throw new UnsupportedOperationException(
							"Unsupported server function: " + function);
					};
				} catch (JobFailedException | IllegalArgumentException e) {
					throw e;
				} catch (Exception e) {
					throw new JobFailedException(e);
				}
			}, VIRTUAL_EXECUTOR);
		} else {
			throw new UnsupportedOperationException("Unsupported MCP feature: " + feature);
		}
	}

	/**
	 * Extracts the MCP server URL from metadata or input parameters.
	 */
	private AString getServerUrl(AMap<AString, ACell> meta, ACell input) {
		// First try to get from input parameters
		AString url = RT.ensureString(RT.getIn(input, Fields.SERVER));
		if (url != null) return url;

		// Then check metadata — the operation block (bridged ops, #80), then
		// the legacy top-level position.
		if (meta != null) {
			url = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.SERVER));
			if (url != null) return url;
			url = RT.ensureString(meta.get(Fields.SERVER));
			if (url != null) return url;
		}

		return null;
	}

	/**
	 * Resolves an operation-metadata auth reference to an access token (#80).
	 * Accepted forms:
	 * <ul>
	 *   <li>{@code s/NAME} / {@code /s/NAME} — resolved in the CALLER's secret
	 *       store (per-caller credentials for a shared bridge)</li>
	 *   <li>{@code did:…/s/NAME} — DID-qualified secret location, resolved in
	 *       that identity's store. Phase 1 allows only the venue's own DID or
	 *       the caller's — anything else fails with a clear message.</li>
	 *   <li>anything else — treated as a literal token (works, discouraged —
	 *       same stance as raw {@code apiKey} in agent config)</li>
	 * </ul>
	 * Fail-closed: a reference that names a missing secret throws rather than
	 * silently connecting unauthenticated.
	 */
	String resolveAuthRef(RequestContext ctx, AString auth) {
		if (auth == null) return null;
		String ref = auth.toString();
		if (ref.startsWith("s/") || ref.startsWith("/s/")) {
			String value = engine.resolveSecret(ref, ctx);
			if (value == null) throw new JobFailedException(
				"MCP server auth secret not found in your store: " + ref);
			return value;
		}
		if (ref.startsWith("did:")) {
			int idx = ref.indexOf("/s/");
			if (idx < 0) throw new JobFailedException(
				"DID-qualified auth reference must be <did>/s/<name>: " + ref);
			String ownerDid = ref.substring(0, idx);
			String name = ref.substring(idx + 3);
			AString venueDid = engine.getDIDString();
			boolean allowed = ownerDid.equals(venueDid != null ? venueDid.toString() : null)
				|| ownerDid.equals(ctx.getCallerDID() != null ? ctx.getCallerDID().toString() : null);
			if (!allowed) throw new JobFailedException(
				"MCP server auth secret is owned by " + ownerDid
				+ " — only the venue's or your own secrets can back a bridged server");
			String value = engine.resolveSecret("s/" + name,
				RequestContext.of(Strings.create(ownerDid)));
			if (value == null) throw new JobFailedException(
				"MCP server auth secret not found: " + ref);
			return value;
		}
		return ref; // literal token
	}
	
	/**
	 * Get or create a persistent session for the given server URL and token.
	 */
	private McpClientSession getOrConnect(String serverUrl, String accessToken) {
		String key = serverUrl + "|" + (accessToken != null ? accessToken : "");
		return clientSessions.computeIfAbsent(key, k -> new McpClientSession(serverUrl, accessToken));
	}

	/**
	 * Connect to an MCP server via a base URL. Returns a connected client
	 * from the session pool.
	 * @param baseURL Server base URL
	 * @param accessToken Optional bearer token
	 * @return McpSyncClient instance (session-managed)
	 * @throws Exception on connection failure
	 */
	public McpSyncClient connect(String baseURL, String accessToken) throws Exception {
		return getOrConnect(baseURL, accessToken).getClient();
	}

	/**
	 * Makes an MCP tool call to the specified server using a persistent session.
	 * @param serverUrl MCP server URL
	 * @param toolName Tool name to call
	 * @param input Tool arguments
	 * @param accessToken Optional access token
	 */
	public ACell callMCPTool(AString serverUrl, String toolName, ACell input, String accessToken) throws Exception {
		McpClientSession session = getOrConnect(serverUrl.toString(), accessToken);
		try {
			McpSyncClient client = session.getClient();
			AMap<AString,ACell> toolArgs = RT.castMap(input);
			return makeToolCall(client, toolName, toolArgs);
		} catch (Exception e) {
			session.invalidate();
			throw e;
		}
	}
	
	/**
	 * Makes a tool call using the MCP client
	 */
	private ACell makeToolCall(McpSyncClient client, String toolName, AMap<AString,ACell> toolParams) throws Exception {

		@SuppressWarnings("unchecked")
		CallToolRequest request = CallToolRequest.builder()
			.name(toolName)
			.arguments((Map<String,Object>)JSON.json(toolParams))
			.build();

		CallToolResult response=client.callTool(request);
		// System.out.println("MCPAdapter response: "+response);
		
		return RT.cvm(response.structuredContent());
	}
	
	/**
	 * Converts an MCP tool's input schema to Convex ACell format.
	 *
	 * <p>As of mcp 2.0 {@code Tool.inputSchema()} is a raw
	 * {@code Map<String,Object>} holding the JSON Schema (earlier releases
	 * exposed a typed {@code JsonSchema} record). We convert it generically,
	 * which faithfully preserves the whole schema (type, properties, required,
	 * {@code $defs}, {@code items}, descriptions, …) rather than copying a
	 * hand-picked subset of fields.</p>
	 *
	 * @param inputSchema The tool's JSON Schema as a raw map (may be null/empty)
	 * @return ACell representing the schema; a minimal object schema if absent
	 */
	private ACell getInputSchema(Map<String, Object> inputSchema) {
		if (inputSchema == null || inputSchema.isEmpty()) {
			return Maps.of(Fields.TYPE, Strings.create("object"));
		}
		try {
			return convertToConvex(inputSchema);
		} catch (Exception e) {
			// If conversion fails, return a basic schema structure
			log.warn("Failed to convert tool input schema to Convex format: " + e.getMessage());
			return Maps.of(
				"type", "object",
				"description", "Input parameters for the tool"
			);
		}
	}
	
	/**
	 * Helper method to convert JsonSchema objects to Convex format
	 * @param obj The Java object to convert
	 * @return ACell representation of the object
	 */
	private ACell convertToConvex(Object obj) {
		if (obj == null) {
			return null;
		} else if (obj instanceof String) {
			return Strings.create((String) obj);
		} else if (obj instanceof Boolean) {
			return RT.cvm((Boolean) obj);
		} else if (obj instanceof Number) {
			return RT.cvm((Number) obj);
		} else if (obj instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) obj;
			AMap<AString, ACell> result = Maps.empty();
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				ACell value = convertToConvex(entry.getValue());
				result = result.assoc(Strings.create(entry.getKey()), value);
			}
			return result;
		} else if (obj instanceof List) {
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) obj;
			AVector<ACell> result = Vectors.empty();
			for (Object item : list) {
				ACell value = convertToConvex(item);
				result = result.conj(value);
			}
			return result;
		} else {
			// For other types, convert to string
			return Strings.create(obj.toString());
		}
	}
	
	/**
	 * Lists available MCP tools from the specified server using a persistent session.
	 * @param serverUrl The MCP server URL
	 * @param accessToken Optional access token for authentication
	 * @return ACell containing the list of tools
	 * @throws Exception if the operation fails
	 */
	public ACell listMCPTools(AString serverUrl, String accessToken) throws Exception {
		McpClientSession session = getOrConnect(serverUrl.toString(), accessToken);
		try {
			McpSyncClient client = session.getClient();
			ListToolsResult result = client.listTools();
			List<Tool> tools = result.tools();

			AVector<AMap<AString, ACell>> toolsVector = Vectors.empty();
			for (Tool tool : tools) {
				ACell inputSchema = getInputSchema(tool.inputSchema());
				AMap<AString, ACell> toolMap = Maps.of(
					Fields.NAME, Strings.create(tool.name()),
					Fields.DESCRIPTION, Strings.create(tool.description()),
					Fields.INPUT_SCHEMA, RT.cvm(inputSchema)
				);
				toolsVector = toolsVector.conj(toolMap);
			}

			return Maps.of(
				"tools", toolsVector,
				Fields.TOTAL, AInteger.create(tools.size())
			);
		} catch (Exception e) {
			session.invalidate();
			throw e;
		}
	}

	// ========== Server bridging (#80) ==========

	private static final AString K_AUTH  = Strings.intern("auth");
	private static final AString K_URL   = Strings.intern("url");
	private static final AString K_SCOPE = Strings.intern("scope");
	private static final AString SCOPE_VENUE = Strings.intern("venue");

	/**
	 * Registers an external MCP server and materialises its tools as catalog
	 * operations. Scope {@code user} (default): registry at
	 * {@code w/mcp/servers/<name>}, ops at the caller's
	 * {@code o/mcp/<name>/<tool>}. Scope {@code venue}: registry at
	 * {@code v/mcp/servers/<name>}, ops at {@code v/ops/mcp/<name>/<tool>} —
	 * requires the {@code mcp/manage} ability on {@code v/mcp}.
	 *
	 * <p>The server URL passes the SAME SSRF validation (and operator
	 * allow/block lists) as the http adapter: binding a remote MCP server can
	 * never reach anything a direct HTTP call couldn't. Bridged assets are
	 * self-contained — they work without the registry entry; the registry
	 * exists for refresh/remove bookkeeping.</p>
	 */
	ACell handleAddServer(RequestContext ctx, ACell input) throws Exception {
		String name = requireServerName(input);
		AString url = RT.ensureString(RT.getIn(input, K_URL));
		if (url == null) throw new IllegalArgumentException("url is required");
		AString auth = RT.ensureString(RT.getIn(input, K_AUTH));
		boolean venueScope = isVenueScope(ctx, input);

		// SSRF guard — shared with the http adapter, including its allowlist.
		((HTTPAdapter) engine.getAdapter("http")).requireSafeUrl(url.toString());

		// Discover tools under the REGISTRAR's auth (resolved now, not stored raw)
		String token = resolveAuthRef(ctx, auth);
		List<Tool> tools = listRemoteTools(url, token);

		// Normalise a bare secret ref to the registrar's DID-qualified form so
		// the stored reference has one unambiguous owner (#80 ruling 3).
		AString storedAuth = qualifyAuthRef(auth, ctx);

		RequestContext writeCtx = venueScope ? engine.venueContext() : ctx;
		String opsRoot = opsRoot(venueScope, name);
		AVector<ACell> toolNames = writeBridgedOps(writeCtx, opsRoot, name, url, storedAuth, tools);

		writeLattice(writeCtx, registryPath(venueScope, name), Maps.of(
			K_URL, url,
			K_AUTH, storedAuth,
			Fields.CALLER, ctx.getCallerDID(),
			Strings.intern("added"), convex.core.data.prim.CVMLong.create(
				convex.core.util.Utils.getCurrentTimestamp()),
			Fields.TOTAL, AInteger.create(toolNames.count())));

		AMap<AString, ACell> result = Maps.of(
			Fields.NAME, Strings.create(name),
			K_SCOPE, venueScope ? SCOPE_VENUE : Strings.intern("user"),
			K_URL, url,
			Strings.intern("tools"), toolNames,
			Fields.TOTAL, AInteger.create(toolNames.count()));
		AString authWarn = rawAuthWarning(auth);
		if (authWarn != null) {
			result = result.assoc(Fields.WARNINGS, Vectors.of(authWarn));
		}
		return result;
	}

	/** Advisory for a literal credential in a server registration — the
	 *  registry persists on the lattice. Secret references (bare or
	 *  DID-qualified) are the supported pattern; null when clean. */
	static AString rawAuthWarning(AString auth) {
		if (auth == null) return null;
		String ref = auth.toString();
		if (ref.startsWith("s/") || ref.startsWith("/s/") || ref.startsWith("did:")) return null;
		return Strings.intern(
			"auth holds a raw credential — the server registry persists on the lattice."
			+ " Store the token with the v/ops/secret/set operation and reference it as"
			+ " s/<name> instead.");
	}

	/** Removes a bridged server: deletes its catalog subtree and registry
	 *  entry — nothing else (deletion scope is minimal). In-flight calls fail
	 *  at the point of use. */
	ACell handleRemoveServer(RequestContext ctx, ACell input) {
		String name = requireServerName(input);
		boolean venueScope = isVenueScope(ctx, input);
		RequestContext writeCtx = venueScope ? engine.venueContext() : ctx;
		deleteLattice(writeCtx, opsRoot(venueScope, name));
		deleteLattice(writeCtx, registryPath(venueScope, name));
		return Maps.of(Fields.NAME, Strings.create(name),
			Strings.intern("removed"), convex.core.data.prim.CVMBool.TRUE);
	}

	/** Re-lists a bridged server's tools and reconciles the catalog: new tools
	 *  added, changed ones re-registered, vanished ones removed. */
	ACell handleRefresh(RequestContext ctx, ACell input) throws Exception {
		String name = requireServerName(input);
		boolean venueScope = isVenueScope(ctx, input);
		RequestContext writeCtx = venueScope ? engine.venueContext() : ctx;

		ACell reg = readLattice(writeCtx, registryPath(venueScope, name));
		AString url = RT.ensureString(RT.getIn(reg, K_URL));
		if (url == null) throw new JobFailedException("Unknown MCP server: " + name
			+ " (no registry entry at " + registryPath(venueScope, name) + ")");
		AString auth = RT.ensureString(RT.getIn(reg, K_AUTH));

		String token = resolveAuthRef(ctx, auth);
		List<Tool> tools = listRemoteTools(url, token);

		// Reconcile: write the fresh set, delete entries no longer served.
		String opsRoot = opsRoot(venueScope, name);
		java.util.Set<String> fresh = new java.util.HashSet<>();
		for (Tool t : tools) fresh.add(t.name());
		ACell existing = readLattice(writeCtx, opsRoot);
		if (existing instanceof AMap<?, ?> em) {
			for (var entry : ((AMap<AString, ACell>) em).entrySet()) {
				if (!fresh.contains(entry.getKey().toString())) {
					deleteLattice(writeCtx, opsRoot + "/" + entry.getKey());
				}
			}
		}
		AVector<ACell> toolNames = writeBridgedOps(writeCtx, opsRoot, name, url, auth, tools);
		writeLattice(writeCtx, registryPath(venueScope, name),
			RT.ensureMap(reg).assoc(Fields.TOTAL, AInteger.create(toolNames.count())));
		return Maps.of(Fields.NAME, Strings.create(name),
			Strings.intern("tools"), toolNames,
			Fields.TOTAL, AInteger.create(toolNames.count()));
	}

	/** Materialises one bridged op per tool; returns the tool names written.
	 *  Tools with path-unsafe names are skipped with a warning log. */
	private AVector<ACell> writeBridgedOps(RequestContext writeCtx, String opsRoot,
			String serverName, AString url, AString auth, List<Tool> tools) {
		AVector<ACell> written = Vectors.empty();
		for (Tool tool : tools) {
			String toolName = tool.name();
			if (toolName == null || !toolName.matches("[A-Za-z0-9_-]{1,128}")) {
				log.warn("Skipping MCP tool with path-unsafe name from '{}': {}", serverName, toolName);
				continue;
			}
			AMap<AString, ACell> opMeta = buildBridgedOpMeta(serverName, url, auth, tool);
			writeLattice(writeCtx, opsRoot + "/" + toolName, opMeta);
			written = written.conj(Strings.create(toolName));
		}
		return written;
	}

	/**
	 * The bridged-op asset metadata (#80) — self-contained: dispatchable with
	 * no registry lookup, hand-authorable without a registry at all. The
	 * op's declared input IS the tool's own schema, so bridged tools are
	 * indistinguishable from native ops to schema validation, agent tool
	 * palettes and the LLM tool conversion. MCP annotations are preserved
	 * verbatim under {@code mcp.annotations} — advisory display data only
	 * (server-asserted hints must never widen a capability decision).
	 */
	@SuppressWarnings("unchecked")
	AMap<AString, ACell> buildBridgedOpMeta(String serverName, AString url, AString auth, Tool tool) {
		String display = (tool.title() != null) ? tool.title()
			: (tool.annotations() != null && tool.annotations().title() != null)
				? tool.annotations().title() : tool.name();
		String desc = (tool.description() != null ? tool.description() : "")
			+ "\n\n[Bridged from MCP server '" + serverName + "']";

		AMap<AString, ACell> operation = Maps.of(
			Fields.ADAPTER, Strings.intern("mcp:tools:call"),
			Fields.REMOTE_TOOL_NAME, Strings.create(tool.name()),
			Fields.SERVER, url,
			Fields.INPUT, getInputSchema(tool.inputSchema()));
		if (auth != null) operation = operation.assoc(K_AUTH, auth);
		if (tool.outputSchema() != null && !tool.outputSchema().isEmpty()) {
			operation = operation.assoc(Fields.OUTPUT, getInputSchema(tool.outputSchema()));
		}

		AMap<AString, ACell> meta = Maps.of(
			Fields.NAME, Strings.create(display),
			Fields.DESCRIPTION, Strings.create(desc),
			Fields.OPERATION, operation);
		if (tool.annotations() != null) {
			AMap<AString, ACell> ann = Maps.empty();
			var a = tool.annotations();
			if (a.title() != null) ann = ann.assoc(Strings.intern("title"), Strings.create(a.title()));
			if (a.readOnlyHint() != null) ann = ann.assoc(Strings.intern("readOnlyHint"), convex.core.data.prim.CVMBool.of(a.readOnlyHint()));
			if (a.destructiveHint() != null) ann = ann.assoc(Strings.intern("destructiveHint"), convex.core.data.prim.CVMBool.of(a.destructiveHint()));
			if (a.idempotentHint() != null) ann = ann.assoc(Strings.intern("idempotentHint"), convex.core.data.prim.CVMBool.of(a.idempotentHint()));
			if (a.openWorldHint() != null) ann = ann.assoc(Strings.intern("openWorldHint"), convex.core.data.prim.CVMBool.of(a.openWorldHint()));
			if (ann.count() > 0) meta = meta.assoc(Strings.intern("mcp"),
				Maps.of(Strings.intern("annotations"), ann));
		}
		return meta;
	}

	/** Lists a remote server's tools over a pooled session. */
	private List<Tool> listRemoteTools(AString url, String token) throws Exception {
		McpClientSession session = getOrConnect(url.toString(), token);
		try {
			return session.getClient().listTools().tools();
		} catch (Exception e) {
			session.invalidate();
			throw new JobFailedException("Cannot list tools from MCP server at " + url
				+ ": " + e.getMessage());
		}
	}

	// ---- scope / path / lattice helpers ----

	private static String requireServerName(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || !name.toString().matches("[a-z0-9-]{1,64}")) {
			throw new IllegalArgumentException(
				"Server name is required and must match [a-z0-9-]{1,64}");
		}
		return name.toString();
	}

	/** Venue scope requires the {@code mcp/manage} ability on {@code v/mcp} —
	 *  denied under the public ceiling, grantable by cap. User scope is the
	 *  default and needs nothing beyond invoking the op. */
	private static boolean isVenueScope(RequestContext ctx, ACell input) {
		boolean venueScope = SCOPE_VENUE.equals(RT.ensureString(RT.getIn(input, K_SCOPE)));
		if (venueScope) ctx.requireCapability("v/mcp", "mcp/manage");
		return venueScope;
	}

	private static String opsRoot(boolean venueScope, String name) {
		return (venueScope ? "v/ops/mcp/" : "o/mcp/") + name;
	}

	private static String registryPath(boolean venueScope, String name) {
		return (venueScope ? "v/mcp/servers/" : "w/mcp/servers/") + name;
	}

	/** Bare {@code s/} refs are stored DID-qualified to the registrar, so the
	 *  persisted reference names one unambiguous owner. DID-qualified refs and
	 *  literals pass through unchanged. */
	private static AString qualifyAuthRef(AString auth, RequestContext ctx) {
		if (auth == null) return null;
		String ref = auth.toString();
		if ((ref.startsWith("s/") || ref.startsWith("/s/")) && ctx.getCallerDID() != null) {
			String name = ref.startsWith("/s/") ? ref.substring(3) : ref.substring(2);
			return Strings.create(ctx.getCallerDID() + "/s/" + name);
		}
		return auth;
	}

	private void writeLattice(RequestContext ctx, String path, ACell value) {
		engine.jobs().invokeInternal(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value), ctx).join();
	}

	private void deleteLattice(RequestContext ctx, String path) {
		engine.jobs().invokeInternal(Strings.create("v/ops/covia/delete"),
			Maps.of(Fields.PATH, Strings.create(path)), ctx).join();
	}

	private ACell readLattice(RequestContext ctx, String path) {
		try {
			ACell result = engine.jobs().invokeInternal(Strings.create("v/ops/covia/read"),
				Maps.of(Fields.PATH, Strings.create(path)), ctx).join();
			return RT.getIn(result, Fields.VALUE);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Close all persistent client sessions. Should be called during shutdown.
	 */
	public void close() {
		for (McpClientSession session : clientSessions.values()) {
			session.close();
		}
		clientSessions.clear();
	}
	
}
