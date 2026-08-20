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
- **No self-service.** An agent that discovers mid-task it needs the "invoice enrichment" procedure has no way to find or acquire it. `more_tools` (goaltree) covers the tool half only, and only if the agent already knows the op paths.

Skills solve this with **progressive disclosure**: a compact index (one line per skill — name and description) is always in context; the full bundle — instructions, context, tools, and optionally another layer of discoverable skills — loads only when the agent asks for it, and stays loaded for the rest of the conversation.

---

## 2. Principles

1. **A skill is an asset.** Skill metadata is ordinary asset metadata — `name`, `description`, and the standard `content` descriptor. The **body is the asset's content**, resolved through the venue's universal content resolution (CAS blob, `content.inline`, `content.dlfs` pinned or live bindings, DLFS drive refs — `Engine.resolveContent`). There is no description-as-body fallback: the description is the index one-liner, never the documentation, and a contentless skill is simply a pure toolset. Skill-specific extras live under a **`skill` facet**, exactly as invocability lives under the `operation` facet. No new type, no schema registration: something is treated as a skill because it is *referenced as one* (declared in `config.skills`, a member of a declared skillset, or the target of `skill_load`).

2. **Built on the loads scope chain — no parallel machinery.** Loading a skill writes an ordinary, skill-flagged entry into the existing context loads tier (session for llmagent, frame for goaltree). Persistence across turns, advisory per-entry budgets, the Context Map inventory, tombstone masking, and unloading via `context_unload` all come from the existing machinery unchanged.

3. **Compatibility with established idioms.** LLMs must see familiar shapes — unfamiliar surface confuses models and wastes their pretraining:
   - Skill metadata is asset metadata; the `skill` facet mirrors the `operation` facet; body-as-content is the asset content model.
   - Markdown skills accept the **Anthropic SKILL.md format** (YAML frontmatter + markdown body) — existing SKILL.md files work as Covia skills unchanged (§3.3).
   - Directory entries may be **string references** — the same string idiom context entries and `agent:create config` already use.
   - `skill_load` follows the `context_load` / `context_unload` / `more_tools` harness-tool family: same schema style, same "takes effect on your next step" result notes, and unloading reuses `context_unload` rather than adding a parallel tool.
   - The venue op follows the `memory` adapter idiom: one command-dispatched tool, minimal tool-context footprint.
   - Index block and `[Skill: <name>]` labels follow the `[Context: …]` rendering conventions.

4. **Progressive disclosure, resolved fresh.** The index and every loaded body are re-resolved each turn from their sources — skills stay live, the same freshness contract as every other context entry. Nothing is snapshotted at load except the skill's tool paths and contributed skill-source refs (§5.3).

5. **Fail-visible.** Absent sources and skills are skipped quietly; resolution *errors* render a visible diagnosable line; malformed shapes (a non-vector `config.skills`/`config.skillsets`, a non-string tools or child-ref entry) throw. A loaded skill that vanishes renders a visible `[Skill: <name> — unavailable: …]` element rather than silently disappearing — a missing skill changes behaviour too much to hide.

6. **Additive and opt-in.** An agent declaring neither `config.skills` nor `config.skillsets` behaves exactly as before skills existed. Skills are read-only surface: authoring uses the existing `covia:write` / `asset:store`; there is no skill-write op.

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
| `tools` | array | Operation catalog paths (`v/ops/...`, `o/...`) added to the agent's tool palette while the skill is loaded. Same form as `config.tools`. |
| `skills` | array | Individual skill refs made discoverable while this skill is loaded (a path to one skill, or an asset ref). |
| `skillsets` | array | Skillset refs — directories of skills — made discoverable while this skill is loaded. |
| `context` | array | Context entries loaded alongside the body — the standard entry grammar of AGENT_CONTEXT.md §3, unchanged. |
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

**A skill with no content has no body — it is a pure toolset** (`skill.tools` plus the index one-liner), which is exactly a #79 toolset. When a contentless skill is loaded, its `[Skill: <name>]` element shows the description line — a rendering fallback, not a body.

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

works unchanged: when the asset metadata lacks `name`/`description`, the frontmatter supplies them, and the body is the markdown after it. Only `name` and `description` are read from frontmatter; other keys are ignored. Asset metadata, when present, wins — frontmatter is the compatibility fallback.

This applies wherever the content comes from — a SKILL.md pasted into `content.inline` of a bare metadata map works identically.

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

When a skill whose asset has an `operation` facet is loaded, **the asset itself joins the tool palette** (in addition to any `skill.tools`) — the skill path is its own op ref. This makes self-documenting tools first-class: an MCP-bridged operation (#80) with usage notes as its content becomes a loadable skill that arrives with its own manual. The body explains; the operation executes; one asset.

(The converse composition also holds: `skill.tools` may reference any catalog op, including bridged ones.)

### 3.5 Where skills live

| Location | Example | Use case |
|----------|---------|----------|
| Asset store | `a/<hash>` | The canonical form: immutable, content-addressed, shareable across venues; body as content |
| Venue catalog | `v/skills/summarise` | Venue-installed standard skills, materialised at boot (like `v/ops`); the catalog entry is the asset metadata, content in CAS |
| User workspace | `w/skills/pdf-processing` | Personal skills, quick iteration (`covia:write`) — metadata maps with `content.inline` bodies, or string refs to shared assets |

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
- **`config.skills`** — each ref is exactly one skill: a stable human-readable path (`v/skills/ops-tools/models`), or an asset ref (`a/<hash>`, `/a/<hash>`, bare hex). This is the form role-specific templates use to curate a compact index. A skillset ref declared here is **not** walked as a directory — the kinds do not interchange.
- A ref that resolves to **null** is skipped quietly (absent — sources are maybe-style paths).
- A ref whose resolution **throws** — including a capability denial — renders one visible line: `[skills source <ref> — unavailable: <reason>]`, on the operator surface only.

**Reads are per source, and `list` degrades where `read` does not.** Listing is a survey: a source the caller cannot read renders that diagnostic line instead of failing the call, so a caller sees what they can see and a venue-configured default they lack access to does not break every call. Reading one named skill is a specific request, so the same denial is an error. Either way the pin is real and per source — nothing from a denied source reaches the caller.

A skill is an asset, and indexing one needs only `crud/read` over its path. An `asset/read` grant does **not** substitute for a path read: the public read-only scope grants `asset/read` unscoped (`with: ""`), which is safe for content addressing — you must already hold the hash to ask — but against a path would become a licence to read any user's workspace. For a content-addressed ref, either ability over that hash is enough.

**Assets and directories are never mixed at one level.** `v/skills` holds skillsets; `v/skills/root` holds the skills an agent starts with. This is what lets a level be walked without guessing what its entries are.

Resolution order is **skills before skillsets**, each in declaration order. Name collisions are first-wins, so an explicitly named skill always beats a same-named member of a skillset.

A non-empty declaration of either kind activates both halves of the feature: the per-turn index injection (§4.3) and the `skill_load` tool (§5). A malformed declaration (not a vector, non-string entry) throws at transition time — a configuration error to fix, not to mask.

**Diagnostics.** Merely absent refs are not warned about in a user's agent config: they are maybe-style paths resolved live, and an empty `w/skills` is the designed default. Two things *are* reported:

- `agent:create` warns when a `config.skillsets` entry resolves to a directory of **directories** — the classic `v/skills` instead of `v/skills/root` mistake, which would silently yield an empty index. Resolution uses the caller's namespace, since `w/` paths are user-relative.
- `agent:create` also warns when a declared source is **denied** to the creator. Absence stays undiagnosed, but a denial renders nothing in the agent's index and nothing in any log, so at runtime it is indistinguishable from an empty source. The check is the creator's own access: an ordinary user is null-scope and unrestricted over their own namespace, so it fires for a capability-scoped creator, and a clean result is never a guarantee that the agent — which runs under its own `config.caps` — will be able to read it.
- At boot, after catalog materialisation, the venue validates its **own** library and logs a warning per problem: a skill installed at skillset level, a skillset holding a nested directory, a child ref that does not resolve, or a child ref declared under the wrong kind. Unlike a user's config, everything here was installed by this venue, so an unresolvable ref is a packaging bug worth surfacing at boot rather than at an agent's first turn.

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

Children are **discovered, not auto-loaded**. Loading `workspace` returns the refreshed effective index immediately, makes the skills under `v/skills/data` and the single `sql-review` path addressable by `skill_load {name}` on the next tool-loop step, and includes them in the next per-turn index. Loading one of those children may reveal a further layer. The resolver never walks an unloaded subtree, so cycles are inert and a broad hierarchy does not flood the prompt.

Contributed refs are denormalised onto the parent's loads entry, like tool refs. Editing a loaded parent's lists therefore needs unload/reload; the target directories and skill metadata remain live. Unloading the parent retracts its contributed sources. A child already loaded remains loaded independently and continues to contribute its own until it too is unloaded.

**The shipped library uses exactly this.** `v/skills/root` holds eight entry-point skills, each opening its family: `workspace`→`data`, `agents`→`agents`, `grid`→`grid`, `discovery`→`ops-tools`+`adapters`, `auth`→`auth`+`caps-permissions`, `venue`→`venue`+`admin`, `skills`→`building`, `covia`→`convex`. That keeps the always-on index at eight lines while every skill stays one load away.

An entry point is installed at **both** its family path and its `root/` mirror, from the same resource — so both addresses hold identical metadata, and content-identity dedup (§5.3) treats them as one skill. Mirroring is only safe this way: hand-copying metadata would produce two different hashes and two context entries. Grouping is decided by the owning adapter, so a skillset only ever lists skills whose adapter is actually active, and `v/adapters/<name>/skills` is itself a ready-made skillset for everything one adapter offers.

### 4.3 The skills index

One budget-tracked system message injected each turn, immediately after the `config.context` entries and before the loads:

```
[Skills]
Named skill packs you can load with skill_load({name: "..."}). Loading injects the
skill's instructions into your context (persists across turns; unload with
context_unload), adds its tools to your palette, and may reveal more skills.
- pdf-processing — Extract text and tables from PDF files
- code-review — Review code against the house style (loaded)
- broken-skill — INVALID: missing description
[skills source w/other-skills — unavailable: timeout]
```

- One line per skill: `- <name> — <description>`, with a `(loaded)` suffix when a skill-flagged loads entry for it is in effective context.
- Resolved fresh each turn — one `resolvePath` per source (plus one per string-ref directory entry); no caching.
- Rendering delegates to the same function the `skills:list` op uses (§8), so the injected index and the op output can never drift.

---

## 5. Loading a Skill — `skill_load`

### 5.1 Tool schema

```
skill_load {
  name?:   string   — a skill name from the [Skills] index
  ref?:    string   — direct address of a skill (a/<hash>, v/skills/<x>, w/skills/<x>)
  budget?: integer  — accounting budget (default: skill.budget, else 2000; clamp [256, 10000])
}
```

A `name` that matches nothing fails with a message naming the skills that ARE available, so an agent can correct itself from the error rather than guessing again. Exactly one of `name` / `ref`. `name` is an index lookup across the agent's effective sources (the configured skills and skillsets, plus what loaded skills contribute); `ref` resolves directly — an asset ref, or a path whose value is a single skill in any §4.1 form — and is how an agent loads a skill outside its discovered sources, e.g. one it was just told about. No session/frame in scope → diagnosable error, same rule as `context_load`.

### 5.2 What loading does

1. Resolves the skill (§3) — failure returns a diagnosable `Error:` tool result naming the skill and reason.
2. Writes a **skill-flagged entry** into the innermost loads tier (session for llmagent, frame for goaltree).
3. Resolves the skill's tools — `skill.tools`, plus the asset itself when it carries an `operation` facet (§3.4) — into LLM tool definitions and activates them **within the same transition**, available from the next tool-loop iteration, exactly like `more_tools`.
4. Adds the skill's `skill.skills` refs to the effective discovery sources and returns the refreshed effective index as `skillIndex`, so named children are visible immediately, can be loaded from the next tool-loop iteration, and appear in the next per-turn index.
5. Loads the skill's `skill.context` entries into the same tier alongside the body.
6. Returns the body immediately, so the instructions are usable in the same turn without waiting for the next context build:

```json
{
  "loaded": true,
  "skill": "pdf-processing",
  "path": "w/skills/pdf-processing",
  "tools": ["file_read", "schema_validate"],
  "skills": ["v/skills/pdf-specialists"],
  "skillIndex": "- pdf-table-extraction — Extract tables from PDFs\n...",
  "unresolved": ["v/ops/gone/op"],
  "body": "## PDF processing\n...",
  "note": "Skill instructions stay in context each turn (unload with context_unload). Tools are available from your next step."
}
```

`body` is the display text: the skill's content, or the description one-liner for a contentless (toolset) skill.

### 5.3 The loads entry

Key = the skill's canonical path (what the index shows). Value:

```json
{"skill": true, "budget": 2000, "ts": 1789000000000, "label": "pdf-processing",
 "tools": ["v/ops/file/read", "v/ops/schema/validate"],
 "skills": ["v/skills/pdf-specialists"]}
```

- The **body is not denormalised** — it re-resolves from the path each turn through the §3.2 chain, staying live like every other load.
- The **tool paths and child skill-source refs are denormalised** onto the entry (including the skill's own path as a tool when it is an operation); their targets still resolve fresh each turn. Trade-off: editing either list after load requires unload/reload to take effect; body and context edits apply on the next turn. This keeps the per-turn cost one resolution per skill and the entry a plain map.
- Because the entry is a plain loads-map entry, everything in the scope chain applies unchanged: tombstone masking, advisory budget accounting, Context Map listing (with a `(skill)` marker), and explicit unloading.
- **Skills dedup by content identity, not path.** A skill's identity is its resolved metadata's value hash — the asset identity Convex already computes and memoises on every cell (and which pins the body: `content.sha256`/`content.inline` live inside the metadata). Nothing is persisted for this: identities are compared **live** (entries re-resolve, consistent with the body/tools liveness contract) and accumulated in a transient set per pass. Loading the same skill from a second address (a directory ref vs the asset hash, mirrored directories) is a no-op naming the existing entry; rendering skips a second entry with an already-seen identity (e.g. across tiers); and the index's `(loaded)` marker matches by identity, so a skill loaded via `a/<hash>` still marks its directory line. Reloading under the *same* path overwrites (budget updates).
- **The agent runtimes carry no skill knowledge.** Rendering dispatches on the entry inside the context assembly (`ContextBuilder`), and tool contribution is the generic rule *"a loads entry may declare `tools`"* — kind-agnostic, applied per loop iteration so the palette always mirrors effective loads (load activates mid-transition; unload retracts). Skills are the first producer of such entries; future additions (memory packs, op bundles) ride the same mechanism. The runtime's only skill surface is the `skill_load` harness tool, whose handler delegates wholesale to the skills resolver.

### 5.4 Unloading

`context_unload {path: "w/skills/pdf-processing"}` — the existing tool. Removing the entry removes the injected body, the skill's context entries, its tools, and its contributed discovery sources from the next turn onward. Already-loaded children remain independent entries. There is deliberately no `skill_unload`: one unload idiom, no near-duplicate tools to confuse a model.

---

## 6. Per-Turn Rendering

Each skill-flagged entry in effective loads, per turn:

1. Re-resolve the skill from its path.
2. Inject one system message: `[Skill: <name>]` followed by the body **verbatim** (markdown preserved — the §3.6 rendering contract of AGENT_CONTEXT.md); a contentless (toolset) skill shows its description one-liner instead.
3. Resolve the skill's `skill.context` entries through the standard context loader and inject each as a labelled `[Context: …]` message.
4. Contribute the skill's tools to the turn's palette (deduplicated against existing tool names).
5. Contribute its immediate `skill.skills` refs to the next skills index and named lookup scope.

A skill that fails to resolve renders a visible `[Skill: <name> — unavailable: <reason>]` element. Advisory aggregate budget pressure never makes a loaded skill silently disappear.

Load order for each inference: system prompt → `config.context` → **skills index** → a freshly resolved loads snapshot (including skill bodies and Context Map) → conversation.

---

## 7. Runtime Integration

| | llmagent | goaltree |
|---|---|---|
| Loads tier written | session (`sessions.<sid>.loads`) | active frame (`frame.loads`) |
| Persistence | across turns for the session, via the existing loads write-back | frame lifetime; **inherited copy-on-push by subgoals** (like all frame loads, tombstones included) |
| Tool activation | same transition — from the next tool-loop iteration | same transition — from the next iteration; the body ALSO re-renders mid-run (goaltree rebuilds loads context per iteration) |
| Tool offered when | any skill source declared (automatic) | any skill source declared (automatic), or bare `"skill_load"` in `config.tools` (registry opt-in) |
| Unload scope | session | frame — unloading an inherited skill masks it for that frame only |

A subgoal therefore starts with its parent's loaded skills and may load/unload its own without affecting the parent — the same lexical-scoping semantics as every other load.

---

## 8. The `skills` Operation — Discovery for Everyone

One command-dispatched op at `v/ops/skills` (tool name `skills`), following the `memory` adapter idiom. Read-only; usable by agents, humans, MCP clients, and as a `config.context` assemble-op.

| Command | Input | Output |
|---------|-------|--------|
| `list` | `sources?` (skillsets, default `["w/skills", "v/skills/root"]`), `skills?` (individual skills) | The rendered index text — a string, or null when no skills exist (the assemble-op contract: null → entry skipped) |
| `read` | exactly one of `name` (looked up across the declared sources) / `ref` | `{name, description, body?, tools, skills?, skillsets?, context?, path}` — `body` present when the skill has content |

Capability pins, per source actually read: workspace/venue/DID paths → `crud/read`; content-addressed refs → `asset/read`. Both sit inside the anonymous read-only scope, so venue skills are publicly discoverable. There is **no write surface** — skills are authored with `covia:write` and `asset:store`.

The defaults are venue-configurable (`adapters.skills.defaultSkillsets` / `defaultSkills`, see [CONFIG.md](CONFIG.md#adapter-configuration)) and published at `v/info/adapters/skills`, so a venue curating its own library answers discovery from it and clients read the entry point rather than assuming `v/skills/root`. They govern this operation only: agents declare their own sources, and an agent declaring none has skills off deliberately.

Because `list` honours the assemble-op contract, an operator can pin the index into any agent the data-driven way instead of declaring sources:

```json
"context": [{"op": "v/ops/skills", "input": {"command": "list"}, "label": "Available skills"}]
```

(This injects the index only — a first-class source declaration is what also activates `skill_load`.)

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

**The venue skill library** ships this way, one resource per skill under `venue/src/main/resources/skills/`, and **each skill is owned by the adapter it teaches**: an adapter calls `installSkill("<name>", "/skills/<name>.json")` in its `installAssets()` (`GridAdapter` ships `grid`, `HITLAdapter` ships `hitl`, `FileAdapter` ships `files`, …), so the skill is published exactly when the adapter is active and retracted when it is disabled or unloaded — the same rule as its operations. `SkillsAdapter.LIBRARY` holds only the platform skills that are about Covia and the venue as a whole: `covia`, `venue`, `discovery`, `provenance`, `skills`, `skill-authoring`. Bodies live in `content.inline`; each declares the operations it teaches. General templates (`minimal`, `skilled`, `goaltree`, `full`) index the complete `v/skills` directory. Specialist templates use stable single-skill paths such as `v/skills/models` to keep their per-turn indexes role-focused. Every template keeps `w/skills` first, so user-authored skills remain visible and shadow same-named venue skills. `SkillsLibraryTest` drift-guards materialisation, bodies, declared tool resolution, compact rendering, and the curated template sources.

---

## 10. Relationship to Other Features

| Feature | Relationship |
|---------|--------------|
| **Assets** | A skill *is* an asset: standard metadata, body as content via the universal content resolution (CAS, `content.inline`, `content.dlfs` pinned/live), extras under a `skill` facet exactly as invocability sits under `operation`. |
| **Operations** | Facets compose: an asset with both `operation` and `skill` is a self-documenting tool — loading it injects its manual and offers the op itself (§3.4). |
| **Context loads / scope chain** | Skills are loads entries with a `skill` flag — the scope chain, budgets, eviction, and `context_unload` are reused, not duplicated. |
| **`config.context`** | Pinned baseline knowledge, always loaded. Skills are the on-demand complement; the `skills:list` assemble-op bridges the two. |
| **Agent templates** | Same philosophy (config is data), same string-reference idiom. A template declares what an agent *is*; a skill declares what an agent *can pick up*. Templates may ship `config.skills` / `config.skillsets`. |
| **`more_tools` (goaltree)** | Skill tool activation reuses its mechanics. `more_tools` remains for raw op paths; skills add the instructions half and cross-runtime persistence. |
| **Toolsets (#79)** | A skill facet with only `tools` + a description *is* a toolset — this design subsumes the #79 sketch. |
| **Hierarchies** | `skill.skills` contributes more source refs while loaded, applying the same progressive-disclosure mechanism recursively without recursively loading anything. |
| **MCP bridging (#80)** | Bridged MCP tools are ordinary catalog ops — referenced from `skill.tools`, or made skills themselves via facet composition (§3.4). |
| **A2A agent cards** | A2A `AgentSkill` entries describe what an *agent* offers outward; these skills describe what an agent can *load* inward. Unrelated surfaces; the A2A card could later advertise loaded skills. |
| **UCAN / caps** | Skill reads pin `crud/read` / `asset/read`. A skill's tools are still capability-checked at invocation — loading a skill grants no authority. |

---

## 11. Limitations and Notes

- **Denormalised tool and child refs** (§5.3): edits to those lists need unload/reload; their targets, bodies, and context are live.
- **Budget is an advisory rendering/accounting weight**: string bodies render verbatim regardless (the existing `renderValue` contract); the budget bounds structured exploration but never triggers silent eviction.
- **`agent:context` inspection** uses the same effective scope, load renderer, contributed tools, ordering, and capability context as a live first inference.
- **Skillsets** read the whole map per turn to build the index — fine at expected scale; revisit with a keys-only listing if venues grow hundreds of skills. Grouping keeps the always-on index small, so a wide library costs an index line only once a family is opened.
- **Skill authors are trusted by the loading agent's operator**: a skill's instructions enter the prompt verbatim, its tools join the palette, and its child sources join discovery. Point your skill sources only at libraries you trust — the same trust rule as `config.context` (child reads and tool invocations remain capability-checked as usual).

---

## 12. Implementation Map

| Piece | Where |
|-------|-------|
| Resolver (one for every surface) | `covia.adapter.agent.Skills` |
| `skills` op (`list`/`read`) | `covia.adapter.SkillsAdapter` + `adapters/skills/skills.json` |
| `content.inline` (asset-model inline content) | `Engine.resolveContent` |
| Index injection + skill rendering + loads-tools rule | `ContextBuilder` (`withSkillsIndex`, `renderLoadEntry`, `loadsToolDefs`) |
| `skill_load` glue | `LLMAgentAdapter.handleSkillLoad` (session tier), `GoalTreeAdapter.runFrame` (frame tier) |
| Venue skill install | `AAdapter.installSkill` → `v/skills/<name>` |
