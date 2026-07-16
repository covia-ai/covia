package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * #200 — the job SSE route must be reachable.
 *
 * <p>The greedy {@code <id>} matcher on {@code GET jobs/<id>} also matched
 * {@code jobs/<id>/sse}, shadowing the SSE route: every subscription returned
 * {@code 400 "Job request requires a job ID as a valid hex string"}. Job ids
 * are single-segment hex strings, so the job routes use the segment matcher
 * {@code {id}}.</p>
 *
 * <p>Uses the shared {@link TestServer} (unrestricted public) rather than its
 * own venue — see {@link CoviaAssetRefTest} for the rationale.</p>
 */
public class JobRoutesTest {

	private final String base = TestServer.BASE_URL;
	private final HttpClient http = HttpClient.newHttpClient();

	/** Invokes the given test op asynchronously and returns the job id (bare hex). */
	private String invoke(String op) throws Exception {
		String body = "{\"operation\":\"" + op + "\",\"input\":{\"data\":\"sse-test\"}}";
		HttpResponse<String> r = http.send(
			HttpRequest.newBuilder().uri(new URI(base + "/api/v1/invoke"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString());
		assertTrue(r.statusCode() == 200 || r.statusCode() == 201, r.body());
		ACell record = JSON.parse(r.body());
		String id = RT.ensureString(RT.getIn(record, "id")).toString();
		assertNotNull(id, "invoke response must carry a job id: " + r.body());
		return id.startsWith("0x") ? id.substring(2) : id;
	}

	@Test
	public void jobStatusRouteResolves() throws Exception {
		String id = invoke("v/test/ops/echo");
		HttpResponse<String> status = http.send(
			HttpRequest.newBuilder().uri(new URI(base + "/api/v1/jobs/" + id)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, status.statusCode(), status.body());
	}

	/** Reads SSE data lines from the stream, completing when {@code until}
	 *  matches a line; returns all data lines seen. */
	private CompletableFuture<java.util.List<String>> readUntil(InputStream in, String until) {
		return CompletableFuture.supplyAsync(() -> {
			java.util.List<String> dataLines = new java.util.ArrayList<>();
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(in));
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("data:")) {
						dataLines.add(line);
						if (line.contains(until)) return dataLines;
					}
				}
			} catch (IOException ignored) {
				// stream closed
			}
			return dataLines;
		});
	}

	/**
	 * #225 — status-change broadcasts must reach subscribers. Subscribes with
	 * the 0x-PREFIXED job id exactly as the REST API renders it (the broken
	 * case: subscriptions were keyed by the raw path parameter while
	 * broadcasts key by bare hex, so real clients only ever saw the initial
	 * frame), then drives a status change and asserts the second frame
	 * arrives — and that the stream closes after the terminal frame.
	 */
	@Test
	public void jobSseBroadcastsStatusTransitions() throws Exception {
		String id = invoke("v/test/ops/never");

		HttpResponse<InputStream> sse = http.send(
			HttpRequest.newBuilder()
				.uri(new URI(base + "/api/v1/jobs/0x" + id + "/sse"))
				.header("Accept", "text/event-stream")
				.GET().build(),
			HttpResponse.BodyHandlers.ofInputStream());
		InputStream in = sse.body();
		try {
			assertEquals(200, sse.statusCode());

			// Wait for the initial frame — registration precedes it, so once
			// it arrives the subscription is live and the cancel broadcast
			// cannot be missed.
			CompletableFuture<java.util.List<String>> initial = readUntil(in, "status");
			assertTrue(!initial.get(10, TimeUnit.SECONDS).isEmpty(),
				"initial job-update frame must arrive on connect");

			// Drive a status transition and expect its broadcast frame
			CompletableFuture<java.util.List<String>> terminal = readUntil(in, "CANCELLED");
			HttpResponse<String> cancel = http.send(
				HttpRequest.newBuilder().uri(new URI(base + "/api/v1/jobs/" + id + "/cancel"))
					.PUT(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
			assertEquals(200, cancel.statusCode(), cancel.body());

			java.util.List<String> frames = terminal.get(10, TimeUnit.SECONDS);
			assertTrue(frames.stream().anyMatch(l -> l.contains("CANCELLED")),
				"the status-change broadcast must reach the subscriber (#225): " + frames);
		} finally {
			in.close();
		}
	}

	/** #222 — a missing Accept header must stream (the /sse path is
	 *  unambiguous), never return a silent empty 200. */
	@Test
	public void jobSseStreamsWithoutAcceptHeader() throws Exception {
		String id = invoke("v/test/ops/never");
		HttpResponse<InputStream> sse = http.send(
			HttpRequest.newBuilder()
				.uri(new URI(base + "/api/v1/jobs/0x" + id + "/sse"))
				.GET().build(),
			HttpResponse.BodyHandlers.ofInputStream());
		InputStream in = sse.body();
		try {
			assertEquals(200, sse.statusCode());
			assertTrue(sse.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
				"no Accept header must still stream SSE, was: " + sse.headers().firstValue("Content-Type"));
			assertTrue(!readUntil(in, "status").get(10, TimeUnit.SECONDS).isEmpty(),
				"initial frame must arrive without an Accept header");
		} finally {
			in.close();
			http.send(HttpRequest.newBuilder().uri(new URI(base + "/api/v1/jobs/" + id + "/cancel"))
				.PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	/** #222 — an explicitly non-SSE Accept fails loudly with 406 and a
	 *  remedy, never a silent empty 200. */
	@Test
	public void jobSseExplicitNonSseAcceptIs406() throws Exception {
		String id = invoke("v/test/ops/echo");
		HttpResponse<String> r = http.send(
			HttpRequest.newBuilder()
				.uri(new URI(base + "/api/v1/jobs/0x" + id + "/sse"))
				.header("Accept", "application/json")
				.GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(406, r.statusCode(), r.body());
		assertTrue(r.body().contains("text/event-stream"), "the 406 must carry the remedy: " + r.body());
	}

	/** Subscribing to an already-terminal job delivers its final record as
	 *  one frame and closes — the subscriber always learns the outcome. */
	@Test
	public void jobSseFinishedJobDeliversTerminalFrame() throws Exception {
		String id = invoke("v/test/ops/echo");
		// Wait until terminal via polling
		for (int i = 0; i < 100; i++) {
			HttpResponse<String> status = http.send(
				HttpRequest.newBuilder().uri(new URI(base + "/api/v1/jobs/" + id)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (status.body().contains("COMPLETE")) break;
			Thread.sleep(50);
		}

		HttpResponse<InputStream> sse = http.send(
			HttpRequest.newBuilder()
				.uri(new URI(base + "/api/v1/jobs/0x" + id + "/sse"))
				.header("Accept", "text/event-stream")
				.GET().build(),
			HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = sse.body()) {
			assertEquals(200, sse.statusCode());
			java.util.List<String> frames = readUntil(in, "COMPLETE").get(10, TimeUnit.SECONDS);
			assertTrue(frames.stream().anyMatch(l -> l.contains("COMPLETE")),
				"terminal record must be delivered to a late subscriber: " + frames);
		}
	}

	/** An unparseable job id is a plain 400, before any stream is committed. */
	@Test
	public void jobSseInvalidIdIs400() throws Exception {
		HttpResponse<String> r = http.send(
			HttpRequest.newBuilder()
				.uri(new URI(base + "/api/v1/jobs/not-a-job-id/sse"))
				.header("Accept", "text/event-stream")
				.GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(400, r.statusCode(), r.body());
	}

	@Test
	public void jobSseRouteResolvesAndStreams() throws Exception {
		// A never-completing job is guaranteed to still be in the active job
		// set at subscribe time, so the SSE handler's initial job-update
		// event fires deterministically on connect.
		String id = invoke("v/test/ops/never");

		HttpRequest sseReq = HttpRequest.newBuilder()
			.uri(new URI(base + "/api/v1/jobs/" + id + "/sse"))
			.header("Accept", "text/event-stream")
			.GET().build();
		HttpResponse<InputStream> sse = http.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
		InputStream in = sse.body();
		try {
			assertEquals(200, sse.statusCode(),
				"SSE route must resolve — a 400 means the greedy jobs/<id> matcher swallowed it (#200)");
			assertTrue(sse.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
				"SSE response must be an event stream");

			// Read in a worker so the main thread can bound the wait and, on
			// timeout, unblock the reader by closing the stream (finally).
			CompletableFuture<Boolean> sawEvent = CompletableFuture.supplyAsync(() -> {
				try {
					BufferedReader reader = new BufferedReader(new InputStreamReader(in));
					String line;
					while ((line = reader.readLine()) != null) {
						if (line.contains("job-update")) return true;
					}
				} catch (IOException ignored) {
					// stream closed by the main thread on timeout
				}
				return false;
			});
			assertTrue(sawEvent.get(10, TimeUnit.SECONDS),
				"the initial job-update event must arrive on connect");
		} finally {
			in.close();
			// Don't leak a forever-running job into the shared venue — and
			// exercise the (also re-matched) cancel route while at it.
			HttpResponse<String> cancel = http.send(
				HttpRequest.newBuilder().uri(new URI(base + "/api/v1/jobs/" + id + "/cancel"))
					.PUT(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
			assertEquals(200, cancel.statusCode(), cancel.body());
		}
	}
}
