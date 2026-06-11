# GCP Deployment (Stable Venues)

Docker-based deployment for the long-lived venue trio `venue-test.covia.ai`,
`venue-1.covia.ai` and `venue-2.covia.ai` (`35.213.147.8`, reserved static
address `venue-covia-ai`, instance in `asia-southeast1-b`).

All three venues run in a **single JVM** (one container) via the
multi-venue `venues` array config — the same mechanism as `local-dev.json`.
This exercises multi-venue hosting in production and keeps the memory
footprint of three venues within one 2 GB heap.

The container runs the `:stable` image, published by `publish-docker.yml`
on every push to `master`, and is redeployed automatically by
`deploy-gcp.yml`. The dev venues (`venue-3`, `venue-4`) track `develop` via
the `:latest` image; this host only changes at releases.

## Host Layout

The host was rebuilt 2026-06-11 (Docker installed, legacy screen-session
venues retired, Caddy moved under systemd):

- **Container:** `covia-venue` — one JVM (`-Xmx2g`) hosting three venues
  on ports 8080 (venue-test), 8081 (venue-1), 8082 (venue-2);
  `--restart unless-stopped` with a Docker health check
- **Config:** `/srv/covia/config.json` (mounted at `/data/config.json`)
- **Data:** `/srv/covia/covia-{test,1,2}-data/` (owned by uid 1001) — each
  venue has its own directory because the identity key is always saved as
  `venue.key` next to the store file; separate directories = separate DIDs.
  Etch stores and identities persist across redeploys.
- **Caddy:** systemd `caddy.service`, config at `/etc/caddy/Caddyfile`
  (venue-test → :8080, venue-1 → :8081, venue-2 → :8082); cert storage
  migrated from the legacy root-run instance to `/var/lib/caddy`

The Caddyfile on the host:

```
{
        email admin@covia.ai
}

venue-1.covia.ai {
        reverse_proxy :8081
}

venue-2.covia.ai {
        reverse_proxy :8082
}

venue-test.covia.ai {
        reverse_proxy :8080
}
```

## GitHub Secrets Required

| Secret | Value |
|--------|-------|
| `GCP_VM_SSH_KEY` | Private key for SSH access to the VM |
| `GCP_VM_HOST` | `35.213.147.8` |
| `GCP_VM_USER` | `deploy` |

The `deploy` user is created by adding its public key to the instance
ssh-keys metadata — the GCP guest agent creates the account with
passwordless sudo:

```bash
echo "deploy:ssh-ed25519 AAAA... covia-gcp-deploy" > /tmp/deploy-key.txt
gcloud compute instances add-metadata <instance> --zone=asia-southeast1-b \
  --metadata-from-file ssh-keys=/tmp/deploy-key.txt
```

The deploy user is intentionally **not** in the docker group (docker-group
membership is root-equivalent); the workflow runs docker via its
passwordless sudo instead.

## Manual Deploy

To deploy manually on the instance (the workflow does the same):

```bash
sudo docker pull ghcr.io/covia-ai/covia:stable
sudo docker stop covia-venue && sudo docker rm covia-venue
sudo docker run -d \
  --name covia-venue \
  -p 8080:8080 -p 8081:8081 -p 8082:8082 \
  -v /srv/covia:/data \
  --restart unless-stopped \
  ghcr.io/covia-ai/covia:stable \
  java -Xmx2g -jar covia.jar /data/config.json
```

## Rollback

Every published build is also tagged with its short commit SHA. To roll
back, run the manual deploy with `ghcr.io/covia-ai/covia:<sha>` instead of
`:stable`.

## Notes

- **Bootstrap image:** the initial `:stable` tag on the host was built
  on-box from the public `latest-snapshot` release JAR (GHCR is private
  and the last tagged release, 0.0.1, predates the current API). The
  first Actions deploy after these workflows reach `master` replaces it
  with the real `:stable` channel.
- The host is an `e2-medium` (2 vCPU / 4 GB) — one shared 2 GB heap for
  all three venues. Raise it only if the machine type grows.
