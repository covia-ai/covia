package covia.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import covia.python.internal.PythonBackend;

class PythonRefTest {
	@Test
	void referencesRetainAndReleaseExactlyOnce() {
		CountingBackend backend = new CountingBackend();
		PythonRuntime runtime = PythonRuntime.using(backend);
		PythonRef original = runtime.owned("object");
		PythonRef retained = original.retain();

		assertEquals(1, backend.retains.get());
		original.close();
		original.close();
		assertEquals(1, backend.releases.get());
		assertThrows(IllegalStateException.class, original::retain);

		retained.close();
		assertEquals(2, backend.releases.get());
	}

	private static final class CountingBackend implements PythonBackend {
		final AtomicInteger retains = new AtomicInteger();
		final AtomicInteger releases = new AtomicInteger();

		@Override public String description() { return "test"; }
		@Override public Object execute(String source, String filename) { return source; }
		@Override public Object evaluate(String expression, Object globals) { return expression; }
		@Override public Object get(Object container, String name) { return name; }
		@Override public Object call(Object callable, List<Object> arguments) { return callable; }
		@Override public boolean interruptCurrentCall() { return false; }
		@Override public Object fromConvex(ACell value) { return value; }
		@Override public ACell toConvex(Object value) { return (ACell) value; }
		@Override public void retain(Object value) { retains.incrementAndGet(); }
		@Override public void release(Object value) { releases.incrementAndGet(); }
	}
}
