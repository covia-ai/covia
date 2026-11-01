package covia.exception;

/**
 * Base class for Covia client and server custom exceptions. A failure a caller
 * can cause or act on is one of these, and its message is the reason written
 * for that caller. Anything else reaching a caller is a runtime or venue bug.
 */
@SuppressWarnings("serial")
public class CoviaException extends RuntimeException {

	public CoviaException(String message) {
		super(message);
	}

	public CoviaException(String message, Throwable cause) {
		super(message, cause);
	}

	/** What a caller is told of an unexpected failure: its type, never its text. */
	public static final String UNEXPECTED = "Unexpected venue error (%s)";

	/**
	 * The reason an expected failure gives its caller, looking through async
	 * wrappers: a CoviaException's message. Null for anything else, including
	 * a CoviaException without a message, whose text is not written for the
	 * caller and may carry a remote's body or a credential.
	 */
	public static String reasonOf(Throwable t) {
		Throwable cause = unwrap(t);
		if (!(cause instanceof CoviaException)) return null;
		String message = cause.getMessage();
		return (message == null || message.isBlank()) ? null : message;
	}

	/**
	 * What a caller is told of a failure: an expected failure's reason, or
	 * {@link #UNEXPECTED} naming the type of anything else.
	 */
	public static String describe(Throwable t) {
		String reason = reasonOf(t);
		return (reason != null) ? reason : UNEXPECTED.formatted(unwrap(t).getClass().getSimpleName());
	}

	private static Throwable unwrap(Throwable t) {
		Throwable cause = t;
		while ((cause instanceof java.util.concurrent.CompletionException
				|| cause instanceof java.util.concurrent.ExecutionException)
				&& cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

}
