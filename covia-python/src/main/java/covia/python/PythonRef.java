package covia.python;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;

import convex.core.data.ACell;
import covia.python.internal.PythonBackend;

/**
 * An owned {@code PyObject*}. Each instance owns exactly one CPython reference;
 * {@link #close()} performs one {@code Py_DecRef}. A Cleaner is a last-resort
 * safety net, not the primary lifecycle mechanism.
 */
public final class PythonRef implements AutoCloseable {
	private static final Cleaner CLEANER = Cleaner.create();

	private static final class State implements Runnable {
		private final PythonBackend backend;
		private final Object handle;
		private final AtomicBoolean released = new AtomicBoolean();

		State(PythonBackend backend, Object handle) {
			this.backend = backend;
			this.handle = handle;
		}

		@Override
		public void run() {
			if (released.compareAndSet(false, true)) backend.release(handle);
		}
	}

	private final PythonRuntime runtime;
	private final PythonBackend backend;
	private final Object handle;
	private final State state;
	private final Cleaner.Cleanable cleanable;

	PythonRef(PythonRuntime runtime, PythonBackend backend, Object handle) {
		if (handle == null) throw new PythonException("CPython returned a null reference");
		this.runtime = runtime;
		this.backend = backend;
		this.handle = handle;
		this.state = new State(backend, handle);
		this.cleanable = CLEANER.register(this, state);
	}

	public boolean isClosed() {
		return state.released.get();
	}

	public PythonRef retain() {
		Object value = handle();
		backend.retain(value);
		return runtime.owned(value);
	}

	public PythonRef get(String name) {
		return runtime.get(this, name);
	}

	public PythonRef call(PythonRef... arguments) {
		return runtime.call(this, arguments);
	}

	public ACell toConvex() {
		return runtime.toConvex(this);
	}

	@Override
	public void close() {
		cleanable.clean();
	}

	PythonRuntime runtime() {
		return runtime;
	}

	Object handle() {
		if (isClosed()) throw new IllegalStateException("Python reference is closed");
		return handle;
	}
}
