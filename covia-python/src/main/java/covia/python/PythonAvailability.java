package covia.python;

/** Result of probing the optional in-process CPython runtime. */
public record PythonAvailability(boolean available, String detail) {
	public static PythonAvailability available(String detail) {
		return new PythonAvailability(true, detail);
	}

	public static PythonAvailability unavailable(String detail) {
		return new PythonAvailability(false, detail);
	}
}
