package covia.exception;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import covia.api.Fields;

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

	private String errorCode;
	private ACell errorDetail;

	/**
	 * Gives this failure an optional code and structured detail, for whoever
	 * acts on it (ERRORS.md). Both are defined by the producer.
	 *
	 * @param code a namespaced code such as {@code agent:unknown-session}, or null
	 * @param detail what the failure's producer knows of it, or null
	 * @return this exception
	 */
	public CoviaException withError(String code, ACell detail) {
		this.errorCode = code;
		this.errorDetail = detail;
		return this;
	}

	/** The producer's code, or null for an unclassified failure. */
	public String getErrorCode() {
		return errorCode;
	}

	/** The producer's structured detail, or null when it gave none. */
	public ACell getErrorDetail() {
		return errorDetail;
	}

	/** The first failure in {@code t}'s cause chain given a code or detail, or null. */
	public static CoviaException carrying(Throwable t) {
		for (; t != null; t = t.getCause()) {
			if (t instanceof CoviaException c && (c.errorCode != null || c.errorDetail != null)) return c;
		}
		return null;
	}

	/**
	 * {@code record} with the {@code errorCode} and {@code errorDetail} that
	 * {@code failure} carries, and without them when it carries none.
	 */
	public static AMap<AString, ACell> recordingError(AMap<AString, ACell> record, Throwable failure) {
		AMap<AString, ACell> recorded = record.dissoc(Fields.ERROR_CODE).dissoc(Fields.ERROR_DETAIL);
		CoviaException carrying = carrying(failure);
		if (carrying == null) return recorded;
		if (carrying.errorCode != null) recorded = recorded.assoc(Fields.ERROR_CODE, Strings.create(carrying.errorCode));
		return (carrying.errorDetail != null) ? recorded.assoc(Fields.ERROR_DETAIL, carrying.errorDetail) : recorded;
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
