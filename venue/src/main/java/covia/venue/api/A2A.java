package covia.venue.api;

import static covia.venue.server.VenueRouteFeature.COVIA_A2A;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

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
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParseException;

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
import covia.api.Abilities;
import covia.api.Fields;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.exception.AuthException;
import covia.exception.CoviaException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.server.AuthMiddleware;
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
 * <p>The venue front door is modelled as a single agent. Fresh
 * {@code SendMessage} (no {@code taskId}) invokes the configured
 * {@code defaultChatOp}, producing a Covia Job whose ID becomes the A2A Task
 * ID. Continuations ({@code taskId} set) flow through
 * {@link covia.venue.JobManager#deliverMessage}, which dispatches to the
 * adapter's multi-turn handler. Hosted agents are also exposed at per-agent
 * endpoints under {@code /a2a/<ownerDID>/g/<agentId>}.</p>
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

	/** Opening of a front-door refusal: the caller has no such job. */
	static final String NO_SUCH_TASK = "Task not found: ";

	/** Refusal of a per-agent by-id call: no task of the asker's for this agent —
	 *  an unknown id, someone else's job, or a job that is no task of this agent.
	 *  One wording for all, so the endpoint discloses nothing about other jobs. */
	public static final String AGENT_TASK_NOT_FOUND = "Task not found for this agent";

	/** Refusal of a request body that is not JSON. */
	public static final String PARSE_ERROR = "Parse error";

	/** Refusal of an empty request body. */
	public static final String EMPTY_REQUEST = "Empty request";

	/** Refusal of a request with no method name. */
	public static final String MISSING_METHOD = "Missing or invalid method";

	/** Opening of a refusal of a method A2A does not define; the method follows. */
	public static final String METHOD_NOT_FOUND = "Method not found: ";

	/** Opening of a refusal of an A2A method this venue does not implement; the method follows. */
	public static final String NOT_IMPLEMENTED = "Not yet implemented: ";

	/** Opening of a refusal of params that do not fit their method; the method follows. */
	public static final String INVALID_PARAMS = "Invalid params for ";

	/** Refusal of a send without a message. */
	public static final String MESSAGE_REQUIRED = "message required";

	/** Refusal of a by-id call without a task id. */
	public static final String TASK_ID_REQUIRED = "id required";

	/** Refusal of a task id that is not hex. */
	public static final String INVALID_TASK_ID = "Invalid task id";

	/** Refusal of a fresh front-door send on a venue with no chat operation. */
	public static final String NO_CHAT_OP = "A2A defaultChatOp not configured on this venue";

	/** Opening of a refusal by the front door's chat operation to start a task; the reason follows. */
	public static final String CHAT_OP_REFUSED = "Default chat op did not take the message: ";

	/** Opening of a refusal by a hosted agent to take a new task; the reason follows. */
	public static final String AGENT_INTAKE_FAILED = "Agent intake failed: ";

	/** Refusal of a continuation of an agent task that records no session. */
	public static final String NO_AGENT_SESSION = "Task has no agent session";

	/** Refusal of a continuation whose contextId is not its task's session. */
	public static final String CONTEXT_MISMATCH = "contextId does not match taskId";

	/** Opening of a refusal of a message to a finished task; its status follows. */
	public static final String TASK_FINISHED = "Task is in terminal state: ";

	/** Refusal of a cancel of a finished task. */
	public static final String TASK_NOT_CANCELABLE = "Task is already in a terminal state";

	/** Refusal of a subscription to a finished task. */
	public static final String NO_FURTHER_UPDATES = "Task is in a terminal state; no further updates will be sent";

	/** Required: operation reference invoked on a fresh SendMessage. */
	private final AString defaultChatOp;

	private final String agentName;
	private final String agentDescription;
	private final String agentVersion;
	private final AgentProvider agentProvider;


	public A2A(Venue venue, AMap<AString, ACell> a2aConfig) {
		super(venue);

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
		routes.post("/a2a", this::handleJsonRpc, COVIA_A2A);
		// Per-agent card (COG-14): the standard A2A well-known path relative to the
		// agent's base endpoint — GET /a2a/<ownerDID>/g/<agentId>/.well-known/agent-card.json.
		// The greedy <addr> wildcard captures the base + suffix; getAgentCardFor
		// strips the suffix and parses the base via A2ACodec.parseAgentEndpoint.
		routes.get("/a2a/<addr>", this::getAgentCardFor, COVIA_A2A);
		// Per-agent JSON-RPC (COG-14 / #184): POST /a2a/<ownerDID>/g/<agentId>.
		routes.post("/a2a/<addr>", this::handleAgentJsonRpc, COVIA_A2A);
	}

	// ==================== Agent Card ====================

	protected void getAgentCard(Context ctx) {
		try {
			String baseUrl = getExternalBaseUrl(ctx, "", engine().config().getTrustedProxies());
			AgentCard card = buildAgentCard(baseUrl);
			writeJson(ctx, 200, card);
		} catch (Exception e) {
			cardFailure(ctx, e);
		}
	}

	/**
	 * Answers a card request that failed: a 500 with the failure as
	 * {@link AAdapter#describeFailure} renders it, never an unexpected
	 * failure's text, which is logged at ERROR.
	 */
	private void cardFailure(Context ctx, Exception e) {
		if (!AAdapter.isExpectedFailure(e)) log.error("Agent card failed in {} {}", ctx.method(), ctx.path(), e);
		buildError(ctx, 500, AAdapter.describeFailure(e));
	}

	private AgentCard buildAgentCard(String baseUrl) {
		// The venue front-door agent. Per-agent cards share the same builder
		// (A2ACodec.agentCard), differing only in name/description/endpoint.
		return A2ACodec.agentCard(agentName, agentDescription, agentVersion, agentProvider, baseUrl + "/a2a");
	}

	/**
	 * Per-agent Agent Card (COG-14): {@code GET /a2a/<ownerDID>/g/<agentId>}.
	 *
	 * <p>Private by default — the card is disclosed to whoever may ask the agent,
	 * and to anyone once {@code a2a.public} is set. A bad address or a missing
	 * agent gets {@code 404}; a caller who may not see the card gets the uniform
	 * per-agent denial, so the endpoint never reveals whether an agent exists to
	 * an anonymous caller.</p>
	 */
	protected void getAgentCardFor(Context ctx) {
		try {
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

			RequestContext rctx = askerContext(ctx);
			// Private by default: only whoever may ask the agent sees the card, unless
			// the agent is explicitly marked public (#186). A denial still hides
			// existence (404 anon, 403 authed).
			if (!isPublic(agent) && !mayAsk(rctx, ref)) { denyPerAgentAccess(ctx, rctx); return; }

			writeJson(ctx, 200, perAgentCard(ctx, ref, agent));
		} catch (Exception e) {
			cardFailure(ctx, e);
		}
	}

	/** Render the per-agent Agent Card from the agent's config (#183). */
	private AgentCard perAgentCard(Context ctx, A2ACodec.AgentRef ref, AgentState agent) {
		AMap<AString, ACell> config = agent.getConfig();
		String name = stringOr(config, "name", ref.agentId());
		String description = stringOr(config, "description", "Covia agent " + ref.agentId());
		String endpoint = A2ACodec.agentEndpointUrl(getExternalBaseUrl(ctx, "", engine().config().getTrustedProxies()), ref.ownerDid(), ref.agentId());
		return A2ACodec.agentCard(name, description, agentVersion, agentProvider, endpoint);
	}

	// ==================== Per-agent JSON-RPC (COG-14) ====================

	/**
	 * The asker's own context: the authenticated caller, or the venue's public
	 * principal for an anonymous one, with any UCAN proofs presented in the
	 * {@code X-Covia-Ucans} header.
	 */
	private RequestContext askerContext(Context ctx) {
		return AuthMiddleware.withTransportGrants(AuthMiddleware.callerContext(ctx),
			AuthMiddleware.headerUcans(ctx), engine().didVerifier());
	}

	/**
	 * Whether the asker may ask the addressed agent: its owner, or a caller the
	 * agent's admission policy ({@code config.accepts}) or a presented
	 * {@code agent/request} proof admits. This is the check every way of asking
	 * an agent shares.
	 */
	private boolean mayAsk(RequestContext asker, A2ACodec.AgentRef ref) {
		try {
			engine().requireLocalAccess(asker, Strings.create(ref.gridAddress()), Abilities.AGENT_REQUEST);
			return true;
		} catch (AuthException e) {
			return false;
		}
	}

	/** True for the venue's public (unauthenticated) caller identity, per AuthMiddleware. */
	private boolean isAnonymousCaller(RequestContext rctx) {
		AString caller = rctx.getCallerDID();
		return caller == null || engine().isPublicPrincipal(caller);
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
	 * Per-agent A2A JSON-RPC endpoint: {@code POST /a2a/<ownerDID>/g/<agentId>}.
	 *
	 * <p>Access is checked <em>before</em> the body is read, with the check every
	 * way of asking an agent shares ({@link #mayAsk}). Anonymous denials hide
	 * existence with 404; authenticated callers without standing receive 403.
	 * Every method then runs as the asker: {@code SendMessage} submits an
	 * {@code agent:request} task that is the asker's own, and the agent serves
	 * it as itself, under its own {@code config.caps}. The task Job becomes the
	 * A2A Task, with {@code contextId = session}. The by-id methods
	 * ({@code GetTask}, {@code CancelTask}, {@code SubscribeToTask}, a
	 * {@code SendMessage} with a {@code taskId}) reach only the asker's own tasks
	 * for this agent ({@link #agentTask}).</p>
	 */
	protected void handleAgentJsonRpc(Context ctx) {
		A2ACodec.AgentRef ref = A2ACodec.parseAgentEndpoint(ctx.path());
		if (ref == null) { buildError(ctx, 404, "Not found"); return; }

		AgentState agent = resolveAgentByOwner(ref);
		if (agent == null) { buildError(ctx, 404, "Not found"); return; }

		RequestContext asker = askerContext(ctx);
		if (!mayAsk(asker, ref)) {
			denyPerAgentAccess(ctx, asker);
			return;
		}

		Rpc rpc = readRequest(ctx);
		if (rpc == null) return;
		Object id = rpc.id();
		try {
			switch (rpc.method()) {
				case A2AMethods.SEND_MESSAGE_METHOD -> {
					MessageSendParams params = parseParams(rpc, MessageSendParams.class);
					doSendMessageToAgent(ctx, id, params, ref, asker);
				}
				case A2AMethods.GET_TASK_METHOD -> {
					TaskQueryParams params = parseParams(rpc, TaskQueryParams.class);
					TaskRecord task = agentTask(ctx, id, params == null ? null : params.id(), ref, asker);
					if (task != null) writeResult(ctx, id, A2ACodec.toTask(task.data()));
				}
				case A2AMethods.CANCEL_TASK_METHOD -> {
					CancelTaskParams params = parseParams(rpc, CancelTaskParams.class);
					TaskRecord task = agentTask(ctx, id, params == null ? null : params.id(), ref, asker);
					if (task != null) cancelTask(ctx, id, task, asker);
				}
				case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> {
					org.a2aproject.sdk.spec.TaskIdParams params =
							parseParams(rpc, org.a2aproject.sdk.spec.TaskIdParams.class);
					TaskRecord task = agentTask(ctx, id, params == null ? null : params.id(), ref, asker);
					if (task != null) subscribeTask(ctx, id, task, asker);
				}
				case A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD ->
					// The access gate already ran; the agent's extended card is
					// its card — per-agent skills from offered ops are a later pass.
					writeResult(ctx, id, perAgentCard(ctx, ref, agent));
				default ->
					writeError(ctx, id, A2AErrorCodes.METHOD_NOT_FOUND, METHOD_NOT_FOUND + rpc.method());
			}
		} catch (Exception e) {
			answerFailure(ctx, rpc, e);
		}
	}

	/**
	 * Per-agent {@code SendMessage}: a message without a {@code taskId} is
	 * submitted to the agent as an {@code agent:request} task — on the session
	 * a {@code contextId} names, or a fresh one — and the task Job becomes the
	 * A2A Task (async — the client polls GetTask). A {@code taskId} attaches
	 * input to that exact non-terminal agent task. Every send is therefore its
	 * own task with its own reply; the per-agent surface never uses
	 * {@code agent:chat} (#416).
	 */
	private void doSendMessageToAgent(Context ctx, Object id, MessageSendParams params,
			A2ACodec.AgentRef ref, RequestContext rctx) {
		if (params == null || params.message() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, MESSAGE_REQUIRED);
			return;
		}
		Message incoming = params.message();
		if (incoming.taskId() != null) {
			continueAgentTask(ctx, id, incoming, ref, rctx);
			return;
		}

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);
		// Every per-agent send is an agent:request task: a contextId selects
		// the session, the task Job is the A2A Task, and each message gets
		// its own cycle and its own reply (#416). agent:chat would batch
		// the messages queued on one session into one shared reply, which
		// A2A cannot express — a message without taskId is a new task.
		AMap<AString, ACell> input = Maps.of(
			Fields.AGENT_ID, Strings.create(ref.gridAddress()),
			Fields.INPUT, record);
		if (incoming.contextId() != null) {
			input = input.assoc(Fields.SESSION_ID, Strings.create(incoming.contextId()));
		}
		Job job;
		try {
			job = engine().jobs().invokeOperation("v/ops/agent/request", input, rctx);
		} catch (CoviaException e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_AGENT_RESPONSE, AGENT_INTAKE_FAILED + reason(e));
			return;
		}
		// The endpoint records the ask it admitted; recording it is not the
		// asker's own write to the task.
		Blob taskId = job.getID();
		AString asker = rctx.getUserDID();
		recordFirstMessage(taskId, () -> engine().jobs().appendToHistory(taskId, asker, record));
		writeResult(ctx, id, A2ACodec.toTask(engine().jobs().getJobData(taskId, rctx)));
	}

	/** Continue one exact non-terminal {@code agent:request} Job of this agent. */
	private void continueAgentTask(Context ctx, Object id, Message incoming,
			A2ACodec.AgentRef ref, RequestContext rctx) {
		TaskRecord task = agentTask(ctx, id, incoming.taskId(), ref, rctx);
		if (task == null) return;
		Blob taskId = task.id();
		AMap<AString, ACell> taskData = task.data();
		AString taskSession = RT.ensureString(taskData.get(Fields.SESSION_ID));
		if (taskSession == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, NO_AGENT_SESSION);
			return;
		}
		if (incoming.contextId() != null
				&& !incoming.contextId().equals(taskSession.toString())) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, CONTEXT_MISMATCH);
			return;
		}
		AString status = RT.ensureString(taskData.get(Fields.STATUS));
		if (isTerminalTaskStatus(status)) {
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, TASK_FINISHED + status);
			return;
		}

		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);
		ACell input = Maps.of(
			Fields.AGENT_ID, Strings.create(ref.gridAddress()),
			Fields.TASK_ID, Strings.create(taskId.toHexString()),
			Fields.SESSION_ID, taskSession,
			Fields.MESSAGE, record);
		try {
			engine().jobs().invokeInternal("v/ops/agent/message", input, rctx).join();
		} catch (CompletionException e) {
			// The task did not take the message
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, reason(e));
			return;
		}
		engine().jobs().appendToHistory(taskId, RT.ensureString(taskData.get(Fields.CALLER)), record);

		AMap<AString, ACell> after = engine().jobs().getJobData(taskId, rctx);
		writeResult(ctx, id, A2ACodec.toTask(after != null ? after : taskData));
	}

	private static boolean isTerminalTaskStatus(AString status) {
		return covia.grid.Status.COMPLETE.equals(status)
			|| covia.grid.Status.FAILED.equals(status)
			|| covia.grid.Status.CANCELLED.equals(status)
			|| covia.grid.Status.REJECTED.equals(status)
			|| covia.grid.Status.TIMEOUT.equals(status);
	}

	// ==================== JSON-RPC dispatch ====================

	protected void handleJsonRpc(Context ctx) {
		Rpc rpc = readRequest(ctx);
		if (rpc == null) return;
		Object id = rpc.id();
		try {
			switch (rpc.method()) {
				case A2AMethods.SEND_MESSAGE_METHOD -> {
					MessageSendParams params = parseParams(rpc, MessageSendParams.class);
					doSendMessage(ctx, id, params);
				}
				case A2AMethods.GET_TASK_METHOD -> {
					TaskQueryParams params = parseParams(rpc, TaskQueryParams.class);
					doGetTask(ctx, id, params, AuthMiddleware.callerContext(ctx));
				}
				case A2AMethods.CANCEL_TASK_METHOD -> {
					CancelTaskParams params = parseParams(rpc, CancelTaskParams.class);
					doCancelTask(ctx, id, params, AuthMiddleware.callerContext(ctx));
				}
				case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
					MessageSendParams params = parseParams(rpc, MessageSendParams.class);
					doSendStreamingMessage(ctx, id, params);
				}
				case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> {
					org.a2aproject.sdk.spec.TaskIdParams params =
							parseParams(rpc, org.a2aproject.sdk.spec.TaskIdParams.class);
					doSubscribeToTask(ctx, id, params);
				}
				case A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD ->
					doGetExtendedCard(ctx, id);
				case A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.LIST_TASK_METHOD ->
					writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, NOT_IMPLEMENTED + rpc.method());
				default ->
					writeError(ctx, id, A2AErrorCodes.METHOD_NOT_FOUND, METHOD_NOT_FOUND + rpc.method());
			}
		} catch (Exception e) {
			answerFailure(ctx, rpc, e);
		}
	}

	/** A JSON-RPC request: its id, method name and raw params. */
	private record Rpc(Object id, String method, Object params) {}

	/**
	 * The JSON-RPC request in the body, or null after writing its refusal: a
	 * body that is not JSON, is empty, or names no method.
	 */
	private Rpc readRequest(Context ctx) {
		String body = ctx.body();
		Map<?, ?> envelope;
		try {
			envelope = JsonUtil.OBJECT_MAPPER.fromJson(body, Map.class);
		} catch (JsonParseException e) {
			writeError(ctx, null, A2AErrorCodes.JSON_PARSE, PARSE_ERROR);
			return null;
		}
		if (envelope == null) {
			writeError(ctx, null, A2AErrorCodes.INVALID_REQUEST, EMPTY_REQUEST);
			return null;
		}
		Object id = envelope.get("id");
		if (!(envelope.get("method") instanceof String method)) {
			writeError(ctx, id, A2AErrorCodes.INVALID_REQUEST, MISSING_METHOD);
			return null;
		}
		return new Rpc(id, method, envelope.get("params"));
	}

	/**
	 * Answers a failure no step of the method answered itself: params that do
	 * not fit the method are {@code INVALID_PARAMS}, and anything else is
	 * {@code INTERNAL} with the failure as {@link AAdapter#describeFailure}
	 * renders it — an expected failure's reason, or only the type of an
	 * unexpected one, which is logged at ERROR.
	 */
	private void answerFailure(Context ctx, Rpc rpc, Exception e) {
		if (!AAdapter.isExpectedFailure(e)) {
			log.error("A2A {} failed in {} {}", rpc.method(), ctx.method(), ctx.path(), e);
		} else if (e instanceof InvalidInputException) {
			writeError(ctx, rpc.id(), A2AErrorCodes.INVALID_PARAMS, e.getMessage());
			return;
		}
		writeError(ctx, rpc.id(), A2AErrorCodes.INTERNAL, AAdapter.describeFailure(e));
	}

	/**
	 * The reason an expected failure gives its caller. Anything else is
	 * rethrown, for {@link #answerFailure} to answer as a venue error: a step
	 * answers only the failures its situation explains.
	 */
	private static String reason(RuntimeException e) {
		String reason = CoviaException.reasonOf(e);
		if (reason == null) throw e;
		return reason;
	}

	// ==================== Method handlers ====================

	private void doSendMessage(Context ctx, Object id, MessageSendParams params) {
		if (params == null || params.message() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, MESSAGE_REQUIRED);
			return;
		}
		Message incoming = params.message();
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);

		Blob taskId = (incoming.taskId() == null)
			? startChatTask(ctx, id, record, rctx)
			: deliverToTask(ctx, id, incoming.taskId(), record, rctx);
		if (taskId == null) return;

		AMap<AString, ACell> jobData = engine().jobs().getJobData(taskId, rctx);
		if (jobData == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, NO_SUCH_TASK + taskId.toHexString());
			return;
		}
		writeResult(ctx, id, A2ACodec.toTask(jobData));
	}

	/**
	 * Starts a fresh front-door task: the configured chat operation, invoked
	 * with the message. Returns the task's id, or null after writing the
	 * refusal.
	 */
	private Blob startChatTask(Context ctx, Object id, AMap<AString, ACell> record, RequestContext rctx) {
		if (defaultChatOp == null) {
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, NO_CHAT_OP);
			return null;
		}
		Job job;
		try {
			job = engine().jobs().invokeOperation(defaultChatOp, Maps.of(Fields.MESSAGE, record), rctx);
		} catch (CoviaException e) {
			writeError(ctx, id, A2AErrorCodes.INVALID_AGENT_RESPONSE, CHAT_OP_REFUSED + reason(e));
			return null;
		}
		// The operation took the message as its input; recording it gives the
		// Task its history without delivering it a second time.
		Blob taskId = job.getID();
		recordFirstMessage(taskId, () -> engine().jobs().appendToHistory(taskId, record, rctx));
		return taskId;
	}

	/**
	 * Records a fresh task's first message in its history. The task exists and
	 * is running by now, so a failure is logged, not answered: an asker told
	 * the send failed would send again and start a second task.
	 */
	private static void recordFirstMessage(Blob taskId, Runnable append) {
		try {
			append.run();
		} catch (RuntimeException e) {
			log.error("Failed to record the first message of task {}", taskId, e);
		}
	}

	/**
	 * Delivers a continuation to one of the caller's own front-door tasks,
	 * recording it in the task's history first. Returns the task's id, or null
	 * after writing the refusal.
	 */
	private Blob deliverToTask(Context ctx, Object id, String taskIdHex,
			AMap<AString, ACell> record, RequestContext rctx) {
		Blob taskId = parseTaskId(ctx, id, taskIdHex);
		if (taskId == null) return null;
		try {
			engine().jobs().appendToHistory(taskId, record, rctx);
			engine().jobs().deliverMessage(taskId, record, rctx);
		} catch (NotFoundException | AuthException e) {
			// A job the caller may not write is not found: its existence is not disclosed
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, NO_SUCH_TASK + taskIdHex);
			return null;
		} catch (CoviaException e) {
			// The task did not take the message
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, reason(e));
			return null;
		}
		return taskId;
	}

	/** A task addressed by id: the Job id and the record it resolved to. */
	private record TaskRecord(Blob id, AMap<AString, ACell> data) {}

	/**
	 * The task a front-door by-id call addresses — one of the caller's own jobs —
	 * or null after writing the refusal. A foreign job is "not found": its
	 * existence is not disclosed.
	 */
	private TaskRecord callerTask(Context ctx, Object id, String taskIdHex, RequestContext rctx) {
		Blob taskId = parseTaskId(ctx, id, taskIdHex);
		if (taskId == null) return null;
		AMap<AString, ACell> data = readableJob(taskId, rctx);
		if (data == null) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, NO_SUCH_TASK + taskIdHex);
			return null;
		}
		return new TaskRecord(taskId, data);
	}

	/**
	 * The task a per-agent by-id call addresses, or null after writing the
	 * refusal: one of the asker's own jobs whose recorded agent address is this
	 * agent's. Ownership confines the asker to its own jobs; the address confines
	 * the endpoint to this agent's. An unknown id, someone else's job and a job
	 * that is no task of this agent are refused alike
	 * ({@link #AGENT_TASK_NOT_FOUND}): the endpoint is no oracle for other jobs.
	 */
	private TaskRecord agentTask(Context ctx, Object id, String taskIdHex,
			A2ACodec.AgentRef ref, RequestContext asker) {
		Blob taskId = parseTaskId(ctx, id, taskIdHex);
		if (taskId == null) return null;
		AMap<AString, ACell> data = readableJob(taskId, asker);
		if (data == null || !Strings.create(ref.gridAddress()).equals(data.get(Fields.ADDRESS))) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_FOUND, AGENT_TASK_NOT_FOUND);
			return null;
		}
		return new TaskRecord(taskId, data);
	}

	/** A job's record when {@code rctx} may read it, else null. */
	private AMap<AString, ACell> readableJob(Blob taskId, RequestContext rctx) {
		try {
			return engine().jobs().getJobData(taskId, rctx);
		} catch (AuthException e) {
			return null;
		}
	}

	/** Parses a task id, or writes INVALID_PARAMS and returns null. */
	private Blob parseTaskId(Context ctx, Object id, String taskIdHex) {
		if (taskIdHex == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, TASK_ID_REQUIRED);
			return null;
		}
		Blob taskId = Blob.parse(taskIdHex);
		if (taskId == null) writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, INVALID_TASK_ID);
		return taskId;
	}

	/** Front-door {@code GetTask}: one of the caller's own jobs. */
	private void doGetTask(Context ctx, Object id, TaskQueryParams params, RequestContext rctx) {
		TaskRecord task = callerTask(ctx, id, params == null ? null : params.id(), rctx);
		if (task != null) writeResult(ctx, id, A2ACodec.toTask(task.data()));
	}

	/** Front-door {@code CancelTask}: one of the caller's own jobs. */
	private void doCancelTask(Context ctx, Object id, CancelTaskParams params, RequestContext rctx) {
		TaskRecord task = callerTask(ctx, id, params == null ? null : params.id(), rctx);
		if (task != null) cancelTask(ctx, id, task, rctx);
	}

	/** Cancels a resolved task under {@code rctx}, the context it was resolved under. */
	private void cancelTask(Context ctx, Object id, TaskRecord task, RequestContext rctx) {
		if (Job.isFinished(task.data())) {
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_CANCELABLE, TASK_NOT_CANCELABLE);
			return;
		}
		try {
			engine().jobs().cancelJob(task.id(), rctx);
		} catch (CoviaException e) {
			// The venue would not cancel it: not live here, or not the caller's to cancel
			writeError(ctx, id, A2AErrorCodes.TASK_NOT_CANCELABLE, reason(e));
			return;
		}
		AMap<AString, ACell> after = engine().jobs().getJobData(task.id(), rctx);
		writeResult(ctx, id, A2ACodec.toTask(after != null ? after : task.data()));
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
		String endpoint = getExternalBaseUrl(ctx, "", engine().config().getTrustedProxies()) + "/a2a";
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
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, MESSAGE_REQUIRED);
			return;
		}
		Message incoming = params.message();
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AMap<AString, ACell> record = A2ACodec.toMessageRecord(incoming, false);

		// Resolve the target Job ID before opening the SSE stream — the Job
		// must exist (and be owned by caller) when A2ASseSession polls it.
		Blob taskId = (incoming.taskId() == null)
			? startChatTask(ctx, id, record, rctx)
			: deliverToTask(ctx, id, incoming.taskId(), record, rctx);
		if (taskId != null) openSseStream(ctx, id, taskId, rctx);
	}

	/** Front-door {@code SubscribeToTask}: one of the caller's own jobs. */
	private void doSubscribeToTask(Context ctx, Object id, org.a2aproject.sdk.spec.TaskIdParams params) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		TaskRecord task = callerTask(ctx, id, params == null ? null : params.id(), rctx);
		if (task != null) subscribeTask(ctx, id, task, rctx);
	}

	/** Subscribes to the ordinary Covia Job backing a resolved A2A Task, under
	 *  {@code rctx}, the context it was resolved under. */
	private void subscribeTask(Context ctx, Object id, TaskRecord task, RequestContext rctx) {
		// Per spec §9.4.6: SubscribeToTask on a terminal task returns
		// UnsupportedOperationError — there will never be more frames.
		if (Job.isFinished(task.data())) {
			writeError(ctx, id, A2AErrorCodes.UNSUPPORTED_OPERATION, NO_FURTHER_UPDATES);
			return;
		}
		openSseStream(ctx, id, task.id(), rctx);
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

	/**
	 * The request's params as the method's SDK record, or null when there are
	 * none.
	 *
	 * @throws InvalidInputException if the params do not fit the method
	 */
	private <T> T parseParams(Rpc rpc, Class<T> cls) {
		// Re-serialise the loose params map, then parse into the typed record.
		// Single round-trip through gson so polymorphic adapters (Part, etc.)
		// are applied consistently.
		// Inject tenant="" before parsing — the SDK's *Params records mark
		// tenant @NonNull but gson doesn't know to pass "" through default
		// convenience constructors. Clients typically omit tenant on the wire.
		Object raw = rpc.params();
		if (raw instanceof Map<?, ?> m) {
			@SuppressWarnings("unchecked")
			Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) m);
			mutable.putIfAbsent("tenant", "");
			// A2A SDK 1.2 documents CancelTaskParams.metadata as optional, but
			// its canonical record constructor defensively copies a non-null map.
			// Gson invokes that constructor directly, so normalise an omitted
			// wire field to the SDK builder's own empty-map default.
			if (cls == CancelTaskParams.class) mutable.putIfAbsent("metadata", Map.of());
			raw = mutable;
		}
		String json = JsonUtil.OBJECT_MAPPER.toJson(raw);
		try {
			return JsonUtil.OBJECT_MAPPER.fromJson(json, cls);
		} catch (JsonParseException e) {
			throw new InvalidInputException(INVALID_PARAMS + rpc.method(), e);
		} catch (RuntimeException e) {
			// The SDK's records refuse a missing required field or a bad value
			// with IllegalArgumentException from their constructor, which gson
			// reports as a bare RuntimeException caused by it
			if (!(e.getCause() instanceof IllegalArgumentException)) throw e;
			throw new InvalidInputException(INVALID_PARAMS + rpc.method(), e);
		}
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
