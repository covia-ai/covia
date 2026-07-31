# Covia Python

`covia-python` is the dependency-light in-process CPython layer. Its only
runtime dependency is `convex-core`; it has no venue, HTTP, JSON, JNI, or Python
process dependency. This boundary is intentional so the package can move to a
lower-level project later.

The public API remains loadable on Java 21. The typed FFM backend is compiled
only on Java 22+, where the Foreign Function & Memory API is stable. On Java 21,
when native access is denied, or when no compatible CPython shared library is
available, `PythonRuntime.availability()` explains why and `tryOpen()` returns
empty.

```java
PythonRuntime runtime = PythonRuntime.open();
try (PythonScript script = runtime.load("""
        def main(value):
            return {"answer": value["x"] * 2}
        """, "example.py")) {
    ACell output = script.call("main", Maps.of("x", 21L));
}
```

`PythonRef` represents one owned `PyObject*` reference. `retain()` performs one
`Py_IncRef`; each wrapper's idempotent `close()` performs one `Py_DecRef` under
the GIL. A Cleaner is only a leak safety net. Scripts retain their isolated
globals dictionary, so module state can persist between calls until the script
is closed.

Supported lossless conversions are nil/`None`, booleans, arbitrary-size
integers, doubles, UTF-8 strings, blobs/`bytes`, vectors/lists/tuples, sets
(as lists), and maps/dicts. Cyclic Python containers and unsupported native
objects fail explicitly.

Library discovery checks `covia.python.library`, `COVIA_PYTHON_LIBRARY`,
Python/Conda/virtual-environment roots, loader paths, and conventional CPython
3.10–3.14 names. Production JVMs should grant FFM access explicitly:

```text
java --enable-native-access=ALL-UNNAMED -jar covia.jar config.json
```

This is native, in-process execution—not a sandbox. Python code and imported C
extensions have the venue process's authority and can crash or compromise it.
Only venue operators choose scripts; use normal venue rate/concurrency controls
for resource governance, and use a separate process or container when isolation
is required.
