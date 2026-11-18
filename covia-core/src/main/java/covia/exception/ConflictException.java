package covia.exception;

/**
 * Two values conflict and cannot both stand, such as a new value where one
 * already exists or an update that would replace what must be kept. The
 * message says what conflicts, so the caller can resolve it. A conflict is
 * one state that does not allow the request; any other is
 * {@link InvalidStateException} itself.
 */
@SuppressWarnings("serial")
public class ConflictException extends InvalidStateException {

	public ConflictException(String message) {
		super(message);
	}
}
