# Models — model definition assets

**Status:** design. Nothing in this document exists yet except what it builds
on: operation assets with argument defaults (OPERATIONS.md), the `model` facet
and its resolution chain (OPERATIONS.md, *The `model` facet*; AGENT_CONTEXT.md
§8), and the `/v/` catalog regions. Where this document and the code differ,
the code is behind.

See [OPERATIONS.md](./OPERATIONS.md) for operations, argument defaults and the
`/v/` namespace, [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) for how a model's
declarations shape a prompt, [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) for
agent configuration, and [SKILLS.md](./SKILLS.md) for the asset pattern this
mirrors.

---

## 1. Problem

An agent names its model twice, in two vocabularies: `llmOperation` is a
lattice path to a *provider* operation (`v/ops/langchain/anthropic`), and
`model` is that provider's bare id (`claude-sonnet-5`). Everything the venue
knows about a particular model lives wherever it happened to be put:

- assembly facts — context budget, tool calling, label dialect — on the
  provider operation's `model` facet, per model under `byModel`;
- the model lists, default model and recommendations that `llm:models`
  reports — in Java (`LangChainAdapter.HOSTED_PROVIDERS`);
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
   provider operation specialised to one model — not a new kind of thing.
2. **Declared as data, on the asset.** What the venue knows about a model is
   on the model asset, readable with the accessors that already read the
   `model` facet. No model facts in Java.
3. **One fact, one home.** The provider's API behaviour is the provider
   operation's; the model's nature is the model asset's; an agent's use of
   it is the agent's. Each layer states only what differs.
4. **The path is the id.** The catalog name of a model is the provider's
   model id, verbatim — `gpt-5.6-terra`, `qwen2.5:7b`,
   `google/gemini-3.6-flash`. A name that differs from the id, however
   slightly, is a trap for every reader that matters: a model copying a path
   from a listing, a human comparing a config with a provider's docs. The id
   is also stated on the asset, so nothing has to parse a path to learn it.
5. **Identity is the hash.** A model asset is content-addressed. Catalog
   paths, aliases and pins are names for hashes; `a/<hash>` is the model.

## 3. A model is an operation asset

```json
{
  "name": "Claude Sonnet 5",
  "description": "Anthropic's balanced model: tool calling, prompt caching, 1M context.",
  "creator": "Covia",
  "operation": {
    "adapter": "langchain:anthropic",
    "default": { "model": "claude-sonnet-5", "maxTokens": 16000 },
    "input":   { "type": "object", "properties": { "...": "the provider's input schema" } }
  },
  "model": {
    "provider": "v/ops/langchain/anthropic",
    "options":  { "toolCalling": true },
    "budget":   { "bytes": 800000 },
    "tags":     ["balanced"]
  }
}
```

| Field | Meaning |
|-------|---------|
| `operation.adapter` | The provider's adapter — the same dispatch as the provider operation. A model asset adds no adapter code. |
| `operation.default` | The binding: the provider's model id under `model`, plus any input this model wants by default (`maxTokens`, `temperature`, a deployment `url`, an `apiKey` as an `s/<SECRET>` reference). Merged under the caller's input at dispatch; the caller always wins (OPERATIONS.md, *Argument defaults*). |
| `operation.input` | The provider's input schema, copied, so the asset is self-describing to tool listing and MCP like every other operation. |
| `model.provider` | The provider operation this model is served by — the one reference the facet resolver follows (§5). |
| `model.options`, `model.budget` | The model's assembly facts, in the facet's existing shape; only what differs from the provider's. |
| `model.tags` | Free vocabulary for discovery (`balanced`, `quality`, `economical`, `coding`, `local`…). |

A model asset is invocable directly — `grid:run v/models/anthropic/claude-sonnet-5 {messages: […]}` — because it is an operation. An agent uses it with the key it already has:

```json
{ "llmOperation": "v/models/anthropic/claude-sonnet-5" }
```

`config.model`, `url` and `apiKey` keep their meaning and still win over the asset's defaults. A deployment variant is another model asset with different defaults; a fine-tune is a model asset whose `default.model` is the fine-tune's id. No adapter is touched for either.

## 4. Paths

### 4.1 The catalog: `v/models/<provider>/<id>`

Venue-provided models live in a catalog region beside `v/ops/`, `v/skills/`
and `v/agents/templates/`, installed through the same seam
(`AAdapter.installAssetAt`) and materialised at boot:

```
v/models/anthropic/claude-sonnet-5
v/models/anthropic/claude-haiku-4-5-20251001
v/models/openai/gpt-5.6-terra
v/models/gemini/gemini-3.6-flash
v/models/ollama/qwen2.5:7b
v/models/openrouter/google/gemini-3.6-flash
v/models/openrouter/meta-llama/llama-4-maverick
```

**`<provider>`** is the provider operation's own name — the last segment of
`v/ops/langchain/<provider>`: `anthropic`, `openai`, `gemini`, `ollama`,
`openrouter`, `xai`, `deepseek`, `mistral`, and whatever an operator adds. The
catalog groups models by *who serves them*, not by who made them: Llama
through Ollama and Llama through OpenRouter are two operations with different
endpoints, keys and behaviour, and they are two entries.

**`<id>`** is the provider's model id, **verbatim and case-sensitive** — the
string the provider's own API takes, the string a human reads in the
provider's documentation, the string `operation.default.model` carries. No
normalisation, no derived names: a listing is something a model or a person
can copy into a config without translation, and a config is something they
can check against a provider's docs by eye.

A `/` inside an id is a namespace boundary, which is exactly what an
aggregator means by it: `google/gemini-3.6-flash` under `openrouter` is the
`google` namespace holding `gemini-3.6-flash`. So the depth of the catalog
under `v/models/<provider>/` is the depth of that provider's ids —
`covia:list v/models/anthropic` lists models, `covia:list v/models/openrouter`
lists vendors and `covia:list v/models/openrouter/google` lists that vendor's
models. The lattice rule that one level holds records *or* sub-namespaces,
never both, holds because a provider's ids are either all flat or all
namespaced; an aggregator that broke it would be publishing a model named
like another model's vendor, and nobody does that.

**What constrains a path.** The `[a-z][a-z0-9-]*` rule that `v/ops/` names
follow is the install-time validator (`AAdapter.isValidCatalogPath`), a
convention chosen for operation names — not a property of the lattice, whose
keys are strings, nor of resolution, which special-cases only a `did:`
prefix, nor of the HTTP surface, which URL-encodes each segment. For
`v/models/` the validator keeps what is about safety and drops what is about
style: segments are non-empty, none is `.` or `..`, and a path does not start
with `/`. Dots, colons, upper case and whatever else a provider puts in an id
pass through.

### 4.2 Aliases

An alias is a second catalog entry for the same hash. `v/models/anthropic/claude-haiku`
declared against the asset that `v/models/anthropic/claude-haiku-4-5-20251001`
names is how "the current Haiku" is expressed; moving the alias is a
re-declaration, and the dated name stays where it was. Identity never moves:
an agent that must reproduce a run pins `a/<hash>` (or the dated name, which
is a promise not to move), exactly as it would for any operation. There is no
`latest` convention beyond this; an alias is an ordinary name.

### 4.3 The adapter mirror

Every catalog region is mirrored under the installing adapter's own subtree
(OPERATIONS.md, `/v/adapters/<name>/`); the mirror for models follows the
existing rule without a new case:

```
v/adapters/langchain/models/<provider>/<id>
```

It is the same hash as the canonical entry — the lattice shares the value —
and is published and retracted with the adapter as one unit.

### 4.4 The user's side

| Location | Example | What it is |
|----------|---------|------------|
| Asset store | `a/<hash>` | The model. Immutable, content-addressed, shareable across venues. |
| Venue catalog | `v/models/anthropic/claude-sonnet-5` | The venue's name for it, materialised at boot or declared by the operator. |
| User operations | `o/sonnet` | The user's own name for a model operation — a pin of a venue or remote asset, or an authored definition (a private deployment with its own key). `/o/` is the existing user operations registry: typed, validated, pinned to `/a/` on invoke (GRID_LATTICE_DESIGN.md §4.3). |
| Workspace draft | `w/drafts/my-model` | A definition being iterated with `covia:write`; invocable as any workspace draft operation is. |
| Remote | `did:web:venue.example/v/models/anthropic/claude-sonnet-5` | Another venue's model, referenced as any remote operation is; inference runs there. |

A model definition a user authors is an *operation* they own, so it lives in
`/o/` like every other operation they own — not in a `w/models/` region of its
own, which would be a second registry for the same kind of thing.

### 4.5 Pointers, not copies

Facts that are *about the provider* and *point at* models live on the
provider operation's facet as references into the catalog:

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

The venue's default LLM operation (`defaultLlmOperation` in venue config,
today `v/ops/langchain/anthropic`) becomes a model path —
`v/models/anthropic/claude-sonnet-5` — with no change to the key or its type:
it was always an operation path.

### 4.6 What is never a path

`config.model` stays the provider's id and never becomes a reference. A
reference would be told from an id only by sniffing — and since the catalog
name *is* the id, `gemini-3.6-flash` could be either. The reference an agent
holds to its model is `llmOperation`; `model` is the override of one default
input.

## 5. Resolution

### 5.1 The facet chain

Today the chain is *provider facet → `byModel` → `config.modelProfile`*. A
model asset adds one hop and retires the special case:

> **provider facet** (reached through the model asset's `model.provider`,
> followed once) **→ model asset facet → `config.modelProfile`**

— each layer merged one key deep, each stating only what it changes, read by
the one resolver (`AbstractLLMAdapter.modelProfile`). The provider edge —
`LangChainAdapter`'s system-message normalisation, caching, structured
output — reads through the same resolver, so a model asset says nothing about
the API and a provider asset says nothing about a model:

| Fact | Home |
|------|------|
| `systemMessages`, `requiresUserMessage`, `cachePrefix`, `toolCallingByModel`, the key secret | provider operation |
| `budget.bytes`, `toolCalling`, `labels`, tags, default inputs | model asset |
| this agent's override of any of those | `config.modelProfile` |

`byModel` remains what it is today for an agent that names a provider
operation and a bare id — the pre-model-asset path keeps working — and is
documented as that fallback. A per-model fact that matters belongs on a model
asset.

### 5.2 Defaults at dispatch

`operation.default` is applied where every operation's defaults are applied
— `JobManager` at dispatch — so the binding is not agent machinery: an
`agent:context` render, a direct `grid:run`, an MCP tool call and a federated
invocation all see the same model. An agent whose config also sets `model`
overrides the asset's id, because a caller always wins; `agent:create`
advises when that happens, since it is usually a mistake and occasionally a
fine-tune.

## 6. Discovery

`llm:models` enumerates the catalog instead of a table:

```json
{
  "providers": [{
    "op": "v/ops/langchain/anthropic",
    "provider": "anthropic",
    "ready": true,
    "default": "v/models/anthropic/claude-sonnet-5",
    "recommended": { "balanced": "v/models/anthropic/claude-sonnet-5", "...": "..." },
    "models": [{
      "op": "v/models/anthropic/claude-sonnet-5",
      "id": "claude-sonnet-5",            // = operation.default.model = the path's tail
      "name": "Claude Sonnet 5",
      "budget": { "bytes": 800000 },
      "options": { "toolCalling": true },
      "tags": ["balanced"]
    }]
  }]
}
```

Readiness stays caller-relative (the provider's key in the caller's secret
store or the venue environment; Ollama's live reachability) and is computed
per provider. The plain catalog reads work too: `covia:list v/models` is the
providers, `covia:list v/models/anthropic` the models, `covia:read
v/models/anthropic/claude-sonnet-5` the asset.

## 7. Seeding and publishing

The venue ships its known models as assets, generated from one JSON resource
per model (`adapters/langchain/models/<provider>/<id>.json`) and installed
at boot under `v/models/` — the same way skills and templates ship. The Java
table goes; its contents become those resources.

An operator publishes a model by storing the asset and declaring the name
(`asset:store`, then the catalog declaration — the same two steps the venue
performs at boot); a user by writing it under `/o/`. Ollama is the case where
the facts are discoverable rather than known: the capability probe that
`agent:create` runs today can write what it learns as `v/models/ollama/<id>`
assets, after which `toolCallingByModel` has nothing left to probe for those
models. That is a later step, not a prerequisite.

## 8. Compatibility

- `llmOperation: v/ops/langchain/<provider>` with a bare `model` keeps
  working unchanged; `byModel` keeps answering for it.
- Templates do not change. A template that leaves `llmOperation` unset gets
  the venue default, which becomes a model asset.
- `config.modelProfile` keeps its meaning as the agent's last layer.
- Remote venues without a `v/models` region are addressed by their provider
  operations, as now.

## 9. Implementation map

Each step ships on its own:

1. **Region and seeds.** `installAssetAt("v/models/", …)` with the
   validator relaxed for this region (§4.1); the `HOSTED_PROVIDERS` table
   becomes JSON resources; `ownedPath` already maps the region to
   `v/adapters/langchain/models/…`.
2. **One hop in the resolver.** `modelProfile(meta, modelId, config)` follows
   `model.provider` once; the LangChain edge reads through it.
3. **Discovery over the catalog.** `llm:models` enumerates `v/models/`;
   `default` and `recommended` move onto the provider facets as paths.
4. **The venue default** points at a model asset.

## 10. Open questions

- **Schema inheritance.** Copying the provider's `input` block into each
  asset keeps every asset self-describing; a `from` reference would remove the
  copies at the cost of a resolution step in tool listing. Copy first; revisit
  if the copies drift.
- **Retirement.** A `deprecated` flag on the facet (with a `replacedBy`
  path) would let `agent:create` advise and `llm:models` filter. Cheap, but
  not needed to land the catalog.
- **Price and limits.** A model's cost per token and rate limits are facts
  about the model and would sit on the facet beside `budget`; nothing reads
  them yet, so they wait for a consumer.
