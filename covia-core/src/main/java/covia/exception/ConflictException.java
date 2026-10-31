package covia.exception;

/**
 * The target's state refuses the request. The message says which state, and
 * what is allowed in it, so the caller can wait, retry or choose otherwise.
 */
@SuppressWarnings("serial")
public class ConflictException extends CoviaException {

	public ConflictException(String message) {
		super(message);
	}
}
