package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.server.VenueServer;

/**
 * Deterministic HTTP wiring test: an over-cap invoke returns 429 + Retry-After
 * through the real request pipeline. Determinism comes from the mechanism, not
 * timing — a never-completing job holds the single slot permanently and
 * {@code blockMs = 0} sheds immediately, so the outcome does not depend on
 * request rate, refill, or load. The token-bucket and admission mechanisms
 * themselves are unit-tested by {@code RateLimiterTest} / {@code JobConcurrencyCapTest};
 * the enable/default logic by {@code ConfigRateLimitTest}.
 */
public class RateLimitTest {

	@Test
	public void testConcurrentJobCap429() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(
				Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted"))),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				// Request rate high so only the concurrent-job cap can trip here.
				Strings.create("rps"), 100000L,
				Strings.create("burst"), 100000L,
				Strings.create("maxConcurrentJobsPerUser"), 1L,
				Strings.create("blockMs"), 0L)));
		try {
			String url = "http://127.0.0.1:" + server.port() + "/api/v1/invoke";
			HttpClient client = HttpClient.newHttpClient();
			HttpRequest invokeNever = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"operation\":\"v/test/ops/never\"}"))
				.build();

			// First invoke takes the only slot with a never-completing job (holds
			// it for the life of the venue).
			HttpResponse<String> first = client.send(invokeNever, HttpResponse.BodyHandlers.ofString());
			assertEquals(201, first.statusCode(), "first invoke should be admitted (201): " + first.body());

			// Second invoke: cap full, blockMs 0 → sheds immediately. Deterministic.
			HttpResponse<String> second = client.send(invokeNever, HttpResponse.BodyHandlers.ofString());
			assertEquals(429, second.statusCode(), "over-cap invoke should be 429: " + second.body());
			assertTrue(second.headers().firstValue("Retry-After").isPresent(),
				"429 from the job cap must carry a Retry-After header");
		} finally {
			server.close();
		}
	}
}
