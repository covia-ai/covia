package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
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
		// Fresh owner identity + a bearer token audienced to this venue (aud != iss
		// → authenticates as the DID with no self-attenuation, i.e. as itself).
		AKeyPair kp = AKeyPair.generate();
		AString ownerDid = UCAN.toDIDKey(kp.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		String jwt = UCAN.create(kp, TestServer.ENGINE.getAccountKey(), exp, Vectors.empty(), Vectors.empty())
				.toJWT(kp).toString();

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

	private HttpResponse<String> get(String path, String jwt) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.GET().timeout(Duration.ofSeconds(10));
		if (jwt != null) b.header("Authorization", "Bearer " + jwt);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}
}
