#!/usr/bin/env bash
# venue-4 (Azure) deploy, run on the VM as root by deploy-azure.yml through
# az vm run-command (security#12): GitHub Actions signs in as the
# covia-deploy-venue-4 managed identity over OIDC, so there is no SSH and no
# stored key. Pulls the published image and (re)starts the container; exits
# non-zero if the venue does not come up healthy.

# Pull latest image (public on GHCR: no login needed)
docker pull ghcr.io/covia-ai/covia:latest

# Ensure config exists (venues array format)
mkdir -p /home/azureuser/covia-data
if [ ! -f /home/azureuser/covia-data/config.json ]; then
  cat > /home/azureuser/covia-data/config.json <<'CONF'
{
  "venues": [
    {
      "name": "Covia Venue (Azure)",
      "hostname": "venue-4.covia.ai",
      "store": "/data/venue.etch",
      "trustedProxies": ["loopback", "172.16.0.0/12"],
      "mcp": {}
    }
  ]
}
CONF
fi
# The venue sits behind nginx on this host and sees it through the
# Docker bridge: it must take the client address from X-Forwarded-For,
# or every caller shares one rate-limit bucket and one authentication
# budget (covia#539). It also keeps the security audit trail and
# the per-request access log, as JSON lines a log shipper can route
# on (security#10, venue/docs/CONFIG.md "Logging"). Reconcile the
# persisted config, leaving any operator-set value alone.
sudo python3 - <<'PY'
import json
from pathlib import Path

path = Path("/home/azureuser/covia-data/config.json")
config = json.loads(path.read_text(encoding="utf-8"))
config.setdefault("operations", {}).setdefault("log-format", "json")
for venue in config.get("venues", []):
    venue.setdefault("trustedProxies", ["loopback", "172.16.0.0/12"])
    logging = venue.setdefault("logging", {})
    logging.setdefault("audit", True)
    logging.setdefault("access", True)
temporary = path.with_suffix(".json.tmp")
temporary.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
temporary.replace(path)
PY
sudo chown -R 1001:1001 /home/azureuser/covia-data

# Stop and remove existing container
docker stop covia-venue 2>/dev/null || true
docker rm covia-venue 2>/dev/null || true

# Run new container. The image's ENTRYPOINT already invokes java and
# forwards CMD as arguments to MainVenue (see Dockerfile) — CMD must
# be just the config path, not a full java command line, or the
# entrypoint double-invokes java and MainVenue receives "java" as
# its config path instead of the real one.
#
# Publish on loopback only: nginx terminates TLS and proxies to
# 127.0.0.1:8080. A bare -p 8080:8080 binds 0.0.0.0, and Docker's
# iptables rules bypass host firewalls such as ufw.
#
# Rotate the json-file container log (Docker's default is unbounded):
# at most 5 x 50 MB before the oldest is dropped.
docker run -d \
  --name covia-venue \
  --stop-timeout 30 \
  -p 127.0.0.1:8080:8080 \
  -v /home/azureuser/covia-data:/data \
  -e JAVA_OPTS="-Xmx2g -XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8" \
  --log-opt max-size=50m \
  --log-opt max-file=5 \
  --restart unless-stopped \
  --health-cmd='curl -f --max-time 3 http://localhost:8080/' \
  --health-interval=30s \
  --health-timeout=5s \
  --health-start-period=15s \
  --health-retries=3 \
  ghcr.io/covia-ai/covia:latest \
  /data/config.json

# Wait for the health check to settle; fail the deploy rather than
# leaving a crash-looping container behind a green checkmark.
status="starting"
# Cover the full health window: 15s start period + three 30s
# intervals + check time, with a final boundary inspection.
for i in $(seq 1 25); do
  status=$(docker inspect --format='{{.State.Health.Status}}' covia-venue 2>/dev/null || echo "unknown")
  echo "Health status: $status"
  [ "$status" = "healthy" ] && break
  [ "$status" = "unhealthy" ] && break
  [ "$i" -lt 25 ] && sleep 5
done
if [ "$status" != "healthy" ]; then
  echo "::error::covia-venue did not become healthy — deploy failed"
  # run-command keeps only the last 4 KB of output, so keep this short
  docker logs --tail 30 covia-venue
  exit 1
fi

# Prune old images
docker image prune -f
