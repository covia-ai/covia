---
name: federation
description: Demo cross-venue federated operations — invoke operations on remote venues, show distributed audit trails. Use for demonstrating Covia's federation capabilities.
argument-hint: [demo|invoke] [venue-url]
---

# Federation Demo

**Prerequisite:** The local venue must be running and connected as an MCP server (`http://localhost:8080/mcp/sse`). This skill also requires a **second venue** — either a deployed venue or a second local venue on a different port (see below). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Demonstrate Covia's federated grid operations — invoking operations across venue boundaries.

## Concepts

- **Grid** — the network of interconnected Covia venues
- **`grid:run`** — synchronous cross-venue operation (submit + wait for result)
- **`grid:invoke`** — asynchronous cross-venue operation (submit + return job ID)
- **Venue** — identified by URL or DID (`did:web:venue.example.com`)
- **UCAN proofs** — capability tokens that travel with requests for authorisation

## Two-Venue Setup

This demo requires a second venue. Options:

**Option A — Two local venues on different ports:**
```bash
# Terminal 1: local venue on default port
java -jar venue/target/covia.jar

# Terminal 2: second venue on port 8081
java -jar venue/target/covia.jar second-venue-config.json
```

Where `second-venue-config.json` contains:
```json
{
  "venues": [{ "name": "Remote Venue", "port": 8081, "mcp": { "enabled": true } }]
}
```

The remote URL is then `http://localhost:8081`.

**Option B — Use a deployed venue** (e.g. `https://venue.example.com`). Ask the user for the URL.

## Commands

### `demo` — Run a federated demo

Ask the user for the remote venue URL (or use `http://localhost:8081` if running two local venues).

1. **Echo test** — verify connectivity:
   ```
   grid_run  operation=test:echo  input={"hello": "federation"}  venue=<remote-url>
   ```

2. **Remote LLM call** — invoke an LLM on the remote venue:
   ```
   grid_run  operation=langchain:openai  venue=<remote-url>
     input={ "messages": [{"role": "user", "content": "What venue are you running on?"}] }
   ```

3. **Async invoke** — submit and poll:
   ```
   grid_invoke  operation=test:echo  input={"async": true}  venue=<remote-url>
   → returns job ID
   grid_jobStatus  id=<job-id>  venue=<remote-url>
   grid_jobResult  id=<job-id>  venue=<remote-url>
   ```

### `invoke <venue-url>` — Run a specific operation on a remote venue

Ask the user which operation and input, then:

```
grid_run  operation=<op>  input=<input>  venue=<venue-url>
```

## Key Talking Points

- Operations execute on the **remote** venue — data stays where it's controlled
- The local venue only sees the result, not intermediate state
- UCAN capability tokens can travel with requests for fine-grained authorisation
- Every invocation creates an immutable job record on both venues
- Venues are identified by DIDs — decentralised, no central registry
- The same operation interface works locally and across the grid
