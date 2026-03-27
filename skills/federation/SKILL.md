---
name: federation
description: Demo cross-venue federated operations — invoke operations on remote venues, show distributed audit trails. Use for demonstrating Covia's federation capabilities.
argument-hint: [demo|invoke] [venue-url]
---

# Federation Demo

Demonstrate Covia's federated grid operations — invoking operations across venue boundaries.

## Concepts

- **Grid** — the network of interconnected Covia venues
- **`grid:run`** — synchronous cross-venue operation (submit + wait for result)
- **`grid:invoke`** — asynchronous cross-venue operation (submit + return job ID)
- **Venue** — identified by URL or DID (`did:web:venue.example.com`)
- **UCAN proofs** — capability tokens that travel with requests for authorisation

## Commands

### `demo` — Run a federated demo

Requires two venues. Ask the user for the remote venue URL.

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
