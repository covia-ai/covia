package covia.venue.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.adapter.AgentAdapter;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Venue;
import covia.lattice.CapabilityChecker;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.SseServer;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.sse.SseHandler;

/**
 * A2A (Agent-to-Agent) server API layered on top of a Covia Venue.
 *
 * <p>Implements the A2A Protocol v1.0 JSON-RPC binding: Agent Card discovery
 * at {@code /.well-known/agent-card.json} and JSON-RPC 2.0 at {@code /a2a}.
 * Supports {@code SendMessage}, {@code GetTask}, {@code CancelTask}, streaming
 * ({@code SendStreamingMessage}, {@code SubscribeToTask}), and
 * {@code GetExtendedAgentCard} (the authenticated agent catalogue, #187).
 * Push-notification-config methods are intentionally unimplemented for now —
 * the handler returns {@code UnsupportedOperationError} when called.</p>
 *
 * <p>The venue is modelled as a single agent. Fresh {@code SendMessage}
 * (no {@code taskId}) invokes the configured {@code defaultChatOp} which
 * produces a Covia Job; the Job ID becomes the A2A Task ID. Continuations
 * ({@code taskId} set) flow through {@link covia.venue.JobManager#deliverMessage}
 * which dispatches to the adapter's multi-turn handler.</p>
 *
 * <p>Wire format uses spec POJOs (gson via {@link JsonUtil#OBJECT_MAPPER})
 * at the HTTP boundary; {@link A2ACodec} translates to/from Covia's ACell
 * representation. Covia internals never see POJOs.</p>
 */
public class A2A extends ACoviaAPI {

	public static final Logger log = LoggerFactory.getLogger(A2A.class);

	/** A2A-specific response content type per spec §9 / §11. */
	static final String A2A_JSON = "application/a2a+json; charset=utf-8";

	/** The A2A well-known Agent Card path — at the origin for the venue front-door
	 *  agent, and relative to a per-agent base endpoint for per-agent cards. */
	static final String WELL_KNOWN_CARD_PATH = "/.well-known/agent-card.json";

	/** Required: operation reference invoked on a fresh SendMessage. */
	private final AString defaultChatOp;

	private final String agentName;
	private final String agentDescription;
	private final String agentVersion;
	private final AgentProvider agentProvider;

	@SuppressWarnings("unused")
	protected final SseServer sseServer;

	public A2A(Venue venue, AMap<AString, ACell> a2aConfig) {
		super(venue);
		this.sseServer = new SseServer(engine());

		// defaultChatOp may be omitted; absence is only fatal when a client
		// actually calls SendMessage on a fresh task (checked in doSendMessage).
		// This keeps A2A discovery + GetTask/CancelTask usable for venues that
		// don't yet have a chat-capable default op configured.
		this.defaultChatOp = RT.ensureString(RT.getIn(a2aConfig, "defaultChatOp"));

		AMap<AString, ACell> agentInfo = RT.getIn(a2aConfig, "agentInfo");
		this.agentName = stringOr(agentInfo, "name", "covia-venue-agent");
		this.agentDescription = stringOr(agentInfo, "description",
				"Covia Venue Agent — federated AI orchestration over the A2A protocol");
		this.agentVersion = stringOr(agentInfo, "version", Utils.getVersion());

		String orgName = stringOr(agentInfo, "organization",
				engine().getName() != null ? engine().getName().toString() : "Covia");
		String orgUrl = stringOr(agentInfo, "providerUrl", "https://covia.ai");
		this.agentProvider = new AgentProvider(orgName, orgUrl);
	}

	public void addRoutes(RoutesConfig routes) {
		routes.get(WELL_KNOWN_CARD_PATH, this::getAgentCard);
		routes.post("/a2a", this::handleJsonRpc);
		// Per-agent card (COG-14): the standard A2A well-known path relative to the
		// agent's base endpoint — GET /a2a/<ownerDID>/g/<agentId>/.well-known/agent-card.json.
		// The greedy <addr> wildcard captures the base + suffix; getAgentCardFor
		// strips the suffix and parses the base via A2ACodec.parseAgentEndpoint.
		routes.get("/a2a/<addr>", this::getAgentCardFor);
		// Per-agent JSON-RPC (COG-14 / #184): POST /a2a/<ownerDID>/g/<agentId>.
		routes.post("/a2a/<addr>", this::handleAgentJsonRpc);
	}

	// ==================== Agent Card ====================

	protected void getAgentCard(Context ctx) {
		try {
			String baseUrl = getExternalBaseUrl(ctx, "");
			AgentCard card = buildAgentCard(baseUrl);
			writeJson(ctx, 200, card);
		} catch (Exception e) {
			log.error("Error generating agent card", e);
			buildError(ctx, 500, "Error generating agent card: " + e.getMessage());
		}
	}

	private AgentCard buildAgentCard(String baseUrl) {
		// The venue front-door agent. Per-agent cards share the same builder
		// (A2ACodec.agentCard), differing only in name/description/endpoint.
		return A2ACodec.agentCard(agentName, agentDescription, agentVersion, agentProvider, baseUrl + "/a2a");
	}

	/**
	 * Per-agent Agent Card (COG-14): {@code GET /a2a/<ownerDID>/g/<agentId>}.
	 *
	 * <p>Private by default — the card is disclosed only to the agent's owner.
	 * A bad address, a non-owner caller, or a missing agent all get a uniform
	 * {@code 404}, so the endpoint never reveals whether an agent exists.
	 * Publicly-exposed agents and the authenticated-non-owner {@code 403} path
	 * are layered on in later steps (#186 / #184).</p>
	 */
	protected void getAgentCardFor(Context ctx) {
		// The card lives at the A2A well-known path relative to the agent's base
		// endpoint — /a2a/<ownerDID>/g/<agentId>/.well-known/agent-card.json — which
		// is where a standard A2ACardResolver fetches it given the base URL. A bare
		// GET on the base endpoint is not a card location.
		String path = ctx.path();
		if (!path.endsWith(WELL_KNOWN_CARD_PATH)) { buildError(ctx, 404, "Not found"); return; }
		String base = path.substring(0, path.length() - WELL_KNOWN_CARD_PATH.length());
		A2ACodec.AgentRef ref = A2ACodec.parseAgentEndpoint(base);
		if (ref == null) { buildError(ctx, 404, "Not found"); return; }

		AgentState agent = resolveAgentByOwner(ref);
		if (agent == null) { buildError(ctx, 404, "Not found"); return; }

		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		// Private by default: only the owner sees the card, unless the agent is
		// explicitly marked public (#186). A non-owner denial still hides existence
		// (404 anon, 403 authed).
		if (!isOwner(rctx, ref) && !isPublic(agent)) { denyPerAgentAccess(ctx, rctx); return; }

		writeJson(ctx, 200, perAgentCard(ctx, ref, agent));
	}

	/** Render the per-agent Agent Card from the agent's config (#183). */
	private AgentCard perAgentCard(Context ctx, A2ACodec.AgentRef ref, AgentState agent) {
		AMap<AString, ACell> config = agent.getConfig();
		String name = stringOr(config, "name", ref.agentId());
		String description = stringOr(config, "description", "Covia agent " + ref.agentId());
		String endpoint = A2ACodec.agentEndpointUrl(getExternalBaseUrl(ctx, ""), ref.ownerDid(), ref.agentId());
		return A2ACodec.agentCard(name, description, agentVersion, agentProvider, endpoint);
	}

	// ==================== Per-agent JSON-RPC (COG-14) ====================

	/** True when the caller owns the addressed agent. */
	private boolean isOwner(RequestContext rctx, A2ACodec.AgentRef ref) {
		AString caller = rctx.getCallerDID();
		return caller != null && ref.ownerDid().equals(caller.toString());
	}

	/** True for the venue's public (unauthenticated) caller identity, per AuthMiddleware. */
	private boolean isAnonymousCaller(RequestContext rctx) {
		AString caller = rctx.getCallerDID();
		return caller == null || (engine().getDIDString() + ":public").equals(caller.toString());
	}

	/**
	 * Uniform per-agent denial (COG-14 §4): an anonymous caller gets a 404 that
	 * hides the agent's existence; an authenticated non-owner gets a 403.
	 */
	private void denyPerAgentAccess(Context ctx, RequestContext rctx) {
		if (isAnonymousCaller(rctx)) buildError(ctx, 404, "Not found");
		else buildError(ctx, 403, "Forbidden");
	}

	/** Resolve the addressed agent in its owner's namespace (from the URL), or null. */
	private AgentState resolveAgentByOwner(A2ACodec.AgentRef ref) {
		User user = engine().getVenueState().users().get(ref.ownerDid());
		AgentState agent = (user == null) ? null : user.agent(ref.agentId());
		return (agent != null && agent.exists()) ? agent : null;
	}

	/** True when the agent's config opts into public A2A exposure ({@code a2a.public = true}). */
	private static boolean isPublic(AgentState agent) {
		AMap<AString, ACell> config = agent.getConfig();
		return config != null
				&& CVMBool.TRUE.equals(RT.getIn(config, Strings.intern("a2a"), Strings.intern("public")));
	}

	/**
	 * True when a non-owner may <em>interact</em> with a public agent — the agent
	 * is public AND has an explicit {@code a2a.caps} grant scope (COG-14 §5). Public
	 * without one is discoverable (card) but not anonymously interactable:
	 * running the agent for anonymous callers requires the owner to deliberately
	 * bound it.
	 */
	private static boolean publicInteractionAllowed(AgentState agent) {
		return isPublic(agent)
				&& RT.getIn(agent.getConfig(), Strings.intern("a2a"), Strings.intern("caps")) != null;
	}

	/**
	 * The context under which a non-owner's message runs on a public agent: the
	 * <em>owner's</em> identity (so it resolves the owner's agent) narrowed by the
	 * {@code a2a.caps} grant scope. {@code "unrestricted"} → no scope (full owner
	 * authority — the owner's explicit, logged choice); a malformed value falls
	 * back to a read-only scope. A grant scope can only narrow what the caller may
	 * do, so this is escalation-safe.
	 */
	private RequestContext ownerDispatchContext(A2ACodec.AgentRef ref, AgentState agent) {
		AString ownerDid = Strings.create(ref.ownerDid());
		ACell caps = RT.getIn(agent.getConfig(), Strings.intern("a2a"), Strings.intern("caps"));
		if (caps instanceof AString s && "unrestricted".equals(s.toString())) {
			log.warn("A2A: public agent {} runs anonymous callers under UNRESTRICTED owner authority", ref.gridAddress());
			return RequestContext.of(ownerDid);
		}
		AVector<ACell> scope = RT.ensureVector(caps);
		if (scope == null) {
			log.warn("A2A: public agent {} has malformed a2a.caps; falling back to read-only", ref.gridAddress());
			scope = CapabilityChecker.readOnlyScope(ownerDid);
		}
		return RequestContext.of(ownerDid).withCaps(scope);
	}

	/**
	 * Per-agent A2A JSON-RPC endpoint: {@code POST /a2a/<ownerDID>/g/<agentId>}.
	 *
	 * <p>Access is the same owner gate as the card (private by default) and runs
	 * <em>before</em> the body is read, so a non-owner never learns an agent
	 * exists. For an owner, {@code message/send} dispatches to {@code agent:request}
	 * — whose own capability check enforces ownership too (facade over the
	 * capability layer) — and the resulting task Job becomes the A2A Task.
	 * {@code GetTask} / {@code CancelTask} reuse the front-door by-id handlers
	 * (a Task id is a global, caller-scoped Job id). Task continuations and the
	 * {@code contextId = session} surfacing are follow-ups (#185).</p>
	 */
	protected void handleAgentJsonRpc(Context ctx) {
		A2ACodec.AgentRef ref = A2ACodec.parseAgentEndpoint(ctx.path());
		if (ref == null) { buildError(ctx, 404, "Not found"); return; }

		AgentState agent = resolveAgentByOwner(ref);
		if (agent == null) { buildError(ctx, 404, "Not found"); return; }

		RequestContext caller = AuthMiddleware.callerContext(ctx);
		boolean owner = isOwner(caller, ref);
		// The context message/send runs under: the owner as themselves, or — for a
		// public agent with an explicit a2a.caps grant scope — the owner's identity
		// narrowed by that scope (COG-14 §5). Otherwise, no access (404/403).
		RequestContext dispatch;
		if (owner) {
			dispatch = caller;
		} else if (publicInteractionAllowed(agent)) {
			dispatch = ownerDispatchContext(ref, agent);
		} else {
			denyPerAgentAccess(ctx, caller);
			return;
		}

		Map<?, ?> envelope;
		try {
			envelope = JsonUtil.OBJECT_MAPPER.fromJson(ctx.body(), Map.class);
		} catch (Exception e) {
			writeError(ctx, null, A2AErrorCodes.JSON_PARSE, "Parse error");
			return;
		}
		if (envelope == null) { writeError(ctx, null, A2AErrorCodes.INVALID_REQUEST, "Empty request"); return; }

		Object id = envelope.get("id");
		Object methodObj = envelope.get("method");
		Object paramsRaw = envelope.get("params");
		if (!(methodObj instanceof String method)) {
			writeError(ctx, id, A2AErrorCodes.INVALID_REQUEST, "Missing or invalid method");
			return;
		}

		try {
			switch (method) {
				case A2AMethods.SEND_MESSAGE_METHOD -> {
					MessageSendParams params = parseParams(paramsRaw, MessageSendParams.class);
					doSendMessageToAgent(ctx, id, params, ref, dispatch);
				}
				case A2AMethods.GET_TASK_METHOD -> {
					// A Task is addressed by its (global) id, and access was decided
					// by the per-agent gate above — so the lookup runs under the
					// gated DISPATCH context. For a public-with-caps agent that is
					// the owner's identity: tasks minted through this endpoint stay
					// pollable by their remote (anonymous) senders, who hold the
					// unguessable task id as their credential.
					TaskQueryParams params = parseParams(paramsRaw, TaskQueryParams.class);
					doGetTask(ctx, id, params, dispatch);
				}
				case A2AMethods.CANCEL_TASK_METHOD -> {
					CancelTaskParams params = parseParams(paramsRaw, CancelTaskParams.class);
					doCancelTask(ctx, id, params, dispatch);
				}
				case A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD ->
					// The access gate already ran; the agent's extended card is
					// its card — per-agent skills from offered ops are a later pass.
					writeResult(ctx, id, perAgentCard(ctx, ref, agent));
				default ->
					writeError(ctx, id, A2AErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method);
			}
		} catch (IllegalArgumentException e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid params: " + e.getMessage());
		} catch (Exception e) {
			log.error("Error handling per-agent A2A method {}", method, e);
			writeError(ctx, id, A2AErrorCodes.INTERNAL, "Internal error: " + e.getMessage());
		}
	}

	/**
	 * Per-agent {@code message/send}: a fresh message is submitted to the agent as
	 * an {@code agent:request} task; the task Job becomes the A2A Task (async — the
	 * client polls GetTask). The {@code contextId = session} mapping and task
	 * continuations (an incoming {@code taskId}) are follow-ups (#185).
	 */
	private void doSendMessageToAgent(Context ctx, Object id, MessageSendParams params,
			A2ACodec.AgentRef ref, RequestContext rctx) {
		if (params == null || params.message() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "message required");
			return;
		}
		Message incoming = params.message();
		if (incoming.taskId() != null) {
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, "Per-agent task continuation not yet implemented");
			return;
		}

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);
		Job job;
		try {
			ACell input = Maps.of(Fields.AGENT_ID, Strings.create(ref.agentId()), Fields.INPUT, record);
			job = engine().jobs().invokeOperation("v/ops/agent/request", input, rctx);
		} catch (IllegalArgumentException e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_AGENT_RESPONSE, "agent:request failed: " + e.getMessage());
			return;
		}
		writeResult(ctx, id, A2ACodec.toTask(engine().jobs().getJobData(job.getID(), rctx)));
	}

	// ==================== JSON-RPC dispatch ====================

	protected void handleJsonRpc(Context ctx) {
		String body = ctx.body();
		Map<?, ?> envelope;
		try {
			envelope = JsonUtil.OBJECT_MAPPER.fromJson(body, Map.class);
		} catch (Exception e) {
			writeError(ctx, null, A2AErrorCodes.JSON_PARSE, "Parse error");
			return;
		}
		if (envelope == null) {
			writeError(ctx, null, A2AErrorCodes.INVALID_REQUEST, "Empty request");
			return;
		}

		Object id = envelope.get("id");
		Object methodObj = envelope.get("method");
		Object paramsRaw = envelope.get("params");

		if (!(methodObj instanceof String method)) {
			writeError(ctx, id, A2AErrorCodes.INVALID_REQUEST, "Missing or invalid method");
			return;
		}

		try {
			switch (method) {
				case A2AMethods.SEND_MESSAGE_METHOD -> {
					MessageSendParams params = parseParams(paramsRaw, MessageSendParams.class);
					doSendMessage(ctx, id, params);
				}
				case A2AMethods.GET_TASK_METHOD -> {
					TaskQueryParams params = parseParams(paramsRaw, TaskQueryParams.class);
					doGetTask(ctx, id, params, AuthMiddleware.callerContext(ctx));
				}
				case A2AMethods.CANCEL_TASK_METHOD -> {
					CancelTaskParams params = parseParams(paramsRaw, CancelTaskParams.class);
					doCancelTask(ctx, id, params, AuthMiddleware.callerContext(ctx));
				}
				case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
					MessageSendParams params = parseParams(paramsRaw, MessageSendParams.class);
					doSendStreamingMessage(ctx, id, params);
				}
				case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> {
					org.a2aproject.sdk.spec.TaskIdParams params =
							parseParams(paramsRaw, org.a2aproject.sdk.spec.TaskIdParams.class);
					doSubscribeToTask(ctx, id, params);
				}
				case A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD ->
					doGetExtendedCard(ctx, id);
				case A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.LIST_TASK_METHOD ->
					writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, "Not yet implemented: " + method);
				default ->
					writeError(ctx, id, A2AErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method);
			}
		} catch (IllegalArgumentException e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid params: " + e.getMessage());
		} catch (Exception e) {
			log.error("Error handling A2A method {}", method, e);
			writeError(ctx, id, A2AErrorCodes.INTERNAL, "Internal error: " + e.getMessage());
		}
	}

	// ==================== Method handlers ====================

	private void doSendMessage(Context ctx, Object id, MessageSendParams params) {
		if (params == null || params.message() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "message required");
			return;
		}
		Message incoming = params.message();
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);

		if (incoming.taskId() == null) {
			// Fresh task — invoke the default chat op
			if (defaultChatOp == null) {
				writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION,
						"A2A defaultChatOp not configured on this venue");
				return;
			}
			Job job;
			try {
				ACell input = Maps.of(Fields.MESSAGE, record);
				job = engine().jobs().invokeOperation(defaultChatOp, input, rctx);
			} catch (IllegalArgumentException e) {
				writeError(ctx, id, A2AErrorCodes.INVALID_AGENT_RESPONSE,
						"Default chat op not resolvable: " + e.getMessage());
				return;
			}
			// Persist the initiating message to Task history so subsequent
			// GetTask sees it. The adapter already received it via the
			// operation's input, so we do not additionally deliverMessage.
			try {
				engine().jobs().appendToHistory(job.getID(), record, rctx);
			} catch (Exception e) {
				log.warn("Failed to append initial message to Task history", e);
			}
			Task task = A2ACodec.toTask(engine().jobs().getJobData(job.getID(), rctx));
			writeResult(ctx, id, task);
			return;
		}

		// Continuation — deliver message to existing task
		Blob taskId;
		try {
			taskId = Blob.parse(incoming.taskId());
			if (taskId == null) throw new IllegalArgumentException("not a hex blob");
		} catch (Exception e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid taskId");
			return;
		}

		try {
			// Persist to Task history first, then dispatch to the adapter.
			engine().jobs().appendToHistory(taskId, record, rctx);
			engine().jobs().deliverMessage(taskId, record, rctx);
		} catch (IllegalArgumentException e) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, e.getMessage());
			return;
		} catch (IllegalStateException e) {
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, e.getMessage());
			return;
		} catch (AuthException e) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found");
			return;
		}

		AMap<AString, ACell> jobData = engine().jobs().getJobData(taskId, rctx);
		if (jobData == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + incoming.taskId());
			return;
		}
		writeResult(ctx, id, A2ACodec.toTask(jobData));
	}

	/** {@code rctx} is the context access was granted under: the caller for the
	 *  front door, the gated dispatch context for a per-agent endpoint. */
	private void doGetTask(Context ctx, Object id, TaskQueryParams params, RequestContext rctx) {
		if (params == null || params.id() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "id required");
			return;
		}
		Blob taskId;
		try {
			taskId = Blob.parse(params.id());
			if (taskId == null) throw new IllegalArgumentException();
		} catch (Exception e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid task id");
			return;
		}

		AMap<AString, ACell> jobData;
		try {
			jobData = engine().jobs().getJobData(taskId, rctx);
		} catch (AuthException e) {
			// Don't leak existence of foreign tasks.
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + params.id());
			return;
		}
		if (jobData == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + params.id());
			return;
		}
		writeResult(ctx, id, A2ACodec.toTask(jobData));
	}

	/** {@code rctx} is the context access was granted under: the caller for the
	 *  front door, the gated dispatch context for a per-agent endpoint. */
	private void doCancelTask(Context ctx, Object id, CancelTaskParams params, RequestContext rctx) {
		if (params == null || params.id() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "id required");
			return;
		}
		Blob taskId;
		try {
			taskId = Blob.parse(params.id());
			if (taskId == null) throw new IllegalArgumentException();
		} catch (Exception e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid task id");
			return;
		}

		AMap<AString, ACell> before;
		try {
			before = engine().jobs().getJobData(taskId, rctx);
		} catch (AuthException e) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + params.id());
			return;
		}
		if (before == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + params.id());
			return;
		}
		if (Job.isFinished(before)) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_CANCELABLE,
					"Task is already in a terminal state");
			return;
		}

		try {
			engine().jobs().cancelJob(taskId, rctx);
		} catch (Exception e) {
			writeError(ctx, id, A2AErrorCodes.INTERNAL, "Cancel failed: " + e.getMessage());
			return;
		}

		AMap<AString, ACell> after = engine().jobs().getJobData(taskId, rctx);
		writeResult(ctx, id, A2ACodec.toTask(after != null ? after : before));
	}

	/**
	 * Front-door {@code GetExtendedAgentCard} (#187): the authenticated agent
	 * catalogue. An authenticated caller gets the venue card extended with one
	 * skill per agent they are entitled to see — their own agents, per COG-14 §3.
	 * An anonymous caller sees only the operator front door: the plain card,
	 * no catalogue (and no error — nothing to disclose, nothing to probe).
	 */
	private void doGetExtendedCard(Context ctx, Object id) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		String endpoint = getExternalBaseUrl(ctx, "") + "/a2a";
		List<AgentSkill> skills = isAnonymousCaller(rctx) ? List.of() : catalogueSkills(rctx);
		writeResult(ctx, id, A2ACodec.agentCard(agentName, agentDescription, agentVersion,
				agentProvider, endpoint, skills));
	}

	/**
	 * One catalogue skill per agent the caller owns, via the job-free agent
	 * list read (#180) — discovery must never mint jobs. The skill id is the
	 * agent's grid address, which is also its A2A endpoint path under
	 * {@code /a2a/}.
	 */
	private List<AgentSkill> catalogueSkills(RequestContext rctx) {
		AgentAdapter agents = (AgentAdapter) engine().getAdapter("agent");
		AVector<ACell> ids = RT.ensureVector(
				RT.getIn(agents.listAgents(rctx, false, false), Strings.intern("agents")));
		if (ids == null || ids.isEmpty()) return List.of();

		String ownerDid = rctx.getCallerDID().toString();
		User user = engine().getVenueState().users().get(rctx.getCallerDID());
		List<AgentSkill> skills = new ArrayList<>((int) ids.count());
		for (long i = 0; i < ids.count(); i++) {
			AString agentId = RT.ensureString(ids.get(i));
			if (agentId == null) continue;
			AgentState agent = (user == null) ? null : user.agent(agentId.toString());
			AMap<AString, ACell> config = (agent == null) ? null : agent.getConfig();
			String name = stringOr(config, "name", agentId.toString());
			String description = stringOr(config, "description", "Covia agent " + agentId);
			String address = new A2ACodec.AgentRef(ownerDid, agentId.toString()).gridAddress();
			skills.add(A2ACodec.agentSkill(address, name, description));
		}
		return skills;
	}

	// ==================== Streaming methods ====================

	private void doSendStreamingMessage(Context ctx, Object id, MessageSendParams params) {
		if (params == null || params.message() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "message required");
			return;
		}
		Message incoming = params.message();
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);

		// Resolve the target Job ID before opening the SSE stream — the Job
		// must exist (and be owned by caller) when A2ASseSession polls it.
		final Blob taskId;
		if (incoming.taskId() == null) {
			if (defaultChatOp == null) {
				writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION,
						"A2A defaultChatOp not configured on this venue");
				return;
			}
			Job job;
			try {
				ACell input = Maps.of(Fields.MESSAGE, record);
				job = engine().jobs().invokeOperation(defaultChatOp, input, rctx);
			} catch (IllegalArgumentException e) {
				writeError(ctx, id, A2AErrorCodes.INVALID_AGENT_RESPONSE,
						"Default chat op not resolvable: " + e.getMessage());
				return;
			}
			try {
				engine().jobs().appendToHistory(job.getID(), record, rctx);
			} catch (Exception e) {
				log.warn("Failed to append initial message to Task history", e);
			}
			taskId = job.getID();
		} else {
			try {
				taskId = Blob.parse(incoming.taskId());
				if (taskId == null) throw new IllegalArgumentException("not a hex blob");
			} catch (Exception e) {
				writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid taskId");
				return;
			}
			try {
				engine().jobs().appendToHistory(taskId, record, rctx);
				engine().jobs().deliverMessage(taskId, record, rctx);
			} catch (IllegalArgumentException e) {
				writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, e.getMessage());
				return;
			} catch (IllegalStateException e) {
				writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, e.getMessage());
				return;
			} catch (AuthException e) {
				writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found");
				return;
			}
		}

		openSseStream(ctx, id, taskId, rctx);
	}

	private void doSubscribeToTask(Context ctx, Object id, org.a2aproject.sdk.spec.TaskIdParams params) {
		if (params == null || params.id() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "id required");
			return;
		}
		Blob taskId;
		try {
			taskId = Blob.parse(params.id());
			if (taskId == null) throw new IllegalArgumentException();
		} catch (Exception e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "Invalid task id");
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		AMap<AString, ACell> jobData;
		try {
			jobData = engine().jobs().getJobData(taskId, rctx);
		} catch (AuthException e) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + params.id());
			return;
		}
		if (jobData == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, "Task not found: " + params.id());
			return;
		}
		// Per spec §9.4.6: SubscribeToTask on a terminal task returns
		// UnsupportedOperationError — there will never be more frames.
		if (Job.isFinished(jobData)) {
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION,
					"Task is in a terminal state; no further updates will be sent");
			return;
		}

		openSseStream(ctx, id, taskId, rctx);
	}

	/**
	 * Hand off the current {@link Context} to a Javalin SSE handler and attach
	 * an {@link A2ASseSession}. The session registers a listener, emits the
	 * initial Task frame, and streams subsequent TaskStatusUpdateEvents. The
	 * POST response becomes {@code text/event-stream}; no JSON-RPC envelope
	 * is written at the top level — each SSE frame is its own envelope.
	 */
	private void openSseStream(Context ctx, Object rpcId, Blob taskId, RequestContext rctx) {
		Engine engine = engine();
		new SseHandler(sseClient -> {
			A2ASseSession session = new A2ASseSession(sseClient, engine, taskId, rpcId, rctx);
			session.start();
		}).handle(ctx);
	}

	// ==================== Wire helpers ====================

	private <T> T parseParams(Object raw, Class<T> cls) {
		// Re-serialise the loose params map, then parse into the typed record.
		// Single round-trip through gson so polymorphic adapters (Part, etc.)
		// are applied consistently.
		// Inject tenant="" before parsing — the SDK's *Params records mark
		// tenant @NonNull but gson doesn't know to pass "" through default
		// convenience constructors. Clients typically omit tenant on the wire.
		if (raw instanceof Map<?, ?> m) {
			@SuppressWarnings("unchecked")
			Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) m);
			mutable.putIfAbsent("tenant", "");
			raw = mutable;
		}
		String json = JsonUtil.OBJECT_MAPPER.toJson(raw);
		return JsonUtil.OBJECT_MAPPER.fromJson(json, cls);
	}

	private void writeResult(Context ctx, Object id, Object result) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("result", result);
		writeJson(ctx, 200, response);
	}

	private void writeError(Context ctx, Object id, A2AErrorCodes code, String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("code", code.code());
		err.put("message", message);
		response.put("error", err);
		// Per spec §9, JSON-RPC errors are still delivered with HTTP 200.
		writeJson(ctx, 200, response);
	}

	private void writeJson(Context ctx, int status, Object payload) {
		ctx.status(status);
		ctx.header("Content-Type", A2A_JSON);
		ctx.result(JsonUtil.OBJECT_MAPPER.toJson(payload).getBytes(StandardCharsets.UTF_8));
	}

	// Suppress SDK unused import warnings — referenced indirectly through gson adapters.
	@SuppressWarnings("unused")
	private static final Class<?>[] _keepTypes = {A2AError.class};

	private static String stringOr(AMap<AString, ACell> m, String key, String fallback) {
		if (m == null) return fallback;
		AString s = RT.ensureString(m.get(Strings.intern(key)));
		return s != null ? s.toString() : fallback;
	}
}
