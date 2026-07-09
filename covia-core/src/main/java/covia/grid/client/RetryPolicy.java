package covia.grid.client;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Retry policy for HTTP 429 (Too Many Requests) responses: bounded attempts with
 * exponential backoff and <b>full jitter</b>, honouring a server {@code Retry-After}
 * as a floor and never exceeding an overall wait budget.
 *
 * <p>This is a pure decision object — no clock, no sleeping, no I/O — so it can be
 * unit-tested deterministically by passing an explicit random value. The caller
 * (the client's send loop) supplies {@code random} from its own generator, does
 * the sleeping, and re-issues the request.</p>
 *
 * <p>Full jitter (randomising the <i>whole</i> backoff interval, not just adding a
 * little) avoids synchronised retry storms when many clients receive the same
 * {@code Retry-After} and would otherwise all wake at the same instant.</p>
 */
public final class RetryPolicy {

	private final int maxAttempts;     // total attempts incl. the first (4 = 1 try + 3 retries)
	private final long baseDelayMs;    // backoff base
	private final long maxDelayMs;     // per-attempt backoff ceiling
	private final long maxTotalWaitMs; // overall budget for cumulative retry sleeping

	public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs, long maxTotalWaitMs) {
		if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
		if (baseDelayMs < 0 || maxDelayMs < 0 || maxTotalWaitMs < 0)
			throw new IllegalArgumentException("delays must be >= 0");
		this.maxAttempts = maxAttempts;
		this.baseDelayMs = baseDelayMs;
		this.maxDelayMs = maxDelayMs;
		this.maxTotalWaitMs = maxTotalWaitMs;
	}

	/** Defaults: 4 attempts (1 + 3 retries), 200ms base, 10s cap, 30s total budget. */
	public static RetryPolicy defaults() {
		return new RetryPolicy(4, 200, 10_000, 30_000);
	}

	/** A no-retry policy — fail fast on the first 429. */
	public static RetryPolicy noRetry() {
		return new RetryPolicy(1, 0, 0, 0);
	}

	public int maxAttempts() { return maxAttempts; }
	public long maxTotalWaitMs() { return maxTotalWaitMs; }

	/**
	 * Delay (ms) to wait before the next attempt after a 429, or {@code -1} to give up.
	 *
	 * @param attempt            the attempt that just returned 429 (1 = the first send)
	 * @param retryAfterMs       server {@code Retry-After} in ms (0 if none) — a floor
	 * @param remainingBudgetMs  time left in the overall retry budget
	 * @param random             a value in {@code [0,1)} for full jitter
	 */
	public long retryDelayMs(int attempt, long retryAfterMs, long remainingBudgetMs, double random) {
		if (attempt >= maxAttempts) return -1;                 // no attempts left
		double backoff = Math.min(maxDelayMs, baseDelayMs * Math.pow(2, attempt - 1));
		long jittered = (long) (random * backoff);             // full jitter: [0, backoff)
		long delay = Math.max(Math.max(0, retryAfterMs), jittered);
		if (delay > remainingBudgetMs) return -1;              // would exceed the budget/deadline
		return delay;
	}

	/**
	 * Parses a {@code Retry-After} header — delta-seconds or an HTTP-date — into
	 * milliseconds from {@code nowMs}. Returns 0 when absent, unparseable, or in
	 * the past.
	 */
	public static long parseRetryAfterMs(String header, long nowMs) {
		if (header == null) return 0;
		String h = header.trim();
		if (h.isEmpty()) return 0;
		try {
			return Math.max(0, Long.parseLong(h) * 1000);
		} catch (NumberFormatException ignore) {
			// not delta-seconds — try HTTP-date
		}
		try {
			long epochMs = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME)
				.toInstant().toEpochMilli();
			return Math.max(0, epochMs - nowMs);
		} catch (RuntimeException ignore) {
			return 0;
		}
	}
}
