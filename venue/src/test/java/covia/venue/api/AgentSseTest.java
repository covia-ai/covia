package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.AgentState;
import covia.venue.TestServer;

/**
 * {@code GET /api/v1/agents/{id}/sse} (#394): the live agent tap over SSE.
 * The stream opens with the current status, relays every run-loop event with
 * the type as the SSE event name and the sequence as the SSE id, honours
 * {@code ?detail=false}, and closes after the TERMINATED frame. Runs against
 * the shared {@link TestServer} as a unique authenticated caller.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class AgentSseTest {

	private static final AString OP_CREATE = Strings.create("v/ops/agent/create");
	private static final AString OP_CHAT   = Strings.create("v/ops/agent/chat");
	private static final AString OP_DELETE = Strings.create("v/ops/agent/delete");

	private String jwt;
	private VenueHTTP client;
	private HttpClient http;

	@BeforeAll
	public void setup() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		UCAN token = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp,
			convex.core.data.Vectors.empty(), convex.core.data.Vectors.empty());
		jwt = token.toJWT(kp).toString();
		client = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(10_000);
		http = covia.venue.TestHTTP.CLIENT;
	}

	private void createAgent(String id) throws Exception {
		Job job = client.invokeAndWait(OP_CREATE, Maps.of(
			Fields.AGENT_ID, Strings.create(id),
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, Strings.create("v/ops/llmagent/chat"),
				Strings.create("llmOperation"), Strings.create("v/test/ops/toolllm"))));
		assertEquals(Status.COMPLETE, job.getStatus(), "agent create failed: " + job.getErrorMessage());
	}

	/** One SSE frame: the event name, the id (or null) and the parsed data. */
	private record Frame(String event, String id, ACell data) {
		String type() { return RT.ensureString(RT.getIn(data, Fields.TYPE)).toString(); }
		String status() { return RT.ensureString(RT.getIn(data, Fields.STATUS)).toString(); }
		long seq() { return RT.ensureLong(RT.getIn(data, Fields.SEQ)).longValue(); }
	}

	/** Parses frames off an open stream until one satisfies {@code until}
	 *  (or the stream ends); frames accumulate in {@code frames}. */
	private static final class Stream {
		final List<Frame> frames = new CopyOnWriteArrayList<>();
		final BufferedReader reader;
		final HttpResponse<InputStream> response;
		volatile boolean closed;

		Stream(HttpResponse<InputStream> response) {
			this.response = response;
			this.reader = new BufferedReader(new InputStreamReader(response.body()));
		}

		CompletableFuture<Frame> readUntil(Predicate<Frame> until) {
			return CompletableFuture.supplyAsync(() -> {
				try {
					String event = null, id = null, data = null, line;
					while ((line = reader.readLine()) != null) {
						if (line.startsWith("event:")) event = line.substring(6).trim();
						else if (line.startsWith("id:")) id = line.substring(3).trim();
						else if (line.startsWith("data:")) data = line.substring(5).trim();
						else if (line.isEmpty() && data != null) {
							Frame f = new Frame(event, id, JSON.parse(data));
							frames.add(f);
							event = null; id = null; data = null;
							if (until.test(f)) return f;
						}
					}
				} catch (IOException ignored) {
					// stream closed
				}
				closed = true;
				return null;
			});
		}

		List<String> events() {
			List<String> out = new ArrayList<>();
			for (Frame f : frames) out.add(f.event());
			return out;
		}
	}

	private Stream open(String id, String query, boolean auth) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(new URI(TestServer.BASE_URL + "/api/v1/agents/" + id + "/sse" + query))
			.header("Accept", "text/event-stream").GET();
		if (auth) b.header("Authorization", "Bearer " + jwt);
		return new Stream(http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()));
	}

	private HttpResponse<String> probe(String id, String accept, boolean auth) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder()
			.uri(new URI(TestServer.BASE_URL + "/api/v1/agents/" + id + "/sse")).GET();
		if (accept != null) b.header("Accept", accept);
		if (auth) b.header("Authorization", "Bearer " + jwt);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	// ========== tests ==========

	@Test
	public void testStreamRequiresOwnerAndSse() throws Exception {
		createAgent("sse-guard");
		int anonymous = probe("sse-guard", "text/event-stream", false).statusCode();
		assertTrue(anonymous == 401 || anonymous == 404,
			"an unauthenticated caller sees nothing: 401 without public access, an existence-hiding 404 as the public caller (" + anonymous + ")");
		assertEquals(404, probe("no-such-agent", "text/event-stream", true).statusCode());
		assertEquals(406, probe("sse-guard", "application/json", true).statusCode(),
			"an explicit non-SSE Accept is rejected loudly");
	}

	@Test
	public void testStreamRelaysRunLoopEventsAndClosesOnTerminate() throws Exception {
		String id = "sse-live";
		createAgent(id);

		Stream full = open(id, "", true);
		Stream safe = open(id, "?detail=false", true);
		try {
			assertEquals(200, full.response.statusCode());
			assertEquals(200, safe.response.statusCode());
			assertTrue(full.response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

			// The initial frame is the current status, stamped with the sequence
			// the stream picks up from.
			Frame initial = full.readUntil(f -> true).get(10, TimeUnit.SECONDS);
			assertNotNull(initial, "initial status frame");
			assertEquals("status", initial.event());
			assertEquals("status", initial.type());
			assertEquals(AgentState.SLEEPING.toString(), initial.status());
			assertEquals(Strings.create(id), RT.getIn(initial.data(), Fields.AGENT_ID));
			assertNotNull(RT.getIn(initial.data(), Fields.ADDRESS));
			assertNotNull(safe.readUntil(f -> true).get(10, TimeUnit.SECONDS));

			// Drive one cycle: chat with the mock tool LLM.
			CompletableFuture<Frame> fullDone = full.readUntil(f -> "run:end".equals(f.event()));
			CompletableFuture<Frame> safeDone = safe.readUntil(f -> "run:end".equals(f.event()));
			Job chat = client.invokeAndWait(OP_CHAT, Maps.of(
				Fields.AGENT_ID, Strings.create(id), Fields.MESSAGE, Strings.create("hello sse")));
			assertEquals(Status.COMPLETE, chat.getStatus(), "chat failed: " + chat.getErrorMessage());
			assertNotNull(fullDone.get(10, TimeUnit.SECONDS), "run:end must arrive");
			assertNotNull(safeDone.get(10, TimeUnit.SECONDS), "run:end must arrive on the safe stream");

			List<String> events = full.events();
			assertEquals(List.of(
					"status", "status", "run:start", "cycle:start",
					"inference:start", "inference:end", "tool:start", "tool:result",
					"inference:start", "inference:end", "cycle:end", "status", "run:end"),
				events, "one cycle of the mock tool LLM, in order");
			assertEquals(events, safe.events(), "both streams see the same sequence");

			// SSE ids are the agent's sequence numbers, strictly increasing.
			long last = 0;
			for (Frame f : full.frames.subList(1, full.frames.size())) {
				assertNotNull(f.id(), "every relayed frame carries its seq as the SSE id: " + f);
				assertEquals(f.seq(), Long.parseLong(f.id()));
				assertTrue(f.seq() > last);
				last = f.seq();
			}

			// Detail is carried by default and stripped on request.
			Frame toolStart = full.frames.stream().filter(f -> "tool:start".equals(f.event())).findFirst().orElseThrow();
			assertNotNull(RT.getIn(toolStart.data(), Fields.DETAIL, Fields.INPUT), "tool input under detail: " + toolStart);
			assertEquals(Strings.create("v/test/ops/echo"), RT.getIn(toolStart.data(), Fields.NAME));
			Frame safeToolStart = safe.frames.stream().filter(f -> "tool:start".equals(f.event())).findFirst().orElseThrow();
			assertFalse(RT.ensureMap(safeToolStart.data()).containsKey(Fields.DETAIL), "detail=false strips it: " + safeToolStart);
			assertEquals(Strings.create("v/test/ops/echo"), RT.getIn(safeToolStart.data(), Fields.NAME));
			Frame cycleEnd = full.frames.stream().filter(f -> "cycle:end".equals(f.event())).findFirst().orElseThrow();
			assertEquals(RT.getIn(chat.getOutput(), Fields.RESPONSE), RT.getIn(cycleEnd.data(), Fields.RESPONSE));
			assertEquals(RT.getIn(chat.getOutput(), Fields.SESSION_ID), RT.getIn(cycleEnd.data(), Fields.SESSION_ID));

			// TERMINATED is the last frame; the server closes the stream after it.
			CompletableFuture<Frame> ended = full.readUntil(f -> false);
			Job delete = client.invokeAndWait(OP_DELETE, Maps.of(Fields.AGENT_ID, Strings.create(id)));
			assertEquals(Status.COMPLETE, delete.getStatus(), "delete failed: " + delete.getErrorMessage());
			assertEquals(null, ended.get(10, TimeUnit.SECONDS), "the stream ends after the terminal frame");
			Frame lastFrame = full.frames.get(full.frames.size() - 1);
			assertEquals("status", lastFrame.event());
			assertEquals(AgentState.TERMINATED.toString(), lastFrame.status());
			assertTrue(full.closed);
		} finally {
			full.response.body().close();
			safe.response.body().close();
		}
	}

	@Test
	public void testTerminatedAgentStreamsFinalStatusAndCloses() throws Exception {
		String id = "sse-dead";
		createAgent(id);
		Job delete = client.invokeAndWait(OP_DELETE, Maps.of(Fields.AGENT_ID, Strings.create(id)));
		assertEquals(Status.COMPLETE, delete.getStatus());

		Stream s = open(id, "", true);
		try {
			assertEquals(200, s.response.statusCode(), "a terminated record still resolves for its owner");
			CompletableFuture<Frame> ended = s.readUntil(f -> false);
			assertEquals(null, ended.get(10, TimeUnit.SECONDS), "one frame, then close");
			assertEquals(1, s.frames.size());
			assertEquals(AgentState.TERMINATED.toString(), s.frames.get(0).status());
		} finally {
			s.response.body().close();
		}
	}

	@Test
	public void testSessionFilterNarrowsTheStream() throws Exception {
		String id = "sse-session";
		createAgent(id);
		Job mint = client.invokeAndWait(OP_CHAT, Maps.of(
			Fields.AGENT_ID, Strings.create(id), Fields.MESSAGE, Strings.create("mint A")));
		assertEquals(Status.COMPLETE, mint.getStatus(), "chat failed: " + mint.getErrorMessage());
		String sessionA = RT.ensureString(RT.getIn(mint.getOutput(), Fields.SESSION_ID)).toString();

		HttpResponse<String> bad = http.send(HttpRequest.newBuilder()
			.uri(new URI(TestServer.BASE_URL + "/api/v1/agents/" + id + "/sse?sessionId=not-hex"))
			.header("Accept", "text/event-stream").header("Authorization", "Bearer " + jwt).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(400, bad.statusCode(), "an unparseable session id is rejected before the stream opens");

		Stream whole = open(id, "", true);
		Stream scoped = open(id, "?sessionId=0x" + sessionA, true);
		try {
			Frame initial = scoped.readUntil(f -> true).get(10, TimeUnit.SECONDS);
			assertNotNull(initial);
			assertEquals(Strings.create(sessionA), RT.getIn(initial.data(), Fields.SESSION_ID),
				"the initial frame echoes the filter in the bare form the events carry");
			assertNotNull(whole.readUntil(f -> true).get(10, TimeUnit.SECONDS));

			CompletableFuture<Frame> wholeDone = whole.readUntil(f -> "run:end".equals(f.event())
				&& whole.frames.stream().filter(x -> "cycle:end".equals(x.event())).count() >= 2);
			Job again = client.invokeAndWait(OP_CHAT, Maps.of(
				Fields.AGENT_ID, Strings.create(id), Fields.SESSION_ID, Strings.create(sessionA),
				Fields.MESSAGE, Strings.create("again A")));
			assertEquals(Status.COMPLETE, again.getStatus(), "chat failed: " + again.getErrorMessage());
			Job other = client.invokeAndWait(OP_CHAT, Maps.of(
				Fields.AGENT_ID, Strings.create(id), Fields.MESSAGE, Strings.create("mint B")));
			assertEquals(Status.COMPLETE, other.getStatus(), "chat failed: " + other.getErrorMessage());
			String sessionB = RT.ensureString(RT.getIn(other.getOutput(), Fields.SESSION_ID)).toString();
			Frame lastWhole = wholeDone.get(10, TimeUnit.SECONDS);
			assertNotNull(lastWhole, "both cycles and the final run end must arrive on the whole stream");

			// Every frame the session view will ever get for those runs was
			// written before the whole stream's run:end; the last of them is
			// the SLEEPING status just before it.
			long target = lastWhole.seq() - 1;
			assertNotNull(scoped.readUntil(f -> f.seq() >= target).get(10, TimeUnit.SECONDS));

			assertEquals(2, whole.frames.stream().filter(f -> "cycle:start".equals(f.event())).count());
			List<Frame> cycles = scoped.frames.stream().filter(f -> "cycle:start".equals(f.event())).toList();
			assertEquals(1, cycles.size(), "the session view saw only its own cycle: " + scoped.events());
			assertEquals(Strings.create(sessionA), RT.getIn(cycles.get(0).data(), Fields.SESSION_ID));
			assertTrue(scoped.events().stream().noneMatch(e -> e.startsWith("run:")),
				"run boundaries are omitted: " + scoped.events());
			for (Frame f : scoped.frames) {
				ACell sid = RT.getIn(f.data(), Fields.SESSION_ID);
				assertFalse(Strings.create(sessionB).equals(sid), "session B never reaches the A view: " + f);
				if (!"status".equals(f.event())) assertEquals(Strings.create(sessionA), sid, f.toString());
			}
			assertTrue(scoped.events().contains("status"), "status events reach the session view");
		} finally {
			whole.response.body().close();
			scoped.response.body().close();
		}
	}
}
