package covia.venue.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
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
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.SseServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseHandler;

/**
 * A2A (Agent-to-Agent) server API layered on top of a Covia Venue.
 *
 * <p>Implements the A2A Protocol v1.0 JSON-RPC binding: Agent Card discovery
 * at {@code /.well-known/agent-card.json} and JSON-RPC 2.0 at {@code /a2a}.
 * Supports {@code SendMessage}, {@code GetTask}, {@code CancelTask} in P0.
 * Streaming ({@code SendStreamingMessage}, {@code SubscribeToTask}) and
 * push-notification-config methods are intentionally unimplemented for now —
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

	public void addRoutes(Javalin javalin) {
		javalin.get("/.well-known/agent-card.json", this::getAgentCard);
		javalin.post("/a2a", this::handleJsonRpc);
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
		// Streaming supported via SendStreamingMessage + SubscribeToTask (P2).
		// Push notifications + extended card still P3.
		AgentCapabilities capabilities = new AgentCapabilities(true, false, false, null);
		// AgentInterface requires non-null fields; we don't use tenanted routing,
		// so pass an empty tenant string per spec "optional" semantics.
		AgentInterface iface = new AgentInterface("JSONRPC", baseUrl + "/a2a", "", "1.0");

		return AgentCard.builder()
				.name(agentName)
				.description(agentDescription)
				.version(agentVersion)
				.provider(agentProvider)
				.capabilities(capabilities)
				.supportedInterfaces(List.of(iface))
				.defaultInputModes(List.of("text/plain", "application/json"))
				.defaultOutputModes(List.of("text/plain", "application/json"))
				.skills(List.<AgentSkill>of())  // populated from adapter-installed assets in a later pass
				.build();
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
					doGetTask(ctx, id, params);
				}
				case A2AMethods.CANCEL_TASK_METHOD -> {
					CancelTaskParams params = parseParams(paramsRaw, CancelTaskParams.class);
					doCancelTask(ctx, id, params);
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
				case A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD,
				     A2AMethods.LIST_TASK_METHOD,
				     A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD ->
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
		RequestContext rctx = RequestContext.of(AuthMiddleware.getCallerDID(ctx));

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

	private void doGetTask(Context ctx, Object id, TaskQueryParams params) {
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
		RequestContext rctx = RequestContext.of(AuthMiddleware.getCallerDID(ctx));

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

	private void doCancelTask(Context ctx, Object id, CancelTaskParams params) {
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
		RequestContext rctx = RequestContext.of(AuthMiddleware.getCallerDID(ctx));

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

	// ==================== Streaming methods ====================

	private void doSendStreamingMessage(Context ctx, Object id, MessageSendParams params) {
		if (params == null || params.message() == null) {
			writeError(ctx, id, A2AErrorCodes.INVALID_PARAMS, "message required");
			return;
		}
		Message incoming = params.message();
		RequestContext rctx = RequestContext.of(AuthMiddleware.getCallerDID(ctx));

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
		RequestContext rctx = RequestContext.of(AuthMiddleware.getCallerDID(ctx));

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
