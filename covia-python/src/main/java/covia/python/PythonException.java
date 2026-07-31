package covia.python;

/** Failure reported by CPython or by the native bridge. */
public class PythonException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public PythonException(String message) {
		super(message);
	}

	public PythonException(String message, Throwable cause) {
		super(message, cause);
	}
}
