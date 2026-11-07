package covia.exception;

/**
 * Base class for Covia client and server custom exceptions. Its message is
 * written for the caller. A failure the caller can cause or act on is one of
 * its subtypes; a {@link FaultException} is a scope's report of a fault in
 * code the request ran. Anything else reaching a caller has not been reported
 * yet: the scope it escapes reports it ({@link FaultException#within}).
 */
@SuppressWarnings("serial")
public class CoviaException extends RuntimeException {

	public CoviaException(String message) {
		super(message);
	}

	public CoviaException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * The reason a failure gives its caller, looking through async wrappers:
	 * a CoviaException's message. Null for anything else, including a
	 * CoviaException without a message, whose text is not written for the
	 * caller and may carry a remote's body or a credential.
	 */
	public static String reasonOf(Throwable t) {
		Throwable cause = unwrap(t);
		if (!(cause instanceof CoviaException)) return null;
		String message = cause.getMessage();
		return (message == null || message.isBlank()) ? null : message;
	}

	/** Looks through {@code CompletionException} and {@code ExecutionException} wrappers to the failure itself. */
	static Throwable unwrap(Throwable t) {
		Throwable cause = t;
		while ((cause instanceof java.util.concurrent.CompletionException
				|| cause instanceof java.util.concurrent.ExecutionException)
				&& cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
	}

}
