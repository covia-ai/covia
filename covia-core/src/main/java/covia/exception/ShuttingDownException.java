package covia.exception;

/**
 * This venue is shutting down and accepts no new work. Unlike other
 * {@link UnavailableException}s it concerns the venue itself, not something
 * the request depends on; the request may succeed once the venue is back.
 */
@SuppressWarnings("serial")
public class ShuttingDownException extends UnavailableException {

	public ShuttingDownException(String message) {
		super(message);
	}
}
