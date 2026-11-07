package covia.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fault in code a request ran, reported by the scope it happened in: an
 * adapter running an operation, an agent's tool handling, the venue handling a
 * request. The message says what failed and whose code was at fault; for an
 * unexpected exception it names the exception's type, never its text, and
 * the cause is chained. The caller cannot fix a fault, so every surface
 * answers it as a server failure.
 */
@SuppressWarnings("serial")
public class FaultException extends CoviaException {

	private static final Logger log = LoggerFactory.getLogger(FaultException.class);

	/** How a scope describes an unexpected exception in its code: the exception's type, then whose code it was. */
	public static final String UNEXPECTED = "unexpected %s in %s";

	/** A scope's report of an unexpected exception: what failed, then {@link #UNEXPECTED}. */
	public static final String FAILED = "%s failed: " + UNEXPECTED;

	/** Whose code it was, for an adapter: its name follows. */
	public static final String ADAPTER = "adapter %s";

	/** Whose code it was, for the venue's own. */
	public static final String VENUE = "the venue";

	/** A fault a scope found and describes itself, such as output that breaks its operation's declared schema. */
	public FaultException(String message) {
		super(message);
	}

	private FaultException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * What a scope reports of a failure that escaped its code. A Covia
	 * exception with a reason is already written for the caller and passes as
	 * it is. Anything else is this scope's fault: it is logged here at ERROR
	 * with its stack, once, and reported as {@link #FAILED}.
	 *
	 * @param what what failed, as its caller knows it, such as "Operation crown:query"
	 * @param owner whose code it was, such as {@code ADAPTER.formatted("crown")} or {@link #VENUE}
	 * @param failure what escaped, looked through for async wrappers
	 */
	public static CoviaException within(String what, String owner, Throwable failure) {
		Throwable cause = unwrap(failure);
		if (cause instanceof CoviaException reported && reasonOf(reported) != null) return reported;
		String message = FAILED.formatted(what, cause.getClass().getSimpleName(), owner);
		log.error(message, cause);
		return new FaultException(message, cause);
	}

	/**
	 * The cause of a failure as a scope states it after its own words for what
	 * failed: a Covia exception's reason, or {@link #UNEXPECTED} for anything
	 * else, logged here at ERROR with its stack.
	 *
	 * @param owner whose code it was
	 * @param failure what escaped, looked through for async wrappers
	 */
	public static String cause(String owner, Throwable failure) {
		Throwable cause = unwrap(failure);
		String detail = describe(owner, cause);
		if (reasonOf(cause) == null) log.error(detail, cause);
		return detail;
	}

	/**
	 * {@link #cause} without logging, for a scope that logs the failure itself,
	 * once, at ERROR with its stack when it is unexpected.
	 */
	public static String describe(String owner, Throwable failure) {
		Throwable cause = unwrap(failure);
		String reason = reasonOf(cause);
		return (reason != null) ? reason : UNEXPECTED.formatted(cause.getClass().getSimpleName(), owner);
	}

	/** Whether a failure is a fault some scope has reported, looking through async wrappers. */
	public static boolean isFault(Throwable t) {
		return unwrap(t) instanceof FaultException;
	}
}
