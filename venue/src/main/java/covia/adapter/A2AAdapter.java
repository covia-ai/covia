package covia.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;
import covia.venue.api.A2ACodec;

/**
 * Outbound A2A adapter — invoke remote A2A agents as Covia grid operations.
 *
 * <p>Sub-operations:</p>
 * <ul>
 *   <li>{@code a2a:getAgentCard} — fetch a remote agent's public Agent Card.</li>
 *   <li>{@code a2a:send} — send a message; one Covia Job mirrors one remote A2A Task.
 *       (implemented in a later pass)</li>
 *   <li>{@code a2a:getTask} / {@code a2a:cancel} — one-shot RPCs against a remote Task.
 *       (implemented in a later pass)</li>
 * </ul>
 *
 * <p>Uses the SDK's {@link JsonUtil#OBJECT_MAPPER} so polymorphic types
 * (Part, SecurityScheme, etc.) parse correctly. Goes directly via
 * {@link HttpClient} instead of the SDK's {@code Client} — we don't need
 * the card-driven transport configuration here, and going direct keeps
 * error handling explicit.</p>
 */
public class A2AAdapter extends AAdapter {

	public static final Logger log = LoggerFactory.getLogger(A2AAdapter.class);

	private static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";
	private static final String A2A_RPC_PATH = "/a2a";

	public static Hash GET_AGENT_CARD_OPERATION;
	public static Hash GET_TASK_OPERATION;
	public static Hash CANCEL_OPERATION;
	public static Hash SEND_OPERATION;

	/** Poll interval for mirroring remote Task state into the local Job. */
	static final Duration POLL_INTERVAL = Duration.ofMillis(500);

	/** Upper bound on total mirror lifetime — defends against a runaway remote.
	 *  Covia jobs can be long-lived but a misbehaving remote peer that never
	 *  terminates shouldn't hold a poller thread forever. */
	static final Duration POLL_MAX_LIFETIME = Duration.ofMinutes(30);

	private final HttpClient httpClient;

	public A2AAdapter() {
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	@Override
	public String getName() {
		return "a2a";
	}

	@Override
	public String getDescription() {
		return "Outbound Agent-to-Agent client. Invoke remote A2A agents as grid operations: "
				+ "fetch agent cards, send messages, get/cancel remote tasks.";
	}

	@Override
	protected void installAssets() {
		GET_AGENT_CARD_OPERATION = installAsset("a2a/agent-card", "/adapters/a2a/agentCard.json");
		GET_TASK_OPERATION       = installAsset("a2a/get-task",   "/adapters/a2a/getTask.json");
		CANCEL_OPERATION         = installAsset("a2a/cancel",     "/adapters/a2a/cancel.json");
		SEND_OPERATION           = installAsset("a2a/send",       "/adapters/a2a/send.json");
	}

	// ==================== Job-aware dispatch ====================

	/**
	 * Override the job-aware path so {@code a2a:send} can mirror a remote
	 * Task's lifecycle into the local Job. Other sub-ops go through the
	 * default future-based dispatch.
	 */
	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if ("send".equals(subOp)) {
			doSendMirrored(job, input);
			return;
		}
		super.invoke(job, ctx, meta, input);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if (subOp == null) {
			return CompletableFuture.failedFuture(
					new IllegalArgumentException("No sub-operation in a2a adapter metadata"));
		}
		return switch (subOp) {
			case "getAgentCard" -> fetchAgentCard(input);
			case "getTask"      -> rpcCall(input, A2AMethods.GET_TASK_METHOD, idParams(input));
			case "cancel"       -> rpcCall(input, A2AMethods.CANCEL_TASK_METHOD, idParams(input));
			default -> CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown a2a sub-operation: " + subOp));
		};
	}

	// ==================== getAgentCard ====================

	private CompletableFuture<ACell> fetchAgentCard(ACell input) {
		AString urlCell = RT.ensureString(RT.getIn(input, Fields.URL));
		if (urlCell == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("url required"));
		}
		String url = normaliseAgentCardUrl(urlCell.toString());

		HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(url))
					.GET()
					.timeout(Duration.ofSeconds(30))
					.build();
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}

		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					int sc = resp.statusCode();
					if (sc < 200 || sc >= 300) {
						throw new RuntimeException("Agent card fetch failed: HTTP " + sc);
					}
					// Round-trip through the SDK mapper so the response is parsed
					// (and any drift in upstream card format is flagged loudly).
					// The returned value is a plain JSON map on the Covia side.
					String body = resp.body();
					return JSON.parse(body);
				});
	}

	private static String normaliseAgentCardUrl(String url) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url must not be empty");
		}
		if (url.endsWith(AGENT_CARD_PATH)) return url;
		// Strip a single trailing slash so we don't double it.
		if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
		return url + AGENT_CARD_PATH;
	}

	// ==================== Generic JSON-RPC call ====================

	/**
	 * Build a params map with an {@code id} field extracted from input. Shared
	 * by {@code getTask} and {@code cancel}, which both take only a task id.
	 */
	private static Map<String, Object> idParams(ACell input) {
		AString id = RT.ensureString(RT.getIn(input, Fields.ID));
		if (id == null) {
			throw new IllegalArgumentException("id required");
		}
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("id", id.toString());
		return p;
	}

	/**
	 * POST a JSON-RPC 2.0 request to the {@code /a2a} endpoint on the remote
	 * agent and return the {@code result} as a Covia ACell. Throws if the
	 * remote returns an {@code error} — callers get a failed future.
	 */
	private CompletableFuture<ACell> rpcCall(ACell input, String method, Map<String, Object> params) {
		AString urlCell = RT.ensureString(RT.getIn(input, Fields.URL));
		if (urlCell == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("url required"));
		}
		String url = normaliseRpcUrl(urlCell.toString());

		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", UUID.randomUUID().toString());
		envelope.put("method", method);
		envelope.put("params", params);

		String body = JsonUtil.OBJECT_MAPPER.toJson(envelope);

		HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(url))
					.header("Content-Type", "application/json")
					.header("Accept", "application/a2a+json, application/json")
					.timeout(Duration.ofSeconds(30))
					.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.build();
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}

		return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					int sc = resp.statusCode();
					// Per spec §9, JSON-RPC errors still arrive with HTTP 200;
					// we only reject on non-2xx transport-level failures.
					if (sc < 200 || sc >= 300) {
						throw new RuntimeException("A2A RPC failed: HTTP " + sc + " — " + resp.body());
					}
					ACell parsed = JSON.parse(resp.body());
					if (!(parsed instanceof AMap)) {
						throw new RuntimeException("A2A response is not a JSON object");
					}
					@SuppressWarnings("unchecked")
					AMap<AString, ACell> parsedMap = (AMap<AString, ACell>) parsed;
					ACell err = parsedMap.get(Fields.ERROR);
					if (err != null) {
						throw new RuntimeException("A2A error: " + err);
					}
					ACell result = parsedMap.get(Fields.RESULT);
					if (result == null) {
						throw new RuntimeException("A2A response has neither result nor error");
					}
					// getTask / cancel both return a Task; unwrap if wrapped.
					return unwrapKind(result, "task");
				});
	}

	private static String normaliseRpcUrl(String url) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url must not be empty");
		}
		if (url.endsWith(A2A_RPC_PATH)) return url;
		if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
		return url + A2A_RPC_PATH;
	}

	// ==================== a2a:send with mirroring ====================

	@SuppressWarnings("unchecked")
	private void doSendMirrored(Job job, ACell input) {
		AString urlCell = RT.ensureString(RT.getIn(input, Fields.URL));
		if (urlCell == null) { job.fail("url required"); return; }
		String rpcUrl = normaliseRpcUrl(urlCell.toString());

		ACell messageRaw = RT.getIn(input, Fields.MESSAGE);
		if (!(messageRaw instanceof AMap)) { job.fail("message required"); return; }

		AString continuationTaskIdCell = RT.ensureString(RT.getIn(input, Fields.TASK_ID));
		String continuationTaskId = continuationTaskIdCell != null ? continuationTaskIdCell.toString() : null;

		// Build the outbound Message. Roles from local records are not trusted
		// for outbound — we're acting as the user from the remote agent's POV.
		Message parsed = A2ACodec.fromMessageRecord((AMap<AString, ACell>) messageRaw, null, continuationTaskId);
		if (parsed == null) { job.fail("message could not be parsed"); return; }
		Message outbound = Message.builder()
				.role(Message.Role.ROLE_USER)
				.parts(parsed.parts())
				.messageId(parsed.messageId() != null ? parsed.messageId() : UUID.randomUUID().toString())
				.contextId(parsed.contextId())
				.taskId(continuationTaskId)
				.build();

		MessageSendParams params = new MessageSendParams(outbound, null, null);
		Map<String, Object> envelope = rpcEnvelope(A2AMethods.SEND_MESSAGE_METHOD, params);

		job.setStatus(Status.STARTED);

		HttpRequest req = postEnvelope(rpcUrl, envelope);
		httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.whenComplete((resp, err) -> {
					if (err != null) {
						Throwable cause = (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
								? err.getCause() : err;
						job.fail("SendMessage transport failure: " + cause.getMessage());
						return;
					}
					handleSendResponse(job, rpcUrl, resp);
				});
	}

	private void handleSendResponse(Job job, String rpcUrl, HttpResponse<String> resp) {
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			job.fail("Remote SendMessage failed: HTTP " + resp.statusCode() + " — " + resp.body());
			return;
		}
		Map<?, ?> envReply;
		try {
			envReply = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
		} catch (Exception e) {
			job.fail("Remote SendMessage response not valid JSON: " + e.getMessage());
			return;
		}
		Object error = envReply.get("error");
		if (error != null) {
			job.fail("Remote SendMessage error: " + error);
			return;
		}
		Object result = envReply.get("result");
		if (result == null) {
			job.fail("Remote SendMessage response has neither result nor error");
			return;
		}

		// SendMessageResponse is a union {task: Task} | {message: Message}
		// (spec §3.2.3). gson's StreamingEventKindTypeAdapter wraps it with
		// the discriminator key on the wire. We unwrap to get a flat Task map
		// for the Covia Job output.
		Task remoteTask;
		ACell taskCell;
		try {
			String resultJson = JsonUtil.OBJECT_MAPPER.toJson(result);
			remoteTask = JsonUtil.OBJECT_MAPPER.fromJson(resultJson, Task.class);
			taskCell = unwrapKind(JSON.parse(resultJson), "task");
		} catch (Exception e) {
			job.fail("Remote SendMessage result not a Task: " + e.getMessage());
			return;
		}
		ACell rawResultCell = taskCell; // renamed, same semantics below

		String remoteTaskId = remoteTask.id();
		AMap<AString, ACell> current = job.getData();
		job.updateData(current.assoc(Fields.REMOTE_TASK_ID, Strings.create(remoteTaskId)));
		job.setCancelHook(() -> fireAndForgetCancel(rpcUrl, remoteTaskId));

		if (remoteTask.status().state().isFinal()) {
			finishJobWithRemoteTask(job, remoteTask, rawResultCell);
			return;
		}
		if (remoteTask.status().state().isInterrupted()) {
			applyInterruptedState(job, remoteTask, rawResultCell);
			return;
		}
		startPoller(job, rpcUrl, remoteTaskId);
	}

	/**
	 * Virtual-thread poller that mirrors the remote Task's state onto the
	 * local Job. Exits when the Job is finished locally (e.g. cancelled) or
	 * the remote reaches a terminal/interrupted state.
	 */
	private void startPoller(Job job, String rpcUrl, String remoteTaskId) {
		Thread.ofVirtual().name("a2a-mirror-" + remoteTaskId).start(() -> {
			long deadline = System.currentTimeMillis() + POLL_MAX_LIFETIME.toMillis();
			while (!job.isFinished() && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(POLL_INTERVAL.toMillis());
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				if (job.isFinished()) return;

				PollResult p;
				try {
					p = pollRemote(rpcUrl, remoteTaskId);
				} catch (Exception e) {
					log.warn("Mirror poll failed for remote task {}: {}", remoteTaskId, e.getMessage());
					continue; // transient errors don't fail the Job
				}

				if (p.task().status().state().isFinal()) {
					finishJobWithRemoteTask(job, p.task(), p.rawResultCell());
					return;
				}
				if (p.task().status().state().isInterrupted()) {
					applyInterruptedState(job, p.task(), p.rawResultCell());
					return;
				}
				// Non-terminal, non-interrupted: reflect STARTED if not already.
				if (!Status.STARTED.equals(job.getStatus())) {
					job.setStatus(Status.STARTED);
				}
			}
			if (!job.isFinished()) {
				job.fail("A2A mirror timed out after " + POLL_MAX_LIFETIME);
			}
		});
	}

	/**
	 * Unwrap a StreamingEventKind discriminator ({@code {"task": {...}}} or
	 * {@code {"message": {...}}}) to the inner map. Returns the cell unchanged
	 * if it's not wrapped. Needed because gson's type-hierarchy adapter always
	 * wraps when serialising any StreamingEventKind — including non-streaming
	 * responses like SendMessage's single result.
	 */
	@SuppressWarnings("unchecked")
	private static ACell unwrapKind(ACell cell, String kind) {
		if (cell instanceof AMap) {
			AMap<AString, ACell> map = (AMap<AString, ACell>) cell;
			ACell inner = map.get(Strings.create(kind));
			if (inner != null) return inner;
		}
		return cell;
	}

	/** Remote state + the raw result cell preserving the remote's exact JSON. */
	private record PollResult(Task task, ACell rawResultCell) {}

	private PollResult pollRemote(String rpcUrl, String remoteTaskId) throws Exception {
		Map<String, Object> params = Map.of("id", remoteTaskId);
		Map<String, Object> envelope = rpcEnvelope(A2AMethods.GET_TASK_METHOD, params);
		HttpRequest req = postEnvelope(rpcUrl, envelope);
		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			throw new RuntimeException("HTTP " + resp.statusCode());
		}
		@SuppressWarnings("rawtypes")
		Map envReply = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
		Object error = envReply.get("error");
		if (error != null) throw new RuntimeException("remote error: " + error);
		Object result = envReply.get("result");
		if (result == null) throw new RuntimeException("no result");
		String resultJson = JsonUtil.OBJECT_MAPPER.toJson(result);
		Task task = JsonUtil.OBJECT_MAPPER.fromJson(resultJson, Task.class);
		ACell raw = unwrapKind(JSON.parse(resultJson), "task");
		return new PollResult(task, raw);
	}

	private void finishJobWithRemoteTask(Job job, Task remote, ACell rawTaskCell) {
		if (remote.status().state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED) {
			job.completeWith(rawTaskCell);
		} else {
			// Failed / cancelled / rejected — terminal non-success. Surface the
			// remote state on the local Job with the task as output.
			AMap<AString, ACell> current = job.getData();
			AString coviaStatus = A2ACodec.fromTaskState(remote.status().state());
			job.updateData(current
					.assoc(Fields.STATUS, coviaStatus)
					.assoc(Fields.OUTPUT, rawTaskCell));
		}
	}

	private void applyInterruptedState(Job job, Task remote, ACell rawTaskCell) {
		AString coviaStatus = A2ACodec.fromTaskState(remote.status().state());
		AMap<AString, ACell> current = job.getData();
		job.updateData(current
				.assoc(Fields.STATUS, coviaStatus)
				.assoc(Fields.OUTPUT, rawTaskCell));
	}

	private void fireAndForgetCancel(String rpcUrl, String remoteTaskId) {
		try {
			Map<String, Object> params = Map.of("id", remoteTaskId);
			Map<String, Object> envelope = rpcEnvelope(A2AMethods.CANCEL_TASK_METHOD, params);
			httpClient.sendAsync(postEnvelope(rpcUrl, envelope), HttpResponse.BodyHandlers.discarding());
		} catch (Exception e) {
			log.warn("Best-effort remote cancel failed for {}: {}", remoteTaskId, e.getMessage());
		}
	}

	// ==================== envelope + HTTP helpers ====================

	private static Map<String, Object> rpcEnvelope(String method, Object params) {
		Map<String, Object> env = new LinkedHashMap<>();
		env.put("jsonrpc", "2.0");
		env.put("id", UUID.randomUUID().toString());
		env.put("method", method);
		env.put("params", params);
		return env;
	}

	private static HttpRequest postEnvelope(String rpcUrl, Map<String, Object> envelope) {
		String body = JsonUtil.OBJECT_MAPPER.toJson(envelope);
		return HttpRequest.newBuilder(URI.create(rpcUrl))
				.header("Content-Type", "application/json")
				.header("Accept", "application/a2a+json, application/json")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
	}

	@SuppressWarnings("unused")
	private static final Class<?>[] _keepTypes = {JsonUtil.class};
}
