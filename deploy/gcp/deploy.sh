#!/usr/bin/env bash
# GCP venue trio deploy (venue-test, venue-1, venue-2), run on the VM as root
# by deploy-gcp.yml (security#22): GitHub Actions signs in as the
# covia-deploy-gcp service account over OIDC and reaches the VM with
# `gcloud compute ssh --tunnel-through-iap` and OS Login, so there is no
# stored key and port 22 only needs to accept Google's IAP range. Pulls the
# :stable image and (re)starts the container; exits non-zero if any venue
# does not come up.
set -euo pipefail

# Pull the stable image (public on GHCR: no login needed). Pull with an empty
# client config: the old SSH deploys left expired GHCR logins in root's
# ~/.docker/config.json, and the registry denies those rather than falling
# back to an anonymous pull. Stop here if the pull fails, or the old image
# would be restarted behind a green deploy (covia#599).
if ! DOCKER_CONFIG=$(mktemp -d) docker pull ghcr.io/covia-ai/covia:stable; then
  echo "::error::docker pull failed — deploy stopped, running container left as is"
  exit 1
fi

# One JVM hosts all three venues (ports 8080-8082); each venue has
# its own store directory so identities (venue.key) stay separate.
# Ensure config exists (venues array format)
if [ ! -f /srv/covia/config.json ]; then
  mkdir -p /srv/covia/covia-test-data /srv/covia/covia-1-data /srv/covia/covia-2-data
  cat > /srv/covia/config.json <<'CONF'
{
  "venues": [
    {
      "name": "Covia Test Venue",
      "hostname": "venue-test.covia.ai",
      "port": 8080,
      "trustedProxies": ["loopback", "172.16.0.0/12"],
      "store": "/data/covia-test-data/venue.etch",
      "users": { "autoCreate": true },
      "mcp": {}
    },
    {
      "name": "Covia Venue 1 (Stable)",
      "hostname": "venue-1.covia.ai",
      "port": 8081,
      "trustedProxies": ["loopback", "172.16.0.0/12"],
      "store": "/data/covia-1-data/venue.etch",
      "mcp": {}
    },
    {
      "name": "Covia Venue 2 (Stable)",
      "hostname": "venue-2.covia.ai",
      "port": 8082,
      "trustedProxies": ["loopback", "172.16.0.0/12"],
      "store": "/data/covia-2-data/venue.etch",
      "mcp": {}
    }
  ]
}
CONF
fi

# venue-test is deliberately open to any successfully authenticated
# DID on first use. Reconcile the persisted config as well as the
# first-install template, without replacing unrelated operator edits.
python3 - <<'PY'
import json
from pathlib import Path

path = Path("/srv/covia/config.json")
config = json.loads(path.read_text(encoding="utf-8"))
# Every venue sits behind Caddy on this host and sees it through the
# Docker bridge: take the client address from X-Forwarded-For, or every
# caller shares one rate-limit bucket and one authentication budget
# (covia#539). Each venue also keeps the security audit trail and the
# per-request access log, as JSON lines a log shipper can route on
# (security#10, venue/docs/CONFIG.md "Logging"). An operator-set value
# is left alone.
config.setdefault("operations", {}).setdefault("log-format", "json")
for venue in config.get("venues", []):
    venue.setdefault("trustedProxies", ["loopback", "172.16.0.0/12"])
    logging = venue.setdefault("logging", {})
    logging.setdefault("audit", True)
    logging.setdefault("access", True)
for venue in config.get("venues", []):
    if venue.get("hostname") == "venue-test.covia.ai":
        users = venue.setdefault("users", {})
        if not isinstance(users, dict):
            raise TypeError("venue-test users config must be an object")
        users["autoCreate"] = True
        break
else:
    raise ValueError("venue-test.covia.ai is missing from config")

temporary = path.with_suffix(".json.tmp")
temporary.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
temporary.replace(path)
PY
chown -R 1001:1001 /srv/covia

# Stop and remove existing container
docker stop covia-venue 2>/dev/null || true
docker rm covia-venue 2>/dev/null || true

# Run new container.
#
# Publish on loopback only: Caddy terminates TLS on this host and
# proxies to localhost:8080-8082. A bare -p 8080:8080 binds 0.0.0.0,
# and Docker's iptables rules bypass host firewalls, leaving the VPC
# firewall as the only thing keeping the plain-HTTP ports off the
# internet.
#
# Rotate the json-file container log (Docker's default is unbounded):
# at most 5 x 50 MB before the oldest is dropped.
docker run -d \
  --name covia-venue \
  -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8081 -p 127.0.0.1:8082:8082 \
  -v /srv/covia:/data \
  --log-opt max-size=50m \
  --log-opt max-file=5 \
  --restart unless-stopped \
  --health-cmd='curl -f --max-time 3 http://localhost:8080/' \
  --health-interval=30s \
  --health-timeout=5s \
  --health-start-period=15s \
  --health-retries=3 \
  -e JAVA_OPTS="-Xmx2g -XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8" \
  ghcr.io/covia-ai/covia:stable \
  /data/config.json

# Wait for health check
sleep 10
docker inspect --format='{{.Name}}: {{.State.Health.Status}}' covia-venue

# All three venues must answer or the deploy fails
for P in 8080 8081 8082; do
  curl -sf --max-time 5 http://localhost:$P/api/v1/status > /dev/null || { echo "port $P NOT responding"; exit 1; }
  echo "port $P OK"
done

# Prove the public path still works now the ports are loopback-only:
# HTTPS through Caddy on this host, pinned to the local interface.
for H in venue-test venue-1 venue-2; do
  curl -sf --max-time 10 --resolve $H.covia.ai:443:127.0.0.1 \
    https://$H.covia.ai/api/v1/status > /dev/null \
    || { echo "::error::$H is up but unreachable through Caddy: check reverse_proxy targets in /etc/caddy/Caddyfile"; exit 1; }
  echo "$H via Caddy OK"
done

# Prune old images
docker image prune -f
