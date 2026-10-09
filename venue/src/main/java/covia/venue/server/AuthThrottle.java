package covia.venue.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import covia.venue.TrustedProxies;

/**
 * Backpressure on authentication itself, per client address (covia#539).
 *
 * <p>The request rate limiter keys on the caller's DID, which a rejected
 * credential never has, and it runs after authentication has already done
 * its work. So without this, bad tokens are free to send: each one costs a
 * signature check and, for a {@code did:web} subject, an outbound fetch to a
 * host of the sender's choosing. Two controls close that, both keyed on the
 * client address ({@link TrustedProxies} decides what that is behind a
 * proxy):</p>
 *
 * <ul>
 *   <li><b>A failure budget.</b> A token bucket that counts <i>rejected</i>
 *       credentials, not attempts, so a legitimate client is never charged for
 *       authenticating. An address that has spent its budget is answered 429
 *       before any verification runs, until the bucket refills.</li>
 *   <li><b>One authentication in flight at a time</b> (configurable). A
 *       second concurrent attempt from the same address waits for the first,
 *       up to the configured wait, then is shed with 429. So an address cannot
 *       fan out outbound DID fetches in parallel, whatever its budget. Request
 *       threads are virtual, so waiting is cheap.</li>
 * </ul>
 *
 * <p>Neither control applies to requests that present no credential.</p>
 */
public final class AuthThrottle {

	private static final Logger log = LoggerFactory.getLogger(AuthThrottle.class);

	/** 429 messages; tests assert against these, never against the wording. */
	public static final String TOO_MANY_FAILURES =
		"Too many failed authentication attempts from this address; retry after ";
	public static final String TOO_MANY_CONCURRENT =
		"Too many concurrent authentication attempts from this address";

	/** Sweep idle gates once the map exceeds this many addresses. */
	private static final int SWEEP_THRESHOLD = 10_000;

	private final TrustedProxies proxies;
	private final RateLimiter failures;
	private final double failuresPerMinute;
	private final double failureBurst;
	private final int concurrency;
	private final long waitMillis;
	private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	/**
	 * @param proxies            what decides the client address
	 * @param failureBurst       failures an address may accumulate before it is refused (bucket capacity)
	 * @param failuresPerMinute  sustained failure rate that refills the bucket
	 * @param concurrency        authentications an address may have in flight at once
	 * @param waitMillis         how long a further attempt waits for a slot before it is shed
	 */
	public AuthThrottle(TrustedProxies proxies, double failureBurst, double failuresPerMinute,
			int concurrency, long waitMillis) {
		if (concurrency < 1) throw new IllegalArgumentException("concurrency must be >= 1");
		if (waitMillis < 0) throw new IllegalArgumentException("waitMillis must be >= 0");
		this.proxies = (proxies != null) ? proxies : TrustedProxies.NONE;
		this.failures = new RateLimiter(failureBurst, failuresPerMinute / 60.0);
		this.failuresPerMinute = failuresPerMinute;
		this.failureBurst = failureBurst;
		this.concurrency = concurrency;
		this.waitMillis = waitMillis;
	}

	/** The client address a request is charged to. */
	public String clientIp(String remote, String forwardedFor) {
		return proxies.clientIp(remote, forwardedFor);
	}

	/** Whole seconds until the address may present a credential again, or 0 when it is within budget. */
	public long overBudgetRetryAfter(String ip) {
		if (!failures.isExhausted(ip)) {
			warned.remove(ip);
			return 0;
		}
		return Math.max(1, failures.retryAfterSeconds(ip));
	}

	/** Charge one rejected credential to the address. Warns once when it crosses the budget. */
	public void recordFailure(String ip) {
		failures.tryAcquire(ip);
		if (failures.isExhausted(ip) && warned.add(ip)) {
			log.warn("Authentication failures from {} exceeded the budget ({} per minute, burst {}); "
				+ "its credentials are refused with 429 until the budget refills", ip,
				(long) failuresPerMinute, (long) failureBurst);
		}
	}

	/**
	 * Take one of the address's in-flight slots, waiting up to the configured
	 * time. Returns the gate to {@link Semaphore#release() release} when the
	 * authentication is done — the exact one acquired, since a swept-out gate
	 * must not be released through a replacement — or null when the wait ran out.
	 */
	public Semaphore acquire(String ip) {
		if (gates.size() > SWEEP_THRESHOLD) sweepIdle();
		Semaphore gate = gates.computeIfAbsent(ip, k -> new Semaphore(concurrency, true));
		try {
			return gate.tryAcquire(waitMillis, TimeUnit.MILLISECONDS) ? gate : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** Drops gates nobody holds or waits on. A dropped gate is recreated on next use. */
	private void sweepIdle() {
		gates.forEach((ip, gate) -> {
			if (gate.availablePermits() == concurrency && !gate.hasQueuedThreads()) gates.remove(ip, gate);
		});
	}

	/** Live gates — for tests and metrics. */
	int gateCount() {
		return gates.size();
	}

	public int concurrency() {
		return concurrency;
	}

	public double failuresPerMinute() {
		return failuresPerMinute;
	}

	public double failureBurst() {
		return failureBurst;
	}
}
