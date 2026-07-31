# Covia Python adapter module

This optional venue module wraps `covia-python` and exposes operator-configured
scripts as ordinary Covia operations. It is not included in `covia.jar`.

Build the reactor on Java 22+ to include the native FFM backend, then configure
the shaded module jar:

```json
{
  "modules": [{
    "path": "modules/covia-python-adapter-0.8.0-module.jar",
    "config": {
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
  }]
}
```

`enabled` defaults to true. When Java FFM, native access, or CPython is absent,
the module loads but the adapter remains inactive and venue startup continues.
Native integration tests skip under the same conditions. Malformed known
configuration still fails fast.

Python executes in-process with the venue's full authority; it is not a sandbox.
