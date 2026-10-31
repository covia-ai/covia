package covia.exception;

/**
 * This venue or component does not do what was asked. The message says why,
 * and the alternative when there is one.
 */
@SuppressWarnings("serial")
public class NotSupportedException extends CoviaException {

	public NotSupportedException(String message) {
		super(message);
	}
}
