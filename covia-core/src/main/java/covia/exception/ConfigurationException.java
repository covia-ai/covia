package covia.exception;

/**
 * The setup a request runs under is wrong or incomplete: the operator's
 * configuration, or the settings and credentials the caller supplied for a
 * dependency, such as a model's key or name. The message says what is not
 * configured and whose it is to fix. Every request made the same way fails
 * until it is fixed.
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
