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
