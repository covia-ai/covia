package covia.exception;

/**
 * What was asked is never possible here: the venue or component has no such
 * facility, or it is switched off on this venue. Asking again the same way
 * will not help. The message says what is not supported, and the alternative
 * when there is one. The counterpart of the JDK's
 * {@link UnsupportedOperationException}.
 */
@SuppressWarnings("serial")
public class UnsupportedException extends CoviaException {

	public UnsupportedException(String message) {
		super(message);
	}

	public UnsupportedException(String message, Throwable cause) {
		super(message, cause);
	}
}
