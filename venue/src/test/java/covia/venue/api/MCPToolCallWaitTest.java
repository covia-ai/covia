package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.TestHTTP;
import covia.venue.server.VenueServer;

/**
 * How long an MCP tool call waits is the caller's to decide (MCP Lifecycle →
 * Timeouts): the venue waits as long as the caller does, within a ceiling and
 * a per-caller limit, keeps a long call's stream alive so a departed caller
 * is noticed, honours {@code notifications/cancelled}, and never ends a call
 * without saying where its result will be.
 *
 * <p>Uses {@code test:never}, a job that never finishes, on a venue of its
 * own with small limits.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class MCPToolCallWaitTest {

	private static final long CEILING_MS = 500;

	private VenueServer server;
	private String base;
	private String session;
	private final HttpClient http = TestHTTP.CLIENT;

	@BeforeAll
	public void launch() throws Exception {
		server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Fields.MCP, Maps.of(
				Strings.create("includePathPrefixes"), Vectors.of(Strings.create("v/ops/"), Strings.create("v/test/ops/")),
				Strings.create("includeAdapters"), Vectors.of(Strings.create("*")),
				Strings.create("anonymousToolCallTimeout"), CEILING_MS,
				Strings.create("keepAliveInterval"), 20,
				Strings.create("maxConcurrentToolCalls"), 2),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(
				Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted")))));
		base = "http://localhost:" + server.port() + "/mcp";
		HttpResponse<String> init = http.send(post("{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":0}", "application/json"),
			HttpResponse.BodyHandlers.ofString());
		session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
	}

	@AfterAll
	public void close() {
		server.close();
	}

	private HttpRequest post(String body, String accept) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base))
			.header("Content-Type", "application/json")
			.header("Accept", accept)
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (session != null) b.header("Mcp-Session-Id", session);
		return b.build();
	}

	private static String never(int id, String meta) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"test_never\","
			+ "\"arguments\":{}" + meta + "}}";
	}

	/** A JSON response's tool result. */
	private static ACell result(String json) {
		return RT.getIn(JSON.parse(json), "result");
	}

	@Test
	public void pastTheCeilingTheCallerIsToldWhereTheResultWillBe() throws Exception {
		HttpResponse<String> r = http.send(post(never(1, ""), "application/json"), HttpResponse.BodyHandlers.ofString());
		ACell result = result(r.body());
		assertEquals(CVMBool.TRUE, RT.getIn(result, "isError"), r.body());
		assertEquals("running", RT.getIn(result, "structuredContent", "status").toString());
		assertNotNull(RT.getIn(result, "structuredContent", "id"), "the job to collect: " + r.body());
		assertTrue(RT.getIn(result, "structuredContent", "message").toString().contains("grid_job_result"), r.body());
	}

	@Test
	public void aLongCallsProgressSaysWhatItIsDoing() throws Exception {
		String call = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"test_progress\","
			+ "\"arguments\":{\"hold\":true},\"_meta\":{\"progressToken\":\"p5\"}}}";
		HttpResponse<InputStream> r = http.send(post(call, "application/json, text/event-stream"),
			HttpResponse.BodyHandlers.ofInputStream());
		java.util.List<String> said = new java.util.ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null;) {
				if (!line.startsWith("data: ") || !line.contains("notifications/progress")) continue;
				said.add(RT.getIn(JSON.parse(line.substring(6)), "params", "message").toString());
			}
		}
		assertTrue(said.contains("step 2"), "the job's own progress message, not only that it still works: " + said);
	}

	@Test
	public void aLongCallStreamsAndShowsItIsStillWorking() throws Exception {
		HttpResponse<InputStream> r = http.send(post(never(2, ",\"_meta\":{\"progressToken\":\"p2\"}"),
			"application/json, text/event-stream"), HttpResponse.BodyHandlers.ofInputStream());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
		String progress = null, last = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null;) {
				if (!line.startsWith("data: ")) continue;
				last = line.substring(6);
				if (progress == null && last.contains("notifications/progress")) progress = last;
			}
		}
		assertNotNull(progress, "progress notifications while it runs");
		assertEquals("p2", RT.getIn(JSON.parse(progress), "params", "progressToken").toString());
		assertEquals("running", RT.getIn(result(last), "structuredContent", "status").toString(),
			"the response ends the stream: " + last);
	}

	@Test
	public void cancellingACallEndsIt() throws Exception {
		CompletableFuture<HttpResponse<String>> call = http.sendAsync(post(never(3, ""), "application/json, text/event-stream"),
			HttpResponse.BodyHandlers.ofString());
		// Until the venue holds the call there is nothing to cancel: keep asking until it answers.
		HttpResponse<String> r = null;
		while (!call.isDone() || r == null) {
			http.send(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}",
				"application/json"), HttpResponse.BodyHandlers.ofString());
			if (call.isDone()) r = call.get();
			else Thread.onSpinWait();
		}
		String data = r.body().lines().filter(l -> l.startsWith("data: ")).reduce((a, b) -> b).map(l -> l.substring(6)).orElse(r.body());
		assertEquals("Cancelled", RT.getIn(result(data), "structuredContent", "message").toString(), r.body());
	}

	@Test
	public void eachCallerHasALimitAndACallerThatLeavesFreesItsPlace() throws Exception {
		// Fill the caller's two places with streams the venue is holding (it has written to them).
		InputStream[] held = new InputStream[2];
		for (int i = 0; i < 2; i++) {
			held[i] = http.send(post(never(10 + i, ""), "application/json, text/event-stream"),
				HttpResponse.BodyHandlers.ofInputStream()).body();
			assertTrue(held[i].read() >= 0, "the venue holds call " + i);
		}
		HttpResponse<String> over = http.send(post(never(12, ""), "application/json"), HttpResponse.BodyHandlers.ofString());
		assertTrue(over.body().contains("Too many tool calls"), "a third at once is refused, and told why: " + over.body());

		// Leave both: the venue notices at its next keep-alive and frees the places.
		for (InputStream in : held) in.close();
		String body;
		do {
			body = http.send(post(never(13, ""), "application/json"), HttpResponse.BodyHandlers.ofString()).body();
		} while (body.contains("Too many tool calls"));
		assertEquals("running", RT.getIn(result(body), "structuredContent", "status").toString(),
			"admitted once they left, and waited for as usual: " + body);
	}
}
