# Covia Python adapter module

This optional venue module wraps `covia-python` and exposes operator-configured
scripts as ordinary Covia operations. It is not included in `covia.jar`.

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
      },
      "instances": {
        "maxPerUser": 8,
        "maxTotal": 128,
        "templates": {
          "health-session": {
            "script": "/opt/venue/python/health_session.py",
            "functions": ["add_reading", "summary", "reset"]
          }
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

## Stateful instances

The `instances` object is itself the operator opt-in. It installs four ordinary
venue operations (and therefore four MCP tools when the venue exposes its
operation catalog):

- `v/ops/python/instances/create` — create an isolated globals namespace from a
  configured template
- `v/ops/python/instances/list` — list the effective venue user's live instances
- `v/ops/python/instances/call` — call an allowlisted synchronous function with
  an `args` vector of zero or more positional Convex values
- `v/ops/python/instances/close` — close an instance and release its Python
  references

Create and list results include the instance's allowed `functions`, so an MCP
agent can discover the call surface after selecting a template.

Instances are process-local and ephemeral: venue restart closes them. Ownership
uses `RequestContext.getUserDID()`, so an agent sub-principal works in its owning
user's instance namespace while the creator's actual caller DID remains visible
as `createdBy`. Other users cannot list, call, or close the instance, even if
they learn its random ID. The venue's shared synthetic public principal is never
accepted as an instance owner, even if public invocation is configured.
`maxPerUser` and `maxTotal` bound native resource use.

Callers cannot submit Python source, host paths, or arbitrary global names. The
operator selects every template path and explicitly allowlists its callable
functions. Running a configured function, or creating or calling an instance,
needs the operator's grant of `admin/execute/python` (the venue's
`docs/CONFIG.md`, Python operations).

Python executes in-process with the venue's full authority; it is not a sandbox.
Templates must therefore be trusted operator code. Start the Java 22+ venue with
native access enabled for the module (for example,
`--enable-native-access=ALL-UNNAMED` for classpath deployment).
