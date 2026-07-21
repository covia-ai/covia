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
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.prim.CVMBool;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;

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
}
