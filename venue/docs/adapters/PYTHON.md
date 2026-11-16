# Python adapter

The optional `python` adapter (module `covia-python-adapter`) runs CPython in
the venue process through Java's foreign function API, using the
[`covia-python`](../../../covia-python/README.md) bridge for the runtime and
for converting values. Its settings and what operators see are in
[CONFIG.md, Python operations](../CONFIG.md#python-operations), and building
and installing the module in its
[README](../../../covia-python-adapter/README.md). This document is its
design: what it does, where that design falls short, and the redesign
proposed in [covia-ai/covia#706](https://github.com/covia-ai/covia/issues/706).

## What it does

The adapter does three jobs, and today each of its features does more than
one of them.

1. **It runs privileged code.** Python runs in the venue process with the
   venue's power; it is not a sandbox. So the code is the operator's: every
   script and function it runs is named in `adapters.python`, and a caller
   never supplies source, a host path or a global name. Who may run it is in
   [CONFIG.md](../CONFIG.md#python-operations).
2. **It keeps Python state.** A configured function's script is loaded once as
   the adapter starts, and its globals last until the adapter closes, shared by
   every caller. An instance is a namespace of its own, loaded from an
   operator's template for one user, and lasts until that user closes it or
   the venue stops.
3. **It publishes an interface for calling Python.** Each configured function
   becomes an operation, `v/ops/python/<id>`, whose name and schemas come
   from its settings. Instances have their own operations
   (`v/ops/python/instances/*`), a list of the functions each template allows
   callers to call, and ownership by the user who created the instance.

| Feature | Runs code | Keeps state | Publishes an interface |
|---------|-----------|-------------|------------------------|
| Configured functions (`operations`) | The script and function named | One namespace per script, shared by all callers | One operation per function |
| Instances (`instances`) | The template's script | One namespace per instance, owned by a user | Four operations, a function allow-list per template, ownership |

## Where the design falls short

- **Settings define the catalogue.** The venue publishes an adapter's
  operations when it installs the adapter, and Python's operations come from
  its settings. So its settings cannot change while it is installed, and
  changing a function needs a restart.
- **A grant names a setting, not code.** The grant to run Python names a
  configured function or template by its id
  ([CONFIG.md](../CONFIG.md#python-operations)), so it carries over to
  whatever script that id names after the settings change.
- **Instances are out of a capability's reach.** The grant names a template,
  not an instance. An instance is state its owner's namespace keeps with no
  resource a capability can name, so only a request acting with that
  namespace's authority reaches it ([UCAN.md](../UCAN.md)). It cannot be
  granted, narrowed or offered to another principal.
- **It decides who may call what.** The template allow-lists and instance
  ownership are an access model of the adapter's own, beside the venue's
  capabilities.

## Proposed redesign

Each job is done once, by the part of the venue whose job it is.

- **Python runs code, and nothing else.** One operation runs a function from
  code given as a content reference: a stored asset, or a file in a file root
  or DLFS drive. Running it needs the operator's grant on a resource that
  names the code by the hash of its source, so a grant approves exact code,
  wherever it is read from and whatever it is called.
- **An interface is a callable.** A callable is a function a principal
  publishes for others to call: it runs an operation with its issuer's
  authority narrowed by caps fixed on it, and a gate decides who may call it.
  Offering a Python function is a callable whose caps cover running that code
  and function. Python publishes no operation per function and keeps no
  allow-list or ownership of its own.
- **A function knows its caller.** The function is told the principal that
  called it, so a script that keeps state for each caller keys it by that
  principal, as a Convex actor keys state by `*caller*` (#706). Per-user
  instances are removed.
- **Settings shrink to the runtime.** With nothing published from them,
  `adapters.python` names only the CPython `library`, and a change applies
  without a restart.

Open questions:

- **Shared state.** Whether code keeps its globals between calls, and for how
  long, once no setting names it: loaded once per source hash and kept, or
  loaded for each call.
- **Input.** How a caller's input reaches the function when an operation
  asset's defaults name the code and function, since a caller's input is
  merged over the defaults ([OPERATIONS.md](../OPERATIONS.md)).
- **Moving over.** How operators replace configured functions and templates
  with stored code and callables.
- **A wrong `library`.** It can be a path or a name the system's loader
  resolves, so `configure` cannot check it: the adapter fails to start, and
  today repairing it needs a venue restart.
