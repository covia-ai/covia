---
name: venue-setup
description: Set up and run a Covia venue — locally for development, on a VM for testing, or containerised for production. Covers build, config, deployment, and troubleshooting.
argument-hint: [local|vm|docker|config]
---

# Venue Setup

Set up a Covia venue for development, testing, or production.

## Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Java JDK | 21+ | Building and running |
| Maven | 3.7+ | Build tool |
| Convex | 0.8.4-SNAPSHOT | Lattice platform (must be in local Maven repo) |
| Git | Any | Source control |

**Install Convex first** (Covia depends on it):
```bash
cd ../convex && mvn clean install -DskipTests
```

## Commands

### `local` — Local Development Setup

For running a venue on your dev machine.

**Build:**
```bash
cd covia
mvn clean install -DskipTests
```

**Run (default config):**
```bash
java -jar venue/target/covia.jar
```

**Run (custom config):**
```bash
java -jar venue/target/covia.jar config.json
```

The venue starts on port **8080** by default. Verify with:
```bash
curl http://localhost:8080/
```

**MCP connection** — add to Claude Code or Claude Desktop MCP settings:
```json
{
  "mcpServers": {
    "local-covia-venue": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

### `vm` — VM/Server Deployment

For a persistent test or staging venue on a Linux VM.

**System setup (Ubuntu):**
```bash
sudo apt update
sudo apt-get install -y openjdk-25-jdk caddy screen
```

**Upload the JAR** (from build machine):
```bash
# Via scp
scp venue/target/covia.jar user@vm-host:~/covia.jar

# Or via cloud storage (e.g. GCloud)
gsutil cp venue/target/covia.jar gs://your-bucket/covia.jar
```

**Config file** — create `~/.covia/config.json`:
```json
{
  "venues": [
    {
      "name": "My Venue",
      "did": "did:web:venue.example.com",
      "hostname": "venue.example.com",
      "port": 8080,
      "mcp": { "enabled": true },
      "adapters": {}
    }
  ]
}
```

**Caddy reverse proxy** — create `/etc/caddy/Caddyfile`:
```
venue.example.com {
  reverse_proxy :8080
}
```

```bash
sudo systemctl start caddy
```

**Run in screen** (persists after SSH disconnect):
```bash
screen -S covia
java -jar ~/covia.jar ~/.covia/config.json
# Ctrl+A, D to detach
# screen -x covia to reattach
```

**Health check:**
```bash
curl https://venue.example.com/
```

### `docker` — Containerised Deployment

For production or cloud deployment.

**Build image:**
```bash
mvn clean install -DskipTests
docker build -t covia:latest .
```

**Run locally:**
```bash
docker run -p 8080:8080 covia:latest
```

**Run with config:**
```bash
docker run -p 8080:8080 -v /path/to/config.json:/app/config.json covia:latest config.json
```

**Container details:**
- Base image: Eclipse Temurin 25 JRE (Alpine)
- Non-root user: `appuser` (UID 1001)
- Health check: `curl http://localhost:8080/` every 30s
- JVM: G1GC, 75% max RAM, container-aware

**Cloud deployment** — push the image to any container registry and deploy. Example with Google Cloud Run:
```bash
docker tag covia:latest gcr.io/PROJECT_ID/covia:latest
docker push gcr.io/PROJECT_ID/covia:latest
gcloud run deploy covia \
  --image gcr.io/PROJECT_ID/covia:latest \
  --platform managed \
  --memory 1Gi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 10
```

Adapt for other platforms (AWS ECS, Azure Container Instances, fly.io, etc.) as needed — the image is a standard OCI container.

### `config` — Configuration Reference

**Config file structure** (`config.json`):
```json
{
  "venues": [
    {
      "name": "Venue Name",
      "did": "did:web:hostname",
      "hostname": "hostname",
      "port": 8080,
      "mcp": {
        "enabled": true
      },
      "auth": {
        "publicAccess": true
      },
      "adapters": {}
    }
  ]
}
```

**Key settings:**
| Field | Default | Purpose |
|-------|---------|---------|
| `port` | 8080 | HTTP listen port |
| `mcp.enabled` | true | Enable MCP endpoint at `/mcp` |
| `auth.publicAccess` | true | Allow unauthenticated access (dev only) |
| `did` | auto-generated | Venue's decentralised identifier |
| `hostname` | localhost | Public hostname for DID resolution |

**Multiple venues** — run several on one host with different ports:
```json
{
  "venues": [
    { "name": "Venue A", "port": 8080 },
    { "name": "Venue B", "port": 8081 }
  ]
}
```

## Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| `Cannot start agent` | `config.operation` is a map, not a string | Use plain string: `"v/ops/llmagent/chat"` |
| MCP tool name rejected | Colons in tool names | Rebuild venue (fix in MCP.java sanitises names) |
| `Job not found` | Wrong job ID format | Use the ID from `agent_request` response |
| Agent SLEEPING but not running | No pending tasks or messages | Use `agent_request` (not `agent_message`) to submit work |
| LLM call fails | Missing API key | Run `secret_set name=OPENAI_API_KEY value=<key>` |
| Port in use | Another process on 8080 | Check with `netstat -lntup` or change port in config |
