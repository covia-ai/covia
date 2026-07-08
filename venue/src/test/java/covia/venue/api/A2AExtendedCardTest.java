package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.RequestContext;
import covia.venue.TestServer;

/**
 * #187 — authenticated agent catalogue over A2A ({@code GetExtendedAgentCard}).
 *
 * <p>Real HTTP against the shared venue. An authenticated caller's extended
 * card carries one skill per agent they own (skill id = the agent's grid
 * address); an anonymous caller gets only the plain front-door card. The
 * catalogue read is job-free (#180) — asserted directly against the engine.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2AExtendedCardTest {

	static final String BASE_URL = TestServer.BASE_URL;
	private HttpClient http;

	@BeforeAll
	public void setup() {
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Test
	public void authenticatedCallerGetsOwnAgentsAsSkills() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		String jwt = bearerFor(kp);

		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
		createAgent(client, "Cat", Maps.of(
				Strings.create("name"), Strings.create("Catalogue Agent"),
				Strings.create("description"), Strings.create("First test agent"),
				Strings.create("operation"), Strings.create("v/test/ops/echo")));
		// Minimal config — the skill falls back to the agent id + default description.
		createAgent(client, "Min", Maps.of(
				Strings.create("operation"), Strings.create("v/test/ops/echo")));
		// A terminated agent must not appear in the catalogue.
		createAgent(client, "Gone", Maps.of(
				Strings.create("operation"), Strings.create("v/test/ops/echo")));
		Job deleted = client.invokeAndWait(Strings.create("v/ops/agent/delete"),
				Maps.of(Strings.create("agentId"), Strings.create("Gone")));
		assertEquals(Status.COMPLETE, deleted.getStatus(), "delete: " + deleted.getErrorMessage());

		// The catalogue read must not mint jobs (#180 principle).
		RequestContext ownerCtx = RequestContext.of(ownerDid);
		long jobsBefore = TestServer.ENGINE.jobs().getJobs(ownerCtx).count();

		AgentCard card = extendedCard(jwt);
		assertNotNull(card);
		assertTrue(card.capabilities().extendedAgentCard(), "card should advertise extendedAgentCard");

		assertEquals(2, card.skills().size(), "skills: " + card.skills());
		AgentSkill cat = skillFor(card, ownerDid + "/g/Cat");
		assertEquals("Catalogue Agent", cat.name());
		assertEquals("First test agent", cat.description());
		AgentSkill min = skillFor(card, ownerDid + "/g/Min");
		assertEquals("Min", min.name());
		assertNull(findSkill(card, ownerDid + "/g/Gone").orElse(null),
				"terminated agent must not be catalogued");

		assertEquals(jobsBefore, TestServer.ENGINE.jobs().getJobs(ownerCtx).count(),
				"GetExtendedAgentCard must not mint jobs");

		// The skill id is the agent's A2A endpoint path: its card resolves below it.
		HttpResponse<String> perAgent = get("/a2a/" + cat.id() + "/.well-known/agent-card.json", jwt);
		assertEquals(200, perAgent.statusCode(), perAgent.body());
	}

	@Test
	public void anonymousCallerGetsFrontDoorOnly() throws Exception {
		// Ensure at least one agent exists on the venue (someone else's).
		AKeyPair kp = AKeyPair.generate();
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(bearerFor(kp)));
		client.setTimeout(5000);
		createAgent(client, "Private", Maps.of(
				Strings.create("operation"), Strings.create("v/test/ops/echo")));

		AgentCard card = extendedCard(null);
		assertNotNull(card);
		// The plain front-door card: no catalogue, nothing disclosed.
		assertEquals(0, card.skills().size(), "anonymous extended card must carry no skills");
		AgentCard wellKnown = JsonUtil.OBJECT_MAPPER.fromJson(
				get("/.well-known/agent-card.json", null).body(), AgentCard.class);
		assertEquals(wellKnown.name(), card.name());
	}

	@Test
	public void perAgentExtendedCardFollowsTheAccessGate() throws Exception {
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = didOf(kp);
		String jwt = bearerFor(kp);
		VenueHTTP client = VenueHTTP.create(URI.create(BASE_URL), VenueAuth.bearer(jwt));
		client.setTimeout(5000);
		createAgent(client, "Mine", Maps.of(
				Strings.create("name"), Strings.create("My Agent"),
				Strings.create("operation"), Strings.create("v/test/ops/echo")));

		String endpoint = "/a2a/" + ownerDid + "/g/Mine";
		Object envelope = rpcEnvelope("x1", "GetExtendedAgentCard", Map.of());

		// Owner → the agent's card over JSON-RPC.
		HttpResponse<String> ok = post(endpoint, envelope, jwt);
		assertEquals(200, ok.statusCode(), ok.body());
		Map<String, Object> parsed = JsonUtil.OBJECT_MAPPER.fromJson(ok.body(), Map.class);
		assertNull(parsed.get("error"), "unexpected error: " + parsed.get("error"));
		AgentCard card = JsonUtil.OBJECT_MAPPER.fromJson(
				JsonUtil.OBJECT_MAPPER.toJson(parsed.get("result")), AgentCard.class);
		assertEquals("My Agent", card.name());

		// Non-owner → 403; anonymous → 404 (the same gate as every per-agent call).
		assertEquals(403, post(endpoint, envelope, bearerFor(AKeyPair.generate())).statusCode());
		assertEquals(404, post(endpoint, envelope, null).statusCode());
	}

	// ---- helpers ----

	private static void createAgent(VenueHTTP client, String agentId, Object config) throws Exception {
		Job created = client.invokeAndWait(Strings.create("v/ops/agent/create"), Maps.of(
				Strings.create("agentId"), Strings.create(agentId),
				Strings.create("config"), config));
		assertEquals(Status.COMPLETE, created.getStatus(),
				"agent create should succeed: " + created.getErrorMessage());
	}

	private AgentCard extendedCard(String jwt) throws Exception {
		HttpResponse<String> resp = post("/a2a", rpcEnvelope("e1", "GetExtendedAgentCard", Map.of()), jwt);
		assertEquals(200, resp.statusCode(), resp.body());
		Map<String, Object> parsed = JsonUtil.OBJECT_MAPPER.fromJson(resp.body(), Map.class);
		assertNull(parsed.get("error"), "unexpected error: " + parsed.get("error"));
		return JsonUtil.OBJECT_MAPPER.fromJson(
				JsonUtil.OBJECT_MAPPER.toJson(parsed.get("result")), AgentCard.class);
	}

	private static Optional<AgentSkill> findSkill(AgentCard card, String id) {
		return card.skills().stream().filter(s -> id.equals(s.id())).findFirst();
	}

	private static AgentSkill skillFor(AgentCard card, String id) {
		return findSkill(card, id)
				.orElseThrow(() -> new AssertionError("no skill with id " + id + " in " + card.skills()));
	}

	private static AString didOf(AKeyPair kp) {
		return UCAN.toDIDKey(kp.getAccountKey());
	}

	/** A bearer token audienced to this venue — authenticates as the key's DID. */
	private static String bearerFor(AKeyPair kp) {
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		return UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp, Vectors.empty(), Vectors.empty())
				.toJWT(kp).toString();
	}

	private static Object rpcEnvelope(String id, String method, Object params) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("jsonrpc", "2.0");
		e.put("id", id);
		e.put("method", method);
		e.put("params", params);
		return e;
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
