---
name: secret
description: Manage secrets on a Covia venue — store API keys and credentials. Use when setting up LLM providers or other integrations that need secrets.
argument-hint: [set|list] [name]
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
covia_list  path=s
```

Shows secret names (not values). Values cannot be read back via MCP — they are resolved internally at invocation time.

## Common Secrets

| Secret Name | Used By | Required For |
|-------------|---------|-------------|
| `OPENAI_API_KEY` | `langchain:openai` | LLM agents using OpenAI (gpt-4o, etc.) |
| `DEEPSEEK_API_KEY` | `langchain:openai` (with custom url) | DeepSeek models |
| `GEMINI_API_KEY` | `langchain:openai` (with custom url) | Google Gemini models |

## Notes

- Secrets are **per-user** — each authenticated DID has its own secret namespace
- In the local dev venue (public access), all MCP clients share the same secret store
- The `langchain:openai` adapter resolves `OPENAI_API_KEY` automatically from the caller's secret store
- For non-OpenAI providers, set the `url` parameter in the agent's `state.config` to point to the compatible endpoint
