package covia.exception;

/**
 * Thrown by a client when a venue keeps answering HTTP 429 (Too Many Requests)
 * after its retries are spent, carrying the venue's {@code Retry-After} hint.
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
