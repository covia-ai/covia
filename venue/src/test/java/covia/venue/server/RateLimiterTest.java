package covia.venue.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the token-bucket {@link RateLimiter}. A
 * controllable clock (an {@link AtomicLong} the test advances) exercises refill
 * without sleeping, so the backpressure/recovery behaviour is exact and stable.
 */
public class RateLimiterTest {

	@Test
	public void testBurstThenDeny() {
		AtomicLong t = new AtomicLong(0);
		RateLimiter rl = new RateLimiter(3, 1, t::get); // burst 3, 1/s
		assertTrue(rl.tryAcquire("a"));
		assertTrue(rl.tryAcquire("a"));
		assertTrue(rl.tryAcquire("a"));
		assertFalse(rl.tryAcquire("a"), "bucket exhausted → denied (backpressure)");
	}

	@Test
	public void testRefillAdmitsAgain() {
		AtomicLong t = new AtomicLong(0);
		RateLimiter rl = new RateLimiter(1, 1, t::get);
		assertTrue(rl.tryAcquire("a"));
		assertFalse(rl.tryAcquire("a"));
		t.addAndGet(1000);                       // +1s → +1 token
		assertTrue(rl.tryAcquire("a"), "backpressure releases after refill");
	}

	@Test
	public void testPartialRefill() {
		AtomicLong t = new AtomicLong(0);
		RateLimiter rl = new RateLimiter(10, 10, t::get); // 10/s
		for (int i = 0; i < 10; i++) assertTrue(rl.tryAcquire("a"));
		assertFalse(rl.tryAcquire("a"));
		t.addAndGet(500);                        // 0.5s → +5 tokens
		for (int i = 0; i < 5; i++) assertTrue(rl.tryAcquire("a"));
		assertFalse(rl.tryAcquire("a"));
	}

	@Test
	public void testCannotBankAboveCapacity() {
		AtomicLong t = new AtomicLong(0);
		RateLimiter rl = new RateLimiter(5, 1, t::get);
		for (int i = 0; i < 5; i++) assertTrue(rl.tryAcquire("a")); // drain
		assertFalse(rl.tryAcquire("a"));
		t.addAndGet(1_000_000);                  // idle a long time
		for (int i = 0; i < 5; i++) assertTrue(rl.tryAcquire("a")); // only capacity back
		assertFalse(rl.tryAcquire("a"), "idle time cannot bank more than capacity");
	}

	@Test
	public void testPerKeyIsolation() {
		AtomicLong t = new AtomicLong(0);
		RateLimiter rl = new RateLimiter(1, 1, t::get);
		assertTrue(rl.tryAcquire("a"));
		assertFalse(rl.tryAcquire("a"));
		assertTrue(rl.tryAcquire("b"), "one caller's exhaustion must not affect another");
	}

	@Test
	public void testRetryAfter() {
		AtomicLong t = new AtomicLong(0);
		RateLimiter rl = new RateLimiter(1, 1, t::get);
		assertTrue(rl.tryAcquire("a"));
		assertFalse(rl.tryAcquire("a"));
		assertTrue(rl.retryAfterSeconds("a") >= 1, "denied caller gets a positive Retry-After");
		assertEquals(0, rl.retryAfterSeconds("never-seen"));
	}

	@Test
	public void testInvalidArgs() {
		assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new RateLimiter(1, 0));
	}
}
