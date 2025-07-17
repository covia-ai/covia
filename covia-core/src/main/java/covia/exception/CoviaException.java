package covia.exception;

/**
 * Base class for Covia client and server custom exceptions
 */
@SuppressWarnings("serial")
public class CoviaException extends RuntimeException {

	public CoviaException(String message) {
		super(message);
	}
	
}
