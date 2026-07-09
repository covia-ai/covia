package covia.grid.client;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

/**
 * Pure, deterministic tests for {@link RetryPolicy} — the retry decision and
 * Retry-After parsing. Random jitter is supplied explicitly, so there is no
 * clock, sleeping, or non-determinism.
 */
public class RetryPolicyTest {

	@Test
	public void testRetryAfterIsFloor() {
		RetryPolicy p = new RetryPolicy(5, 100, 10_000, 60_000);
		// jitter 0 → delay is exactly the Retry-After floor
		assertEquals(3000, p.retryDelayMs(1, 3000, 60_000, 0.0));
	}

	@Test
	public void testFullJitterWithinBackoff() {
		RetryPolicy p = new RetryPolicy(5, 100, 10_000, 60_000);
		// attempt 3 → backoff = 100 * 2^2 = 400; random 0.5 → 200
		assertEquals(200, p.retryDelayMs(3, 0, 60_000, 0.5));
		// random near 1 → below the backoff ceiling
		assertTrue(p.retryDelayMs(3, 0, 60_000, 0.999) < 400);
	}

	@Test
	public void testBackoffCaps() {
		RetryPolicy p = new RetryPolicy(20, 100, 1000, 600_000);
		// attempt 10 raw backoff is huge; capped at maxDelay
		assertTrue(p.retryDelayMs(10, 0, 600_000, 0.999) <= 1000);
	}

	@Test
	public void testGiveUpAtMaxAttempts() {
		RetryPolicy p = new RetryPolicy(3, 100, 1000, 60_000);
		assertTrue(p.retryDelayMs(1, 0, 60_000, 0.0) >= 0);
		assertTrue(p.retryDelayMs(2, 0, 60_000, 0.0) >= 0);
		assertEquals(-1, p.retryDelayMs(3, 0, 60_000, 0.0)); // 3rd 429 → no attempts left
	}

	@Test
	public void testGiveUpWhenExceedsBudget() {
		RetryPolicy p = new RetryPolicy(5, 100, 10_000, 60_000);
		assertEquals(-1, p.retryDelayMs(1, 5000, 1000, 0.0)); // Retry-After 5s > 1s budget
	}

	@Test
	public void testNoRetryPolicy() {
		assertEquals(-1, RetryPolicy.noRetry().retryDelayMs(1, 0, 60_000, 0.0));
	}

	@Test
	public void testParseRetryAfterSeconds() {
		assertEquals(5000, RetryPolicy.parseRetryAfterMs("5", 0));
		assertEquals(0, RetryPolicy.parseRetryAfterMs("0", 0));
		assertEquals(0, RetryPolicy.parseRetryAfterMs("-3", 0)); // past → clamped to 0
	}

	@Test
	public void testParseRetryAfterHttpDate() {
		long now = 1_000_000_000_000L;
		String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
			Instant.ofEpochMilli(now + 10_000).atZone(ZoneOffset.UTC));
		assertEquals(10_000, RetryPolicy.parseRetryAfterMs(header, now));
	}

	@Test
	public void testParseRetryAfterAbsentOrGarbage() {
		assertEquals(0, RetryPolicy.parseRetryAfterMs(null, 0));
		assertEquals(0, RetryPolicy.parseRetryAfterMs("", 0));
		assertEquals(0, RetryPolicy.parseRetryAfterMs("not-a-date", 0));
	}

	@Test
	public void testInvalidArgs() {
		assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1, 1));
	}
}
