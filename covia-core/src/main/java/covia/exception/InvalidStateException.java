package covia.exception;

/**
 * The request is valid, but the current state of what it addresses does not
 * allow it, such as a message to a finished job or revoking an account's last
 * key. It will not succeed until that state changes. The message names the
 * state and, where it can, what would change it. The counterpart of the JDK's
 * {@link IllegalStateException}, which the venue keeps for its own internal
 * invariants. {@link ConflictException} refines it for a value that cannot
 * stand beside one already there.
 */
@SuppressWarnings("serial")
public class InvalidStateException extends CoviaException {

	public InvalidStateException(String message) {
		super(message);
	}

	public InvalidStateException(String message, Throwable cause) {
		super(message, cause);
	}
}
