---
name: secret
description: Manage secrets on a Covia venue — store API keys and credentials. Use when setting up LLM providers or other integrations that need secrets.
argument-hint: "<set|list|delete> <name>"
---

# Secret Management

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Manage encrypted secrets in the venue's per-user secret store.

## Commands

### `set <name>` — Store a secret

Ask the user for the value, then:

```
secret_set  name=<NAME>  value=<value>
```

Secret values are encrypted at rest and redacted in job records.

### `list` — List stored secrets

```
secret_list  prefix=<optional-prefix>  offset=0  limit=100
```

Returns `names`, matching `total`, `offset` and `nextOffset` when more remain. `prefix` and pagination are optional; limit defaults to 100 and is capped at 1000. Requires `crud/read` on `s/`. `covia:list {path:"s"}` remains available. Values cannot be read back via MCP — integrations resolve `s/NAME` references internally.

### `delete <name>` — Remove a secret

```
secret_delete  name=<NAME>
```

Only delete an exact credential the user has explicitly asked to remove. Deletion can break agents and integrations that reference it; do not use it for speculative cleanup. Requires `crud/delete` on `s/NAME`. Returns `{name,deleted}`; an absent name gives `deleted:false`. This removes the stored copy, not the credential at its provider. To rotate, use `secret_set` with `overwrite:true`; no delete is needed. `covia:delete {path:"s/NAME"}` remains available.

## Extraction

`secret:extract` is for direct owner calls, not this agent/MCP workflow. It
requires `secret/extract` on `s/NAME` and refuses recorded jobs, tool calls,
agent/session/task contexts and nested operations. Do not route it through a
conversation or paste its result into prompts, history or durable configuration.
Owners can use the published `v/ops/secret/extract` operation through the REST
result-only `/api/v1/run` endpoint. See `venue/docs/UCAN.md` for the restrictions.

## Common Secrets

| Secret Name | Used By | Required For |
|-------------|---------|-------------|
| `OPENAI_API_KEY` | `langchain:openai` | LLM agents using OpenAI (GPT-5.6 family, etc.) |
| `DEEPSEEK_API_KEY` | `langchain:openai` (with custom url) | DeepSeek models |
| `MISTRAL_API_KEY` | `langchain:mistral` | Mistral models |
| `OPENROUTER_API_KEY` | `langchain:openrouter` | Any OpenRouter model |
| `GEMINI_API_KEY` | `langchain:openai` (with custom url) | Google Gemini models |

## Notes

- Secrets are **per-user** — each authenticated DID has its own secret namespace
- In the local dev venue (public access), all MCP clients share the same secret store
- The `langchain:openai` adapter resolves `OPENAI_API_KEY` automatically from the caller's secret store
- Another user's secret is named `<did>/s/NAME` and needs a `secret/use` grant from its owner (see the ucan skill); the grant may be bound to a url prefix with `nb.url`
- For non-OpenAI providers, set the `url` parameter in the agent's `config` to point to the compatible endpoint
