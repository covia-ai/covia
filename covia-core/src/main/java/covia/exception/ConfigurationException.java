package covia.exception;

/**
 * The operator's setup is wrong or incomplete for what was asked. The message
 * says what is not configured, so the caller can report it and the operator
 * can fix it.
 */
@SuppressWarnings("serial")
public class ConfigurationException extends CoviaException {

	public ConfigurationException(String message) {
		super(message);
	}

	public ConfigurationException(String message, Throwable cause) {
		super(message, cause);
	}
}
