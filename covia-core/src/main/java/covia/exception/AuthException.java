package covia.exception;

/**
 * Thrown when an operation requires authentication or authorisation that
 * the caller has not provided. Distinct from {@link IllegalArgumentException}
 * (bad input) and {@link SecurityException} (JVM-level security).
 */
@SuppressWarnings("serial")
public class AuthException extends CoviaException {

	public AuthException(String message) {
		super(message);
	}
}
