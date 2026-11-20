package covia.grid.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.RateLimitException;
import covia.grid.Job;
import covia.venue.Config;
import covia.venue.server.VenueServer;

/**
 * Real-venue integration: a genuine 429 from the venue surfaces as a typed
 * {@link RateLimitException} through {@link VenueHTTP}. The venue's request
 * rate allows one request a second with a burst of one, so the first invoke
 * spends the caller's only token and the second, sent at once, is refused;
 * the client is configured with a no-retry policy, so it fails without
 * sleeping. It needs a venue of its own with limits on, so it is a failsafe
 * integration test.
 */
public class VenueHTTPRateLimitIT {

	private static final AString ECHO = Strings.create("v/test/ops/echo");

	@Test
	public void testInvokeGets429AsRateLimitException() throws Exception {
		VenueServer server = VenueServer.launch(Maps.of(
			Strings.create("port"), 0, Config.STORE, "memory",
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(
				Config.ENABLED, true, Config.CAPS, Strings.create("unrestricted"))),
			Config.RATE_LIMIT, Maps.of(
				Config.ENABLED, true,
				Strings.create("rps"), 1L,
				Strings.create("burst"), 1L)));
		try {
			VenueHTTP client = VenueHTTP.create(URI.create("http://127.0.0.1:" + server.port()));
			client.setRetryPolicy(RetryPolicy.noRetry()); // fail fast, no retry sleeping

			// The first invoke spends the only token.
			Job first = client.startJobAsync(ECHO, Maps.empty()).join();
			assertNotNull(first);

			// Second invoke: no token left → venue 429 → typed RateLimitException.
			CompletionException ce = assertThrows(CompletionException.class,
				() -> client.startJobAsync(ECHO, Maps.empty()).join());
			assertInstanceOf(RateLimitException.class, ce.getCause(),
				"a venue 429 must surface as RateLimitException, was: " + ce.getCause());
		} finally {
			server.close();
		}
	}
}
