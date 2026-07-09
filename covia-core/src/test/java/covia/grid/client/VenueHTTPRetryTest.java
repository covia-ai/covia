package covia.grid.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import covia.exception.RateLimitException;

/**
 * Deterministic tests for {@link VenueHTTP}'s synchronous 429 retry loop. The
 * transport is a scripted sequence of responses and the sleep hook records
 * durations instead of waiting — so the loop's behaviour (retry, honour
 * Retry-After, give up, don't retry non-429) is exercised without a socket,
 * real time, or load.
 */
public class VenueHTTPRetryTest {

	private static VenueHTTP client() {
		return new VenueHTTP(URI.create("http://localhost:1")); // no connection is made
	}

	/** Minimal fake response carrying a status code and optional Retry-After. */
	private static HttpResponse<String> resp(int code, String retryAfter) {
		HttpHeaders headers = (retryAfter == null)
			? HttpHeaders.of(Map.of(), (a, b) -> true)
			: HttpHeaders.of(Map.of("Retry-After", List.of(retryAfter)), (a, b) -> true);
		return new HttpResponse<>() {
			public int statusCode() { return code; }
			public HttpRequest request() { return null; }
			public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
			public HttpHeaders headers() { return headers; }
			public String body() { return "body"; }
			public Optional<SSLSession> sslSession() { return Optional.empty(); }
			public URI uri() { return null; }
			public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
		};
	}

	@Test
	public void testRetriesThenSucceeds() throws Exception {
		VenueHTTP c = client();
		List<Long> slept = new ArrayList<>();
		c.setSleeper(slept::add);
		c.setRng(() -> 0.0); // jitter 0 → delay is exactly the Retry-After floor
		c.setRetryPolicy(new RetryPolicy(5, 100, 10_000, 60_000));

		Iterator<HttpResponse<String>> script =
			List.of(resp(429, "1"), resp(429, "1"), resp(200, null)).iterator();
		HttpResponse<String> r = c.retrying(script::next);

		assertEquals(200, r.statusCode());
		assertEquals(List.of(1000L, 1000L), slept, "slept once per 429, honouring Retry-After");
	}

	@Test
	public void testGivesUpAfterMaxAttempts() {
		VenueHTTP c = client();
		List<Long> slept = new ArrayList<>();
		c.setSleeper(slept::add);
		c.setRng(() -> 0.0);
		c.setRetryPolicy(new RetryPolicy(3, 100, 1000, 60_000));

		RateLimitException ex = assertThrows(RateLimitException.class,
			() -> c.retrying(() -> resp(429, "2")));
		assertEquals(2, ex.getRetryAfterSeconds());
		assertEquals(2, slept.size(), "3 attempts → 2 sleeps, then give up");
	}

	@Test
	public void testNoRetryOnNon429() throws Exception {
		VenueHTTP c = client();
		List<Long> slept = new ArrayList<>();
		c.setSleeper(slept::add);
		HttpResponse<String> r = c.retrying(() -> resp(500, null));
		assertEquals(500, r.statusCode(), "non-429 returned as-is for the caller's handling");
		assertTrue(slept.isEmpty(), "5xx is not retried by the 429 policy");
	}

	@Test
	public void testSuccessFirstTryNoSleep() throws Exception {
		VenueHTTP c = client();
		List<Long> slept = new ArrayList<>();
		c.setSleeper(slept::add);
		assertEquals(200, c.retrying(() -> resp(200, null)).statusCode());
		assertTrue(slept.isEmpty());
	}
}
