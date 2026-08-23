# Models — model definition assets

**Status:** implemented (v1). Built-in LangChain models are generated from a
single data manifest, published as complete operation assets under `v/models/`,
and discovered from that catalog. Provider-operation paths remain compatible.

See [OPERATIONS.md](./OPERATIONS.md) for operations, argument defaults and the
`/v/` namespace, [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) for how a model's
declarations shape a prompt, [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) for
agent configuration, and [SKILLS.md](./SKILLS.md) for the asset pattern this
mirrors.

---

## 1. Problem

Before this change, an agent named its model twice, in two vocabularies: `llmOperation` was a
lattice path to a *provider* operation (`v/ops/langchain/anthropic`), and
`model` is that provider's bare id (`claude-sonnet-5`). Everything the venue
knows about a particular model lives wherever it happened to be put:

- assembly facts — context budget, tool calling, label dialect — on the
  provider operation's `model` facet, per model under `byModel`;
- the model lists, default model and recommendations that `llm:models`
  reported — in Java (`LangChainAdapter.HOSTED_PROVIDERS`);
- default inputs for a model — `maxTokens`, a `url`, a deployment's key —
  nowhere: an operator hand-writes a config layer per provider
  (AGENT_TEMPLATES.md shows `w/agent-config/providers/anthropic`);
- per-deployment variants (Azure OpenAI, a proxy, a fine-tune) — the same
  hand-written layer, again.

There is no catalog of models to list, reference, pin or share, and the facts
about one model are split across an asset, a map inside it, a Java table and
a user's workspace.

## 2. Principles

1. **A model is an operation.** The thing an agent calls is "run inference
   with model X through provider Y"; that is an operation, and Covia already
   has operation assets with argument defaults. A model definition is the
   provider operation preset for one model — not a new kind of thing. Its
   defaults shape the call; they do not restrict it.
2. **Declared as data, on the asset.** What the venue knows about a model is
   on the model asset, readable with the accessors that already read the
   `model` facet. No model facts in Java.
3. **One authored fact, one home.** The provider operation is the authoring
   source for its API contract, endpoint and secret convention; the model
   asset is the source for the model's nature; an agent states only what is
   true of its use. A model asset materialises the provider operation contract
   as a generated snapshot so it remains a complete, safe operation asset;
   those copied fields are derived, never independently authored.
4. **The id is data; the path is a name.** `model.id` and
   `operation.default.model` carry the provider's id verbatim —
   `gpt-5.6-terra`, `qwen2.5:7b`, `google/gemini-3.6-flash`. The catalog uses
   that id directly where the path grammar permits and exposes the exact id in
   every listing. An alias need not equal the id, and no consumer parses a
   path to discover it.
5. **Identity is the hash of the preset.** A model asset is content-addressed.
   Catalog paths, aliases and pins are names for hashes; `a/<hash>` identifies
   the immutable operation metadata and defaults. It does not claim that a
   caller cannot override those defaults, nor pin provider code, credentials,
   endpoint behaviour or upstream model weights.
6. **Policy stays in capabilities.** Defaults are purpose-shaping, never
   policy. A caller may override `model`, `url`, `apiKey` or any other default.
   If a deployment must constrain an argument, a capability gate evaluates
   the effective input at dispatch (OPERATIONS.md, *Argument defaults*).

## 3. A model is an operation asset

```json
{
  "name": "Claude Sonnet 5",
  "description": "Anthropic's balanced model: tool calling, prompt caching, 1M context.",
  "creator": "Covia",
  "operation": {
    "adapter": "langchain:anthropic",
    "secretFields": ["apiKey"],
    "secretKey": "ANTHROPIC_API_KEY",
    "default": { "model": "claude-sonnet-5", "maxTokens": 16000 },
    "input":   { "type": "object", "properties": { "...": "the provider's input schema" } },
    "output":  { "type": "object", "properties": { "...": "the provider's output schema" } }
  },
  "model": {
    "id": "claude-sonnet-5",
    "provider": "v/ops/langchain/anthropic",
    "options":  { "toolCalling": true },
    "budget":   { "bytes": 800000 },
    "tags":     ["balanced"]
  }
}
```

| Field | Meaning |
|-------|---------|
| `operation` contract | A generated snapshot of every provider-operation field needed for correct dispatch, validation, discovery and persistence: at least `adapter`, `input`, `output`, `secretKey`, `secretFields`, `strict`, plus any execution-relevant top-level protocol annotations the provider declares. A model asset adds no adapter code, but it must not lose behaviour or redaction by copying only the input schema. |
| `operation.default` | The preset: the provider's model id under `model`, plus model-specific defaults such as `maxTokens` or `temperature`. Deployment fields originate on the serving operation — for example `operation.default.url` and `operation.secretKey` — and enter the generated snapshot from there. Defaults merge under the caller's input at dispatch; the caller always wins. An `apiKey` default may only be an `s/<SECRET>` reference, never credential material. |
| `model.id` | The exact, case-sensitive provider model id. This is authoritative for discovery; paths are never parsed to recover it. For a normal catalog entry it equals `operation.default.model`. |
| `model.provider` | The serving-profile operation from which the operation snapshot and provider-level facet were derived. The profile resolver follows it once (§5); it is provenance and inheritance for declared behaviour, not an execution-site reference. |
| `model.options`, `model.budget` | The model's assembly facts, in the facet's existing shape; only what differs from the provider's. |
| `model.tags` | Free vocabulary for discovery (`balanced`, `quality`, `economical`, `coding`, `local`…). |

A model asset is invocable directly — `grid:run v/models/anthropic/claude-sonnet-5 {messages: […]}` — because it is an operation. An agent uses it with the key it already has:

```json
{ "llmOperation": "v/models/anthropic/claude-sonnet-5" }
```

`config.model`, `url` and `apiKey` keep their meaning and still win over the
asset's defaults. The effective model profile follows the effective model id,
not blindly the asset's default (§5): overriding Sonnet with Opus must not keep
Sonnet's budget and capabilities. A fine-tune is normally published as its own
model asset, but using its id as a caller override remains valid. Restrictions
on either form belong in capabilities, not metadata.

## 4. Paths

### 4.1 The catalog: `v/models/<provider>/<id-path>`

Venue-provided models live in a catalog region beside `v/ops/`, `v/skills/`
and `v/agents/templates/`, installed through the focused
`AAdapter.installModel` seam and materialised at boot:

```
v/models/anthropic/claude-sonnet-5
v/models/anthropic/claude-haiku-4-5-20251001
v/models/openai/gpt-5.6-terra
v/models/gemini/gemini-3.6-flash
v/models/ollama/qwen2.5:7b
v/models/openrouter/google/gemini-3.6-flash
v/models/openrouter/meta-llama/llama-4-maverick
v/models/azure-prod/gpt-5.4-mini
```

**`<provider>`** is a stable catalog key for a *serving profile*: the provider
operation referenced by the model assets below it. The built-ins use familiar
names — `anthropic`, `openai`, `gemini`, `ollama`, `openrouter`, `xai`,
`deepseek`, `mistral` — but the key is not derived from
`operation.adapter`. An operator may add `azure-prod`, `openai-proxy` or
`ollama-gpu` operations that dispatch through an existing adapter with their
own endpoint and secret convention. The catalog groups models by who serves
them, not who made them: Llama through Ollama and Llama through OpenRouter are
different entries, and two deployments of the same OpenAI model can coexist
under different serving profiles.

**`<id-path>`** is the readable catalog form of `model.id`. The id stored on
the asset is **verbatim and case-sensitive** — the string the provider's API
takes and `operation.default.model` carries. A listing always returns that
field, so a model or person can copy it into config without translating a
catalog path.

A `/` inside an id is a namespace boundary, which is exactly what an
aggregator means by it: `google/gemini-3.6-flash` under `openrouter` is the
`google` namespace holding `gemini-3.6-flash`. So the depth of the catalog
under `v/models/<provider>/` is the depth of that provider's ids —
`covia:list v/models/anthropic` lists models, `covia:list v/models/openrouter`
lists vendors and `covia:list v/models/openrouter/google` lists that vendor's
models. Installation walks the complete id and rejects a record/namespace
prefix collision — for example a provider cannot publish both `foo` and
`foo/bar` in the same serving profile. Discovery walks recursively; it never
assumes every provider is uniformly flat or namespaced.

**What constrains a path.** The `[a-z][a-z0-9-]*` rule that `v/ops/` names
follow is the existing install-time validator (`AAdapter.isValidCatalogPath`),
a convention chosen for operation names. `v/models/` needs its own validator:

- the serving-profile segment follows the ordinary catalog-name grammar;
- each id segment matches `[A-Za-z0-9][A-Za-z0-9._:+@-]*`;
- segments are non-empty and neither `.` nor `..`;
- a path has no leading or trailing `/`, backslash, percent escape, query,
  fragment, control character or non-canonical Unicode form;
- joining the id segments with `/` equals `model.id` for a canonical entry.

This v1 grammar covers the known hosted, OpenRouter and Ollama ids while
keeping lattice paths, DID URLs and HTTP references unambiguous. A provider id
outside it remains usable in an authored asset addressed through `/o/` or
`a/<hash>`, but is not installed in `v/models/` until Covia defines one
canonical reversible path encoding. Do not improvise an encoding at install
time.

### 4.2 Aliases

An alias is a second catalog entry for the same hash. `v/models/anthropic/claude-haiku`
declared against the asset that `v/models/anthropic/claude-haiku-4-5-20251001`
names is how "the current Haiku" is expressed; moving the alias is a
re-declaration. Every venue catalog name is operator-mutable, including a
dated-looking one; immutability comes only from `a/<hash>`. A venue may adopt
the convention that dated names never move, but the resolver does not enforce
that promise. There is no `latest` convention beyond this; an alias is an
ordinary name.

Generated models have one canonical binding whose id path reconstructs
`model.id`; aliases are additional bindings in the same serving profile.
`llm:models` groups bindings by asset hash, prefers that canonical `op` when it
is present, and lists the other names under `aliases`.

### 4.3 The adapter mirror

Every catalog region is mirrored under the installing adapter's own subtree
(OPERATIONS.md, `/v/adapters/<name>/`); the mirror for models follows the
existing rule without a new case:

```
v/adapters/langchain/models/<provider>/<id-path>
```

It is the same hash as the canonical entry — the lattice shares the value —
and is published and retracted with the adapter as one unit.

### 4.4 The user's side

| Location | Example | What it is |
|----------|---------|------------|
| Asset store | `a/<hash>` | The model-operation preset. Immutable, content-addressed and portable to venues with a compatible local adapter. |
| Venue catalog | `v/models/anthropic/claude-sonnet-5` | The venue's name for it, materialised at boot or declared by the operator. |
| User operations | `o/sonnet` | The user's own name for a model operation — a pin of a venue or remote asset, or an authored definition (a private deployment referring to its own secret, never embedding the credential). `/o/` is the existing user operations registry: typed, validated, pinned to `/a/` on invoke (GRID_LATTICE_DESIGN.md §4.3). |
| Workspace draft | `w/drafts/my-model` | A definition being iterated with `covia:write`; invocable as any workspace draft operation is. |
| Remote definition | `did:web:venue.example/v/models/anthropic/claude-sonnet-5` | Another venue's named model definition. Resolution fetches and hash-verifies the definition, then invocation executes locally with local adapters, secrets and context. To run inference on the publishing venue, use explicit `grid:run` with its `venue`. |

A model definition a user authors is an *operation* they own, so it lives in
`/o/` like every other operation they own — not in a `w/models/` region of its
own, which would be a second registry for the same kind of thing.

### 4.5 Pointers, not copies

Facts that are *about the serving profile* and *point at* models live on its
operation facet as references into the catalog:

```json
"model": {
  "options": { "systemMessages": "single", "requiresUserMessage": true, "cachePrefix": true },
  "budget":  { "bytes": 400000 },
  "default": "v/models/anthropic/claude-sonnet-5",
  "recommended": {
    "balanced":    "v/models/anthropic/claude-sonnet-5",
    "quality":     "v/models/anthropic/claude-opus-5",
    "longRunning": "v/models/anthropic/claude-fable-5",
    "economical":  "v/models/anthropic/claude-haiku-4-5-20251001"
  }
}
```

The serving operation is also the authoring home for deployment facts such as
its `operation.secretKey` and default `url`. Model generation copies the
effective operation contract into each model asset so invocation, validation
and redaction remain self-contained. Changing that provider contract produces
new model-asset hashes on the next publication; existing hashes remain the old
snapshots.

The venue's default LLM operation (`defaultLlmOperation` in venue config) is
now `v/models/anthropic/claude-sonnet-5`, with no change to the key or its
type: it was always an operation path.

### 4.6 What is never a path

`config.model` stays the provider's id and never becomes a reference. The
reference an agent holds is unambiguously `llmOperation`; `model` is an input
value that overrides one default. Catalog aliases and encoded paths therefore
never leak into provider API calls.

## 5. Resolution

### 5.1 The facet chain

Today the chain is *provider facet → `byModel` → `config.modelProfile`*. A
model asset makes the selected operation and its default model independently
addressable, but the caller may override that model. Resolution therefore
starts with the **effective input**, after `operation.default` has been merged
under the caller's arguments.

One engine-aware resolver returns the resolved model operation, serving
operation, effective model id, provider profile and assembly profile. When the
selected operation has no `model.provider` (the legacy provider-operation
form), that selected operation is itself the serving operation. The resolver
is used by agent assembly and inspection, discovery and the LangChain provider
edge; those paths do not reconstruct different answers. Its profile chain is:

> **provider facet** (reached through `model.provider`, followed once)
> **→ provider `byModel[effectiveModelId]` compatibility entry**
> **→ model asset facet, only when `effectiveModelId == model.id`**
> **→ `config.modelProfile`**

Each layer is merged one key deep and states only what it changes. The
`byModel` hop retains compatibility for provider-operation calls and during
migration; model-specific facts should move to model assets. The conditional
model-asset hop is essential: invoking the Sonnet preset with an Opus override
must not retain Sonnet's budget, labels or tool capability. If neither the
provider nor another asset describes the override, provider-level facts apply
and the caller may supply `config.modelProfile`.

The execution profile stops after the conditional model-asset layer;
`config.modelProfile` is added only to the assembly profile and cannot change
the provider API dialect. `LangChainAdapter`'s system-message normalisation
reads the execution profile from the same resolved record. Generated model
assets keep provider-owned options such as `systemMessages` on the serving
operation; authored assets should follow the same convention:

| Fact | Home |
|------|------|
| `systemMessages`, `requiresUserMessage`, `cachePrefix`, `toolCallingByModel`, endpoint and secret convention | serving operation |
| `budget.bytes`, `toolCalling`, `labels`, tags and model-specific default inputs | model asset |
| this agent's assembly overrides (`options`, `budget`) | `config.modelProfile` |

The existing pure map helpers remain useful for legacy provider metadata, but
following `model.provider` requires the engine, caller context and resolved
operation asset; it is not added implicitly to the static
`modelProfile(meta, modelId, config)` signature.

### 5.2 Defaults at dispatch

`operation.default` is applied where every operation's defaults are applied
— `JobManager` at dispatch — so the preset is not agent machinery: a direct
`grid:run`, an MCP tool call and an operation fetched from another venue all
get the same merge. The effective-model resolver reads the same declared
default for assembly and inspection, so `agent:context` exposes the model that
dispatch supplies later.

Caller values always win. Capability gates run after defaults and therefore
see the one effective input; a deployment that permits only particular model
ids, endpoints or secret references expresses that rule in a gate. The
complete model operation snapshot carries `secretFields`, so an explicit
credential is redacted from durable Job state exactly as it is for the
provider operation.

## 6. Discovery

`llm:models` enumerates the catalog instead of a Java table. It accepts the
existing optional provider filter. Enumeration is recursive because model ids
may contain `/`. The `models` vector of ids is retained for compatibility;
`entries` carries the operation definitions:

```json
{
  "providers": [{
    "op": "v/ops/langchain/anthropic",
    "provider": "anthropic",
    "ready": true,
    "default": "v/models/anthropic/claude-sonnet-5",
    "recommended": { "balanced": "v/models/anthropic/claude-sonnet-5", "...": "..." },
    "models": ["claude-sonnet-5"],
    "entries": [{
      "op": "v/models/anthropic/claude-sonnet-5",
      "id": "claude-sonnet-5",
      "name": "Claude Sonnet 5",
      "budget": { "bytes": 800000 },
      "options": { "toolCalling": true },
      "tags": ["balanced"],
      "aliases": []
    }]
  }]
}
```

Readiness stays caller-relative (the provider's key in the caller's secret
store or the venue environment; a local server's live reachability) and is
computed per serving profile, since two deployments of one vendor can differ.
The endpoint and secret-name convention come from that profile's operation,
not a provider-name branch in Java.

Discovery treats maps with an `operation` facet as model leaves, groups aliases
by asset hash, and leaves a definition discoverable even when its serving
profile cannot be resolved. Strong operator-entry diagnostics and pagination
can be added when catalog sizes justify them; the built-in catalog remains a
small boot-time snapshot.

The plain catalog reads work too: `covia:list v/models` is the serving
profiles, `covia:list v/models/anthropic` the models, and `covia:read
v/models/anthropic/claude-sonnet-5` the asset. Namespaced providers require
deeper listing or `llm:models`' recursive enumeration.

## 7. Seeding and publishing

The venue ships its known models as generated assets. The filesystem-safe
`adapters/langchain/model-catalog.json` manifest states serving-operation
resources, model ids, names, model-specific defaults, facets and tags. At
install time the generator reads
the serving operation resource, materialises its complete operation contract
into each model asset, applies the manifest entry and installs the result
under `v/models/`. Provider ids are data inside the manifest, never resource
filenames (`qwen2.5:7b.json` is not a portable filename).

Generation is deterministic:

1. Copy the provider's `operation` map and execution-relevant top-level facets
   other than `model`.
2. Shallow-layer the manifest's model-specific defaults over the provider's
   `operation.default`, then set `default.model` to the manifest's exact id.
3. Replace optional presentation fields (`name`, `description`) from the
   manifest.
4. Build the model facet from `{id, provider}` plus the manifest's options,
   budget and tags. Provider-level behaviour remains reachable through the
   reference rather than being copied into this facet.

The same inputs produce the same metadata hash. A generated asset changes
when either its provider contract or its model manifest entry changes.

The generated metadata is stored in CAS before both its canonical catalog
entry and adapter mirror are declared, just like skills and templates. The
former Java provider/default/model table has been removed; runtime defaults
and discovery now come exclusively from declared assets.

An operator publishes a model by storing the asset and writing its metadata at
the catalog name while authenticated as the venue identity (`asset:store`,
then `covia:write v/models/...`) — the operator form of the same CAS plus
declaration performed at boot. A user publishes an owned operation under
`/o/`.

Ollama is the case where model facts may be discovered rather than known, but
a caller's `agent:create` must not mutate venue-global catalog state: its URL,
installed models and capabilities can be caller- or deployment-specific. A
live probe may enrich that response or advise the caller; durable publication
goes under the caller's `/o/`, or into `v/models/<serving-profile>/` only by an
explicit operator action. `toolCallingByModel` remains the fallback until a
declared asset supplies the fact.

## 8. Compatibility

- `llmOperation: v/ops/langchain/<provider>` with a bare `model` keeps
  working unchanged; `byModel` keeps answering for it.
- A provider-operation call that omits `model` keeps its current default. That
  runtime default now lives in `operation.default.model`; a JSON Schema
  `default` alone is not a dispatch default.
- Templates do not change. A template that leaves `llmOperation` unset gets
  the venue default, which is now a model asset.
- A template or caller that explicitly supplies `model`, `url` or `apiKey`
  continues to override the selected model asset's defaults. Gates, if any,
  decide whether that effective input is authorised.
- `config.modelProfile` keeps its meaning as the agent's last layer.
- Remote venues without a `v/models` region are addressed by their provider
  operations, as now. A remote DID reference still fetches a definition for
  local execution; remote execution remains explicit through `grid:run`.

## 9. Implementation

V1 deliberately adds no model registry service or model-specific execution
subsystem:

1. `AAdapter.installModel` validates and declares paths under `v/models/`;
   bootstrap and the existing adapter mirror publish them.
2. `LangChainAdapter` reads one manifest, layers it over complete provider
   operation resources, and stores deterministic model-operation snapshots.
3. `AbstractLLMAdapter.resolveModel` follows `model.provider` once and returns
   the effective id, execution profile and assembly profile. Caller overrides
   select the corresponding provider `byModel` entry rather than retaining the
   preset model's facts.
4. `llm:models` walks `v/models/` recursively and adds caller-relative secret
   readiness or Ollama reachability. It contains no provider/model table.
5. Legacy provider ops carry `operation.default.model`, while the venue default
   points at the Sonnet model asset.

## 10. Open questions

- **Snapshot refresh.** V1 intentionally materialises the complete provider
  operation contract into every generated model asset. That keeps dispatch,
  MCP listing, validation and secret redaction self-contained, at the cost of
  new model hashes when the provider contract changes. Revisit generic
  operation inheritance only if generated snapshots become operationally
  expensive; do not introduce model-only inheritance.
- **Catalog encoding.** V1 rejects provider ids outside the safe grammar in
  §4.1. If a real provider requires more, define and test one reversible
  encoding across lattice paths, DID URLs, REST and SDKs before widening the
  validator.
- **Retirement.** A `deprecated` flag on the facet (with a `replacedBy`
  path) would let `agent:create` advise and `llm:models` filter. Cheap, but
  not needed to land the catalog.
- **Price and limits.** Published token prices can sit on the model facet when
  they describe a provider offering. Rate limits are usually facts about a
  serving deployment, account or operation and belong there instead. Nothing
  consumes either yet, so add them with their first policy or accounting
  consumer rather than guessing one shared shape now.
