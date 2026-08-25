package covia.venue.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.server.VenueServer;

/**
 * #179 — when a venue has no {@code a2a} config block, the well-known A2A
 * routes must answer with a discoverable "not configured" hint rather than the
 * generic catch-all 404 (indistinguishable from a wrong URL).
 */
@TestInstance(Lifecycle.PER_CLASS)
public class A2ANotConfiguredTest {

	private VenueServer server;
	private String baseUrl;
	private HttpClient http;

	@BeforeAll
	public void setup() {
		// No `a2a` block → A2A routes are not registered by the A2A handler.
		server = VenueServer.launch(Maps.of(Strings.create("port"), 0));
		baseUrl = "http://localhost:" + server.port();
		http = covia.venue.TestHTTP.CLIENT;
	}

	@AfterAll
	public void teardown() {
		try {
			server.close();
		} catch (Exception ignored) {
			// best effort
		}
	}

	@Test
	public void agentCardRoute_returnsHintWhenA2ANotConfigured() throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/.well-known/agent-card.json"))
				.GET().timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(501, resp.statusCode(), resp.body());
		assertTrue(resp.body().contains("A2A is not configured"), resp.body());
		assertTrue(resp.body().contains("a2a"), resp.body());
	}

	@Test
	public void sendMessageRoute_returnsHintWhenA2ANotConfigured() throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/a2a"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.timeout(Duration.ofSeconds(10)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(501, resp.statusCode(), resp.body());
		assertTrue(resp.body().contains("A2A is not configured"), resp.body());
	}
}
