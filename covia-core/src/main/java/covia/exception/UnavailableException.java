package covia.exception;

/**
 * Something the request depends on failed or could not be reached — another
 * venue, a model provider, a store. Not the caller's fault; the message names
 * the dependency, and retrying later may succeed.
 */
@SuppressWarnings("serial")
public class UnavailableException extends CoviaException {

	public UnavailableException(String message) {
		super(message);
	}

	public UnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
