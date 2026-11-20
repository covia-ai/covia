package covia.grid.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * rate allows one request a second with a burst of one, and several invokes
 * are sent at once, so however slowly the venue answers, they arrive within
 * the second and all but the first are refused. The client is configured
 * with a no-retry policy, so it fails without sleeping. It needs a venue of
 * its own with limits on, so it is a failsafe integration test.
 */
public class VenueHTTPRateLimitIT {

	private static final AString ECHO = Strings.create("v/test/ops/echo");
	private static final int SENT = 5;

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

			List<CompletableFuture<Job>> sent = new ArrayList<>();
			for (int i = 0; i < SENT; i++) sent.add(client.startJobAsync(ECHO, Maps.empty()));
			List<Throwable> refused = new ArrayList<>();
			for (CompletableFuture<Job> invoke : sent) {
				try {
					assertNotNull(invoke.join());
				} catch (CompletionException e) {
					refused.add(e.getCause());
				}
			}
			assertFalse(refused.isEmpty(), "invokes beyond the caller's one token are refused");
			for (Throwable cause : refused) {
				assertInstanceOf(RateLimitException.class, cause,
					"a venue 429 must surface as RateLimitException, was: " + cause);
			}
		} finally {
			server.close();
		}
	}
}
