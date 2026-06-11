# Covia Venue Servers

Live venue server instances available for development, testing, and agent use.

Two tiers:

- **Stable** — `venue-1`, `venue-2` (plus `venue-test` for scratch use): run the `:stable` image, deployed automatically on push to `master` (i.e. at releases). Use these for demos, integrations, and anything long-lived.
- **Dev** — `venue-3`, `venue-4`: run the `:latest` image, deployed automatically on every push to `develop`. Use these to exercise unreleased features.

## Stable Venues

All three stable-tier venues run in a **single JVM** on one GCP host (multi-venue `venues` array config — see `deploy/gcp/README.md`), each with its own store and DID. Bootstrapped June 2026 from the latest snapshot build; from the next release onward they track the `:stable` image published from `master`.

### venue-1.covia.ai (GCP)

- **URL:** https://venue-1.covia.ai
- **Status:** https://venue-1.covia.ai/api/v1/status
- **MCP:** https://venue-1.covia.ai/mcp
- **Swagger:** https://venue-1.covia.ai/swagger
- **DID:** https://venue-1.covia.ai/.well-known/did.json
- **TLS:** Let's Encrypt (auto-renew)

### venue-2.covia.ai (GCP)

- **URL:** https://venue-2.covia.ai
- **Status:** https://venue-2.covia.ai/api/v1/status
- **MCP:** https://venue-2.covia.ai/mcp
- **Swagger:** https://venue-2.covia.ai/swagger
- **DID:** https://venue-2.covia.ai/.well-known/did.json
- **TLS:** Let's Encrypt (auto-renew)

### venue-test.covia.ai (GCP)

- **URL:** https://venue-test.covia.ai
- **Status:** https://venue-test.covia.ai/api/v1/status
- **MCP:** https://venue-test.covia.ai/mcp
- **Purpose:** scratch venue for experiments — same build as venue-1/-2, but make no assumptions about data longevity

The trio gives a stable federation set for cross-venue work on one host (`35.213.147.8`).

## Dev Venues

### venue-3.covia.ai (AWS)

- **URL:** https://venue-3.covia.ai
- **Status:** https://venue-3.covia.ai/api/v1/status
- **MCP:** https://venue-3.covia.ai/mcp
- **Swagger:** https://venue-3.covia.ai/swagger
- **DID:** https://venue-3.covia.ai/.well-known/did.json
- **Region:** AWS us-east-1 (N. Virginia)
- **Spec:** 2 vCPU, 4 GB RAM
- **TLS:** Let's Encrypt (auto-renew)

### venue-4.covia.ai (Azure)

- **URL:** https://venue-4.covia.ai
- **Status:** https://venue-4.covia.ai/api/v1/status
- **MCP:** https://venue-4.covia.ai/mcp
- **Swagger:** https://venue-4.covia.ai/swagger
- **DID:** https://venue-4.covia.ai/.well-known/did.json
- **Region:** Azure Korea Central
- **Spec:** 2 vCPU, 4 GB RAM
- **TLS:** Let's Encrypt (auto-renew)

## Common Details

Venues within a tier run the same Covia server version. Data (assets, workspace, jobs, agents, secrets) persists across restarts and redeploys.

### Connecting via SDK

```javascript
import { Venue, KeyPairAuth } from "@covia/covia-sdk";

const auth = KeyPairAuth.generate();
const venue = await Venue.connect("https://venue-3.covia.ai", auth);
// or
const venue = await Venue.connect("https://venue-4.covia.ai", auth);
```

### Connecting via MCP

Use the venue's MCP endpoint as a server URL in your MCP client configuration:

```json
{
  "mcpServers": {
    "covia-aws": { "url": "https://venue-3.covia.ai/mcp" },
    "covia-azure": { "url": "https://venue-4.covia.ai/mcp" }
  }
}
```

### API Endpoints

Both venues expose the same API surface:

- `GET  /api/v1/status` — Venue health and stats
- `GET  /api/v1/operations` — List available operations
- `GET  /api/v1/operations/{name}` — Operation details
- `POST /api/v1/invoke` — Execute an operation
- `GET  /api/v1/jobs` — List jobs
- `GET  /api/v1/jobs/{id}` — Job status
- `GET  /api/v1/jobs/{id}/sse` — Job event stream
- `GET  /api/v1/assets` — List assets
- `GET  /api/v1/secrets` — List secrets
- `POST /mcp` — MCP JSON-RPC
- `POST /a2a` — Agent-to-Agent JSON-RPC
- `GET  /.well-known/did.json` — Venue DID document
- `GET  /.well-known/agent-card.json` — A2A agent card
- `GET  /.well-known/mcp` — MCP discovery
- `GET  /swagger` — API documentation
