package covia.exception;

/**
 * The caller is identified, but a rule forbids the request whatever the caller
 * holds, such as a change to data only the framework manages. Asking again the
 * same way will not help. The message names the rule. A capability the
 * request's authority lacks is {@link CapabilityDeniedException}.
 */
@SuppressWarnings("serial")
public class ForbiddenException extends AuthException {

	public ForbiddenException(String message) {
		super(message);
	}

	public ForbiddenException(String message, Throwable cause) {
		super(message, cause);
	}
}
