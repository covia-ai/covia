# Covia Venue Servers

Live venue server instances available for development, testing, and agent use.

## venue-3.covia.ai (AWS)

- **URL:** https://venue-3.covia.ai
- **Status:** https://venue-3.covia.ai/api/v1/status
- **MCP:** https://venue-3.covia.ai/mcp
- **Swagger:** https://venue-3.covia.ai/swagger
- **DID:** https://venue-3.covia.ai/.well-known/did.json
- **Region:** AWS us-east-1 (N. Virginia)
- **Spec:** 2 vCPU, 4 GB RAM
- **TLS:** Let's Encrypt (auto-renew)

## venue-4.covia.ai (Azure)

- **URL:** https://venue-4.covia.ai
- **Status:** https://venue-4.covia.ai/api/v1/status
- **MCP:** https://venue-4.covia.ai/mcp
- **Swagger:** https://venue-4.covia.ai/swagger
- **DID:** https://venue-4.covia.ai/.well-known/did.json
- **Region:** Azure Korea Central
- **Spec:** 2 vCPU, 4 GB RAM
- **TLS:** Let's Encrypt (auto-renew)

## Common Details

Both venues run the same Covia server version and are deployed automatically on push to `develop`. Data (assets, workspace, jobs, agents, secrets) persists across restarts.

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
