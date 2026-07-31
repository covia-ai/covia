package covia.python;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
		return callRef(function, Collections.singletonList(input));
	}

	/** Calls a named function with zero or more positional Convex arguments. */
	public PythonRef callRef(String function, List<? extends ACell> arguments) {
		if (arguments == null) throw new IllegalArgumentException("arguments are required");
		List<PythonRef> converted = new ArrayList<>(arguments.size());
		try (PythonRef callable = function(function)) {
			for (ACell argument : arguments) converted.add(runtime.toPython(argument));
			return callable.call(converted.toArray(PythonRef[]::new));
		} finally {
			for (int i = converted.size() - 1; i >= 0; i--) converted.get(i).close();
		}
	}

	public ACell call(String function, ACell input) {
		try (PythonRef result = callRef(function, input)) {
			return result.toConvex();
		}
	}

	/** Calls a named function with zero or more positional Convex arguments. */
	public ACell call(String function, List<? extends ACell> arguments) {
		try (PythonRef result = callRef(function, arguments)) {
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
