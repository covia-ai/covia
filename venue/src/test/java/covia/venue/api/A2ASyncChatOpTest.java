package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.server.VenueServer;

/**
 * Regression for #178 — A2A {@code SendMessage} on a fresh task must not come
 * back {@code TASK_STATE_FAILED} when the configured {@code defaultChatOp}
 * completes synchronously.
 *
 * <p>The main {@link A2ATest} suite configures {@code v/test/ops/chat}, a
 * multi-turn op that stays live in {@code activeJobs}; the bug only surfaces
 * with an op that completes and is evicted before the handler re-reads it
 * (e.g. {@code v/test/ops/echo}). This test pins that case with a dedicated
 * server so it can't regress silently.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2ASyncChatOpTest {

	private VenueServer server;
	private String baseUrl;
	private HttpClient http;

	@BeforeAll
	public void setup() {
		server = VenueServer.launch(Maps.of(
				Strings.create("port"), 0, // ephemeral
				Fields.A2A, Maps.of(
						Strings.create("defaultChatOp"), Strings.create("v/test/ops/echo")),
				Config.AUTH, Maps.of(
						Config.PUBLIC, Maps.of(
								Config.ENABLED, true,
								Config.CAPS, Strings.create("unrestricted")))));
		baseUrl = "http://localhost:" + server.port();
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterAll
	public void teardown() {
		try {
			server.close();
		} catch (Exception ignored) {
			// best effort
		}
	}

	@Test
	public void sendMessage_synchronousChatOp_doesNotFail() throws Exception {
		Message msg = userMessage("hello");
		MessageSendParams params = new MessageSendParams(msg, null, null);
		Map<String, Object> resp = rpcCall("req-1", "SendMessage", params);

		assertNull(resp.get("error"), "Unexpected JSON-RPC error: " + resp.get("error"));
		Task task = extractTask(resp);
		assertNotNull(task, "Expected a Task result");
		assertNotNull(task.status());
		assertNotEquals(TaskState.TASK_STATE_FAILED, task.status().state(),
				"A synchronously-completing chat op must not yield a FAILED Task (#178)");
		assertEquals(1, task.history().size(),
				"The initiating message must be seeded into Task history (#178)");
	}

	// ---- helpers (mirrors A2ATest) ----

	private Message userMessage(String text) {
		return Message.builder()
				.role(Message.Role.ROLE_USER)
				.parts(List.<Part<?>>of(new TextPart(text, null)))
				.messageId("msg-" + UUID.randomUUID())
				.build();
	}

	private Map<String, Object> rpcCall(String id, String method, Object params) throws Exception {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("jsonrpc", "2.0");
		envelope.put("id", id);
		envelope.put("method", method);
		envelope.put("params", params);
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/a2a"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JsonUtil.OBJECT_MAPPER.toJson(envelope)))
				.timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode(), "HTTP " + resp.statusCode() + ": " + resp.body());
		return JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
	}

	private Task extractTask(Map<String, Object> rpcResp) {
		Object result = rpcResp.get("result");
		if (result == null) return null;
		return JsonUtil.OBJECT_MAPPER.fromJson(JsonUtil.OBJECT_MAPPER.toJson(result), Task.class);
	}
}
