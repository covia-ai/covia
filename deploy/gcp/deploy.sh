#!/usr/bin/env bash
# GCP stable-venue deploy, run on a venue host as root by deploy-gcp.yml
# (security#6, #22): GitHub Actions signs in as the covia-deploy-gcp service
# account over OIDC and reaches the host with `gcloud compute ssh
# --tunnel-through-iap` and OS Login, so there is no stored key and port 22
# only accepts Google's IAP range.
#
#   DEPLOY_IMAGE=ghcr.io/covia-ai/covia@sha256:<digest> deploy.sh <venue>...
#
# Hosts and the venues each one runs:
#   covia-stable      venue-1 venue-2
#   covia-venue-test  venue-test
#
# Each venue is its own container (covia-<venue>) with only its own directory
# mounted. Venues are redeployed one at a time, so the others on the host stay
# up. Exits non-zero, leaving the remaining venues untouched, if a venue does
# not come back with the same DID.
set -euo pipefail

if [[ ! "${DEPLOY_IMAGE:-}" =~ ^ghcr.io/covia-ai/covia@sha256:[0-9a-f]{64}$ ]]; then
  echo "::error::DEPLOY_IMAGE must name the published image by digest"
  exit 1
fi
if [ $# -lt 1 ]; then
  echo "::error::usage: deploy.sh <venue>... (venue-test, venue-1, venue-2)"
  exit 1
fi

# venue -> port, heap, status name
venue_info() {
  case $1 in
    venue-test) echo "8080 768m Covia Test Venue" ;;
    venue-1)    echo "8081 1g Covia Venue 1 (Stable)" ;;
    venue-2)    echo "8082 1g Covia Venue 2 (Stable)" ;;
    *) return 1 ;;
  esac
}
for v in "$@"; do
  venue_info "$v" > /dev/null || { echo "::error::unknown venue '$v'"; exit 1; }
done

# Pull the selected image (public on GHCR: no login needed). Pull with an
# empty client config so a stale registry login on the host can't turn an
# anonymous pull into a denied one. Stop here if the pull fails, or the old
# image would be restarted behind a green deploy (covia#599).
if ! DOCKER_CONFIG=$(mktemp -d) docker pull "$DEPLOY_IMAGE"; then
  echo "::error::docker pull failed — deploy stopped, running containers left as is"
  exit 1
fi

status_did() {
  curl -sf --max-time 5 "http://127.0.0.1:$1/api/v1/status" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin).get("did", ""))' 2>/dev/null || true
}

for v in "$@"; do
  read -r port heap name < <(venue_info "$v")
  dir=/srv/covia/$v
  config=$dir/config.json
  container=covia-$v
  echo "== $v (port $port, -Xmx$heap)"

  install -d -o 1001 -g 1001 -m 0750 "$dir" "$dir/data"

  # First install only: a single-venue config (the server config format
  # always takes a venues array). The store and its venue.key live together
  # in $dir/data, so the identity moves with the data.
  if [ ! -f "$config" ]; then
    python3 - "$config" "$name" "$v.covia.ai" "$port" <<'PY'
import json, sys
path, name, host, port = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
venue = {"name": name, "hostname": host, "port": port,
         "store": "/data/data/venue.etch", "mcp": {},
         "trustedProxies": ["loopback", "172.16.0.0/12"]}
with open(path, "w", encoding="utf-8") as f:
    json.dump({"venues": [venue]}, f, indent=2)
    f.write("\n")
PY
  fi

  # Reconcile the persisted config, leaving operator-set values alone:
  # - trustedProxies: the venue sees Caddy through the Docker bridge, so it
  #   must take the client address from X-Forwarded-For, or every caller
  #   shares one rate-limit bucket and one authentication budget (covia#539).
  # - logging: keep the security audit trail and per-request access log, as
  #   JSON lines a log shipper can route on (security#10, CONFIG.md "Logging").
  # - venue-test is deliberately open to any authenticated DID on first use.
  python3 - "$config" "$v" <<'PY'
import json, sys
from pathlib import Path

path, venue_name = Path(sys.argv[1]), sys.argv[2]
config = json.loads(path.read_text(encoding="utf-8"))
venues = config.get("venues")
if not isinstance(venues, list) or len(venues) != 1:
    raise SystemExit(f"{path}: expected a venues array with exactly one venue")
venue = venues[0]
if venue.get("hostname") != f"{venue_name}.covia.ai":
    raise SystemExit(f"{path}: hostname {venue.get('hostname')!r} is not {venue_name}.covia.ai")
config.setdefault("operations", {}).setdefault("log-format", "json")
venue.setdefault("trustedProxies", ["loopback", "172.16.0.0/12"])
logging = venue.setdefault("logging", {})
logging.setdefault("audit", True)
logging.setdefault("access", True)
if venue_name == "venue-test":
    users = venue.setdefault("users", {})
    if not isinstance(users, dict):
        raise SystemExit("venue-test users config must be an object")
    users["autoCreate"] = True
temporary = path.with_suffix(".json.tmp")
temporary.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
temporary.replace(path)
PY
  chown 1001:1001 "$config"
  chmod 0640 "$config"

  # The DID the venue answers with now; it must be the same after the restart.
  before=$(status_did "$port")

  docker stop "$container" 2>/dev/null || true
  docker rm "$container" 2>/dev/null || true

  # Publish on loopback only: Caddy terminates TLS on this host. A bare
  # -p 8080:8080 binds 0.0.0.0, and Docker's iptables rules bypass host
  # firewalls. The json-file log is rotated at 5 x 50 MB.
  docker run -d \
    --name "$container" \
    --stop-timeout 30 \
    -p "127.0.0.1:$port:$port" \
    -v "$dir:/data" \
    -e JAVA_OPTS="-Xmx$heap -XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dfile.encoding=UTF-8" \
    --log-opt max-size=50m \
    --log-opt max-file=5 \
    --restart unless-stopped \
    --health-cmd="curl -f --max-time 3 http://localhost:$port/" \
    --health-interval=30s \
    --health-timeout=5s \
    --health-start-period=15s \
    --health-retries=3 \
    "$DEPLOY_IMAGE" \
    /data/config.json > /dev/null

  # Wait for the venue to answer; fail rather than leave a crash-looping
  # container behind a green deploy.
  after=""
  for i in $(seq 1 40); do
    after=$(status_did "$port")
    [ -n "$after" ] && break
    if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" != true ]; then break; fi
    sleep 3
  done
  if [ -z "$after" ]; then
    echo "::error::$v did not come up — deploy stopped"
    docker logs --tail 30 "$container"
    exit 1
  fi
  if [ -n "$before" ] && [ "$after" != "$before" ]; then
    echo "::error::$v came back as $after, not $before — wrong store or key; deploy stopped"
    exit 1
  fi
  echo "$v up: $after"

  # HTTPS through Caddy on this host, pinned to the local interface. A host
  # may not serve a venue's public site yet (venue-test while its DNS still
  # points at the covia-stable relay); say so rather than fail.
  if grep -q "^$v\.covia\.ai" /etc/caddy/Caddyfile; then
    curl -sf --max-time 10 --resolve "$v.covia.ai:443:127.0.0.1" \
      "https://$v.covia.ai/api/v1/status" > /dev/null \
      || { echo "::error::$v is up but unreachable through Caddy: check /etc/caddy/Caddyfile"; exit 1; }
    echo "$v via Caddy OK"
  else
    echo "::notice::no public Caddy site for $v on this host; HTTPS check skipped"
  fi
done

docker image prune -f > /dev/null
echo "deployed $DEPLOY_IMAGE: $*"
