package covia.exception;

/**
 * Two values conflict and cannot both stand, such as a new value where one
 * already exists or an update that would replace what must be kept. The
 * message says what conflicts, so the caller can resolve it. A request the
 * target's current state does not allow is not a conflict: it is bad input,
 * not supported, or unavailable.
 */
@SuppressWarnings("serial")
public class ConflictException extends CoviaException {

	public ConflictException(String message) {
		super(message);
	}
}
