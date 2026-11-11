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
import covia.exception.CoviaException;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.exception.JobFailedException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.api.Abilities;
import covia.lattice.CoviaPath;
import covia.venue.Engine;
import covia.venue.RequestContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
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
		// The adapter's own skill: v/skills/mcp lives and dies with this adapter.
		installSkill("ops-tools/mcp", "/skills/mcp.json");
		TOOL_CALL  = installAsset("mcp/tools-call", "/adapters/mcp/toolCall.json");
		TOOLS_LIST = installAsset("mcp/tools-list", "/adapters/mcp/toolList.json");
		installAsset("mcp/add-server",    "/adapters/mcp/addServer.json");
		installAsset("mcp/add-tool",      "/adapters/mcp/addTool.json");
		installAsset("mcp/remove-server", "/adapters/mcp/removeServer.json");
		installAsset("mcp/refresh",       "/adapters/mcp/refresh.json");
		installAsset("mcp/create-token",  "/adapters/mcp/createToken.json");
		installAsset("mcp/update-token",  "/adapters/mcp/updateToken.json");
		installAsset("mcp/delete-token",  "/adapters/mcp/deleteToken.json");
		installAsset("mcp/list-tokens",   "/adapters/mcp/listTokens.json");
		installAsset("mcp/get-token",     "/adapters/mcp/getToken.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// getSubOperation returns everything after "mcp:", e.g. "tools:call" or "tools:list"
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			throw new InvalidDefinitionException("Insufficient specification for MCP operation");
		}

		String[] subParts = subOp.split(":");
		String feature = subParts[0];

		if (feature.equals("tools")) {
			if (subParts.length < 2) {
				throw new InvalidDefinitionException("MCP tools operation requires function (call/list)");
			}
			String function = subParts[1];

			if (function.equals("call")) {
				// Standard MCP tool call
				return CompletableFuture.supplyAsync(() -> {
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
						throw new InvalidInputException("No remote tool name provided (either in input or operation metadata)");
					}

					// Get MCP server URL from metadata or input
					AString serverUrl =getServerUrl(meta, input);
					if (serverUrl == null) {
						throw new InvalidInputException("No server URL provided in input (or asset metadata fallback)");
					}
					// The caller may name the server: the same outbound policy as http.
					serverUrl = Strings.create(engine.outboundUrl(ctx, serverUrl.toString()));

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
						throw new InvalidInputException("Tool call requires arguments as a JSON object");
					}

					// Access token: explicit input wins; else the operation
					// metadata's auth reference (secret ref, possibly
					// DID-qualified) resolved at call time — never persisted
					// as a raw value in the bridged asset.
					AString token=RT.getIn(input, Fields.TOKEN);
					String accessToken=(token!=null)?token.toString()
						: resolveAuthRef(ctx, RT.ensureString(
							RT.getIn(meta, Fields.OPERATION, K_AUTH)), serverUrl);

					// Make the MCP tool call
					return callMCPTool(serverUrl, remoteToolName.toString(), toolArguments, accessToken);
				}, VIRTUAL_EXECUTOR);

			} else if (function.equals("list")) {
				// List available MCP tools
				return CompletableFuture.supplyAsync(() -> {
					// Get MCP server URL from metadata or input
					AString serverUrl = getServerUrl(meta, input);
					if (serverUrl == null) {
						throw new InvalidInputException("No server URL provided in input (or asset metadata fallback)");
					}
					serverUrl = Strings.create(engine.outboundUrl(ctx, serverUrl.toString()));

					// Get API access token, if provided
					AString token = RT.getIn(input, Fields.TOKEN);
					String accessToken = (token == null) ? null : token.toString();

					// List the MCP tools
					return listMCPTools(serverUrl, accessToken);
				}, VIRTUAL_EXECUTOR);
			} else {
				throw new NotSupportedException("Unsupported tools function: " + function);
			}
		} else if (feature.equals("server")) {
			// Server bridging (#80): register / refresh / remove external MCP
			// servers whose tools materialise as ordinary catalog operations.
			if (subParts.length < 2) {
				throw new InvalidDefinitionException("MCP server operation requires function (add/remove/refresh)");
			}
			String function = subParts[1];
			return CompletableFuture.supplyAsync(() -> switch (function) {
				case "add" -> handleAddServer(ctx, input);
				case "remove" -> handleRemoveServer(ctx, input);
				case "refresh" -> handleRefresh(ctx, input);
				default -> throw new NotSupportedException(
					"Unsupported server function: " + function);
			}, VIRTUAL_EXECUTOR);
		} else if (feature.equals("tool")) {
			// Tool-level bridging (#80): curate individual tools from any
			// server at any catalog path — the tool is the entity, the server
			// is just where it happens to live.
			if (subParts.length < 2) {
				throw new InvalidDefinitionException("MCP tool operation requires function (add)");
			}
			String function = subParts[1];
			return CompletableFuture.supplyAsync(() -> switch (function) {
				case "add" -> handleAddTool(ctx, input);
				default -> throw new NotSupportedException(
					"Unsupported tool function: " + function);
			}, VIRTUAL_EXECUTOR);
		} else if (feature.equals("token")) {
			// Scoped bearer tokens for the venue's own /mcp endpoint (MCP_TOKENS.md).
			if (subParts.length < 2) {
				throw new InvalidDefinitionException("MCP token operation requires function (create/update/delete/list/get)");
			}
			String function = subParts[1];
			return CompletableFuture.supplyAsync(() -> switch (function) {
				case "create" -> handleCreateToken(ctx, input);
				case "update" -> handleUpdateToken(ctx, input);
				case "delete" -> handleDeleteToken(ctx, input);
				case "list" -> handleListTokens(ctx);
				case "get" -> handleGetToken(ctx, input);
				default -> throw new NotSupportedException(
					"Unsupported token function: " + function);
			}, VIRTUAL_EXECUTOR);
		} else {
			throw new NotSupportedException("Unsupported MCP feature: " + feature);
		}
	}

	/**
	 * Extracts the MCP server URL from metadata or input parameters.
	 */
	private AString getServerUrl(AMap<AString, ACell> meta, ACell input) {
		// First try to get from input parameters
		AString url = RT.ensureString(RT.getIn(input, Fields.SERVER));
		if (url != null) return checkedUrl(url, InvalidInputException::new);

		// Then check metadata — the operation block (bridged ops, #80), then
		// the legacy top-level position.
		if (meta != null) {
			url = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.SERVER));
			if (url != null) return checkedUrl(url, InvalidDefinitionException::new);
			url = RT.ensureString(meta.get(Fields.SERVER));
			if (url != null) return checkedUrl(url, InvalidDefinitionException::new);
		}

		return null;
	}

	/**
	 * A server URL that names an MCP endpoint. {@link McpClientSession} refuses
	 * a malformed one without knowing where it came from; {@code refusal} says
	 * whose it is to fix: the call's input, or a stored definition (a bridged
	 * operation or a server registration).
	 */
	private static AString checkedUrl(AString url,
			java.util.function.BiFunction<String, Throwable, ? extends CoviaException> refusal) {
		try {
			McpClientSession.endpointUrl(url.toString());
			return url;
		} catch (IllegalArgumentException e) {
			throw refusal.apply(e.getMessage(), e);
		}
	}

	/**
	 * Resolves an operation-metadata auth reference to an access token (#80).
	 * Accepted forms:
	 * <ul>
	 *   <li>{@code s/NAME} / {@code /s/NAME} — resolved in the CALLER's secret
	 *       store (per-caller credentials for a shared bridge)</li>
	 *   <li>{@code did:…/s/NAME} — DID-qualified secret location, resolved in
	 *       that identity's store under a {@code secret/use} grant to the
	 *       caller, which may be bound to the server url ({@code nb.url}).</li>
	 *   <li>anything else — treated as a literal token (works, discouraged —
	 *       same stance as raw {@code apiKey} in agent config)</li>
	 * </ul>
	 * Fail-closed: a reference that names a missing secret throws rather than
	 * silently connecting unauthenticated.
	 */
	String resolveAuthRef(RequestContext ctx, AString auth, AString serverUrl) {
		if (auth == null) return null;
		String ref = auth.toString();
		if (!Engine.isSecretRef(ref)) return ref; // literal token
		String value = engine.resolveSecret(ref, ctx, serverUrl != null ? serverUrl.toString() : null);
		if (value == null) throw new NotFoundException("MCP server auth secret not found: " + ref, ref);
		return value;
	}
	
	/**
	 * Get or create a persistent session for the given server URL and token.
	 */
	private McpClientSession getOrConnect(String serverUrl, String accessToken) {
		String endpointUrl = McpClientSession.endpointUrl(serverUrl);
		String key = endpointUrl + "|" + (accessToken != null ? accessToken : "");
		return clientSessions.computeIfAbsent(key, k -> new McpClientSession(endpointUrl, accessToken));
	}

	/**
	 * Connect to an MCP server via a base URL. Returns a connected client
	 * from the session pool.
	 * @param baseURL Server base URL
	 * @param accessToken Optional bearer token
	 * @return McpSyncClient instance (session-managed)
	 * @throws CoviaException if the session cannot be opened
	 */
	public McpSyncClient connect(String baseURL, String accessToken) {
		return getOrConnect(baseURL, accessToken).getClient();
	}

	/**
	 * Makes an MCP tool call to the specified server using a persistent session.
	 *
	 * <p>Failure surfacing is designed for LLM consumption (agents call bridged
	 * tools directly): a transport or protocol failure is the Covia type for
	 * it, naming the server and the tool and never quoting the server
	 * ({@link McpClientSession#failure}); a tool-level error ({@code isError:
	 * true} per the MCP spec) fails the job with the remote error text so the
	 * model can self-correct — never a silent success with an error-shaped
	 * payload.</p>
	 *
	 * @param serverUrl MCP server URL
	 * @param toolName Tool name to call
	 * @param input Tool arguments
	 * @param accessToken Optional access token
	 */
	public ACell callMCPTool(AString serverUrl, String toolName, ACell input, String accessToken) {
		McpClientSession session = getOrConnect(serverUrl.toString(), accessToken);

		@SuppressWarnings("unchecked")
		CallToolRequest request = CallToolRequest.builder(toolName)
			.arguments((Map<String,Object>)JSON.json(RT.castMap(input)))
			.build();

		CallToolResult response = session.request(McpClientSession.TOOL_CALL.formatted(toolName),
			client -> client.callTool(request));

		// Tool-level error: the session is healthy, the TOOL reported failure.
		String err = errorText(response);
		if (err != null) {
			throw new JobFailedException("MCP tool '" + toolName + "' reported an error: " + err);
		}
		return successValue(response);
	}

	/**
	 * Extracts the error text from an MCP tool result, or null when the result
	 * is not an error. Best-effort: prefers {@code structuredContent.message},
	 * falls back to concatenated text content blocks.
	 */
	static String errorText(CallToolResult response) {
		if (!Boolean.TRUE.equals(response.isError())) return null;
		Object structured = response.structuredContent();
		if (structured instanceof Map<?, ?> m) {
			Object msg = m.get("message");
			if (msg != null) return msg.toString();
		}
		String text = contentText(response);
		if (text != null) return text;
		if (structured != null) return String.valueOf(structured);
		return "(no error detail provided by the server)";
	}

	/**
	 * Extracts the success value from an MCP tool result: structured content
	 * when the server provides it, else the text content blocks (one string,
	 * or a vector of strings for multi-block results) — most external MCP
	 * servers return text-only results, which must not be dropped.
	 */
	static ACell successValue(CallToolResult response) {
		Object structured = response.structuredContent();
		if (structured != null) return RT.cvm(structured);
		List<io.modelcontextprotocol.spec.McpSchema.Content> content = response.content();
		if (content == null || content.isEmpty()) return null;
		AVector<ACell> texts = Vectors.empty();
		for (var block : content) {
			if (block instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc && tc.text() != null) {
				texts = texts.conj(Strings.create(tc.text()));
			}
		}
		if (texts.count() == 0) return null;
		return (texts.count() == 1) ? texts.get(0) : texts;
	}

	/** Concatenated text content blocks, or null if there are none. */
	private static String contentText(CallToolResult response) {
		List<io.modelcontextprotocol.spec.McpSchema.Content> content = response.content();
		if (content == null) return null;
		StringBuilder sb = new StringBuilder();
		for (var block : content) {
			if (block instanceof io.modelcontextprotocol.spec.McpSchema.TextContent tc && tc.text() != null) {
				if (sb.length() > 0) sb.append('\n');
				sb.append(tc.text());
			}
		}
		return (sb.length() > 0) ? sb.toString() : null;
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
	 * @throws CoviaException if the server cannot be reached or does not list its tools
	 */
	public ACell listMCPTools(AString serverUrl, String accessToken) {
		List<Tool> tools = listRemoteTools(serverUrl, accessToken);

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
	ACell handleAddServer(RequestContext ctx, ACell input) {
		String name = requireServerName(input);
		AString url = RT.ensureString(RT.getIn(input, K_URL));
		if (url == null) throw new InvalidInputException("url is required");
		checkedUrl(url, InvalidInputException::new);
		AString auth = RT.ensureString(RT.getIn(input, K_AUTH));
		boolean venueScope = isVenueScope(ctx, input);

		// SSRF guard — shared with the http adapter, including its allowlist.
		url = Strings.create(engine.outboundUrl(ctx, url.toString()));

		// Discover tools under the REGISTRAR's auth (resolved now, not stored raw)
		String token = resolveAuthRef(ctx, auth, url);
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
			Strings.intern("added"), engine.currentTimestamp(),
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

	/**
	 * Curates ONE tool from an MCP server at a caller-chosen catalog path
	 * (#80 — the tool is the entity, the server is just where it lives).
	 * Registry-free: the bridged asset is self-contained, so groups are just
	 * catalog paths — {@code o/research/search_papers} and
	 * {@code o/research/github_search} can point at different servers with
	 * different auth. Paths under the caller's {@code o/} need nothing extra;
	 * {@code v/ops/...} requires the {@code mcp/manage} ability. Removal is
	 * plain {@code covia:delete} on the path — nothing resurrects it.
	 */
	ACell handleAddTool(RequestContext ctx, ACell input) {
		AString url = RT.ensureString(RT.getIn(input, Fields.SERVER));
		if (url == null) throw new InvalidInputException(
			"server is required — the MCP server's base URL, e.g. https://host/mcp");
		checkedUrl(url, InvalidInputException::new);
		AString toolName = RT.ensureString(RT.getIn(input, Fields.TOOL));
		if (toolName == null || !toolName.toString().matches("[A-Za-z0-9_-]{1,128}")) {
			throw new InvalidInputException(
				"tool is required — the remote tool's name exactly as the server lists it"
				+ " (see v/ops/mcp/tools-list)");
		}
		String path = requireToolPath(input);
		boolean venuePath = path.startsWith("v/");
		if (venuePath) requireVenueManage(ctx);

		// SSRF guard — shared with the http adapter, including its allowlist.
		url = Strings.create(engine.outboundUrl(ctx, url.toString()));

		AString auth = RT.ensureString(RT.getIn(input, K_AUTH));
		String token = resolveAuthRef(ctx, auth, url);
		List<Tool> tools = listRemoteTools(url, token);
		Tool tool = null;
		for (Tool t : tools) {
			if (toolName.toString().equals(t.name())) { tool = t; break; }
		}
		if (tool == null) {
			throw new NotFoundException("Tool '" + toolName + "' not found on MCP server at "
				+ origin(url.toString()) + ". Available tools: " + availableNames(tools), toolName.toString());
		}

		AString storedAuth = qualifyAuthRef(auth, ctx);
		AString nameOverride = RT.ensureString(RT.getIn(input, Fields.NAME));
		AString descOverride = RT.ensureString(RT.getIn(input, Fields.DESCRIPTION));
		AMap<AString, ACell> opMeta = buildBridgedOpMeta(
			"Bridged from MCP server at " + url, url, storedAuth, tool, nameOverride, descOverride);

		// Argument defaults: stored on the op (generic dispatch-time merge —
		// JobManager.applyDefaults); defaulted keys leave the declared
		// schema's required list so callers know they may omit them.
		ACell defsCell = RT.getIn(input, Fields.DEFAULT);
		if (defsCell != null) {
			AMap<AString, ACell> defaults = RT.ensureMap(defsCell);
			if (defaults == null) throw new InvalidInputException(
				"default must be an object of argument values to fill in when the caller omits them");
			if (defaults.count() > 0) opMeta = withDefaults(opMeta, defaults);
		}

		RequestContext writeCtx = venuePath ? engine.venueContext() : ctx;
		writeLattice(writeCtx, path, opMeta);

		AMap<AString, ACell> result = Maps.of(
			Fields.TOOL, Strings.create(tool.name()),
			Fields.PATH, Strings.create(path),
			Fields.SERVER, url);
		AString authWarn = rawAuthWarning(auth);
		if (authWarn != null) {
			result = result.assoc(Fields.WARNINGS, Vectors.of(authWarn));
		}
		return result;
	}

	/** The catalog destination for a curated tool: under the caller's
	 *  {@code o/} or the venue's {@code v/ops/}, naming the op itself. */
	private static String requireToolPath(ACell input) {
		AString p = RT.ensureString(RT.getIn(input, Fields.PATH));
		if (p == null) throw new InvalidInputException(
			"path is required — the catalog location for the bridged tool,"
			+ " e.g. o/research/search_papers (your own operations)"
			+ " or v/ops/... (venue catalog, needs the mcp/manage ability)");
		String path = p.toString();
		boolean ok = (path.startsWith("o/") && path.length() > 2 && !path.endsWith("/"))
			|| (path.startsWith("v/ops/") && path.length() > 6 && !path.endsWith("/"));
		if (!ok) throw new InvalidInputException(
			"path must be under o/ (your operations) or v/ops/ (venue catalog),"
			+ " naming the op itself — e.g. o/research/search_papers. Got: " + path);
		return path;
	}

	/** Tool names for a not-found error — enough for an LLM to self-correct,
	 *  capped so a huge server doesn't flood the message. */
	private static String availableNames(List<Tool> tools) {
		if (tools.isEmpty()) return "(none — the server lists no tools)";
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Tool t : tools) {
			if (n >= 40) {
				sb.append(", … (").append(tools.size() - 40)
					.append(" more — list them all with v/ops/mcp/tools-list)");
				break;
			}
			if (n++ > 0) sb.append(", ");
			sb.append(t.name());
		}
		return sb.toString();
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

	/**
	 * Refreshes bridged tools, in one of two modes with deliberately different
	 * semantics:
	 * <ul>
	 *   <li>{@code name} — MIRROR refresh of a registered server: the catalog
	 *       subtree mirrors the server, so reconcile fully — new tools added,
	 *       changed ones rewritten, vanished ones deleted.</li>
	 *   <li>{@code path} — CURATED refresh of hand-picked tools (any catalog
	 *       path, may span servers): schemas and annotations update in place;
	 *       name/description are owner-editable and left untouched; a tool the
	 *       server no longer offers is REPORTED, never deleted — the owner
	 *       picked it, removal is their call via covia:delete.</li>
	 * </ul>
	 */
	ACell handleRefresh(RequestContext ctx, ACell input) {
		AString path = RT.ensureString(RT.getIn(input, Fields.PATH));
		AString nameArg = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (path != null && nameArg != null) throw new InvalidInputException(
			"Provide either name (refresh a mirrored server) or path (refresh curated tools), not both");
		if (path != null) return refreshPath(ctx, path.toString());

		// ---- mirror mode: reconcile a registered server's subtree ----
		String name = requireServerName(input);
		boolean venueScope = isVenueScope(ctx, input);
		RequestContext writeCtx = venueScope ? engine.venueContext() : ctx;

		ACell reg = readLattice(writeCtx, registryPath(venueScope, name));
		AString url = RT.ensureString(RT.getIn(reg, K_URL));
		if (url == null) throw new NotFoundException("Unknown MCP server: " + name
			+ " (no registry entry at " + registryPath(venueScope, name) + ")", name);
		checkedUrl(url, InvalidDefinitionException::new);
		AString auth = RT.ensureString(RT.getIn(reg, K_AUTH));

		String token = resolveAuthRef(ctx, auth, url);
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

	/**
	 * Curated refresh: walk the bridged ops under a path (one op or a whole
	 * group), re-list each referenced server ONCE, update schemas in place.
	 * A dead server fails its own group and is reported in {@code errors};
	 * other groups still refresh. Fails outright only when nothing could be
	 * refreshed at all.
	 */
	@SuppressWarnings("unchecked")
	ACell refreshPath(RequestContext ctx, String path) {
		boolean venuePath = path.startsWith("v/");
		if (venuePath) requireVenueManage(ctx);
		RequestContext writeCtx = venuePath ? engine.venueContext() : ctx;

		ACell root = readLattice(writeCtx, path);
		java.util.LinkedHashMap<String, AMap<AString, ACell>> ops = new java.util.LinkedHashMap<>();
		collectBridgedOps(root, path, ops, 0);
		if (ops.isEmpty()) throw new NotFoundException(
			"No bridged MCP tools found at path: " + path
			+ " — bridged ops carry operation.adapter = mcp:tools:call;"
			+ " curate one with v/ops/mcp/add-tool", path);

		// Group by server + auth so each server is listed once
		java.util.LinkedHashMap<String, java.util.List<String>> groups = new java.util.LinkedHashMap<>();
		for (var entry : ops.entrySet()) {
			AMap<AString, ACell> meta = entry.getValue();
			String server = RT.getIn(meta, Fields.OPERATION, Fields.SERVER).toString();
			ACell auth = RT.getIn(meta, Fields.OPERATION, K_AUTH);
			String key = server + "|" + (auth != null ? auth.toString() : "");
			groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(entry.getKey());
		}

		long updated = 0, unchanged = 0;
		AVector<ACell> missing = Vectors.empty();
		AVector<ACell> errors = Vectors.empty();
		for (var group : groups.entrySet()) {
			String firstOp = group.getValue().get(0);
			AMap<AString, ACell> firstMeta = ops.get(firstOp);
			AString url = RT.ensureString(RT.getIn(firstMeta, Fields.OPERATION, Fields.SERVER));
			AString auth = RT.ensureString(RT.getIn(firstMeta, Fields.OPERATION, K_AUTH));

			java.util.Map<String, Tool> fresh = new java.util.HashMap<>();
			try {
				String token = resolveAuthRef(ctx, auth, url);
				for (Tool t : listRemoteTools(url, token)) fresh.put(t.name(), t);
			} catch (CoviaException e) {
				errors = errors.conj(Maps.of(
					Fields.SERVER, url,
					Fields.ERROR, Strings.create(describeFailure(e))));
				continue;
			}

			for (String opPath : group.getValue()) {
				AMap<AString, ACell> meta = ops.get(opPath);
				String remoteName = RT.getIn(meta, Fields.OPERATION, Fields.REMOTE_TOOL_NAME).toString();
				Tool tool = fresh.get(remoteName);
				if (tool == null) {
					log.warn("Curated MCP tool '{}' at {} no longer offered by {}", remoteName, opPath, url);
					missing = missing.conj(Maps.of(
						Fields.PATH, Strings.create(opPath),
						Fields.TOOL, Strings.create(remoteName),
						Fields.SERVER, url));
					continue;
				}
				AMap<AString, ACell> operation = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
				AMap<AString, ACell> newOp = operation.assoc(Fields.INPUT, getInputSchema(tool.inputSchema()));
				if (tool.outputSchema() != null && !tool.outputSchema().isEmpty()) {
					newOp = newOp.assoc(Fields.OUTPUT, getInputSchema(tool.outputSchema()));
				} else {
					newOp = (AMap<AString, ACell>) newOp.dissoc(Fields.OUTPUT);
				}
				// Argument defaults survive refresh (they live in the operation
				// map) — re-subtract them from the fresh schema's required list.
				AMap<AString, ACell> defs = RT.ensureMap(RT.getIn(newOp, Fields.DEFAULT));
				if (defs != null && defs.count() > 0) {
					newOp = newOp.assoc(Fields.INPUT,
						subtractRequired(RT.getIn(newOp, Fields.INPUT), defs));
				}
				AMap<AString, ACell> newMeta = meta.assoc(Fields.OPERATION, newOp);
				newMeta = withMcpAnnotations(newMeta, tool);
				if (newMeta.equals(meta)) {
					unchanged++;
				} else {
					writeLattice(writeCtx, opPath, newMeta);
					updated++;
				}
			}
		}

		if (updated == 0 && unchanged == 0 && missing.count() == 0) {
			throw new UnavailableException("Could not refresh any bridged tools at " + path
				+ ": " + JSON.print(errors));
		}
		AMap<AString, ACell> result = Maps.of(
			Fields.PATH, Strings.create(path),
			Fields.TOTAL, AInteger.create(ops.size()),
			Strings.intern("updated"), AInteger.create(updated),
			Strings.intern("unchanged"), AInteger.create(unchanged));
		if (missing.count() > 0) result = result.assoc(Strings.intern("missing"), missing);
		if (errors.count() > 0) result = result.assoc(Strings.intern("errors"), errors);
		return result;
	}

	/** Recursively collects bridged ops (operation.adapter = mcp:tools:call
	 *  with a pinned remoteToolName + server) under a catalog value. */
	@SuppressWarnings("unchecked")
	private static void collectBridgedOps(ACell node, String path,
			java.util.Map<String, AMap<AString, ACell>> out, int depth) {
		if (depth > 16 || !(node instanceof AMap<?, ?>)) return;
		AMap<AString, ACell> m = (AMap<AString, ACell>) node;
		ACell adapter = RT.getIn(m, Fields.OPERATION, Fields.ADAPTER);
		if (adapter != null && adapter.toString().startsWith("mcp:tools:call")
				&& RT.getIn(m, Fields.OPERATION, Fields.REMOTE_TOOL_NAME) != null
				&& RT.getIn(m, Fields.OPERATION, Fields.SERVER) != null) {
			out.put(path, m);
			return;
		}
		for (var entry : m.entrySet()) {
			if (!(entry.getKey() instanceof AString key)) continue;
			collectBridgedOps(entry.getValue(), path + "/" + key, out, depth + 1);
		}
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
	AMap<AString, ACell> buildBridgedOpMeta(String serverName, AString url, AString auth, Tool tool) {
		return buildBridgedOpMeta("Bridged from MCP server '" + serverName + "'",
			url, auth, tool, null, null);
	}

	AMap<AString, ACell> buildBridgedOpMeta(String provenance, AString url, AString auth, Tool tool,
			AString nameOverride, AString descOverride) {
		String display = (nameOverride != null) ? nameOverride.toString()
			: (tool.title() != null) ? tool.title()
			: (tool.annotations() != null && tool.annotations().title() != null)
				? tool.annotations().title() : tool.name();
		String baseDesc = (descOverride != null) ? descOverride.toString()
			: (tool.description() != null ? tool.description() : "");
		String desc = baseDesc + "\n\n[" + provenance + "]";

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
		return withMcpAnnotations(meta, tool);
	}

	/** Attaches argument defaults to a bridged op: {@code operation.default}
	 *  (merged under caller input at dispatch — JobManager.applyDefaults) and
	 *  drops defaulted keys from the declared schema's {@code required} list.
	 *  Values may be any type. Purpose-shaping, not policy — callers can
	 *  override; enforcement is a capability gate's job. */
	static AMap<AString, ACell> withDefaults(AMap<AString, ACell> meta, AMap<AString, ACell> defaults) {
		AMap<AString, ACell> operation = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
		operation = operation.assoc(Fields.DEFAULT, defaults);
		operation = operation.assoc(Fields.INPUT,
			subtractRequired(RT.getIn(operation, Fields.INPUT), defaults));
		return meta.assoc(Fields.OPERATION, operation);
	}

	/** Removes defaulted keys from a JSON schema's {@code required} list —
	 *  the caller may omit them. The properties stay declared (overridable). */
	@SuppressWarnings("unchecked")
	static ACell subtractRequired(ACell schema, AMap<AString, ACell> defaults) {
		AMap<AString, ACell> s = RT.ensureMap(schema);
		if (s == null) return schema;
		ACell reqCell = s.get(Strings.intern("required"));
		if (!(reqCell instanceof AVector<?> req)) return schema;
		AVector<ACell> remaining = Vectors.empty();
		for (long i = 0; i < req.count(); i++) {
			ACell k = req.get(i);
			if (k instanceof AString ks && defaults.containsKey(ks)) continue;
			remaining = remaining.conj(k);
		}
		if (remaining.count() == req.count()) return schema;
		return (remaining.count() > 0)
			? s.assoc(Strings.intern("required"), remaining)
			: (AMap<AString, ACell>) s.dissoc(Strings.intern("required"));
	}

	/** Attaches the tool's MCP annotations under {@code mcp.annotations}
	 *  (advisory display data only — never widens a capability decision).
	 *  When the tool carries none, the metadata is left untouched — on
	 *  refresh this preserves owner edits rather than stripping them. */
	private static AMap<AString, ACell> withMcpAnnotations(AMap<AString, ACell> meta, Tool tool) {
		if (tool.annotations() == null) return meta;
		AMap<AString, ACell> ann = Maps.empty();
		var a = tool.annotations();
		if (a.title() != null) ann = ann.assoc(Strings.intern("title"), Strings.create(a.title()));
		if (a.readOnlyHint() != null) ann = ann.assoc(Strings.intern("readOnlyHint"), convex.core.data.prim.CVMBool.of(a.readOnlyHint()));
		if (a.destructiveHint() != null) ann = ann.assoc(Strings.intern("destructiveHint"), convex.core.data.prim.CVMBool.of(a.destructiveHint()));
		if (a.idempotentHint() != null) ann = ann.assoc(Strings.intern("idempotentHint"), convex.core.data.prim.CVMBool.of(a.idempotentHint()));
		if (a.openWorldHint() != null) ann = ann.assoc(Strings.intern("openWorldHint"), convex.core.data.prim.CVMBool.of(a.openWorldHint()));
		if (ann.count() == 0) return meta;
		return meta.assoc(Strings.intern("mcp"), Maps.of(Strings.intern("annotations"), ann));
	}

	/** Lists a remote server's tools over a pooled session. */
	private List<Tool> listRemoteTools(AString url, String token) {
		return getOrConnect(url.toString(), token)
			.request(McpClientSession.TOOLS_LIST, client -> client.listTools().tools());
	}

	// ---- scope / path / lattice helpers ----

	private static String requireServerName(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || !name.toString().matches("[a-z0-9-]{1,64}")) {
			throw new InvalidInputException(
				"Server name is required and must match [a-z0-9-]{1,64}");
		}
		return name.toString();
	}

	/** Venue scope is venue administration (see {@link #requireVenueManage}).
	 *  User scope is the default and needs nothing beyond invoking the op. */
	private boolean isVenueScope(RequestContext ctx, ACell input) {
		boolean venueScope = SCOPE_VENUE.equals(RT.ensureString(RT.getIn(input, K_SCOPE)));
		if (venueScope) requireVenueManage(ctx);
		return venueScope;
	}

	/** Venue-side targets are written as the venue into the shared catalog, so a
	 *  null capability scope is not enough: the caller must be the venue itself
	 *  or present a venue-issued {@code mcp/manage} delegation over
	 *  {@code <venue DID>/v/mcp}. */
	private void requireVenueManage(RequestContext ctx) {
		engine.requireVenueAuthority(ctx, Abilities.V_MCP.toString(), Abilities.MCP_MANAGE);
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
		if ((ref.startsWith("s/") || ref.startsWith("/s/")) && ctx.getUserDID() != null) {
			// Secrets live in the user's store, so qualify against the user.
			String name = ref.startsWith("/s/") ? ref.substring(3) : ref.substring(2);
			return Strings.create(ctx.getUserDID() + "/s/" + name);
		}
		return auth;
	}

	private void writeLattice(RequestContext ctx, String path, ACell value) {
		engine.jobs().invokeInternal(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, storedPath(path), Fields.VALUE, value), ctx).join();
	}

	private void deleteLattice(RequestContext ctx, String path) {
		engine.jobs().invokeInternal(Strings.create("v/ops/covia/delete"),
			Maps.of(Fields.PATH, storedPath(path)), ctx).join();
	}

	/**
	 * Where a change to {@code path} is made. {@code v/} is the venue's
	 * read-only view of its {@code w/global}, so a venue entry is changed
	 * there, by the venue.
	 */
	private static AString storedPath(String path) {
		if (!path.startsWith("v/")) return Strings.create(path);
		return Strings.create(CoviaPath.address(CoviaAdapter.venueGlobals(CoviaPath.parse(path))).substring(1));
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

	// ========== MCP tokens (MCP_TOKENS.md) ==========

	public static final String TOKEN_NAME_INVALID = "name is required and must match [a-z0-9-]{1,64}";
	public static final String TOKEN_NOT_FOUND = "No MCP token named %s";
	public static final String TOKEN_EXISTS = "An MCP token named %s already exists: update or delete it";
	public static final String TOOLS_INVALID = "tools must be an array of operation references, or of objects {operation, name?, description?}";
	public static final String TOOL_NOT_HELD = "tools[%d] names no operation this venue holds: %s";
	public static final String TOOL_NAME_INVALID = "tools[%d] would be named %s, which is not an MCP tool name ([A-Za-z0-9_.-]{1,128}): give it a name";
	public static final String TOOL_NAMES_CLASH = "Two tools would both be named %s: give one of them a name";
	public static final String EXPIRES_INVALID = "expires must be a time still to come, in milliseconds since the epoch";
	public static final String RAW_NOT_KEPT = "This venue keeps only the hashes of MCP tokens, not the tokens (adapters.mcp.keepRawTokens)";
	public static final String RAW_NOT_HELD = "MCP token %s was created or last rotated while the venue kept no tokens: rotate it";
	public static final String TOKEN_SOURCE_NOT_HELD = "MCP token %s can only be changed or read with the authority it was created with";
	public static final String ISSUER_MAY_NOT_CREATE = "%s may not create MCP tokens";
	public static final String KEEP_RAW_NOT_BOOLEAN = "adapters.mcp.keepRawTokens must be a boolean";

	/** Adapter config: whether the venue keeps each token's bearer beside its hash. */
	static final AString KEEP_RAW_TOKENS = Strings.intern("keepRawTokens");

	private static final java.util.regex.Pattern TOKEN_NAME = java.util.regex.Pattern.compile("[a-z0-9-]{1,64}");
	private static final java.util.regex.Pattern MCP_TOOL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,128}");

	/** The venue's MCP tokens. */
	public McpTokens tokens() {
		return new McpTokens(adapterState().cursor());
	}

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		ACell keep = (config == null) ? null : config.get(KEEP_RAW_TOKENS);
		if (keep != null && !(keep instanceof convex.core.data.prim.CVMBool)) throw new IllegalArgumentException(KEEP_RAW_NOT_BOOLEAN);
		// Reconfigured to stop keeping tokens: the kept ones go now (at boot, start does it)
		if (engine != null && !convex.core.data.prim.CVMBool.TRUE.equals(keep)) tokens().dropRaw();
		return true;
	}

	@Override
	public void start() {
		if (!keepRawTokens()) tokens().dropRaw();
	}

	/** Whether the venue keeps each token's bearer beside its hash. */
	boolean keepRawTokens() {
		return convex.core.data.prim.CVMBool.TRUE.equals(engine.adapterConfig(getName()).get(KEEP_RAW_TOKENS));
	}

	@Override
	public void deleteUserState(AString did, covia.venue.AdapterState state) {
		new McpTokens(state.cursor()).deleteOwner(did);
	}

	@Override
	public void deleteAgentState(AString agentDID, covia.venue.AdapterState state) {
		new McpTokens(state.cursor()).deleteIssuer(covia.grid.Principals.userOf(agentDID), agentDID);
	}

	/** The capability resource a token is: {@code x/mcp/tokens/<name>} in its namespace. */
	static AString tokenResource(AString name) {
		return Strings.create("x/mcp/tokens/" + name);
	}

	private static AString requireTokenName(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || !TOKEN_NAME.matcher(name.toString()).matches()) throw new InvalidInputException(TOKEN_NAME_INVALID);
		return name;
	}

	/** The namespace a token request acts in, which its tokens belong to. */
	private static AString tokenOwner(RequestContext ctx) {
		AString owner = ctx.getUserDID();
		if (owner == null) throw new covia.exception.AuthException("Authentication required");
		return owner;
	}

	/**
	 * The tool entries a token offers, as an agent's {@code config.tools}: each
	 * a local operation {@code ctx} may read, under an MCP tool name, no two
	 * alike (ToolPalette's naming).
	 */
	private AVector<ACell> requireTools(RequestContext ctx, ACell input) {
		AVector<ACell> tools = RT.ensureVector(RT.getIn(input, Fields.TOOLS));
		if (tools == null) throw new InvalidInputException(TOOLS_INVALID);
		java.util.Set<String> names = new java.util.HashSet<>();
		for (long i = 0; i < tools.count(); i++) {
			ACell entry = tools.get(i);
			AString[] parsed = covia.adapter.agent.ToolPalette.parseConfigToolEntry(entry);
			if (parsed == null) throw new InvalidInputException(TOOLS_INVALID);
			String name = covia.adapter.agent.ToolPalette.localToolName(engine, ctx, entry);
			if (name == null) throw new InvalidInputException(TOOL_NOT_HELD.formatted(i, parsed[0]));
			if (!MCP_TOOL_NAME.matcher(name).matches()) throw new InvalidInputException(TOOL_NAME_INVALID.formatted(i, name));
			if (!names.add(name)) throw new InvalidInputException(TOOL_NAMES_CLASH.formatted(name));
		}
		return tools;
	}

	/** The expiry the input asks for, or null for never; it must be still to come. */
	private Long expiresFrom(ACell input) {
		ACell raw = RT.getIn(input, Fields.EXPIRES);
		if (raw == null) return null;
		if (!(raw instanceof convex.core.data.prim.CVMLong at) || at.longValue() <= engine.currentTimestamp().longValue()) {
			throw new InvalidInputException(EXPIRES_INVALID);
		}
		return at.longValue();
	}

	/** Caps as a token shows them: {@code "unrestricted"} for none stored. */
	private static ACell shownCaps(AVector<ACell> caps) {
		return (caps == null) ? covia.lattice.Caps.UNRESTRICTED : caps;
	}

	/** What a token's operations return about it; {@code bearer} only when it is handed out. */
	private static AMap<AString, ACell> tokenResult(McpToken token, String bearer) {
		AMap<AString, ACell> result = Maps.of(
			Fields.NAME, token.name(),
			Fields.ISSUER, token.issuer(),
			Fields.TOOLS, token.tools(),
			Fields.CAPS, shownCaps(token.caps().scope()),
			Fields.CREATED, token.record().get(Fields.CREATED));
		if (token.expires() != null) result = result.assoc(Fields.EXPIRES, convex.core.data.prim.CVMLong.create(token.expires()));
		return (bearer == null) ? result : result.assoc(Fields.TOKEN, Strings.create(bearer));
	}

	/** Refuses {@code ctx} unless it holds the authority {@code token} was created with. */
	private void requireTokenSource(RequestContext ctx, McpToken token) {
		if (!engine.holdsAuthorityOf(ctx, new covia.venue.Engine.Source(token.issuer(), token.source()))) {
			throw new covia.exception.AuthException(TOKEN_SOURCE_NOT_HELD.formatted(token.name()));
		}
	}

	/** The fields a new bearer sets on a record: its hash, and itself while the venue keeps tokens. */
	private AMap<AString, ACell> withBearer(AMap<AString, ACell> record, String bearer) {
		record = record.assoc(McpTokens.HASH, McpTokens.hashOf(bearer));
		return keepRawTokens() ? record.assoc(McpTokens.RAW, Strings.create(bearer)) : record.dissoc(McpTokens.RAW);
	}

	/**
	 * Creates a token in the caller's namespace, issued by the source of
	 * authority the request acts with ({@link covia.venue.Engine#actingSource}),
	 * and returns its bearer, this once.
	 */
	ACell handleCreateToken(RequestContext ctx, ACell input) {
		AString name = requireTokenName(input);
		AString owner = tokenOwner(ctx);
		covia.venue.Engine.Source issuer = covia.venue.Engine.actingSource(ctx);
		AString principal = issuer.principal();
		if (principal == null || engine.isPublicPrincipal(principal) || engine.getDIDString().equals(principal)) {
			throw new covia.exception.AuthException(ISSUER_MAY_NOT_CREATE.formatted(principal));
		}
		engine.requireAuthority(ctx, tokenResource(name), Abilities.MCP_TOKEN);
		// A delegate acting for another user holds none of that user's own authority to hand on.
		if (!engine.holdsAuthorityOf(ctx, issuer)) throw new covia.exception.AuthException(ISSUER_MAY_NOT_CREATE.formatted(principal));
		AVector<ACell> tools = requireTools(ctx, input);
		ACell capsInput = RT.getIn(input, Fields.CAPS);
		AVector<ACell> caps = (capsInput == null) ? Vectors.empty() : covia.lattice.Caps.parse(capsInput);
		Long expires = expiresFrom(input);

		String bearer = McpTokens.newBearer();
		AMap<AString, ACell> record = Maps.of(
			Fields.ID, McpTokens.newId(),
			Fields.ISSUER, principal,
			Fields.TOOLS, tools,
			Fields.CREATED, engine.currentTimestamp());
		if (issuer.token() != null) record = record.assoc(Fields.SOURCE, issuer.token());
		if (caps != null) record = record.assoc(Fields.CAPS, caps);
		if (expires != null) record = record.assoc(Fields.EXPIRES, convex.core.data.prim.CVMLong.create(expires));
		record = withBearer(record, bearer);
		if (!tokens().create(owner, name, record)) throw new covia.exception.ConflictException(TOKEN_EXISTS.formatted(name));
		return tokenResult(tokens().token(owner, name), bearer);
	}

	/**
	 * Changes a token's tools, caps or expiry, or gives it a new bearer
	 * ({@code rotate}). Each needs the authority the token was created with,
	 * so no request through a token widens it.
	 */
	ACell handleUpdateToken(RequestContext ctx, ACell input) {
		AString name = requireTokenName(input);
		AString owner = tokenOwner(ctx);
		engine.requireAuthority(ctx, tokenResource(name), Abilities.MCP_TOKEN);
		McpToken token = tokens().token(owner, name);
		if (token.record() == null) throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		requireTokenSource(ctx, token);
		AVector<ACell> tools = (RT.getIn(input, Fields.TOOLS) == null) ? null : requireTools(ctx, input);
		ACell capsInput = RT.getIn(input, Fields.CAPS);
		AVector<ACell> caps = (capsInput == null) ? null : covia.lattice.Caps.parse(capsInput);
		Long expires = expiresFrom(input);
		boolean rotate = convex.core.data.prim.CVMBool.TRUE.equals(RT.getIn(input, Fields.ROTATE));
		String bearer = rotate ? McpTokens.newBearer() : null;
		AString id = token.id();

		AMap<AString, ACell> changed = tokens().update(owner, name, record -> {
			// Changed only if it is still the token the checks above were made on
			if (!java.util.Objects.equals(id, record.get(Fields.ID))) return record;
			AMap<AString, ACell> next = record;
			if (tools != null) next = next.assoc(Fields.TOOLS, tools);
			if (capsInput != null) next = (caps == null) ? next.dissoc(Fields.CAPS) : next.assoc(Fields.CAPS, caps);
			if (expires != null) next = next.assoc(Fields.EXPIRES, convex.core.data.prim.CVMLong.create(expires));
			return (bearer == null) ? next : withBearer(next, bearer);
		});
		if (changed == null || !java.util.Objects.equals(id, changed.get(Fields.ID))) {
			throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		}
		return tokenResult(tokens().token(owner, name), bearer);
	}

	/** Deletes a token: its bearer stops working, and what it narrowed holds nothing. */
	ACell handleDeleteToken(RequestContext ctx, ACell input) {
		AString name = requireTokenName(input);
		AString owner = tokenOwner(ctx);
		engine.requireAuthority(ctx, tokenResource(name), Abilities.MCP_TOKEN);
		if (tokens().delete(owner, name) == null) throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		return Maps.of(Fields.NAME, name, Fields.DELETED, convex.core.data.prim.CVMBool.TRUE);
	}

	/** Lists the tokens in the caller's namespace, never their bearers. */
	ACell handleListTokens(RequestContext ctx) {
		AString owner = tokenOwner(ctx);
		engine.requireAuthority(ctx, Strings.create("x/mcp/tokens"), convex.auth.ucan.Capability.CRUD_READ);
		AVector<ACell> listed = Vectors.empty();
		for (var entry : tokens().list(owner).entrySet()) {
			listed = listed.conj(tokenResult(tokens().token(owner, entry.getKey()), null));
		}
		return Maps.of(Strings.intern("tokens"), listed, Fields.TOTAL, AInteger.create(listed.count()));
	}

	/** A token's bearer again, where the venue keeps it, to whoever may administer it. */
	ACell handleGetToken(RequestContext ctx, ACell input) {
		AString name = requireTokenName(input);
		AString owner = tokenOwner(ctx);
		engine.requireAuthority(ctx, tokenResource(name), Abilities.ADMIN_TOKEN);
		if (!keepRawTokens()) throw new NotSupportedException(RAW_NOT_KEPT);
		McpToken token = tokens().token(owner, name);
		if (token.record() == null) throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		requireTokenSource(ctx, token);
		AString raw = token.raw();
		if (raw == null) throw new NotSupportedException(RAW_NOT_HELD.formatted(name));
		return Maps.of(Fields.NAME, name, Fields.TOKEN, raw);
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
