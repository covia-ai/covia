#!/usr/bin/env bash
# venue-3 (EC2) deploy, run on the host as root by deploy-ec2.yml through
# AWS SSM Run Command (security#12): GitHub Actions assumes the
# covia-deploy-venue-3 role over OIDC, so there is no SSH and no stored key.
# Pulls the published image and (re)starts the container; exits non-zero if
# the venue does not come up healthy behind nginx.

# Pull latest image (public on GHCR: no login needed)
docker pull ghcr.io/covia-ai/covia:latest

# Stop legacy systemd service if still present
sudo systemctl stop covia-venue.service 2>/dev/null || true

# Stop and remove existing container (by name, then any container still using port 8080)
docker stop covia-venue 2>/dev/null || true
docker rm covia-venue 2>/dev/null || true
docker ps -q --filter publish=8080 | xargs -r docker stop
docker ps -aq --filter publish=8080 | xargs -r docker rm

# Ensure data directory exists with correct config (preserve existing etch store)
mkdir -p /home/ec2-user/covia-data
if [ ! -f /home/ec2-user/covia-data/config.json ]; then
  printf '{"venues":[{"name":"Covia Venue (EC2)","hostname":"venue-3.covia.ai","port":8080,"store":"/data/venue.etch","trustedProxies":["loopback","172.16.0.0/12"],"mcp":{"enabled":true}}]}\n' \
    > /home/ec2-user/covia-data/config.json
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

path = Path("/home/ec2-user/covia-data/config.json")
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
# Container runs as UID 1001 (appuser) — grant write access to data dir
sudo chown -R 1001:1001 /home/ec2-user/covia-data

# Run new container. The image's ENTRYPOINT already invokes java and
# forwards CMD as arguments to MainVenue (see Dockerfile) — CMD must
# be just the config path, not a full java command line, or the
# entrypoint double-invokes java and MainVenue receives "java" as
# its config path instead of the real one.
#
# Publish on loopback only: nginx terminates TLS and proxies to
# localhost:8080. A bare -p 8080:8080 binds 0.0.0.0, and Docker's
# iptables rules bypass host firewalls, leaving the security group
# as the only thing keeping the plain-HTTP port off the internet.
docker run -d \
  --name covia-venue \
  --stop-timeout 30 \
  -p 127.0.0.1:8080:8080 \
  -v /home/ec2-user/covia-data:/data \
  -e JAVA_OPTS="-Xmx2g -XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8" \
  --log-driver=awslogs \
  --log-opt awslogs-region=ap-southeast-1 \
  --log-opt awslogs-group=/covia/venue \
  --log-opt awslogs-stream=docker-covia-venue \
  --log-opt awslogs-create-group=true \
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
  docker logs --tail 80 covia-venue
  exit 1
fi

# Prove the public path still works now the port is loopback-only:
# HTTPS through nginx on this host, pinned to the local interface.
if ! curl -sf --max-time 10 --resolve venue-3.covia.ai:443:127.0.0.1 \
    https://venue-3.covia.ai/api/v1/status > /dev/null; then
  echo "::error::venue is healthy but unreachable through nginx: check proxy_pass targets 127.0.0.1:8080"
  sudo nginx -T 2>/dev/null | grep -n proxy_pass || true
  exit 1
fi

# Prune old images
docker image prune -f
