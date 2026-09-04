# Agent Skills — Design

Named, discoverable bundles of instructions, context, tools, and further skill sources that an agent loads on demand.

**Status:** Current — implemented, July 2026. Live on both agent runtimes (llmagent + goaltree).

See [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) for the context entry grammar and the loads scope chain that skills build on, and [AGENT_TEMPLATES.md](./AGENT_TEMPLATES.md) for the template model that skills deliberately mirror.

---

## 1. Problem

An agent's knowledge and abilities are fixed at configuration time: the system prompt, `config.context` entries, and `config.tools` are all declared by the operator up front. There is no way for an agent to **discover and load a named capability bundle at run time**.

The consequences:

- **Context waste.** Material for occasional tasks (a PDF-processing procedure, a compliance checklist, a niche toolset) must either be pinned into every turn's context or left out entirely. Pinning burns budget on every turn for material used in one turn out of fifty.
- **Duplication.** Agents sharing a domain duplicate the same instruction blocks and tool lists across their configs. Updating a procedure means touching every agent (#79 catalogued the same problem for tool lists).
- **No bundled self-service.** `more_tools` can persist raw operation paths when the agent already knows them, but it supplies no procedure, reference context or further discovery sources.

Skills solve this with **progressive disclosure**: a compact index (one line per skill — name and description) is always in context; the full bundle — instructions, context, tools, and optionally another layer of discoverable skills — loads only when the agent asks for it, and stays loaded for the rest of the conversation.

---

## 2. Principles

1. **A skill is an asset.** Skill metadata is ordinary asset metadata — `name`, `description`, and the standard `content` descriptor. The **body is the asset's content**, resolved through the venue's universal content resolution (CAS blob, `content.inline`, `content.dlfs` pinned or live bindings, DLFS drive refs — `Engine.resolveContent`). There is no description-as-body fallback: the description is the index one-liner, never the documentation, and a contentless skill is simply a pure toolset. Skill-specific extras live under a **`skill` facet**, exactly as invocability lives under the `operation` facet. No new type, no schema registration: something is treated as a skill because it is *referenced as one* (declared in `config.skills`, a member of a declared skillset, or the target of `skill_load`).

2. **Built on the loads scope chain — no parallel machinery.** Loading a skill writes an ordinary, agent-managed, skill-flagged entry into the existing context loads tier (session for llmagent, frame for goaltree). Persistence across turns, advisory per-entry budgets, explicit ownership and unloading via `context_unload` all come from the existing machinery unchanged. Operator/caller-pinned skill entries use the same resolver but cannot be unloaded by the agent.

3. **Compatibility with established idioms.** LLMs must see familiar shapes — unfamiliar surface confuses models and wastes their pretraining:
   - Skill metadata is asset metadata; the `skill` facet mirrors the `operation` facet; body-as-content is the asset content model.
   - Markdown skills accept the **Anthropic SKILL.md format** (YAML frontmatter + markdown body) — existing SKILL.md files work as Covia skills unchanged (§3.3).
   - Directory entries may be **string references** — the same string idiom context entries and `agent:create config` already use.
   - `skill_load` follows the `context_load` / `context_unload` / `more_tools` harness-tool family: same schema style, same "takes effect on your next step" result notes, and unloading reuses `context_unload` rather than adding a parallel tool.
   - The venue op follows the `memory` adapter idiom: one command-dispatched tool, minimal tool-context footprint.
   - The index block and ownership-specific `[Pinned skill: …]` / `[Loaded skill: …]` labels follow the shared context rendering conventions.

4. **Progressive disclosure, appended once.** Loading resolves a skill once and appends its instructions and contributed context to the conversation. Source mutation alone does not rewrite prior model input; an explicit reload appends the newer version. Tool paths, contributed skill-source refs and exact operation/schema bindings are snapshotted on the loads entry between initial-context rebuilds. Compaction returns active loads to the shared initial-load path defined in [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) §5.6 and §7.1.

5. **Fail-visible.** Absent sources and skills are skipped quietly; resolution *errors* render a visible diagnosable line; malformed shapes (a non-vector `config.skills`/`config.skillsets`, a non-string tools or child-ref entry) throw. A persistent skill that vanishes keeps its ownership label and adds `unavailable: …` rather than silently disappearing — a missing skill changes behaviour too much to hide.

6. **Additive and opt-in.** An agent declaring neither `config.skills` nor `config.skillsets` behaves exactly as before skills existed. Skills are ordinary assets with no store of their own: authoring uses the existing `covia:write` / `asset:store`. The one skills op that writes, `skills:import` (§8), is a translator over that same lattice write — one SKILL.md in, one `<skillset>/<name>` out — so a body reaches the venue without passing through a model's context.

---

## 3. Skill Format

### 3.1 A skill is an asset with a `skill` facet

```json
{
  "name": "pdf-processing",
  "description": "Extract text and tables from PDF files",
  "content": {
    "contentType": "text/markdown",
    "sha256": "74f16013e2b7..."
  },
  "skill": {
    "tools": ["v/ops/file/read", "v/ops/schema/validate"],
    "skillsets": ["v/skills/pdf-specialists"],
    "context": [
      {"ref": "w/docs/pdf-rules", "label": "PDF handling rules"}
    ]
  }
}
```

Everything above the facet is standard asset metadata, unchanged:

| Field | Role |
|-------|------|
| `name` | Standard asset field. In a directory source the **key is canonical** and an inner `name` is ignored for identity. |
| `description` | Standard asset field, required for skills: one line for the index — what the skill does and when to load it. Never the documentation. |
| `content` | The standard content descriptor (`contentType`, `fileName`, `inline`, `ref`, `sha256`) describing where the body lives — exactly as for any other asset. Existing `dlfs` pointers remain a compatibility alias for `ref`. |

The **`skill` facet** carries the loadable extras, mirroring how `operation` carries invocability. All fields optional; an empty facet (or none at all) is valid — the skill is then pure instructions:

| Facet field | Type | Description |
|-------------|------|-------------|
| `tools` | array | Operation catalog paths (`v/ops/...`, `o/...`). Their exact definitions become available when the skill is loaded. Same form as `config.tools`. |
| `skills` | array | Individual skill refs made discoverable while this skill is loaded (a path to one skill, or an asset ref). |
| `skillsets` | array | Skillset refs — directories of skills — made discoverable while this skill is loaded. |
| `context` | array | Context entries loaded alongside the body — the standard entry grammar of AGENT_CONTEXT.md §6, unchanged. |
| `budget` | integer | Default accounting budget for `skill_load` (caller may override; clamped as usual). |

Skill bodies describe capabilities, not generated callable aliases. Keep stable
operation references in `skill.tools`; on every inference the runtime resolves
them to the current provider-facing name, description, and input schema. The body
should explain when to use an operation, when not to, its important argument and
result semantics, and how to handle failures, then direct the model to select the
matching operation from its live palette. It must not teach a provider alias or
suggest deriving an operation path from one. This keeps the same skill portable
across Anthropic, OpenAI-compatible, and other adapters.

### 3.2 The body is the asset's content

Body resolution is the venue's **universal content resolution** (`Engine.resolveContent`), decoded UTF-8:

- the CAS content blob;
- **`content.inline`** — small textual content declared directly in the metadata map. The bytes are part of the metadata, so the asset's own identity hash covers them — no separate verification exists or is needed;
- a `content.ref` binding to any resolvable asset, lattice path, file, DLFS path, or DID URL (pinned when `content.sha256` is declared — hash-verified, drift fails loudly; live when not). Existing `content.dlfs` bindings remain compatible;
- a DLFS drive ref, or a raw blob value at the path.

Nothing skill-specific here: `content.inline` is an asset-model feature, served by the same resolution for every content consumer (`asset:content`, context entries, skills). A metadata map that is not itself stored in the CAS (e.g. hand-written in a workspace) still serves its metadata-declared content.

**A skill with no content has no body — it is a pure toolset** (`skill.tools` plus the index one-liner), which is exactly a #79 toolset. When a contentless skill is persistent, its ownership-specific skill element shows the description line — a rendering fallback, not a body.

Validation (fail-visible): a missing `description` renders a visible index error line (`- <name> — INVALID: missing description`) and `skill_load` refuses it with a diagnosable error. A `skill.tools` or `skill.skills` entry that is not a string is an error at load; a tool path that fails to *resolve* is not — it is reported in the `skill_load` result under `unresolved` so the model can see and adapt (mirrors `config.tools` skip-with-warn behaviour). Contributed skill sources use the normal index behaviour for absent, invalid, and inaccessible sources.

### 3.3 Markdown skills (SKILL.md)

A skill stored with `asset:store contentText` whose content is an Anthropic-style SKILL.md:

```markdown
---
name: pdf-processing
description: Extract text and tables from PDF files
---

## PDF processing
...
```

works unchanged: when the asset metadata lacks `name`/`description`, the frontmatter supplies them, and the body is the markdown after it. Asset metadata, when present, wins — frontmatter is the compatibility fallback.

What the parser reads (`Skills.parseFrontmatter`):

| Frontmatter key | Becomes |
|-----------------|---------|
| `name`, `description` | The asset fields, when metadata lacks them. A description is normalised to **one line** whatever form it was written in — folded (`>`), literal (`\|`), wrapped over continuation lines, or quoted — because it is the index row |
| `tools`, `skills`, `skillsets` | The facet lists, when the facet leaves them empty (flow `[a, b]` or block `- a` sequences) — so a self-contained SKILL.md can be a router |
| `license`, `compatibility` | Carried onto the asset as plain fields by `skills:parse` / `skills:import` (provenance); ignored by the resolver |
| anything else (`allowed-tools`, `argument-hint`, `metadata`, …) | Ignored by the resolver; **reported** under `ignored` by `skills:parse` / `skills:import`. Claude Code tool names are not Covia operations — `skill.tools` is the Covia-native way |

This applies wherever the content comes from — a SKILL.md pasted into `content.inline` of a bare metadata map works identically.

**A content ref to a SKILL.md is itself a skill ref.** `file://<root>/<dir>/SKILL.md` and `dlfs/<drive>/<path>` are accepted wherever a skill ref is — a `config.skills` entry, a skillset member value (`w/skills/agent = "file://reference/skills/agent/SKILL.md"`), or a `skill_load {ref}`. The content provider pins its own read (`crud/read` on the `file://` resource), the frontmatter is the metadata, and identity is the hash of that synthesised metadata, so two addresses of one file dedup as one skill. The file is read when the index is initialised or the skill is explicitly loaded/reloaded; an edit does not rewrite an active conversation implicitly. `skills:import` lifts `name`/`description` into stored metadata so discovery does not depend on a live file read.

**Importing.** `skills:import {source}` (or `{text}`, for a SKILL.md already in hand) parses one SKILL.md and writes `<skillset>/<name>` (§8); `skills:parse` returns the same metadata without storing it. Both translate the file **as a single skill**: supporting files (`references/`, `scripts/`, `assets/`) are not walked or copied. A body that links to them relatively will point at nothing once imported; bind the ones an agent needs as `skill.context` entries with `file://` refs, or keep the whole skill live with `content: "ref"` so the agent's `file_read` can follow the same root. Nothing derives the name from anything but the frontmatter, except a SKILL.md that declares none, which takes its directory's name (the Agent Skills rule that the two match).

### 3.4 A skill can also be an operation

Facets compose. An asset may carry both `operation` and `skill`:

```json
{
  "name": "report-bug",
  "description": "File a well-formed bug report in the tracker",
  "content": {"contentType": "text/markdown", "sha256": "..."},
  "operation": {"adapter": "mcp:tools:call", "remoteToolName": "create_issue", "...": "..."},
  "skill": {}
}
```

When a skill asset has an `operation` facet, **the asset itself is also a tool** (in addition to any `skill.tools`) — the skill path is its own op ref. Its definition becomes available when the skill is loaded. This makes self-documenting tools first-class: an MCP-bridged operation (#80) with usage notes as its content can arrive with its own manual. The body explains; the operation executes; one asset.

(The converse composition also holds: `skill.tools` may reference any catalog op, including bridged ones.)

### 3.5 Where skills live

| Location | Example | Use case |
|----------|---------|----------|
| Asset store | `a/<hash>` | The canonical form: immutable, content-addressed, shareable across venues; body as content |
| Venue catalog | `v/skills/summarise` | Venue-installed standard skills, materialised at boot (like `v/ops`); the catalog entry is the asset metadata, content in CAS |
| User workspace | `w/skills/pdf-processing` | Personal skills, quick iteration (`covia:write`) — metadata maps with `content.inline` bodies, or string refs to shared assets |
| Host file root / DLFS drive | `file://reference/skills/agent/SKILL.md`, `dlfs/team/pdf/SKILL.md` | A SKILL.md used in place, live (§3.3) — or the source for `skills:import` |

No special namespace requirements — these are the standard resolvable addresses.

---

## 4. Sources and Discovery

### 4.1 `config.skills` and `config.skillsets`

Discovery has two **declared kinds**. A **skill** ref addresses one skill; a **skillset** ref addresses a directory of skills. The kind is declared, never inferred from what the ref resolves to:

```json
{
  "config": {
    "systemPrompt": "You are an AP invoice processor...",
    "skillsets": ["w/skills", "v/skills/root"],
    "skills": ["v/skills/ops-tools/models", "a/8cd17cbd..."]
  }
}
```

- **`config.skillsets`** — each ref resolves to a map whose keys are skill names. Each value is one of the established forms:
  - an **asset metadata map** (§3.1) — body via content resolution;
  - a **string reference** (`a/<hash>`, another path) — followed to the skill asset (one hop), the same string-ref idiom as templates and context entries.
  A value that is neither is a nested directory, not a broken skill: it is skipped in agent context and reported only on the operator surface (`skills:list`), because skills and directories are separate kinds.
- **`config.skills`** — each ref is exactly one skill: a stable human-readable path (`v/skills/ops-tools/models`), an asset ref (`a/<hash>`, `/a/<hash>`, bare hex), or a content ref to a SKILL.md (`file://<root>/<dir>/SKILL.md`, `dlfs/<drive>/<path>`, §3.3). This is the form role-specific templates use to curate a compact index. A skillset ref declared here is **not** walked as a directory — the kinds do not interchange.
- A ref that resolves to **null** is skipped quietly (absent — sources are maybe-style paths).
- A ref whose resolution **throws** — including a capability denial — renders one visible line: `[skills source <ref> — unavailable: <reason>]`, on the operator surface only.

**Reads are per source, and `list` degrades where `read` does not.** Listing is a survey: a source the caller cannot read renders that diagnostic line instead of failing the call, so a caller sees what they can see and a venue-configured default they lack access to does not break every call. Reading one named skill is a specific request, so the same denial is an error. Either way the pin is real and per source — nothing from a denied source reaches the caller.

A skill is an asset, and indexing one needs only `crud/read` over its path. An `asset/read` grant does **not** substitute for a path read: the public read-only scope grants `asset/read` unscoped (`with: ""`), which is safe for content addressing — you must already hold the hash to ask — but against a path would become a licence to read any user's workspace. For a content-addressed ref, either ability over that hash is enough.

**Assets and directories are never mixed at one level.** `v/skills` holds skillsets; `v/skills/root` holds the skills an agent starts with. This is what lets a level be walked without guessing what its entries are.

Resolution order is **skills before skillsets**, each in declaration order. Name collisions are first-wins, so an explicitly named skill always beats a same-named member of a skillset.

A non-empty declaration of either kind activates both halves of the feature: the initial index snapshot (§4.3) and the `skill_load` tool (§5). A malformed declaration (not a vector, non-string entry) throws at transition time — a configuration error to fix, not to mask.

**Diagnostics.** Merely absent refs are not warned about in a user's agent config: they are maybe-style paths resolved live, and an empty `w/skills` is the designed default. The following *are* reported:

- `agent:create` warns when a `config.skills` entry resolves to a directory rather than one skill — for example `w/skills` or `v/skills` under the wrong key.
- `agent:create` warns when a `config.skillsets` entry resolves to a directory of **directories** — the classic `v/skills` instead of `v/skills/root` mistake, which would silently yield an empty index. Resolution uses the caller's namespace, since `w/` paths are user-relative.
- `agent:create` reports source problems as one terse line per category, aggregating refs:

  ```
  skill missing: w/skills/typo, w/skills/foo
  skillset missing: w/imaginary
  skillset empty: w/skills
  no access capability: did:some-other-user/s/FOO
  ```

  Each line states a fact and nothing more. No guidance: that would cost context on every create for a reader that mostly does not need it. What these words mean, and what to do about them, lives in the **`agents`** skill — the one an agent creating an agent already has loaded — with `skills` there for a deeper dive into discovery and authoring, and `capabilities` for what an access capability is. The vocabulary is the link.

  Reported per kind, because a missing skill and a missing skillset are different mistakes, and collected rather than short-circuited so a caller sees everything at once.

  `skillset empty` is not a fault. `w/skills` ships in every standard template precisely so an agent has somewhere to author personal skills, and it is empty until one does — so the caller's own `w/skills` reads as empty rather than missing even when it does not exist yet. Any other absent path is missing.

  The denial check is the creator's own access. An ordinary user is null-scope and unrestricted over their own namespace, so it fires for a capability-scoped creator; a clean result is never a guarantee that the agent — which runs under its own `config.caps` — will be able to read it.

### 4.2 Hierarchical skill contribution

A loaded skill may contribute more of both kinds through `skill.skills` and `skill.skillsets`. This is the skill equivalent of `skill.tools`: the parent widens the agent's discovery surface while it remains loaded.

```json
{
  "name": "workspace",
  "description": "Read and write your durable lattice workspace",
  "skill": {
    "tools": ["v/ops/covia/read", "v/ops/covia/write"],
    "skillsets": ["v/skills/data"],
    "skills": ["w/team-skills/sql-review"]
  }
}
```

Effective sources are the configured refs followed by the immediate contributions of loaded skills, deduplicated first-wins **per kind**, so configured refs retain precedence. Each contributed ref has the same grammar and capability checks as a configured one; contributing a ref does not grant permission to read it.

Children are **discovered, not auto-loaded**. Loading `workspace` returns the refreshed effective index in its acknowledgement, makes the skills under `v/skills/data` and the single `sql-review` path addressable by `skill_load {name}` on the next tool-loop step, and names newly revealed entries explicitly. Loading one of those children may reveal a further layer. The resolver never walks an unloaded subtree, so cycles are inert and a broad hierarchy does not flood the prompt.

Contributed refs are denormalised onto the parent's loads entry, like tool refs. Editing a loaded parent's lists therefore needs unload/reload; the target directories and skill metadata remain live. Unloading the parent retracts its contributed sources. A child already loaded remains loaded independently and continues to contribute its own until it too is unloaded.

**The shipped library uses exactly this.** `v/skills/root` holds eight entry-point skills, each opening its family: `workspace`→`data`, `agents`→`agents`, `grid`→`grid`, `discovery`→`ops-tools`+`adapters`, `auth`→`auth`+`caps-permissions`, `venue`→`venue`+`admin`, `skills`→`building`, `covia`→`convex`. That keeps the always-on index at eight lines while every skill stays one load away.

An entry point is installed at **both** its family path and its `root/` mirror, from the same resource — so both addresses hold identical metadata, and content-identity dedup (§5.3) treats them as one skill. Mirroring is only safe this way: hand-copying metadata would produce two different hashes and two context entries. Grouping is decided by the owning adapter, so a skillset only ever lists skills whose adapter is actually active, and `v/adapters/<name>/skills` is itself a ready-made skillset for everything one adapter offers.

### 4.3 The skills index

One budget-tracked system message snapshotted when the rendered context is initialised:

```
[Skills]
Named skill packs available through the advertised skill-loading control. Loading
injects the skill's instructions into your context across turns; tool availability
and invocation authority are independent. Loading may reveal later tools or more
skills. A loaded skill's header gives its exact removal key.
- pdf-processing — Extract text and tables from PDF files
- code-review — Review code against the house style (loaded)
- broken-skill — INVALID: missing description
[skills source w/other-skills — unavailable: timeout]
```

- One line per skill: `- <name> — <description>`, with a `(loaded)` suffix when a skill-flagged loads entry for it is in effective context.
- Later catalog changes do not rewrite it. An explicit load returns a refreshed index and revealed names in its appended tool exchange. A changed declarative config causes the config-owned prefix, including this index, to be rebuilt on the session's next inference; reapplying equal config does nothing. `agent:reloadContext` forces a same-config refresh from current sources. Compaction/reset also build a replacement initial snapshot.
- Rendering delegates to the same function the `skills:list` op uses (§8), so the injected index and the op output can never drift.

---

## 5. Loading a Skill — `skill_load`

### 5.1 Tool schema

```
skill_load {
  name?:     string   — a skill name from the [Skills] index
  ref?:      string   — direct address of a skill (a/<hash>, v/skills/<x>, w/skills/<x>)
  budget?:   integer  — accounting budget (default: skill.budget, else 2000; clamp [256, 10000])
  volatile?: boolean  — watch the skill source and append only when its rendered value changes
}
```

A `name` that matches nothing fails with a message naming the skills that ARE available, so an agent can correct itself from the error rather than guessing again. Exactly one of `name` / `ref`. `name` is an index lookup across the agent's effective sources (the configured skills and skillsets, plus what loaded skills contribute). A `ref` is accepted when that exact ref is in the effective index or is already loaded. Any other direct ref requires an explicit `skill/load` capability on that resource before Covia resolves it as trusted instructions. The ordinary skill metadata/content read checks still apply; load authority is not read authority. No session/frame in scope → diagnosable error, same rule as `context_load`.

### 5.2 What loading does

1. Resolves the skill (§3) — failure returns a diagnosable `Error:` tool result naming the skill and reason.
2. Writes a **skill-flagged entry** into the innermost loads tier (session for llmagent, frame for goaltree).
3. Activates the skill's tools — `skill.tools`, plus the asset itself when it carries an `operation` facet (§3.4). Their exact schemas and routes are materialised onto the load and append as one trusted tool-addition event. Cached providers keep their fixed manifest and dispatch these names through `invoke_tool` until a provider edge supports native additions. A load already present when a context is materialised contributes directly to that initial vector. Explicitly supplied `tools` metadata remains authoritative.
4. Adds the skill's contributed refs to the effective discovery sources and reports what that gained: `revealed` names the skills that were not discoverable before, alongside the refreshed `skillIndex`. Named children can be loaded from the next tool-loop iteration and appear in the refreshed discovery index.

   `revealed` exists because the index alone was not enough: the reader already has the turn-start `[Skills]` block and the refreshed index, but must notice they differ. A live agent observably did not — it reported "no new skills" while listing the revealed ones. Naming them removes the inference.
5. By default, appends the body as a loaded-skill system event and the skill's `skill.context` as one `loaded_context` result under the same key. The next inference in the same tool loop therefore sees both without regenerating either. With `volatile: true`, the persistent declaration is instead watched before each inference: its first value, and only later changed values, append through the same observation lifecycle as other volatile loads.
6. Returns a compact acknowledgement. The body and contributed data occur only in the appended events, not again in this result:

```json
{
  "loaded": true,
  "skill": "pdf-processing",
  "path": "w/skills/pdf-processing",
  "tools": ["file_read", "schema_validate"],
  "skillIndex": "- pdf-table-extraction — Extract tables from PDFs\n...",
  "unresolved": ["v/ops/gone/op"],
  "note": "Skill instructions were appended to context. Its path is the exact unload key if you later need to remove it; ordinary tool results need no cleanup. Tools and contributed skills are active from your next step."
}
```

### 5.3 The loads entry

Key = the skill's canonical path (what the index shows). Value:

```json
{"skill": true, "budget": 2000, "ts": 1789000000000, "label": "pdf-processing", "appended": true,
 "tools": ["v/ops/file/read", "v/ops/schema/validate"],
 "skills": ["v/skills/pdf-specialists"], "skillsets": []}
```

- The **body is not duplicated on the entry** — its rendered instruction event is already in conversation. `appended: true` tells later assembly not to resolve or re-inject it before the next compaction.
- The **tool paths and child skill-source refs are snapshotted** onto the entry, including empty vectors and the skill's own path as a tool when it is an operation. Editing the body, context or these lists does not affect the active context until an explicit reload or compaction; reload appends the new material, while compaction rebuilds initial context under the canonical rules linked above.
- A `volatile: true` entry omits `appended`: its body and bundled context are re-resolved and compared as one canonical provider-visible value. Equality adds no prompt bytes; a change appends the new exact messages while retaining earlier versions as history. Tools and child-source refs remain the load-time snapshots above. The durable observation shape and compaction rules are defined once in [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) §1.1 and §5.5.
- Because the entry is a plain loads-map entry, everything in the scope chain applies unchanged: explicit ownership, advisory budget accounting, and explicit unloading of agent-managed entries.
- **Skills dedup by content identity, not path.** A skill's identity is its resolved metadata's value hash — the asset identity Convex already computes and memoises on every cell. Identity is compared when an explicit load/reload resolves the candidate. Loading the same skill from a second address (a directory ref vs the asset hash, mirrored directories) is a no-op naming the existing entry; reloading under the same path appends the newer body and updates its budget.
- **The agent runtimes carry no skill-specific tool state.** Tool contribution remains the generic rule *"a loads entry may declare `tools`, `skills` and `skillsets`"*: a definition and route append when its load first appears, and retract when that load is removed. A plain session load — a note pinned at mint with `{text, tools, skillsets}` — widens a session's palette and discovery exactly as a loaded skill does. Skills are the first producer of such entries; future additions (memory packs, op bundles) ride the same mechanism. The runtimes' only skill surface is the `skill_load` handler, which delegates resolution to the skills subsystem.

### 5.4 Unloading

`context_unload {path: "w/skills/pdf-processing"}` — the existing tool, using the exact key shown in the loaded-skill header. Removing an agent-loaded entry retracts tool bindings introduced by that load and its contributed discovery sources from the next inference. Its earlier body and context remain honest stale history until explicit compaction/reset; the unload result is the newer state event. Already-loaded children remain independent. A pinned skill has no unload key and is rejected. There is deliberately no `skill_unload`: one unload idiom, no near-duplicate tools to confuse a model.

---

## 6. Load-Event Rendering

When a skill is loaded or explicitly reloaded:

1. Resolve the skill from its path.
2. Append one system message followed by the body **verbatim**: `[Loaded skill: <name> — unload key: <path>]` for an agent-loaded skill, or `[Pinned skill: <name> — <source>]` for operator/caller-owned context. A contentless toolset shows its description one-liner instead.
3. Resolve `skill.context` through the standard loader and aggregate its data under the same ownership class. For an agent-loaded skill, `loaded_context[<path>]` contains one entry or a vector of entries, so the body and all contributed data share one exact unload key. The skill body is instruction; the context it brings along is data.
4. Materialise exact bindings for genuinely new tools (deduplicated against the fixed palette and earlier loads).
5. Contribute its immediate `skill.skills` refs to the next skills index and named lookup scope.

A skill that fails to resolve appends a visible ownership-specific label with `unavailable: <reason>`. Advisory aggregate budget pressure never makes a persistent skill silently disappear. Between initial-context rebuilds, later inferences reuse the appended cells and an explicit reload is the only operation that refreshes the skill. Compaction follows the shared rebuild rule linked in §5.3. A `volatile` skill follows the watched observation rule linked there.

---

## 7. Runtime Integration

| | llmagent | goaltree |
|---|---|---|
| Frame use | one durable root frame | root plus active subgoal frames |
| Load persistence | root-frame lifetime | frame lifetime; **inherited copy-on-push by subgoals** |
| Late tool visibility | same transition — from the next tool-loop iteration | same transition — from the next iteration |
| Tool offered when | any skill source declared (automatic) | any skill source declared (automatic), or bare `"skill_load"` in `config.tools` (registry opt-in) |
| Unload scope | session, agent-managed entries only | frame, agent-managed entries only — removing an inherited copy leaves the parent's copy untouched |

A subgoal therefore starts with its parent's loaded skills and may load/unload its own without affecting the parent — the same lexical-scoping semantics as every other load. The durable loads shape and ownership rules are defined only in [AGENT_CONTEXT.md](./AGENT_CONTEXT.md) §1.1 and §7.

---

## 8. The Skills Operations — Discovery for Everyone

Four ops, one question each, because a single command-dispatched union could only say which arguments belong to which in prose:

| Op | Tool | Input | Output |
|----|------|-------|--------|
| `v/ops/skills/list` | `skills_list` | `skillset?` — one directory of skills; omitted → the venue's configured entry skillsets | A map from each skill's resolved **path** to `{name, description, id}` |
| `v/ops/skills/read` | `skills_read` | `skill` — one resolved path, asset ref, or content ref | `{name, description, path, id, tools, body?, skills?, skillsets?, context?}` |
| `v/ops/skills/parse` | `skills_parse` | exactly one of `source` (one content ref to a SKILL.md) or `text` (the SKILL.md itself); `content?` = `inline` (default) \| `ref` | `{metadata, name, description, ignored?}` — `metadata` is the map `covia:write` / `asset:store` accept as-is. Nothing stored |
| `v/ops/skills/import` | `skills_import` | exactly one of `source` (one content ref to a SKILL.md) or `text` (the SKILL.md itself); `skillset?` (default `w/skills`); `content?` = `inline` (default) | `ref` (`ref` needs `source`) | `{path, name, description, source?, content, existed, ignored?}` — written to `<skillset>/<name>` |\| `ref` | `{path, name, description, source, content, existed, ignored?}` — written to `<skillset>/<name>` |

**Single arity.** One skillset per list, one skill per read, one SKILL.md per parse or import. That removes partial failure entirely — there is no "three of five worked" to represent — and the error says what to pass instead. The one plural case is the default: omit `skillset` and the venue's configured entry skillsets are listed, because "where should I start" is inherently a set.

**Import names one file, never a directory.** A library is imported by naming each SKILL.md. There is deliberately no tree walk: what lands in a skillset is exactly what the caller asked for, the read pin is on one precise resource, and "which of these forty were skills" never has to be reported. The target is a **skillset** rather than a path, so the entry's key is always the frontmatter name — the key is canonical for a skillset member (§3.1), and letting the two disagree would produce an index line that `skill_load {name}` cannot load. Read and parse complete before the write, so a bad source writes nothing; the write is `covia:write`'s own seam, so the namespace rules and the `crud/write` pin are the same ones. Re-importing overwrites (`existed: true`).

**Inline or ref.** `content: "inline"` copies the body into the stored metadata — self-contained, and the facet carries the frontmatter's `tools`/`skills`/`skillsets` since the frontmatter is gone from an inline body. `content: "ref"` binds `content.ref` to the source — the body stays live in the file, the facet is left to the live frontmatter, and only `name`/`description` are snapshots (re-import to refresh the index line). Neither pins bytes; add `content.sha256` with `covia:write` to freeze a ref.

**Why parse exists beside import.** `parse` is the translator alone: for a SKILL.md the caller already holds as text, for review before storing, or to feed `asset:store` for an immutable `a/<hash>`. `import` is the same translation plus the write, and its reason to exist is that the body never passes through a model's context — the alternative, `file_read` then `covia_write`, round-trips every byte through the tool call.

**Listing pairs path with metadata.** A name alone does not say where a skill lives, and where it lives is what you need in order to read it, to see which skillset won a name collision, or to fix a source. Only actual skills appear: a skillset may sit beside nested directories or unrelated data, and a listing answers "what can I load here".

**`read` takes a path or asset ref, never a name.** A name is only meaningful against a declared set of skillsets, resolved first-wins at the moment of the call — there is no index to look one up in. Callers list a skillset to obtain the path; an agent's own by-name lookup is `skill_load`, which has its sources in scope.

**List degrades, read does not.** A skillset that does not resolve, or that the caller may not read, contributes nothing to a listing rather than failing it; the read pin still applies per skillset, so nothing from a denied one appears. A read is a specific request, so the same denial is an error.

Capability pins per resource read: a path needs `crud/read`; a content-addressed ref accepts either `asset/read` or `crud/read` over that hash. Both sit inside the anonymous read-only scope, so venue skills are publicly discoverable.

There is **no skill store**. Skills are ordinary assets: create and update them with the lattice write and asset store operations (or `import`, which is the lattice write with a parser in front), and remove them with the lattice delete operation.

**There is no operation that enumerates skillsets, deliberately.** A skillset is not a registered thing — it is any lattice path that happens to contain skill assets. Listing the skillsets this venue ships is therefore an ordinary lattice read (`v/ops/covia/list` on `v/skills`, whose keys are the skillsets), and a skillset anywhere else is found the same way you would find any other path. Adding a skills-specific enumeration would imply a registry that does not exist.

The defaults are venue-configurable (`adapters.skills.defaultSkillsets` / `defaultSkills`, see [CONFIG.md](CONFIG.md#adapter-configuration)) and published at `v/info/adapters/skills`, so a venue curating its own library answers discovery from it and clients read the entry point rather than assuming `v/skills/root`. They govern these operations only: agents declare their own sources, and an agent declaring none has skills off deliberately.

**Rendering is not an operation.** The initial `[Skills]` snapshot is built by `ContextAssembler` calling `Skills.renderIndex` directly — it never goes through an op. There is deliberately no callable render: it would exist only to pin an index into `config.context` as an assemble-op, which nothing ships and which a first-class source declaration does better, since that also activates `skill_load`. Add one if a real consumer appears.

---

## 9. Authoring Skills

```bash
# The canonical form: an immutable skill asset — standard metadata + skill facet,
# body as content
asset_store \
  metadata={"name": "pdf-processing",
            "description": "Extract text and tables from PDF files",
            "skill": {"tools": ["v/ops/file/read"]}} \
  contentText="## PDF processing\n1. Use file_read to load the PDF...\n..."

# An existing Anthropic SKILL.md, stored as-is (frontmatter supplies name/description)
asset_store metadata={} contentText="$(cat SKILL.md)"

# A SKILL.md on a venue file root: import it (→ w/skills/agent), or use it in place
skills_import source=file://reference/skills/agent/SKILL.md
skills_import source=file://reference/skills/agent/SKILL.md content=ref   # body stays live
covia_write path=w/skills/agent value="file://reference/skills/agent/SKILL.md"   # no copy at all

# Translate without storing — review, or store as an immutable asset
skills_parse source=dlfs/team/pdf/SKILL.md
skills_parse text="$(cat SKILL.md)"

# Quick workspace iteration: body inline in the standard content descriptor
covia_write path=w/skills/scratch-notes value={
  "description": "House conventions for scratch analysis",
  "content": {"inline": "Always write intermediate results to w/analysis/..."}}

# Mix inline skills and shared assets in one directory
covia_write path=w/skills/pdf-processing value="a/<hash-from-store>"

# Point an agent at sources
agent_update agentId=Carol config={"skillsets": ["w/skills", "v/skills/root"]}
```

Venue-installed skills ship as classpath resources registered by an adapter via `installSkill(name, resource)` and materialise at `v/skills/<name>` on boot (the `v/agents/templates` mechanism).

**Module adapters ship their own skills the same way**: `readResource` resolves against the adapter's own classloader, so a venue module jar (see venue/CLAUDE.md §Venue modules) carries its skill JSONs alongside its op definitions and calls `installSkill` in `installAssets` — the skill appears in `v/skills` exactly when the module is loaded, and the static library list never has to know. covia-sql's `sql` skill is the reference example.

**The venue skill library** ships this way, one resource per skill under `venue/src/main/resources/skills/`, and **each skill is owned by the adapter it teaches**: an adapter calls `installSkill("<name>", "/skills/<name>.json")` in its `installAssets()` (`GridAdapter` ships `grid`, `HITLAdapter` ships `hitl`, `FileAdapter` ships `files`, …), so the skill is published exactly when the adapter is active and retracted when it is disabled or unloaded — the same rule as its operations. `SkillsAdapter.LIBRARY` holds only the platform skills that are about Covia and the venue as a whole: `covia`, `venue`, `discovery`, `provenance`, `lattice`, `skills`, and the `building` family it opens — `skill-authoring` (shape, body, storage) and `skill-import` (SKILL.md files, in place or imported). The root `skills` skill teaches discovery and loading only; format-specific material lives one load deeper, so the always-on index and the root body stay small and an agent pays for the SKILL.md details only when it has such files to bring in. Bodies live in `content.inline`; each declares the operations it teaches. General templates (`minimal`, `skilled`, `goaltree`, `full`) index the complete `v/skills` directory. Specialist templates use stable single-skill paths such as `v/skills/models` to keep their discovery indexes role-focused. Every template keeps `w/skills` first, so user-authored skills remain visible and shadow same-named venue skills. `SkillsLibraryTest` drift-guards materialisation, bodies, declared tool resolution, compact rendering, and the curated template sources.

---

## 10. Relationship to Other Features

| Feature | Relationship |
|---------|--------------|
| **Assets** | A skill *is* an asset: standard metadata, body as content via the universal content resolution (CAS, `content.inline`, `content.dlfs` pinned/live), extras under a `skill` facet exactly as invocability sits under `operation`. |
| **Operations** | Facets compose: an asset with both `operation` and `skill` is a self-documenting tool — loading it injects its manual and offers the op itself (§3.4). |
| **Context loads / scope chain** | Skills are loads entries with a `skill` flag — the scope chain, budgets, explicit ownership, and `context_unload` are reused, not duplicated. |
| **`config.context`** | Pinned baseline knowledge, always loaded. Skills are the on-demand complement, declared with `config.skills` / `config.skillsets`. |
| **Agent templates** | Same philosophy (config is data), same string-reference idiom. A template declares what an agent *is*; a skill declares what an agent *can pick up*. Templates may ship `config.skills` / `config.skillsets`. |
| **`more_tools`** | The same shared load lifecycle with a tool-only projection. An operation already declared by config, an active load or an effective advertised skill may be selected; any other ref needs explicit `tool/load`. It persists raw op paths and exact bindings; skills add instructions, context and further discovery sources. |
| **Toolsets (#79)** | A skill facet with only `tools` + a description *is* a toolset — this design subsumes the #79 sketch. |
| **Hierarchies** | `skill.skills` contributes more source refs while loaded, applying the same progressive-disclosure mechanism recursively without recursively loading anything. |
| **MCP bridging (#80)** | Bridged MCP tools are ordinary catalog ops — referenced from `skill.tools`, or made skills themselves via facet composition (§3.4). |
| **A2A agent cards** | A2A `AgentSkill` entries describe what an *agent* offers outward; these skills describe what an agent can *load* inward. Unrelated surfaces; the A2A card could later advertise loaded skills. |
| **UCAN / caps** | Skill reads pin `crud/read` / `asset/read`. An unadvertised direct skill or tool additionally needs explicit `skill/load` / `tool/load`; null (ordinarily unrestricted) caps do not opt into those trust-surface expansions. A loaded tool is still checked separately for `invoke`. |

---

## 11. Limitations and Notes

- **Denormalised tool and child refs** (§5.3): edits to those lists need unload/reload. Stable tool definitions are materialised at load time; skill bodies and context follow their declared stable/volatile placement.
- **Budget is an advisory rendering/accounting weight**: string bodies render verbatim regardless (the existing `renderValue` contract); the budget bounds structured exploration but never triggers silent eviction.
- **`agent:context` inspection** uses the same effective scope, load renderer, contributed tools, ordering, and capability context as a live first inference.
- **Skillsets** read the whole map per turn to build the index — fine at expected scale; revisit with a keys-only listing if venues grow hundreds of skills. Grouping keeps the always-on index small, so a wide library costs an index line only once a family is opened.
- **Skill loading is an instruction-trust decision**: a resolved skill body enters the prompt verbatim as a system message whether it was pinned or selected with `skill_load`. Trust comes from the operator-supplied skill catalog or an explicit `skill/load` grant, never from an agent/caller `trusted` flag. Point sources only at libraries you trust. Context data bundled by the skill remains a tool result, and child reads/tool invocations remain capability-checked as usual.

---

## 12. Implementation Map

| Piece | Where |
|-------|-------|
| Resolver (one for every surface) | `covia.adapter.agent.Skills` |
| `skills` ops (`list`/`read`/`parse`/`import`) | `covia.adapter.SkillsAdapter` + `adapters/skills/{list,read,parse,import}.json` |
| SKILL.md frontmatter and translation to metadata | `Skills.parseFrontmatter`, `Skills.parseSkillText`; content refs via `Skills.resolveContentSkill` |
| `content.inline` (asset-model inline content) | `Engine.resolveContent` |
| Index injection + skill rendering + loads-tools rule | `ContextAssembler` (`skillsIndex`), `Loads` (`elements`), `ToolPalette` (`loadPalette`) |
| `skill_load` glue | `LLMAgentAdapter.handleSkillLoad` (session tier), `GoalTreeAdapter.runFrame` (frame tier) |
| Venue skill install | `AAdapter.installSkill` → `v/skills/<name>` |
