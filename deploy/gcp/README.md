# GCP Deployment (Stable Venues)

Docker-based deployment for the long-lived venue trio `venue-test.covia.ai`,
`venue-1.covia.ai` and `venue-2.covia.ai` (`35.213.147.8`, reserved static
address `venue-covia-ai`, instance in `asia-southeast1-b`).

All three venues run in a **single JVM** (one container) via the
multi-venue `venues` array config — the same mechanism as `local-dev.json`.
This exercises multi-venue hosting in production and keeps the memory
footprint of three venues within one 2 GB heap.

`venue-test.covia.ai` is intentionally open for authenticated first-use
registration (`users.autoCreate: true`). Any caller that proves control of a
valid DID is admitted on its first operation. `venue-1` and `venue-2` retain
the default closed admission policy and require explicit provisioning.

The container runs the `:stable` image, published by `publish-docker.yml`
on every push to `master`, and is redeployed automatically by
`deploy-gcp.yml`. The dev venues (`venue-3`, `venue-4`) track `develop` via
the `:latest` image; this host only changes at releases.

## Host Layout

The host was rebuilt 2026-06-11 (Docker installed, legacy screen-session
venues retired, Caddy moved under systemd):

- **Container:** `covia-venue` — one JVM (`-Xmx2g`) hosting three venues
  on ports 8080 (venue-test), 8081 (venue-1), 8082 (venue-2), published on
  `127.0.0.1` only so the plain-HTTP ports are reachable just through Caddy;
  `--restart unless-stopped` with a Docker health check; json-file log
  rotated at 5 x 50 MB
- **Config:** `/srv/covia/config.json` (mounted at `/data/config.json`)
  The deployment workflow enforces open first-use registration for
  `venue-test` while preserving all other operator settings in this file.
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

## Deploy and Admin Access (security#22)

There is no stored SSH key. `deploy-gcp.yml` signs in to GCP over GitHub OIDC
(Workload Identity Federation) and runs [`deploy.sh`](deploy.sh) on the VM as
root through an IAP tunnel with OS Login:

```bash
gcloud compute ssh <instance> --zone asia-southeast1-b \
  --tunnel-through-iap --command "echo $(base64 -w0 deploy/gcp/deploy.sh) | base64 -d | sudo bash"
```

| Piece | Value |
|-------|-------|
| Workload Identity provider | `projects/323115557788/locations/global/workloadIdentityPools/github/providers/covia-covia` |
| Provider condition | `assertion.repository_id == '813519888' && assertion.workflow_ref == 'covia-ai/covia/.github/workflows/deploy-gcp.yml@refs/heads/develop'` |
| Deploy service account | `covia-deploy-gcp@idyllic-pottery-468305-u2.iam.gserviceaccount.com` |
| Its grants (this instance only) | `roles/compute.osAdminLogin`, `roles/iap.tunnelResourceAccessor`, custom `coviaInstanceGetter` (`compute.instances.get`); plus `roles/iam.serviceAccountUser` on the VM's compute service account, which OS Login requires |

The condition names `develop` because `workflow_run` always runs the workflow
from the default branch; the image deployed is still `:stable` from `master`.
None of these values is secret, so they are written in the workflow.

OS Login is on for this instance (`enable-oslogin=TRUE`), so `ssh-keys`
metadata is ignored here. Admins log in with their Google account, which needs
`roles/compute.osAdminLogin` and `roles/iap.tunnelResourceAccessor` on the
instance (project Owners already have both):

```bash
gcloud compute ssh <instance> --zone asia-southeast1-b --tunnel-through-iap
```

## Manual Deploy

Run [`deploy.sh`](deploy.sh) as above, or on the instance:

```bash
sudo docker pull ghcr.io/covia-ai/covia:stable
sudo docker stop covia-venue && sudo docker rm covia-venue
sudo docker run -d \
  --name covia-venue \
  -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8081 -p 127.0.0.1:8082:8082 \
  -v /srv/covia:/data \
  --log-opt max-size=50m --log-opt max-file=5 \
  --restart unless-stopped \
  -e JAVA_OPTS="-Xmx2g" \
  ghcr.io/covia-ai/covia:stable \
  /data/config.json
```

## Rollback

Every published build is also tagged with its short commit SHA. To roll
back, run the manual deploy with `ghcr.io/covia-ai/covia:<sha>` instead of
`:stable`.

## Notes

- The `:stable` image is published by the image-publication workflow and deployment
  consumes that registry artifact; hosts do not build a bootstrap image
  on-box.
- The host is an `e2-medium` (2 vCPU / 4 GB) — one shared 2 GB heap for
  all three venues. Raise it only if the machine type grows.
