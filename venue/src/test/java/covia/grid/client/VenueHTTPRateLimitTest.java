package covia.grid.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.RateLimitException;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.server.VenueServer;

/**
 * Real-venue integration: a genuine 429 from the venue surfaces as a typed
 * {@link RateLimitException} through {@link VenueHTTP}. Deterministic — a
 * never-completing job holds the single job slot and the client is configured
 * with a no-retry policy, so the second invoke fails immediately with no
 * sleeping or timing dependence.
 */
public class VenueHTTPRateLimitTest {

	@Test
	public void testInvokeGets429AsRateLimitException() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0,
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(
				Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted"))),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				Strings.create("rps"), 100000L,      // request rate high — only the job cap trips
				Strings.create("burst"), 100000L,
				Strings.create("maxConcurrentJobsPerUser"), 1L,
				Strings.create("blockMs"), 0L)));
		try {
			VenueHTTP client = VenueHTTP.create(URI.create("http://127.0.0.1:" + server.port()));
			client.setRetryPolicy(RetryPolicy.noRetry()); // fail fast, no retry sleeping

			// Hold the only slot with a never-completing job.
			Job never = client.startJobAsync(Strings.create("v/test/ops/never"), Maps.empty()).join();
			assertNotNull(never);

			// Second invoke: cap full → venue 429 → typed RateLimitException.
			CompletionException ce = assertThrows(CompletionException.class,
				() -> client.startJobAsync(Strings.create("v/test/ops/never"), Maps.empty()).join());
			assertInstanceOf(RateLimitException.class, ce.getCause(),
				"a venue 429 must surface as RateLimitException, was: " + ce.getCause());
		} finally {
			server.close();
		}
	}
}
