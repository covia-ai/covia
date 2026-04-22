package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import covia.venue.TestServer;

/**
 * End-to-end tests for the A2A server.
 *
 * <p>Drives the Javalin server with real HTTP and parses responses using the
 * official a2a-java-sdk spec POJOs + {@link JsonUtil#OBJECT_MAPPER}. Any drift
 * between our wire format and the spec's expectations surfaces here.</p>
 *
 * <p>Intentionally uses bare HTTP + the SDK's JSON mapper instead of the full
 * {@code Client} + transport stack — smaller test surface for P0. Full Client
 * integration arrives in P1 alongside streaming.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2ATest {

	static final String BASE_URL = TestServer.BASE_URL;

	private HttpClient http;

	@BeforeAll
	public void setup() {
		assertNotNull(TestServer.SERVER, "Test server must be running");
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	// ============================================================
	// Agent Card discovery
	// ============================================================

	@Test
	public void agentCard_returns200WithSpecShape() throws Exception {
		HttpResponse<String> resp = get("/.well-known/agent-card.json");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("a2a+json")
				|| resp.headers().firstValue("Content-Type").orElse("").contains("json"));

		AgentCard card = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), AgentCard.class);
		assertNotNull(card);
		assertNotNull(card.name());
		assertNotNull(card.description());
		assertNotNull(card.version());
		assertNotNull(card.capabilities());
		assertNotNull(card.supportedInterfaces());
		assertFalse(card.supportedInterfaces().isEmpty(), "Must advertise at least one interface");
		assertEquals("JSONRPC", card.supportedInterfaces().get(0).protocolBinding());
		assertEquals("1.0", card.supportedInterfaces().get(0).protocolVersion());
		assertTrue(card.supportedInterfaces().get(0).url().endsWith("/a2a"));
		assertNotNull(card.defaultInputModes());
		assertNotNull(card.defaultOutputModes());
		assertNotNull(card.skills()); // may be empty
	}

	// ============================================================
	// SendMessage — new task (no taskId)
	// ============================================================

	@Test
	public void sendMessage_newTaskReturnsTaskWithSubmittedOrTerminalState() throws Exception {
		Message msg = userMessage("hello");
		MessageSendParams params = new MessageSendParams(msg, null, null);

		Map<String, Object> envelope = rpcEnvelope("req-1", "SendMessage", params);
		HttpResponse<String> resp = post("/a2a", envelope);

		assertEquals(200, resp.statusCode(), resp.body());
		Map<String, Object> parsed = parseMap(resp.body());
		assertNull(parsed.get("error"), "Unexpected error: " + parsed.get("error"));
		assertEquals("req-1", parsed.get("id"));

		Task task = extractTask(parsed);
		assertNotNull(task);
		assertNotNull(task.id(), "Task.id must be set");
		assertNotNull(task.contextId(), "Task.contextId must be set");
		assertNotNull(task.status());
		assertNotNull(task.status().state());
		// State must be one of the valid lifecycle values — the TestAdapter's
		// `chat` op doesn't immediately complete, so we expect a non-terminal
		// state on first return, but be tolerant of fast adapters.
		TaskState state = task.status().state();
		assertTrue(
			state == TaskState.TASK_STATE_SUBMITTED
			|| state == TaskState.TASK_STATE_WORKING
			|| state == TaskState.TASK_STATE_COMPLETED
			|| state == TaskState.TASK_STATE_FAILED
			|| state == TaskState.TASK_STATE_INPUT_REQUIRED,
			"Unexpected initial state: " + state);
	}

	@Test
	public void sendMessage_newTaskSeedsHistoryWithFirstMessage() throws Exception {
		Message msg = userMessage("history-seed-check");
		MessageSendParams params = new MessageSendParams(msg, null, null);
		Map<String, Object> resp = rpcCall("req-2", "SendMessage", params);

		Task task = extractTask(resp);
		assertNotNull(task);
		List<Message> history = task.history();
		assertNotNull(history);
		assertEquals(1, history.size(), "First message should appear in history");
		assertEquals(Message.Role.ROLE_USER, history.get(0).role());
		assertTrue(history.get(0).parts().get(0) instanceof TextPart);
		assertEquals("history-seed-check",
				((TextPart) history.get(0).parts().get(0)).text());
	}

	// ============================================================
	// GetTask
	// ============================================================

	@Test
	public void getTask_returnsExistingTask() throws Exception {
		Task created = extractTask(rpcCall("req-3", "SendMessage",
				new MessageSendParams(userMessage("ping"), null, null)));
		assertNotNull(created);

		Map<String, Object> resp = rpcCall("req-4", "GetTask",
				new TaskQueryParams(created.id(), null));
		Task fetched = extractTask(resp);
		assertNotNull(fetched);
		assertEquals(created.id(), fetched.id());
		assertEquals(created.contextId(), fetched.contextId());
	}

	@Test
	public void getTask_unknownIdReturnsTaskNotFoundError() throws Exception {
		Map<String, Object> resp = rpcCall("req-5", "GetTask",
				new TaskQueryParams("00000000000000000000000000000001", null));
		Map<String, Object> err = cast(resp.get("error"));
		assertNotNull(err, "Expected error, got: " + resp);
		assertEquals(-32001.0, toDouble(err.get("code")), 0.001);
	}

	@Test
	public void getTask_invalidIdReturnsInvalidParamsError() throws Exception {
		Map<String, Object> resp = rpcCall("req-6", "GetTask",
				new TaskQueryParams("not-a-hex-blob", null));
		Map<String, Object> err = cast(resp.get("error"));
		assertNotNull(err);
		assertEquals(-32602.0, toDouble(err.get("code")), 0.001);
	}

	// ============================================================
	// CancelTask
	// ============================================================

	@Test
	public void cancelTask_movesRunningTaskToCanceled() throws Exception {
		Task created = extractTask(rpcCall("req-7", "SendMessage",
				new MessageSendParams(userMessage("to-be-canceled"), null, null)));
		assertNotNull(created);

		Map<String, Object> cancelResp = rpcCall("req-8", "CancelTask",
				new CancelTaskParams(created.id()));
		// Either we got the cancelled Task back, or the task was already done
		// and we got TASK_NOT_CANCELABLE — both are spec-valid.
		Map<String, Object> err = cast(cancelResp.get("error"));
		if (err != null) {
			assertEquals(-32002.0, toDouble(err.get("code")), 0.001,
					"Expected TaskNotCancelable, got: " + err);
			return;
		}
		Task cancelled = extractTask(cancelResp);
		assertEquals(created.id(), cancelled.id());
		assertEquals(TaskState.TASK_STATE_CANCELED, cancelled.status().state());
	}

	// ============================================================
	// Error handling
	// ============================================================

	@Test
	public void unknownMethod_returnsMethodNotFound() throws Exception {
		Map<String, Object> resp = rpcCall("req-9", "BogusMethod", new LinkedHashMap<>());
		Map<String, Object> err = cast(resp.get("error"));
		assertNotNull(err);
		assertEquals(-32601.0, toDouble(err.get("code")), 0.001);
	}

	@Test
	public void streamingMethodNotYetImplemented() throws Exception {
		MessageSendParams params = new MessageSendParams(userMessage("stream"), null, null);
		Map<String, Object> resp = rpcCall("req-10", "SendStreamingMessage", params);
		Map<String, Object> err = cast(resp.get("error"));
		assertNotNull(err);
		assertEquals(-32004.0, toDouble(err.get("code")), 0.001,
				"Expected UnsupportedOperationError for not-yet-implemented stream");
	}

	@Test
	public void malformedJson_returnsParseError() throws Exception {
		HttpResponse<String> resp = postRaw("/a2a", "not json");
		Map<String, Object> parsed = parseMap(resp.body());
		Map<String, Object> err = cast(parsed.get("error"));
		assertNotNull(err);
		assertEquals(-32700.0, toDouble(err.get("code")), 0.001);
	}

	@Test
	public void missingMethod_returnsInvalidRequest() throws Exception {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", "req-11");
		// method intentionally omitted
		HttpResponse<String> resp = post("/a2a", envelope);
		Map<String, Object> parsed = parseMap(resp.body());
		Map<String, Object> err = cast(parsed.get("error"));
		assertNotNull(err);
		assertEquals(-32600.0, toDouble(err.get("code")), 0.001);
	}

	// ============================================================
	// Helpers
	// ============================================================

	private Message userMessage(String text) {
		return Message.builder()
				.role(Message.Role.ROLE_USER)
				.parts(List.<Part<?>>of(new TextPart(text, null)))
				.messageId("msg-" + UUID.randomUUID())
				.build();
	}

	private Map<String, Object> rpcEnvelope(String id, String method, Object params) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("jsonrpc", "2.0");
		e.put("id", id);
		e.put("method", method);
		e.put("params", params);
		return e;
	}

	private Map<String, Object> rpcCall(String id, String method, Object params) throws Exception {
		HttpResponse<String> resp = post("/a2a", rpcEnvelope(id, method, params));
		assertEquals(200, resp.statusCode(), "HTTP status: " + resp.statusCode() + " body: " + resp.body());
		return parseMap(resp.body());
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.GET().timeout(Duration.ofSeconds(10)).build();
		return http.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, Object body) throws Exception {
		String json = JsonUtil.OBJECT_MAPPER.toJson(body);
		return postRaw(path, json);
	}

	private HttpResponse<String> postRaw(String path, String raw) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(raw))
				.timeout(Duration.ofSeconds(10)).build();
		return http.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private Map<String, Object> parseMap(String body) {
		return JsonUtil.OBJECT_MAPPER.fromJson(body, Map.class);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> cast(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	/** Extract a Task record from a JSON-RPC "result" field by re-serialising it. */
	private Task extractTask(Map<String, Object> rpcResp) {
		Object result = rpcResp.get("result");
		if (result == null) return null;
		String json = JsonUtil.OBJECT_MAPPER.toJson(result);
		return JsonUtil.OBJECT_MAPPER.fromJson(json, Task.class);
	}

	private double toDouble(Object o) {
		if (o instanceof Number n) return n.doubleValue();
		if (o instanceof String s) return Double.parseDouble(s);
		throw new AssertionError("not numeric: " + o);
	}
}
