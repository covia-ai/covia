package covia.python.internal;

import java.util.List;

import convex.core.data.ACell;

/** Internal boundary which keeps FFM types out of the Java 21 public API. */
public interface PythonBackend {
	String description();
	Object execute(String source, String filename);
	Object evaluate(String expression, Object globals);
	Object get(Object container, String name);
	Object call(Object callable, List<Object> arguments);
	Object fromConvex(ACell value);
	ACell toConvex(Object value);
	void retain(Object value);
	void release(Object value);
}
