package covia.exception;

/**
 * Exception for HTTP/API response errors in VenueHTTP client operations.
 * Carries the HTTP response (if available) for inspection by callers.
 */
@SuppressWarnings("serial")
public class ResponseException extends CoviaException {

	public final Object response;

	public ResponseException(String message) {
		super(message);
		this.response=null;
	}

	public ResponseException(String message, Object response) {
		super(message);
		this.response=response;
	}

	public ResponseException(String message, Throwable cause) {
		super(message);
		this.response=null;
		initCause(cause);
	}
}
