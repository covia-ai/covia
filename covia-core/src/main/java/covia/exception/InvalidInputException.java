package covia.exception;

/**
 * The caller's input is wrong. The message names what is wrong with it — the
 * field or value, and why — so the caller can correct the request.
 */
@SuppressWarnings("serial")
public class InvalidInputException extends CoviaException {

	public InvalidInputException(String message) {
		super(message);
	}

	public InvalidInputException(String message, Throwable cause) {
		super(message, cause);
	}
}
