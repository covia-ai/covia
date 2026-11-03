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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.TestAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.TestEngine;
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
@Execution(ExecutionMode.SAME_THREAD) // Every anonymous call takes one of the same two places.
public class MCPToolCallWaitTest {

	private static final long CEILING_MS = 500;
	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":0}";

	private VenueServer server;
	private String base;
	private String session;
	private final HttpClient http = TestHTTP.CLIENT;

	@BeforeAll
	public void launch() throws Exception {
		server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0, Config.STORE, "memory",
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
		HttpResponse<String> init = http.send(post(INITIALIZE, "application/json"), HttpResponse.BodyHandlers.ofString());
		session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
	}

	@AfterAll
	public void close() {
		server.close();
	}

	private HttpRequest post(String body, String accept) {
		return post(body, accept, null, session);
	}

	/** A request from the caller a bearer names, or the anonymous caller when it is null. */
	private HttpRequest post(String body, String accept, String bearer, String sessionId) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base))
			.header("Content-Type", "application/json")
			.header("Accept", accept)
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
		return b.build();
	}

	/** A bearer a new key issues for itself. */
	private String selfIssued() {
		AKeyPair key = AKeyPair.generate();
		AString did = UCAN.toDIDKey(key.getAccountKey());
		return JWT.signPublic(Maps.of(JWT.SUB, did, JWT.AUD, server.getEngine().getDIDString(),
			JWT.EXP, CVMLong.create(System.currentTimeMillis() / 1000 + 3600)), key).toString();
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

	/** A tools/call request's structured result. */
	private ACell callTool(int id, String tool, String arguments) throws Exception {
		String call = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
			+ tool + "\",\"arguments\":" + arguments + "}}";
		String body = http.send(post(call, "application/json"), HttpResponse.BodyHandlers.ofString()).body();
		ACell content = RT.getIn(result(body), "structuredContent");
		assertNotNull(content, body);
		return content;
	}

	@Test
	public void anUnrecordedCallPastTheCeilingLeavesAJobToLookUp() throws Exception {
		String gateName = "mcp-read-only-" + System.identityHashCode(this);
		Job job;
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			// A read-only call runs unrecorded; past the ceiling it is mirrored by a recorded job.
			ACell ran = callTool(20, "test_holdread", "{\"gate\":\"" + gateName + "\",\"result\":\"read-late\"}");
			assertEquals("running", RT.getIn(ran, "status").toString(), ran.toString());
			String id = RT.getIn(ran, "id").toString();
			job = server.getEngine().jobs().getJob(Blob.fromHex(id));
			assertNotNull(job, "the job named is live");
			assertTrue(job.isRecorded(), "and recorded");
			ACell status = callTool(21, "grid_job_status", "{\"id\":\"" + id + "\"}");
			assertEquals("STARTED", RT.getIn(status, "status").toString(),
				"grid_job_status sees it while it runs: " + status);
			gate.release();
			TestEngine.awaitJob(job, Job::isFinished, 10_000, () -> "the call did not finish");
		}
		ACell status = callTool(22, "grid_job_status", "{\"id\":\"" + job.getID().toHexString() + "\"}");
		assertEquals("COMPLETE", RT.getIn(status, "status").toString(), status.toString());
		assertEquals("read-late", RT.getIn(status, "output").toString(),
			"and its outcome once it has finished");
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
		// The venue holds the call before it opens the call's stream with a first
		// keep-alive, so once the response has begun there is a call to cancel.
		HttpResponse<InputStream> r = http.send(post(never(3, ""), "application/json, text/event-stream"),
			HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = r.body()) {
			assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
				"a long call is answered as a stream");
			http.send(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}",
				"application/json"), HttpResponse.BodyHandlers.ofString());
			String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			String data = body.lines().filter(l -> l.startsWith("data: ")).reduce((a, b) -> b).map(l -> l.substring(6)).orElse(body);
			assertEquals("Cancelled", RT.getIn(result(data), "structuredContent", "message").toString(), body);
		}
	}

	@Test
	public void eachCallerHasALimitAndACallerThatLeavesFreesItsPlace() throws Exception {
		// A signed-in caller's calls wait for an hour, so only leaving frees a place.
		String bearer = selfIssued();
		String own = http.send(post(INITIALIZE, "application/json", bearer, null), HttpResponse.BodyHandlers.ofString())
			.headers().firstValue("Mcp-Session-Id").orElseThrow();

		// Fill the caller's two places with calls the venue is holding as streams.
		InputStream[] held = new InputStream[2];
		for (int i = 0; i < 2; i++) {
			HttpResponse<InputStream> r = http.send(post(never(10 + i, ""), "application/json, text/event-stream", bearer, own),
				HttpResponse.BodyHandlers.ofInputStream());
			assertTrue(streams(r), "the venue holds call " + i);
			held[i] = r.body();
		}
		HttpResponse<String> over = http.send(post(never(12, ""), "application/json", bearer, own), HttpResponse.BodyHandlers.ofString());
		assertTrue(over.body().contains("Too many tool calls"), "a third at once is refused, and told why: " + over.body());

		// Leave both: the venue notices at its next keep-alive and frees the places.
		for (InputStream in : held) in.close();
		HttpResponse<InputStream> admitted;
		while (!streams(admitted = http.send(post(never(13, ""), "application/json, text/event-stream", bearer, own),
				HttpResponse.BodyHandlers.ofInputStream()))) {
			String refusal = new String(admitted.body().readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(refusal.contains("Too many tool calls"), "refused only while its places are taken: " + refusal);
		}
		admitted.body().close();
	}

	/** Whether the venue is holding a call, answering it as a stream. */
	private static boolean streams(HttpResponse<?> r) {
		return r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream");
	}
}
