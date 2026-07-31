package covia.python;

import convex.core.data.ACell;

/** Executed Python source with an isolated globals dictionary. */
public final class PythonScript implements AutoCloseable {
	private final PythonRuntime runtime;
	private final PythonRef globals;

	PythonScript(PythonRuntime runtime, PythonRef globals) {
		this.runtime = runtime;
		this.globals = globals;
	}

	public PythonRef function(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("function name is required");
		}
		return globals.get(name);
	}

	public PythonRef callRef(String function, ACell input) {
		try (PythonRef callable = function(function);
				PythonRef argument = runtime.toPython(input)) {
			return callable.call(argument);
		}
	}

	public ACell call(String function, ACell input) {
		try (PythonRef result = callRef(function, input)) {
			return result.toConvex();
		}
	}

	public PythonRef evaluate(String expression) {
		return runtime.evaluate(expression, globals);
	}

	@Override
	public void close() {
		globals.close();
	}
}
