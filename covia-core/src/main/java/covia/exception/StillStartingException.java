package covia.exception;

/**
 * What the request needs is still starting up. Unlike other
 * {@link UnavailableException}s it is expected to become available on its
 * own, so the caller can simply try again shortly.
 */
@SuppressWarnings("serial")
public class StillStartingException extends UnavailableException {

	public StillStartingException(String message) {
		super(message);
	}
}
