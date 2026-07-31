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
  }]
}
```

`enabled` defaults to true. When Java FFM, native access, or CPython is absent,
the module loads but the adapter remains inactive and venue startup continues.
Native integration tests skip under the same conditions. Malformed known
configuration still fails fast.

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
they learn its random ID. `maxPerUser` and `maxTotal` bound native resource use.

Callers cannot submit Python source, host paths, or arbitrary global names. The
operator selects every template path and explicitly allowlists its callable
functions. Each operation still requires its normal path-scoped `invoke`
capability.

Python executes in-process with the venue's full authority; it is not a sandbox.
Templates must therefore be trusted operator code. Start the Java 22+ venue with
native access enabled for the module (for example,
`--enable-native-access=ALL-UNNAMED` for classpath deployment).
