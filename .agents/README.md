# `.agents/` — Codex workspace config

Codex CLI and the Codex IDE extension read this repo's agent skills from
`.agents/skills/`. Claude Code reads the same skills from `.claude/skills/`.
Both are local links to the canonical, tracked `skills/` directory at the
repo root — skills are authored once and shared by every tool.

The links are gitignored, so each checkout creates its own:

```bash
# Windows (from covia root)
cmd /c "mklink /J .agents\skills skills"

# macOS / Linux
ln -s ../skills .agents/skills
```

Without it, `/skills` in Codex finds nothing and `$skill-name` will not
resolve. Nothing else in this directory is tracked.

Agent instructions for this repo live in [`AGENTS.md`](../AGENTS.md) — the
tool-agnostic source of truth, read by Codex from the repo root.
