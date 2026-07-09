package covia.exception;

/**
 * Thrown when a request is refused by an admission / rate control — for example
 * a caller already holds the maximum number of concurrent jobs and the bounded
 * wait for a free slot elapsed. Maps to HTTP 429 (Too Many Requests) with a
 * {@code Retry-After} hint.
 */
public class RateLimitException extends CoviaException {

	private static final long serialVersionUID = 1L;

	private final long retryAfterSeconds;

	public RateLimitException(String message, long retryAfterSeconds) {
		super(message);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	/** Suggested seconds for a client to wait before retrying (Retry-After). */
	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
