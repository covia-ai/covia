# Covia Python adapter module

This optional venue module wraps `covia-python` and exposes operator-configured
scripts as ordinary Covia operations. It is not included in `covia.jar`. Its
design, and the redesign proposed for it, are in
[venue/docs/adapters/PYTHON.md](../venue/docs/adapters/PYTHON.md).

Build the reactor on Java 22+ to include the native FFM backend, then list the
shaded module jar and configure the `python` adapter it provides:

```json
{
  "modules": ["modules/covia-python-adapter-<version>-module.jar"],
  "adapters": {
    "python": {
      "library": "/usr/lib/libpython3.13.so",
      "operations": {
        "health/score": {
          "script": "/opt/venue/python/health_score.py",
          "function": "main",
          "input": { "type": "object" },
          "output": { "type": "object" }
        }
      }
    }
  }
}
```

`adapters.python.enabled: false` switches the adapter off. When Java FFM, native
access, or CPython is absent, the module loads but the adapter fails to start:
it stays enabled but failed, with the reason, and the venue starts without it. Native integration tests
skip under the same conditions. A malformed setting, or a script path that names
no readable file, is refused the same way. The operations are published from
these settings as the adapter installs, so they cannot change while it runs:
change them in the venue config and restart.

## Running and offering functions

Each script is loaded once when the adapter starts, and its globals persist
between calls for the venue's lifetime. Callers cannot submit Python source,
host paths or global names: the operator configures every function. Running
one needs the operator's grant of `admin/execute/python` (the venue's
`docs/CONFIG.md`, Python operations). To offer a function to other principals
without a grant, the venue publishes it as a callable, which decides who may
call it and with what caps (the venue's `docs/CALLABLES.md`). A function
configured with `"principals": true` is also told the authority it runs with
and who called it, so one offered through a callable can keep each caller's
state apart (the venue's `docs/CONFIG.md`, Python operations). A config that
names `instances`, which earlier releases used for per-user copies of a
template, is refused with a message saying so.

Python executes in-process with the venue's full authority; it is not a sandbox.
Scripts must therefore be trusted operator code. Start the Java 22+ venue with
native access enabled for the module (for example,
`--enable-native-access=ALL-UNNAMED` for classpath deployment).
