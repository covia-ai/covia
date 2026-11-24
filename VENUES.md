# Covia Venue Servers

Live venue server instances available for development, testing, and agent use.

Three tiers, one per branch; every change passes them in turn (see `BUILD.md` → Development and QA):

- **Dev** — `venue-test`: runs the `:dev` image, deployed automatically on every push to `develop`. Scratch venue for trying the newest code; expect breakage and resets.
- **QA** — `venue-3`, `venue-4`: run the `:latest` image, deployed automatically on every push to `qa`, which takes develop by pull request. Use these to exercise unreleased features.
- **Stable** — `venue-1`, `venue-2`: run the `:stable` image, deployed automatically on push to `master` (i.e. at releases). Use these for demos, integrations, and anything long-lived.

| Venue | Tier | Fresh authenticated DID |
|---|---|---|
| `venue-1.covia.ai` | Stable | Requires operator provisioning with `user:create` |
| `venue-2.covia.ai` | Stable | Requires operator provisioning with `user:create` |
| `venue-test.covia.ai` | Dev | Registered on first authenticated use |
| `venue-3.covia.ai` | QA | Operator-configured; do not assume first-use registration |
| `venue-4.covia.ai` | QA | Registered on first authenticated use |

Anonymous access and authenticated-user admission are separate policies. Use
`venue-1` or `venue-2` for anonymous stable reads; use `venue-test` for a
fresh-key authenticated experiment without prior provisioning. QA-venue
policy may change with its operator configuration.

## Stable Venues

venue-1 and venue-2 run on the GCP host `covia-stable` (`35.213.147.8`), each in its own container with its own store and DID (see `deploy/gcp/README.md`). They track the `:stable` image published from `master`, so they change only at releases. Together they give a stable federation pair for cross-venue work.

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

## Dev Venue

### venue-test.covia.ai (GCP)

- **URL:** https://venue-test.covia.ai
- **Status:** https://venue-test.covia.ai/api/v1/status
- **MCP:** https://venue-test.covia.ai/mcp
- **Host:** `covia-venue-test` (`34.126.87.243`), on its own, isolated from the stable venues
- **Build:** the `:dev` image, redeployed on every push to `develop` that passes the default tests
- **Purpose:** scratch venue for trying the newest code — expect breakage, and make no assumptions about data longevity

## QA Venues

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
- **Region:** Azure Central India (Pune)
- **Spec:** 2 vCPU, 4 GB RAM
- **TLS:** Let's Encrypt (auto-renew)

## Common Details

Venues within a tier run the same Covia server version. Data (assets, workspace, jobs, agents, secrets) persists across restarts and redeploys.

### Connecting via SDK

```javascript
import { Venue, KeyPairAuth } from "@covia/covia-sdk";

const auth = KeyPairAuth.generate();
const venueUrl = process.env.COVIA_VENUE_URL;
if (!venueUrl) throw new Error("Set COVIA_VENUE_URL to a venue listed above");
const venue = await Venue.connect(venueUrl, auth);
```

### Connecting via MCP

Use the venue's MCP endpoint as a server URL in your MCP client configuration:

```json
{
  "mcpServers": {
    "covia": { "url": "https://your-venue.example.com/mcp" }
  }
}
```

### API Endpoints

All venues expose the same API surface:

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
