package covia.exception;

/**
 * A definition the request uses is malformed: an operation, an orchestration
 * or an agent, as stored or as given. Not the caller's input and not a venue
 * error. The message names the definition and what is wrong with it, so its
 * author can fix it.
 */
@SuppressWarnings("serial")
public class InvalidDefinitionException extends CoviaException {

	public InvalidDefinitionException(String message) {
		super(message);
	}

	public InvalidDefinitionException(String message, Throwable cause) {
		super(message, cause);
	}
}
