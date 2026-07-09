package covia.venue.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory per-key token-bucket rate limiter.
 *
 * <p>Each key gets a bucket of {@code capacity} tokens that refills at
 * {@code refillPerSecond}. {@link #tryAcquire} consumes one token and reports
 * whether the request is admitted; a denied request should be answered with
 * HTTP 429 + {@code Retry-After} (see {@link #retryAfterSeconds}). Bursts up to
 * {@code capacity} are allowed; the sustained rate is capped at
 * {@code refillPerSecond}. This is real backpressure: work is refused at the
 * edge, before any handler runs, and the client is told when to retry.</p>
 *
 * <p>The key is an opaque {@link String} — callers decide what it means (today
 * the caller DID; all anonymous callers share the venue's {@code :public} DID,
 * so they form a single bucket). Keeping the limiter key-agnostic lets richer
 * policies (per operation class, per IP, per tenant) compose a different key
 * later without touching this class.</p>
 *
 * <p>Thread-safe. Buckets are created lazily and are tiny; the key space is
 * bounded by the number of distinct callers. A light sweep drops idle (full)
 * buckets if the map ever grows large, so long uptimes with churn stay bounded.
 * The clock is injectable so refill behaviour can be tested deterministically
 * without sleeping.</p>
 */
public final class RateLimiter {

	private final double capacity;
	private final double refillPerMilli;
	private final LongSupplier clock;
	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	/** Sweep idle buckets once the map exceeds this many keys. */
	private static final int SWEEP_THRESHOLD = 10_000;

	private static final class Bucket {
		double tokens;
		long lastRefillMillis;
		Bucket(double tokens, long now) { this.tokens = tokens; this.lastRefillMillis = now; }
	}

	/** @param capacity        maximum tokens (burst size), &gt; 0
	 *  @param refillPerSecond  sustained refill rate in tokens/second, &gt; 0 */
	public RateLimiter(double capacity, double refillPerSecond) {
		this(capacity, refillPerSecond, System::currentTimeMillis);
	}

	RateLimiter(double capacity, double refillPerSecond, LongSupplier clock) {
		if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
		if (refillPerSecond <= 0) throw new IllegalArgumentException("refillPerSecond must be > 0");
		this.capacity = capacity;
		this.refillPerMilli = refillPerSecond / 1000.0;
		this.clock = clock;
	}

	/** Attempts to consume one token for {@code key}. Returns {@code true} if the
	 *  request is admitted, {@code false} if the bucket is empty (rate exceeded). */
	public boolean tryAcquire(String key) {
		long now = clock.getAsLong();
		if (buckets.size() > SWEEP_THRESHOLD) sweepIdle(now);
		Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
		synchronized (b) {
			refill(b, now);
			if (b.tokens >= 1.0) {
				b.tokens -= 1.0;
				return true;
			}
			return false;
		}
	}

	/** Whole seconds until at least one token is available for {@code key}
	 *  (minimum 1 when currently exhausted), suitable for a {@code Retry-After}
	 *  header. Returns 0 if a token is already available. */
	public long retryAfterSeconds(String key) {
		Bucket b = buckets.get(key);
		if (b == null) return 0;
		synchronized (b) {
			refill(b, clock.getAsLong());
			if (b.tokens >= 1.0) return 0;
			double needed = 1.0 - b.tokens;
			return Math.max(1, (long) Math.ceil(needed / (refillPerMilli * 1000.0)));
		}
	}

	private void refill(Bucket b, long now) {
		long elapsed = now - b.lastRefillMillis;
		if (elapsed > 0) {
			b.tokens = Math.min(capacity, b.tokens + elapsed * refillPerMilli);
			b.lastRefillMillis = now;
		}
	}

	/** Drops buckets that have refilled to full (idle callers). A dropped bucket
	 *  is simply recreated full on next use, so no rate budget is lost. */
	private void sweepIdle(long now) {
		buckets.forEach((k, b) -> {
			synchronized (b) {
				refill(b, now);
				if (b.tokens >= capacity) buckets.remove(k, b);
			}
		});
	}

	/** Number of live buckets — for tests and metrics. */
	public int size() { return buckets.size(); }
}
