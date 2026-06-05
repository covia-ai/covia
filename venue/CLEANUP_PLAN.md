# Venue Module Cleanup Plan

A phased plan to pay down structural debt in the `venue` module while keeping
the test suite green and **preserving observable behaviour** at every step.
Derived from a six-cluster architecture review (Jun 2026).

Guiding rules (from the project's stated preferences):
- **Don't change existing behaviour** — refactors are additive / mechanical; new
  behaviour is opt-in.
- **Ask before adding complexity** — extend existing structures before
  introducing new classes; validate a design before a structural change.
- **No implicit behaviour in API design** — disambiguate via args/functions, not
  type-discrimination, structural rules, or prefix conventions.
- **Don't reinvent lattice navigation** — go through `convex.lattice.cursor`.
- **Avoid `Strings.create` for fixed strings** — use `Strings.intern` +
  existing constants.
- **Instance-based ops** over static-utility-with-overload explosion.

Each item is checkable; build + venue suite must stay green before commit.

---

## Scope summary

82 files, ~27.5k LOC. Healthy lattice/job core; five recurring structural
debts (four are deviations from the rules above). Tackling the cross-cutting
themes first yields the most cleanup-per-effort because each is duplicated many
times.

Already good — **do not touch**: `A2ACodec` (clean pure-function codec — the
model to copy), `Config` (disciplined typed accessors), `ContextLoader` &
`GoalTreeContext` (pure functions), `JobManager` (large but cohesive — not a
god-class), `RequestContext` (clean immutable value).

---

## Phase 0 — Quick wins (mechanical, behaviour-preserving)

Low risk. Shrinks surface, removes drift hazards, builds confidence. Batchable.

### 0a. Dead code
- [ ] `AgentAdapter.extractSessionIdFromTask` (~:1912) — no callers anywhere. Delete.
- [ ] `Engine.resolveAssetRef` (~:1052) — no callers; logic inlined in `resolvePath`/`resolveHash`. Delete.
- [ ] `AgentAdapter` unused `PENDING` constant (~:68) — confirm zero refs, delete.
- [ ] `Engine.getRootCursor` (~:1469) — remove the vestigial `@SuppressWarnings("unchecked")` (method performs no cast).
- [ ] `GoalTreeContext.describeTask` (~:134) — orphan; logic re-inlined in `describeTransitionInput`. Delete method + its dedicated tests.
- [ ] `LLMAgentAdapter.isKnownTask` / `isAlreadyCompleted` (~:644/:657) — production-dead leftovers of an earlier task-completion path; referenced only by their own unit tests. Confirm superseded, then delete method + tests.
- [ ] `GoalTreeContext.estimateLiveTurnBytes` (~:508) — production-dead; test-only. Confirm, then delete method + tests.
- [ ] **Deferred (not Phase 0):** `GoalTreeAdapter.buildTypedRootHarnessTools` is the *canonical* typed-tool builder that 3 inline copies should adopt — keep as the seed for Phase 1 consolidation (do NOT delete).
- [ ] **Investigate separately:** agent-level `pending` Index in `AgentState` (`addPending`/`getPending`/`extractPending` + `resolveJobIds` plumbing) appears write-only — removing it is a data-path change, not a quick win. Defer to its own task.

### 0b. Orphaned / misattached Javadoc (zero behaviour risk)
- [ ] `AgentAdapter` — three-source sweep Javadoc is attached to `failQueuedTasks` but documents `failAllPendingForAgent`. Move it down.
- [ ] `JobManager` (~:270-304) — `prepareInvocation`'s doc sits above `enforceCaps`. Move it to precede `prepareInvocation`.
- [ ] `CoviaAdapter` (~:488-544) — `handleWrite`'s Javadoc sits above the `copy` section. Move it down to `handleWrite`.

### 0c. `Strings.create` → intern / `Fields.*` (safest, verified subset)
- [ ] `Engine` (~:607-608, :969) — `Strings.create("name"|"description"|"operation")` → `Fields.NAME`/`Fields.DESCRIPTION`/`Fields.OPERATION` (already interned; removes a per-call alloc in dispatch).
- [ ] `AgentAdapter` fixed op refs → interned constants (`OP_LLMAGENT_CHAT`, `OP_LANGCHAIN_OPENAI`, fixed status/message literals), following the existing `TRIGGER_OP` pattern.
- [ ] `CoviaAPI` (~:458 `"true"`, ~:812 `"[]"`), `MCP` (`"includePathPrefixes"`/`"includeAdapters"`) → interned constants.
- [ ] Broader sweep in `TestAdapter`, `GoalTreeAdapter` (schema vocab), `JVMAdapter` is large — split into its own focused task if it grows the diff too much.

### 0d. Tiny consistency fixes
- [ ] `Engine.STATUS_MAP` → `static final` (it's genuinely constant; currently a per-instance "constant" field).
- [ ] `AgentAdapter.K_LLM_OPERATION` — reference `AbstractLLMAdapter.K_LLM_OPERATION` instead of re-declaring a private copy.

**Note on T4 (namespace-prefix unification):** the prefix lists in
`Engine.isUserNamespacePath` and `ContextLoader.isNamespacePath`/`isAssetReference`
**diverge** (`t/`, `v/`). Unifying them is therefore *not* behaviour-preserving
— it requires a deliberate decision about the correct set. Moved to **Phase 1**
(with a decision needed), not Phase 0.

---

## Phase 1 — Shared contracts (highest leverage)

Many files, but each edit small and mechanical. Behind the green suite.

### T1. Adapter dispatch / parse / error contract in `AAdapter`
- [ ] Add a `dispatch(meta, handlers)` template (subOp → handler), wrapping the body in `supplyAsync(VIRTUAL_EXECUTOR)` + uniform error→`failedFuture` + one canonical "unknown/missing sub-operation" message.
- [ ] Add `requireAuth(ctx)` (collapse the copied `callerDID==null` guard in Covia/Asset/Secret/DLFS).
- [ ] Add `paramOrMeta(input, meta, Fields.X)` (collapse 4 copies: `ConvexAdapter.locateEndpoint`, `GridAdapter.resolveVenue`, `MCPAdapter.getServerUrl`, LangChain url/apiKey).
- [ ] Promote `GridAdapter.describeFailure` to `AAdapter` (shared error rendering); use it in the default `invoke` `exceptionally` block.
- [ ] Single `coerceJsonString` helper (collapse the 4 inconsistent JSON-string re-parsers: `AbstractLLMAdapter.ensureParsedInput`, `SchemaAdapter.parseValue`, `GridAdapter.coerceOperationInput`, `Orchestrator.getMap`).
- [ ] First-class "job-only adapter" affordance so `Orchestrator`/`TestAdapter` stop sabotaging `invokeFuture` (throw vs failedFuture).
- [ ] Migrate the pure-function adapters first (`JSONAdapter`, `SchemaAdapter`, `JVMAdapter`, `TestAdapter`); fix `MCPAdapter`'s synchronous `throw` from `invokeFuture`.

### T2. LLM message-protocol vocabulary
- [ ] Add the one missing key (`Fields.ROLE`) + a small `covia.api` holder for `ROLE_*` / `TOOL_CALLS`.
- [ ] Point `LangChainAdapter`, `AbstractLLMAdapter`, `ContextBuilder`, `GoalTreeContext`, `AgentState`, `TestAdapter` at the shared constants (delete the duplicate declarations).
- [ ] Extract shared message-scan helpers (`lastUserContent`, `hasToolResults`, `assistantText`, `assistantToolCall`); collapse `TestAdapter`'s ~350 LOC of hand-rolled scans and the producer-side duplication.
- [ ] Consolidate the typed complete/fail-tool wiring (3 inline copies) onto `GoalTreeAdapter.buildTypedRootHarnessTools`.

### T4. Namespace-prefix single source of truth (decision needed)
- [ ] Decide the correct prefix set (resolve the `t/`/`v/` divergence deliberately).
- [ ] One `Set<String>` (ideally derived from `Namespace.*`), consumed by `Engine` and `ContextLoader`.

---

## Phase 2 — God-class / overlong-method extraction

Add new classes — **propose each design before cutting**. One at a time, suite green.

- [ ] **`AgentAdapter` (2173)** → extract `AgentRunLoop` instance class (`runningLoops`/`activeTransitions`/`deferredCompletions`/`activeChats` + `wakeAgent`/`executeRunLoop`/`mergeAndPostProcess`). Collapse the picked-work tuple into a `PickedWork` record (kills the 14-param signature). Collapse `AgentState.mergeRunResult` 3-overload chain into one + param object.
- [ ] **`Engine` (1665)** → extract `AssetResolver`/`PathResolver` (~370 LOC, cleanest first), then `AdapterRegistry` (trivial), then `VenueProvisioner` (breaks the startup `Engine→JobManager→Engine` cycle; rename `addDemoAssets` away from "demo"), then `PersistenceCoordinator`.
- [ ] **`CoviaAPI` (948)** → `handleJobLifecycle(...)` helper (4 near-identical handlers, ~120 LOC); consider splitting secrets/DID into their own `ACoviaAPI` subclasses like `UserAPI`.
- [ ] **`GoalTreeAdapter.runFrame` (~355 LOC)** → extract `selectHarnessTools`/`handleMoreTools`/`assembleFrameHistory`; tool dispatch `if/else` → `switch` mirroring `executeToolCall`.
- [ ] `JobManager` `(id)`/`(id,ctx)` pairs → `requireOwnedJob(id, ctx)`; collapse the wrappers.

---

## Phase 3 — Lattice-nav unification + CoviaAdapter split

Trickiest; aligns with the existing P2 TODO. Propose design first.

- [ ] Single-pass `resolveExternalPath(cursor, jsonKeys)` (walk lattice + value together); replace `readPath`'s O(n²) descending-prefix loop. Used by `read`/`slice`/`list`/`inspect`.
- [ ] Collapse `deepGet`/`deepSet`/`deepDelete`/`deepAppend` into one `deepUpdate(root, keys, from, leafOp)` reusing `navigateInto`'s type-dispatch; hoist the `==2` split into one `applyWrite(...)`.
- [ ] One `requireWritable(ctx, jsonKeys)` (collapse `isWritableNamespace`/`validateWritablePath` + the asymmetric `canWrite` check).
- [ ] Extract DID-URL resolution + UCAN proof verification from `CoviaAdapter` to a `CoviaAccessControl` helper.
- [ ] File/DLFS: extract shared tree-walk (`TreeState`/`walkTree`/`humanSize`/`boundedInt`) + one canonical `looksLikeText`; push backend quirks behind the `Root` hierarchy.

---

## Tier 4 — Discuss separately (behaviour / contract changes)

These deviate from "no implicit behaviour" but fixing them changes a contract —
needs a disambiguation decision, not a silent refactor.

- [ ] `Orchestrator` positional-sentinel spec DSL (type of `v.get(0)` selects meaning); decode duplicated across `scanDeps`/`computeInput`. At minimum share one classifier enum + document the grammar.
- [ ] `CoviaAPI.invokeOperation` accepts both string `"true"` and JSON boolean for `wait` — pick one declared type (route through `parseWaitMs`).
- [ ] `AgentAdapter` `parseConfigArg` `startsWith("{")` and `handleCreate` `systemPrompt`-key-sniffing — make the choice explicit.

---

## Borderline bugs surfaced during the smell review (file/fix separately)

Not smells — real defects found while reading. Track as issues.

- [ ] **High** — `GoalTreeAdapter` renders ancestors + active-frame conversation **twice** (`processGoal` `.withFrameStack` *and* `runFrame`), inconsistent eliding → doubled token cost + latent correctness hazard.
- [ ] **Med** — `CoviaWebApp` does `new MCP(...)` on every adapter-detail page render → leaks a job-update listener + `SseServer` each hit.
- [ ] **Med** — `looksLikeText` (UTF-8 + control-char ratio) vs `isLikelyText` (NUL-only) classify the same file differently via File-root vs DLFS path.
- [ ] **Low** — `CoviaAPI.listSecrets` empty case returns the string `"[]"` instead of an empty list.

---

## Progress log

- _2026-06-05_ — Plan created from six-cluster review. Phase 0 started.
