package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import covia.venue.TestServer;

/**
 * E2E tests for A2A streaming methods: {@code SendStreamingMessage} and
 * {@code SubscribeToTask}. Consumes SSE via {@link HttpResponse.BodyHandlers#ofLines}
 * and parses each {@code data: ...} frame as a JSON-RPC envelope.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2AStreamingTest {

	static final String BASE_URL = TestServer.BASE_URL;
	private HttpClient http;

	@BeforeAll
	public void setup() {
		assertNotNull(TestServer.SERVER);
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	// ============================================================
	// SendStreamingMessage
	// ============================================================

	@Test
	public void sendStreamingMessage_emitsInitialTaskFrame() throws Exception {
		Message msg = userMessage("streaming hello");
		Map<String, Object> envelope = rpcEnvelope("stream-1", "SendStreamingMessage",
				new MessageSendParams(msg, null, null));

		List<Map<String, Object>> frames = readFrames(envelope, 1, 3000);
		assertTrue(frames.size() >= 1, "Expected at least one SSE frame, got " + frames.size());

		// First frame wraps a Task (initial snapshot).
		Map<String, Object> first = frames.get(0);
		assertEquals("2.0", first.get("jsonrpc"));
		assertEquals("stream-1", first.get("id"));
		Map<String, Object> result = castMap(first.get("result"));
		assertNotNull(result);
		Map<String, Object> task = castMap(result.get("task"));
		assertNotNull(task, "First frame must carry a Task under the `task` discriminator");
		assertNotNull(task.get("id"));
		Map<String, Object> status = castMap(task.get("status"));
		assertNotNull(status);
		assertNotNull(status.get("state"));
	}

	@Test
	public void sendStreamingMessage_contentTypeIsEventStream() throws Exception {
		Message msg = userMessage("check headers");
		Map<String, Object> envelope = rpcEnvelope("stream-2", "SendStreamingMessage",
				new MessageSendParams(msg, null, null));

		HttpResponse<java.util.stream.Stream<String>> resp = postStreaming(envelope);
		try {
			assertEquals(200, resp.statusCode());
			String contentType = resp.headers().firstValue("Content-Type").orElse("");
			assertTrue(contentType.contains("text/event-stream"),
					"Expected text/event-stream, got: " + contentType);
		} finally {
			resp.body().close();
		}
	}

	// ============================================================
	// SubscribeToTask — error paths
	// ============================================================

	@Test
	public void subscribeToTask_unknownIdReturnsTaskNotFound() throws Exception {
		Map<String, Object> envelope = rpcEnvelope("sub-1", "SubscribeToTask",
				Map.of("id", "00000000000000000000000000000001"));
		HttpResponse<String> resp = postJson(envelope);
		// Error path: JSON response (not SSE) with TaskNotFoundError.
		Map<String, Object> parsed = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
		Map<String, Object> err = castMap(parsed.get("error"));
		assertNotNull(err, "Expected error, got: " + parsed);
		assertEquals(-32001.0, toDouble(err.get("code")), 0.001);
	}

	// ============================================================
	// helpers
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

	private HttpResponse<String> postJson(Map<String, Object> envelope) throws Exception {
		String body = JsonUtil.OBJECT_MAPPER.toJson(envelope);
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.timeout(Duration.ofSeconds(10))
				.build();
		return http.send(req, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<java.util.stream.Stream<String>> postStreaming(Map<String, Object> envelope) throws Exception {
		String body = JsonUtil.OBJECT_MAPPER.toJson(envelope);
		HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a"))
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.timeout(Duration.ofSeconds(10))
				.build();
		return http.send(req, HttpResponse.BodyHandlers.ofLines());
	}

	/**
	 * Open an SSE stream and parse at least {@code minFrames} {@code data:}
	 * lines as JSON-RPC envelopes. Returns after minFrames are collected or
	 * after the timeout. Idle streams (task stuck in INPUT_REQUIRED) are
	 * tolerated by the timeout.
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> readFrames(Map<String, Object> envelope,
			int minFrames, long timeoutMs) throws Exception {
		HttpResponse<java.util.stream.Stream<String>> resp = postStreaming(envelope);
		assertEquals(200, resp.statusCode(), "SSE POST expected 200");
		List<Map<String, Object>> frames = new java.util.ArrayList<>();
		long deadline = System.currentTimeMillis() + timeoutMs;

		// Consume the stream on a background thread so we can enforce the
		// timeout from the test thread — JDK's ofLines() blocks indefinitely.
		// The test thread closes the stream below to stop us; that surfaces
		// as an UncheckedIOException out of forEach which we swallow — it's
		// the expected shutdown path, not a test failure.
		Thread consumer = Thread.ofVirtual().start(() -> {
			try (java.util.stream.Stream<String> lines = resp.body()) {
				lines.forEach(line -> {
					if (line.startsWith("data: ") || line.startsWith("data:")) {
						String payload = line.substring(line.indexOf(':') + 1).trim();
						try {
							frames.add(JsonUtil.OBJECT_MAPPER.fromJson(payload, Map.class));
						} catch (Exception ignored) {
							// ignore non-JSON data lines
						}
						synchronized (frames) { frames.notifyAll(); }
					}
				});
			} catch (java.io.UncheckedIOException ignored) {
				// stream closed by the test thread — expected
			}
		});

		synchronized (frames) {
			while (frames.size() < minFrames && System.currentTimeMillis() < deadline) {
				frames.wait(Math.max(1, deadline - System.currentTimeMillis()));
			}
		}
		// Best-effort stream cancellation.
		try { resp.body().close(); } catch (Exception ignored) {}
		if (!consumer.join(Duration.ofMillis(500))) {
			consumer.interrupt();
		}
		if (frames.size() < minFrames) {
			throw new TimeoutException("Only got " + frames.size() + " frame(s) within " + timeoutMs + "ms");
		}
		return frames;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	private double toDouble(Object o) {
		if (o instanceof Number n) return n.doubleValue();
		if (o instanceof String s) return Double.parseDouble(s);
		throw new AssertionError("not numeric: " + o);
	}

	@SuppressWarnings("unused")
	private static final TimeUnit _unused = TimeUnit.MILLISECONDS;
}
