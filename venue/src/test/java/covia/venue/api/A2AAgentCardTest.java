package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.prim.CVMBool;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;
import covia.venue.AgentState;

/**
 * #183 — per-agent Agent Card over A2A (COG-14): {@code GET /a2a/<ownerDID>/g/<agentId>}.
 *
 * <p>Real HTTP against the shared venue with a dummy, non-LLM agent — the config
 * is just name/description (+ an echo transition op), and card rendering reads
 * config without ever invoking the agent. Verifies the owner sees the card and
 * that the endpoint hides existence from everyone else. Also exercises the
 * did:key endpoint end to end (its colons survive the HTTP path).</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2AAgentCardTest {

	static final String BASE_URL = TestServer.BASE_URL;
	private HttpClient http;

	@BeforeAll
	public void setup() {
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Test
	public void ownerSeesCard_othersGet404() throws Exception {
		// Fresh owner identity + a bearer token audienced to this venue.
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		String jwt = bearerFor(kp);

		// Create a dummy agent as the owner — no LLM: an echo transition op and a
		// name/description config, which is all the card renders from.
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
		Job created = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
				Strings.create("agentId"), Strings.create("Alice"),
				Strings.create("config"), Maps.of(
						Strings.create("name"), Strings.create("Alice Agent"),
						Strings.create("description"), Strings.create("A dummy test agent"),
						Strings.create("operation"), Strings.create("v/test/ops/echo"))));
		assertEquals(Status.COMPLETE, created.getStatus(),
				"agent create should succeed: " + created.getErrorMessage());

		String base = "/a2a/" + ownerDid + "/g/Alice";
		// A2A-standard card location: the well-known path relative to the agent base.
		String cardPath = base + "/.well-known/agent-card.json";

		// Owner → 200 + the card rendered from config.
		HttpResponse<String> ok = get(cardPath, jwt);
		assertEquals(200, ok.statusCode(), ok.body());
		AgentCard card = JsonUtil.OBJECT_MAPPER.fromJson(ok.body(), AgentCard.class);
		assertNotNull(card);
		assertEquals("Alice Agent", card.name());
		assertEquals("A dummy test agent", card.description());
		assertNotNull(card.provider());
		assertEquals(1, card.supportedInterfaces().size());
		// The card's interface advertises the base *endpoint* (POST target), not the
		// card URL — and the did:key colons survive the HTTP path end to end.
		assertTrue(card.supportedInterfaces().get(0).url().endsWith(base),
				"interface url should end with " + base + ", got " + card.supportedInterfaces().get(0).url());

		// A bare GET on the base endpoint is not a card location.
		assertEquals(404, get(base, jwt).statusCode());

		// Anonymous → 404 (existence hidden).
		assertEquals(404, get(cardPath, null).statusCode());

		// Owner, but unknown agent → 404.
		assertEquals(404, get("/a2a/" + ownerDid + "/g/Nonexistent/.well-known/agent-card.json", jwt).statusCode());
	}

	@Test
	public void ownerSendsMessage_othersDenied() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		String jwt = bearerFor(kp);

		// Dummy echo agent (no LLM): agent:request wakes it and it echoes the input.
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
		Job created = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
				Strings.create("agentId"), Strings.create("Bob"),
				Strings.create("config"), Maps.of(
						Strings.create("name"), Strings.create("Bob Agent"),
						Strings.create("operation"), Strings.create("v/test/ops/echo"))));
		assertEquals(Status.COMPLETE, created.getStatus(), "agent create: " + created.getErrorMessage());

		String endpoint = "/a2a/" + ownerDid + "/g/Bob";
		Object envelope = rpcEnvelope("m1", "SendMessage",
				new MessageSendParams(userMessage("hi Bob"), null, null));

		// Owner → 200 + a non-terminal Task (async request; client would poll GetTask).
		HttpResponse<String> ok = post(endpoint, envelope, jwt);
		assertEquals(200, ok.statusCode(), ok.body());
		Map<String, Object> parsed = JsonUtil.OBJECT_MAPPER.fromJson(ok.body(), Map.class);
		assertNull(parsed.get("error"), "unexpected error: " + parsed.get("error"));
		Task task = extractTask(parsed);
		assertNotNull(task);
		assertNotNull(task.id(), "Task.id must be set");
		assertNotNull(task.status());
		assertNotNull(task.status().state());
		// contextId is the agent session, distinct from the task/Job id (#185).
		assertNotNull(task.contextId());
		assertNotEquals(task.id(), task.contextId(), "contextId should be the session, not the Job id");

		// Authenticated non-owner → 403.
		String otherJwt = bearerFor(AKeyPair.generate());
		assertEquals(403, post(endpoint, envelope, otherJwt).statusCode());

		// Anonymous → 404 (existence hidden).
		assertEquals(404, post(endpoint, envelope, null).statusCode());

		// GetTask by the returned id → the same task (owner), reusing the by-id handler.
		String taskId = task.id();
		Map<String, Object> getResp = JsonUtil.OBJECT_MAPPER.fromJson(
				post(endpoint, rpcEnvelope("g1", "GetTask", new TaskQueryParams(taskId, null)), jwt).body(),
				Map.class);
		assertNull(getResp.get("error"), "GetTask error: " + getResp.get("error"));
		Task fetched = extractTask(getResp);
		assertNotNull(fetched);
		assertEquals(taskId, fetched.id());
		// The session context survives into GetTask (preserved across completion).
		assertEquals(task.contextId(), fetched.contextId());
		assertNotEquals(fetched.id(), fetched.contextId());

		// Non-owner GetTask → 403 (gated before the body is processed).
		assertEquals(403, post(endpoint, rpcEnvelope("g2", "GetTask",
				new TaskQueryParams(taskId, null)), otherJwt).statusCode());

		// CancelTask → the cancelled task, or TASK_NOT_CANCELABLE if the echo
		// already finished — both are spec-valid.
		Map<String, Object> cancelResp = JsonUtil.OBJECT_MAPPER.fromJson(
				post(endpoint, rpcEnvelope("c1", "CancelTask", new CancelTaskParams(taskId)), jwt).body(),
				Map.class);
		Object cancelErr = cancelResp.get("error");
		if (cancelErr == null) {
			assertEquals(taskId, extractTask(cancelResp).id());
		}
	}

	@Test
	public void publicAgentCardIsDiscoverableByAnyone() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);

		// Create an agent that opts into public A2A exposure (a2a.public = true).
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(bearerFor(kp)));
		client.setTimeout(5000);
		Job created = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
				Strings.create("agentId"), Strings.create("PublicOne"),
				Strings.create("config"), Maps.of(
						Strings.create("name"), Strings.create("Public Agent"),
						Strings.create("operation"), Strings.create("v/test/ops/echo"),
						Strings.create("a2a"), Maps.of(Strings.create("public"), CVMBool.TRUE))));
		assertEquals(Status.COMPLETE, created.getStatus(), "create: " + created.getErrorMessage());

		String cardPath = "/a2a/" + ownerDid + "/g/PublicOne/.well-known/agent-card.json";

		// Anonymous → 200 + card (public discovery).
		HttpResponse<String> anon = get(cardPath, null);
		assertEquals(200, anon.statusCode(), anon.body());
		AgentCard card = JsonUtil.OBJECT_MAPPER.fromJson(anon.body(), AgentCard.class);
		assertEquals("Public Agent", card.name());

		// Authenticated non-owner → 200 too.
		assertEquals(200, get(cardPath, bearerFor(AKeyPair.generate())).statusCode());
	}

	@Test
	public void publicAgentWithCaps_acceptsAnonMessage_withoutCapsDenies() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(bearerFor(kp)));
		client.setTimeout(5000);

		// Public agent WITH an explicit a2a.caps scope ("unrestricted" for the
		// test) → an anonymous message/send runs under the owner, bounded by caps.
		Job withCaps = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
				Strings.create("agentId"), Strings.create("PubChat"),
				Strings.create("config"), Maps.of(
						Strings.create("operation"), Strings.create("v/test/ops/echo"),
						Strings.create("a2a"), Maps.of(
								Strings.create("public"), CVMBool.TRUE,
								Strings.create("caps"), Strings.create("unrestricted")))));
		assertEquals(Status.COMPLETE, withCaps.getStatus(), "create: " + withCaps.getErrorMessage());

		Object env = rpcEnvelope("pm1", "SendMessage", new MessageSendParams(userMessage("hi public"), null, null));

		HttpResponse<String> anon = post("/a2a/" + ownerDid + "/g/PubChat", env, null);
		assertEquals(200, anon.statusCode(), anon.body());
		Map<String, Object> parsed = JsonUtil.OBJECT_MAPPER.fromJson(anon.body(), Map.class);
		assertNull(parsed.get("error"), "unexpected error: " + parsed.get("error"));
		Task anonTask = extractTask(parsed);
		assertNotNull(anonTask);

		// The anonymous sender polls its own task by id on the same endpoint.
		// The lookup runs under the gated dispatch (owner) context, so a task
		// minted through this endpoint stays visible to the remote sender —
		// without this, the outbound a2a:send mirror can never observe
		// completion of a task it created on a public agent.
		Map<String, Object> polled = JsonUtil.OBJECT_MAPPER.fromJson(
				post("/a2a/" + ownerDid + "/g/PubChat",
						rpcEnvelope("pg1", "GetTask", new TaskQueryParams(anonTask.id(), null)), null).body(),
				Map.class);
		assertNull(polled.get("error"), "anonymous sender must be able to poll its task: " + polled.get("error"));
		assertEquals(anonTask.id(), extractTask(polled).id());

		// Public agent WITHOUT a2a.caps → discoverable, but an anonymous
		// message/send is denied (card-only; the owner hasn't bounded a run).
		Job noCaps = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
				Strings.create("agentId"), Strings.create("PubNoCaps"),
				Strings.create("config"), Maps.of(
						Strings.create("operation"), Strings.create("v/test/ops/echo"),
						Strings.create("a2a"), Maps.of(Strings.create("public"), CVMBool.TRUE))));
		assertEquals(Status.COMPLETE, noCaps.getStatus());
		assertEquals(404, post("/a2a/" + ownerDid + "/g/PubNoCaps", env, null).statusCode());
	}

	@Test
	public void taskAndContextContinuation_areDistinctAndIdempotent() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		String jwt = bearerFor(kp);
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);

		// The test transition explicitly completes tasks. Its config delay holds
		// revision 0 open while two identical continuation requests race.
		Job created = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
			Fields.AGENT_ID, Strings.create("Continuer"),
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, Strings.create("v/test/ops/taskcomplete"),
				Fields.DELAY, convex.core.data.prim.CVMLong.create(300))));
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());

		String endpoint = "/a2a/" + ownerDid + "/g/Continuer";
		Map<String, Object> initialResponse = parse(post(endpoint,
			rpcEnvelope("start", "SendMessage",
				new MessageSendParams(userMessage("first"), null, null)), jwt));
		Task initial = extractTask(initialResponse);
		assertNotNull(initial);

		Message continuation = Message.builder()
			.role(Message.Role.ROLE_USER)
			.parts(List.<Part<?>>of(new TextPart("second", null)))
			.messageId("same-continuation")
			.contextId(initial.contextId())
			.taskId(initial.id())
			.build();
		Object continuationRpc = rpcEnvelope("continue", "SendMessage",
			new MessageSendParams(continuation, null, null));
		Message otherContinuation = Message.builder()
			.role(Message.Role.ROLE_USER)
			.parts(List.<Part<?>>of(new TextPart("third", null)))
			.messageId("other-continuation")
			.contextId(initial.contextId())
			.taskId(initial.id())
			.build();
		Object otherContinuationRpc = rpcEnvelope("continue-other", "SendMessage",
			new MessageSendParams(otherContinuation, null, null));
		CompletableFuture<HttpResponse<String>> one = CompletableFuture.supplyAsync(
			() -> postUnchecked(endpoint, continuationRpc, jwt));
		CompletableFuture<HttpResponse<String>> two = CompletableFuture.supplyAsync(
			() -> postUnchecked(endpoint, continuationRpc, jwt));
		CompletableFuture<HttpResponse<String>> three = CompletableFuture.supplyAsync(
			() -> postUnchecked(endpoint, otherContinuationRpc, jwt));
		assertEquals(200, one.join().statusCode());
		assertEquals(200, two.join().statusCode());
		assertEquals(200, three.join().statusCode());

		Task completed = awaitTask(endpoint, initial.id(), jwt, TaskState.TASK_STATE_COMPLETED);
		assertEquals(3, completed.history().size(),
			"retrying the same messageId must not duplicate Task history");
		AgentState continued = TestServer.ENGINE.getVenueState().users().get(ownerDid)
			.agent("Continuer");
		assertEquals(3, continued.getTimeline().count(),
			"the stale cycle and both distinct continuations each run exactly once");
		var session = continued.getSession(Blob.fromHex(initial.contextId()));
		var frames = (convex.core.data.AVector<?>) session.get(AgentState.KEY_FRAMES);
		var conversation = (convex.core.data.AVector<?>)
			((convex.core.data.AMap<?, ?>) frames.get(0)).get(AgentState.KEY_CONVERSATION);
		assertEquals(6, conversation.count(),
			"three user turns and three agent turns must be recorded without duplicate frames");

		// The endpoint's caller/publication gate runs before continuation lookup.
		assertEquals(403, post(endpoint, continuationRpc,
			bearerFor(AKeyPair.generate())).statusCode());

		Message mismatched = Message.builder()
			.role(Message.Role.ROLE_USER)
			.parts(List.<Part<?>>of(new TextPart("mismatch", null)))
			.messageId("mismatched-context")
			.contextId("00".repeat(initial.contextId().length() / 2))
			.taskId(initial.id())
			.build();
		Map<String, Object> mismatch = parse(post(endpoint,
			rpcEnvelope("mismatch", "SendMessage",
				new MessageSendParams(mismatched, null, null)), jwt));
		assertEquals(org.a2aproject.sdk.spec.A2AErrorCodes.INVALID_PARAMS.code(),
			errorCode(mismatch));

		Message unknown = Message.builder()
			.role(Message.Role.ROLE_USER)
			.parts(List.<Part<?>>of(new TextPart("unknown", null)))
			.messageId("unknown-task")
			.taskId("00".repeat(initial.id().length() / 2))
			.build();
		Map<String, Object> unknownResponse = parse(post(endpoint,
			rpcEnvelope("unknown", "SendMessage",
				new MessageSendParams(unknown, null, null)), jwt));
		assertEquals(org.a2aproject.sdk.spec.A2AErrorCodes.TASK_NOT_FOUND.code(),
			errorCode(unknownResponse));

		// A taskId is bound to its publishing agent, even for the same owner.
		client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
			Fields.AGENT_ID, Strings.create("OtherContinuer"),
			Fields.CONFIG, Maps.of(Fields.OPERATION, Strings.create("v/test/ops/echo"))));
		Map<String, Object> crossAgent = parse(post(
			"/a2a/" + ownerDid + "/g/OtherContinuer", continuationRpc, jwt));
		assertEquals(org.a2aproject.sdk.spec.A2AErrorCodes.TASK_NOT_FOUND.code(),
			errorCode(crossAgent));

		// Once terminal, taskId continuation is forbidden.
		Map<String, Object> terminal = parse(post(endpoint, continuationRpc, jwt));
		assertEquals(org.a2aproject.sdk.spec.A2AErrorCodes.UNSUPPORTED_OPERATION.code(),
			errorCode(terminal));

		// contextId alone means a new Job/Task in the same conversation.
		Message nextTurn = Message.builder()
			.role(Message.Role.ROLE_USER)
			.parts(List.<Part<?>>of(new TextPart("fourth", null)))
			.messageId("context-followup")
			.contextId(initial.contextId())
			.build();
		Task next = extractTask(parse(post(endpoint,
			rpcEnvelope("next", "SendMessage", new MessageSendParams(nextTurn, null, null)), jwt)));
		assertNotNull(next);
		assertNotEquals(initial.id(), next.id());
		assertEquals(initial.contextId(), next.contextId());
		awaitTask(endpoint, next.id(), jwt, TaskState.TASK_STATE_COMPLETED);
	}

	/**
	 * #305 — an A2A turn has no synchronous completion deadline. SendMessage
	 * returns the ordinary durable task Job immediately; the same id can be
	 * reattached through polling or SSE, and both views converge on one final
	 * state. The deterministic transition delay keeps the task live while the
	 * client disconnects from SendMessage and opens a fresh subscription.
	 */
	@Test
	public void longTurnReattachesAndPollingConvergesWithSse() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		String jwt = bearerFor(kp);
		String agentId = "LongTurn" + Long.toUnsignedString(System.nanoTime());
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);

		Job created = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
			Fields.AGENT_ID, Strings.create(agentId),
			Fields.CONFIG, Maps.of(
				Fields.OPERATION, Strings.create("v/test/ops/taskcomplete"),
				Fields.DELAY, convex.core.data.prim.CVMLong.create(3000))));
		assertEquals(Status.COMPLETE, created.getStatus(), created.getErrorMessage());

		String endpoint = "/a2a/" + ownerDid + "/g/" + agentId;
		Task submitted = extractTask(parse(post(endpoint,
			rpcEnvelope("long-send", "SendMessage",
				new MessageSendParams(userMessage("long turn"), null, null)), jwt)));
		assertNotNull(submitted);
		assertNotNull(submitted.id(), "SendMessage must return the durable Job id");
		assertNotNull(submitted.contextId(), "SendMessage must return the session id");
		assertTrue(!submitted.status().state().isFinal(),
			"SendMessage must not wait for or manufacture a terminal timeout");

		TaskStatusUpdateEvent streamed = awaitFinalStatusUpdate(endpoint,
			submitted.id(), jwt, 7000);
		assertEquals(submitted.id(), streamed.taskId());
		assertEquals(submitted.contextId(), streamed.contextId());
		assertTrue(streamed.isFinal());
		assertEquals(TaskState.TASK_STATE_COMPLETED, streamed.status().state());

		Task polled = awaitTask(endpoint, submitted.id(), jwt,
			TaskState.TASK_STATE_COMPLETED);
		assertEquals(streamed.taskId(), polled.id());
		assertEquals(streamed.contextId(), polled.contextId());
		assertEquals(streamed.status().state(), polled.status().state(),
			"GetTask and SubscribeToTask must converge on the same Job state");
	}

	// ---- helpers ----

	private static AString didOf(AKeyPair kp) {
		return UCAN.toDIDKey(kp.getAccountKey());
	}

	/** A bearer token audienced to this venue — authenticates as the key's DID;
	 *  a bearer carries identity, never a grant scope. */
	private static String bearerFor(AKeyPair kp) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		return UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp, Vectors.empty(), Vectors.empty())
				.toJWT(kp).toString();
	}

	private static Message userMessage(String text) {
		return Message.builder()
				.role(Message.Role.ROLE_USER)
				.parts(List.<Part<?>>of(new TextPart(text, null)))
				.messageId("msg-" + UUID.randomUUID())
				.build();
	}

	private static Object rpcEnvelope(String id, String method, Object params) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("jsonrpc", "2.0");
		e.put("id", id);
		e.put("method", method);
		e.put("params", params);
		return e;
	}

	private static Task extractTask(Map<String, Object> rpcResp) {
		Object result = rpcResp.get("result");
		if (result == null) return null;
		return JsonUtil.OBJECT_MAPPER.fromJson(JsonUtil.OBJECT_MAPPER.toJson(result), Task.class);
	}

	@SuppressWarnings("unchecked")
	private static int errorCode(Map<String, Object> rpcResp) {
		return ((Number) ((Map<String, Object>) rpcResp.get("error")).get("code")).intValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(HttpResponse<String> response) {
		return JsonUtil.OBJECT_MAPPER.fromJson(response.body(), Map.class);
	}

	private Task awaitTask(String endpoint, String taskId, String jwt, TaskState wanted)
			throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
		Task task = null;
		do {
			task = extractTask(parse(post(endpoint,
				rpcEnvelope("poll-" + UUID.randomUUID(), "GetTask",
					new TaskQueryParams(taskId, null)), jwt)));
			if (task != null && wanted.equals(task.status().state())) return task;
			Thread.sleep(20);
		} while (System.currentTimeMillis() < deadline);
		throw new AssertionError("Task " + taskId + " did not reach " + wanted
			+ "; last state=" + (task == null ? null : task.status().state()));
	}

	private TaskStatusUpdateEvent awaitFinalStatusUpdate(String endpoint,
			String taskId, String jwt, long timeoutMs) throws Exception {
		HttpResponse<java.util.stream.Stream<String>> response = postStreaming(endpoint,
			rpcEnvelope("long-subscribe", "SubscribeToTask", new TaskIdParams(taskId)), jwt);
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("")
			.contains("text/event-stream"));

		AtomicReference<TaskStatusUpdateEvent> terminal = new AtomicReference<>();
		AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
		List<String> observed = Collections.synchronizedList(new java.util.ArrayList<>());
		Object signal = new Object();
		Thread consumer = Thread.ofVirtual().start(() -> {
			try (java.util.stream.Stream<String> lines = response.body()) {
				var iterator = lines.iterator();
				while (iterator.hasNext()) {
					String line = iterator.next();
					if (!line.startsWith("data:")) continue;
					observed.add(line);
					@SuppressWarnings("unchecked")
					Map<String, Object> envelope = JsonUtil.OBJECT_MAPPER.fromJson(
						line.substring(line.indexOf(':') + 1).trim(), Map.class);
					Map<String, Object> result = castMap(envelope.get("result"));
					Map<String, Object> update = result == null
						? null : castMap(result.get("statusUpdate"));
					if (update == null) continue;
					TaskStatusUpdateEvent event = JsonUtil.OBJECT_MAPPER.fromJson(
						JsonUtil.OBJECT_MAPPER.toJson(update), TaskStatusUpdateEvent.class);
					if (!event.isFinal()) continue;
					terminal.set(event);
					synchronized (signal) { signal.notifyAll(); }
					return;
				}
			} catch (RuntimeException failure) {
				consumerFailure.compareAndSet(null, failure);
				synchronized (signal) { signal.notifyAll(); }
			}
		});

		long deadline = System.currentTimeMillis() + timeoutMs;
		synchronized (signal) {
			while (terminal.get() == null && consumerFailure.get() == null
					&& System.currentTimeMillis() < deadline) {
				signal.wait(Math.max(1, deadline - System.currentTimeMillis()));
			}
		}
		try { response.body().close(); } catch (Exception ignored) {}
		if (!consumer.join(Duration.ofMillis(500))) consumer.interrupt();
		if (terminal.get() == null) {
			Task current = extractTask(parse(post(endpoint,
				rpcEnvelope("long-diagnostic", "GetTask", new TaskQueryParams(taskId, null)), jwt)));
			throw new AssertionError("SubscribeToTask did not emit a final update; failure="
				+ consumerFailure.get() + "; frames=" + observed + "; current="
				+ (current == null ? null : current.status().state()));
		}
		return terminal.get();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value) {
		return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
	}

	private HttpResponse<String> postUnchecked(String path, Object body, String jwt) {
		try {
			return post(path, body, jwt);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private HttpResponse<String> get(String path, String jwt) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.GET().timeout(Duration.ofSeconds(10));
		if (jwt != null) b.header("Authorization", "Bearer " + jwt);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, Object body, String jwt) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JsonUtil.OBJECT_MAPPER.toJson(body)))
				.timeout(Duration.ofSeconds(10));
		if (jwt != null) b.header("Authorization", "Bearer " + jwt);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<java.util.stream.Stream<String>> postStreaming(
			String path, Object body, String jwt) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(JsonUtil.OBJECT_MAPPER.toJson(body)))
			.timeout(Duration.ofSeconds(10));
		if (jwt != null) b.header("Authorization", "Bearer " + jwt);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofLines());
	}
}
