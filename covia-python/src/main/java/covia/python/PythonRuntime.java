package covia.python;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import convex.core.data.ACell;
import covia.python.internal.PythonBackend;

/**
 * Process-wide CPython runtime accessed through the Foreign Function &amp; Memory
 * API. The class itself is Java-21-loadable; on Java 21 (or when native access
 * or a CPython shared library is unavailable) {@link #availability()} reports
 * the reason and {@link #tryOpen()} is empty.
 */
public final class PythonRuntime {
	private static final String BACKEND =
		"covia.python.internal.ffm.FFMPythonBackend";

	private record LoadResult(PythonBackend backend, PythonAvailability status) {}
	private static final class Holder {
		private static final LoadResult RESULT = load();
	}

	private final PythonBackend backend;

	private PythonRuntime(PythonBackend backend) {
		this.backend = backend;
	}

	static PythonRuntime using(PythonBackend backend) {
		return new PythonRuntime(backend);
	}

	public static PythonAvailability availability() {
		return Holder.RESULT.status();
	}

	public static Optional<PythonRuntime> tryOpen() {
		PythonBackend backend = Holder.RESULT.backend();
		return backend == null ? Optional.empty()
			: Optional.of(new PythonRuntime(backend));
	}

	/** Opens an explicitly selected CPython shared library without consulting the
	 * cached default probe. Intended for operator configuration. */
	public static Optional<PythonRuntime> tryOpen(String library) {
		if (library == null || library.isBlank()) return tryOpen();
		LoadResult result = load(library);
		return result.backend() == null ? Optional.empty()
			: Optional.of(new PythonRuntime(result.backend()));
	}

	public static PythonRuntime open() {
		return tryOpen().orElseThrow(() ->
			new PythonException("Python runtime unavailable: "
				+ availability().detail()));
	}

	public static PythonRuntime open(String library) {
		if (library == null || library.isBlank()) return open();
		LoadResult result = load(library);
		if (result.backend() == null) {
			throw new PythonException("Python runtime unavailable: "
				+ result.status().detail());
		}
		return new PythonRuntime(result.backend());
	}

	public String description() {
		return backend.description();
	}

	public PythonScript load(String source, String filename) {
		if (source == null) throw new IllegalArgumentException("source is required");
		String name = filename == null || filename.isBlank() ? "<covia>" : filename;
		return new PythonScript(this, owned(backend.execute(source, name)));
	}

	public PythonRef evaluate(String expression) {
		return owned(backend.evaluate(expression, null));
	}

	PythonRef evaluate(String expression, PythonRef globals) {
		return owned(backend.evaluate(expression, requireOwn(globals)));
	}

	public PythonRef toPython(ACell value) {
		return owned(backend.fromConvex(value));
	}

	public ACell toConvex(PythonRef value) {
		return backend.toConvex(requireOwn(value));
	}

	public PythonRef call(PythonRef callable, PythonRef... arguments) {
		List<Object> args = Arrays.stream(arguments)
			.map(this::requireOwn).toList();
		return owned(backend.call(requireOwn(callable), args));
	}

	/**
	 * Best-effort interruption of the Python function call currently executing
	 * through this runtime. A successful request schedules
	 * {@code KeyboardInterrupt} in that call's CPython thread; it does not
	 * guarantee prompt termination. Python bytecode normally observes the
	 * interrupt quickly, while native extensions may observe it only after they
	 * return to the interpreter and a wedged native call may never observe it.
	 *
	 * @return true if CPython accepted an interrupt for an active call, or false
	 *         when no active call could be targeted
	 */
	public boolean interruptCurrentCall() {
		return backend.interruptCurrentCall();
	}

	PythonRef get(PythonRef container, String name) {
		return owned(backend.get(requireOwn(container), name));
	}

	PythonRef owned(Object handle) {
		return new PythonRef(this, backend, handle);
	}

	Object requireOwn(PythonRef ref) {
		if (ref == null) throw new IllegalArgumentException("Python reference is required");
		if (ref.runtime() != this) {
			throw new IllegalArgumentException("Python reference belongs to another runtime");
		}
		return ref.handle();
	}

	private static LoadResult load() {
		return load(null);
	}

	private static LoadResult load(String library) {
		int java = Runtime.version().feature();
		if (java < 22) return unavailable(
			"Java " + java + " has no stable Foreign Function & Memory API (requires 22+)");
		try {
			Class<?> type = Class.forName(BACKEND);
			Method open = type.getMethod("open", String.class);
			PythonBackend backend = (PythonBackend) open.invoke(null, library);
			return new LoadResult(backend,
				PythonAvailability.available(backend.description()));
		} catch (ClassNotFoundException e) {
			return unavailable("FFM backend was not built for this runtime");
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			return unavailable(cause.getMessage() == null
				? cause.getClass().getSimpleName() : cause.getMessage());
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return unavailable(e.getMessage() == null
				? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	private static LoadResult unavailable(String detail) {
		return new LoadResult(null, PythonAvailability.unavailable(detail));
	}
}
